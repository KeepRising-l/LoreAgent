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

## 快速运行（Phase 1b：流式对话 + 工具调用）

方式一：配置模型环境变量（密钥不入库）：

```bash
export LORE_MODEL_BASE_URL="https://your-openai-compatible-endpoint/v1"
export LORE_MODEL_API_KEY="sk-..."
export LORE_MODEL_NAME="your-model-name"
```

方式二：在项目根目录新建 `application-local.yml`（已在 `.gitignore` 中忽略，不会提交）：

```yaml
lore:
  model:
    api-key: sk-...
```

`application.yml` 会通过 `spring.config.import: optional:file:./application-local.yml` 自动加载它，缺失也不影响启动。

```bash
# 运行单元测试
mvn test

# 打包并启动
mvn package
java -jar loreagent-app/target/loreagent-app-0.1.0-SNAPSHOT.jar

# 验证健康检查
curl http://localhost:8080/health

# 用浏览器打开 http://localhost:8080/ 即可使用临时调试页（SSE 流式吐字 / 停止生成）
```
