package cn.keeprising.core;

import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.LlmAdapter;
import cn.keeprising.spi.Message;
import cn.keeprising.spi.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    @Test
    void shouldEmitDeltaAndDoneEventsInSingleTurn() {
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            onChunk.accept(StreamChunk.text("你"));
            onChunk.accept(StreamChunk.text("好"));
            onChunk.accept(StreamChunk.finish("stop", new StreamChunk.Usage(4, 2, 6)));
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", fake));
        List<AgentEvent> events = new ArrayList<>();

        AgentEvent.Done done = loop.run(
                new TraceContext("req_1", ""), null, List.of(Message.user("hi")), events::add, () -> false);

        assertThat(done.fullContent()).isEqualTo("你好");
        assertThat(done.finishReason()).isEqualTo("stop");
        assertThat(events).containsExactly(
                new AgentEvent.Delta("req_1", "你"),
                new AgentEvent.Delta("req_1", "好"),
                new AgentEvent.Done("req_1", "stop", "你好", new StreamChunk.Usage(4, 2, 6)));
    }

    @Test
    void shouldPreserveLengthFinishReasonWithoutPretendingComplete() {
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            onChunk.accept(StreamChunk.text("截断一半"));
            onChunk.accept(StreamChunk.finish("length", null));
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", fake));
        List<AgentEvent> events = new ArrayList<>();

        AgentEvent.Done done = loop.run(
                new TraceContext("req_2", ""), "test-model", List.of(Message.user("写长文")), events::add, () -> false);

        assertThat(done.finishReason()).isEqualTo("length");
    }

    @Test
    void shouldFailWithSpecificCodeWhenModelReturnsEmptyText() {
        LlmAdapter fake = (model, msgs, tools, onChunk, cancelled) -> {
            onChunk.accept(StreamChunk.text("   "));
            onChunk.accept(StreamChunk.finish("stop", null));
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", fake));
        List<AgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> loop.run(
                new TraceContext("req_3", ""), "test-model", List.of(Message.user("hi")), events::add, () -> false))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.MODEL_EMPTY_RESPONSE));

        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.Error.class);
        assertThat(((AgentEvent.Error) events.get(1)).code()).isEqualTo("MODEL_EMPTY_RESPONSE");
    }

    @Test
    void shouldEmitErrorEventWhenAdapterFails() {
        LlmAdapter failing = (model, msgs, tools, onChunk, cancelled) -> {
            throw new Failure(Failure.ErrorCode.MODEL_UNAVAILABLE, "upstream down");
        };
        AgentLoop loop = new AgentLoop(new ModelRegistry("test-model", failing));
        List<AgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> loop.run(
                new TraceContext("req_4", ""), "test-model", List.of(Message.user("hi")), events::add, () -> false))
                .isInstanceOf(Failure.class);

        assertThat(events).containsExactly(new AgentEvent.Error("req_4", "MODEL_UNAVAILABLE", "upstream down"));
    }
}
