package comg.fox.springai.kb.search;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 多源搜索聚合层。
 * <p>
 * 职责：按配置选择搜索源 → 并发/顺序调用 → 合并去重 → 截断 → 缓存。
 * 具体的 HTTP 细节在 {@link SearchProvider} 实现里，这里不知道也不关心用的是哪家服务。
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li>{@code merge}（默认）：并发查所有已启用的源，按「轮询」合并（第 1 条来自 A、第 2 条来自 B…），
 *       按 URL 去重后取前 max-results 条。覆盖更全，代价是每次提问会调用所有源（费用 × 源数量）。</li>
 *   <li>{@code first}：按 yml 里的书写顺序逐个尝试，第一个有结果的源即返回。
 *       延迟低、费用省，但没有「多源互证」的效果。</li>
 * </ul>
 *
 * <h3>失败隔离</h3>
 * 单个源报错/超时只记日志并跳过，其余源照常返回；只有当所有源都失败时才抛异常。
 */
@Component
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    /** 缓存条目上限，超过整体清空（简单够用） */
    private static final int MAX_CACHE_ENTRIES = 200;
    /** 并发等待的额外余量：单源自身还有 RestClient 读超时，这里只是兜底 */
    private static final long AWAIT_EXTRA_MILLIS = 2_000L;

    private record CacheEntry(List<SearchItem> items, long expireAt) {}

    private final WebSearchProperties props;
    /** 已启用且配置齐全的源，顺序与 application.yml 中 providers 的书写顺序一致 */
    private final List<SearchProvider> providers;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public WebSearchService(WebSearchProperties props, List<SearchProvider> allProviders) {
        this.props = props;

        Map<String, SearchProvider> byKey = new LinkedHashMap<>();
        for (SearchProvider provider : allProviders) {
            byKey.put(provider.configKey(), provider);
        }
        List<SearchProvider> enabled = new ArrayList<>();
        if (props.getProviders() != null) {
            for (String key : props.getProviders().keySet()) {          // 保留 yml 顺序
                SearchProvider provider = byKey.get(key);
                if (provider == null) {
                    log.warn("app.search.providers.{} 没有对应的 SearchProvider 实现，已忽略", key);
                    continue;
                }
                if (provider.isAvailable()) {
                    enabled.add(provider);
                }
            }
        }
        this.providers = List.copyOf(enabled);

        if (!props.isEnabled()) {
            log.info("联网搜索未启用（app.search.enabled=false）");
        } else if (providers.isEmpty()) {
            log.warn("联网搜索已启用但没有可用的搜索源，webSearch 工具不会被暴露");
        } else {
            log.info("联网搜索就绪: mode={}, 搜索源={}, 每源 {} 条, 合并上限 {} 条",
                    props.getMode(),
                    providers.stream().map(SearchProvider::displayName).toList(),
                    props.getCount(), props.getMaxResults());
        }
    }

    public boolean isAvailable() {
        return props.isEnabled() && !providers.isEmpty();
    }

    /**
     * 执行一次多源搜索并返回去重后的结果。
     *
     * @throws IllegalStateException 未配置或所有源都失败时抛出（消息可直接展示给模型）
     */
    public List<SearchItem> search(String query) {
        if (!isAvailable()) {
            throw new IllegalStateException("联网搜索未配置（app.search.enabled / providers）");
        }
        String q = normalizeQuery(query);
        boolean firstMode = "first".equalsIgnoreCase(props.getMode());
        String cacheKey = (firstMode ? "first" : "merge") + "|" + q;

        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt() > now) {
            log.debug("联网搜索命中缓存: {}", cacheKey);
            return cached.items();
        }

        List<SearchItem> raw = firstMode ? searchFirst(q) : searchMerge(q);
        List<SearchItem> items = dedupe(raw, Math.max(1, props.getMaxResults()));

        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(cacheKey, new CacheEntry(items, now + props.getCacheTtl().toMillis()));
        log.info("联网搜索完成: query=\"{}\", 模式={}, 原始 {} 条, 去重后 {} 条",
                q, firstMode ? "first" : "merge", raw.size(), items.size());
        return items;
    }

    /** 按顺序尝试，首个有结果的源胜出 */
    private List<SearchItem> searchFirst(String query) {
        List<String> errors = new ArrayList<>();
        for (SearchProvider provider : providers) {
            try {
                List<SearchItem> items = provider.search(query, perProviderCount());
                if (!items.isEmpty()) {
                    return items;
                }
                errors.add(provider.displayName() + " 无结果");
            } catch (Exception e) {
                log.warn("搜索源 {} 失败: {}", provider.displayName(), e.getMessage());
                errors.add(provider.displayName() + "：" + e.getMessage());
            }
        }
        throw new IllegalStateException("所有搜索源均未返回结果（" + String.join("；", errors) + "）");
    }

    /** 并发查所有源，按轮询顺序合并，保证每个源都有代表 */
    private List<SearchItem> searchMerge(String query) {
        List<Future<List<SearchItem>>> futures = new ArrayList<>();
        for (SearchProvider provider : providers) {
            futures.add(executor.submit(() -> provider.search(query, perProviderCount())));
        }

        long awaitMillis = (props.getTimeout() == null ? 15_000L : props.getTimeout().toMillis())
                + AWAIT_EXTRA_MILLIS;
        List<List<SearchItem>> perProvider = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < providers.size(); i++) {
            SearchProvider provider = providers.get(i);
            List<SearchItem> items = List.of();
            try {
                items = futures.get(i).get(awaitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                errors.add(provider.displayName() + "：超时");
                log.warn("搜索源 {} 超时（{}ms），已跳过", provider.displayName(), awaitMillis);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                errors.add(provider.displayName() + "：" + cause.getMessage());
                log.warn("搜索源 {} 失败: {}", provider.displayName(), cause.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(provider.displayName() + "：被中断");
                break;
            }
            perProvider.add(items);
            total += items.size();
        }

        if (total == 0 && !errors.isEmpty()) {
            throw new IllegalStateException("所有搜索源均失败（" + String.join("；", errors) + "）");
        }

        // 轮询合并：第 1 轮取每个源的第 1 条，第 2 轮取第 2 条……
        List<SearchItem> merged = new ArrayList<>();
        int maxSize = perProvider.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < maxSize; rank++) {
            for (List<SearchItem> list : perProvider) {
                if (rank < list.size()) {
                    merged.add(list.get(rank));
                }
            }
        }
        return merged;
    }

    /**
     * 按 URL 规范形式去重（忽略协议、www.、末尾斜杠、utm 等跟踪参数），最多保留 max 条。
     * 同一篇文章常常被两个搜索引擎同时返回，去重能显著提升上下文的信息密度。
     */
    private List<SearchItem> dedupe(List<SearchItem> items, int max) {
        Map<String, SearchItem> unique = new LinkedHashMap<>();
        for (SearchItem item : items) {
            String key = normalizedUrl(item.url());
            if (key.isBlank()) {
                key = "title:" + item.title();
            }
            if (unique.containsKey(key)) {
                continue;
            }
            unique.put(key, item);
            if (unique.size() >= max) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String normalizedUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = stripTracking(uri.getRawQuery());
            return host + path + (query.isEmpty() ? "" : "?" + query);
        } catch (IllegalArgumentException e) {
            return url.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static String stripTracking(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        return Arrays.stream(rawQuery.split("&"))
                .filter(param -> {
                    String name = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
                    String lower = name.toLowerCase(Locale.ROOT);
                    return !(lower.startsWith("utm_") || lower.equals("spm")
                            || lower.equals("from") || lower.equals("ref") || lower.equals("source"));
                })
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    private int perProviderCount() {
        return Math.max(1, Math.min(50, props.getCount()));
    }

    /** 折叠空白 + 截断到各源都接受的长度 */
    private String normalizeQuery(String query) {
        String q = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        return BaseSearchProvider.clip(q, BaseSearchProvider.MAX_QUERY_CHARS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
