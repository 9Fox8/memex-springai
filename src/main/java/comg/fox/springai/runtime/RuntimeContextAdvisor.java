package comg.fox.springai.runtime;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 每次请求前把「当前日期」追加到系统提示词。
 *
 * <p>为什么必须注入：大模型本身没有时钟，训练数据也有截止日期。系统提示词里没有时间时，
 * 模型被问「今天几号」只能去猜或去联网搜索——而搜索返回的是网页的<b>发布日期</b>，
 * 用来回答「今天几号」必然错。
 *
 * <p>为什么放在 advisor 里：ChatClient 在启动时构建一次，时间必须每次请求现算，
 * 否则进程跨天运行会一直回答昨天的日期。
 *
 * <p>为什么只注入到「天」：见 {@link #buildBlock()} 的注释——秒级时间会破坏前缀缓存，
 * 让整段历史每轮都按未命中价重新计费。
 */
@Component
public class RuntimeContextAdvisor implements BaseAdvisor {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RuntimeContextProperties properties;

    public RuntimeContextAdvisor(RuntimeContextProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "RuntimeContextAdvisor";
    }

    @Override
    public int getOrder() {
        // 排在记忆顾问之前：先把运行时信息挂到系统消息上，后续 advisor 改动消息列表时不会丢失
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!properties.isEnabled()) {
            return request;
        }
        Prompt original = request.prompt();
        List<Message> instructions = new ArrayList<>(original.getInstructions());

        // 注意：不要用 Prompt#augmentSystemMessage —— 它会用新内容「替换」系统消息，
        // 把原本的系统提示词（人设、工具规则）整段丢掉。这里自己合并：保留原有系统内容 + 追加运行时信息。
        StringBuilder systemText = new StringBuilder();
        for (Message message : instructions) {
            if (MessageType.SYSTEM.equals(message.getMessageType()) && message.getText() != null) {
                if (systemText.length() > 0) {
                    systemText.append('\n');
                }
                systemText.append(message.getText());
            }
        }
        instructions.removeIf(message -> MessageType.SYSTEM.equals(message.getMessageType()));
        systemText.append('\n').append(buildBlock());
        instructions.add(0, new SystemMessage(systemText.toString()));

        return request.mutate().prompt(new Prompt(instructions, original.getOptions())).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * 拼出注入到系统提示词末尾的运行时信息。
     *
     * <p><b>只放日期，不放时分秒</b>。原因不是省那几十个 token，而是缓存：
     * 服务端（如 DeepSeek）按<b>前缀</b>匹配缓存，系统提示词在请求最前面，
     * 里面只要有一个每次都变的字段，整段历史前缀就无法命中缓存，
     * 每轮都要按未命中价（约为命中价的 50 倍）重新计费。
     * 日期一天变一次，同一自然日内前缀稳定，缓存才能生效；
     * 需要精确到秒时由 {@code getCurrentDateTime} 工具现取。
     *
     * <p>行为规则（禁止联网查时间、本机信息用工具）放在系统提示词文件里，
     * 那些是静态内容，本来就该被缓存，不该塞进这段每次都变的消息。
     */
    String buildBlock() {
        ZonedDateTime now = ZonedDateTime.now(properties.resolveZone());
        String week = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return "\n【当前日期】%s %s（时区 %s）".formatted(
                now.format(DATE), week, now.getZone().getId());
    }
}
