package cn.keeprising.core;

import cn.keeprising.spi.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    @Test
    void shouldEmitDeltaAndDoneEventsInSingleTurn() {
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            onChunk.accept(StreamChunk.text("你"));
            onChunk.accept(StreamChunk.text("好"));
            onChunk.accept(StreamChunk.finish("stop", new StreamChunk.Usage(4, 2, 6)));
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", fake));
        List<AgentEvent> events = new ArrayList<>();

        AgentEvent.Done done = loop.run(
                new TraceContext("req_1", ""), null, List.of(Message.user("hi")), events::add, () -> false);

        assertThat(done.fullContent()).isEqualTo("你好");
        assertThat(done.finishReason()).isEqualTo("stop");
        assertThat(events).containsExactly(
                new AgentEvent.Delta("req_1", "你"),
                new AgentEvent.Delta("req_1", "好"),
                new AgentEvent.Done("req_1", "stop", "你好", new StreamChunk.Usage(4, 2, 6)));
    }

    @Test
    void shouldPreserveLengthFinishReasonWithoutPretendingComplete() {
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            onChunk.accept(StreamChunk.text("截断一半"));
            onChunk.accept(StreamChunk.finish("length", null));
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", fake));
        List<AgentEvent> events = new ArrayList<>();

        AgentEvent.Done done = loop.run(
                new TraceContext("req_2", ""), "test-model", List.of(Message.user("写长文")), events::add, () -> false);

        assertThat(done.finishReason()).isEqualTo("length");
    }

    @Test
    void shouldFailWithSpecificCodeWhenModelReturnsEmptyText() {
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            onChunk.accept(StreamChunk.text("   "));
            onChunk.accept(StreamChunk.finish("stop", null));
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", fake));
        List<AgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> loop.run(
                new TraceContext("req_3", ""), "test-model", List.of(Message.user("hi")), events::add, () -> false))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.MODEL_EMPTY_RESPONSE));

        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.Error.class);
        assertThat(((AgentEvent.Error) events.get(1)).code()).isEqualTo("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void shouldEmitErrorEventWhenAdapterFails() {
        LlmAdapter failing = (model, msgs, tools, onChunk, cancelled) -> {
            throw new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE, "upstream down");
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", failing));
        List<AgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> loop.run(
                new TraceContext("req_4", ""), "test-model", List.of(Message.user("hi")), events::add, () -> false))
                .isInstanceOf(Failure.class);

        assertThat(events).containsExactly(new AgentEvent.Error("req_4", "MODEL_UNAVAILABLE", "upstream down"));
    }

    @Test
    void shouldAssembleChunkedToolCallsExecuteToolAndContinueToFinalAnswer() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new Tool() {
            @Override public String name() { return "echo_add"; }
            @Override public String description() { return "add"; }
            @Override public String parametersSchemaJson() {
                return "{\"type\":\"object\",\"required\":[\"expr\"],\"properties\":{\"expr\":{\"type\":\"string\"}}}";
            }
            @Override public String execute(String argumentsJson) { return "5"; }
        });

        AtomicInteger step = new AtomicInteger();
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            if (step.incrementAndGet() == 1) {
                // 模拟流式工具调用参数被切成两片下发
                onChunk.accept(new StreamChunk("", List.of(
                        new StreamChunk.ToolCallDelta(0, "call_1", "echo_add", "{\"expr\":\"")), null, null));
                onChunk.accept(new StreamChunk("", List.of(
                        new StreamChunk.ToolCallDelta(0, null, null, "2+3\"}")), "tool_calls", null));
            } else {
                // 第二轮应该能收到 tool 角色回填结果 "5"
                assertThat(msgs).anyMatch(m -> "tool".equals(m.role()) && "5".equals(m.content()));
                onChunk.accept(StreamChunk.text("答案是5"));
                onChunk.accept(StreamChunk.finish("stop", new StreamChunk.Usage(10, 5, 15)));
            }
        };

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            AgentLoop loop = new AgentLoop(
                    new ModelRegistry("test-model", fake),
                    toolRegistry,
                    new ToolExecutor(toolRegistry, new ToolArgumentsParser(), pool),
                    new ContextAssembler(new TokenEstimator()),
                    4000);
            List<AgentEvent> events = new ArrayList<>();

            AgentEvent.Done done = loop.run(
                    new TraceContext("req_5", "conv_1"),
                    "test-model",
                    List.of(Message.user("帮我算2+3")),
                    new RunBudget(),
                    new Cancellation(),
                    events::add);

            assertThat(done.fullContent()).isEqualTo("答案是5");
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolStarted);
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolFinished tf && "5".equals(tf.outputPreview()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void shouldFailWhenBudgetMaxStepsExceededInToolLoop() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new Tool() {
            @Override public String name() { return "ping"; }
            @Override public String description() { return "ping"; }
            @Override public String parametersSchemaJson() { return "{\"type\":\"object\"}"; }
            @Override public String execute(String argumentsJson) { return "pong"; }
        });

        LlmAdapter endlessToolCaller = (model, msgs, tools, onChunk, cancelled) -> onChunk.accept(
                new StreamChunk("", List.of(new StreamChunk.ToolCallDelta(0, "c1", "ping", "{}")), "tool_calls", null));

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            AgentLoop loop = new AgentLoop(
                    new ModelRegistry("test-model", endlessToolCaller),
                    toolRegistry,
                    new ToolExecutor(toolRegistry, new ToolArgumentsParser(), pool),
                    new ContextAssembler(new TokenEstimator()),
                    4000);
            RunBudget tightBudget = new RunBudget(2, Duration.ofSeconds(10), 10_000);
            List<AgentEvent> events = new ArrayList<>();

            assertThatThrownBy(() -> loop.run(
                    new TraceContext("req_budget", ""),
                    "test-model",
                    List.of(Message.user("loop")),
                    tightBudget,
                    new Cancellation(),
                    events::add))
                    .isInstanceOf(Failure.class)
                    .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.BUDGET_EXCEEDED));
            assertThat(events.get(events.size() - 1))
                    .isInstanceOfSatisfying(AgentEvent.Error.class, err ->
                            assertThat(err.code()).isEqualTo("BUDGET_EXCEEDED"));
        } finally {
            pool.shutdownNow();
        }
    }
}
