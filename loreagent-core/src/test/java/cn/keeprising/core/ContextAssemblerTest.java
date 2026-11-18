package cn.keeprising.core;

import cn.keeprising.spi.Message;
import cn.keeprising.spi.Tool;
import cn.keeprising.spi.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAssemblerTest {

    private final TokenEstimator estimator = new TokenEstimator();
    private final ContextAssembler assembler = new ContextAssembler(estimator);

    @Test
    void shouldDeductToolsKeepLatestTurnsAndPreserveToolCallPairs() {
        Tool calc = simpleTool("calc");
        Message sys = Message.system("你是助手");
        Message oldUser = Message.user("很早以前的一轮很长的历史问题，用来测试旧历史被整体裁掉");
        Message oldAssistant = Message.assistant("很早以前的一轮很长的历史回答，用来测试旧历史被整体裁掉");
        Message midCall = Message.assistantTools("", List.of(new ToolCall("c1", "calc", "{\"expr\":\"1+1\"}")));
        Message midTool = Message.tool("c1", "2");
        Message curUser = Message.user("现在算2+3");

        int exactLimit = estimator.estimateTools(List.of(calc))
                + estimator.estimateMessage(sys)
                + estimator.estimateMessage(midCall)
                + estimator.estimateMessage(midTool)
                + estimator.estimateMessage(curUser);

        List<Message> result = assembler.assemble(
                exactLimit,
                List.of(sys, oldUser, oldAssistant, midCall, midTool, curUser),
                List.of(calc));

        assertThat(result).containsExactly(sys, midCall, midTool, curUser);
    }

    @Test
    void shouldNeverSplitToolCallAndToolResultPairWhenBudgetIsTight() {
        Message sys = Message.system("s");
        Message oldCall = Message.assistantTools("", List.of(new ToolCall("c1", "calc", "{\"expr\":\"1+1\"}")));
        Message oldTool = Message.tool("c1", "这段工具返回值非常长，足以导致这一整对消息塞不进剩余预算");
        Message curUser = Message.user("hi");

        // 预算刚好够 sys + curUser + oldTool（或者 oldCall），但装不下 oldCall + oldTool 成对两者
        int tightLimit = estimator.estimateMessage(sys)
                + estimator.estimateMessage(curUser)
                + estimator.estimateMessage(oldCall) + 1;

        List<Message> result = assembler.assemble(tightLimit, List.of(sys, oldCall, oldTool, curUser), List.of());

        assertThat(result).containsExactly(sys, curUser);
    }

    @Test
    void shouldThrowActionableOverflowMessageWithExactExcessCount() {
        Message longUser = Message.user("这是一个非常长非常长非常长非常长非常长非常长的问题");

        assertThatThrownBy(() -> assembler.assemble(10, List.of(longUser), List.of()))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> {
                    Failure f = (Failure) ex;
                    assertThat(f.code()).isEqualTo(Failure.ErrorCode.CONTEXT_OVERFLOW);
                    assertThat(f.getMessage()).contains("超出上限").contains("建议：");
                });
    }

    private Tool simpleTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "math"; }
            @Override public String parametersSchemaJson() { return "{\"type\":\"object\"}"; }
            @Override public String execute(String argumentsJson) { return "0"; }
        };
    }
}
