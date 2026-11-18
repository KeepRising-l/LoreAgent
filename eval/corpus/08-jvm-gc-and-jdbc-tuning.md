# JDK 17、HikariCP 与 Spring JdbcTemplate 事务调优手记

在构建像 LoreAgent 这样兼顾低延迟流式 SSE 响应与高频 PostgreSQL 读写的应用时，数据库访问层与 JVM 运行时的调优并不需要复杂框架。相反，清晰地理解连接池大小、显式事务边界与批量 SQL 写法，就能避免绝大多数线上卡死与延迟毛刺。

## 一、为什么选择 `JdbcTemplate` 而不是 ORM（JPA / Hibernate）

当系统里既要维护带 `uuid` 外键级联、PostgreSQL 原生 `jsonb`、`text[]` 数组，又要写 `pgvector` 的 `<=>` 余弦距离与 `to_tsvector` / `to_tsquery` GIN 检索时，传统 ORM 的方言抽象往往会变成阻碍：
1. **无隐式延迟加载与 N+1 查询**：每一条查询就是一行可见的 SQL，参数与索引使用情况一目了然，不会出现遍历实体列表时偷偷多发出几十次数据库查询的现象。
2. **原生利用 PostgreSQL 专属特性**：`cast(? as jsonb)`、`cast(? as vector)`、`create_array` / `text[]` 重叠运算符 `&&`、`INSERT ... RETURNING` 单次往返拿回自增 `id` 和 `created_at`，全部都能一行 SQL 直接完成。
3. **领域对象全部不可变**：查询结果通过 `RowMapper` 直接映射为 Java 17 `record`（如 `Document`、`Conversation`、`ChatMessage`），不存在 Hibernate 脏检查代理对象与线程安全隐患。

## 二、同事务原子写入与并发 `seq` 分配

在并发与幂等控制下，数据库写入边界必须精简且具备原子性：
- **先删旧还是先写新？同事务包住全过程**：以同名文档覆盖重传为例，绝对不能「先在事务外删旧切片，再慢慢调 Embedding，最后写新切片」——一旦 Embedding 耗时 3 秒或失败，这 3 秒内文档会呈现“全空切片”状态。正确顺序是：先在内存准备好切片与向量（或降级结果），再打开 `TransactionOperations` 事务，先更新 `document.active_version = active_version + 1`、批量插入带新 `version` 的 `chunks`，最后 `delete from chunks where document_id = ? and version < ?`。由于都在一个事务内提交，外部并发读取永远只看到完整的新版或完整的旧版，绝无中间破损态。
- **先落库再发 SSE `done`**：在对话流输出结束时，先把本轮所有工具中间消息与带 `request_id` 的终态助手消息提交到数据库，再向 `SseEmitter` 发送 `event:done`。一旦 `JdbcTemplate` 抛出 `DataAccessException`，仓储层一律包装为 `Failure(STORAGE_ERROR, ...)` 并触发事务回滚，Controller 向下游发射 `event:error` 而不是静默成功。

## 三、HikariCP 连接池经验公式与泄漏防范

Spring Boot 3 默认内置 HikariCP。很多项目喜欢把 `maximum-pool-size` 随手设成 100 或 200，其实在单机多核容器里反而会导致严重的上下文切换与锁争用。按照 PostgreSQL 官方及 HikariCP 推荐公式：

`connections = ((core_count * 2) + effective_spindle_count)`

对于现代 NVMe SSD 与 4～8 核应用容器，连接池上限设在 **10～20** 之间吞吐量最高。更重要的是，**千万不要在数据库事务块或数据库连接持有期间发起外部大模型 HTTP 调用**！Agent 与 Embedding 的 HTTP 请求耗时可能高达几秒甚至几十秒；必须遵循「查库加载上下文 → 立刻释放连接 → 调 HTTP 流式生成/向量化 → 重新拿连接快速落库提交」的短事务原则，这样即使上百路流式 SSE 同时挂起等待大模型首字，HikariCP 也只需要寥寥几条连接即可轻松应付。

## 四、JDK 17 容器环境垃圾回收器选择

在 JDK 17 运行环境中，针对包含大量短生命周期 JSON 字符串碎片（SSE delta 拼接）和中等数组（float 向量数组）的 RAG 服务：
- 当堆内存分配在 1 GB ～ 4 GB 时，默认 **G1GC**配合 `-XX:MaxGCPauseMillis=100` 即可保持很低的停顿时间与高吞吐；
- 当向量缓存与内存片段较多、堆内存在 4 GB 以上且极度关注流式吐字尾延迟（防止百毫秒级 STW 卡顿影响首字响应）时，可以考虑切换为 **ZGC（`-XX:+UseZGC`）**，其并发标记与重定位将停顿控制在亚毫秒级。配合容器优雅停机与虚拟/守护线程池命名（如 `lore-chat`、`lore-tool`、`lore-sse-heartbeat`），线下排查线程堆栈与连接泄漏会非常清晰。
