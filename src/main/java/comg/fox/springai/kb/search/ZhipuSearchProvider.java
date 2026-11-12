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
 * 智谱 Web Search API（{@code POST /paas/v4/web_search}）。
 * 复用 {@code ZHIPU_API_KEY}，不需要额外账号。
 * 文档：https://docs.bigmodel.cn/api-reference/工具-api/网络搜索
 */
@Component
public class ZhipuSearchProvider extends BaseSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    static final String KEY = "zhipu";
    private static final String DEFAULT_URL = "https://open.bigmodel.cn/api/paas/v4/web_search";

    public ZhipuSearchProvider(WebSearchProperties props) {
        super(props, KEY, "智谱", DEFAULT_URL);
    }

    @Override
    public List<SearchItem> search(String query, int count) {
        log.info("搜索通道走的是：{}","deepseek");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("search_query", clip(query, MAX_QUERY_CHARS));
        body.put("search_engine", isBlank(cfg.getEngine()) ? "search_std" : cfg.getEngine());
        // 模型已经决定要搜了，没必要再让服务端做一次意图识别
        body.put("search_intent", false);
        body.put("count", Math.max(1, Math.min(50, count)));
        if (!isBlank(cfg.getContentSize())) {
            body.put("content_size", cfg.getContentSize());
        }
        if (!isBlank(cfg.getRecency())) {
            body.put("search_recency_filter", cfg.getRecency());
        }
        if (!isBlank(cfg.getDomainFilter())) {
            body.put("search_domain_filter", cfg.getDomainFilter());
        }

        JsonNode root = postJson(body);
        return parse(root);
    }

    private List<SearchItem> parse(JsonNode root) {
        if (root == null) {
            return List.of();
        }
        if (root.has("error")) {
            JsonNode err = root.path("error");
            throw new IllegalStateException("智谱搜索报错[" + err.path("code").asText() + "]："
                    + err.path("message").asText());
        }
        JsonNode array = root.path("search_result");
        if (!array.isArray()) {
            return List.of();
        }
        List<SearchItem> items = new ArrayList<>();
        for (JsonNode node : array) {
            items.add(new SearchItem(
                    text(node, "title"),
                    text(node, "link"),
                    text(node, "media"),
                    text(node, "publish_date"),
                    text(node, "content"),
                    displayName()));
        }
        return items;
    }
}
