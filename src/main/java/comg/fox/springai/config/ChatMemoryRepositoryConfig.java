package comg.fox.springai.config;

import comg.fox.springai.memory.CompositeChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class ChatMemoryRepositoryConfig {

    /**
     * 把项目中所有 ChatMemoryRepository 聚合成一个 @Primary Bean。
     * <p>
     * 关键点：注入 List&lt;ChatMemoryRepository&gt; 时，Spring 会排除“正在创建的 composite 自身”，
     * 这里再额外过滤一次以防万一，保证 delegates 里不会包含 composite，避免 saveAll 自递归。
     * <p>
     * 当前只引入了 JDBC 仓库时，它等价于“直接用 JDBC”；后续若引入 Redis 等第二个仓库，
     * 该 Bean 会自动把写入扇出到所有存储，无需改其它代码。
     */
    @Bean
    @Primary
    public ChatMemoryRepository compositeChatMemoryRepository(List<ChatMemoryRepository> allRepositories) {
        List<ChatMemoryRepository> repositories = allRepositories.stream()
                .filter(r -> !(r instanceof CompositeChatMemoryRepository))
                .toList();

        ChatMemoryRepository primary = repositories.stream()
                .filter(r -> r instanceof JdbcChatMemoryRepository)
                .findFirst()
                .orElseGet(() -> repositories.isEmpty() ? null : repositories.get(0));

        if (primary == null) {
            throw new IllegalStateException(
                    "未找到任何 ChatMemoryRepository，请确认已引入 chat-memory-repository 模块（如 jdbc / redis）");
        }
        return new CompositeChatMemoryRepository(repositories, primary);
    }
}
