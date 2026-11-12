CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL COMMENT '会话ID,用于隔离不同对话',
    content         TEXT        NOT NULL COMMENT '消息内容',
    type            VARCHAR(36) NOT NULL COMMENT '消息类型,如 USER / AI / SYSTEM',
    `timestamp`     TIMESTAMP   NOT NULL COMMENT '消息产生的时间戳',
    sequence_id     BIGINT      NOT NULL COMMENT '消息序号,用于同一会话内排序',
    PRIMARY KEY (conversation_id, sequence_id)
    ) COMMENT='Spring AI 聊天记忆表';

CREATE INDEX SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY (conversation_id, `timestamp`);
