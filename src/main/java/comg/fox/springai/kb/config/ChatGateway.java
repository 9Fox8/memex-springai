package comg.fox.springai.kb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 对话网关：失败重试 + 故障切换。
 *
 * <p>流程：当前选中模型最多尝试 app.chat.retry.max-attempts 次（含首次，指数退避），
 * 仍失败则按 app.chat.fallback.chain 依次切换，链尾为最终兜底模型。
 * 每次尝试都带 app.chat.timeout.idle 的空闲超时：超过该时长没有新 token 即视为失败。
 *
 * <p>流式约束：一旦已经向前端吐出首 token，就不再重试或切换（否则会产生重复内容），
 * 只给出中断提示。仅在「首 token 之前」失败才允许重试/切换。
 * 4xx（除 408/429）视为不可重试，5xx / 408 / 429 / 连接失败 / 超时可重试。
 */
@Component
public class ChatGateway {

    private static final Logger log = LoggerFactory.getLogger(ChatGateway.class);

    private final ChatClientRegistry registry;
    private final ChatModelProperties properties;

    public ChatGateway(ChatClientRegistry registry, ChatModelProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * @param modelId 前端选中的模型 id
     * @param invoke  给定模型 id 发起一次流式请求的动作（由控制器构造，含提示词/记忆/工具）
     */
    public Flux<String> stream(String modelId, Function<String, Flux<String>> invoke) {
        final List<String> chain = buildChain(modelId);
        log.info("本轮候选模型链: {}", chain);
        return Flux.defer(() -> attempt(chain, 0, 1, invoke));
    }

    /**
     * 候选链：从「选中模型在降级链中的位置」开始往后，避免回退到更差的模型；
     * 选中模型不在链中时置于最前再走完整链。链尾模型始终保证在最后（最终兜底）。
     */
    private List<String> buildChain(String modelId) {
        List<String> configured = properties.getFallback().getChain();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String selected = (modelId == null || modelId.isBlank()) ? registry.getDefaultId() : modelId;
        int pos = configured.indexOf(selected);
        if (pos < 0) {
            ordered.add(selected);
            ordered.addAll(configured);
        } else {
            ordered.addAll(configured.subList(pos, configured.size()));
        }
        if (!configured.isEmpty()) {
            ordered.add(configured.get(configured.size() - 1));
        }
        return new ArrayList<>(ordered);
    }

    private Flux<String> attempt(List<String> chain, int index, int attemptNo,
                                 Function<String, Flux<String>> invoke) {
        String modelId = chain.get(index);
        AtomicBoolean started = new AtomicBoolean(false);

        // 空闲超时：两次输出之间超过 app.chat.timeout.idle 没有新 token 即视为失败。
        // 上游卡死时不会永久占用连接与会话锁；首 token 前超时按可重试异常处理。
        Flux<String> current = Flux.defer(() -> invoke.apply(modelId))
                .timeout(properties.getTimeout().getIdle())
                .doOnNext(t -> started.set(true));

        return current.onErrorResume(err -> {
            if (started.get()) {
                log.warn("模型 {} 在输出过程中失败，不重试以免前端出现重复内容: {}", modelId, err.toString());
                return notice("\n[生成中断，请重新发送]");
            }

            int max = Math.max(1, properties.getRetry().getMaxAttempts());
            if (attemptNo < max && isRetryable(err)) {
                long delay = backoffMillis(attemptNo);
                log.warn("模型 {} 第 {} 次调用失败，{}ms 后重试: {}", modelId, attemptNo, delay, err.toString());
                return Mono.delay(Duration.ofMillis(delay))
                        .flatMapMany(x -> attempt(chain, index, attemptNo + 1, invoke));
            }

            if (index + 1 < chain.size()) {
                String next = chain.get(index + 1);
                log.warn("模型 {} 重试 {} 次仍失败，切换到 {}: {}", modelId, max, next, err.toString());
                return Flux.concat(notice("\n[模型不可用，正在切换至 " + displayName(next) + "…]\n"),
                        Mono.delay(Duration.ofMillis(200))
                                .flatMapMany(x -> attempt(chain, index + 1, 1, invoke)));
            }

            log.error("所有候选模型均不可用，最后失败模型: {}", modelId, err);
            return notice("\n[所有模型均不可用，请稍后重试]");
        });
    }

    /** 退避：base, base*2, base*4 ...，最多放大 32 倍 */
    private long backoffMillis(int attemptNo) {
        long base = Math.max(0, properties.getRetry().getBackoff().toMillis());
        return base * (1L << Math.min(Math.max(attemptNo - 1, 0), 5));
    }

    private boolean isRetryable(Throwable err) {
        Throwable t = err;
        while (t != null) {
            if (t instanceof WebClientResponseException) {
                int code = ((WebClientResponseException) t).getStatusCode().value();
                return code == 408 || code == 429 || code >= 500;
            }
            t = t.getCause();
        }
        return true;   // 连接失败 / 超时 / 未知异常默认可重试
    }

    private String displayName(String id) {
        return properties.getModels().stream()
                .filter(m -> id.equals(m.getId()))
                .map(ChatModelProperties.ModelDef::getName)
                .findFirst()
                .orElse(id);
    }

    private Flux<String> notice(String text) {
        return properties.getFallback().isNotice() ? Flux.just(text) : Flux.empty();
    }
}
