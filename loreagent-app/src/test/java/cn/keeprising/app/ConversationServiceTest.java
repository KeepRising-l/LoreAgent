package cn.keeprising.app;

import cn.keeprising.core.Cancellation;
import cn.keeprising.core.Failure;
import cn.keeprising.core.RunRegistry;
import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.Message;
import cn.keeprising.spi.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationServiceTest {

    private final InMemoryTestRepos repos = new InMemoryTestRepos();
    private final InMemoryTestRepos.FakeStore store = repos.fakeStore();
    private final ConversationRepository convRepo = repos.fakeConversationRepository(store);
    private final MessageRepository msgRepo = repos.fakeMessageRepository(store);
    private final RunRegistry runRegistry = new RunRegistry();
    private final ConversationService service = new ConversationService(
            convRepo, msgRepo, runRegistry, TransactionOperations.withoutTransaction());

    @Test
    void shouldAutoTitleFromFirst20CharsMapToolHistoryAndRejectRetryAfterSuccess() {
        Conversation conv = service.createConversation("local", null);
        assertThat(conv.title()).isEqualTo("新对话");

        String longFirstQuestion = "请问这个是一段超过二十个字的测试问题用来检查自动截断标题功能";
        ConversationService.TurnStart turn1 = service.startTurn(
                "local", conv.id().toString(), longFirstQuestion, "req_1", null, new Cancellation());

        Conversation renamed = convRepo.findById("local", conv.id()).orElseThrow();
        assertThat(renamed.title()).hasSize(20);
        assertThat(turn1.history()).containsExactly(Message.user(longFirstQuestion));

        // 模拟中间调用工具 + 最终答复落库，再次发起第 2 轮时应能完整映射成 spi.Message
        List<ChatMessage> steps = List.of(
                new ChatMessage(0L, conv.id(), "local", 0, "assistant", "",
                        List.of(new ToolCall("c1", "calc", "{\"expr\":\"2+3\"}")), null, null, null),
                new ChatMessage(0L, conv.id(), "local", 0, "tool", "5",
                        List.of(new ToolCall("c1", "calc", "")), null, null, null));
        service.persistTurnOutputs("local", conv.id(), "req_1", steps,
                new AgentEvent.Done("req_1", "stop", "结果是5", null), null);

        // 已成功答复过的 user 消息不允许再带 retryMessageId 重试
        assertThatThrownBy(() -> service.startTurn(
                "local", conv.id().toString(), null, "req_retry_bad", turn1.userMessageId(), new Cancellation()))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).code()).isEqualTo(Failure.ErrorCode.INVALID_ARGUMENT));

        // 第二轮上下文里应完整保留第 1 轮的用户、助手工具调用、工具回填和最终助手答复
        ConversationService.TurnStart turn2 = service.startTurn(
                "local", conv.id().toString(), "再加1呢", "req_2", null, new Cancellation());
        assertThat(turn2.history()).hasSize(5);
        assertThat(turn2.history().get(1).toolCalls()).hasSize(1);
        assertThat(turn2.history().get(2).role()).isEqualTo("tool");
        assertThat(turn2.history().get(2).toolCallId()).isEqualTo("c1");
        assertThat(turn2.history().get(3).content()).isEqualTo("结果是5");
        service.abortTurn(conv.id(), "req_2");
    }

    @Test
    void shouldReuseUserMessageOnRetryWithoutInsertingDuplicateRow() {
        Conversation conv = service.createConversation("local", "测试重试");
        ConversationService.TurnStart failedTurn = service.startTurn(
                "local", conv.id().toString(), "你好", "req_fail_1", null, new Cancellation());
        // 模拟模型中途失败，释放锁但没有写入 assistant 回复
        service.abortTurn(conv.id(), "req_fail_1");

        ConversationService.TurnStart retryTurn = service.startTurn(
                "local", conv.id().toString(), "", "req_retry_2", failedTurn.userMessageId(), new Cancellation());
        assertThat(retryTurn.userMessageId()).isEqualTo(failedTurn.userMessageId());
        assertThat(service.getMessages("local", conv.id())).hasSize(1);
        service.abortTurn(conv.id(), "req_retry_2");
    }
}
