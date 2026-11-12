package comg.fox.springai.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class PermanentChatMemory implements ChatMemory {

    private final ChatMemoryRepository repository;
    /** 发给模型的上下文条数，默认 20；数据库里仍保存全部历史 */
    private final int contextWindowSize;
    /** 每个会话一把锁，避免并发 add 时读-改-写互相覆盖、clear 与 add 竞争 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public PermanentChatMemory(ChatMemoryRepository repository,
                               @Value("${app.chat.context-window:20}") int contextWindowSize) {
        this.repository = repository;
        this.contextWindowSize = Math.max(1, contextWindowSize);
    }

    /** 读取：返回最近 N 条作为模型上下文（历史仍全量存在库里） */
    @Override
    public List<Message> get(String conversationId) {
        ReentrantLock lock = lockFor(conversationId);
        lock.lock();
        try {
            List<Message> all = repository.findByConversationId(conversationId);
            if (all.size() <= contextWindowSize) {
                return new ArrayList<>(all);
            }
            return new ArrayList<>(all.subList(all.size() - contextWindowSize, all.size()));
        } finally {
            lock.unlock();
        }
    }

    /** 写入：先取全部历史 → 追加 → 全量覆盖写回（历史不丢） */
    @Override
    public void add(String conversationId, List<Message> messages) {
        ReentrantLock lock = lockFor(conversationId);
        lock.lock();
        try {
            List<Message> merged = new ArrayList<>(repository.findByConversationId(conversationId));
            merged.addAll(messages);
            repository.saveAll(conversationId, merged);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear(String conversationId) {
        ReentrantLock lock = lockFor(conversationId);
        lock.lock();
        try {
            repository.deleteByConversationId(conversationId);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String conversationId) {
        return locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
    }
}
