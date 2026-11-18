package cn.keeprising.core;

import java.util.Objects;

/**
 * 项目里所有“可预期的报错”都统一抛这个异常，不直接抛 RuntimeException。
 * 好处是：哪里出错了，外层一眼就能通过错误码知道该给前端回什么 HTTP 状态、该不该自动重试。
 */
public class Failure extends RuntimeException {

    private final ErrorCode code;

    public Failure(ErrorCode code, String message) {
        super(Objects.requireNonNull(message, "Failure.message 不能为空"));
        this.code = Objects.requireNonNull(code, "Failure.code 不能为空");
    }

    public Failure(ErrorCode code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "Failure.message 不能为空"), cause);
        this.code = Objects.requireNonNull(code, "Failure.code 不能为空");
    }

    public ErrorCode code() {
        return code;
    }

    // 告诉外层（如模型适配器）：遇到这个错能不能自动再试一次
    public boolean isRetryable() {
        return code.retryable();
    }

    // 告诉 ChatController：如果还没开始流式输出，应该给浏览器回什么 HTTP 状态码
    public int httpStatus() {
        return code.httpStatus();
    }

    /**
     * 错误码表：每个错误码顺带写死了 (HTTP状态码, 能不能重试)，省得代码里到处写 if/else 判断。
     */
    public enum ErrorCode {
        INVALID_ARGUMENT(400, false),      // 参数错/空消息（重发多少遍还是错，不重试）
        CONTEXT_OVERFLOW(400, false),      // 对话太长超上下文了
        NOT_FOUND(404, false),             // 会话/消息等业务资源不存在
        MODEL_NOT_FOUND(404, false),       // 找不到这个模型名
        SESSION_BUSY(409, false),          // 同一个会话上一条还没说完，拒绝并发插队
        BUDGET_EXCEEDED(429, false),       // 步数/时间/token 预算用光了
        CANCELLED(499, false),             // 用户点了停止或断开了连接
        MODEL_EMPTY_RESPONSE(502, false),  // 模型啥正经字都没吐，只回了个空白
        INVALID_CITATION(502, false),      // 引用编号 [Kn] 越界或与检索切片对不上，整次运行失败
        EMBEDDING_UNAVAILABLE(502, true),  // Embedding 向量服务 5xx 或超时（在线问答可降级关键词检索）
        MODEL_UNAVAILABLE(502, true),      // 模型接口挂了或 500 抖动（吐字前可重试）
        TIMEOUT(504, true),                // 等模型超时了（吐字前可重试）
        TOOL_EXECUTION_FAILED(500, false), // 本地工具运行出错
        STORAGE_ERROR(500, false),         // 数据库读写失败（禁止只吐流不落库静默成功）
        INTERNAL_ERROR(500, false);        // 其他内部异常兜底

        private final int httpStatus;
        private final boolean retryable;

        ErrorCode(int httpStatus, boolean retryable) {
            this.httpStatus = httpStatus;
            this.retryable = retryable;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
