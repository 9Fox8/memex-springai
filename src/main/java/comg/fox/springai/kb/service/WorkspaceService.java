package comg.fox.springai.kb.service;

import comg.fox.springai.kb.session.SessionService;
import comg.fox.springai.kb.session.Workspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工作区管理：创建 / 列表 / 重命名 / 删除（含级联删除其下会话）。
 * 每个工作区绑定一个本地目录，删除工作区时一并逻辑删除其所有会话，避免孤儿会话。
 */
@Service
public class WorkspaceService {

    /** 当前登录用户。与 SessionService 保持一致 */
    public static final String CURRENT_USER_ID = SessionService.CURRENT_USER_ID;

    private final JdbcTemplate jdbc;
    private final SessionService sessionService;
    /** 允许创建工作区的根目录（沙箱主根 + extra-roots 白名单），均为绝对路径且已规范化 */
    private final List<Path> allowedRoots;

    public WorkspaceService(JdbcTemplate jdbc, SessionService sessionService,
                            @Value("${app.file.root:./workspace}") String fileRoot,
                            @Value("${app.file.extra-roots:}") String extraRootsRaw) {
        this.jdbc = jdbc;
        this.sessionService = sessionService;
        List<Path> roots = new ArrayList<>();
        roots.add(Paths.get(fileRoot).toAbsolutePath().normalize());
        if (extraRootsRaw != null) {
            for (String dir : extraRootsRaw.split(",")) {
                if (dir != null && !dir.isBlank()) {
                    roots.add(Paths.get(dir.trim()).toAbsolutePath().normalize());
                }
            }
        }
        this.allowedRoots = List.copyOf(roots);
    }

    /** 应用启动时确保 workspace 表存在（IF NOT EXISTS，可重复执行） */
    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS workspace (
                    id VARCHAR(64) NOT NULL COMMENT '工作区ID(UUID)',
                    user_id VARCHAR(64) NOT NULL COMMENT '所属用户ID',
                    name VARCHAR(255) NOT NULL DEFAULT '未命名工作区' COMMENT '工作区名称',
                    path VARCHAR(1024) NOT NULL COMMENT '绑定的本地目录(绝对路径)',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
                    delete_flag TINYINT NOT NULL DEFAULT 0 COMMENT '删除标识:0-正常,1-已删除',
                    PRIMARY KEY (id)
                ) COMMENT='工作区表：每个工作区绑定一个本地目录';
                """);
        jdbc.execute("ALTER TABLE workspace COMMENT = '工作区表：每个工作区绑定一个本地目录'");
    }

    /**
     * 创建工作区：校验名称/目录，解析为绝对路径，确保目录可创建且可写。
     * <p>
     * 目录必须是「沙箱主根（app.file.root）或 extra-roots 白名单」之下的路径：
     * 工作区目录会成为 FileTools 的根目录，如果允许任意绝对路径，
     * 就等于把模型的文件读写/删除能力开放到整块磁盘。
     * 相对路径基于沙箱主根解析（如 {@code myproj} → {@code ./workspace/myproj}）。
     */
    public Workspace createWorkspace(String name, String path) {
        if (name == null || name.trim().isBlank()) {
            throw new IllegalArgumentException("工作区名称不能为空");
        }
        if (path == null || path.trim().isBlank()) {
            throw new IllegalArgumentException("工作区目录路径不能为空");
        }
        String wsName = name.trim();
        if (wsName.length() > 255) wsName = wsName.substring(0, 255);

        Path requested = Paths.get(path.trim());
        Path dir = requested.isAbsolute()
                ? requested.normalize()
                : allowedRoots.get(0).resolve(requested).normalize();
        requireInsideAllowedRoots(dir);
        try {
            Files.createDirectories(dir);
            if (!Files.isWritable(dir)) {
                throw new IllegalStateException("目录不可写: " + dir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法创建/访问工作区目录: " + dir, e);
        }
        String id = UUID.randomUUID().toString();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO workspace(id, user_id, name, path, created_at, updated_at, delete_flag) " +
                        "VALUES (?,?,?,?,?,?,0)",
                id, CURRENT_USER_ID, wsName, dir.toString(), now, now);
        return getWorkspace(id);
    }

    /** 校验目录落在白名单之内，否则拒绝；防止用任意绝对路径把沙箱扩大到整块磁盘 */
    private void requireInsideAllowedRoots(Path dir) {
        for (Path root : allowedRoots) {
            if (dir.equals(root) || dir.startsWith(root)) {
                return;
            }
        }
        throw new IllegalArgumentException("工作区目录必须位于以下目录之内: " + describeAllowedRoots()
                + "；如需使用其它目录，请把它加到 application.yml 的 app.file.extra-roots 白名单后重启");
    }

    private String describeAllowedRoots() {
        return allowedRoots.stream().map(Path::toString).collect(Collectors.joining(" ; "));
    }

    public List<Workspace> listWorkspaces() {
        return jdbc.query(
                "SELECT id, user_id, name, path, created_at, updated_at FROM workspace " +
                        "WHERE user_id = ? AND delete_flag = 0 ORDER BY updated_at DESC",
                (rs, i) -> mapWorkspace(rs), CURRENT_USER_ID);
    }

    public Workspace getWorkspace(String id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, user_id, name, path, created_at, updated_at FROM workspace " +
                            "WHERE id = ? AND delete_flag = 0",
                    (rs, i) -> mapWorkspace(rs), id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Workspace renameWorkspace(String id, String name) {
        String newName = name == null ? "" : name.trim();
        if (newName.isEmpty()) newName = "未命名工作区";
        if (newName.length() > 255) newName = newName.substring(0, 255);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int rows = jdbc.update(
                "UPDATE workspace SET name = ?, updated_at = ? WHERE id = ? AND delete_flag = 0",
                newName, now, id);
        return rows > 0 ? getWorkspace(id) : null;
    }

    /** 逻辑删除工作区，并级联逻辑删除其下所有会话（含消息），避免孤儿会话 */
    public void deleteWorkspace(String id) {
        sessionService.deleteSessionsByWorkspace(id);
        jdbc.update("UPDATE workspace SET delete_flag = 1 WHERE id = ? AND delete_flag = 0", id);
    }

    private Workspace mapWorkspace(java.sql.ResultSet rs) throws java.sql.SQLException {
        Workspace w = new Workspace();
        w.id = rs.getString("id");
        w.userId = rs.getString("user_id");
        w.name = rs.getString("name");
        w.path = rs.getString("path");
        w.createdAt = rs.getTimestamp("created_at").toString();
        w.updatedAt = rs.getTimestamp("updated_at").toString();
        return w;
    }
}
