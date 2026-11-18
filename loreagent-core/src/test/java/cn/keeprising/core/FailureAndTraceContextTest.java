package cn.keeprising.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FailureAndTraceContextTest {

    @Test
    void failureShouldDistinguishRetryableAndNonRetryableErrors() {
        Failure networkError = new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE, "upstream 503");
        Failure badInput = new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "missing content");

        assertThat(networkError.isRetryable()).isTrue();
        assertThat(networkError.httpStatus()).isEqualTo(502);
        assertThat(badInput.isRetryable()).isFalse();
        assertThat(badInput.httpStatus()).isEqualTo(400);
    }

    @Test
    void traceContextShouldBindRestoreAndPropagateAcrossThreads() throws Exception {
        TraceContext outer = new TraceContext("req_1", "conv_1");
        assertThat(TraceContext.current()).isEmpty();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (TraceContext.Scope ignored = outer.bind()) {
            assertThat(TraceContext.current()).contains(outer);
            assertThat(outer.logPrefix()).isEqualTo("requestId=req_1 conversationId=conv_1");

            Future<String> workerId = pool.submit(outer.wrap(() ->
                    TraceContext.current().map(TraceContext::requestId).orElse("")));
            assertThat(workerId.get()).isEqualTo("req_1");
        } finally {
            pool.shutdown();
        }

        assertThat(TraceContext.current()).isEmpty();
    }
}
