package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.spi.ToolCall;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯单测用的内存假仓储（不使用 Mockito，也不连接 PostgreSQL）。
 */
@TestConfiguration
public class InMemoryTestRepos {

    static final AtomicBoolean FAIL_NEXT_MESSAGE_APPEND = new AtomicBoolean(false);
    static final AtomicBoolean FAIL_NEXT_ASSISTANT_APPEND = new AtomicBoolean(false);

    @Bean
    @Primary
    public TransactionOperations testTransactionOperations() {
        return TransactionOperations.withoutTransaction();
    }

    @Bean
    @Primary
    public ConversationRepository fakeConversationRepository(FakeStore store) {
        return new ConversationRepository() {
            @Override
            public Conversation create(String userId, UUID id, String title) {
                Instant now = store.nextTick();
                Conversation conv = new Conversation(id, userId, title, now, now);
                store.conversations.put(id, conv);
                return conv;
            }

            @Override
            public Optional<Conversation> findById(String userId, UUID id) {
                Conversation c = store.conversations.get(id);
                return (c != null && c.userId().equals(userId)) ? Optional.of(c) : Optional.empty();
            }

            @Override
            public List<Conversation> list(String userId) {
                return store.conversations.values().stream()
                        .filter(c -> c.userId().equals(userId))
                        .sorted(Comparator.comparing(Conversation::updatedAt).reversed()
                                .thenComparing(Conversation::id, Comparator.reverseOrder()))
                        .toList();
            }

            @Override
            public boolean rename(String userId, UUID id, String title) {
                Conversation c = store.conversations.get(id);
                if (c == null || !c.userId().equals(userId)) {
                    return false;
                }
                store.conversations.put(id, new Conversation(c.id(), c.userId(), title, c.createdAt(), store.nextTick()));
                return true;
            }

            @Override
            public void touch(String userId, UUID id) {
                Conversation c = store.conversations.get(id);
                if (c != null && c.userId().equals(userId)) {
                    store.conversations.put(id, new Conversation(c.id(), c.userId(), c.title(), c.createdAt(), store.nextTick()));
                }
            }

            @Override
            public boolean delete(String userId, UUID id) {
                Conversation c = store.conversations.get(id);
                if (c == null || !c.userId().equals(userId)) {
                    return false;
                }
                store.conversations.remove(id);
                store.messages.removeIf(m -> m.conversationId().equals(id));
                return true;
            }
        };
    }

    @Bean
    @Primary
    public MessageRepository fakeMessageRepository(FakeStore store) {
        return new MessageRepository() {
            @Override
            public ChatMessage append(
                    String userId, UUID conversationId, String role, String content,
                    List<ToolCall> toolCalls, String citationsJson, String requestId) {
                if (FAIL_NEXT_MESSAGE_APPEND.getAndSet(false)) {
                    throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "模拟落库失败");
                }
                if ("assistant".equals(role) && requestId != null && FAIL_NEXT_ASSISTANT_APPEND.getAndSet(false)) {
                    throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "模拟终态落库失败");
                }
                if ("assistant".equals(role) && requestId != null) {
                    boolean dup = store.messages.stream()
                            .anyMatch(m -> "assistant".equals(m.role()) && requestId.equals(m.requestId()));
                    if (dup) {
                        throw new Failure(Failure.ErrorCode.STORAGE_ERROR, "duplicate request_id=" + requestId);
                    }
                }
                int nextSeq = store.messages.stream()
                        .filter(m -> m.conversationId().equals(conversationId))
                        .mapToInt(ChatMessage::seq)
                        .max().orElse(0) + 1;
                ChatMessage saved = new ChatMessage(
                        store.msgId.incrementAndGet(), conversationId, userId, nextSeq, role,
                        content == null ? "" : content, toolCalls, citationsJson, requestId, Instant.now());
                store.messages.add(saved);
                return saved;
            }

            @Override
            public List<ChatMessage> listByConversation(String userId, UUID conversationId) {
                return store.messages.stream()
                        .filter(m -> m.userId().equals(userId) && m.conversationId().equals(conversationId))
                        .sorted(Comparator.comparingInt(ChatMessage::seq))
                        .toList();
            }

            @Override
            public List<ChatMessage> listRecent(String userId, UUID conversationId, int limit) {
                List<ChatMessage> all = listByConversation(userId, conversationId);
                return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
            }

            @Override
            public Optional<ChatMessage> findById(String userId, UUID conversationId, long messageId) {
                return store.messages.stream()
                        .filter(m -> m.id() == messageId && m.userId().equals(userId)
                                && m.conversationId().equals(conversationId))
                        .findFirst();
            }

            @Override
            public boolean hasAssistantReplyAfter(String userId, UUID conversationId, int userSeq) {
                return store.messages.stream().anyMatch(m -> m.userId().equals(userId)
                        && m.conversationId().equals(conversationId)
                        && m.seq() > userSeq
                        && "assistant".equals(m.role())
                        && m.requestId() != null);
            }

            @Override
            public Optional<ChatMessage> findCompletedAssistantByRequestId(String userId, String requestId) {
                if (requestId == null || requestId.isBlank()) {
                    return Optional.empty();
                }
                return store.messages.stream()
                        .filter(m -> m.userId().equals(userId) && "assistant".equals(m.role())
                                && requestId.equals(m.requestId()))
                        .findFirst();
            }
        };
    }

    @Bean
    public FakeStore fakeStore() {
        return new FakeStore();
    }

    public static class FakeStore {
        final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
        final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong msgId = new AtomicLong();
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

        Instant nextTick() {
            return Instant.ofEpochMilli(clock.addAndGet(100));
        }
    }
}
