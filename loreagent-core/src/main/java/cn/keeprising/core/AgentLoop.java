package cn.keeprising.core;

import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.LlmAdapter;
import cn.keeprising.spi.Message;
import cn.keeprising.spi.StreamChunk;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 核心总控循环：串起“追踪工单 → 找模型 → 流式吐字 → 检查空回复 → 发完成/报错事件”。
 * Phase 1a 先做单轮直接回答（还不调工具），它不碰任何 Spring Web 对象，全靠 onEvent 回调往外递消息。
 */
public class AgentLoop {

    private final ModelRegistry modelRegistry;

    public AgentLoop(ModelRegistry modelRegistry) {
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry 不能为空");
    }

    public AgentEvent.Done run(
            TraceContext trace,
            String modelName,
            List<Message> messages,
            Consumer<AgentEvent> onEvent,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(trace, "trace 不能为空");
        Objects.requireNonNull(onEvent, "onEvent 不能为空");
        BooleanSupplier cancelProbe = cancelled == null ? () -> false : cancelled;

        // 1. 把工单号挂到当前线程上，结束时自动摘掉
        try (TraceContext.Scope ignored = trace.bind()) {
            if (messages == null || messages.isEmpty()) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "对话消息不能为空");
            }
            // 2. 确定要用的模型名，并从通讯录拿到对应的发请求客户端
            String resolvedModel = modelRegistry.resolveModel(modelName);
            LlmAdapter adapter = modelRegistry.get(resolvedModel);

            StringBuilder fullText = new StringBuilder();
            String[] finishReason = {null};
            StreamChunk.Usage[] usage = {null};

            // 3. 调大模型吐字：每收到一块文字就攒进 fullText，同时立刻发一个 Delta 事件推给外层
            adapter.streamChat(resolvedModel, messages, List.of(), chunk -> {
                if (!chunk.textDelta().isEmpty()) {
                    fullText.append(chunk.textDelta());
                    onEvent.accept(new AgentEvent.Delta(trace.requestId(), chunk.textDelta()));
                }
                if (chunk.finishReason() != null && !chunk.finishReason().isBlank()) {
                    finishReason[0] = chunk.finishReason();
                }
                if (chunk.usage() != null) {
                    usage[0] = chunk.usage();
                }
            }, cancelProbe);

            // 4. 吐完检查：如果模型只回了一堆空格，算异常而不是假装成功
            String content = fullText.toString();
            if (content.isBlank()) {
                throw new Failure(Failure.ErrorCode.MODEL_EMPTY_RESPONSE, "模型返回空内容，请检查提示词或上游状态");
            }
            // 5. 顺利收尾：发 Done 事件给外层（保留模型原样的 finishReason，如 length 截断）
            AgentEvent.Done done = new AgentEvent.Done(trace.requestId(), finishReason[0], content, usage[0]);
            onEvent.accept(done);
            return done;
        } catch (Failure failure) {
            // 遇到可预期报错：先发 Error 事件通知前端，再把异常往外抛给日志/调用方
            onEvent.accept(new AgentEvent.Error(trace.requestId(), failure.code().name(), failure.getMessage()));
            throw failure;
        } catch (RuntimeException e) {
            // 遇到意料外的运行时异常：统一包成 INTERNAL_ERROR，不把裸异常漏出去
            Failure wrapped = new Failure(Failure.ErrorCode.INTERNAL_ERROR, "执行异常: " + e.getMessage(), e);
            onEvent.accept(new AgentEvent.Error(trace.requestId(), wrapped.code().name(), wrapped.getMessage()));
            throw wrapped;
        }
    }
}
