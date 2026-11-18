package cn.keeprising.app;

import cn.keeprising.core.*;
import cn.keeprising.spi.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring 装配层：读取环境变量，将纯 Java 的 core 内核与工具装配为托管 Bean。
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

    // 注册内置计算器工具（后续新工具直接声明 @Bean 或 @Component 即可自动注入进来）
    @Bean
    public CalcTool calcTool() {
        return new CalcTool();
    }

    // 装配工具通讯录：把容器里所有 Tool 实现自动收进注册表
    @Bean
    public ToolRegistry toolRegistry(List<Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }

    @Bean
    public ToolArgumentsParser toolArgumentsParser() {
        return new ToolArgumentsParser();
    }

    // 工具并发线程池：同一步多工具同时执行，停机时自动关闭
    @Bean(destroyMethod = "shutdown")
    public ExecutorService toolExecutorService() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "lore-tool");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public ToolExecutor toolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentsParser parser,
            ExecutorService toolExecutorService) {
        return new ToolExecutor(toolRegistry, parser, toolExecutorService);
    }

    @Bean
    public ContextAssembler contextAssembler() {
        return new ContextAssembler(new TokenEstimator());
    }

    @Bean
    public RunRegistry runRegistry() {
        return new RunRegistry();
    }

    // Phase 2：装配纯 Java 文本解析、切片、中英二元组分词与向量/引用/拒答组件
    @Bean
    public MarkdownParser markdownParser() {
        return new MarkdownParser();
    }

    @Bean
    public Chunker chunker() {
        return new Chunker();
    }

    @Bean
    public Tokenizer tokenizer() {
        return new Tokenizer();
    }

    @Bean
    public EmbeddingClient embeddingClient(
            @Value("${lore.embedding.base-url:}") String baseUrl,
            @Value("${lore.embedding.api-key:}") String apiKey,
            @Value("${lore.embedding.model:text-embedding-3-small}") String model,
            @Value("${lore.embedding.max-batch-size:16}") int maxBatchSize) {
        return new EmbeddingClient(baseUrl, apiKey, model, maxBatchSize);
    }

    @Bean
    public RrfFusion rrfFusion() {
        return new RrfFusion();
    }

    @Bean
    public RefusalPolicy refusalPolicy() {
        return new RefusalPolicy();
    }

    @Bean
    public CitationValidator citationValidator() {
        return new CitationValidator();
    }

    // 装配智能体执行循环，负责驱动多轮工具调用与流式输出
    @Bean
    public AgentLoop agentLoop(
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ContextAssembler contextAssembler,
            @Value("${lore.model.max-prompt-tokens:16000}") int maxPromptTokens) {
        return new AgentLoop(modelRegistry, toolRegistry, toolExecutor, contextAssembler, maxPromptTokens);
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
