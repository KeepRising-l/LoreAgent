package cn.keeprising.core;

import cn.keeprising.spi.AgentEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 运行态注册表（内存版，Phase 1.5 会把幂等终态升级到数据库）：
 * 1. 同会话并发互斥：同一 conversationId 同一时间只允许一条消息在跑，插队直接 409 SESSION_BUSY；
 * 2. 请求幂等：同一 clientRequestId 还在跑就拒绝（409），已经跑完就直接回放终态不重调模型。
 */
public class RunRegistry {

    private final Map<String, ActiveRun> activeByConversation = new HashMap<>();
    private final Map<String, RequestRecord> requestsById = new HashMap<>();

    // 原子抢占执行权：要么拿到执行权（或命中已完成回放），要么直接抛 409 SESSION_BUSY
    public synchronized AcquireResult acquire(
            String conversationId, String clientRequestId, Cancellation cancellation) {
        String convKey = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        Objects.requireNonNull(cancellation, "cancellation 不能为空");

        if (clientRequestId != null && !clientRequestId.isBlank()) {
            RequestRecord existing = requestsById.get(clientRequestId);
            if (existing != null) {
                if (existing.done() != null) {
                    return AcquireResult.replay(existing.done());
                }
                throw new Failure(Failure.ErrorCode.SESSION_BUSY,
                        "重复请求正在执行中 clientRequestId=" + clientRequestId);
            }
        }

        if (activeByConversation.containsKey(convKey)) {
            throw new Failure(Failure.ErrorCode.SESSION_BUSY,
                    "当前会话上一条消息尚未结束，请等待完成后再发");
        }

        activeByConversation.put(convKey, new ActiveRun(clientRequestId, cancellation));
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            requestsById.put(clientRequestId, new RequestRecord(convKey, null));
        }
        return AcquireResult.acquired(convKey);
    }

    // 正常结束：释放会话锁，并把终态记进幂等缓存，方便后续相同 clientRequestId 回放
    public synchronized void complete(String conversationId, String clientRequestId, AgentEvent.Done done) {
        String convKey = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        activeByConversation.remove(convKey);
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            requestsById.put(clientRequestId, new RequestRecord(convKey, Objects.requireNonNull(done)));
        }
    }

    // 失败或被取消：释放会话锁并清掉未完成的幂等占位，允许用户稍后重发
    public synchronized void abort(String conversationId, String clientRequestId) {
        String convKey = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        activeByConversation.remove(convKey);
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            RequestRecord existing = requestsById.get(clientRequestId);
            if (existing != null && existing.done() == null) {
                requestsById.remove(clientRequestId);
            }
        }
    }

    public synchronized int activeCount() {
        return activeByConversation.size();
    }

    private record ActiveRun(String clientRequestId, Cancellation cancellation) {
    }

    private record RequestRecord(String conversationId, AgentEvent.Done done) {
    }

    public record AcquireResult(String conversationKey, AgentEvent.Done replayDone) {

        static AcquireResult acquired(String conversationKey) {
            return new AcquireResult(conversationKey, null);
        }

        static AcquireResult replay(AgentEvent.Done done) {
            return new AcquireResult(null, done);
        }

        public boolean isReplay() {
            return replayDone != null;
        }
    }
}
