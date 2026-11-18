package cn.keeprising.core;

import cn.keeprising.spi.Message;
import cn.keeprising.spi.StreamChunk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleAdapterTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldStreamSplitChunksAndUsageFromFakeServer() throws Exception {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        startServer(exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                // 故意把一行 JSON 拆成两次写入，验证适配器 + SseParser 的拼片能力
                out.write("data: {\"choices\":[{\"delta\":{\"conte".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("nt\":\"你好\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.write(("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });

        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(baseUrl(), "secret-key");
        List<StreamChunk> chunks = new ArrayList<>();
        adapter.streamChat("gpt-4o-mini", List.of(Message.user("hi")), List.of(), chunks::add, () -> false);

        assertThat(capturedAuth.get()).isEqualTo("Bearer secret-key");
        assertThat(capturedBody.get()).contains("\"stream\":true").contains("\"model\":\"gpt-4o-mini\"");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).textDelta()).isEqualTo("你好");
        assertThat(chunks.get(1).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(1).usage().totalTokens()).isEqualTo(5);
    }

    @Test
    void shouldNotRetryOn400BadRequest() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            hits.incrementAndGet();
            byte[] err = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, err.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(err);
            }
        });

        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(
                baseUrl(), "k", Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        assertThatThrownBy(() -> adapter.streamChat(
                "m", List.of(Message.user("hi")), List.of(), c -> {}, () -> false))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));
        // 400 属于不可重试错误，只发 1 次请求
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void shouldRetryOnceOn500BeforeStreamStartsAndSucceed() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            if (hits.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });

        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(
                baseUrl(), "k", Duration.ofSeconds(2), Duration.ofSeconds(2), 1);
        List<StreamChunk> chunks = new ArrayList<>();
        adapter.streamChat("m", List.of(Message.user("hi")), List.of(), chunks::add, () -> false);

        assertThat(hits.get()).isEqualTo(2);
        assertThat(chunks).extracting(StreamChunk::textDelta).containsExactly("ok");
    }

    @Test
    void shouldFailWithTimeoutWhenServerRespondsTooSlowly() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(
                baseUrl(), "k", Duration.ofMillis(120), Duration.ofSeconds(1), 0);
        assertThatThrownBy(() -> adapter.streamChat(
                "m", List.of(Message.user("hi")), List.of(), c -> {}, () -> false))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.TIMEOUT));
    }

    @Test
    void shouldDetectAbruptDisconnectMidStreamAndAvoidDuplicateRetry() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        startServer(exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                // 只吐了一半内容就直接关流，不发 finish_reason 也不发 [DONE]
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });

        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(
                baseUrl(), "k", Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        List<StreamChunk> chunks = new ArrayList<>();
        assertThatThrownBy(() -> adapter.streamChat(
                "m", List.of(Message.user("hi")), List.of(), chunks::add, () -> false))
                .isInstanceOf(Failure.class);
        assertThat(chunks).hasSize(1);
        // 已经吐出过分片，不能再重试
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void shouldAbortWhenCancelledDuringStream() throws Exception {
        startServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"第一字\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"第二字\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });

        AtomicBoolean cancelled = new AtomicBoolean(false);
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(baseUrl(), "k");
        List<String> received = new ArrayList<>();
        assertThatThrownBy(() -> adapter.streamChat("m", List.of(Message.user("hi")), List.of(), chunk -> {
            received.add(chunk.textDelta());
            cancelled.set(true);
        }, cancelled::get))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.CANCELLED));
        assertThat(received).containsExactly("第一字");
    }

    @Test
    void modelRegistryShouldResolveDefaultAndRejectUnknownModel() {
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter("http://localhost:8080/v1", "k");
        ModelRegistry registry = new ModelRegistry("gpt-4o-mini", adapter);

        assertThat(registry.resolveModel(null)).isEqualTo("gpt-4o-mini");
        assertThat(registry.get("")).isSameAs(adapter);
        assertThatThrownBy(() -> registry.get("unknown-model"))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.MODEL_NOT_FOUND));
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
