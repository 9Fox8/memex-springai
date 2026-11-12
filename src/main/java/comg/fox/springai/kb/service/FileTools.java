package comg.fox.springai.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 暴露给 Agent 的本地文件操作工具集合。
 * 默认所有路径都限制在 {@code app.file.root} 指定的“沙箱”主根目录内，防止路径穿越误操作系统文件；
 * 另支持通过 {@code app.file.extra-roots} 配置额外可访问的挂载目录（如个人知识库），
 * 对挂载目录使用绝对路径即可读写，但仍不允许越出「主根 + 挂载」之外。
 */
public class FileTools {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    /** 主根目录：工作区根，相对路径的基准 */
    private final Path root;
    /** 全部允许访问的目录（主根 + 挂载白名单），均为绝对路径且已规范化 */
    private final List<Path> roots;

    public FileTools(String rootDir, List<String> extraRoots) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件操作根目录: " + this.root, e);
        }
        List<Path> all = new ArrayList<>();
        all.add(this.root);
        if (extraRoots != null) {
            for (String dir : extraRoots) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                // 挂载目录不自动创建，避免配置笔误时静默生成新目录
                all.add(Paths.get(dir.trim()).toAbsolutePath().normalize());
            }
        }
        this.roots = List.copyOf(all);
        log.info("本地文件操作工具根目录(沙箱): {}，挂载目录: {}", this.root,
                this.roots.size() > 1 ? this.roots.subList(1, this.roots.size()) : "无");
    }

    /**
     * 将用户提供的路径解析为安全的绝对路径：相对路径基于主根；绝对路径必须落在
     * 「主根或任一挂载目录」之内，杜绝 ../ 穿越与越界访问。
     */
    private Path resolve(String userPath) {
        String p = userPath == null ? "" : userPath.trim();
        Path resolved = Paths.get(p).isAbsolute()
                ? Paths.get(p).normalize()
                : root.resolve(p).normalize();
        for (Path r : roots) {
            if (resolved.equals(r) || resolved.startsWith(r)) {
                return resolved;
            }
        }
        throw new IllegalArgumentException(
                "非法路径，超出允许的操作范围: " + userPath + "（允许的目录: " + describeRoots() + "）");
    }

    /** 允许访问的全部目录描述（用于报错提示，帮助模型自行纠正路径） */
    private String describeRoots() {
        return roots.stream().map(Path::toString).collect(Collectors.joining(" ; "));
    }

    /** 主根之外的挂载目录列表 */
    private List<Path> mounts() {
        return roots.size() > 1 ? roots.subList(1, roots.size()) : List.of();
    }

    @Tool(description = "在允许的目录内创建/覆盖一个文件（例如生成一份 .md 文档）并写入内容。" +
            "当用户要求“生成/创建/写/保存一个文件到某路径”时调用。返回写入结果。" +
            "路径可以是相对工作区根目录的相对路径，也可以是挂载目录（如个人知识库）的绝对路径。")
    public String writeFile(@ToolParam(description = "文件相对路径，例如 notes/readme.md") String path,
                            @ToolParam(description = "要写入文件的完整文本内容") String content) {
        try {
            Path p = resolve(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content == null ? "" : content);
            return "已写入文件: " + p + "（" + (content == null ? 0 : content.length()) + " 字符）";
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    @Tool(description = "读取工作区内某个文件的完整内容（相当于“打开并查看”文件）。" +
            "当用户要求“打开/读取/查看某文件”时调用。")
    public String readFile(@ToolParam(description = "文件相对路径") String path) {
        try {
            Path p = resolve(path);
            if (!Files.exists(p)) {
                return "文件不存在: " + path;
            }
            if (Files.isDirectory(p)) {
                return "这是一个目录，不是文件: " + path;
            }
            return Files.readString(p);
        } catch (Exception e) {
            return "读取文件失败: " + e.getMessage();
        }
    }

    @Tool(description = "向工作区内已有文件末尾追加内容（不覆盖原有内容）。" +
            "当用户要求“在文件后面追加/补充内容”时调用。")
    public String appendToFile(@ToolParam(description = "文件相对路径") String path,
                               @ToolParam(description = "要追加的文本内容") String content) {
        try {
            Path p = resolve(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content == null ? "" : content,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "已追加内容到: " + p;
        } catch (Exception e) {
            return "追加失败: " + e.getMessage();
        }
    }

    @Tool(description = "删除工作区内的某个文件。" +
            "当用户明确要求“删除/移除某个文件”时调用；删除是不可逆操作，调用前请先向用户确认。")
    public String deleteFile(@ToolParam(description = "文件相对路径") String path) {
        try {
            Path p = resolve(path);
            if (!Files.exists(p)) {
                return "文件不存在: " + path;
            }
            if (Files.isDirectory(p)) {
                return "这是目录，请使用专门的目录删除工具或手动删除: " + path;
            }
            Files.delete(p);
            return "已删除文件: " + p;
        } catch (Exception e) {
            return "删除失败: " + e.getMessage();
        }
    }

    @Tool(description = "列出工作区内某个目录下的文件和子目录。" +
            "当用户要求“查看/列出某目录内容”时调用。传空字符串或 \".\" 表示根目录。")
    public String listFiles(@ToolParam(description = "目录相对路径，传空字符串或 . 表示根目录") String dir) {
        try {
            Path p = resolve(dir == null || dir.isBlank() ? "." : dir);
            if (!Files.isDirectory(p)) {
                return "不是目录: " + dir;
            }
            List<String> items;
            try (var stream = Files.list(p)) {
                items = stream
                        .map(sub -> (Files.isDirectory(sub) ? "[目录] " : "[文件] ") + p.relativize(sub))
                        .collect(Collectors.toList());
            }
            String result = items.isEmpty() ? "（空目录）" : String.join("\n", items);
            // 列举根目录时附带挂载目录提示，让模型知道还能用绝对路径访问哪些目录（如个人知识库）
            if (p.equals(root) && !mounts().isEmpty()) {
                result += "\n\n此外还可读写以下挂载目录（请直接使用其绝对路径操作）: "
                        + mounts().stream().map(Path::toString).collect(Collectors.joining(" ; "));
            }
            return result;
        } catch (Exception e) {
            return "列举失败: " + e.getMessage();
        }
    }

    @Tool(description = "在工作区/挂载目录内重命名或移动文件与文件夹（不会覆盖已存在的目标）。" +
            "当用户要求“重命名/改名字/移动某个文件或目录”时调用。" +
            "路径可为相对工作区根的相对路径，或挂载目录的绝对路径。")
    public String renamePath(@ToolParam(description = "原路径（相对工作区根或绝对路径）") String oldPath,
                             @ToolParam(description = "新路径（相对工作区根或绝对路径）") String newPath) {
        try {
            Path s = resolve(oldPath);
            Path t = resolve(newPath);
            if (!Files.exists(s)) {
                return "源文件/目录不存在: " + oldPath;
            }
            if (Files.exists(t)) {
                return "目标已存在，为避免覆盖已中止: " + newPath;
            }
            if (t.getParent() != null) {
                Files.createDirectories(t.getParent());
            }
            Files.move(s, t);
            return "已重命名/移动: " + s + " -> " + t;
        } catch (Exception e) {
            return "重命名失败: " + e.getMessage();
        }
    }

    /** 以二进制方式写入文件（用于保存上传的图片/附件）。仅供内部落盘使用，不暴露为工具 */
    public String writeBytes(String path, byte[] data) {
        try {
            Path p = resolve(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.write(p, data == null ? new byte[0] : data);
            return "已写入文件: " + p + "（" + (data == null ? 0 : data.length) + " 字节）";
        } catch (Exception e) {
            return "写入文件失败: " + e.getMessage();
        }
    }

    @Tool(description = "在工作区内创建一个新的目录（文件夹）。" +
            "当用户要求“新建/创建文件夹”时调用。")
    public String createDirectory(@ToolParam(description = "目录相对路径") String dir) {
        try {
            Path p = resolve(dir);
            Files.createDirectories(p);
            return "已创建目录: " + p;
        } catch (Exception e) {
            return "创建目录失败: " + e.getMessage();
        }
    }
}
