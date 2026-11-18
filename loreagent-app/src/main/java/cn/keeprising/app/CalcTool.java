package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.spi.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 内置四则运算计算器工具：
 * 全程无状态（解析器每次调用现用现建），多线程并发调用安全，不依赖外部脚本引擎。
 */
public class CalcTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["expr"],
              "properties": {
                "expr": {
                  "type": "string",
                  "description": "四则运算数学表达式，支持加减乘除与括号，例如 (2+3)*4"
                }
              }
            }
            """;

    @Override
    public String name() {
        return "calc";
    }

    @Override
    public String description() {
        return "精确计算四则运算数学表达式，支持加减乘除、小数和括号";
    }

    @Override
    public String parametersSchemaJson() {
        return SCHEMA;
    }

    @Override
    public String execute(String argumentsJson) {
        String expr;
        try {
            JsonNode root = MAPPER.readTree(argumentsJson);
            expr = root.path("expr").asText("");
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "calc 参数 JSON 解析失败: " + e.getMessage(), e);
        }
        if (expr.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "calc 表达式 expr 不能为空");
        }
        BigDecimal result = new ExpressionParser(expr).parse();
        BigDecimal normalized = result.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : result.stripTrailingZeros();
        return normalized.toPlainString();
    }

    // 递归下降四则运算解析器：每次 execute 单独创建一个实例，彻底避免共享可变状态（坑 #4）
    private static final class ExpressionParser {
        private final String input;
        private int pos;

        private ExpressionParser(String input) {
            this.input = input;
        }

        private BigDecimal parse() {
            BigDecimal value = parseAddSub();
            skipWhitespace();
            if (pos < input.length()) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT,
                        "表达式存在非法字符 '" + input.charAt(pos) + "': " + input);
            }
            return value;
        }

        private BigDecimal parseAddSub() {
            BigDecimal left = parseMulDiv();
            while (true) {
                skipWhitespace();
                if (eat('+')) {
                    left = left.add(parseMulDiv());
                } else if (eat('-')) {
                    left = left.subtract(parseMulDiv());
                } else {
                    return left;
                }
            }
        }

        private BigDecimal parseMulDiv() {
            BigDecimal left = parseUnary();
            while (true) {
                skipWhitespace();
                if (eat('*')) {
                    left = left.multiply(parseUnary());
                } else if (eat('/')) {
                    BigDecimal right = parseUnary();
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "除数不能为 0");
                    }
                    left = left.divide(right, 10, RoundingMode.HALF_UP);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal parseUnary() {
            skipWhitespace();
            if (eat('+')) {
                return parseUnary();
            }
            if (eat('-')) {
                return parseUnary().negate();
            }
            return parsePrimary();
        }

        private BigDecimal parsePrimary() {
            skipWhitespace();
            if (eat('(')) {
                BigDecimal inside = parseAddSub();
                skipWhitespace();
                if (!eat(')')) {
                    throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "表达式缺少右括号: " + input);
                }
                return inside;
            }
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "无法解析表达式: " + input);
            }
            try {
                return new BigDecimal(input.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "非法数字格式: " + input, e);
            }
        }

        private boolean eat(char expected) {
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
