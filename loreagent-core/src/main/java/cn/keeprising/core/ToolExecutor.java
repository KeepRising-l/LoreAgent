package cn.keeprising.core;

import cn.keeprising.spi.Tool;
import cn.keeprising.spi.ToolCall;
import cn.keeprising.spi.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具并发执行器：
 * 同一步里模型可能同时发起多个 ToolCall，这里先把所有参数校验过关，再并发扔进线程池跑，
 * 最后严格按模型原来的调用顺序收集 ToolResult 回填，并支持超时与取消立即打断所有子线程。
 */
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolArgumentsParser parser;
    private final ExecutorService executor;
    private final Duration perToolTimeout;

    public ToolExecutor(ToolRegistry toolRegistry, ToolArgumentsParser parser, ExecutorService executor) {
        this(toolRegistry, parser, executor, Duration.ofSeconds(30));
    }

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentsParser parser,
            ExecutorService executor,
            Duration perToolTimeout) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.parser = Objects.requireNonNull(parser, "parser 不能为空");
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
        this.perToolTimeout = Objects.requireNonNull(perToolTimeout, "perToolTimeout 不能为空");
    }

    public List<ToolResult> executeAll(
            TraceContext trace,
            List<ToolCall> toolCalls,
            Cancellation cancellation,
            Duration budgetRemaining) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        Objects.requireNonNull(trace, "trace 不能为空");
        Cancellation cancel = cancellation == null ? new Cancellation() : cancellation;
        cancel.throwIfCancelled();

        // 1. 先串行做完存在性与 schema 校验，参数不合法立刻失败，不白起线程
        List<Tool> resolvedTools = new ArrayList<>(toolCalls.size());
        for (ToolCall call : toolCalls) {
            Tool tool = toolRegistry.get(call.name());
            parser.parseAndValidate(call.argumentsJson(), tool.parametersSchemaJson());
            resolvedTools.add(tool);
        }

        // 2. 按原顺序并发提交到线程池，并用 trace.wrap 把 requestId 带进工具线程
        List<Future<ToolResult>> futures = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall call = toolCalls.get(i);
            Tool tool = resolvedTools.get(i);
            futures.add(executor.submit(trace.wrap(() -> {
                cancel.throwIfCancelled();
                String output = tool.execute(call.argumentsJson());
                return ToolResult.ok(call.id(), call.name(), output);
            })));
        }

        // 3. 一旦上层取消，立刻向所有还在跑的工具线程发中断，避免线程残留
        cancel.onCancel(() -> cancelRemaining(futures));

        // 4. 严格按原来的调用顺序收结果，总等待上限不超过单个超时和剩余预算的较小值
        Duration effectiveTimeout = (budgetRemaining != null && budgetRemaining.compareTo(perToolTimeout) < 0)
                ? budgetRemaining
                : perToolTimeout;
        long deadlineNanos = System.nanoTime() + Math.max(1L, effectiveTimeout.toNanos());

        List<ToolResult> results = new ArrayList<>(toolCalls.size());
        try {
            for (int i = 0; i < futures.size(); i++) {
                cancel.throwIfCancelled();
                long waitNanos = Math.max(1L, deadlineNanos - System.nanoTime());
                results.add(futures.get(i).get(waitNanos, TimeUnit.NANOSECONDS));
            }
            return List.copyOf(results);
        } catch (CancellationException e) {
            cancelRemaining(futures);
            throw new Failure(Failure.ErrorCode.CANCELLED, "工具执行已取消", e);
        } catch (TimeoutException e) {
            cancelRemaining(futures);
            throw new Failure(Failure.ErrorCode.TIMEOUT, "工具执行超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelRemaining(futures);
            throw new Failure(Failure.ErrorCode.CANCELLED, "工具执行线程被中断", e);
        } catch (ExecutionException e) {
            cancelRemaining(futures);
            if (e.getCause() instanceof Failure failure) {
                throw failure;
            }
            throw new Failure(Failure.ErrorCode.TOOL_EXECUTION_FAILED,
                    "工具执行失败: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private void cancelRemaining(List<Future<ToolResult>> futures) {
        for (Future<ToolResult> future : futures) {
            future.cancel(true);
        }
    }
}
