package cn.keeprising.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseParserTest {

    @Test
    void shouldAssembleSplitChunksIntoCompleteDataEvents() {
        SseParser parser = new SseParser();
        List<String> events = new ArrayList<>();

        // 模拟网络把一行 JSON 切成两半发过来
        parser.feed("data: {\"te", events::add);
        assertThat(events).isEmpty(); // 半截行还没收到换行，先缓冲着不吐

        parser.feed("xt\":\"你好\"}\n\n", events::add);
        assertThat(events).containsExactly("{\"text\":\"你好\"}");
        assertThat(parser.isDone()).isFalse();
    }

    @Test
    void shouldStopAtDoneMarkerAndIgnoreCommentsAndCrLf() {
        SseParser parser = new SseParser();
        List<String> events = new ArrayList<>();

        parser.feed(": keep-alive\r\n\r\n", events::add);
        parser.feed("data: {\"text\":\"A\"}\r\n\r\n", events::add);
        parser.feed("data: [DONE]\r\n\r\n", events::add);
        parser.feed("data: {\"text\":\"should-ignore\"}\r\n\r\n", events::add);

        assertThat(events).containsExactly("{\"text\":\"A\"}");
        assertThat(parser.isDone()).isTrue();
    }

    @Test
    void shouldFlushLastEventWhenStreamEndsWithoutTrailingBlankLine() {
        SseParser parser = new SseParser();
        List<String> events = new ArrayList<>();

        parser.feed("data: {\"text\":\"最后一条\"}", events::add);
        assertThat(events).isEmpty();

        parser.finish(events::add);
        assertThat(events).containsExactly("{\"text\":\"最后一条\"}");
    }
}
