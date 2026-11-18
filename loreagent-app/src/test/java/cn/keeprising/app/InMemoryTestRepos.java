package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.core.RrfFusion;
import cn.keeprising.core.Tokenizer;
import cn.keeprising.spi.StreamChunk;
import cn.keeprising.spi.ToolCall;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.time.LocalDate;
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
    @Primary
    public DocumentRepository fakeDocumentRepository(FakeStore store) {
        return new DocumentRepository() {
            @Override
            public Document upsertVersion(String userId, UUID id, String title, List<String> tags, LocalDate docDate) {
                Instant now = store.nextTick();
                Document existing = store.documents.values().stream()
                        .filter(d -> d.userId().equals(userId) && d.title().equals(title))
                        .findFirst().orElse(null);
                List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);
                Document saved = existing == null
                        ? new Document(id, userId, title, safeTags, docDate, 1, now, now)
                        : new Document(existing.id(), userId, title, safeTags, docDate,
                                existing.activeVersion() + 1, existing.createdAt(), now);
                store.documents.put(saved.id(), saved);
                return saved;
            }

            @Override
            public Optional<Document> findById(String userId, UUID id) {
                Document d = store.documents.get(id);
                return (d != null && d.userId().equals(userId)) ? Optional.of(d) : Optional.empty();
            }

            @Override
            public List<Document> list(String userId, String tagFilter) {
                return store.documents.values().stream()
                        .filter(d -> d.userId().equals(userId))
                        .filter(d -> tagFilter == null || tagFilter.isBlank() || d.tags().contains(tagFilter.trim()))
                        .sorted(Comparator.comparing(Document::updatedAt).reversed())
                        .toList();
            }

            @Override
            public long count(String userId) {
                return store.documents.values().stream().filter(d -> d.userId().equals(userId)).count();
            }

            @Override
            public boolean delete(String userId, UUID id) {
                Document d = store.documents.get(id);
                if (d == null || !d.userId().equals(userId)) {
                    return false;
                }
                store.documents.remove(id);
                store.chunks.removeIf(c -> c.documentId().equals(id));
                return true;
            }
        };
    }

    @Bean
    @Primary
    public ChunkRepository fakeChunkRepository(FakeStore store) {
        return new ChunkRepository() {
            @Override
            public void insertBatch(String userId, UUID documentId, int version, List<ChunkInsert> chunks) {
                for (ChunkInsert c : chunks) {
                    store.chunks.add(new ChunkRow(
                            store.chunkId.incrementAndGet(), documentId, userId, version,
                            c.chunkIndex(), c.heading(), c.content(), c.tokens(),
                            c.vectorLiteral(), c.embeddingModel(), c.dim()));
                }
            }

            @Override
            public int deleteOldVersions(String userId, UUID documentId, int activeVersion) {
                int before = store.chunks.size();
                store.chunks.removeIf(c -> c.userId().equals(userId) && c.documentId().equals(documentId)
                        && c.version() < activeVersion);
                return before - store.chunks.size();
            }

            @Override
            public List<ChunkRow> listActiveByDocument(String userId, UUID documentId) {
                Document d = store.documents.get(documentId);
                if (d == null || !d.userId().equals(userId)) {
                    return List.of();
                }
                return store.chunks.stream()
                        .filter(c -> c.userId().equals(userId) && c.documentId().equals(documentId)
                                && c.version() == d.activeVersion())
                        .sorted(Comparator.comparingInt(ChunkRow::chunkIndex))
                        .toList();
            }
        };
    }

    @Bean
    @Primary
    public RunTraceRepository fakeRunTraceRepository(FakeStore store) {
        return new RunTraceRepository() {
            @Override
            public void save(
                    String userId, UUID conversationId, String requestId, String query,
                    List<RetrievedChunkTrace> retrievedChunks, boolean degraded,
                    String finishReason, StreamChunk.Usage usage) {
                store.traceDocIds.put(requestId == null ? UUID.randomUUID().toString() : requestId,
                        retrievedChunks.stream().map(RetrievedChunkTrace::documentId).toList());
            }

            @Override
            public int deleteByDocumentId(String userId, UUID documentId) {
                return 0;
            }
        };
    }

    @Bean
    @Primary
    public VectorSearch fakeVectorSearch() {
        return new VectorSearch() {
            @Override
            public List<RrfFusion.ScoredChunk> search(
                    String userId, float[] queryVector, String embeddingModel,
                    String ownerFilter, List<String> tagFilters, int limit) {
                return List.of();
            }
        };
    }

    @Bean
    @Primary
    public KeywordSearch fakeKeywordSearch(FakeStore store, Tokenizer tokenizer) {
        return new KeywordSearch(tokenizer) {
            @Override
            public List<RrfFusion.ScoredChunk> search(
                    String userId, String query, String ownerFilter, List<String> tagFilters, int limit) {
                String tsQuery = tokenizer.toOrTsQuery(query);
                if (tsQuery.isBlank()) {
                    return List.of();
                }
                Set<String> qTerms = Set.of(tsQuery.split(" \\| "));
                return store.chunks.stream()
                        .filter(c -> c.userId().equals(userId))
                        .filter(c -> {
                            Document d = store.documents.get(c.documentId());
                            if (d == null || c.version() != d.activeVersion()) return false;
                            if (ownerFilter != null && !ownerFilter.isBlank() && !d.userId().equals(ownerFilter)) return false;
                            return tagFilters == null || tagFilters.isEmpty() || d.tags().stream().anyMatch(tagFilters::contains);
                        })
                        .map(c -> {
                            long hits = Arrays.stream(c.tokens().split(" ")).filter(qTerms::contains).count();
                            Document d = store.documents.get(c.documentId());
                            return new RrfFusion.ScoredChunk(
                                    c.id(), c.documentId(), d.title(), c.heading(), c.content(),
                                    c.chunkIndex(), hits, 0.0, hits);
                        })
                        .filter(sc -> sc.keywordScore() > 0)
                        .sorted(Comparator.comparingDouble(RrfFusion.ScoredChunk::keywordScore).reversed())
                        .limit(limit)
                        .toList();
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
        final Map<UUID, Document> documents = new ConcurrentHashMap<>();
        final List<ChunkRepository.ChunkRow> chunks = Collections.synchronizedList(new ArrayList<>());
        final Map<String, List<UUID>> traceDocIds = new ConcurrentHashMap<>();
        final AtomicLong msgId = new AtomicLong();
        final AtomicLong chunkId = new AtomicLong();
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

        Instant nextTick() {
            return Instant.ofEpochMilli(clock.addAndGet(100));
        }
    }
}
