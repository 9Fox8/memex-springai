package comg.fox.springai.kb.session;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * 会话（窗口）管理：创建 / 列表 / 逻辑删除 / 读取消息 / 更新活跃时间。
 * 会话与消息均落 MySQL，按 userId 隔离。
 *
 * <p>删除统一采用逻辑删除：delete_flag = 0 正常 / 1 已删除，不做物理 DELETE，
 * 所有查询与更新都只作用于未删除的记录，便于恢复与审计。
 *
 * <p>应用启动时自动建表（幂等），表与字段均带 MySQL COMMENT 注释：
 * <ul>
 *   <li>chat_session：会话窗口表（会话ID / 所属用户 / 标题 / 创建与活跃时间 / 删除标识）</li>
 *   <li>chat_message：会话消息表（自增主键 / 所属会话 / 所属用户 / 角色 / 正文 / 时间 / 删除标识）</li>
 * </ul>
 * 对已存在的旧表会自动补齐 delete_flag 列与表注释（CREATE TABLE IF NOT EXISTS 不会改动旧表结构）。
 */
@Service
public class SessionService {

    /** 当前登录用户。TODO: 接入权限体系后改为真实登录用户 */
    public static final String CURRENT_USER_ID = "001";

    private final JdbcTemplate jdbc;

    public SessionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 应用启动时确保表存在（IF NOT EXISTS，可重复执行） */
    @PostConstruct
    public void initSchema() {
        // 会话窗口表：一个会话对应前端一个聊天窗口
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_session (
                    id VARCHAR(64) NOT NULL COMMENT '会话ID(UUID)',
                    user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID',
                    title VARCHAR(255) NOT NULL DEFAULT '新会话' COMMENT '会话标题(取首条用户消息)',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
                    delete_flag TINYINT NOT NULL DEFAULT 0 COMMENT '删除标识:0-正常,1-已删除(逻辑删除)',
                    PRIMARY KEY (id)
                ) COMMENT='会话窗口表';
                """);

        // 会话消息表：多轮记忆与前端历史回显都读这张表
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    id BIGINT AUTO_INCREMENT COMMENT '自增主键',
                    session_id VARCHAR(64) NOT NULL COMMENT '所属会话ID',
                    user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID',
                    role VARCHAR(16) NOT NULL COMMENT '消息角色:USER/ASSISTANT',
                    content TEXT COMMENT '消息正文',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
                    delete_flag TINYINT NOT NULL DEFAULT 0 COMMENT '删除标识:0-正常,1-已删除(逻辑删除)',
                    PRIMARY KEY (id),
                    INDEX idx_chat_message_session (session_id, created_at),
                    INDEX idx_chat_message_session_id (session_id, id)
                ) COMMENT='会话消息表';
                """);

