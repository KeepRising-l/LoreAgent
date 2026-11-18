package cn.keeprising.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamChunkAndEventTest {

    @Test
    void shouldSupportExhaustiveSwitchAndChunkFactories() {
        StreamChunk textChunk = StreamChunk.text("你好");
        assertThat(textChunk.textDelta()).isEqualTo("你好");
        assertThat(textChunk.toolCallDeltas()).isEmpty();

        StreamChunk endChunk = StreamChunk.finish("stop", new StreamChunk.Usage(10, 5, 15));
        assertThat(endChunk.finishReason()).isEqualTo("stop");
        assertThat(endChunk.usage().totalTokens()).isEqualTo(15);

        AgentEvent event = new AgentEvent.Delta("req_1", "你好");
        String rendered;
        if (event instanceof AgentEvent.Delta d) {
            rendered = "delta:" + d.text();
        } else if (event instanceof AgentEvent.ToolStarted ts) {
            rendered = "tool_start:" + ts.toolName();
        } else if (event instanceof AgentEvent.ToolFinished tf) {
            rendered = "tool_end:" + tf.toolName();
        } else if (event instanceof AgentEvent.Done dn) {
            rendered = "done:" + dn.finishReason();
        } else if (event instanceof AgentEvent.Error err) {
            rendered = "error:" + err.code();
        } else {
            throw new IllegalStateException("未处理的事件类型: " + event);
        }

        assertThat(event.requestId()).isEqualTo("req_1");
        assertThat(rendered).isEqualTo("delta:你好");
    }
}
