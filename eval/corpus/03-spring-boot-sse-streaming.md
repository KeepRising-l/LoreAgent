# Spring Boot 下 SseEmitter 流式输出与客户端断连取消传播

在基于 LLM 的问答应用中，模型生成完整回复往往需要 3 到 15 秒甚至更久，如果采用传统的阻塞式 HTTP JSON 响应，用户将长时间面对空白屏幕。Server-Sent Events（SSE，媒体类型 `text/event-stream`）基于单向 HTTP 长连接按行逐片推送事件，比双向 WebSocket 更轻量、天然兼容 HTTP 负载均衡器，是目前主流大模型对话接口的标准协议。

在 Spring MVC 中，我们选用 `SseEmitter` 而不是引入整套响应式 WebFlux 栈，核心原因在于可理解性与调测成本：常规 `JdbcTemplate` 与阻塞 IO 线程池模型清晰直观，配合 `SseEmitter` 的异步推流即可满足高并发低首字延迟需求，无需为局部流式场景重构全链路响应式编程模型。

## 一、异步执行线程与 Servlet 容器线程解耦

使用 `SseEmitter` 时最忌讳直接在 Tomcat HTTP 工作线程里同步等待上游 LLM 流。正确结构应当是：

1. Controller 方法在主请求线程完成参数校验、幂等查重、`RunRegistry` 会话并发锁抢占以及首条用户消息落库；
2. 随后立即创建 `SseEmitter` 实例并把耗时的 `AgentLoop` 推理循环提交到独立的后台线程池 `chatExecutor`；
3. Controller 方法立刻把 `emitter` 返回给 Spring MVC，释放 Tomcat 请求线程去服务其他并发请求；
4. 后台线程通过 `emitter.send(SseEmitter.event().name("delta").data(...))` 逐字吐字，结束后调用 `emitter.complete()` 正式关闭流。

## 二、SseEmitter 必须显式解决的两个深坑

### 1. 默认 30 秒超时坑
在 Spring Boot 内嵌 Tomcat 环境下，如果不传参数直接 `new SseEmitter()`，默认超时时间往往只有 **30 秒**。当模型进行多步工具调用或检索重文档生成较长答复时，极易在吐字中途被容器强制超时切断并抛出 `AsyncRequestTimeoutException`。因此在创建时必须显式传入长超时时间，例如 `new SseEmitter(300_000L)`（5 分钟），并配合后端 `RunBudget` 自己的总运行时间上限兜底。

### 2. 等待首字或慢工具期间客户端断开不感知坑
这是工程中最容易被忽略的细节：当浏览器关闭标签页、或者命令行 `curl` 按下 `Ctrl-C` 主动断开 TCP 连接后，如果此时后端正好卡在等待大模型首字（TTFT）或卡在一个耗时 5 秒的慢工具调用里，**Servlet 容器并不会主动触发 `SseEmitter.onCompletion` 回调**！因为没有任何网络写操作去探测到底层 Socket 的 `RST/FIN` 已关闭。结果就是后台线程和慢工具一直空跑到结束，白白浪费模型 token 和线程池资源，同时占死 `RunRegistry` 的同会话并发锁，导致用户重开页面后一直被 409 `SESSION_BUSY` 拒绝。

解决该问题的可靠方案是**轻量心跳探测 + 统一取消信号 `Cancellation`**：
- 为每个活跃的 `SseEmitter` 启动一个后台定时任务（例如通过 `ScheduledExecutorService` 每 5 秒执行一次）；
- 定时任务向连接写入一条符合 SSE 规范的空注释行 `emitter.send(SseEmitter.event().comment("ping"))`（序列化为 `: ping\n\n`，前端浏览器及 SSE 解析器会自动忽略注释行，不污染正常 `delta` 内容）；
- **核心触发链**：一旦客户端已经断开连接，下一次心跳写操作会在 Tomcat 层立即抛出 `IOException`（如 Broken pipe）或 `IllegalStateException`；
- 此时在 `catch` 块里立刻调用 `cancellation.cancel()`，配合 `emitter.onCompletion(cancellation::cancel)`、`onTimeout`、`onError`，做到多路兜底；
- `Cancellation` 内部通过注册的回调与原子布尔标志位，立刻对 `chatExecutor` 任务发出 `Future.cancel(true)`、向 `ToolExecutor` 所有并发工具子线程发送 `interrupt()`、并让上游 HTTP 客户端在读流与循环判断点提前退出，确保无线程残留并立即释放会话锁。

## 三、发送线程并发安全与 `complete()` 幂等

因为正常推流线程（`chatExecutor`）与心跳探测线程（`sseHeartbeatExecutor`）会并发访问同一个 `SseEmitter`，如果两者同时调用 `emitter.send(...)`，在底层缓冲写入时可能发生交错甚至触发容器状态异常。因此每个请求实例必须持有一把专属的局部互斥锁 `Object sendLock = new Object()`，无论是正常发送事件、发送 `: ping\n\n` 心跳，还是最终 `emitter.complete()`，都必须在 `synchronized (sendLock)` 块内完成，并先判断 `cancellation.isCancelled()` 再执行写操作。

## 四、优雅停机（Graceful Shutdown）

当服务端发版重启或收到 `SIGTERM` 信号时，如果进程被立刻强杀，正在进行中的流式回答和尚未提交的数据库落库事务会被腰斩，造成数据库只存了一半对话或出现孤立 `user` 行。在 `application.yml` 中配置 `server.shutdown: graceful` 后，Spring Boot 停机时会先停止接收新 HTTP 请求，并给予活跃的异步请求与线程池一段缓冲时间完成当前收尾与数据库提交；同时在 `@Bean(destroyMethod = "shutdown")` 中有序关闭后台执行器，确保停机过程不产生半截脏数据。

## 五、前端消费 POST SSE 流的要点

HTML5 原生的 `new EventSource(url)` 接口在规范上**只支持 HTTP GET 请求且无法自定义 JSON 请求体或 Header**。而 AI 对话接口需要在 `POST /api/chat` 的请求体里携带用户长提问、`conversationId`、`clientRequestId` 以及知识库 `scope` 参数。因此前端必须使用 `fetch('/api/chat', { method: 'POST', body: ..., signal: controller.signal })`，读取 `response.body.getReader()` 字节流并通过 `TextDecoder` 逐块解码，按 `\n\n` 分割 SSE 事件块，解析 `event:` 与 `data:` 行；同时将浏览器的 `AbortController.abort()` 绑定到前端的「停止生成」按钮上——一旦点击停止，浏览器立即中断底层 HTTP 连接，后端的心跳或流写入随之感知断连并完成全链路取消。
