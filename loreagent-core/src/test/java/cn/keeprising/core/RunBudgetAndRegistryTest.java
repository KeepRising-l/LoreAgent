package cn.keeprising.core;

import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.StreamChunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunBudgetAndRegistryTest {

    @Test
    void runBudgetShouldRejectWhenMaxStepsOrTokensExceeded() {
        RunBudget budget = new RunBudget(2, Duration.ofSeconds(10), 100);
        Cancellation cancel = new Cancellation();

        budget.checkBeforeStep(cancel);
        budget.consumeStep();
        budget.recordUsage(new StreamChunk.Usage(30, 30, 60));

        budget.checkBeforeStep(cancel);
        budget.consumeStep();
        assertThatThrownBy(() -> budget.checkBeforeStep(cancel))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.BUDGET_EXCEEDED));

        RunBudget tokenBudget = new RunBudget(5, Duration.ofSeconds(10), 50);
        tokenBudget.recordUsage(new StreamChunk.Usage(40, 20, 60));
        assertThatThrownBy(() -> tokenBudget.checkBeforeStep(cancel))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.BUDGET_EXCEEDED));
    }

    @Test
    void runRegistryShouldRejectBusySessionAndReplayCompletedRequestId() {
        RunRegistry registry = new RunRegistry();
        Cancellation c1 = new Cancellation();
        Cancellation c2 = new Cancellation();

        RunRegistry.AcquireResult first = registry.acquire("conv_1", "req_1", c1);
        assertThat(first.isReplay()).isFalse();
        assertThat(registry.activeCount()).isEqualTo(1);

        // 同会话并发第二条消息被拒绝
        assertThatThrownBy(() -> registry.acquire("conv_1", "req_2", c2))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.SESSION_BUSY));

        // 同一 clientRequestId 进行中重复提交也被拒绝
        assertThatThrownBy(() -> registry.acquire("conv_other", "req_1", c2))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.SESSION_BUSY));

        // 完成后再次提交同一 clientRequestId 直接命中回放
        AgentEvent.Done done = new AgentEvent.Done("req_1", "stop", "你好", new StreamChunk.Usage(1, 2, 3));
        registry.complete("conv_1", "req_1", done);
        assertThat(registry.activeCount()).isEqualTo(0);

        RunRegistry.AcquireResult replay = registry.acquire("conv_1", "req_1", new Cancellation());
        assertThat(replay.isReplay()).isTrue();
        assertThat(replay.replayDone()).isEqualTo(done);
    }
}
