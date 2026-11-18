package cn.keeprising.app;

import cn.keeprising.core.Failure;
import cn.keeprising.core.RrfFusion;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库文档管理与只读检索调试 REST 入口：支持上传覆盖、标签过滤列举、切片查看、级联删除，以及 /api/retrieval/debug 调试检索排序。
 */
@RestController
public class DocumentController {

    private final DocumentService documentService;
    private final RetrievalService retrievalService;

    public DocumentController(DocumentService documentService, RetrievalService retrievalService) {
        this.documentService = documentService;
        this.retrievalService = retrievalService;
    }

    @PostMapping("/api/documents")
    public ResponseEntity<DocumentService.IngestResult> ingest(@RequestBody(required = false) IngestRequest request) {
        if (request == null) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "文档请求体不能为空");
        }
        LocalDate date = (request.docDate() == null || request.docDate().isBlank())
                ? null : parseDate(request.docDate());
        DocumentService.IngestResult result = documentService.ingest(
                AppConfig.LOCAL_USER, request.title(), request.content(), request.tags(), date);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/api/documents")
    public List<Document> list(@RequestParam(required = false) String tag) {
        return documentService.listDocuments(AppConfig.LOCAL_USER, tag);
    }

    @GetMapping("/api/documents/{id}/chunks")
    public List<ChunkRepository.ChunkRow> chunks(@PathVariable UUID id) {
        return documentService.getChunks(AppConfig.LOCAL_USER, id);
    }

    @DeleteMapping("/api/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        documentService.deleteDocument(AppConfig.LOCAL_USER, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/retrieval/debug")
    public RetrievalDebugResponse debugRetrieval(@RequestBody(required = false) DebugRetrievalRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "query 不能为空");
        }
        RetrievalService.Scope scope = new RetrievalService.Scope(request.owner(), request.tags());
        RetrievalService.RetrievalResult result = retrievalService.retrieve(
                AppConfig.LOCAL_USER, request.query(), scope, request.topK());
        List<DebugChunkItem> items = result.chunks().stream()
                .map(c -> new DebugChunkItem(
                        c.chunkId(), c.documentId(), c.docTitle(), c.heading(),
                        c.score(), c.vectorScore(), c.keywordScore(), c.content()))
                .toList();
        return new RetrievalDebugResponse(result.degraded(), result.degradedReason(), items);
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String, Object>> handleFailure(Failure failure) {
        return ResponseEntity.status(failure.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INVALID_ARGUMENT, "docDate 必须为 YYYY-MM-DD 格式");
        }
    }

    public record IngestRequest(String title, String content, List<String> tags, String docDate) {
    }

    public record DebugRetrievalRequest(String query, String owner, List<String> tags, Integer topK) {
    }

    public record DebugChunkItem(
            long chunkId, UUID documentId, String docTitle, String heading,
            double score, double vectorScore, double keywordScore, String content) {
    }

    public record RetrievalDebugResponse(
            boolean degraded, String degradedReason, List<DebugChunkItem> chunks) {
    }
}
