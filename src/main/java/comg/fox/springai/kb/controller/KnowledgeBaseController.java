package comg.fox.springai.kb.controller;

import comg.fox.springai.kb.config.ChatClientRegistry;
import comg.fox.springai.kb.config.ChatGateway;
import comg.fox.springai.kb.service.DocumentIngestionService;
import comg.fox.springai.kb.service.FileTools;
import comg.fox.springai.kb.service.KnowledgeTools;
import comg.fox.springai.kb.service.WorkspaceService;
import comg.fox.springai.kb.search.WebSearchProperties;
import comg.fox.springai.kb.search.WebSearchTools;
import comg.fox.springai.runtime.SystemInfoTools;
import comg.fox.springai.kb.session.ChatMessage;
import comg.fox.springai.kb.session.ChatSession;
import comg.fox.springai.kb.session.Workspace;
import comg.fox.springai.kb.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.content.Media;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/kb")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    /** 响应头：本次对话最终生效的会话ID（后端可能因会话失效而新建） */
    private static final String SESSION_ID_HEADER = "X-Session-Id";

    /** 单文件 / 单次请求的大小上限，与 application.yml 的 spring.servlet.multipart.* 保持一致 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_REQUEST_BYTES = 50L * 1024 * 1024;

    private final ChatClientRegistry chatClientRegistry;
    private final ChatGateway chatGateway;
    private final DocumentIngestionService ingestionService;
    private final SessionService sessionService;
    private final WorkspaceService workspaceService;
    private final KnowledgeTools knowledgeTools;
    private final WebSearchTools webSearchTools;
    private final WebSearchProperties webSearchProperties;
    private final SystemInfoTools systemInfoTools;

    @Value("${app.file.root:./workspace}")
    private String defaultFileRoot;

    /** 工作区沙箱之外额外允许读写的本地目录（挂载白名单，如个人知识库），多个用英文逗号分隔 */
    @Value("${app.file.extra-roots:}")
    private String extraRootsRaw;

    public KnowledgeBaseController(ChatClientRegistry chatClientRegistry,
                                   ChatGateway chatGateway,
                                   DocumentIngestionService ingestionService,
                                   SessionService sessionService,
                                   WorkspaceService workspaceService,
                                   KnowledgeTools knowledgeTools,
                                   WebSearchTools webSearchTools,
                                   WebSearchProperties webSearchProperties,
                                   SystemInfoTools systemInfoTools) {
        this.chatClientRegistry = chatClientRegistry;
        this.chatGateway = chatGateway;
        this.ingestionService = ingestionService;
        this.sessionService = sessionService;
        this.workspaceService = workspaceService;
        this.knowledgeTools = knowledgeTools;
        this.webSearchTools = webSearchTools;
        this.webSearchProperties = webSearchProperties;
        this.systemInfoTools = systemInfoTools;
    }

    /** 上传文档并摄入知识库（支持多选） */
    @PostMapping("/upload")
    public String upload(@RequestParam("files") MultipartFile[] files) {
        log.info("收到文件上传请求，共 {} 个文件", files.length);
        int total = 0;
        for (MultipartFile file : files) {
            try {
                total += ingestionService.ingest(file);
            } catch (Exception e) {
                log.error("文件 [{}] 摄入失败", file.getOriginalFilename(), e);
                return "摄入失败 [" + file.getOriginalFilename() + "]: " + e.getMessage();
            }
        }
        log.info("文件摄入完成，共写入 {} 个文本块", total);
        return "成功摄入 " + total + " 个文本块";
    }

    /** 前端模型下拉框的数据源：可选模型清单 */
    @GetMapping("/models")
    public List<ChatClientRegistry.ModelOption> listModels() {
        return chatClientRegistry.list();
    }

    // ===================== 会话（窗口）管理 =====================

    /** 新建会话；workspaceId 为空表示默认空间 */
    @PostMapping("/sessions")
    public ChatSession createSession(@RequestParam(required = false) String workspaceId) {
        return sessionService.createSession(workspaceId);
    }

    // ===================== 工作区管理 =====================

    /** 列出当前用户的所有工作区 */
    @GetMapping("/workspaces")
    public List<Workspace> listWorkspaces() {
        return workspaceService.listWorkspaces();
    }

    /** 创建工作区：name 名称，path 本地目录路径（绝对或相对均可，后端会校验并创建目录） */
    @PostMapping("/workspaces")
    public Workspace createWorkspace(@RequestParam String name, @RequestParam String path) {
        return workspaceService.createWorkspace(name, path);
    }

    /** 重命名工作区 */
    @PutMapping("/workspaces/{id}/name")
    public Workspace renameWorkspace(@PathVariable String id, @RequestParam String name) {
        return workspaceService.renameWorkspace(id, name);
    }

    /** 删除工作区（级联逻辑删除其下所有会话与消息，避免孤儿） */
    @DeleteMapping("/workspaces/{id}")
    public Map<String, Object> deleteWorkspace(@PathVariable String id) {
        workspaceService.deleteWorkspace(id);
        return Map.of("ok", true);
    }

    /** 列出当前用户在某工作区下的会话；workspaceId 为空表示默认空间 */
    @GetMapping("/sessions")
    public List<ChatSession> listSessions(@RequestParam(required = false) String workspaceId) {
        return sessionService.listSessions(workspaceId);
    }

    /** 获取某会话的历史消息（用于切换窗口时回显） */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> getMessages(@PathVariable String sessionId) {
        return sessionService.getMessages(sessionId);
    }

    /** 删除会话（逻辑删除，同时逻辑删除其全部消息） */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return Map.of("ok", true);
    }

    /** 重命名会话；会话不存在或已删除时返回 null */
    @PutMapping("/sessions/{sessionId}/title")
    public ChatSession renameSession(@PathVariable String sessionId, @RequestParam String title) {
        return sessionService.renameSession(sessionId, title);
    }

    // ===================== 单对话框 Agent 对话 =====================

    /**
     * 单对话框全能 Agent（支持附件）：文本 + 可选文件附件，且带多轮记忆。
     * 附件内容作为上下文喂给模型（本轮“检索/参考”），同时暴露 saveAttachment 工具，
     * 由模型按用户意图自行决定是否把附件写入知识库（长期“存储”）。
     */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String message,
                             @RequestParam(value = "sessionId", required = false) String sessionId,
                             @RequestParam(value = "modelId", required = false) String modelId,
                             @RequestParam(value = "workspaceId", required = false) String workspaceId,
                             @RequestParam(value = "files", required = false) MultipartFile[] files,
                             HttpServletResponse response) {

        // 附件大小校验放在最前面：超限直接拒绝，避免先建会话/解析文件再失败
        checkAttachmentSize(files);

        // 按前端选中的模型分发；未传或 id 未知时由注册表回退到默认模型
        log.info("本轮对话使用模型: {}",
                (modelId == null || modelId.isBlank()) ? chatClientRegistry.getDefaultId() : modelId);

        // 会话不存在或已被逻辑删除则自动创建新会话（getSession 已按 delete_flag 过滤），避免产生孤儿消息
        if (sessionId == null || sessionId.isBlank() || sessionService.getSession(sessionId) == null) {
            sessionId = sessionService.createSession(workspaceId).id;
        }
        final String sid = sessionId;   // lambda 只能捕获最终变量
        // 把最终生效的会话ID回传给前端：会话失效时这里会静默新建，前端只有拿到新ID才不会
        // 继续用旧ID发消息（否则每条消息都会被当成新会话，会话列表越来越长）
        response.setHeader(SESSION_ID_HEADER, sid);
        boolean isFirstMessage = sessionService.getMessages(sid).isEmpty();

        StringBuilder userContent = new StringBuilder(message);
        final Map<String, String> fileTexts = new HashMap<>();   // 文本类附件内容（供 RAG/引用）
        final Map<String, byte[]> fileBytes = new HashMap<>();    // 附件原始字节（供落盘保存）
        final List<Media> mediaList = new ArrayList<>();          // 图片等多模态内容（供模型识别）

        if (files != null) {
            for (MultipartFile file : files) {
                String name = file.getOriginalFilename();
                String ct = file.getContentType();
                try {
                    if (ct != null && ct.startsWith("image/")) {
                        // 图片：作为多模态内容直接交给模型识别，并保留字节用于落盘保存
                        byte[] bytes = file.getBytes();
                        mediaList.add(Media.builder()
                                .mimeType(MimeTypeUtils.parseMimeType(ct))
                                .data(bytes)
                                .build());
                        fileBytes.put(name, bytes);
                        userContent.append("\n\n[附件图片: ").append(name)
                                .append("]（已作为图片内容提供给模型识别）");
                        log.info("对话携带图片附件: {}", name);
                    } else {
                        // 文本/文档类：提取文本作为上下文，并保留字节用于落盘
                        String text = ingestionService.extractText(file);
                        fileTexts.put(name, text);
                        fileBytes.put(name, file.getBytes());
                        userContent.append("\n\n[附件文件: ").append(name).append("]\n").append(text);
                        log.info("对话携带附件: {} (字符数 {})", name, text.length());
                    }
                } catch (Exception e) {
                    log.error("附件解析失败: {}", name, e);
                    userContent.append("\n\n[附件文件: ").append(name)
                            .append(" 解析失败: ").append(e.getMessage()).append("]");
                }
            }
        }

        // 附件工具（saveAttachment / saveAttachmentFile）在解析出文件操作根目录后定义，见下方

        // 更新会话活跃时间；首条消息用作会话标题
        sessionService.touch(sid, isFirstMessage ? message : null);

        // 解析本次对话的文件操作根目录：优先使用工作区绑定的目录，否则用默认空间根目录
        String root = defaultFileRoot;
        if (workspaceId != null && !workspaceId.isBlank()) {
            Workspace ws = workspaceService.getWorkspace(workspaceId);
            if (ws != null) root = ws.path;
        }
        // 解析额外挂载目录（如个人知识库），模型可通过绝对路径读写这些目录
        List<String> extraRoots = new ArrayList<>();
        for (String dir : extraRootsRaw.split(",")) {
            if (dir != null && !dir.isBlank()) extraRoots.add(dir.trim());
        }
        FileTools workspaceFileTools = new FileTools(root, extraRoots);

        // 每个请求动态生成的工具：把本次对话里的附件保存到知识库（文本入库 / 二进制落盘）
        Object attachmentTools = new Object() {
            @Tool(description = "将本次对话中某个文本/文档附件的内容向量化存入个人知识库，便于以后检索。" +
                    "当用户要求保存/入库/记住附件的文字内容时调用。传入附件文件名。")
            public String saveAttachment(@ToolParam(description = "要保存的附件文件名") String filename) {
                String text = fileTexts.get(filename);
                if (text == null) return "未找到名为 " + filename + " 的附件";
                int n = ingestionService.ingestText(text);
                return "已将附件 " + filename + " 存入知识库，共 " + n + " 个文本块";
            }

            @Tool(description = "将本次对话中的某个附件（图片/文档）作为文件保存到本地工作区或挂载目录，" +
                    "便于长期留存。当用户要求“把附件/图片保存到本地/存成文件”时调用。" +
                    "savePath 为相对工作区根的相对路径或挂载目录的绝对路径（如 img/photo.png 或 F:/知识库/a.png）。")
            public String saveAttachmentFile(@ToolParam(description = "要保存的附件文件名") String filename,
                                             @ToolParam(description = "保存目标路径（相对工作区根或挂载目录绝对路径）") String savePath) {
                byte[] data = fileBytes.get(filename);
                if (data == null) return "未找到名为 " + filename + " 的附件";
                return workspaceFileTools.writeBytes(savePath, data);
            }
        };

        // 工具在 prompt 级注入，使文件沙箱根目录能按工作区动态切换（知识库 + 附件 + 文件操作）
        // 联网搜索按开关动态加入：未配置密钥时不暴露给模型，避免模型每次都调用一个必然失败的工具
        // systemInfoTools 常驻：本机 IP / 系统版本这类信息只能现场取，模型猜不出来
        List<Object> tools = new ArrayList<>(
                List.of(knowledgeTools, attachmentTools, workspaceFileTools, systemInfoTools));
        if (webSearchProperties.isEnabled() && webSearchTools.isAvailable()) {
            tools.add(webSearchTools);
        }
        Function<String, Flux<String>> invoke = mid ->
                chatClientRegistry.get(mid).prompt()
                        .user(u -> {
                            u.text(userContent.toString());
                            if (!mediaList.isEmpty()) {
                                u.media(mediaList.toArray(new Media[0]));
                            }
                        })
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sid))
                        .tools(tools.toArray())
                        .stream()
                        .content();
        // 持久化兜底：客户端中途断开（如刷新页面）会取消流式，advisor 的完成回调不再执行，
        // assistant 消息将永远不落库（刷新后看不到）。这里累积已输出内容，仅在取消时补写；
        // 正常完成时由 advisor 落库，本回调不触发，不会双写。
        StringBuilder partial = new StringBuilder();
        return chatGateway.stream(modelId, invoke)
                .doOnNext(partial::append)
                .doOnCancel(() -> {
                    if (partial.length() > 0) {
                        log.warn("客户端中断连接，补写已生成的部分回复（{} 字符），sessionId={}",
                                partial.length(), sid);
                        sessionService.saveMessage(sid, "ASSISTANT", partial.toString());
                    }
                });
    }

    /** 附件大小校验：单文件 20MB、单次请求 50MB，超限直接拒绝并返回 400 */
    private void checkAttachmentSize(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return;
        }
        long total = 0;
        for (MultipartFile file : files) {
            long size = file.getSize();
            if (size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("附件过大：" + file.getOriginalFilename()
                        + "（" + toMb(size) + "MB），单个文件上限 " + toMb(MAX_FILE_BYTES) + "MB");
            }
            total += size;
        }
        if (total > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("附件总大小 " + toMb(total) + "MB，单次请求上限 "
                    + toMb(MAX_REQUEST_BYTES) + "MB");
        }
    }

    private static String toMb(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
    }
}
