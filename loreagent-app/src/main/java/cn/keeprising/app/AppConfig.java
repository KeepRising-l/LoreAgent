package cn.keeprising.app;

import cn.keeprising.core.AgentLoop;
import cn.keeprising.core.ModelRegistry;
import cn.keeprising.core.OpenAiCompatibleAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring 装配层：读取环境变量，将纯 Java 的 core 内核装配为托管 Bean。
 */
@Configuration
public class AppConfig {

    /**
     * Phase 1～7 单用户占位标识，Phase 8 接入登录后统一替换为 UserContext。
     */
    public static final String LOCAL_USER = "local";

    // 装配模型注册表：把默认模型名和对应的 OpenAI 请求适配器绑在一起
    @Bean
    public ModelRegistry modelRegistry(
            @Value("${lore.model.base-url}") String baseUrl,
            @Value("${lore.model.api-key:}") String apiKey,
            @Value("${lore.model.name}") String modelName) {
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(baseUrl, apiKey);
        return new ModelRegistry(modelName, adapter);
    }

    // 装配智能体执行循环，负责驱动对话流式输出
    @Bean
    public AgentLoop agentLoop(ModelRegistry modelRegistry) {
        return new AgentLoop(modelRegistry);
    }

    // 后台对话线程池：把耗时的模型请求丢到这里跑，避免堵住 HTTP 线程导致不能实时吐字
    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "lore-chat");
            t.setDaemon(true);
            return t;
        });
    }

    // SSE 心跳定时线程池：每 5 秒发一次空注释探测客户端是否已断开，配合停机时自动关闭
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "lore-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
