package cn.keeprising.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(InMemoryTestRepos.class)
class RagControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer fakeUpstream;
    private static final AtomicReference<String> LLM_MODE = new AtomicReference<>("cite_k1");
    private static final AtomicInteger EMBED_STATUS = new AtomicInteger(200);
    private static final AtomicInteger LLM_HITS = new AtomicInteger(0);

    @LocalServerPort
    private int port;

    @Autowired
    private InMemoryTestRepos.FakeStore store;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws IOException {
        fakeUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeUpstream.createContext("/v1/chat/completions", exchange -> {
            LLM_HITS.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if ("bad_citation".equals(LLM_MODE.get())) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"胡编引用 [K99]\"},\"finish_reason\":\"stop\"}]}");
                } else {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"HNSW 默认 ef_search 为 40 [K1]。\"},\"finish_reason\":\"stop\"}]}");
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        fakeUpstream.createContext("/v1/embeddings", exchange -> {
            int code = EMBED_STATUS.get();
            if (code >= 500) {
                exchange.sendResponseHeaders(code, 0);
                exchange.close();
                return;
            }
            JsonNode req = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            int n = req.get("input").size();
            StringBuilder data = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) data.append(',');
                data.append("{\"index\":").append(i).append(",\"embedding\":[1.0,0.0,0.0]}");
            }
            data.append(']');
            byte[] resp = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        fakeUpstream.start();

        String baseUrl = "http://127.0.0.1:" + fakeUpstream.getAddress().getPort() + "/v1";
        registry.add("lore.model.base-url", () -> baseUrl);
        registry.add("lore.model.api-key", () -> "k");
        registry.add("lore.model.name", () -> "m");
        registry.add("lore.embedding.base-url", () -> baseUrl);
        registry.add("lore.embedding.api-key", () -> "k");
        registry.add("lore.embedding.model", () -> "emb-m");
    }

    @AfterAll
    static void stopServer() {
        if (fakeUpstream != null) {
            fakeUpstream.stop(0);
        }
    }

    @BeforeEach
    void clean() {
        store.documents.clear();
        store.chunks.clear();
        store.conversations.clear();
        store.messages.clear();
        LLM_MODE.set("cite_k1");
        EMBED_STATUS.set(200);
        LLM_HITS.set(0);
    }

    @Test
    void shouldSupportIngestCitationRefusalInvalidCitationFailAndEmbeddingDegradation() throws Exception {
        // 1. 上传 md，验证入库 + 同名覆盖 version 1 -> 2
        HttpResponse<String> doc1 = post("/api/documents", """
                {"title":"PG 调优","content":"# HNSW\\nef_search 默认值为 40，用于控制搜索候选队列大小。","tags":["pg"]}
                """);
        assertThat(doc1.statusCode()).isEqualTo(201);
        String docId = MAPPER.readTree(doc1.body()).get("document").get("id").asText();

        HttpResponse<String> docOverwrite = post("/api/documents", """
                {"title":"PG 调优","content":"# HNSW\\nef_search 默认值为 40，新版本说明覆盖旧切片。","tags":["pg"]}
                """);
        assertThat(MAPPER.readTree(docOverwrite.body()).get("document").get("activeVersion").asInt()).isEqualTo(2);

        // 2. 问文档内问题：答案带 [K1] 与结构化 citations
        String convId = MAPPER.readTree(post("/api/conversations", "{}").body()).get("id").asText();
        HttpResponse<String> okChat = post("/api/chat",
                "{\"message\":\"请问 ef_search 默认值是多少\",\"clientRequestId\":\"rag_1\",\"conversationId\":\"" + convId + "\"}");
        assertThat(okChat.statusCode()).isEqualTo(200);
        assertThat(okChat.body()).contains("[K1]").contains("event:done").contains("\"refId\":\"K1\"");

        // 3. 问文档外问题：直接拒答“依据不足”，不调用大模型
        int hitsBeforeRefuse = LLM_HITS.get();
        HttpResponse<String> refuseChat = post("/api/chat",
                "{\"message\":\"昨天太阳系黑洞新闻怎么样\",\"clientRequestId\":\"rag_refuse\",\"conversationId\":\"" + convId + "\"}");
        assertThat(refuseChat.statusCode()).isEqualTo(200);
        assertThat(refuseChat.body()).contains("依据不足").contains("event:done");
        assertThat(LLM_HITS.get()).isEqualTo(hitsBeforeRefuse);

        // 4. 模型返回越界引用 [K99]：整次运行失败，发 event:error(INVALID_CITATION)，不发 event:done
        LLM_MODE.set("bad_citation");
        HttpResponse<String> badCite = post("/api/chat",
                "{\"message\":\"请问 ef_search 默认值是多少\",\"clientRequestId\":\"rag_bad_cite\",\"conversationId\":\"" + convId + "\"}");
        assertThat(badCite.body()).contains("event:error").contains("INVALID_CITATION");
        assertThat(badCite.body()).doesNotContain("event:done");

        // 5. Embedding 500：降级为关键词检索并显式说明
        LLM_MODE.set("cite_k1");
        EMBED_STATUS.set(500);
        HttpResponse<String> debug = post("/api/retrieval/debug", "{\"query\":\"请问 ef_search 默认值是多少\",\"topK\":3}");
        assertThat(debug.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(debug.body()).get("degraded").asBoolean()).isTrue();

        HttpResponse<String> degradedChat = post("/api/chat",
                "{\"message\":\"请问 ef_search 默认值是多少\",\"clientRequestId\":\"rag_deg\",\"conversationId\":\"" + convId + "\"}");
        assertThat(degradedChat.body()).contains("降级").contains("关键词检索").contains("[K1]").contains("event:done");

        // 6. 删除文档级联清理
        HttpResponse<String> del = delete("/api/documents/" + docId);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(MAPPER.readTree(get("/api/documents").body()).size()).isZero();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
