package cn.keeprising.spi;

/**
 * 内核执行过程中向外发出的不可变事件（AgentLoop 自己不碰 Spring 的 SseEmitter，靠这些事件往外报信）。
 * 采用 Java 17 sealed interface 严格限定只有下面 5 种事件，避免消费端出现未登记的野事件。
 */
public sealed interface AgentEvent
        permits AgentEvent.Delta,
                AgentEvent.ToolStarted,
                AgentEvent.ToolFinished,
                AgentEvent.Done,
                AgentEvent.Error {

    // 所有事件都带 requestId，方便日志和前端追踪同一次请求
    String requestId();

    // 增量吐字事件：每收到一小段文字推一次
    record Delta(String requestId, String text) implements AgentEvent {
    }

    // 工具开始执行事件（Phase 1b 接入工具后发出，用于界面提示“正在调用某个工具”）
    record ToolStarted(String requestId, String toolCallId, String toolName, String argumentsJson)
            implements AgentEvent {
    }

    // 工具执行结束事件（Phase 1b 接入工具后发出）
    record ToolFinished(
            String requestId,
            String toolCallId,
            String toolName,
            boolean success,
            String outputPreview)
            implements AgentEvent {
    }

    // 本轮对话顺利完成事件：带上结束原因、完整拼好的回答和 token 用量
    record Done(
            String requestId,
            String finishReason,
            String fullContent,
            StreamChunk.Usage usage)
            implements AgentEvent {
    }

    // 执行失败事件：带上错误码和可读提示
    record Error(String requestId, String code, String message) implements AgentEvent {
    }
}
