package comg.fox.springai.kb.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 搜索源实现的公共基类：统一处理「读取配置 → 构造带超时的 RestClient → 发 JSON → 解析 JSON → 错误包装」。
 * 子类只需要关心「请求体长什么样」和「响应字段怎么映射成 {@link SearchItem}」。
 * <p>
 * 未启用或没配密钥时不抛异常，而是让 {@link #isAvailable()} 返回 false，
 * 由聚合层跳过该源——这样少配一个 key 不会导致整个应用起不来。
 */
abstract class BaseSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BaseSearchProvider.class);

    /** 各源对 query 长度的限制不同（智谱 70 字、DeepSeek 128 字），聚合层统一按 70 截断 */
    static final int MAX_QUERY_CHARS = 70;

    /** 配置里的这一段，未配置时为 null */
    protected final WebSearchProperties.Provider cfg;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    private final String configKey;
    private final String displayName;
    /** 未启用/未配置密钥时为 null */
    private final RestClient client;

    protected BaseSearchProvider(WebSearchProperties props, String configKey, String displayName,
                                 String defaultBaseUrl) {
        this.configKey = configKey;
        this.displayName = displayName;
        this.cfg = props.getProviders() == null ? null : props.getProviders().get(configKey);

        if (cfg == null || !cfg.isEnabled()) {
            log.info("搜索源未启用: {}", configKey);
            this.client = null;
            return;
        }
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            log.warn("搜索源 {} 已启用但未配置 api-key，将被跳过", configKey);
            this.client = null;
            return;
        }
        String baseUrl = cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank() ? defaultBaseUrl : cfg.getBaseUrl();
        Duration timeout = props.getTimeout() == null ? Duration.ofSeconds(15) : props.getTimeout();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getApiKey())
                .build();
        log.info("搜索源已启用: {}（{}）", displayName, baseUrl);
    }

    @Override
    public String configKey() {
        return configKey;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean isAvailable() {
        return client != null;
    }

    /** 发一个 JSON POST，返回解析后的响应树；失败统一包成 IllegalStateException */
    protected JsonNode postJson(Map<String, Object> body) {
        if (client == null) {
            throw new IllegalStateException(displayName + " 搜索源未配置");
        }
        String raw;
        try {
            raw = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(displayName + " 返回 " + e.getStatusCode().value()
                    + "：" + clip(e.getResponseBodyAsString(), 200), e);
        } catch (ResourceAccessException e) {
            throw new IllegalStateException(displayName + " 请求超时或网络异常", e);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            throw new IllegalStateException(displayName + " 返回内容无法解析", e);
        }
    }

    protected static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    protected static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    protected static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
