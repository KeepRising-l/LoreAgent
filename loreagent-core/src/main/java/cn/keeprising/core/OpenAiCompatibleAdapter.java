package cn.keeprising.core;

import cn.keeprising.spi.LlmAdapter;
import cn.keeprising.spi.Message;
import cn.keeprising.spi.StreamChunk;
import cn.keeprising.spi.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 真正跑去调大模型的 HTTP 客户端（按 OpenAI 兼容协议请求 /chat/completions）。
 * 干 4 件事：把消息转成 JSON 发出去、流开始前遇 500/超时重试、交给 SseParser 切包、随时看取消开关刹车。
 */
public class OpenAiCompatibleAdapter implements LlmAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI chatEndpoint;
    private final String apiKey;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final HttpClient httpClient;

    public OpenAiCompatibleAdapter(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ofSeconds(60), Duration.ofSeconds(10), 1);
    }

    public OpenAiCompatibleAdapter(
            String baseUrl, String apiKey, Duration requestTimeout, Duration connectTimeout, int maxRetries) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatEndpoint = URI.create(normalized + "/chat/completions");
        this.apiKey = Objects.requireNonNullElse(apiKey, "");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空");
        this.maxRetries = Math.max(0, maxRetries);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout 不能为空")).build();
    }

    // 入口方法：负责在“还没开始吐字之前”对可重试错误（500/超时）自动重试
    @Override
    public void streamChat(
            String model, List<Message> messages, List<Tool> tools,
            Consumer<StreamChunk> onChunk, BooleanSupplier cancelled) {
        Objects.requireNonNull(onChunk, "onChunk 不能为空");
        BooleanSupplier cancelProbe = cancelled == null ? () -> false : cancelled;
        String bodyJson = buildRequestBody(model, messages, tools);
        int attempt = 0;
        while (true) {
            checkCancelled(cancelProbe);
            try {
                executeAttempt(bodyJson, onChunk, cancelProbe);
                return;
            } catch (Failure failure) {
                if (!failure.isRetryable() || attempt >= maxRetries || cancelProbe.getAsBoolean()) {
                    throw failure;
                }
                attempt++;
            }
        }
    }

    // 发起单次 HTTP POST 请求：拿到响应流，状态码 >=400 直接报错，200 就交给 readSseStream 逐片读
    private void executeAttempt(String bodyJson, Consumer<StreamChunk> onChunk, BooleanSupplier cancelled) {
        HttpRequest.Builder req = HttpRequest.newBuilder(chatEndpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new Failure(Failure.ErrorCode.TIMEOUT, "模型请求超时: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.ErrorCode.CANCELLED, "模型请求被线程中断", e);
        } catch (IOException e) {
            throw new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE, "连接模型接口失败: " + e.getMessage(), e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() >= 400) {
                String snippet = new String(body.readNBytes(512), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
                throw mapHttpFailure(response.statusCode(), snippet);
            }
            readSseStream(body, onChunk, cancelled);
        } catch (IOException e) {
            throw new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE, "读取模型流式响应失败: " + e.getMessage(), e);
        }
    }

    // 边读 HTTP 响应流边喂给 SseParser，再把切出来的 JSON 解析成 StreamChunk 回调给 AgentLoop
    private void readSseStream(InputStream body, Consumer<StreamChunk> onChunk, BooleanSupplier cancelled)
            throws IOException {
        SseParser parser = new SseParser();
        boolean[] flags = {false, false}; // [0]=是否收到结束原因, [1]=是否已经往外吐过字
        Consumer<String> emitter = payload -> {
            // 同一批 read 缓冲里可能包含多个 SSE 事件，回调前必须再探一次取消状态
            checkCancelled(cancelled);
            StreamChunk chunk = parseChunkJson(payload);
            if (chunk.finishReason() != null && !chunk.finishReason().isBlank()) {
                flags[0] = true;
            }
            flags[1] = true;
            onChunk.accept(chunk);
        };
        // 用 InputStreamReader 按字符解码，避免多字节中文在 TCP 分片边界被拆成乱码
        InputStreamReader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
        char[] buf = new char[1024];
        try {
            int read;
            while ((read = reader.read(buf)) != -1) {
                checkCancelled(cancelled);
                parser.feed(new String(buf, 0, read), emitter);
                if (parser.isDone()) {
                    break;
                }
            }
            checkCancelled(cancelled);
            parser.finish(emitter);
        } catch (Failure failure) {
            // 一旦已向下游吐出过字，就禁止再整轮重试，防止前端看到重复半句
            if (flags[1] && failure.isRetryable()) {
                throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, failure.getMessage(), failure);
            }
            throw failure;
        }
        if (!parser.isDone() && !flags[0]) {
            throw new Failure(flags[1] ? Failure.ErrorCode.INTERNAL_ERROR : Failure.ErrorCode.MODEL_UNAVAILABLE,
                    "模型流式响应未收到结束标记即断开");
        }
    }

    // 把一条 OpenAI SSE JSON 拆成我们的 StreamChunk（文字增量 + 工具指令增量 + 结束原因 + 用量）
    private StreamChunk parseChunkJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.hasNonNull("error")) {
                throw new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE,
                        root.path("error").path("message").asText("上游返回流式错误"));
            }
            JsonNode u = root.get("usage");
            StreamChunk.Usage usage = (u == null || u.isNull()) ? null : new StreamChunk.Usage(
                    u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), u.path("total_tokens").asInt(0));
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return new StreamChunk("", List.of(), null, usage);
            }
            JsonNode first = choices.get(0);
            JsonNode delta = first.path("delta");
            return new StreamChunk(
                    Objects.requireNonNullElse(textOrNull(delta, "content"), ""),
                    parseToolDeltas(delta.get("tool_calls")),
                    textOrNull(first, "finish_reason"),
                    usage);
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE, "模型响应 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private List<StreamChunk.ToolCallDelta> parseToolDeltas(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        List<StreamChunk.ToolCallDelta> result = new ArrayList<>(toolCallsNode.size());
        for (JsonNode item : toolCallsNode) {
            JsonNode fn = item.path("function");
            result.add(new StreamChunk.ToolCallDelta(
                    item.path("index").asInt(0),
                    textOrNull(item, "id"),
                    textOrNull(fn, "name"),
                    Objects.requireNonNullElse(textOrNull(fn, "arguments"), "")));
        }
        return result;
    }

    private String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    // 把我们的 Message 和 Tool 列表打包成 OpenAI 接口要求的 JSON 请求体
    private String buildRequestBody(String model, List<Message> messages, List<Tool> tools) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            body.put("messages", messages.stream().map(this::toMessageMap).toList());
            if (tools != null && !tools.isEmpty()) {
                List<Map<String, Object>> toolDefs = new ArrayList<>(tools.size());
                for (Tool tool : tools) {
                    toolDefs.add(Map.of("type", "function", "function", Map.of(
                            "name", tool.name(), "description", tool.description(),
                            "parameters", MAPPER.readTree(tool.parametersSchemaJson()))));
                }
                body.put("tools", toolDefs);
            }
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "构造模型请求 JSON 失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toMessageMap(Message msg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", msg.role());
        map.put("content", (!msg.toolCalls().isEmpty() && msg.content().isEmpty()) ? null : msg.content());
        if (!msg.toolCalls().isEmpty()) {
            map.put("tool_calls", msg.toolCalls().stream().map(tc -> Map.of(
                    "id", tc.id(), "type", "function",
                    "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson()))).toList());
        }
        if (msg.toolCallId() != null) {
            map.put("tool_call_id", msg.toolCallId());
        }
        return map;
    }

    // 把上游 HTTP 状态码转成我们的 Failure 错误码（4xx 不重试，5xx 可重试）
    private Failure mapHttpFailure(int status, String bodySnippet) {
        Failure.ErrorCode code = switch (status) {
            case 400, 401, 403, 422 -> Failure.ErrorCode.INVALID_ARGUMENT;
            case 404 -> Failure.ErrorCode.MODEL_NOT_FOUND;
            default -> Failure.ErrorCode.MODEL_UNAVAILABLE;
        };
        return new Failure(code, "模型请求异常 status=" + status + " body=" + bodySnippet);
    }

    // 检查是否被前端取消或线程中断，是的话立刻抛 CANCELLED 刹车
    private void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new Failure(Failure.ErrorCode.CANCELLED, "请求已取消");
        }
    }
}
