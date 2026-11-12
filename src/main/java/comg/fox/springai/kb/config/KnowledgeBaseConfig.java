package comg.fox.springai.kb.config;

import comg.fox.springai.kb.session.SessionBackedChatMemory;
import comg.fox.springai.runtime.RuntimeContextAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识库（RAG）与多模型对话的 Spring AI 装配。
 * 按 app.chat.models 为每个可选模型预构建一个 ChatClient，
 * 由 ChatClientRegistry 按前端传入的 modelId 分发，共享记忆、工具与提示词模板。
 */
@Configuration
@EnableConfigurationProperties(ChatModelProperties.class)
public class KnowledgeBaseConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseConfig.class);

    /**
     * 知识库向量化使用智谱（OpenAI 兼容）的 embedding-2（由 spring.ai.openai.embedding.* 指向智谱）。
     * DeepSeek 只提供对话模型、不提供 embedding 接口，故向量化仍由智谱承担（入库与检索同一 embedding 模型）。
     * 这里把 OpenAI 的 EmbeddingModel 标记为 @Primary，供 RedisVectorStore 自动配置注入。
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(@Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        return openAiEmbeddingModel;
    }

    /**
     * 为每个配置的模型构建 ChatClient：
     * - 系统提示词来自外部文件，${assistantName} / ${modelName} 按模型注入
     * - 共享同一套多轮记忆（按 sessionId 落 MySQL）、日志 advisor 与知识库工具
     */
    @Bean
    public ChatClientRegistry chatClientRegistry(OpenAiChatModel openAiChatModel,
                                                 OllamaChatModel ollamaChatModel,
                                                 JdbcTemplate jdbcTemplate,
                                                 ChatModelProperties properties,
                                                 RuntimeContextAdvisor runtimeContextAdvisor,
                                                 @Value("${app.chat.context-window:20}") int contextWindow,
                                                 @Value("${app.chat.system-prompt-location:classpath:prompts/system-prompt.txt}") Resource promptResource,
                                                 @Value("${app.chat.assistant-name:AI 助手}") String assistantName) {

        String promptTemplate;
        try {
            promptTemplate = new String(promptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取系统提示词文件: " + promptResource, e);
        }

        // provider -> 已由 starter 自动装配的底层 ChatModel
        Map<String, ChatModel> providers = new HashMap<>();
        providers.put("openai", openAiChatModel);    // 当前 spring.ai.openai.* 指向 DeepSeek
        providers.put("ollama", ollamaChatModel);    // 本地 qwen3:4b

        // 多轮记忆基于 MySQL，与模型无关，所有模型共享同一份会话历史
        ChatMemory kbChatMemory = new SessionBackedChatMemory(jdbcTemplate, contextWindow);

        ChatClientRegistry registry = new ChatClientRegistry(properties.getDefaultModel());
        for (ChatModelProperties.ModelDef def : properties.getModels()) {
            ChatModel model;
            if ("openai-compatible".equals(def.getProvider())) {
                model = buildOpenAiCompatibleModel(def);   // 独立厂商，按自己的地址/密钥构造
            } else {
                model = providers.get(def.getProvider());  // starter 自动装配的模型
            }
            if (model == null) {
                log.warn("模型 {} 的 provider={} 无对应 ChatModel，已跳过", def.getId(), def.getProvider());
                continue;
            }
            String modelName = def.getModelName() != null ? def.getModelName() : def.getName();
            String systemPrompt = promptTemplate.replace("${assistantName}", assistantName)
                    .replace("${modelName}", modelName == null ? "" : modelName);

            ChatClient client = ChatClient.builder(model)
                    .defaultSystem(systemPrompt)
                    // 每轮注入「当前时间」等运行时信息：模型没有时钟，不注入就只会去猜或去联网搜
                    .defaultAdvisors(runtimeContextAdvisor)
                    .defaultAdvisors(new SimpleLoggerAdvisor())
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(kbChatMemory).build())
                    .build();

            registry.register(def.getId(), def.getName(), def.getDescription(), client);
            log.info("已装配对话模型: id={}, provider={}, modelName={}", def.getId(), def.getProvider(), modelName);
        }
        if (registry.isEmpty()) {
            throw new IllegalStateException(
                    "app.chat.models 未配置任何可用模型（provider 支持 openai / ollama / openai-compatible）");
        }
        return registry;
    }

    /**
     * 手动构造独立的 OpenAI 兼容 ChatModel（Spring AI 2.0 由 OpenAiChatOptions 直接承载连接信息）。
     * 工具调用在 2.0 由 ChatClient 层的 ToolCallingAdvisor 驱动，与 ChatModel 是否手动构造无关。
     */
    private ChatModel buildOpenAiCompatibleModel(ChatModelProperties.ModelDef def) {
        if (def.getBaseUrl() == null || def.getBaseUrl().isBlank()
                || def.getApiKey() == null || def.getApiKey().isBlank()
                || def.getModel() == null || def.getModel().isBlank()) {
            throw new IllegalStateException("模型 " + def.getId()
                    + " 使用 openai-compatible，必须配置 base-url / api-key / model");
        }
        log.info("手动装配 OpenAI 兼容模型: id={}, baseUrl={}, model={}",
                def.getId(), def.getBaseUrl(), def.getModel());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(def.getBaseUrl())
                .apiKey(def.getApiKey())
                .model(def.getModel())
                .temperature(def.getTemperature() == null ? 0.7d : def.getTemperature())
                .build();

        return OpenAiChatModel.builder().options(options).build();
    }

    /** 默认 ChatClient：保留 kbChatClient Bean，兼容既有按名称注入的场景 */
    @Bean
    public ChatClient kbChatClient(ChatClientRegistry registry) {
        return registry.getDefault();
    }
}
