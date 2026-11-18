package cn.keeprising.core;

import cn.keeprising.spi.Message;
import cn.keeprising.spi.Tool;
import cn.keeprising.spi.ToolCall;

import java.util.List;

/**
 * 轻量上下文 token 估算器（不引第三方分词库，可解释、零外部依赖）：
 * 中文/非 ASCII 字符按 1 字≈1.5 token 向上取整（宁多勿少保安全），ASCII 按 4 字符≈1 token。
 */
public class TokenEstimator {

    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int nonAscii = 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                nonAscii++;
            } else {
                ascii++;
            }
        }
        int tokens = (nonAscii * 3 + 1) / 2 + (ascii + 3) / 4;
        return Math.max(1, tokens);
    }

    public int estimateMessage(Message message) {
        if (message == null) {
            return 0;
        }
        int total = 4 + estimateText(message.role()) + estimateText(message.content());
        if (message.toolCallId() != null) {
            total += estimateText(message.toolCallId());
        }
        for (ToolCall tc : message.toolCalls()) {
            total += 8 + estimateText(tc.id()) + estimateText(tc.name()) + estimateText(tc.argumentsJson());
        }
        return total;
    }

    public int estimateTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Tool tool : tools) {
            total += 16 + estimateText(tool.name())
                    + estimateText(tool.description())
                    + estimateText(tool.parametersSchemaJson());
        }
        return total;
    }
}
