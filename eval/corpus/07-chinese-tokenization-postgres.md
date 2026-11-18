# PostgreSQL 中文全文检索实战：二元组分词与 `plainto_tsquery` 避坑

把 PostgreSQL 当作知识库底座时，几乎所有人在实现中文关键词检索时都会踩同一个深坑：测试单个英文词（`pgvector`）或短中文词好好的，一换成用户真实输入的自然语言中文长问句（例如「请问在 PostgreSQL 里为什么检索不到中文配置说明」），SQL 竟然稳定返回 **0 条结果**，甚至让开发者怀疑是不是索引坏了或者表里没数据。

## 一、坑 1：PostgreSQL 默认分词器切不开连续中文

PostgreSQL 内置了 `english` 和 `simple` 等全文检索配置（Text Search Configuration）：
- `english` 配置会按空格和标点切词，然后做英文词干提取（例如 `running` → `run`）和英文停用词过滤；
- `simple` 配置按空格与非字母数字符号切分，并转小写。

当一段连续的中文句子「向量检索系统架构」直接交给 `to_tsvector('simple', '向量检索系统架构')` 时，由于中间没有任何空格，PostgreSQL 会把整句这 8 个字当成**唯一一个超长单词 token**：`'向量检索系统架构':1`。结果就是用户只搜「向量检索」四个字时，完全匹配不上！虽然可以通过源码编译安装 `zhparser`、`pg_jieba` 等第三方 C 扩展，但那会破坏原生 `pgvector/pgvector:pg16` 官方镜像的开箱即用性——CI、Docker Compose 与团队成员本地环境全要维护自编译插件，性价比极低。

## 二、解法：应用层 `Tokenizer` 相邻字二元组（Bigram）

与其在数据库层强求中文词典扩展，不如在应用层写一个不到百行、完全可控的纯 Java `Tokenizer` 类，**文档入库与用户查询共用同一个 `Tokenizer`**：

1. **中文部分按相邻字二元组（Bigram）切分**：
   - 对于连续汉字片段，例如 `"向量检索"`，按步长 1 取所有相邻两字组合：`"向量"`、`"量检"`、`"检索"`；
   - 如果汉字片段恰好只有 1 个字（例如中英夹杂时出现的 `"在"`、`"的"`），则单独作为一个 1-gram 保留给后续处理。
2. **英文与数字部分按连续词切分并转小写**：
   - 遇到连续 ASCII 字母或数字，例如 `"PostgreSQL 16"`、`"pgvector"`、`"HNSW"`，完整切出并转为小写：`"postgresql"`、`"16"`、`"pgvector"`、`"hnsw"`。
3. **标点与空白跳过**：逗号、句号、问号、括号与空白只起到切分边界作用，不作为 token 输出。

文档切片入库时，调用 `Tokenizer` 得到去空格拼接的词串写入 `chunks.tokens`（例如 `"向量 量检 检索 postgresql 16"`），并在该列建立 GIN 索引：
```sql
create index if not exists chunks_tokens_gin_idx
    on chunks using gin (to_tsvector('simple', tokens));
```
这样既完全不依赖自定义 C 扩展，又能原生享受 PostgreSQL GIN 倒排索引的毫秒级检索速度。

## 三、坑 2：致命的 `plainto_tsquery` 全词 `AND` 连接

很多团队做对了第一步（写入二元组到 `tokens`），却在查询 SQL 里习惯性地写下：
```sql
-- 错误示范！千万别用 plainto_tsquery
where to_tsvector('simple', tokens) @@ plainto_tsquery('simple', ?)
```
问题在于：`plainto_tsquery` 会把空格分隔的所有输入词**全部用逻辑与 `AND (&)` 连起来**！
看一个实际例子：用户问「**请问在 PostgreSQL 中如何配置向量检索索引**」，`Tokenizer` 切出问句词串为：
`请问 在 postgresql 中 如何 何配 配置 向量 量检 检索 索索 索引`
如果直接塞进 `plainto_tsquery`，PostgreSQL 实际执行的查询条件等价于：
`'请问' & '在' & 'postgresql' & '中' & '如何' & '何配' & '配置' & '向量' & '量检' & '检索' & '索引'`
只要你写的正经技术笔记正文里没有出现“请问”或“何配”这两个闲聊连接字，**整条 `AND` 表达式就立刻判定为 False，返回 0 行！** 长问句越多废话，命中率反而降为精确的 0%。

## 四、最终方案：过滤单字停用词 + `OR (|)` 拼 `to_tsquery` + `ts_rank` 排序

正确的查询侧拼接逻辑非常直白且经过工程验证：
1. 对用户提问使用同一个 `Tokenizer` 切出二元组与英文词列表；
2. 过滤掉常见单字虚词/停用词（如 `"的"`、`"了"`、`"在"`、`"是"`、`"有"`、`"和"`、`"中"`、`"了"`、`"吗"`、`"呢"`、`"啊"` 等）以及可能误触发语法错误的多余特殊符号，并做去重保序；
3. 用 **` | `（OR 运算符）** 把剩余词项拼装成合法的 tsquery 字符串，例如：
   `'请问 | postgresql | 如何 | 何配 | 配置 | 向量 | 量检 | 检索 | 索引'`
4. SQL 执行：
   ```sql
   select c.id, ts_rank(to_tsvector('simple', c.tokens), q) as score
   from chunks c, to_tsquery('simple', ?) q
   where ... and to_tsvector('simple', c.tokens) @@ q
   order by score desc
   limit ?
   ```
虽然 `|` 会放过包含部分闲聊词的候选条件，但 `ts_rank` 会天然根据**词频与匹配词数密度**打分：同时命中 `postgresql`、`向量`、`量检`、`检索`、`索引` 的核心技术段落得分远高于只偶遇“如何”两个字的无关段落，稳居 Top-1。这个看似不起眼的细节直接决定了中文关键词检索路是否真正可用。
