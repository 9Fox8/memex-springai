package comg.fox.springai.kb.session;

/**
 * 单条聊天消息，对应 chat_message 表。
 * 表中的 delete_flag 为逻辑删除标识，历史回显与多轮记忆都只读取未删除的消息。
 */
public class ChatMessage {
    /** 消息角色：USER / ASSISTANT（库里为大写，前端按大小写不敏感判断气泡方向） */
    public String role;
    /** 消息正文 */
    public String content;
}
