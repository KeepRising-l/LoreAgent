package cn.keeprising.core;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 给每次请求发一张“追踪工单号”（requestId 和 conversationId）。
 * 这样不管这条请求跑进哪个方法或子线程，日志里都能印出同一个 requestId，出问题一搜就能顺藤摸瓜。
 */
public record TraceContext(String requestId, String conversationId) {

    // 用来把当前请求的工单号挂在“当前正在干活的线程”上，这样底层工具想打日志随时能拿到
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    public TraceContext {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("TraceContext.requestId 不能为空");
        }
        conversationId = Objects.requireNonNullElse(conversationId, "");
    }

    // 把工单号挂到当前线程上；配合 try (...) 使用，代码块结束自动摘掉，防止线程池里下一个人串用别人的工单号
    public Scope bind() {
        TraceContext previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    // 取出当前线程上挂着的工单号
    public static Optional<TraceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    // 以后多线程并发调工具时，ThreadLocal 不会自动跨线程传，用 wrap 把工单号打包带进子线程
    public <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task, "task 不能为空");
        return () -> {
            try (Scope ignored = bind()) {
                return task.call();
            }
        };
    }

    // 同上，用来包装没有返回值的后台任务
    public Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task 不能为空");
        return () -> {
            try (Scope ignored = bind()) {
                task.run();
            }
        };
    }

    // 统一拼成 "requestId=xxx conversationId=yyy"，打日志时直接用它，格式全项目统一
    public String logPrefix() {
        if (conversationId.isEmpty()) {
            return "requestId=" + requestId;
        }
        return "requestId=" + requestId + " conversationId=" + conversationId;
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
