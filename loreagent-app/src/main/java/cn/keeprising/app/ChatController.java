package cn.keeprising.app;

import cn.keeprising.core.AgentLoop;
import cn.keeprising.core.Failure;
import cn.keeprising.core.TraceContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话流式 HTTP 入口（门口接待员）：接住浏览器发来的 POST /api/chat，
 * 把内核 AgentLoop 推出来的 AgentEvent 转成浏览器能一条条收到的 SSE 流，并负责监听取消/停止。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    // 显式放大超时到 5 分钟，防止 Spring 默认 30 秒把长回答掐断（坑 #3）
    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final long HEARTBEAT_INTERVAL_SEC = 5L;

    private final AgentLoop agentLoop;
    private final ExecutorService chatExecutor;
    private final ScheduledExecutorService heartbeatExecutor;

    public ChatController(
            AgentLoop agentLoop,
            ExecutorService chatExecutor,
            ScheduledExecutorService sseHeartbeatExecutor) {
        this.agentLoop = agentLoop;
        this.chatExecutor = chatExecutor;
        this.heartbeatExecutor = sseHeartbeatExecutor;
    }

    @PostMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) ChatRequest request) {
        // 1. 先拦空消息：直接抛 Failure，由下面的 handleFailure 转成 400 JSON 返回
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "message 不能为空");
        }
        // 2. 确定本次请求的工单号（前端没传就现场生成一个 UUID）
        String requestId = (request.clientRequestId() == null || request.clientRequestId().isBlank())
                ? UUID.randomUUID().toString()
                : request.clientRequestId();
        TraceContext trace = new TraceContext(requestId, "");

        // 3. 创建长连接通道 SseEmitter，并准备取消开关和发送锁（SseEmitter 不是线程安全的，心跳和吐字不能同时写）
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Object sendLock = new Object();

        // 连接正常结束、超时或出错时，都把取消开关拉成 true，通知底层停下来
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(ex -> cancelled.set(true));

        // 4. 等待首字期间 Tomcat 不会主动发现客户端断开，靠每 5 秒发一次空注释心跳探测写失败，第一时间拉起取消开关
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendCommentSafe(emitter, sendLock, cancelled),
                HEARTBEAT_INTERVAL_SEC,
                HEARTBEAT_INTERVAL_SEC,
                TimeUnit.SECONDS);

        // 5. 把真正耗时的模型调用丢进后台线程池，HTTP 线程立刻把 emitter 返回给浏览器
        chatExecutor.submit(() -> {
            try {
                log.info("{} event=chat_start model={}", trace.logPrefix(), request.model());
                agentLoop.run(
                        trace,
                        request.model(),
                        List.of(Message.user(request.message())),
                        event -> sendEventSafe(emitter, sendLock, cancelled, event),
                        cancelled::get);
                completeSafe(emitter, sendLock);
            } catch (Failure failure) {
                log.warn("{} event=chat_failed code={}", trace.logPrefix(), failure.code());
                completeSafe(emitter, sendLock);
            } finally {
                // 不管成功还是失败，结束时一定要关掉定时心跳，防止一直空转
                heartbeat.cancel(false);
            }
        });

        return emitter;
    }

    // 还没开始推流时（如空消息 400）抛出的 Failure，直接转成标准 JSON 错误响应
    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String, Object>> handleFailure(Failure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
    }

    // 加锁安全地把 AgentEvent 写进 SSE 流；如果写入时发现客户端已经断开，立刻把 cancelled 设为 true
    private void sendEventSafe(
            SseEmitter emitter, Object sendLock, AtomicBoolean cancelled, AgentEvent event) {
        if (cancelled.get()) {
            return;
        }
        synchronized (sendLock) {
            if (cancelled.get()) {
                return;
            }
            try {
                if (event instanceof AgentEvent.Delta d) {
                    emitter.send(SseEmitter.event().name("delta").data(Map.of("text", d.text())));
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
                cancelled.set(true);
            }
        }
    }

    // 发一条前端会自动忽略的 ": ping" SSE 注释心跳，专门用来探测浏览器是不是已经点停止断开了
    private void sendCommentSafe(SseEmitter emitter, Object sendLock, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        synchronized (sendLock) {
            if (cancelled.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                cancelled.set(true);
            }
        }
    }

    // 安全关闭 SSE 流（防重复 complete 抛 IllegalStateException）
    private void completeSafe(SseEmitter emitter, Object sendLock) {
        synchronized (sendLock) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    // 前端 POST /api/chat 传进来的请求体
    public record ChatRequest(String message, String model, String clientRequestId) {
    }
}
