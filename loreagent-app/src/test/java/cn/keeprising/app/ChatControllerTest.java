package cn.keeprising.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles("test")
@Import(InMemoryTestRepos.class)
class ChatControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        InMemoryTestRepos.FAIL_NEXT_MESSAGE_APPEND.set(false);
        InMemoryTestRepos.FAIL_NEXT_ASSISTANT_APPEND.set(false);
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
    void shouldStreamDeltaAndDoneEventsAndPersistMessages() throws Exception {
        String convId = createConversation();
        HttpResponse<String> response = postChat(
                "{\"message\":\"打个招呼\",\"clientRequestId\":\"req_chat_1\",\"conversationId\":\"" + convId + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.body())
                .contains("event:delta").contains("{\"text\":\"你\"}").contains("{\"text\":\"好\"}")
                .contains("event:done").contains("\"requestId\":\"req_chat_1\"");

        HttpResponse<String> history = get("/api/conversations/" + convId + "/messages");
        assertThat(history.statusCode()).isEqualTo(200);
        JsonNode arr = MAPPER.readTree(history.body());
        assertThat(arr.size()).isEqualTo(2);
        assertThat(arr.get(0).get("role").asText()).isEqualTo("user");
        assertThat(arr.get(1).get("content").asText()).isEqualTo("你好");
    }

    @Test
    void shouldInvokeCalcToolPersistStepsAndFeedResultBackToModel() throws Exception {
        String convId = createConversation();
        MODE.set("tool_calc");
        HttpResponse<String> response = postChat(
                "{\"message\":\"帮我算2+3\",\"clientRequestId\":\"req_calc_1\",\"conversationId\":\"" + convId + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("event:tool_started").contains("calc")
                .contains("event:tool_finished").contains("\"output\":\"5\"")
                .contains("计算结果是5").contains("event:done");
        assertThat(LLM_HITS.get()).isEqualTo(2);

        JsonNode arr = MAPPER.readTree(get("/api/conversations/" + convId + "/messages").body());
        assertThat(arr.size()).isEqualTo(4); // user, assistant(tool_calls), tool, assistant
        assertThat(arr.get(1).get("requestId").isNull()).isTrue();
        assertThat(arr.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(arr.get(3).get("requestId").asText()).isEqualTo("req_calc_1");
    }

    @Test
    void shouldRejectConcurrentSameConversationWith409AndAllowDifferentConversation() throws Exception {
        String convBusy = createConversation();
        String convParallel = createConversation();
        MODE.set("slow");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> postChat(
                    "{\"message\":\"第一条\",\"clientRequestId\":\"req_busy_1\",\"conversationId\":\"" + convBusy + "\"}"));
            assertThat(SLOW_STARTED.get().await(3, TimeUnit.SECONDS)).isTrue();

            HttpResponse<String> duplicateConv = postChat(
                    "{\"message\":\"插队\",\"clientRequestId\":\"req_busy_2\",\"conversationId\":\"" + convBusy + "\"}");
            assertThat(duplicateConv.statusCode()).isEqualTo(409);
            assertThat(duplicateConv.body()).contains("SESSION_BUSY");

            HttpResponse<String> duplicateReq = postChat(
                    "{\"message\":\"重复\",\"clientRequestId\":\"req_busy_1\",\"conversationId\":\"" + convParallel + "\"}");
            assertThat(duplicateReq.statusCode()).isEqualTo(409);
            assertThat(duplicateReq.body()).contains("SESSION_BUSY");

            MODE.set("ok");
            HttpResponse<String> otherConv = postChat(
                    "{\"message\":\"其他会话\",\"clientRequestId\":\"req_other_ok\",\"conversationId\":\"" + convParallel + "\"}");
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
        String convId = createConversation();
        String body = "{\"message\":\"打个招呼\",\"clientRequestId\":\"req_idemp_1\",\"conversationId\":\"" + convId + "\"}";
        assertThat(postChat(body).statusCode()).isEqualTo(200);
        assertThat(LLM_HITS.get()).isEqualTo(1);

        HttpResponse<String> second = postChat(body);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(second.body()).contains("event:delta").contains("你好").contains("event:done");
        assertThat(LLM_HITS.get()).isEqualTo(1);
    }

    @Test
    void shouldCancelWhenClientDisconnectsMidStreamAndAllowNewMessage() throws Exception {
        String convId = createConversation();
        MODE.set("slow");
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"message\":\"慢请求\",\"clientRequestId\":\"req_abort_1\",\"conversationId\":\"" + convId + "\"}",
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> streamResp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(SLOW_STARTED.get().await(3, TimeUnit.SECONDS)).isTrue();
        streamResp.body().close();
        SLOW_RELEASE.get().countDown();

        MODE.set("ok");
        HttpResponse<String> next = null;
        for (int i = 0; i < 20; i++) {
            next = postChat("{\"message\":\"恢复\",\"clientRequestId\":\"req_abort_2\",\"conversationId\":\"" + convId + "\"}");
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
    void shouldReturnStorageErrorInsteadOfSilentDoneWhenPersistenceFails() throws Exception {
        String convId = createConversation();
        // 1. user 消息落库失败时直接返回 HTTP 500 STORAGE_ERROR
        InMemoryTestRepos.FAIL_NEXT_MESSAGE_APPEND.set(true);
        HttpResponse<String> failOnUser = postChat(
                "{\"message\":\"失败1\",\"clientRequestId\":\"req_fail_u\",\"conversationId\":\"" + convId + "\"}");
        assertThat(failOnUser.statusCode()).isEqualTo(500);
        assertThat(failOnUser.body()).contains("STORAGE_ERROR");

        // 2. 流式输出完成后助手终态落库失败：不能只吐流发 event:done，必须发 event:error(STORAGE_ERROR)
        InMemoryTestRepos.FAIL_NEXT_ASSISTANT_APPEND.set(true);
        HttpResponse<String> failOnAssistant = postChat(
                "{\"message\":\"失败2\",\"clientRequestId\":\"req_fail_a\",\"conversationId\":\"" + convId + "\"}");
        assertThat(failOnAssistant.statusCode()).isEqualTo(200);
        assertThat(failOnAssistant.body()).contains("event:delta").contains("event:error").contains("STORAGE_ERROR");
        assertThat(failOnAssistant.body()).doesNotContain("event:done");
    }

    @Test
    void shouldReturn400JsonWhenMessageIsBlank() throws Exception {
        HttpResponse<String> response = postChat("{\"message\":\"   \"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_ARGUMENT");
    }

    @Test
    void shouldEmitErrorEventWhenModelReturnsEmptyTextAndSupportRetryWithoutDuplicateUser() throws Exception {
        String convId = createConversation();
        MODE.set("empty");
        HttpResponse<String> failed = postChat(
                "{\"message\":\"hi\",\"clientRequestId\":\"req_empty_1\",\"conversationId\":\"" + convId + "\"}");

        assertThat(failed.statusCode()).isEqualTo(200);
        assertThat(failed.body()).contains("event:error").contains("MODEL_EMPTY_RESPONSE");

        JsonNode afterFail = MAPPER.readTree(get("/api/conversations/" + convId + "/messages").body());
        assertThat(afterFail.size()).isEqualTo(1);
        long userMsgId = afterFail.get(0).get("id").asLong();

        MODE.set("ok");
        HttpResponse<String> retried = postChat("{\"message\":\"\",\"clientRequestId\":\"req_retry_ok\",\"conversationId\":\""
                + convId + "\",\"retryMessageId\":" + userMsgId + "}");
        assertThat(retried.statusCode()).isEqualTo(200);
        assertThat(retried.body()).contains("event:done");

        JsonNode afterRetry = MAPPER.readTree(get("/api/conversations/" + convId + "/messages").body());
        assertThat(afterRetry.size()).isEqualTo(2);
    }

    @Test
    void shouldServeStageAdaptedPageAtRoot() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("LoreAgent").contains("Phase 2");
    }

    private String createConversation() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/conversations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(201);
        return MAPPER.readTree(resp.body()).get("id").asText();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
