---
title: Agent 执行预算、分级重试与状态机幂等设计规范（v1 版）
date: 2026-08-10
tags: [agent, reliability]
---

# Agent 执行预算、分级重试与状态机幂等设计规范（v1 版）

> 本文档为 2026 年 8 月初版运行规范，记录 ReAct 循环、外部模型调用与接口幂等的基线参数，作为系统可靠性第一道防线。（注：部分默认阈值已在 2026 年 10 月的配置更新公告中上调或细化。）

在真实的生产环境里，大语言模型（LLM）和外部工具天然具备不确定性：模型可能陷入死循环反复调用同一个参数错误的工具、上游 API 可能随机抖动返回 502/504、前端用户可能在弱网下连续点击两次发送按钮。没有硬边界保护的 Agent 循环一旦失控，会在几分钟内烧光所有接口额度或打满数据库与线程池。

## 一、三维运行预算（`RunBudget`）

每一次用户提问触发的 `AgentLoop` 执行，都必须携带一份独立的运行预算对象 `RunBudget`，从三个维度施加强制上限：

1. **最大推理步数（`maxSteps`）**：在 2026-08 v1 规范中，默认 `max_steps = 6`。每进入一次「调用大模型 → 解析结果或工具」记为消耗 1 步。若模型连续调工具超过 6 步仍未收敛出最终回答，循环必须在第 7 步入口前立刻熔断，抛出可识别的 `BUDGET_EXCEEDED`（HTTP 429）错误码，不允许无限迭代。
2. **总执行墙钟时间（`maxDuration`）**：单次对话全链路默认限时 `120` 秒（`Duration.ofSeconds(120)`）。每次准备调模型或并发调度一批工具前，都计算 `budget.remainingDuration()` 并透传给底层超时控制，保证单个慢工具不会把总耗时拖到几十分钟。
3. **上下文 Token 与调用预算**：通过 `ContextAssembler` 与 `TokenEstimator` 对每次传给大模型的 Prompt 进行严格估算与裁剪——先扣除挂载的工具 schema 占用，再锁定系统提示词，保底当前提问与当前轮工具调用/返回对，再从最近历史轮往回填充，直到接近 `maxPromptTokens` 预算即停止，并在当前轮本身超限时明确告知客户端“超了多少 Token”以及缩减建议。

## 二、异常分类与分级重试策略

系统统一使用受控异常 `Failure(ErrorCode, message)` 表达业务和外部依赖异常，并在每个 `ErrorCode` 枚举中预先写死两个属性：`httpStatus` 与 `retryable`。

- **不可重试错误（`retryable = false`）**：参数空或格式错（`INVALID_ARGUMENT` 400）、上下文超限（`CONTEXT_OVERFLOW` 400）、模型名不存在（`MODEL_NOT_FOUND` 404）、同会话并发冲突（`SESSION_BUSY` 409）、预算用尽（`BUDGET_EXCEEDED` 429）、客户端取消（`CANCELLED` 499）、模型返回全空白文本（`MODEL_EMPTY_RESPONSE` 502）、引用编号越界（`INVALID_CITATION` 502）、数据库读写失败（`STORAGE_ERROR` 500）。这些错误再重试结果一样还是错，必须立刻快速失败并向前端吐出清晰的错误码。
- **可重试错误（`retryable = true`）**：模型或 Embedding 服务返回 5xx 临时故障（`MODEL_UNAVAILABLE` 502）以及连接/响应超时（`TIMEOUT` 504）。
- **重试的黄金铁律——只在吐字前重试**：如果流式响应还没有收到任何有效内容（零增量 `Delta`、零工具调用）时上游返回 500 或超时，`OpenAiCompatibleAdapter` 可以自动再试 1 次；但如果连接**已经向前端吐出了哪怕半句话**，哪怕中途断线也绝对不允许静默重试——否则前端界面会收到前后拼接残缺的乱码。在 v1 规范中，Embedding 批处理默认单批上限设为 `max_batch_size = 32`。

## 三、请求级状态机幂等与同会话并发互斥

为了防止客户端双击或网关超时自动重发导致后端把同一个问题并行跑两次模型、甚至向数据库插进两条重复回答，我们把每个会话抽象为单道互斥状态机（`RunRegistry` + PostgreSQL 部分唯一索引 `chat_message_request_idx`）：

1. **同一会话并发拒绝（409 `SESSION_BUSY`）**：任何时刻同一 `conversationId` 只能有一个请求处于 `RUNNING` 状态。如果上一个请求还在吐字或执行工具，同一会话发来第二条新消息，不排队（避免维护复杂的分布式队列超时与死锁），直接快速拒绝返回 HTTP 409 与 `SESSION_BUSY`。不同 `conversationId` 互不影响，可完全并发执行。
2. **进行中重复 `clientRequestId` 拒绝（409 `SESSION_BUSY`）**：如果同一个 `clientRequestId` 正在运行且客户端又重发了一遍，一样返回 409 防止并发重入。
3. **已完成 `clientRequestId` 持久化 SSE 回放**：在真正抢占运行权与调模型前，服务层优先检查 PostgreSQL `chat_message` 表中是否存在 `role = 'assistant' and request_id = :clientRequestId`。如果已存在（哪怕应用刚重启过、内存 `RunRegistry` 为空），系统绝不再调用大模型，而是直接启动一条本地模拟的 `text/event-stream` 流，依次把已落库的 `content`、`citations` 通过 `event:delta` 与 `event:done` 原样回放给前端。
4. **多步工具落库的唯一索引保护**：当一轮对话包含多次工具调用时，中途产生的 `assistant(tool_calls)` 与 `tool` 结果行必须将 `request_id` 设为 `NULL`，只有整轮最后产出的那条最终答复 `assistant` 行才写入 `clientRequestId`，以此配合 PostgreSQL 的部分唯一索引 `where role = 'assistant' and request_id is not null`，做到强一致性幂等。

## 四、失败后重试不重插用户消息（`retryMessageId`）

当模型上游报错（例如 `MODEL_EMPTY_RESPONSE`）或中途取消导致该轮对话中断时，用户的提问行已经在此前写入 `chat_message` 表，但其后面没有任何 `role = 'assistant' and request_id is not null` 的终态回复。如果前端重试时不加以区分，用户每点一次「重试」就会在聊天窗口里多出一个一模一样的用户气泡。

为此，系统支持前端在失败卡片上点击重试时携带新的 `clientRequestId` 以及原 `retryMessageId`。服务端在 `ConversationService` 事务内查出该消息并校验两件事：第一，它属于当前 `userId` 和当前 `conversationId` 且 `role = 'user'`；第二，它之后尚未出现成功的终态助手回复。校验通过后直接复用原 `user` 消息继续加载上下文重跑大模型，既保留顺序又不产生任何重复记录。
