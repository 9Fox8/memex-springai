package comg.fox.springai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 自定义 Redis 版 ChatMemoryRepository，作为 {@code CompositeChatMemoryRepository} 的一个委托存储，
 * 与 JDBC(MySQL) 形成双写。读仍以 MySQL(primary) 为准，Redis 主要作高速副本。
 * <p>
 * 关键点（方案2）：写入 Redis 时只序列化「消息类型 + 文本」(ChatEntry)，<b>完全不碰 metadata</b>。
 * 这样既规避了 metadata 里 {@code java.time.Instant} 等类型导致的 Jackson 序列化失败，
 * 也让 Redis 体积更小、与“Redis 当上下文副本”的定位更契合。
 */
@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String KEY_PREFIX = "chat:memory:";
    private static final String IDS_KEY = "chat:memory:ids";

    /** 轻量结构：只存消息类型 + 文本，不携带 metadata */
    private record ChatEntry(String type, String content) {}

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void checkConnection() {
        // 通过模板执行命令，由模板负责从连接池取连接并归还。
        // 不要直接 getConnection() 后不管：那会每次启动泄漏一条 Jedis 连接。
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            log.info("Redis 连接成功(ping={})，双写已就绪", pong);
        } catch (Exception e) {
            log.warn("Redis 连接失败，双写将降级为仅写 MySQL。原因: {}", e.getMessage());
        }
    }

    @Override
    public List<String> findConversationIds() {
        try {
            var members = redisTemplate.opsForSet().members(IDS_KEY);
            return members == null ? Collections.emptyList() : List.copyOf(members);
        } catch (Exception e) {
            log.warn("Redis 读取会话ID列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
            if (json == null) {
                return Collections.emptyList();
            }
            List<ChatEntry> entries = objectMapper.readValue(json, new TypeReference<List<ChatEntry>>() {});
            return entries.stream()
                    .map(RedisChatMemoryRepository::toMessage)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Redis 读取会话[{}]失败: {}", conversationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<ChatEntry> entries = messages.stream()
                    .map(m -> new ChatEntry(m.getMessageType().name(), m.getText()))
                    .toList();
            String json = objectMapper.writeValueAsString(entries);
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, json);
            redisTemplate.opsForSet().add(IDS_KEY, conversationId);
            log.debug("Redis 已写入会话[{}]，条数={}", conversationId, messages.size());
        } catch (Exception e) {
            // Redis 写入失败不影响主流程（MySQL 为主存储），但打出告警便于排查
            log.warn("Redis 写入会话[{}]失败，已降级(仅MySQL): {}", conversationId, e.getMessage());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        try {
            redisTemplate.delete(KEY_PREFIX + conversationId);
            redisTemplate.opsForSet().remove(IDS_KEY, conversationId);
        } catch (Exception e) {
            log.warn("Redis 删除会话[{}]失败: {}", conversationId, e.getMessage());
        }
    }

    /** 从轻量结构重建消息；TOOL 等暂不支持重建的返回 null（会被过滤掉） */
    private static Message toMessage(ChatEntry e) {
        if (e == null || e.content() == null) {
            return null;
        }
        return switch (e.type()) {
            case "USER" -> new UserMessage(e.content());
            case "ASSISTANT" -> new AssistantMessage(e.content());
            case "SYSTEM" -> new SystemMessage(e.content());
            default -> null;
        };
    }
}
