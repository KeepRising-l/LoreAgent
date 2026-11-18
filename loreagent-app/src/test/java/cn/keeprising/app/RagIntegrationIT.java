package cn.keeprising.app;

import cn.keeprising.core.Failure;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 真 PostgreSQL 16 + pgvector 集成测试（mvn verify -Pit 运行）。
 * 覆盖：vector 扩展与建表、分批 embedding、同名重传干净覆盖、前过滤作用域、中文长问句 OR 关键词检索、
 * Embedding 500 降级说明、[K1] 校验失败拦截、文档删除级联 chunks/向量/run_trace。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagIntegrationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer fakeUpstream;
    private static final AtomicReference<String> LLM_MODE = new AtomicReference<>("cite_k1");
    private static final AtomicInteger EMBED_STATUS = new AtomicInteger(200);
    private static final List<Integer> EMBED_BATCH_SIZES = Collections.synchronizedList(new ArrayList<>());

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KeywordSearch keywordSearch;

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) throws IOException {
        fakeUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeUpstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if ("bad_citation".equals(LLM_MODE.get())) {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"幻觉编号 [K99]\"},\"finish_reason\":\"stop\"}]}");
                } else {
                    writeSse(out, "{\"choices\":[{\"delta\":{\"content\":\"答案引用自资料 [K1]。\"},\"finish_reason\":\"stop\"}]}");
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        fakeUpstream.createContext("/v1/embeddings", exchange -> {
            if (EMBED_STATUS.get() >= 500) {
                exchange.sendResponseHeaders(EMBED_STATUS.get(), 0);
                exchange.close();
                return;
            }
            JsonNode req = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode input = req.get("input");
            EMBED_BATCH_SIZES.add(input.size());
            if (input.size() > 4) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }
            StringBuilder data = new StringBuilder("[");
            for (int i = 0; i < input.size(); i++) {
                if (i > 0) data.append(',');
                String text = input.get(i).asText();
                String vec = vectorFor(text);
                data.append("{\"index\":").append(i).append(",\"embedding\":").append(vec).append("}");
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
        registry.add("lore.model.api-key", () -> "it-key");
        registry.add("lore.model.name", () -> "it-model");
        registry.add("lore.embedding.base-url", () -> baseUrl);
        registry.add("lore.embedding.api-key", () -> "it-key");
        registry.add("lore.embedding.model", () -> "it-embed-3d");
        registry.add("lore.embedding.max-batch-size", () -> "4");
    }

    // 构造 3 维语义特征向量：使相似主题接近 [1,0,0]，干扰主题为 [0.7,0.7,0]，文档外问题正交 [0,0,1]
    private static String vectorFor(String text) {
        if (text.contains("黑洞") || text.contains("故宫")) {
            return "[0.0,0.0,1.0]";
        }
        if (text.contains("干扰标签")) {
            return "[1.0,0.0,0.0]";
        }
        if (text.contains("JavaScope")) {
            return "[0.8,0.6,0.0]";
        }
        return "[1.0,0.0,0.0]";
    }

    @AfterAll
    static void stopServer() {
        if (fakeUpstream != null) {
            fakeUpstream.stop(0);
        }
    }

    @BeforeEach
    void cleanDb() {
        jdbc.execute("truncate table run_trace, chunks, document, chat_message, conversation restart identity cascade");
        LLM_MODE.set("cite_k1");
        EMBED_STATUS.set(200);
        EMBED_BATCH_SIZES.clear();
    }

    @Test
    void shouldVerifyAllPhase2RagAcceptanceAgainstRealPgvector() throws Exception {
        // ⑧ 超单批上限(maxBatchSize=4)自动分批 + ① 文档入库与同名重传干净覆盖
        Path notePath = Path.of("..", "eval", "corpus", "07-chinese-tokenization-postgres.md");
        if (!Files.isRegularFile(notePath)) {
            notePath = Path.of("eval", "corpus", "07-chinese-tokenization-postgres.md");
        }
        String noteContent = Files.readString(notePath);
        HttpResponse<String> firstUpload = post("/api/documents",
                MAPPER.writeValueAsString(Map.of(
                        "title", "中文分词踩坑", "content", noteContent, "tags", List.of("postgres", "nlp"), "docDate", "2026-09-15")));
        assertThat(firstUpload.statusCode()).isEqualTo(201);
        JsonNode uploadJson = MAPPER.readTree(firstUpload.body());
        String docId = uploadJson.get("document").get("id").asText();
        int v1Count = uploadJson.get("chunkCount").asInt();
        assertThat(v1Count).isGreaterThan(4);
        assertThat(EMBED_BATCH_SIZES).allMatch(size -> size <= 4);
        assertThat(EMBED_BATCH_SIZES.size()).isGreaterThanOrEqualTo(2);

        // ① 同名重传：active_version 升为 2，旧版本片段被彻底清除
        HttpResponse<String> reupload = post("/api/documents",
                MAPPER.writeValueAsString(Map.of(
                        "title", "中文分词踩坑", "content", "# 替换\n最新版 plainto_tsquery 避坑指南使用 OR 连接二元组并按 ts_rank 排序。",
                        "tags", List.of("postgres"))));
        assertThat(MAPPER.readTree(reupload.body()).get("document").get("activeVersion").asInt()).isEqualTo(2);
        Integer remainingRows = jdbc.queryForObject(
                "select count(*) from chunks where document_id = ?", Integer.class, UUID.fromString(docId));
        assertThat(remainingRows).isEqualTo(1);

        // ⑦ 中文自然语言长问句：关键词检索返回非空（验证 OR 拼 to_tsquery 防 plainto_tsquery 坑）
        List<cn.keeprising.core.RrfFusion.ScoredChunk> kwHits = keywordSearch.search(
                AppConfig.LOCAL_USER,
                "请问在日常项目开发里为什么用 PostgreSQL 的 plainto_tsquery 查很长的中文自然语言问句总是返回零条结果呢？",
                null, List.of(), 5);
        assertThat(kwHits).isNotEmpty();
        assertThat(kwHits.get(0).documentId()).isEqualTo(UUID.fromString(docId));

        // ① 带 [K1] 问答 + 落库 citations 与 run_trace
        String convId = MAPPER.readTree(post("/api/conversations", "{}").body()).get("id").asText();
        HttpResponse<String> ragOk = post("/api/chat",
                "{\"message\":\"为什么 plainto_tsquery 会返回空\",\"clientRequestId\":\"pg_rag_1\",\"conversationId\":\"" + convId + "\"}");
        assertThat(ragOk.body()).contains("[K1]").contains("event:done");
        Integer traceCount = jdbc.queryForObject("select count(*) from run_trace where conversation_id = ?",
                Integer.class, UUID.fromString(convId));
        assertThat(traceCount).isGreaterThanOrEqualTo(1);

        // ② 文档外问题明确回复“依据不足”（正交向量 + 无关键词命中）
        HttpResponse<String> refuse = post("/api/chat",
                "{\"message\":\"请问故宫博物院黑洞展览时间\",\"clientRequestId\":\"pg_rag_refuse\",\"conversationId\":\"" + convId + "\"}");
        assertThat(refuse.body()).contains("依据不足").contains("event:done");

        // ③ 引用编号对不上时整次运行失败，且不持久化助手消息
        LLM_MODE.set("bad_citation");
        HttpResponse<String> badCite = post("/api/chat",
                "{\"message\":\"为什么 plainto_tsquery 会返回空\",\"clientRequestId\":\"pg_rag_bad\",\"conversationId\":\"" + convId + "\"}");
        assertThat(badCite.body()).contains("event:error").contains(Failure.ErrorCode.INVALID_CITATION.name());
        assertThat(badCite.body()).doesNotContain("event:done");

        // ④ Embedding 500 时降级关键词检索并在回答中明确说明
        LLM_MODE.set("cite_k1");
        EMBED_STATUS.set(500);
        HttpResponse<String> degradedChat = post("/api/chat",
                "{\"message\":\"为什么 plainto_tsquery 会返回空\",\"clientRequestId\":\"pg_rag_deg\",\"conversationId\":\"" + convId + "\"}");
        assertThat(degradedChat.body()).contains("降级").contains("关键词检索").contains("[K1]").contains("event:done");

        // ⑤ 作用域过滤在检索前生效（topK=1 下忽略更近的 other 标签文档，直接命中 java 标签文档）
        EMBED_STATUS.set(200);
        post("/api/documents", "{\"title\":\"干扰文档\",\"content\":\"干扰标签全匹配向量\",\"tags\":[\"other\"]}");
        String targetDocId = MAPPER.readTree(
                post("/api/documents", "{\"title\":\"目标文档\",\"content\":\"JavaScope 目标技术文档\",\"tags\":[\"java\"]}").body())
                .get("document").get("id").asText();
        HttpResponse<String> scopedDebug = post("/api/retrieval/debug",
                "{\"query\":\"干扰标签技术问题\",\"tags\":[\"java\"],\"topK\":1}");
        JsonNode debugChunks = MAPPER.readTree(scopedDebug.body()).get("chunks");
        assertThat(debugChunks.size()).isEqualTo(1);
        assertThat(debugChunks.get(0).get("documentId").asText()).isEqualTo(targetDocId);

        // ⑥ 删除文档级联删除 chunks、向量与 run_trace，不留脏数据
        assertThat(delete("/api/documents/" + docId).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from chunks where document_id = ?",
                Integer.class, UUID.fromString(docId))).isZero();
        String marker = "[{\"documentId\":\"" + docId + "\"}]";
        assertThat(jdbc.queryForObject(
                "select count(*) from run_trace where retrieved_chunks @> cast(? as jsonb)",
                Integer.class, marker)).isZero();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(),
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
