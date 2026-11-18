package cn.keeprising.core;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * SSE 流式文本切包器：专门对付网络发来“一截一截不完整的 JSON”。
 * 工作方式：先把没收齐的半行字攒在缓冲区里，等凑齐一整行并遇到空行，再去掉 "data:" 前缀吐给外层；遇到 "[DONE]" 就停。
 */
public class SseParser {

    // 还没遇到换行符的半截行先放这里攒着
    private final StringBuilder lineBuffer = new StringBuilder();
    // 已经读到 "data:" 但还没遇到结尾空行的数据先放这里攒着
    private final StringBuilder dataBuffer = new StringBuilder();
    // 是否已经看到结束标记 "[DONE]"
    private boolean done;

    // 往里塞一块新收到的网络字符串；每凑齐一条完整 JSON 就调一次 onData
    public void feed(String chunk, Consumer<String> onData) {
        Objects.requireNonNull(onData, "onData 不能为空");
        if (done || chunk == null || chunk.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\r') {
                // 兼容 Windows/代理返回的 \r\n，统一忽略 \r 只认 \n 作为行尾
                continue;
            }
            if (c == '\n') {
                processLine(lineBuffer.toString(), onData);
                lineBuffer.setLength(0);
                if (done) {
                    return;
                }
            } else {
                lineBuffer.append(c);
            }
        }
    }

    // 万一对方服务器关连接前忘了补最后那个空行，流结束时调一下它，把缓存里最后一条救出来
    public void finish(Consumer<String> onData) {
        Objects.requireNonNull(onData, "onData 不能为空");
        if (done) {
            return;
        }
        if (lineBuffer.length() > 0) {
            processLine(lineBuffer.toString(), onData);
            lineBuffer.setLength(0);
        }
        if (!done && dataBuffer.length() > 0) {
            emitEvent(onData);
        }
    }

    public boolean isDone() {
        return done;
    }

    // 处理凑齐的一行文本
    private void processLine(String line, Consumer<String> onData) {
        if (line.isEmpty()) {
            // 遇到空行说明一条 SSE 事件结束了，把攒好的数据吐出去
            if (dataBuffer.length() > 0) {
                emitEvent(onData);
            }
            return;
        }
        if (line.startsWith(":")) {
            // 冒号开头是服务器发来的保活心跳注释（如 : ping），直接跳过
            return;
        }
        if (line.startsWith("data:")) {
            // 规范允许 "data: xxx" 或 "data:xxx"，只剥离冒号后最多一个可选空格
            String payload = line.length() > 5 && line.charAt(5) == ' '
                    ? line.substring(6)
                    : line.substring(5);
            if (dataBuffer.length() > 0) {
                dataBuffer.append('\n');
            }
            dataBuffer.append(payload);
        }
    }

    // 把攒好的数据交出去，顺便识别是不是 "[DONE]"
    private void emitEvent(Consumer<String> onData) {
        String payload = dataBuffer.toString();
        dataBuffer.setLength(0);
        if ("[DONE]".equals(payload.trim())) {
            done = true;
            return;
        }
        onData.accept(payload);
    }
}
