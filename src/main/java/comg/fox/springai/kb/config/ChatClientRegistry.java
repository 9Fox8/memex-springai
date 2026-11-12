package comg.fox.springai.kb.config;

import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 modelId 提供预构建好的 ChatClient。
 * 每个模型各有自己的系统提示词（${modelName} 不同），但共享同一套记忆 advisor 与工具。
 * 这些 ChatClient 不注册为 Spring Bean，避免容器里多个 ChatClient 造成按类型注入歧义。
 */
public class ChatClientRegistry {

    /** 返回给前端的模型选项（不含 ChatClient，避免序列化） */
    public record ModelOption(String id, String name, String description) {}

    private final Map<String, ChatClient> clients = new LinkedHashMap<>();
    private final List<ModelOption> options = new ArrayList<>();
    private final String defaultId;

    public ChatClientRegistry(String defaultId) {
        this.defaultId = defaultId;
    }

    public void register(String id, String name, String description, ChatClient client) {
        clients.put(id, client);
        options.add(new ModelOption(id, name, description));
    }

    /** 取模型对应的 ChatClient；未指定或 id 未知时回退到默认模型 */
    public ChatClient get(String modelId) {
        ChatClient c = modelId == null ? null : clients.get(modelId);
        return c != null ? c : getDefault();
    }

    public ChatClient getDefault() {
        ChatClient c = clients.get(defaultId);
        if (c == null && !clients.isEmpty()) {
            c = clients.values().iterator().next();   // 默认 id 配错时兜底取第一个
        }
        return c;
    }

    public String getDefaultId() { return defaultId; }

    /** 提供给 GET /kb/models 的清单，顺序与 yml 配置一致 */
    public List<ModelOption> list() { return List.copyOf(options); }

    public boolean isEmpty() { return clients.isEmpty(); }
}
