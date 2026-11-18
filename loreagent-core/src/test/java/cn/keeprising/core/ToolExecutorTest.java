package cn.keeprising.core;

import cn.keeprising.spi.Tool;
import cn.keeprising.spi.ToolCall;
import cn.keeprising.spi.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutorTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void shouldRunToolsConcurrentlyAndPreserveOriginalCallOrder() {
        ToolRegistry registry = new ToolRegistry();
        // 第一个工具故意比第二个更慢，验证总耗时≈最慢那个且结果仍按 call_1、call_2 顺序回填
        registry.register(slowTool("slow_first", 220, "first-done"));
        registry.register(slowTool("fast_second", 60, "second-done"));

        ToolExecutor executor = new ToolExecutor(registry, new ToolArgumentsParser(), pool);
        long startMs = System.currentTimeMillis();

        List<ToolResult> results = executor.executeAll(
                new TraceContext("req_t1", ""),
                List.of(
                        new ToolCall("call_1", "slow_first", "{}"),
                        new ToolCall("call_2", "fast_second", "{}")),
                new Cancellation(),
                Duration.ofSeconds(5));

        long costMs = System.currentTimeMillis() - startMs;
        assertThat(costMs).isLessThan(380);
        assertThat(results).extracting(ToolResult::toolCallId).containsExactly("call_1", "call_2");
        assertThat(results).extracting(ToolResult::content).containsExactly("first-done", "second-done");
    }

    @Test
    void shouldInterruptRunningToolThreadsImmediatelyWhenCancelled() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interruptedInTool = new AtomicBoolean(false);
        CountDownLatch exited = new CountDownLatch(1);

        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "blocking"; }
            @Override public String description() { return "blocking"; }
            @Override public String parametersSchemaJson() { return "{\"type\":\"object\"}"; }
            @Override
            public String execute(String argumentsJson) {
                entered.countDown();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    interruptedInTool.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    exited.countDown();
                }
                return "done";
            }
        });

        Cancellation cancellation = new Cancellation();
        ExecutorService triggerPool = Executors.newSingleThreadExecutor();
        try {
            triggerPool.submit(() -> {
                try {
                    entered.await(2, TimeUnit.SECONDS);
                    cancellation.cancel();
                } catch (InterruptedException ignored) {
                }
            });

            ToolExecutor executor = new ToolExecutor(registry, new ToolArgumentsParser(), pool);
            assertThatThrownBy(() -> executor.executeAll(
                    new TraceContext("req_cancel", ""),
                    List.of(new ToolCall("c1", "blocking", "{}")),
                    cancellation,
                    Duration.ofSeconds(5)))
                    .isInstanceOf(Failure.class)
                    .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.CANCELLED));

            assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interruptedInTool.get()).isTrue();
        } finally {
            triggerPool.shutdownNow();
        }
    }

    @Test
    void shouldFailBeforeExecutionWhenToolArgumentsViolateSchema() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(slowTool("need_expr", 10, "ok", "{\"type\":\"object\",\"required\":[\"expr\"]}"));
        ToolExecutor executor = new ToolExecutor(registry, new ToolArgumentsParser(), pool);

        assertThatThrownBy(() -> executor.executeAll(
                new TraceContext("req_bad", ""),
                List.of(new ToolCall("c1", "need_expr", "{}")),
                new Cancellation(),
                Duration.ofSeconds(5)))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));
    }

    private Tool slowTool(String name, long sleepMs, String output) {
        return slowTool(name, sleepMs, output, "{\"type\":\"object\"}");
    }

    private Tool slowTool(String name, long sleepMs, String output, String schema) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public String parametersSchemaJson() { return schema; }
            @Override
            public String execute(String argumentsJson) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Failure(Failure.ErrorCode.CANCELLED, "中断");
                }
                return output;
            }
        };
    }
}
