---
title: 文本向量化（Embedding）接口规范、分批策略与缓存设计
date: 2026-07-01
tags: [embedding, performance, rag]
---

# 文本向量化（Embedding）接口规范、分批策略与缓存设计

在 RAG（检索增强生成）系统中，Embedding 模型负责把变长的 Markdown 文档片段与用户自然语言问句映射到固定维度的稠密向量空间。虽然业界习惯把所有注意力放在大语言模型（LLM）的选择上，但在真实系统稳定性评估里，Embedding 服务的延迟抖动、接口批大小限制与异常降级策略往往直接决定了整条检索链路是否可用。

## 一、OpenAI 兼容 `/embeddings` 接口契约

LoreAgent 采用标准 OpenAI 兼容 JSON 协议对接文本向量服务（端点为 `{baseUrl}/embeddings`）：
- **请求体**：
  ```json
  {
    "model": "text-embedding-3-small",
    "input": ["片段文本一", "片段文本二"]
  }
  ```
- **响应体**：返回 `data` 数组，每项包含 `index` 与浮点向量 `embedding`，外加可选 `usage.total_tokens`。
- **工程防坑点——响应排序按 `index` 重排**：虽然绝大多数兼容实现会按 `input` 原顺序返回 `data`，但协议允许部分分布式推理网关并发处理同一个 batch 后乱序回传 `data` 元素，只靠每个元素里的 `index` 字段对齐输入位置。因此 `EmbeddingClient` 在解析 JSON 时**不能直接按数组下标遍历 `data`**，而应当分配一个长度等于输入批大小的数组，严格依据 `item.get("index").asInt()` 把向量放回对应槽位，防止切片与向量张冠李戴。

## 二、为什么必须支持自动分批（Batch Splitting）

一篇 3000 字的技术笔记按 250 字切块、保留 40 字重叠，大约会拆出 15～20 个 chunk；如果上传一篇长篇架构手册或连续导入 10 篇文档，单次入库动辄产生上百条文本切片。

主流商用及本地部署 Embedding API 对单次请求的 `input` 数组长度都有严格硬上限：
- OpenAI Cloud / 部分兼容代理限制单批最多 16～100 条或总 token 上限 8192；
- 本地 Ollama / vLLM / TEI 推理节点受显存与 batch padding 限制，常见单批上限为 16 或 32 条。

如果客户端不做分批直接把 120 个 chunk 一次性塞进 `POST /embeddings`，接口会立刻返回 `HTTP 400 Bad Request` 或 `413 Payload Too Large`。因此 `EmbeddingClient` 必须维护 `maxBatchSize` 参数：当上层传入的 `List<String> texts` 长度超过 `maxBatchSize` 时，按固定步长在客户端切片为多个子批，逐批串行或有序发起请求，并在全部批次完成后按原始顺序拼回完整的 `List<float[]>` 返回。

## 三、Embedding 服务故障处理与降级闭环

1. **客户端超时与重试分级**：单批 `/embeddings` 请求设置合理连接超时（如 5 秒）与读超时（如 20 秒）。遇到连接异常或服务端 5xx（如 HTTP 500/502/503/504），可进行有限次短暂重试；若最终仍返回 5xx 或响应体缺向量字段，则统一封装抛出受控异常 `Failure(EMBEDDING_UNAVAILABLE, ...)`。
2. **检索链路降级（不能让 RAG 全挂）**：在在线问答阶段，当 `RetrievalService` 调用 `EmbeddingClient` 失败时，绝不应该直接向用户甩出 HTTP 500。系统应捕获异常、记录结构化告警日志（禁止打印 API Key），置 `degraded = true`，立刻切换到仅依赖 PostgreSQL `tsvector` 的 `KeywordSearch` 路径召回相关文档片段；同时把降级原因透传给 `AnswerService` 与 `/api/retrieval/debug` 调试接口，确保最终回答正文里明确告知用户「向量检索异常，当前已自动降级为关键词检索」。
3. **模型参数参考与历史阈值**：在 2026 年 7 月的早期测试版本中，使用 `text-embedding-3-small`（1536 维）时，内部评测笔记曾建议将最低余弦相似度门限 `minVectorScore` 设为 **`0.35`**；同时指出同一向量库在没有做归一化或混用模型时，不同模型返回的分数尺度完全不可直接横比，这也是所有片段行必须记录 `embedding_model` 与 `dim` 两列元数据的根本原因。

## 四、为何要与关键词检索互补工作

即便 Embedding 接口完全健康，纯语义向量对新造词、内部配置键名、特定 SQL 函数名（如 `plainto_tsquery`、`to_tsvector`）以及带版本号的编号仍可能失灵。将 Embedding 作为稠密语义一路，与本地免外部依赖的 PostgreSQL `Tokenizer` 关键词检索并行运行、再用 RRF 合并，才是兼顾召回率与系统高可用的最小可行架构。
