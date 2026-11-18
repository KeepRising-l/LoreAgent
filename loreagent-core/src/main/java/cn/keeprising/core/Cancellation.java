package cn.keeprising.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 跨层取消信号：把“前端点停止 / HTTP 断连”一路传到 AgentLoop、ToolExecutor 和模型客户端。
 * 本身实现 BooleanSupplier，可以直接作为探针传进 SPI 的 LlmAdapter，不打破模块依赖方向。
 */
public class Cancellation implements BooleanSupplier {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    // 挂在这里的回调会在 cancel() 时立刻执行（比如中止正在跑的线程池任务）
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    // 触发取消：保证只触发一次，并把已经登记的中止动作全部跑一遍
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable hook : hooks) {
                try {
                    hook.run();
                } catch (RuntimeException ignored) {
                    // 单个清理动作失败不能拦住后面其他资源的释放
                }
            }
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public boolean getAsBoolean() {
        return isCancelled();
    }

    // 注册一个“被取消时要做的收尾动作”；如果登记时已经取消了，当场立刻执行
    public void onCancel(Runnable hook) {
        Objects.requireNonNull(hook, "hook 不能为空");
        hooks.add(hook);
        if (cancelled.get()) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // 在关键节点检查：一旦被取消（或线程被中断），立刻抛 CANCELLED 刹车
    public void throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new Failure(Failure.ErrorCode.CANCELLED, "请求已取消");
        }
    }
}
