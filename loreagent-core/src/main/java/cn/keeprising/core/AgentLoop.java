package cn.keeprising.core;

import cn.keeprising.spi.*;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 核心总控循环（Phase 1b 完整版）：
 * 串起“工单追踪 → 预算闸门 → 上下文裁剪 → 模型流式吐字 → 工具并发执行与回填 → 终态输出”。
 * 对 Web 层保持零感知，全部进度通过 Consumer<AgentEvent> 向外推流。
 */
public class AgentLoop {

    private static final int DEFAULT_MAX_PROMPT_TOKENS = 16_000;

    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ContextAssembler contextAssembler;
    private final int maxPromptTokens;

    public AgentLoop(ModelRegistry modelRegistry) {
        this(modelRegistry, new ToolRegistry(), null, new ContextAssembler(new TokenEstimator()), DEFAULT_MAX_PROMPT_TOKENS);
    }

    public AgentLoop(
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ContextAssembler contextAssembler,
            int maxPromptTokens) {
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry 不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry 不能为空");
        this.toolExecutor = toolExecutor;
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler 不能为空");
        if (maxPromptTokens <= 0) {
            throw new IllegalArgumentException("maxPromptTokens 必须大于 0");
        }
        this.maxPromptTokens = maxPromptTokens;
    }

    // 兼容 Phase 1a 单测的简化入口
    public AgentEvent.Done run(
            TraceContext trace,
            String modelName,
            List<Message> messages,
            Consumer<AgentEvent> onEvent,
            BooleanSupplier cancelled) {
        Cancellation cancellation = new Cancellation();
        BooleanSupplier combinedCancel = cancelled == null
                ? cancellation
                : () -> cancellation.isCancelled() || cancelled.getAsBoolean();
        return executeLoop(trace, modelName, messages, new RunBudget(), cancellation, combinedCancel, onEvent);
    }

    // Phase 1b 主入口：显式传入 RunBudget 与 Cancellation，支持多步工具调用与彻底取消
    public AgentEvent.Done run(
            TraceContext trace,
            String modelName,
            List<Message> messages,
            RunBudget budget,
            Cancellation cancellation,
            Consumer<AgentEvent> onEvent) {
        RunBudget effectiveBudget = budget == null ? new RunBudget() : budget;
        Cancellation effectiveCancel = cancellation == null ? new Cancellation() : cancellation;
        return executeLoop(trace, modelName, messages, effectiveBudget, effectiveCancel, effectiveCancel, onEvent);
    }

    private AgentEvent.Done executeLoop(
            TraceContext trace,
            String modelName,
            List<Message> messages,
            RunBudget budget,
            Cancellation cancellation,
            BooleanSupplier cancelProbe,
            Consumer<AgentEvent> onEvent) {
        Objects.requireNonNull(trace, "trace 不能为空");
        Objects.requireNonNull(onEvent, "onEvent 不能为空");

        // 1. 把工单号挂到当前线程，结束后自动清理，防止线程池串号
        try (TraceContext.Scope ignored = trace.bind()) {
            if (messages == null || messages.isEmpty()) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "对话消息不能为空");
            }
            String resolvedModel = modelRegistry.resolveModel(modelName);
            LlmAdapter adapter = modelRegistry.get(resolvedModel);
            List<Tool> availableTools = toolRegistry.all();
            List<Message> conversation = new ArrayList<>(messages);

