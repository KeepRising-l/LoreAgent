package cn.keeprising.app;

import cn.keeprising.core.Cancellation;
import cn.keeprising.core.Failure;
import cn.keeprising.core.RunRegistry;
import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.Message;
import cn.keeprising.spi.ToolCall;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.*;

/**
 * 会话与消息持久化协调服务（全在 app 层，保持 core 对数据库零感知）。
 * 负责：会话 CRUD、首条消息自动命名、ChatMessage ↔ spi.Message 映射、
 * 持久化幂等回放、失败重试复用原 user 消息，以及整轮工具步骤与终态的原子落库。
 */
@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "新对话";
    private static final int HISTORY_LIMIT = 50;

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;
    private final RunRegistry runRegistry;
    private final TransactionOperations tx;

    public ConversationService(ConversationRepository conversationRepo, MessageRepository messageRepo,
            RunRegistry runRegistry, TransactionOperations tx) {
        this.conversationRepo = Objects.requireNonNull(conversationRepo);
        this.messageRepo = Objects.requireNonNull(messageRepo);
        this.runRegistry = Objects.requireNonNull(runRegistry);
        this.tx = Objects.requireNonNull(tx);
    }

    public Conversation createConversation(String userId, String title) {
        String resolved = (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
        return conversationRepo.create(userId, UUID.randomUUID(), resolved);
    }

    public List<Conversation> listConversations(String userId) {
        return conversationRepo.list(userId);
    }

    public Conversation renameConversation(String userId, UUID conversationId, String title) {
        if (title == null || title.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "会话标题不能为空");
        }
        if (!conversationRepo.rename(userId, conversationId, title.trim())) {
            throw new Failure(Failure.ErrorCode.NOT_FOUND, "会话不存在: " + conversationId);
        }
        return conversationRepo.findById(userId, conversationId)
                .orElseThrow(() -> new Failure(Failure.ErrorCode.NOT_FOUND, "会话不存在: " + conversationId));
    }

    public void deleteConversation(String userId, UUID conversationId) {
        if (!conversationRepo.delete(userId, conversationId)) {
            throw new Failure(Failure.ErrorCode.NOT_FOUND, "会话不存在: " + conversationId);
        }
    }

    public List<ChatMessage> getMessages(String userId, UUID conversationId) {
        requireConversation(userId, conversationId);
        return messageRepo.listByConversation(userId, conversationId);
    }

    // 开跑前准备：检查持久化幂等 → 抢占会话并发锁 → 落库 user 消息（或校验 retryMessageId）→ 加载最近历史
    public TurnStart startTurn(
            String userId, String rawConversationId, String rawMessage,
            String clientRequestId, Long retryMessageId, Cancellation cancellation) {
        UUID convId = parseOrCreateConversationId(userId, rawConversationId, retryMessageId);
        Optional<ChatMessage> dbDone = messageRepo.findCompletedAssistantByRequestId(userId, clientRequestId);
        if (dbDone.isPresent()) {
            ChatMessage done = dbDone.get();
            return TurnStart.replay(done.conversationId(), done.content(), clientRequestId);
        }
        RunRegistry.AcquireResult acquired = runRegistry.acquire(convId.toString(), clientRequestId, cancellation);
        if (acquired.isReplay()) {
            AgentEvent.Done d = acquired.replayDone();
            return TurnStart.replay(convId, d.fullContent(), d.requestId());
        }

        try {
            return tx.execute(status -> {
                Optional<ChatMessage> raced = messageRepo.findCompletedAssistantByRequestId(userId, clientRequestId);
                if (raced.isPresent()) {
                    runRegistry.abort(convId.toString(), clientRequestId);
                    return TurnStart.replay(raced.get().conversationId(), raced.get().content(), clientRequestId);
                }
                Conversation conv = requireConversation(userId, convId);
                ChatMessage userMsg = resolveOrAppendUserMessage(userId, conv, rawMessage, clientRequestId, retryMessageId);
                List<Message> spiMessages = messageRepo.listRecent(userId, convId, HISTORY_LIMIT).stream()
                        .map(this::toSpiMessage).toList();
                return new TurnStart(convId, userMsg.id(), spiMessages, null, null);
            });
        } catch (RuntimeException e) {
            runRegistry.abort(convId.toString(), clientRequestId);
            throw e;
        }
    }

    // 整轮结束后把工具中间轮次（requestId=null）与最终助手回复（带 requestId）在同一个事务内落库
    public void persistTurnOutputs(
            String userId, UUID conversationId, String clientRequestId,
            List<ChatMessage> intermediateSteps, AgentEvent.Done done) {
        tx.executeWithoutResult(status -> {
            for (ChatMessage step : intermediateSteps) {
                messageRepo.append(userId, conversationId, step.role(), step.content(), step.toolCalls(), null, null);
            }
            messageRepo.append(userId, conversationId, "assistant", done.fullContent(), List.of(), null, clientRequestId);
            conversationRepo.touch(userId, conversationId);
        });
        runRegistry.complete(conversationId.toString(), clientRequestId, done);
    }

    public void abortTurn(UUID conversationId, String clientRequestId) {
        if (conversationId != null) {
            runRegistry.abort(conversationId.toString(), clientRequestId);
        }
    }

    private ChatMessage resolveOrAppendUserMessage(
            String userId, Conversation conv, String rawMessage, String clientRequestId, Long retryMessageId) {
        if (retryMessageId != null) {
            ChatMessage target = messageRepo.findById(userId, conv.id(), retryMessageId)
                    .orElseThrow(() -> new Failure(Failure.ErrorCode.NOT_FOUND, "重试目标消息不存在: " + retryMessageId));
            if (!"user".equals(target.role()) || messageRepo.hasAssistantReplyAfter(userId, conv.id(), target.seq())) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "该消息已成功回复或非用户消息，不能重试");
            }
            conversationRepo.touch(userId, conv.id());
            return target;
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "message 不能为空");
        }
        ChatMessage userMsg = messageRepo.append(
                userId, conv.id(), "user", rawMessage, List.of(), null, clientRequestId);
        if (userMsg.seq() == 1 && DEFAULT_TITLE.equals(conv.title())) {
            conversationRepo.rename(userId, conv.id(), summarizeTitle(rawMessage));
        } else {
            conversationRepo.touch(userId, conv.id());
        }
        return userMsg;
    }

    private UUID parseOrCreateConversationId(String userId, String rawId, Long retryMessageId) {
        if (rawId == null || rawId.isBlank()) {
            if (retryMessageId != null) {
                throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "带 retryMessageId 重试时必须传 conversationId");
            }
            return createConversation(userId, DEFAULT_TITLE).id();
        }
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "conversationId 必须是合法 UUID");
        }
    }

    private Conversation requireConversation(String userId, UUID id) {
        return conversationRepo.findById(userId, id)
                .orElseThrow(() -> new Failure(Failure.ErrorCode.NOT_FOUND, "会话不存在: " + id));
    }

    // 标题不调模型：取首条用户消息前 20 字
    private String summarizeTitle(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= 20 ? clean : clean.substring(0, 20);
    }

    private Message toSpiMessage(ChatMessage m) {
        return switch (m.role()) {
            case "system" -> Message.system(m.content());
            case "user" -> Message.user(m.content());
            case "assistant" -> m.toolCalls().isEmpty()
                    ? Message.assistant(m.content())
                    : Message.assistantTools(m.content(), m.toolCalls());
            case "tool" -> {
                if (m.toolCalls().isEmpty()) {
                    throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "工具消息缺少 toolCallId 元数据 id=" + m.id());
                }
                yield Message.tool(m.toolCalls().get(0).id(), m.content());
            }
            default -> throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "未知消息角色: " + m.role());
        };
    }

    public record TurnStart(
            UUID conversationId, long userMessageId, List<Message> history,
            String replayContent, String replayRequestId) {
        static TurnStart replay(UUID convId, String content, String requestId) {
            return new TurnStart(convId, 0L, List.of(), content, requestId);
        }
        public boolean isReplay() { return replayContent != null; }
    }
}
