package comg.fox.springai.repository;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 业务侧聊天记录表。与框架表 SPRING_AI_CHAT_MEMORY 解耦，结构自由，
 * 用于后台查询/展示（用户ID、时间、角色、原文）。
 */
@Repository
public class ChatMessageLogRepository implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;

    public ChatMessageLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动时自建表（IF NOT EXISTS，幂等） */
    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_message_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
                    user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户ID',
                    model VARCHAR(32) NOT NULL DEFAULT '' COMMENT '模型标识(openai/ollama)',
                    `role` VARCHAR(16) NOT NULL COMMENT '角色 USER/AI/SYSTEM',
                    content TEXT NOT NULL COMMENT '消息原文',
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
                    INDEX idx_conversation (conversation_id),
                    INDEX idx_user (user_id)
                ) COMMENT='业务侧聊天记录表';
                """);
    }

    public void save(String conversationId, String userId, String model, String role, String content) {
        jdbcTemplate.update(
                "INSERT INTO chat_message_log (conversation_id, user_id, model, `role`, content, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                conversationId, userId, model, role, content, LocalDateTime.now());
    }
}
