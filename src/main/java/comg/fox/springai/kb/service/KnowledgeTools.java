package comg.fox.springai.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 暴露给 Agent 的工具集合：由大模型自主决定何时检索 / 写入个人知识库。
 */
@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);

    private final DocumentIngestionService ingestionService;
    private final VectorStore vectorStore;
    private final int ragTopK;
    private final double ragThreshold;
    private final double fallbackThreshold;

    public KnowledgeTools(DocumentIngestionService ingestionService,
                         VectorStore vectorStore,
                         @Value("${app.chat.rag.top-k:5}") int ragTopK,
                         @Value("${app.chat.rag.threshold:0.7}") double ragThreshold,
                         @Value("${app.chat.rag.fallback-threshold:0.3}") double fallbackThreshold) {
        this.ingestionService = ingestionService;
        this.vectorStore = vectorStore;
        this.ragTopK = ragTopK;
        this.ragThreshold = ragThreshold;
        this.fallbackThreshold = fallbackThreshold;
    }

    @Tool(description = "检索个人知识库中已保存的资料，其中包含用户的个人信息（姓名、职业、背景、偏好、习惯等）。" +
            "当用户询问与自己相关的信息（如“我是谁”“我叫什么”“我的职业/偏好是什么”），" +
            "或问题可能涉及他之前保存过的资料时都应调用。" +
            "查询词请改写为描述性短语而非用户原句：例如用户问“我是谁”，应查询“用户 姓名 职业 背景信息”。" +
            "与用户资料无关的闲聊、通用知识不要调用。")
    public String searchKnowledge(@ToolParam(description = "检索用的描述性查询短语，如“用户 姓名 职业 背景信息”") String query) {
        log.info("Agent 调用 searchKnowledge 工具，查询: {}", query);
        // 第一阶段：按常规阈值精确检索
        List<Document> docs = search(query, ragThreshold);
        boolean lowConfidence = false;
        if (docs == null || docs.isEmpty()) {
            // 第二阶段：降低阈值兜底，避免“我是谁”这类间接提问因相似度不足而漏检
            docs = search(query, fallbackThreshold);
            lowConfidence = true;
        }
        if (docs == null || docs.isEmpty()) {
            return "（知识库中未检索到与「" + query + "」相关的内容）";
        }
        StringBuilder sb = new StringBuilder();
        if (lowConfidence) {
            sb.append("（以下为低置信度召回结果，仅供参考）\n");
        }
        for (int i = 0; i < docs.size(); i++) {
            sb.append("【资料 ").append(i + 1).append("】\n")
              .append(docs.get(i).getText()).append("\n");
        }
        return sb.toString().trim();
    }

    private List<Document> search(String query, double threshold) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(ragTopK)
                .similarityThreshold(threshold)
                .build());
    }

    @Tool(description = "将一段知识内容保存到个人知识库（向量库）中，便于将来检索。" +
            "当用户在补充或纠正知识库内容、陈述事实/偏好/经验/操作步骤，或明确要求“记住/保存”时调用。")
    public String saveKnowledge(@ToolParam(description = "要保存到知识库的精炼知识文本") String content) {
        log.info("Agent 调用 saveKnowledge 工具，内容: {}", content);
        int n = ingestionService.ingestText(content);
        return "已成功保存 " + n + " 条知识到知识库。";
    }
}
