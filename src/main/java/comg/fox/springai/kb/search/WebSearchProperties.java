package comg.fox.springai.kb.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联网搜索配置，绑定 app.search。
 * <p>
 * 支持多源：{@code providers} 下每个 key 对应一个 {@link SearchProvider} 实现的 configKey，
 * yml 里的书写顺序就是 {@code mode=first} 时的尝试顺序。
 * 新增搜索服务只需加一段配置 + 一个实现类，聚合/去重/缓存/失败隔离都不用改。
 */
@Component
@ConfigurationProperties(prefix = "app.search")
public class WebSearchProperties {

    /** 总开关：false 时不向模型暴露 webSearch 工具 */
    private boolean enabled = true;

    /** 聚合模式：merge=并发查所有源后合并去重；first=按配置顺序逐个尝试，首个有结果即返回（更省费用） */
    private String mode = "merge";

    /** 每个源请求的条数（1-50） */
    private int count = 5;

    /** 合并去重后最多交给模型几条，防止上下文被撑爆 */
    private int maxResults = 8;

    /** 单次搜索超时（连接 + 读取） */
    private Duration timeout = Duration.ofSeconds(15);

    /** 相同 query 的结果缓存时长，避免模型反复检索重复计费 */
    private Duration cacheTtl = Duration.ofMinutes(5);

    /** 单条摘要最多注入多少字符 */
    private int maxSnippetChars = 600;

    /** 各搜索源的独立配置，key = SearchProvider.configKey()，顺序即 first 模式的优先级 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** 单个搜索源的配置。字段按源不同而不同，用不到的留空即可。 */
    public static class Provider {
        private boolean enabled = true;
        private String apiKey;
        private String baseUrl;

        // ===== 智谱 Web Search 专有 =====
        /** 搜索引擎：search_std / search_pro / search_pro_sogou / search_pro_quark */
        private String engine;
        /** 摘要详细程度：medium / high */
        private String contentSize;
        /** 时间范围：oneDay / oneWeek / oneMonth / oneYear / noLimit */
        private String recency;
        /** 白名单域名 */
        private String domainFilter;

        // ===== DeepSeek Search 专有 =====
        /** 是否返回网页正文（false 时 content 恒为空，更省 token） */
        private boolean needContent = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
        public String getContentSize() { return contentSize; }
        public void setContentSize(String contentSize) { this.contentSize = contentSize; }
        public String getRecency() { return recency; }
        public void setRecency(String recency) { this.recency = recency; }
        public String getDomainFilter() { return domainFilter; }
        public void setDomainFilter(String domainFilter) { this.domainFilter = domainFilter; }
        public boolean isNeedContent() { return needContent; }
        public void setNeedContent(boolean needContent) { this.needContent = needContent; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Duration getCacheTtl() { return cacheTtl; }
    public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    public int getMaxSnippetChars() { return maxSnippetChars; }
    public void setMaxSnippetChars(int maxSnippetChars) { this.maxSnippetChars = maxSnippetChars; }
    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }
}
