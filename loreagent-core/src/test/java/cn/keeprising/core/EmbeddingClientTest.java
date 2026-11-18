package cn.keeprising.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSplitIntoMultipleBatchesAndReassembleInOrderWhenExceedingMaxBatchSize() throws Exception {
        List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode req = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode input = req.get("input");
            batchSizes.add(input.size());
            if (input.size() > 2) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }
            // 故意倒序回传 index，校验客户端按 index 对齐而非按数组下标对齐
            StringBuilder data = new StringBuilder("[");
            for (int i = input.size() - 1; i >= 0; i--) {
                if (i < input.size() - 1) {
                    data.append(',');
                }
                double val = input.get(i).asText().length();
                data.append("{\"index\":").append(i).append(",\"embedding\":[").append(val).append(",1.0]}");
            }
            data.append(']');
            byte[] resp = ("{\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        EmbeddingClient client = new EmbeddingClient(baseUrl, "k", "m", 2,
                Duration.ofSeconds(5), Duration.ofSeconds(2), 0);

        List<float[]> vectors = client.embedBatch(List.of("a", "bb", "ccc", "dddd", "eeeee"));
        assertThat(batchSizes).containsExactly(2, 2, 1);
        assertThat(vectors).hasSize(5);
        assertThat(vectors.get(0)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(2)[0]).isEqualTo(3.0f);
        assertThat(vectors.get(4)[0]).isEqualTo(5.0f);
    }

    @Test
    void shouldThrowEmbeddingUnavailableOnHttp500() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        EmbeddingClient client = new EmbeddingClient(baseUrl, "k", "m", 16,
                Duration.ofSeconds(5), Duration.ofSeconds(2), 1);

        assertThatThrownBy(() -> client.embedQuery("hello"))
                .isInstanceOf(Failure.class)
                .satisfies(e -> assertThat(((Failure) e).code()).isEqualTo(Failure.ErrorCode.EMBEDDING_UNAVAILABLE));
    }
}
