package comg.fox.springai.config;

import comg.fox.springai.advisor.ChatLogAdvisor;
import comg.fox.springai.repository.ChatMessageLogRepository;
import comg.fox.springai.runtime.RuntimeContextAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    //openAi模型配置
    @Bean
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel,
                                      ChatMemory chatMemory,
                                      ChatMessageLogRepository chatMessageLogRepository,
                                      RuntimeContextAdvisor runtimeContextAdvisor) {
        return ChatClient.builder(openAiChatModel)    //创建ChatClient对象，以及设置模型为model
//                .defaultAdvisors(new MySimpleLoggerAdvisor())   //添加一个日志拦截器
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(runtimeContextAdvisor)   //注入当前时间等运行时信息
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())//添加对话记忆拦截器
                .defaultAdvisors(new ChatLogAdvisor(chatMessageLogRepository, "openai"))//业务侧聊天记录
                .build();   //构建chatClient对象
    }

    //ollama模型配置
    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel,
                                      ChatMemory chatMemory,
                                      ChatMessageLogRepository chatMessageLogRepository,
                                      RuntimeContextAdvisor runtimeContextAdvisor) {
        return ChatClient.builder(ollamaChatModel)    //创建ChatClient对象，以及设置模型为model
//                .defaultAdvisors(new MySimpleLoggerAdvisor())   //添加一个日志拦截器
                .defaultAdvisors(new SimpleLoggerAdvisor())   //添加一个日志拦截器
                .defaultAdvisors(runtimeContextAdvisor)   //注入当前时间等运行时信息
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultAdvisors(new ChatLogAdvisor(chatMessageLogRepository, "ollama"))//业务侧聊天记录
                .build();   //构建chatClient对象
    }

    //openAi模型配置（面试官）
    @Bean
    public ChatClient chatClient3(OpenAiChatModel openAiChatModel,
                                 ChatMessageLogRepository chatMessageLogRepository,
                                 RuntimeContextAdvisor runtimeContextAdvisor) {

        String systemTemplate = """
                    你是一位严谨友好的资深技术面试官，擅长 Java 后端与 AI 应用开发方向的面试。
                
                    面试规则：
                    1. 一次只问一个问题，问完等待候选人回答，不要一次抛出多个问题。
                    2. 优先基于「面试参考资料」中的内容出题；参考资料中没有的，可结合你的经验延伸提问，并说明这是延伸问题。
                    3. 候选人回答后，先用一句话点评（答得好 / 部分正确 / 偏题），再决定：追问细节、提升难度、或切换到下一个知识点。
                    4. 难度适配：easy=概念题为主；medium=原理 + 使用场景；hard=原理 + 踩坑 + 系统设计。
                    5. 语气专业简洁，像真实面试官：不讨好、不剧透答案、不重复候选人说过的原话。
                    6. 候选人明确说「结束面试」时，停止提问，用 100 字以内做收尾总结。
                    """;

        return ChatClient.builder(openAiChatModel)    //创建ChatClient对象，以及设置模型为model
//                .defaultAdvisors(new MySimpleLoggerAdvisor())   //添加一个日志拦截器
                .defaultAdvisors(new SimpleLoggerAdvisor())   //添加一个日志拦截器
                .defaultAdvisors(runtimeContextAdvisor)   //注入当前时间等运行时信息
                .defaultAdvisors(new ChatLogAdvisor(chatMessageLogRepository, "openai"))//业务侧聊天记录
                .defaultSystem(systemTemplate)
                .build();   //构建chatClient对象
    }
}