            while (true) {
                // 2. 每轮调模型前先过预算与取消闸门
                if (cancelProbe.getAsBoolean()) {
                    cancellation.cancel();
                }
                budget.checkBeforeStep(cancellation);
                budget.consumeStep();

                // 3. 按预算从最新一轮往回裁剪上下文，保证工具 schema 和当前轮完整保留
                List<Message> promptMessages = contextAssembler.assemble(maxPromptTokens, conversation, availableTools);

                StepResult step = invokeModelStep(
                        trace, adapter, resolvedModel, promptMessages, availableTools, cancelProbe, onEvent);
                budget.recordUsage(step.usage());
                if (cancelProbe.getAsBoolean()) {
                    cancellation.cancel();
                }
                cancellation.throwIfCancelled();

                // 4. 如果模型没要求调工具，说明这轮已经给出最终回答
                if (step.toolCalls().isEmpty()) {
                    if (step.text().isBlank()) {
                        throw new Failure(Failure.ErrorCode.MODEL_EMPTY_RESPONSE, "模型返回空内容，请检查提示词或上游状态");
                    }
                    AgentEvent.Done done = new AgentEvent.Done(
                            trace.requestId(), step.finishReason(), step.text(), step.usage());
                    onEvent.accept(done);
                    return done;
                }

                // 5. 模型要求调工具：记录助手调用指令 -> 并发执行工具 -> 按原顺序回填结果，再进下一轮模型生成
                if (toolExecutor == null) {
                    throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, "未配置 ToolExecutor，无法执行工具调用");
                }
                conversation.add(Message.assistantTools(step.text(), step.toolCalls()));
                for (ToolCall tc : step.toolCalls()) {
                    onEvent.accept(new AgentEvent.ToolStarted(
                            trace.requestId(), tc.id(), tc.name(), tc.argumentsJson()));
                }
                List<ToolResult> results = toolExecutor.executeAll(
                        trace, step.toolCalls(), cancellation, budget.remainingDuration());
                for (ToolResult res : results) {
                    onEvent.accept(new AgentEvent.ToolFinished(
                            trace.requestId(), res.toolCallId(), res.name(), res.success(), res.content()));
                    conversation.add(Message.tool(res.toolCallId(), res.content()));
                }
            }
        } catch (Failure failure) {
            onEvent.accept(new AgentEvent.Error(trace.requestId(), failure.code().name(), failure.getMessage()));
            throw failure;
        } catch (RuntimeException e) {
            Failure wrapped = new Failure(Failure.ErrorCode.INTERNAL_ERROR, "执行异常: " + e.getMessage(), e);
            onEvent.accept(new AgentEvent.Error(trace.requestId(), wrapped.code().name(), wrapped.getMessage()));
            throw wrapped;
        }
    }

    private StepResult invokeModelStep(
            TraceContext trace,
            LlmAdapter adapter,
            String model,
            List<Message> promptMessages,
            List<Tool> tools,
            BooleanSupplier cancelProbe,
            Consumer<AgentEvent> onEvent) {
        StringBuilder stepText = new StringBuilder();
        String[] finishReason = {null};
        StreamChunk.Usage[] usage = {null};
        // 用 TreeMap 按模型返回的 index 升序保存并发工具指令碎片，保证顺序稳定
        Map<Integer, ToolCallAccumulator> toolAccumulators = new TreeMap<>();

        adapter.streamChat(model, promptMessages, tools, chunk -> {
            if (!chunk.textDelta().isEmpty()) {
                stepText.append(chunk.textDelta());
                onEvent.accept(new AgentEvent.Delta(trace.requestId(), chunk.textDelta()));
            }
            for (StreamChunk.ToolCallDelta delta : chunk.toolCallDeltas()) {
                toolAccumulators
                        .computeIfAbsent(delta.index(), idx -> new ToolCallAccumulator(idx))
                        .accept(delta);
            }
            if (chunk.finishReason() != null && !chunk.finishReason().isBlank()) {
                finishReason[0] = chunk.finishReason();
            }
            if (chunk.usage() != null) {
                usage[0] = chunk.usage();
            }
        }, cancelProbe);

        List<ToolCall> toolCalls = toolAccumulators.values().stream()
                .map(ToolCallAccumulator::build)
                .toList();
        return new StepResult(stepText.toString(), toolCalls, finishReason[0], usage[0]);
    }

    private record StepResult(
            String text, List<ToolCall> toolCalls, String finishReason, StreamChunk.Usage usage) {
    }

    // 用来把流式拆碎的 ToolCallDelta（id、name、一段段 argumentsDelta）按 index 拼回完整的 ToolCall
    private static final class ToolCallAccumulator {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder args = new StringBuilder();

        private ToolCallAccumulator(int index) {
            this.index = index;
        }

        private void accept(StreamChunk.ToolCallDelta delta) {
            if (delta.id() != null && !delta.id().isBlank()) {
                this.id = delta.id();
            }
            if (delta.name() != null && !delta.name().isBlank()) {
                this.name = delta.name();
            }
            if (!delta.argumentsDelta().isEmpty()) {
                this.args.append(delta.argumentsDelta());
            }
        }

        private ToolCall build() {
            String resolvedId = (id == null || id.isBlank()) ? ("call_" + index) : id;
            if (name == null || name.isBlank()) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "模型返回的工具调用缺少工具名 index=" + index);
            }
            return new ToolCall(resolvedId, name, args.toString());
        }
    }
}
