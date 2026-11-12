package comg.fox.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/ai")
public class MyChatClientController {

    @Autowired
    private ChatClient openAiChatClient;

    @Autowired
    private ChatClient ollamaChatClient;

    @Autowired
    private ChatClient chatClient3;

    /**
     * 普通（非流式）对话：一次性返回完整回答。
     * <p>
     * 注意不要声明成 {@code text/event-stream}：这里返回的是完整字符串、没有 SSE 帧，
     * 按 SSE 解析的客户端会一直等 {@code \n\n} 结束标记而看起来「卡住」。需要逐字输出请用 /ai/ollama/send。
     */
    @GetMapping(value = "/openAi/send", produces = "text/plain;charset=utf-8")
    public String openAiSend(String question, @RequestParam(required = false) String id) {
        // 会话ID为空时给一个默认值：MessageChatMemoryAdvisor 不接受 null 的 conversationId
        String conversationId = (id == null || id.isBlank()) ? "default" : id;
        return openAiChatClient.prompt()//创建prompt对象，用于构建聊天请求
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(question)//设置用户输入
                .call()//调用模型，返回结果
                .content();//读取返回内容
    }

    @GetMapping(value = "/ollama/send",produces = "text/html;charset=utf-8")
    public Flux<String> ollamaSend(String question,String id){
        return ollamaChatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
                .user(question)//设置用户输入
                .stream()
                .content();
    }

    /**
     * 使用模板，一套代码应用到所有场景
     * @param topic
     * @return
     */
    @GetMapping(value = "/ask",produces = "text/html;charset=utf-8")
    public Flux<String> topic(String topic){
        PromptTemplate promptTemplate = new PromptTemplate("介绍一下{topic}");
        Prompt prompt = promptTemplate.create(Map.of("topic", topic));
        return ollamaChatClient.prompt(prompt)  //使用模板创建prompt对象，用于构建聊天请求
                .stream()   //流式相应输出
                .content(); //读取返回内容
    }

    /**
     * 使用模板，一套代码应用到所有场景
     * @param topic
     * @return
     */
    @GetMapping(value = "/ask2",produces = "text/html;charset=utf-8")
    public Flux<String> topic2(String topic){
        return ollamaChatClient.prompt()  //使用模板创建prompt对象，用于构建聊天请求
                .system("你是一个专业书评助手")
                .user(u->u.text("给我推荐两本关于{topic}的书籍").param("topic",topic))
                .stream()   //流式相应输出
                .content(); //读取返回内容
    }

    /**
     * 使用模板，一套代码应用到所有场景
     * @param topic
     * @return
     */
    @GetMapping(value = "/ask3",produces = "text/html;charset=utf-8")
    public Flux<String> topic3(String topic){
        return chatClient3.prompt()  //使用模板创建prompt对象，用于构建聊天请求
                .user(topic)
                .stream()   //流式相应输出
                .content(); //读取返回内容
    }
}
