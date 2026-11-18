package cn.keeprising.app;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerTest {

    private static HttpServer fakeLlmServer;
    private static final AtomicReference<String> NEXT_RESPONSE_MODE = new AtomicReference<>("ok");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerFakeModelProperties(DynamicPropertyRegistry registry) throws IOException {
        fakeLlmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLlmServer.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                if ("empty".equals(NEXT_RESPONSE_MODE.get())) {
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"   \"},\"finish_reason\":\"stop\"}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        fakeLlmServer.start();

        registry.add("lore.model.base-url", () -> "http://127.0.0.1:" + fakeLlmServer.getAddress().getPort() + "/v1");
        registry.add("lore.model.api-key", () -> "test-key");
        registry.add("lore.model.name", () -> "test-model");
    }

    @AfterAll
    static void stopFakeServer() {
        if (fakeLlmServer != null) {
            fakeLlmServer.stop(0);
        }
    }

    @Test
    void shouldStreamDeltaAndDoneEventsOverSse() throws Exception {
        NEXT_RESPONSE_MODE.set("ok");
        HttpResponse<String> response = postChat("{\"message\":\"打个招呼\",\"clientRequestId\":\"req_chat_1\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).isPresent();
        assertThat(response.headers().firstValue("Content-Type").get()).startsWith("text/event-stream");
        assertThat(response.body())
                .contains("event:delta")
                .contains("{\"text\":\"你\"}")
                .contains("{\"text\":\"好\"}")
                .contains("event:done")
                .contains("\"requestId\":\"req_chat_1\"");
    }

    @Test
    void shouldReturn400JsonWhenMessageIsBlank() throws Exception {
        HttpResponse<String> response = postChat("{\"message\":\"   \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("INVALID_ARGUMENT");
    }

    @Test
    void shouldEmitErrorEventWhenModelReturnsEmptyText() throws Exception {
        NEXT_RESPONSE_MODE.set("empty");
        HttpResponse<String> response = postChat("{\"message\":\"hi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:error").contains("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void shouldServeTemporaryDebugPageAtRoot() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("LoreAgent 临时调试页");
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
