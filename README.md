# LoreAgent

> A minimal, readable RAG agent in Java.

## 模块结构

- `loreagent-spi`：核心 SPI 与不可变消息/事件模型（零 Spring 依赖）
- `loreagent-core`：Agent 循环、上下文预算、取消传播与模型/检索内核（不依赖 Spring）
- `loreagent-app`：Spring Boot Web 入口、持久化与业务装配

## 环境要求

- JDK 17+
- Maven 3.9+
- PostgreSQL 16 + pgvector（Phase 1.5 起需要，可用本地安装或 `docker compose up -d`）
- RabbitMQ（仅 Phase 7 异步入库需要，`docker compose --profile mq up -d`）

## 快速运行（Phase 2：RAG 基础 + 多会话持久化 + 流式工具对话）

方式一：配置模型与 Embedding 环境变量（密钥不入库）：

```bash
export LORE_MODEL_BASE_URL="https://your-openai-compatible-endpoint/v1"
export LORE_MODEL_API_KEY="sk-..."
export LORE_MODEL_NAME="your-model-name"

# Phase 2 向量化配置（不配置密钥或接口不可用时自动降级为中文关键词检索）
export LORE_EMBEDDING_BASE_URL="https://api.openai.com/v1"
export LORE_EMBEDDING_API_KEY="sk-..."
export LORE_EMBEDDING_MODEL="text-embedding-3-small"
export LORE_EMBEDDING_MAX_BATCH_SIZE=16

# PostgreSQL 16 + pgvector
export LORE_DB_URL="jdbc:postgresql://localhost:5432/loreagent"
export LORE_DB_USER="postgres"
export LORE_DB_PASSWORD="your-password"
```

方式二：在项目根目录新建 `application-local.yml`（已在 `.gitignore` 中忽略，不会提交）：

```yaml
lore:
  model:
    api-key: sk-...
  embedding:
    api-key: sk-...
```

`application.yml` 会通过 `spring.config.import: optional:file:./application-local.yml` 自动加载它，缺失也不影响启动。

```bash
# 运行纯单元测试（不依赖 PostgreSQL）
mvn test

# 运行含真实 PostgreSQL 16 + pgvector 的集成测试
LORE_DB_URL=jdbc:postgresql://localhost:5432/loreagent_test LORE_DB_USER=postgres LORE_DB_PASSWORD=... mvn verify -Pit

# 打包并启动
mvn package
java -jar loreagent-app/target/loreagent-app-0.1.0-SNAPSHOT.jar

# 验证健康检查
curl http://localhost:8080/health

# 浏览器打开 http://localhost:8080/ 使用阶段调试页：
# 支持会话持久化、Markdown 上传/覆盖/删除、作用域筛选、/api/retrieval/debug 调试检索与 [K1] 引用展示
```

## 评测语料目录（`eval/`）

- `eval/corpus/`：8 篇独立技术笔记（每篇 2000+ 字，总切片 100+，含日期与参数冲突样本文档留作 Phase 4 测试）。
- `eval/cases.json`：前 5 条检索与拒答基线评测用例。
