package cn.keeprising.core;

import cn.keeprising.spi.StreamChunk;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 单次运行的资源预算闸门：防模型死循环调工具、防单次对话无限耗时或烧光 token。
 */
public class RunBudget {

    private final int maxSteps;
    private final Duration maxDuration;
    private final int maxTotalTokens;
    private final Instant deadline;

    private int stepsUsed;
    private int tokensUsed;

    public RunBudget() {
        this(6, Duration.ofSeconds(120), 32_000);
    }

    public RunBudget(int maxSteps, Duration maxDuration, int maxTotalTokens) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps 必须大于 0");
        }
        Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration 必须大于 0");
        }
        if (maxTotalTokens <= 0) {
            throw new IllegalArgumentException("maxTotalTokens 必须大于 0");
        }
        this.maxSteps = maxSteps;
        this.maxDuration = maxDuration;
        this.maxTotalTokens = maxTotalTokens;
        this.deadline = Instant.now().plus(maxDuration);
    }

    // 每次准备调大模型前检查一次：没取消、没超时、步数和 token 都没花超
    public void checkBeforeStep(Cancellation cancellation) {
        if (cancellation != null) {
            cancellation.throwIfCancelled();
        }
        if (Instant.now().isAfter(deadline)) {
            throw new Failure(Failure.ErrorCode.BUDGET_EXCEEDED,
                    "本轮执行总耗时超出预算上限（" + maxDuration.toSeconds() + " 秒）");
        }
        if (stepsUsed >= maxSteps) {
            throw new Failure(Failure.ErrorCode.BUDGET_EXCEEDED,
                    "工具调用步数超出上限（最多 " + maxSteps + " 步）");
        }
        if (tokensUsed > maxTotalTokens) {
            throw new Failure(Failure.ErrorCode.BUDGET_EXCEEDED,
                    "本轮累计 token 超出上限（已用 " + tokensUsed + "，上限 " + maxTotalTokens + "）");
        }
    }

    // 扣减一步执行名额
    public void consumeStep() {
        stepsUsed++;
    }

    // 记下本轮模型返回的 token 用量，供下一步开头校验是否超预算
    public void recordUsage(StreamChunk.Usage usage) {
        if (usage != null) {
            int delta = usage.totalTokens() > 0
                    ? usage.totalTokens()
                    : usage.promptTokens() + usage.completionTokens();
            tokensUsed += Math.max(0, delta);
        }
    }

    // 给 ToolExecutor 用的剩余时间预算，防止工具把总超时时间拖穿
    public Duration remainingDuration() {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public int stepsUsed() {
        return stepsUsed;
    }

    public int tokensUsed() {
        return tokensUsed;
    }
}
