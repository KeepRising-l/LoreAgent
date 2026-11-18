package cn.keeprising.app;

import cn.keeprising.core.*;
import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.Message;
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
 * 对话流式 HTTP 入口（门口接待员）：接住浏览器发来的 POST /api/chat，
 * 负责同会话并发互斥与幂等回放（RunRegistry）、把 AgentEvent 转成 SSE 流，以及断连取消传播。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    // 显式放大超时到 5 分钟，防止 Spring 默认 30 秒把长回答掐断（坑 #3）
    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final long HEARTBEAT_INTERVAL_SEC = 5L;

    private final AgentLoop agentLoop;
    private final RunRegistry runRegistry;
    private final ExecutorService chatExecutor;
    private final ScheduledExecutorService heartbeatExecutor;

    public ChatController(
            AgentLoop agentLoop,
            RunRegistry runRegistry,
            ExecutorService chatExecutor,
            ScheduledExecutorService sseHeartbeatExecutor) {
        this.agentLoop = agentLoop;
        this.runRegistry = runRegistry;
        this.chatExecutor = chatExecutor;
        this.heartbeatExecutor = sseHeartbeatExecutor;
    }

    @PostMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "message 不能为空");
        }
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? "default"
                : request.conversationId();
        String clientRequestId = (request.clientRequestId() == null || request.clientRequestId().isBlank())
                ? null
                : request.clientRequestId();
        String traceId = clientRequestId != null ? clientRequestId : UUID.randomUUID().toString();
        TraceContext trace = new TraceContext(traceId, conversationId);

        Cancellation cancellation = new Cancellation();
        // 1. 抢占会话锁与幂等键：同会话正在跑或重复进行中直接抛 409 SESSION_BUSY；已完成则直接回放
        RunRegistry.AcquireResult acquired = runRegistry.acquire(conversationId, clientRequestId, cancellation);
        if (acquired.isReplay()) {
            return replayDoneStream(acquired.replayDone());
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Object sendLock = new Object();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(ex -> cancellation.cancel());

        // 2. 等待首字或慢工具期间靠每 5 秒心跳探测写失败，客户端 Ctrl-C 后第一时间拉起取消
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendCommentSafe(emitter, sendLock, cancellation),
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        Future<?> taskFuture = chatExecutor.submit(() -> {
            try {
                log.info("{} event=chat_start model={}", trace.logPrefix(), request.model());
                AgentEvent.Done done = agentLoop.run(
                        trace,
                        request.model(),
                        List.of(Message.user(request.message())),
                        new RunBudget(),
                        cancellation,
                        event -> sendEventSafe(emitter, sendLock, cancellation, event));
                runRegistry.complete(conversationId, clientRequestId, done);
                completeSafe(emitter, sendLock);
            } catch (Failure failure) {
                runRegistry.abort(conversationId, clientRequestId);
                log.warn("{} event=chat_failed code={}", trace.logPrefix(), failure.code());
                completeSafe(emitter, sendLock);
            } catch (RuntimeException e) {
                runRegistry.abort(conversationId, clientRequestId);
                log.warn("{} event=chat_error message={}", trace.logPrefix(), e.getMessage());
                completeSafe(emitter, sendLock);
            } finally {
                heartbeat.cancel(false);
            }
        });
        // 3. 把后台任务中断钩子挂进 Cancellation，确保取消信号直接打断阻塞中的 HTTP/工具线程
        cancellation.onCancel(() -> taskFuture.cancel(true));
        return emitter;
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String, Object>> handleFailure(Failure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
    }

    // 幂等回放：已完成请求不再重跑模型，直接按 SSE 流吐出完整回答与 done 事件
    private SseEmitter replayDoneStream(AgentEvent.Done done) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Object lock = new Object();
        Cancellation noop = new Cancellation();
        chatExecutor.submit(() -> {
            sendEventSafe(emitter, lock, noop, new AgentEvent.Delta(done.requestId(), done.fullContent()));
            sendEventSafe(emitter, lock, noop, done);
            completeSafe(emitter, lock);
        });
        return emitter;
    }

    private void sendEventSafe(
            SseEmitter emitter, Object sendLock, Cancellation cancellation, AgentEvent event) {
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
                            .data(Map.of("toolCallId", ts.toolCallId(),
                                    "toolName", ts.toolName(),
                                    "arguments", ts.argumentsJson())));
                } else if (event instanceof AgentEvent.ToolFinished tf) {
                    emitter.send(SseEmitter.event().name("tool_finished")
                            .data(Map.of("toolCallId", tf.toolCallId(), "toolName", tf.toolName(),
                                    "success", tf.success(), "output", tf.outputPreview())));
                } else if (event instanceof AgentEvent.Done dn) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", dn.requestId());
                    payload.put("finishReason", dn.finishReason());
                    payload.put("usage", dn.usage());
                    emitter.send(SseEmitter.event().name("done").data(payload));
                } else if (event instanceof AgentEvent.Error err) {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("code", err.code(), "message", err.message())));
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

    public record ChatRequest(String message, String model, String clientRequestId, String conversationId) {
    }
}
