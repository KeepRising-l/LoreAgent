package cn.keeprising.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * 工具入参校验器：手写最小 JSON Schema 子集校验（只认 type / required / properties / enum / items，其余忽略）。
 * 模型经常会编出非法 JSON 或少填必填字段，在这里先拦死并返回明确错误码 INVALID_ARGUMENT。
 */
public class ToolArgumentsParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 解析模型传入的参数 JSON 并对照工具 schema 做校验，通过后返回解析好的 JsonNode
    public JsonNode parseAndValidate(String argumentsJson, String schemaJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "工具参数不能为空，需传入合法 JSON");
        }
        JsonNode argsNode;
        try {
            argsNode = MAPPER.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "工具参数不是合法 JSON: " + e.getOriginalMessage(), e);
        }
        if (schemaJson == null || schemaJson.isBlank()) {
            return argsNode;
        }
        JsonNode schemaNode;
        try {
            schemaNode = MAPPER.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, "工具 schema 配置非法: " + e.getOriginalMessage(), e);
        }
        validateNode("$", argsNode, schemaNode);
        return argsNode;
    }

    private void validateNode(String path, JsonNode value, JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        if (schema.hasNonNull("type")) {
            checkType(path, value, schema.get("type").asText());
        }
        if (schema.has("enum") && schema.get("enum").isArray()) {
            checkEnum(path, value, schema.get("enum"));
        }
        if (value.isObject()) {
            checkObject(path, value, schema);
        } else if (value.isArray() && schema.has("items")) {
            JsonNode itemSchema = schema.get("items");
            for (int i = 0; i < value.size(); i++) {
                validateNode(path + "[" + i + "]", value.get(i), itemSchema);
            }
        }
    }

    private void checkType(String path, JsonNode value, String expectedType) {
        boolean matched = switch (expectedType) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true; // 超出最小子集的类型关键字按约定忽略
        };
        if (!matched) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT,
                    "工具参数类型不符 schema（路径 " + path + " 期望 " + expectedType + "）");
        }
    }

    private void checkEnum(String path, JsonNode value, JsonNode enumNode) {
        for (JsonNode candidate : enumNode) {
            if (Objects.equals(candidate, value)) {
                return;
            }
        }
        throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT,
                "工具参数值不在 enum 允许范围内（路径 " + path + "）");
    }

    private void checkObject(String path, JsonNode value, JsonNode schema) {
        if (schema.has("required") && schema.get("required").isArray()) {
            for (JsonNode req : schema.get("required")) {
                String field = req.asText();
                if (!value.hasNonNull(field)) {
                    throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT,
                            "工具参数缺少必填字段: " + path + "." + field);
                }
            }
        }
        if (schema.has("properties") && schema.get("properties").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = schema.get("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                if (value.has(fieldName) && !value.get(fieldName).isNull()) {
                    validateNode(path + "." + fieldName, value.get(fieldName), entry.getValue());
                }
            }
        }
    }
}
