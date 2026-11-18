package cn.keeprising.app;

import cn.keeprising.core.*;
import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * 对话流式 HTTP 入口（门口接待员）：
 * 委托 ConversationService 完成会话校验、持久化幂等/重试与落库，自己专注 SSE 推流与断连取消。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final long HEARTBEAT_INTERVAL_SEC = 5L;

    private final AgentLoop agentLoop;
    private final ConversationService conversationService;
    private final ExecutorService chatExecutor;
    private final ScheduledExecutorService heartbeatExecutor;

    public ChatController(
            AgentLoop agentLoop,
            ConversationService conversationService,
            ExecutorService chatExecutor,
            ScheduledExecutorService sseHeartbeatExecutor) {
        this.agentLoop = agentLoop;
        this.conversationService = conversationService;
        this.chatExecutor = chatExecutor;
        this.heartbeatExecutor = sseHeartbeatExecutor;
    }

    @PostMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) ChatRequest request) {
        if (request == null) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "请求体不能为空");
        }
        String clientRequestId = (request.clientRequestId() == null || request.clientRequestId().isBlank())
                ? null : request.clientRequestId();

        Cancellation cancellation = new Cancellation();
        ConversationService.TurnStart turn = conversationService.startTurn(
                AppConfig.LOCAL_USER, request.conversationId(), request.message(),
                clientRequestId, request.retryMessageId(), cancellation);
        if (turn.isReplay()) {
            return replayDoneStream(turn.conversationId(), turn.replayRequestId(), turn.replayContent());
        }

        String traceId = clientRequestId != null ? clientRequestId : UUID.randomUUID().toString();
        TraceContext trace = new TraceContext(traceId, turn.conversationId().toString());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Object sendLock = new Object();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(ex -> cancellation.cancel());

        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendCommentSafe(emitter, sendLock, cancellation),
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        Future<?> taskFuture = chatExecutor.submit(() -> {
            StepCollector collector = new StepCollector(turn.conversationId());
            try {
                log.info("{} event=chat_start model={}", trace.logPrefix(), request.model());
                AgentEvent.Done done = agentLoop.run(
                        trace, request.model(), turn.history(), new RunBudget(), cancellation,
                        event -> {
                            collector.accept(event);
                            if (!(event instanceof AgentEvent.Done)) {
                                sendEventSafe(emitter, sendLock, cancellation, event, turn);
                            }
                        });
                // 必须先事务落库成功，再发 SSE done 事件（验收 #6：禁止只吐流不落库静默成功）
                conversationService.persistTurnOutputs(
                        AppConfig.LOCAL_USER, turn.conversationId(), clientRequestId, collector.steps(), done);
                sendEventSafe(emitter, sendLock, cancellation, done, turn);
                completeSafe(emitter, sendLock);
            } catch (Failure failure) {
                conversationService.abortTurn(turn.conversationId(), clientRequestId);
                log.warn("{} event=chat_failed code={}", trace.logPrefix(), failure.code());
                if (failure.code() == Failure.ErrorCode.STORAGE_ERROR) {
                    sendEventSafe(emitter, sendLock, cancellation,
                            new AgentEvent.Error(trace.requestId(), failure.code().name(), failure.getMessage()), turn);
                }
                completeSafe(emitter, sendLock);
            } catch (RuntimeException e) {
                conversationService.abortTurn(turn.conversationId(), clientRequestId);
                log.warn("{} event=chat_error message={}", trace.logPrefix(), e.getMessage());
                completeSafe(emitter, sendLock);
            } finally {
                heartbeat.cancel(false);
            }
        });
        cancellation.onCancel(() -> taskFuture.cancel(true));
        return emitter;
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String, Object>> handleFailure(Failure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
    }

    private SseEmitter replayDoneStream(UUID conversationId, String requestId, String content) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Object lock = new Object();
        Cancellation noop = new Cancellation();
        ConversationService.TurnStart meta = new ConversationService.TurnStart(conversationId, 0L, List.of(), content, requestId);
        chatExecutor.submit(() -> {
            sendEventSafe(emitter, lock, noop, new AgentEvent.Delta(requestId, content), meta);
            sendEventSafe(emitter, lock, noop, new AgentEvent.Done(requestId, "stop", content, null), meta);
            completeSafe(emitter, lock);
        });
        return emitter;
    }

    private void sendEventSafe(
            SseEmitter emitter, Object sendLock, Cancellation cancellation,
            AgentEvent event, ConversationService.TurnStart turn) {
        if (cancellation.isCancelled()) {
            return;
        }
        synchronized (sendLock) {
            if (cancellation.isCancelled()) {
                return;
            }
            try {
                if (event instanceof AgentEvent.Delta d) {
                    emitter.send(SseEmitter.event().name("delta").data(Map.of("text", d.text())));
                } else if (event instanceof AgentEvent.ToolStarted ts) {
                    emitter.send(SseEmitter.event().name("tool_started")
                            .data(Map.of("toolCallId", ts.toolCallId(), "toolName", ts.toolName(),
                                    "arguments", ts.argumentsJson())));
                } else if (event instanceof AgentEvent.ToolFinished tf) {
                    emitter.send(SseEmitter.event().name("tool_finished")
                            .data(Map.of("toolCallId", tf.toolCallId(), "toolName", tf.toolName(),
                                    "success", tf.success(), "output", tf.outputPreview())));
                } else if (event instanceof AgentEvent.Done dn) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", dn.requestId());
                    payload.put("conversationId", turn.conversationId());
                    payload.put("userMessageId", turn.userMessageId());
                    payload.put("finishReason", dn.finishReason());
                    payload.put("usage", dn.usage());
                    emitter.send(SseEmitter.event().name("done").data(payload));
                } else if (event instanceof AgentEvent.Error err) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("code", err.code());
                    payload.put("message", err.message());
                    payload.put("conversationId", turn.conversationId());
                    payload.put("userMessageId", turn.userMessageId());
                    emitter.send(SseEmitter.event().name("error").data(payload));
                }
            } catch (IOException | IllegalStateException e) {
                cancellation.cancel();
            }
        }
    }

    private void sendCommentSafe(SseEmitter emitter, Object sendLock, Cancellation cancellation) {
        if (cancellation.isCancelled()) {
            return;
        }
        synchronized (sendLock) {
            if (cancellation.isCancelled()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                cancellation.cancel();
            }
        }
    }

    private void completeSafe(SseEmitter emitter, Object sendLock) {
        synchronized (sendLock) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    // 收集单轮工具执行过程中的 assistant(tool_calls) 与 tool 消息，确保按顺序一并落库
    private static final class StepCollector {
        private final UUID conversationId;
        private final StringBuilder pendingText = new StringBuilder();
        private final List<ToolCall> pendingCalls = new ArrayList<>();
        private final List<ChatMessage> collected = new ArrayList<>();

        private StepCollector(UUID conversationId) {
            this.conversationId = conversationId;
        }

        private void accept(AgentEvent event) {
            if (event instanceof AgentEvent.Delta d) {
                pendingText.append(d.text());
            } else if (event instanceof AgentEvent.ToolStarted ts) {
                pendingCalls.add(new ToolCall(ts.toolCallId(), ts.toolName(), ts.argumentsJson()));
            } else if (event instanceof AgentEvent.ToolFinished tf) {
                if (!pendingCalls.isEmpty()) {
                    collected.add(new ChatMessage(0L, conversationId, AppConfig.LOCAL_USER, 0,
                            "assistant", pendingText.toString(), List.copyOf(pendingCalls), null, null, null));
                    pendingText.setLength(0);
                    pendingCalls.clear();
                }
                List<ToolCall> ref = List.of(new ToolCall(tf.toolCallId(), tf.toolName(), ""));
                collected.add(new ChatMessage(0L, conversationId, AppConfig.LOCAL_USER, 0,
                        "tool", tf.outputPreview(), ref, null, null, null));
            }
        }

        private List<ChatMessage> steps() {
            return collected;
        }
    }

    public record ChatRequest(
            String message, String model, String clientRequestId, String conversationId, Long retryMessageId) {
    }
}
