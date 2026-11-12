package comg.fox.springai.kb.session;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 MySQL 会话消息表的 {@link ChatMemory} 实现。
 * 以 conversationId 即 sessionId 为键，按 userId 隔离；用于 MessageChatMemoryAdvisor 的多轮记忆。
 *
 * <p>遵循逻辑删除约定：只读取 delete_flag = 0 的消息，clear() 只置删除标识而不物理删除；
 * 会话已被逻辑删除时不再写入，避免产生孤儿消息。
 * 注意：本类不注册为 Spring Bean，由 KnowledgeBaseConfig 手动实例化，避免与自动配置的 ChatMemory 冲突。
 */
public class SessionBackedChatMemory implements ChatMemory {

    private final JdbcTemplate jdbc;
    private final int maxMessages;

    public SessionBackedChatMemory(JdbcTemplate jdbc, int maxMessages) {
        this.jdbc = jdbc;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 会话不存在或已逻辑删除时不再落库，避免复活已删会话 / 产生孤儿消息
        if (!isSessionAlive(conversationId)) {
            return;
        }
        String userId = resolveUserId(conversationId);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (Message m : messages) {
            String role = m.getMessageType().name();
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) {
                continue;
            }
            // 相邻去重：失败重试 / 模型切换会对同一轮重复触发本方法，
            // 若库中最后一条已是内容相同的 USER 消息则跳过，防止同一句提问出现重复气泡
            if ("USER".equals(role) && isDuplicateTail(conversationId, role, m.getText())) {
                continue;
            }
            jdbc.update(
                    "INSERT INTO chat_message(session_id, user_id, role, content, created_at, delete_flag) " +
                            "VALUES (?,?,?,?,?,0)",
                    conversationId, userId, role, m.getText(), now);
        }
    }

    /** 库中该会话最后一条未删除消息是否与指定角色 + 内容完全相同（用于写入去重） */
    private boolean isDuplicateTail(String sessionId, String role, String content) {
        try {
            String tail = jdbc.queryForObject(
                    "SELECT CONCAT(role, '|', content) FROM chat_message " +
                            "WHERE session_id = ? AND delete_flag = 0 ORDER BY id DESC LIMIT 1",
                    String.class, sessionId);
            return tail != null && tail.equals(role + "|" + content);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        // 按自增 id 倒序取最近 N 条：created_at 是秒级 DATETIME，同一秒内的多条消息会并列，
        // 用它排序会让 LIMIT 截到错误的边界（甚至把 USER 和 ASSISTANT 顺序弄反）
        List<Row> rows = jdbc.query(
                "SELECT role, content FROM chat_message " +
                        "WHERE session_id = ? AND delete_flag = 0 " +
                        "AND EXISTS (SELECT 1 FROM chat_session s WHERE s.id = ? AND s.delete_flag = 0) " +
                        "ORDER BY id DESC LIMIT ?",
                (rs, i) -> new Row(rs.getString("role"), rs.getString("content")),
                conversationId, conversationId, maxMessages);
        Collections.reverse(rows);
        List<Message> result = new ArrayList<>();
        for (Row r : rows) {
            if ("USER".equals(r.role)) {
                result.add(new UserMessage(r.content));
            } else if ("ASSISTANT".equals(r.role)) {
                result.add(new AssistantMessage(r.content));
            }
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        // 逻辑删除该会话的全部消息，不做物理 DELETE
        jdbc.update("UPDATE chat_message SET delete_flag = 1 WHERE session_id = ? AND delete_flag = 0",
                conversationId);
    }

    /** 会话必须存在且未被逻辑删除 */
    private boolean isSessionAlive(String sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_session WHERE id = ? AND delete_flag = 0",
                Integer.class, sessionId);
        return count != null && count > 0;
    }

    private String resolveUserId(String sessionId) {
        try {
            String u = jdbc.queryForObject(
                    "SELECT user_id FROM chat_session WHERE id = ? AND delete_flag = 0",
                    String.class, sessionId);
            return u != null ? u : SessionService.CURRENT_USER_ID;
        } catch (EmptyResultDataAccessException e) {
            return SessionService.CURRENT_USER_ID;
        }
    }

    private static final class Row {
        final String role;
        final String content;
        Row(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
