package comg.fox.springai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;

/**
 * 自定义日志切面
 * 实现CallAdvisor和StreamAdvisor接口。同时支持两种模式
 */
public class MySimpleLoggerAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        System.out.println("发送请求前：->" + chatClientRequest);
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        System.out.println("接收到响应：->" + chatClientResponse);
        return chatClientResponse;
    }

    @Override
    public String getName() {
        return "简单日志-Advisor";
    }

    /**
     * 这个getOrder()方法用于指定Advisor(通知器)的执行顺序。
     * 作用说明:
     * 返回值越小，优先级越高，越早执行
     * 返回0 表示高优先级
     * 如果有多个Advisor，Spring AI 会按照此值从小到大依次执行
     *
     * @return
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
