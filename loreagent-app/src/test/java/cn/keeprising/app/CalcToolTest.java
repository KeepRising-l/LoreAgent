package cn.keeprising.app;

import cn.keeprising.core.Failure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalcToolTest {

    private final CalcTool calcTool = new CalcTool();

    @Test
    void shouldEvaluateArithmeticExpressionsAndRespectPrecedence() {
        assertThat(calcTool.execute("{\"expr\":\"2+3\"}")).isEqualTo("5");
        assertThat(calcTool.execute("{\"expr\":\"2 + 3 * 4\"}")).isEqualTo("14");
        assertThat(calcTool.execute("{\"expr\":\"(10 - 2) / 4\"}")).isEqualTo("2");
        assertThat(calcTool.execute("{\"expr\":\"-5 + 1.5\"}")).isEqualTo("-3.5");
    }

    @Test
    void shouldRejectDivisionByZeroAndInvalidExpressions() {
        assertThatThrownBy(() -> calcTool.execute("{\"expr\":\"10 / 0\"}"))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));

        assertThatThrownBy(() -> calcTool.execute("{\"expr\":\"2 + abc\"}"))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void shouldBeStatelessAndSafeUnderConcurrentExecution() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                int n = i;
                tasks.add(() -> calcTool.execute("{\"expr\":\"" + n + "*2\"}"));
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (int i = 0; i < 20; i++) {
                assertThat(futures.get(i).get()).isEqualTo(String.valueOf((i + 1) * 2));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
