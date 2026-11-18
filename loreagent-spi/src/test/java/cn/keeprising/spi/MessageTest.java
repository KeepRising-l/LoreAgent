package cn.keeprising.spi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {

    @Test
    void shouldCreateMessagesAndDefensivelyCopyToolCalls() {
        ToolCall call = new ToolCall("call_1", "calc", "{\"expr\":\"2+3\"}");
        List<ToolCall> mutable = new ArrayList<>(List.of(call));

        Message assistant = Message.assistantTools(null, mutable);
        mutable.clear();

        assertThat(assistant.role()).isEqualTo("assistant");
        assertThat(assistant.content()).isEmpty();
        assertThat(assistant.toolCalls()).containsExactly(call);

        ToolResult result = ToolResult.ok("call_1", "calc", "5");
        Message toolMsg = Message.tool(result.toolCallId(), result.content());
        assertThat(toolMsg.role()).isEqualTo("tool");
        assertThat(toolMsg.toolCallId()).isEqualTo("call_1");
        assertThat(toolMsg.content()).isEqualTo("5");
    }
}
