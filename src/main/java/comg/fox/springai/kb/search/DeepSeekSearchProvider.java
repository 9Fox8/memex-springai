package comg.fox.springai.kb.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Search API（{@code POST /v1/tools/search}），复用 {@code DEEPSEEK_API_KEY}。
 * <p>
 * 计费：每 1000 次**成功**请求 5 元（空结果、失败、超时不收费）；count 不影响单价。
 * 响应里给了 {@code rerank_score}（0-3）和 {@code authority}（0-4），
 * 这里不参与排序（多源合并按轮询保持各源代表性），但保留在字段里便于以后加权。
 * 文档：https://api-docs.deepseek.com/zh-cn/guides/search/
 */
@Component
public class DeepSeekSearchProvider extends BaseSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    static final String KEY = "deepseek";
    private static final String DEFAULT_URL = "https://api.deepseek.com/v1/tools/search";

    public DeepSeekSearchProvider(WebSearchProperties props) {
        super(props, KEY, "DeepSeek", DEFAULT_URL);
    }

    @Override
    public List<SearchItem> search(String query, int count) {
        log.info("搜索通道走的是：{}","deepseek");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", clip(query, MAX_QUERY_CHARS));
        body.put("count", Math.max(1, Math.min(50, count)));
        // false：content 恒为空，只用 summary（600-800 字，专为模型阅读设计），更省 token
        body.put("need_content", cfg != null && cfg.isNeedContent());

        JsonNode root = postJson(body);
        return parse(root);
    }

    private List<SearchItem> parse(JsonNode root) {
        if (root == null) {
            return List.of();
        }
        if (root.has("error")) {
            JsonNode err = root.path("error");
            throw new IllegalStateException("DeepSeek 搜索报错[" + err.path("code").asText() + "]："
                    + err.path("message").asText());
        }
        JsonNode array = root.path("results");
        if (!array.isArray()) {
            return List.of();
        }
        List<SearchItem> items = new ArrayList<>();
        for (JsonNode node : array) {
            // summary 是「面向模型的长摘要」，比 snippet（100-200 字，面向前端）信息更全
            String body = text(node, "summary");
            if (isBlank(body)) {
                body = text(node, "snippet");
            }
            if (isBlank(body)) {
                body = text(node, "content");
            }
            items.add(new SearchItem(
                    text(node, "title"),
                    text(node, "url"),
                    text(node, "site_name"),
                    text(node, "date"),
                    body,
                    displayName()));
        }
        return items;
    }
}
