package cn.keeprising.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;

/**
 * OpenAI 兼容 /embeddings 客户端（纯 Java，不依赖 Spring）。
 * 支持按 maxBatchSize 自动分批提交并按 index 拼回原序；遇到 5xx/超限/网络异常抛受控 Failure，供检索层自动降级关键词。
 */
public class EmbeddingClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_BATCH_SIZE = 16;

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxBatchSize;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final HttpClient httpClient;

    public EmbeddingClient(String baseUrl, String apiKey, String model, int maxBatchSize) {
        this(baseUrl, apiKey, model, maxBatchSize, Duration.ofSeconds(20), Duration.ofSeconds(5), 1);
    }

    public EmbeddingClient(
            String baseUrl, String apiKey, String model, int maxBatchSize,
            Duration requestTimeout, Duration connectTimeout, int maxRetries) {
        String normalized = (baseUrl == null || baseUrl.isBlank())
                ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.endpoint = normalized.isEmpty() ? null : URI.create(normalized + "/embeddings");
        this.apiKey = Objects.requireNonNullElse(apiKey, "");
        this.model = Objects.requireNonNullElse(model, "");
        this.maxBatchSize = maxBatchSize > 0 ? maxBatchSize : DEFAULT_MAX_BATCH_SIZE;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不能为空");
        this.maxRetries = Math.max(0, maxRetries);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout 不能为空")).build();
    }

    public String modelName() {
        return model;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    // 未配 API Key 且不是本地服务时视为未启用，避免纯关键词测试或本地开发启动阻断
    public boolean isConfigured() {
        if (endpoint == null || model.isBlank()) {
            return false;
        }
        String host = endpoint.getHost();
        boolean isLocal = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        return !apiKey.isBlank() || isLocal;
    }

    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "向量化查询文本不能为空");
        }
        return embedBatch(List.of(text)).get(0);
    }

    // 按 maxBatchSize 自动分批提交，确保超单批上限时不触发上游 400/413
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!isConfigured()) {
            throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 服务未配置（缺少 API Key 或 Base URL）");
        }
        List<float[]> all = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, texts.size());
            all.addAll(requestBatchWithRetry(texts.subList(start, end)));
        }
        return List.copyOf(all);
    }

    private List<float[]> requestBatchWithRetry(List<String> batch) {
        String body = buildRequestBody(batch);
        int attempt = 0;
        while (true) {
            try {
                return executeSingleBatch(body, batch.size());
            } catch (Failure failure) {
                if (!failure.isRetryable() || attempt >= maxRetries) {
                    throw failure;
                }
                attempt++;
            }
        }
    }

    private List<float[]> executeSingleBatch(String bodyJson, int expectedSize) {
        HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        if (!apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status >= 500) {
                throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 上游服务错误 HTTP " + status);
            }
            if (status >= 400) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "Embedding 请求被拒绝 HTTP " + status);
            }
            return parseEmbeddings(resp.body(), expectedSize);
        } catch (HttpTimeoutException e) {
            throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 请求超时: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 网络异常: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.ErrorCode.CANCELLED, "Embedding 请求被中断", e);
        }
    }

    private String buildRequestBody(List<String> batch) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", batch);
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, "序列化 Embedding 请求失败: " + e.getMessage(), e);
        }
    }

    private List<float[]> parseEmbeddings(String responseJson, int expectedSize) {
        try {
            JsonNode root = MAPPER.readTree(responseJson);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != expectedSize) {
                throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 响应 data 数量与请求批大小不匹配");
            }
            float[][] ordered = new float[expectedSize][];
            for (int pos = 0; pos < data.size(); pos++) {
                JsonNode item = data.get(pos);
                int idx = item.has("index") ? item.get("index").asInt() : pos;
                JsonNode vecNode = item.get("embedding");
                if (idx < 0 || idx >= expectedSize || vecNode == null || !vecNode.isArray() || vecNode.isEmpty()) {
                    throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 响应向量格式非法");
                }
                float[] vec = new float[vecNode.size()];
                for (int j = 0; j < vecNode.size(); j++) {
                    vec[j] = (float) vecNode.get(j).asDouble();
                }
                ordered[idx] = vec;
            }
            for (float[] vec : ordered) {
                if (vec == null) {
                    throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "Embedding 响应缺少部分 index 槽位");
                }
            }
            return Arrays.asList(ordered);
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.EMBEDDING_UNAVAILABLE, "解析 Embedding JSON 失败: " + e.getMessage(), e);
        }
    }

    // 拼成 pgvector 的 '[0.1,0.2,...]' 字面量，配合 SQL cast(? as vector) 写入，避免引入额外第三方依赖
    public static String toVectorLiteral(float[] vec) {
        if (vec == null || vec.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(vec.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
