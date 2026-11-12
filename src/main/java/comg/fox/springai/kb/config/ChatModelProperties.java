package comg.fox.springai.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话模型与网关策略配置，绑定 app.chat。
 *
 * provider 的取值：
 * - openai：使用 starter 自动装配的 OpenAiChatModel（当前 spring.ai.openai.* 指向 DeepSeek）
 * - ollama：使用 starter 自动装配的 OllamaChatModel（本地 qwen3:4b）
 * - openai-compatible：按本项的 baseUrl / apiKey / model 手动构造一个独立 OpenAiChatModel，
 *   用于接入第二个及以上的 OpenAI 兼容厂商（如智谱），新增厂商只需加 yml，无需改 Java
 *
 * modelName 注入系统提示词里的 ${modelName}，让每个模型回答身份时如实自报。
 * retry / fallback / timeout 由 ChatGateway 消费，控制失败重试、故障切换与空闲超时。
 */
@ConfigurationProperties(prefix = "app.chat")
public class ChatModelProperties {

    public static class ModelDef {
        private String id;
        private String name;
        private String description;
        private String provider;
        private String modelName;

        // 仅 openai-compatible 需要：独立的接入地址、密钥、模型标识、温度
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
    }

    /** 单个模型的失败重试策略 */
    public static class RetryPolicy {
        private int maxAttempts = 3;                        // 含首次在内的最多尝试次数
        private Duration backoff = Duration.ofMillis(500);  // 退避基数，按 2^n 递增

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getBackoff() { return backoff; }
        public void setBackoff(Duration backoff) { this.backoff = backoff; }
    }

    /** 故障切换策略 */
    public static class FallbackPolicy {
        // 切换顺序：从「当前选中模型在链中的位置」往后尝试，最后一项为最终兜底
        private List<String> chain = new ArrayList<>(List.of("ollama", "zhipu", "deepseek"));
        private boolean notice = true;   // 是否在 SSE 中提示用户「正在切换模型」

        public List<String> getChain() { return chain; }
        public void setChain(List<String> chain) { this.chain = chain; }
        public boolean isNotice() { return notice; }
        public void setNotice(boolean notice) { this.notice = notice; }
    }

    /** 超时策略：流式响应按「两次输出之间的空闲时间」计算，避免上游卡死时请求永久挂起 */
    public static class TimeoutPolicy {
        private Duration idle = Duration.ofSeconds(60);

        public Duration getIdle() { return idle; }
        public void setIdle(Duration idle) { this.idle = idle; }
    }

    private List<ModelDef> models = new ArrayList<>();
    private String defaultModel = "deepseek";
    private RetryPolicy retry = new RetryPolicy();
    private FallbackPolicy fallback = new FallbackPolicy();
    private TimeoutPolicy timeout = new TimeoutPolicy();

    public List<ModelDef> getModels() { return models; }
    public void setModels(List<ModelDef> models) { this.models = models; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public RetryPolicy getRetry() { return retry; }
    public void setRetry(RetryPolicy retry) { this.retry = retry; }
    public FallbackPolicy getFallback() { return fallback; }
    public void setFallback(FallbackPolicy fallback) { this.fallback = fallback; }
    public TimeoutPolicy getTimeout() { return timeout; }
    public void setTimeout(TimeoutPolicy timeout) { this.timeout = timeout; }
}
