package comg.fox.springai.kb.session;

/**
 * 工作区元信息，对应 workspace 表。
 * 每个工作区绑定一个本地目录（path），其下会话的文件操作被限制在该目录内。
 */
public class Workspace {
    /** 工作区ID(UUID) */
    public String id;
    /** 所属用户ID */
    public String userId;
    /** 工作区名称 */
    public String name;
    /** 绑定的本地目录（绝对路径） */
    public String path;
    /** 创建时间 */
    public String createdAt;
    /** 最后活跃时间 */
    public String updatedAt;
}
