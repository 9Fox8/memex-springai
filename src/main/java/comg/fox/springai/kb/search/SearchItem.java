package comg.fox.springai.kb.search;

/**
 * 一条统一的搜索结果。不同搜索源的字段各不相同，这里收敛成模型需要的几个维度。
 *
 * @param title       标题
 * @param url         链接
 * @param site        站点名称（如「Spring」「CSDN博客」）
 * @param publishedAt 发布时间（各源格式不一，这里保留原始字符串）
 * @param snippet     摘要/正文片段（已按 max-snippet-chars 截断）
 * @param provider    命中的搜索源展示名（如「DeepSeek」「智谱」）
 */
public record SearchItem(String title, String url, String site, String publishedAt,
                         String snippet, String provider) {
}
