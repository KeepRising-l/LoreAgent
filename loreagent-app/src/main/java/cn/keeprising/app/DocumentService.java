package cn.keeprising.app;

import cn.keeprising.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDate;
import java.util.*;

/**
 * 文档入库与生命周期管理服务：解析 Markdown → 切片 → 分词 → 分批向量化 → 同事务原子覆盖旧版切片，并在删除时级联清理轨迹。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository docRepo;
    private final ChunkRepository chunkRepo;
    private final RunTraceRepository traceRepo;
    private final MarkdownParser markdownParser;
    private final Chunker chunker;
    private final Tokenizer tokenizer;
    private final EmbeddingClient embeddingClient;
    private final TransactionOperations tx;

    public DocumentService(
            DocumentRepository docRepo, ChunkRepository chunkRepo, RunTraceRepository traceRepo,
            MarkdownParser markdownParser, Chunker chunker, Tokenizer tokenizer,
            EmbeddingClient embeddingClient, TransactionOperations tx) {
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.traceRepo = traceRepo;
        this.markdownParser = markdownParser;
        this.chunker = chunker;
        this.tokenizer = tokenizer;
        this.embeddingClient = embeddingClient;
        this.tx = tx;
    }

    public IngestResult ingest(
            String userId, String rawTitle, String content, List<String> tags, LocalDate docDate) {
        MarkdownParser.ParsedDocument parsed = markdownParser.parse(content, rawTitle);
        String resolvedTitle = (rawTitle != null && !rawTitle.isBlank()) ? rawTitle.trim() : parsed.title();
        if (resolvedTitle == null || resolvedTitle.isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "文档标题不能为空");
        }
        List<String> resolvedTags = (tags != null && !tags.isEmpty()) ? tags : parsed.tags();
        LocalDate resolvedDate = docDate != null ? docDate : parsed.docDate();

        List<Chunker.ChunkDraft> drafts = chunker.chunk(parsed.sections());
        List<String> embedInputs = drafts.stream()
                .map(d -> (d.heading().isBlank() ? "" : d.heading() + "\n") + d.content())
                .toList();

        List<float[]> vectors = List.of();
        boolean embeddingDegraded = false;
        if (embeddingClient.isConfigured() && !embedInputs.isEmpty()) {
            try {
                vectors = embeddingClient.embedBatch(embedInputs);
            } catch (Failure f) {
                // Embedding 异常时仍允许仅写关键词 tokens 入库，保证知识库可用不阻断上传
                embeddingDegraded = true;
                log.warn("event=ingest_embedding_degraded title={} code={}", resolvedTitle, f.code());
            }
        }

        List<ChunkRepository.ChunkInsert> rows = buildChunkRows(drafts, vectors);
        Document saved = tx.execute(status -> {
            Document doc = docRepo.upsertVersion(userId, UUID.randomUUID(), resolvedTitle, resolvedTags, resolvedDate);
            chunkRepo.insertBatch(userId, doc.id(), doc.activeVersion(), rows);
            chunkRepo.deleteOldVersions(userId, doc.id(), doc.activeVersion());
            return doc;
        });
        log.info("event=doc_ingested docId={} version={} chunks={} embeddingDegraded={}",
                saved.id(), saved.activeVersion(), rows.size(), embeddingDegraded);
        return new IngestResult(saved, rows.size(), embeddingDegraded);
    }

    public List<Document> listDocuments(String userId, String tagFilter) {
        return docRepo.list(userId, tagFilter);
    }

    public List<ChunkRepository.ChunkRow> getChunks(String userId, UUID documentId) {
        docRepo.findById(userId, documentId)
                .orElseThrow(() -> new Failure(Failure.ErrorCode.NOT_FOUND, "文档不存在: " + documentId));
        return chunkRepo.listActiveByDocument(userId, documentId);
    }

    public void deleteDocument(String userId, UUID documentId) {
        tx.executeWithoutResult(status -> {
            traceRepo.deleteByDocumentId(userId, documentId);
            if (!docRepo.delete(userId, documentId)) {
                throw new Failure(Failure.ErrorCode.NOT_FOUND, "文档不存在: " + documentId);
            }
        });
        log.info("event=doc_deleted docId={}", documentId);
    }

    private List<ChunkRepository.ChunkInsert> buildChunkRows(
            List<Chunker.ChunkDraft> drafts, List<float[]> vectors) {
        List<ChunkRepository.ChunkInsert> rows = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            Chunker.ChunkDraft d = drafts.get(i);
            String tokens = tokenizer.toTsTokens((d.heading().isBlank() ? "" : d.heading() + " ") + d.content());
            float[] vec = i < vectors.size() ? vectors.get(i) : null;
            rows.add(new ChunkRepository.ChunkInsert(
                    d.chunkIndex(), d.heading(), d.content(), tokens,
                    vec == null ? null : EmbeddingClient.toVectorLiteral(vec),
                    vec == null ? null : embeddingClient.modelName(),
                    vec == null ? null : vec.length));
        }
        return rows;
    }

    public record IngestResult(Document document, int chunkCount, boolean embeddingDegraded) {
    }
}
