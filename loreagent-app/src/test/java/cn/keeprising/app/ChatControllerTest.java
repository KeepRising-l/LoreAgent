package cn.keeprising.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerTest {

    private static HttpServer fakeLlmServer;
    private static ExecutorService fakeLlmPool;
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicInteger LLM_HITS = new AtomicInteger();
    private static final AtomicReference<CountDownLatch> SLOW_STARTED = new AtomicReference<>(new CountDownLatch(1));
    private static final AtomicReference<CountDownLatch> SLOW_RELEASE = new AtomicReference<>(new CountDownLatch(1));

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerFakeModelProperties(DynamicPropertyRegistry registry) throws IOException {
        fakeLlmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLlmServer.createContext("/v1/chat/completions", exchange -> {
            LLM_HITS.incrementAndGet();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                String mode = MODE.get();
                if ("empty".equals(mode)) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"   \"},\"finish_reason\":\"stop\"}]}");
                } else if ("tool_calc".equals(mode)) {
                    if (!reqBody.contains("\"role\":\"tool\"")) {
                        writeSse(out, "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_calc_1\",\"type\":\"function\",\"function\":{\"name\":\"calc\",\"arguments\":\"{\\\"expr\\\":\\\"2+3\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
                    } else {
                        String answer = reqBody.contains("\"5\"") ? "计算结果是5" : "没拿到结果";
                        writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"" + answer + "\"},\"finish_reason\":\"stop\"}]}");
                    }
                } else if ("slow".equals(mode)) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"慢\"}}]}");
                    SLOW_STARTED.get().countDown();
                    try {
                        SLOW_RELEASE.get().await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"完\"},\"finish_reason\":\"stop\"}]}");
                } else {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}");
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        });
        fakeLlmPool = Executors.newCachedThreadPool();
        fakeLlmServer.setExecutor(fakeLlmPool);
        fakeLlmServer.start();

        registry.add("lore.model.base-url", () -> "http://127.0.0.1:" + fakeLlmServer.getAddress().getPort() + "/v1");
        registry.add("lore.model.api-key", () -> "test-key");
        registry.add("lore.model.name", () -> "test-model");
    }

    @BeforeEach
    void resetState() {
        MODE.set("ok");
        LLM_HITS.set(0);
        SLOW_STARTED.set(new CountDownLatch(1));
        SLOW_RELEASE.set(new CountDownLatch(1));
    }

    @AfterAll
    static void stopFakeServer() {
        if (fakeLlmServer != null) {
            fakeLlmServer.stop(0);
        }
        if (fakeLlmPool != null) {
            fakeLlmPool.shutdownNow();
        }
    }

    @Test
    void shouldStreamDeltaAndDoneEventsOverSse() throws Exception {
        HttpResponse<String> response = postChat(
                "{\"message\":\"打个招呼\",\"clientRequestId\":\"req_chat_1\",\"conversationId\":\"c_stream\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).isPresent();
        assertThat(response.headers().firstValue("Content-Type").get()).startsWith("text/event-stream");
        assertThat(response.body())
                .contains("event:delta").contains("{\"text\":\"你\"}").contains("{\"text\":\"好\"}")
                .contains("event:done").contains("\"requestId\":\"req_chat_1\"");
    }

    @Test
    void shouldInvokeCalcToolAndFeedResultBackToModel() throws Exception {
        MODE.set("tool_calc");
        HttpResponse<String> response = postChat(
                "{\"message\":\"帮我算2+3\",\"clientRequestId\":\"req_calc_1\",\"conversationId\":\"c_calc\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("event:tool_started").contains("calc")
                .contains("event:tool_finished").contains("\"output\":\"5\"")
                .contains("计算结果是5")
                .contains("event:done");
        assertThat(LLM_HITS.get()).isEqualTo(2);
    }

    @Test
    void shouldRejectConcurrentSameConversationWith409AndAllowDifferentConversation() throws Exception {
        MODE.set("slow");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> postChat(
                    "{\"message\":\"第一条\",\"clientRequestId\":\"req_busy_1\",\"conversationId\":\"c_busy\"}"));
            assertThat(SLOW_STARTED.get().await(3, TimeUnit.SECONDS)).isTrue();

            // 同一个会话并发第二条消息，必须直接返回 409 SESSION_BUSY
            HttpResponse<String> duplicateConv = postChat(
                    "{\"message\":\"插队\",\"clientRequestId\":\"req_busy_2\",\"conversationId\":\"c_busy\"}");
            assertThat(duplicateConv.statusCode()).isEqualTo(409);
            assertThat(duplicateConv.body()).contains("SESSION_BUSY");

            // 相同 clientRequestId 在进行中重复提交，也返回 409 SESSION_BUSY
            HttpResponse<String> duplicateReq = postChat(
                    "{\"message\":\"重复\",\"clientRequestId\":\"req_busy_1\",\"conversationId\":\"c_other_conv\"}");
            assertThat(duplicateReq.statusCode()).isEqualTo(409);
            assertThat(duplicateReq.body()).contains("SESSION_BUSY");

            // 不同会话可以正常并发执行，互不阻塞
            MODE.set("ok");
            HttpResponse<String> otherConv = postChat(
                    "{\"message\":\"其他会话\",\"clientRequestId\":\"req_other_ok\",\"conversationId\":\"c_parallel\"}");
            assertThat(otherConv.statusCode()).isEqualTo(200);
            assertThat(otherConv.body()).contains("{\"text\":\"你\"}").contains("{\"text\":\"好\"}");

            SLOW_RELEASE.get().countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            SLOW_RELEASE.get().countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void shouldReplayCompletedClientRequestIdWithoutReExecutingLlm() throws Exception {
        String body = "{\"message\":\"打个招呼\",\"clientRequestId\":\"req_idemp_1\",\"conversationId\":\"c_idemp\"}";
        HttpResponse<String> first = postChat(body);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(LLM_HITS.get()).isEqualTo(1);

        // 相同 clientRequestId 再次提交：SSE 回放终态，模型调用次数保持 1 不增加
        HttpResponse<String> second = postChat(body);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Content-Type").get()).startsWith("text/event-stream");
        assertThat(second.body()).contains("event:delta").contains("你好").contains("event:done");
        assertThat(LLM_HITS.get()).isEqualTo(1);
    }

    @Test
    void shouldCancelWhenClientDisconnectsMidStream() throws Exception {
        MODE.set("slow");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"慢请求\",\"clientRequestId\":\"req_abort_1\",\"conversationId\":\"c_abort\"}",
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> streamResp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(SLOW_STARTED.get().await(3, TimeUnit.SECONDS)).isTrue();
        // 模拟浏览器停止 / Ctrl-C 关闭响应流
        streamResp.body().close();
        SLOW_RELEASE.get().countDown();

        // 取消后会话锁应迅速释放，同一会话能立即发下一条新消息
        MODE.set("ok");
        HttpResponse<String> next = null;
        for (int i = 0; i < 20; i++) {
            next = postChat("{\"message\":\"恢复\",\"clientRequestId\":\"req_abort_2\",\"conversationId\":\"c_abort\"}");
            if (next.statusCode() == 200) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(next).isNotNull();
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(next.body()).contains("{\"text\":\"你\"}").contains("{\"text\":\"好\"}");
    }

    @Test
    void shouldReturn400JsonWhenMessageIsBlank() throws Exception {
        HttpResponse<String> response = postChat("{\"message\":\"   \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_ARGUMENT");
    }

    @Test
    void shouldEmitErrorEventWhenModelReturnsEmptyText() throws Exception {
        MODE.set("empty");
        HttpResponse<String> response = postChat("{\"message\":\"hi\",\"conversationId\":\"c_empty\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:error").contains("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void shouldServeTemporaryDebugPageAtRoot() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("LoreAgent").contains("Phase 1b");
    }

    private static void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private HttpResponse<String> postChat(String jsonBody) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
