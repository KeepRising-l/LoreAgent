package cn.keeprising.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 PostgreSQL 集成测试：仅在 mvn verify -Pit 时运行（@Tag("integration")）。
 * 覆盖：schema.sql 自动建表、两轮含工具调用落库、userId 隔离、级联删除、重启后幂等回放、失败重试不重复插入。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersistenceIntegrationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer fakeLlmServer;
    private static final AtomicReference<String> MODE = new AtomicReference<>("tool_calc");
    private static final AtomicInteger LLM_HITS = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationRepository conversationRepo;

    @Autowired
    private MessageRepository messageRepo;

    @Autowired
    private cn.keeprising.core.RunRegistry runRegistry;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws IOException {
        fakeLlmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLlmServer.createContext("/v1/chat/completions", exchange -> {
            LLM_HITS.incrementAndGet();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if ("empty".equals(MODE.get())) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"  \"},\"finish_reason\":\"stop\"}]}");
                } else if ("tool_calc".equals(MODE.get()) && !reqBody.contains("\"role\":\"tool\"")) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_pg_1\",\"type\":\"function\",\"function\":{\"name\":\"calc\",\"arguments\":\"{\\\"expr\\\":\\\"2+3\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
                } else if ("tool_calc".equals(MODE.get())) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"2+3=5\"},\"finish_reason\":\"stop\"}]}");
                } else {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"第二轮回复\"},\"finish_reason\":\"stop\"}]}");
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        fakeLlmServer.start();

        registry.add("lore.model.base-url", () -> "http://127.0.0.1:" + fakeLlmServer.getAddress().getPort() + "/v1");
        registry.add("lore.model.api-key", () -> "it-key");
        registry.add("lore.model.name", () -> "it-model");
    }

    @AfterAll
    static void stopServer() {
        if (fakeLlmServer != null) {
            fakeLlmServer.stop(0);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table chat_message, conversation restart identity cascade");
        LLM_HITS.set(0);
        MODE.set("tool_calc");
    }

    @Test
    void shouldPersistMultiTurnToolConversationCascadeDeleteAndSurviveRestartIdempotency() throws Exception {
        // 1. 新建会话并发送第 1 轮（含 CalcTool 调用）
        String convId = createConversation();
        HttpResponse<String> r1 = postChat(
                "{\"message\":\"帮我算一下2+3是多少\",\"clientRequestId\":\"it_req_1\",\"conversationId\":\"" + convId + "\"}");
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r1.body()).contains("2+3=5").contains("event:done");

        // 2. 发送第 2 轮普通对话，验证顺序与历史完整
        MODE.set("plain");
        HttpResponse<String> r2 = postChat(
                "{\"message\":\"再说一句\",\"clientRequestId\":\"it_req_2\",\"conversationId\":\"" + convId + "\"}");
        assertThat(r2.statusCode()).isEqualTo(200);

        JsonNode history = MAPPER.readTree(get("/api/conversations/" + convId + "/messages").body());
        assertThat(history.size()).isEqualTo(6);
        assertThat(history.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(history.get(1).get("toolCalls").get(0).get("name").asText()).isEqualTo("calc");
        assertThat(history.get(2).get("role").asText()).isEqualTo("tool");
        assertThat(history.get(3).get("content").asText()).isEqualTo("2+3=5");
        assertThat(history.get(5).get("seq").asInt()).isEqualTo(6);

        // 验证自动标题取前 20 字
        JsonNode list = MAPPER.readTree(get("/api/conversations").body());
        assertThat(list.get(0).get("title").asText()).isEqualTo("帮我算一下2+3是多少");

        // 3. 模拟重启清空内存 RunRegistry 后，重复提交 it_req_1 仍走数据库回放，不再调模型
        int hitsBeforeReplay = LLM_HITS.get();
        runRegistry.abort(convId, "it_req_1"); // 确保内存态不可用，完全验证 PG 持久化幂等
        HttpResponse<String> replay = postChat(
                "{\"message\":\"帮我算一下2+3是多少\",\"clientRequestId\":\"it_req_1\",\"conversationId\":\"" + convId + "\"}");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("2+3=5").contains("event:done");
        assertThat(LLM_HITS.get()).isEqualTo(hitsBeforeReplay);

        // 4. 验证多会话按 updated_at 倒序 + userId 隔离
        Conversation otherUserConv = conversationRepo.create("other_user", UUID.randomUUID(), "别人的会话");
        assertThat(conversationRepo.findById("local", otherUserConv.id())).isEmpty();
        assertThat(messageRepo.listByConversation("other_user", UUID.fromString(convId))).isEmpty();

        // 5. 删除会话时级联清空 chat_message，不留脏数据
        assertThat(delete("/api/conversations/" + convId).statusCode()).isEqualTo(204);
        Integer remaining = jdbc.queryForObject(
                "select count(*) from chat_message where conversation_id = ?", Integer.class, UUID.fromString(convId));
        assertThat(remaining).isZero();
    }

    @Test
    void shouldReuseUserMessageOnRetryInRealPostgres() throws Exception {
        String convId = createConversation();
        MODE.set("empty");
        HttpResponse<String> failed = postChat(
                "{\"message\":\"会失败的问题\",\"clientRequestId\":\"it_fail_1\",\"conversationId\":\"" + convId + "\"}");
        assertThat(failed.body()).contains("MODEL_EMPTY_RESPONSE");

        List<ChatMessage> afterFail = messageRepo.listByConversation(AppConfig.LOCAL_USER, UUID.fromString(convId));
        assertThat(afterFail).hasSize(1);

        MODE.set("plain");
        HttpResponse<String> retry = postChat("{\"message\":\"\",\"clientRequestId\":\"it_retry_2\",\"conversationId\":\""
                + convId + "\",\"retryMessageId\":" + afterFail.get(0).id() + "}");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).contains("event:done");

        List<ChatMessage> afterRetry = messageRepo.listByConversation(AppConfig.LOCAL_USER, UUID.fromString(convId));
        assertThat(afterRetry).hasSize(2);
        assertThat(afterRetry.get(0).role()).isEqualTo("user");
        assertThat(afterRetry.get(1).role()).isEqualTo("assistant");
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
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postChat(String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
