package comg.fox.springai.kb.search;

import java.util.List;

/**
 * 搜索源抽象。新增一个搜索服务（Tavily / 博查 / Bing / SearXNG …）只需：
 * <ol>
 *   <li>实现本接口（建议继承 {@link BaseSearchProvider} 复用 HTTP/JSON 处理）</li>
 *   <li>标注 {@code @Component}，在 application.yml 的 {@code app.search.providers} 下加一段配置</li>
 * </ol>
 * 聚合、去重、缓存、失败隔离都由 {@link WebSearchService} 统一处理，不需要改。
 */
public interface SearchProvider {

    /** 配置键，对应 app.search.providers.&lt;key&gt;，如 zhipu / deepseek */
    String configKey();

    /** 展示名，写进工具输出，便于知道结果来自哪个源 */
    String displayName();

    /** 是否已启用且配置齐全（密钥、地址） */
    boolean isAvailable();

    /**
     * 执行搜索。失败时抛异常，由聚合层决定是忽略该源还是整体失败。
     *
     * @param query 搜索词
     * @param count 期望条数
     */
    List<SearchItem> search(String query, int count);
}
