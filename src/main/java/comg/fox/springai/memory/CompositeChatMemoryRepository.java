package comg.fox.springai.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 组合式 ChatMemoryRepository：把多个真实存储（如 MySQL/JDBC、Redis）聚合为一个。
 * <ul>
 *   <li>写入（saveAll / deleteByConversationId）：对全部委托存储都执行，实现双写/多写。</li>
 *   <li>读取（findByConversationId / findConversationIds）：只从 primary 读取，避免跨存储不一致。</li>
 * </ul>
 * 配合 {@code @Primary} 使用：把它声明为首选 ChatMemoryRepository 后，
 * PermanentChatMemory 注入拿到的就是它，从而自动扇出到所有底层存储。
 */
public class CompositeChatMemoryRepository implements ChatMemoryRepository {

    private final List<ChatMemoryRepository> delegates;
    private final ChatMemoryRepository primary;

    public CompositeChatMemoryRepository(List<ChatMemoryRepository> delegates, ChatMemoryRepository primary) {
        this.delegates = delegates;
        this.primary = primary;
    }

    @Override
    public List<String> findConversationIds() {
        return primary.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return primary.findByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        for (ChatMemoryRepository repository : delegates) {
            repository.saveAll(conversationId, messages);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        for (ChatMemoryRepository repository : delegates) {
            repository.deleteByConversationId(conversationId);
        }
    }
}
