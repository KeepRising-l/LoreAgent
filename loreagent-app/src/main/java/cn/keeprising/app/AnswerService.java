package cn.keeprising.app;

import cn.keeprising.core.*;
import cn.keeprising.spi.AgentEvent;
import cn.keeprising.spi.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;

/**
 * RAG 问答编排服务：在知识库非空或显式指定作用域时，依次执行
 * RetrievalService 召回 → RefusalPolicy 拒答门控 → 注入带 [K1] 引用规则的上下文 → 委托 AgentLoop 流式生成 → CitationValidator 校验与轨迹落库。
 */
@Service
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RETRIEVAL_TOP_K = 5;

    private final DocumentRepository documentRepo;
    private final RetrievalService retrievalService;
    private final RefusalPolicy refusalPolicy;
    private final CitationValidator citationValidator;
    private final RunTraceRepository traceRepo;
    private final AgentLoop agentLoop;

    public AnswerService(
            DocumentRepository documentRepo,
            RetrievalService retrievalService,
            RefusalPolicy refusalPolicy,
            CitationValidator citationValidator,
            RunTraceRepository traceRepo,
            AgentLoop agentLoop) {
        this.documentRepo = documentRepo;
        this.retrievalService = retrievalService;
        this.refusalPolicy = refusalPolicy;
        this.citationValidator = citationValidator;
        this.traceRepo = traceRepo;
        this.agentLoop = agentLoop;
    }

    public AnswerOutcome answer(
            TraceContext trace,
            String userId,
            UUID conversationId,
            String modelName,
            String query,
            List<Message> history,
            RetrievalService.Scope scope,
            RunBudget budget,
            Cancellation cancellation,
            Consumer<AgentEvent> eventSink) {
        boolean hasScope = scope != null && ((scope.owner() != null && !scope.owner().isBlank())
                || (scope.tags() != null && !scope.tags().isEmpty()));
        boolean hasDocs = documentRepo.count(userId) > 0;
        if (!hasDocs && !hasScope) {
            AgentEvent.Done done = agentLoop.run(trace, modelName, history, budget, cancellation, eventSink);
            return new AnswerOutcome(done, List.of(), null);
        }

        RetrievalService.RetrievalResult retrieval = retrievalService.retrieve(userId, query, scope, RETRIEVAL_TOP_K);
        RefusalPolicy.Decision decision = refusalPolicy.evaluate(retrieval.chunks(), retrieval.degraded());
        if (decision.refused()) {
            traceRepo.save(userId, conversationId, trace.requestId(), query,
                    toTraces(retrieval.chunks()), retrieval.degraded(), "refused", null);
            eventSink.accept(new AgentEvent.Delta(trace.requestId(), decision.refusalMessage()));
            AgentEvent.Done refused = new AgentEvent.Done(trace.requestId(), "stop", decision.refusalMessage(), null);
            return new AnswerOutcome(refused, List.of(), null);
        }

        String prefix = retrieval.degraded()
                ? "> 注：" + retrieval.degradedReason() + "。\n\n"
                : "";
        if (!prefix.isEmpty()) {
            eventSink.accept(new AgentEvent.Delta(trace.requestId(), prefix));
        }

        List<Message> ragHistory = buildRagMessages(history, retrieval.chunks(), retrieval.degraded());
        boolean[] usedTools = {false};
        AgentEvent.Done rawDone = agentLoop.run(trace, modelName, ragHistory, budget, cancellation, ev -> {
            if (ev instanceof AgentEvent.ToolStarted) {
                usedTools[0] = true;
            }
            if (!(ev instanceof AgentEvent.Done)) {
                eventSink.accept(ev);
            }
        });

        String finalContent = prefix + rawDone.fullContent();
        List<CitationValidator.Citation> citations;
        try {
            citations = citationValidator.validate(finalContent, retrieval.chunks(), usedTools[0]);
        } catch (Failure failure) {
            traceRepo.save(userId, conversationId, trace.requestId(), query,
                    toTraces(retrieval.chunks()), retrieval.degraded(), "invalid_citation", rawDone.usage());
            eventSink.accept(new AgentEvent.Error(trace.requestId(), failure.code().name(), failure.getMessage()));
            throw failure;
        }

        traceRepo.save(userId, conversationId, trace.requestId(), query,
                toTraces(retrieval.chunks()), retrieval.degraded(), rawDone.finishReason(), rawDone.usage());
        log.info("{} event=rag_answered chunks={} citations={} degraded={}",
                trace.logPrefix(), retrieval.chunks().size(), citations.size(), retrieval.degraded());

        AgentEvent.Done finalDone = new AgentEvent.Done(
                rawDone.requestId(), rawDone.finishReason(), finalContent, rawDone.usage());
        String citationsJson = citations.isEmpty() ? null : toJson(citations);
        return new AnswerOutcome(finalDone, citations, citationsJson);
    }

    private List<Message> buildRagMessages(
            List<Message> history, List<RrfFusion.ScoredChunk> chunks, boolean degraded) {
        StringBuilder sys = new StringBuilder();
        sys.append("你是 LoreAgent 个人知识库助手。请严格依据下面给出的【参考资料】回答用户问题，并遵守以下硬性规则：\n");
        sys.append("1. 每条源自参考资料的事实后必须紧跟对应引用编号，格式统一为 [K1]、[K2] 等。\n");
        sys.append("2. 严禁使用未在下方出现的 [Kn] 编号，也不要编造资料之外的细节。\n");
        sys.append("3. 若参考资料不足以支撑回答，请直接明确回复“依据不足”。\n");
        if (degraded) {
            sys.append("4. 当前向量检索已降级为关键词检索，请在回答中保留降级说明提示用户。\n");
        }
        sys.append("\n【参考资料】\n");
        for (int i = 0; i < chunks.size(); i++) {
            RrfFusion.ScoredChunk c = chunks.get(i);
            sys.append("[K").append(i + 1).append("] 文档《").append(c.docTitle()).append("》");
            if (c.heading() != null && !c.heading().isBlank()) {
                sys.append("（章节：").append(c.heading()).append("）");
            }
            sys.append("：\n").append(c.content()).append("\n\n");
        }

        List<Message> out = new ArrayList<>(history.size() + 1);
        out.add(Message.system(sys.toString()));
        out.addAll(history);
        return List.copyOf(out);
    }

    private List<RunTraceRepository.RetrievedChunkTrace> toTraces(List<RrfFusion.ScoredChunk> chunks) {
        return chunks.stream()
                .map(c -> new RunTraceRepository.RetrievedChunkTrace(
                        c.documentId(), c.chunkId(), c.docTitle(), c.score(), c.vectorScore(), c.keywordScore()))
                .toList();
    }

    private String toJson(List<CitationValidator.Citation> citations) {
        try {
            return MAPPER.writeValueAsString(citations);
        } catch (Exception e) {
            throw new Failure(Failure.ErrorCode.INTERNAL_ERROR, "序列化引用信息失败: " + e.getMessage(), e);
        }
    }

    public record AnswerOutcome(
            AgentEvent.Done done, List<CitationValidator.Citation> citations, String citationsJson) {
    }
}
