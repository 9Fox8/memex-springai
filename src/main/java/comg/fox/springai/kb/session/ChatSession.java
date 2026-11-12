package comg.fox.springai.kb.session;

/**
 * 会话（窗口）元信息，对应 chat_session 表。
 * 表中的 delete_flag 为逻辑删除标识，仅未删除的会话会出现在列表与查询结果中。
 */
public class ChatSession {
    /** 会话ID(UUID) */
    public String id;
    /** 所属用户ID */
    public String userId;
    /** 会话标题（取首条用户消息前 30 字） */
    public String title;
    /** 所属工作区ID；NULL 表示默认空间（不属于任何工作区） */
    public String workspaceId;
    /** 创建时间 */
    public String createdAt;
    /** 最后活跃时间 */
    public String updatedAt;
}
