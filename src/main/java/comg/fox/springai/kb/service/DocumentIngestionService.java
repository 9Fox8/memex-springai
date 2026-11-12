package comg.fox.springai.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文档摄入（ETL）服务：读取 -> 切分 -> 向量化 -> 入库。
 * <p>
 * 文本类文档（PDF / Word / HTML / TXT / MD 等）由 Apache Tika 统一解析；
 * 图片 / 视频暂未支持，需接入多模态模型生成文字描述后再入库（见 {@code ingest} 中的类型守卫）。
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 摄入单个文件，返回写入向量库的文本块数量。
     *
     * @throws UnsupportedOperationException 当文件为图片/视频等暂不支持的类型时
     * @throws IOException                  读取文件失败
     */
    public int ingest(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        log.info("开始摄入文件: {} (contentType={})", filename, contentType);

        // 类型守卫：图片/视频需要先经多模态模型转成文本描述，这里先拒绝并给出提示
        if (contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            log.warn("跳过暂不支持的类型（图片/视频）: {}", filename);
            throw new UnsupportedOperationException(
                    "图片/视频暂未支持，需接入多模态模型进行描述后入库: " + filename);
        }

        TikaDocumentReader reader = new TikaDocumentReader(
                new InputStreamResource(file.getInputStream(), filename));
        List<Document> documents = reader.get();
        log.info("文件 {} 经 Tika 解析得到 {} 个原始文档", filename, documents.size());

        // 切分为适合检索的文本块
        List<Document> chunks = splitter.apply(documents);
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source", filename);
            chunk.getMetadata().put("kb", "personal");
        }

        vectorStore.add(chunks);
        log.info("文件 {} 已切分为 {} 个文本块并写入向量库", filename, chunks.size());
        return chunks.size();
    }

    /**
     * 仅解析文件文本（不入库），用于把附件内容作为上下文喂给模型，
     * 或供其按需调用工具存入知识库。
     */
    public String extractText(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            throw new UnsupportedOperationException("图片/视频暂未支持: " + filename);
        }
        TikaDocumentReader reader = new TikaDocumentReader(
                new InputStreamResource(file.getInputStream(), filename));
        StringBuilder sb = new StringBuilder();
        for (Document d : reader.get()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(d.getText());
        }
        return sb.toString();
    }

    /**
     * 直接把一段文本写入向量库（按块切分，兼顾长短文本）。
     * 用于对话式沉淀：用户日常输入经模型判定、或把附件内容存库时调用。
     *
     * @return 写入的文本块数量
     */
    public int ingestText(String text) {
        List<Document> chunks = splitter.apply(List.of(new Document(text)));
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source", "chat");
            chunk.getMetadata().put("kb", "personal");
            chunk.getMetadata().put("type", "conversational");
        }
        vectorStore.add(chunks);
        // 只打长度 + 前 40 字预览：知识原文可能包含个人隐私，避免整段落进日志
        log.info("通过对话新增 {} 个知识块（原文 {} 字符）: {}", chunks.size(), text == null ? 0 : text.length(),
                preview(text, 40));
        return chunks.size();
    }

    /** 日志预览：截断并按空白折叠，避免多行内容刷屏 */
    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