        // 兼容已存在的旧表：自动补 delete_flag 列与新版 workspace_id 列；表注释为幂等操作
        ensureDeleteFlag("chat_session");
        ensureDeleteFlag("chat_message");
        ensureColumn("chat_session", "workspace_id",
                "ADD COLUMN workspace_id VARCHAR(64) DEFAULT NULL COMMENT '所属工作区ID，NULL表示默认空间'");
        // 消息按自增 id 排序（created_at 是秒级 DATETIME，同一秒内的多条消息会并列），补对应索引
        ensureIndex("chat_message", "idx_chat_message_session_id",
                "CREATE INDEX idx_chat_message_session_id ON chat_message (session_id, id)");
        jdbc.execute("ALTER TABLE chat_session COMMENT = '会话窗口表'");
        jdbc.execute("ALTER TABLE chat_message COMMENT = '会话消息表'");
    }

    /** 旧表若缺指定索引则补齐（索引缺失只影响排序/查询性能，不影响正确性） */
    private void ensureIndex(String table, String indexName, String ddl) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName);
        if (count != null && count == 0) {
            jdbc.execute(ddl);
        }
    }

    /** 旧表若缺指定列则补齐，避免查询报 Unknown column */
    private void ensureColumn(String table, String column, String ddl) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE " + table + " " + ddl);
        }
    }

    /** 旧表若缺 delete_flag 列则补齐，避免查询报 Unknown column */
    private void ensureDeleteFlag(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'delete_flag'",
                Integer.class, table);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE " + table +
                    " ADD COLUMN delete_flag TINYINT NOT NULL DEFAULT 0 COMMENT '删除标识:0-正常,1-已删除(逻辑删除)'");
        }
    }

    public ChatSession createSession(String workspaceId) {
        String id = UUID.randomUUID().toString();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        String ws = (workspaceId == null || workspaceId.isBlank()) ? null : workspaceId;
        jdbc.update("INSERT INTO chat_session(id, user_id, title, created_at, updated_at, workspace_id, delete_flag) " +
                        "VALUES (?,?,?,?,?,?,0)",
                id, CURRENT_USER_ID, "新会话", now, now, ws);
        return getSession(id);
    }

    /**
     * 会话列表：只返回未删除的会话；workspaceId 为空表示“默认空间”（workspace_id IS NULL）。
     */
    public List<ChatSession> listSessions(String workspaceId) {
        boolean hasWs = workspaceId != null && !workspaceId.isBlank();
        String sql = "SELECT id, user_id, title, created_at, updated_at, workspace_id FROM chat_session " +
                "WHERE user_id = ? AND delete_flag = 0 " +
                (hasWs ? "AND workspace_id = ? " : "AND workspace_id IS NULL ") +
                "ORDER BY updated_at DESC";
        return hasWs
                ? jdbc.query(sql, (rs, i) -> mapSession(rs), CURRENT_USER_ID, workspaceId)
                : jdbc.query(sql, (rs, i) -> mapSession(rs), CURRENT_USER_ID);
    }

    /** 查询单个会话；已逻辑删除的会话视为不存在 */
    public ChatSession getSession(String id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, user_id, title, created_at, updated_at, workspace_id FROM chat_session " +
                            "WHERE id = ? AND delete_flag = 0",
                    (rs, i) -> mapSession(rs), id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 读取会话历史：只返回未删除消息，且会话本身必须未删除 */
    public List<ChatMessage> getMessages(String sessionId) {
        return jdbc.query(
                "SELECT role, content FROM chat_message " +
                        "WHERE session_id = ? AND delete_flag = 0 " +
                        "AND EXISTS (SELECT 1 FROM chat_session s WHERE s.id = ? AND s.delete_flag = 0) " +
                        // 用自增 id 排序：created_at 为秒级 DATETIME，同一秒内写入的多条消息会并列，
                        // 按它排序会出现「先答后问」或窗口截断截错一条
                        "ORDER BY id ASC",
                (rs, i) -> {
                    ChatMessage m = new ChatMessage();
                    m.role = rs.getString("role");
                    m.content = rs.getString("content");
                    return m;
                }, sessionId, sessionId);
    }

    /** 逻辑删除会话及其消息：只置 delete_flag = 1，不做物理 DELETE */
    public void deleteSession(String sessionId) {
        jdbc.update("UPDATE chat_message SET delete_flag = 1 WHERE session_id = ? AND delete_flag = 0", sessionId);
        jdbc.update("UPDATE chat_session SET delete_flag = 1 WHERE id = ? AND delete_flag = 0", sessionId);
    }

    /** 逻辑删除某工作区下的全部会话及其消息（供删除工作区级联调用，避免孤儿会话） */
    public void deleteSessionsByWorkspace(String workspaceId) {
        jdbc.update("UPDATE chat_message m JOIN chat_session s ON m.session_id = s.id " +
                        "SET m.delete_flag = 1 WHERE s.workspace_id = ? AND s.delete_flag = 0 AND m.delete_flag = 0",
                workspaceId);
        jdbc.update("UPDATE chat_session SET delete_flag = 1 WHERE workspace_id = ? AND delete_flag = 0", workspaceId);
    }

    /** 重命名会话；空标题回退为「新会话」，超长截断；已逻辑删除的会话不可修改 */
    public ChatSession renameSession(String sessionId, String title) {
        String newTitle = title == null ? "" : title.trim();
        if (newTitle.isEmpty()) {
            newTitle = "新会话";
        }
        if (newTitle.length() > 255) {
            newTitle = newTitle.substring(0, 255);
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int rows = jdbc.update(
                "UPDATE chat_session SET title = ?, updated_at = ? WHERE id = ? AND delete_flag = 0",
                newTitle, now, sessionId);
        return rows > 0 ? getSession(sessionId) : null;
    }

    /**
     * 直接落一条消息（不经 advisor）。用于客户端中断连接时补写已生成的部分回复；
     * 正常完成路径由 MessageChatMemoryAdvisor 落库，二者互斥不会双写。
     */
    public void saveMessage(String sessionId, String role, String content) {
        if (content == null || content.isBlank() || getSession(sessionId) == null) {
            return;
        }
        jdbc.update(
                "INSERT INTO chat_message(session_id, user_id, role, content, created_at, delete_flag) " +
                        "VALUES (?,?,?,?,?,0)",
                sessionId, CURRENT_USER_ID, role, content, new Timestamp(System.currentTimeMillis()));
    }

    /**
     * 更新会话活跃时间；首条用户消息仅在标题仍为默认值「新会话」时才用作标题，
     * 避免用户手动重命名过的会话在发第一句话时被覆盖。
     */
    public void touch(String sessionId, String firstUserText) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("UPDATE chat_session SET updated_at = ? WHERE id = ? AND delete_flag = 0", now, sessionId);
        if (firstUserText != null && !firstUserText.isBlank()) {
            String title = firstUserText.length() > 30 ? firstUserText.substring(0, 30) : firstUserText;
            jdbc.update("UPDATE chat_session SET title = ? " +
                            "WHERE id = ? AND delete_flag = 0 AND title = '新会话'",
                    title, sessionId);
        }
    }

    private ChatSession mapSession(java.sql.ResultSet rs) throws java.sql.SQLException {
        ChatSession s = new ChatSession();
        s.id = rs.getString("id");
        s.userId = rs.getString("user_id");
        s.title = rs.getString("title");
        s.workspaceId = rs.getString("workspace_id");
        s.createdAt = rs.getTimestamp("created_at").toString();
        s.updatedAt = rs.getTimestamp("updated_at").toString();
        return s;
    }
}
