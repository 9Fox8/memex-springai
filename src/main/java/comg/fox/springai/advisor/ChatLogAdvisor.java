package comg.fox.springai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import comg.fox.springai.repository.ChatMessageLogRepository;

import java.util.List;
import java.util.Map;

/**
 * 业务侧聊天记录顾问：每轮对话把 USER / AI 消息各落一条到 chat_message_log 表。
 * 与记忆顾问（MessageChatMemoryAdvisor）无关，纯业务审计用途。
 */
public class ChatLogAdvisor implements BaseAdvisor {

    /** 可选：在请求参数里传 chatLogUserId 区分真实用户；不传则等同于会话ID */
    public static final String USER_ID_KEY = "chatLogUserId";

    private final ChatMessageLogRepository repository;
    private final String model;

    public ChatLogAdvisor(ChatMessageLogRepository repository, String model) {
        this.repository = repository;
        this.model = model;
    }

    @Override
    public String getName() {
        return "ChatLogAdvisor";
    }

    @Override
    public int getOrder() {
        // 在记忆顾问之后执行，避免影响记忆链路
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Map<String, Object> ctx = request.context();
        String conversationId = (String) ctx.get(ChatMemory.CONVERSATION_ID);
        if (conversationId == null) {
            return request; // 未传会话ID时不记录，避免打断对话
        }
        String userId = (String) ctx.getOrDefault(USER_ID_KEY, conversationId);
        String userText = extractLastUserText(request.prompt());
        if (isNotBlank(userText)) {
            repository.save(conversationId, userId, model, "USER", userText);
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Map<String, Object> ctx = response.context();
        String conversationId = (String) ctx.get(ChatMemory.CONVERSATION_ID);
        if (conversationId == null) {
            return response;
        }
        String userId = (String) ctx.getOrDefault(USER_ID_KEY, conversationId);
        String aiText = extractAiText(response);
        if (isNotBlank(aiText)) {
            repository.save(conversationId, userId, model, "AI", aiText);
        }
        return response;
    }

    private String extractLastUserText(Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        List<Message> messages = prompt.getInstructions();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.stream()
                .filter(m -> MessageType.USER.equals(m.getMessageType()))
                .reduce((a, b) -> b) // 取最后一条用户消息
                .map(Message::getText)
                .orElse(null);
    }

    private String extractAiText(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
