# Joseph.zhou RAG-AI 知识库系统

基于 Spring AI Alibaba + Graph Agent 工作流引擎的企业级 RAG 知识库平台，集成 **SuperSQL（Text-to-SQL）** 自然语言查询引擎，融合多模态语义理解、Dense+BM25 混合检索、智能意图分类与 MCP 工具调用，为组织提供精准、高效、可追溯的智能问答与数据分析体验。

---

## 系统架构

### 整体拓扑

```
                    ┌────────────────────────────────────────┐
                    │         前端 (Vue 3 + Element Plus)      │
                    └──────────────────┬─────────────────────┘
                                       │ SSE / REST
                    ┌──────────────────▼─────────────────────┐
                    │     xushu-rag-ai-back (Spring Boot)    │
                    │                                        │
                    │  ┌──────────────────────────────────┐  │
                    │  │    Graph Agent 工作流引擎          │  │
                    │  │  (Spring AI Alibaba Graph)        │  │
                    │  └──────────────────────────────────┘  │
                    │  ┌──────────────────────────────────┐  │
                    │  │    SuperSQL NL2SQL 查询引擎        │  │
                    │  │  (RAG DDL/文档检索 → LLM → SQL)   │  │
                    │  └──────────────────────────────────┘  │
                    │  ┌──────────────────────────────────┐  │
                    │  │    MCP Gateway 工具网关            │  │
                    │  │  (STDIO / SSE / Streamable HTTP)  │  │
                    │  └──────────────────────────────────┘  │
                    └───┬───────┬───────┬───────┬───────────┘
                        │       │       │       │
                ┌───────▼┐ ┌───▼───┐ ┌▼───┐ ┌─▼──────┐
                │ Milvus │ │ MySQL │ │Redis│ │Ali OSS │
                │ 向量库  │ │关系库  │ │缓存 │ │对象存储 │
                └────────┘ └───────┘ └────┘ └────────┘
```

### RAG 问答工作流

```
用户提问
  │
  ▼
Graph Agent 工作流引擎（Spring AI Alibaba Graph）
  │
  ├── 意图分类节点（LLM 结构化输出）
  │     ├── 情绪检测（positive / neutral / negative）
  │     └── 工具确认检测（operation 类意图）
  │
  ├── 条件路由（分类结果）
  │     ├── 负面情绪 / 转人工 → 人工客服接入
  │     ├── 操作类 + 已确认 → MCP 工具调用
  │     ├── 对比/汇总类 → 查询拆解 → 并行检索
  │     └── 其他 → Dense+BM25 混合检索 → 上下文构建 → LLM 生成
  │
  ▼
SSE 流式输出（实时步骤回显 + 答案流式返回）
```

### Text-to-SQL 推理流程

```
用户自然语言问题 ("本月销售额TOP10产品")
  │
  ▼
SuperSQL: AbstractSqlEngine.generateSql()
  │
  ├── 向量检索（语义匹配）
  │     ├── DDL 检索 → "CREATE TABLE orders (id INT, product_name VARCHAR, amount DECIMAL...)"
  │     ├── 文档检索 → "orders表字段: product_name商品名, amount金额, created_at创建时间..."
  │     └── SQL 示例 → "历史问答: 上月销量最高? → SELECT ... FROM orders WHERE ..."
  │
  ├── Prompt 拼接（###Tables + ###Additional Context + ###SQL Examples）
  │
  ├── LLM 生成 SQL
  │     ├── 普通路径 → 直接返回可执行 SQL
  │     └── 中间路径 → 生成 intermediate_sql 探查枚举值 → 二次推理输出最终 SQL
  │
  ▼
执行 SQL → 结果返回
```

---

## 核心特性

### 1. Graph Agent 工作流引擎

基于 Spring AI Alibaba Graph 构建的可视化工作流引擎，将 RAG 问答过程分解为可观测、可扩展的有向图：

- **10 个专用节点**：QuestionInput → IntentClassify → QueryDecompose / PromptRoute → Retrieval → ContextBuild → LLMGenerate + Escalation + McpToolCall + HybridSearchTest
- **条件分支路由**：根据意图分类结果动态选择执行路径，避免不必要的检索和生成
- **实时 SSE 步骤回显**：每个节点完成时立即推送步骤事件，用户可见「正在思考 → 正在检索 → 正在生成」
- **客户端断开保护**：SSE 取消时设置短路标志，各节点检查后提前返回，避免浪费算力

### 2. 智能意图分类（LLM 结构化输出）

使用 BeanOutputConverter 实现 LLM 结构化输出，分类结果直接反序列化为 Java 对象：

| 分类 | 说明 | 处理路径 |
|------|------|----------|
| `reference` | 资料查阅（定义、规定、流程查询） | RAG 检索 + LLM 生成 |
| `calculation` | 计算类（工资、补偿、天数等数值计算） | RAG 检索 + LLM 生成 |
| `operation` | 操作类（请假、查询信息、合同签署等） | 意图确认 → MCP 工具调用 |
| `comparison` / `aggregation` | 对比/汇总类 | 查询拆解 → 多路检索 → 汇总生成 |
| `chitchat` | 闲聊（问候、感谢） | 直接 LLM 回复 |
| `escalation` | 转人工 | 接入人工客服 |

**情绪检测**：同步检测用户情绪，负面情绪自动触发转人工流程。

### 3. SuperSQL：RAG 驱动的 Text-to-SQL

独立开发的 Java Text-to-SQL 框架，基于"检索增强生成"理念，通过向量化存储表结构元数据实现自然语言到 SQL 的智能转换：

- **表结构自动采集**：启动时自动执行 `INFORMATION_SCHEMA.COLUMNS` 抓取全库字段，通过 `TrainingPlanGenerator` 按 database → schema → table 分组生成文档描述，向量化存入 Milvus
- **三层知识检索**：同时检索 DDL（建表语句）、文档（字段说明）、历史 SQL（问答示例），覆盖完整表语义
- **中间 SQL 探查**：LLM 不确定某列枚举值时，自动生成 `intermediate_sql` 执行探查，将结果再注入 prompt 二次推理
- **Few-shot 训练**：支持手动添加 SQL 问答示例，提升生成准确度
- **DDL/文档/文档转DDL 多模式训练**：支持直接录入建表语句、表字段文档，甚至从业务文档自动生成 DDL
- **重排序（Rerank）**：可选开启 RerankModel 对检索结果精排，提升相关性

详见下方 [SuperSQL 模块](#supersql-text-to-sql-框架) 专章。

### 4. MCP 工具集成与智能建议

#### 工具调用

兼容 MCP（Model Context Protocol）标准，支持通过配置文件动态接入外部工具服务：

- **传输方式**：STDIO / HTTP SSE / Streamable HTTP（自动识别）
- **懒加载**：首次使用时创建连接，连接池化管理
- **工具发现**：自动获取 MCP Server 暴露的工具列表，用于意图分类和路由
- **已集成**：高德地图（路线规划、POI 搜索、天气查询）、紫牛本地服务

#### 工具智能建议

检索完成后自动注入 MCP 工具推荐（≤100 token），帮助用户发现"我还能做什么"：

- 基于检索结果语义，LLM 判断当前场景下最有用的工具
- 返回工具名 + 调用示例，前端以按钮形式展示，用户一键触发
- 可通过 `mcp.tools.suggest.enabled` 开关控制

### 5. 人工客服转接

双模式转接机制，保障用户满意度：

- **主动转接**：用户点击「转人工」按钮，立即接入人工客服
- **被动转接**：意图分类检测到负面情绪（negative），自动触发转人工
- **会话模式管理**：基于 Redis 的会话模式标记，人工服务期间 AI 不干扰，超时自动回 AI

### 6. 多格式文档引擎

支持三大主流办公文档格式，全程无外部脚本依赖：

| 格式 | 技术 | 能力 |
|------|------|------|
| PDF | PDFBox + Tabula | 文本提取 · 表格结构化（Markdown 输出） · 内嵌图片抽取 |
| DOCX | Apache POI XWPF | 段落解析 · 表格还原 · 图片导出 |
| XLSX | Apache POI XSSF | 行列级结构化 · 多 Sheet 独立分块 |

解析失败自动降级至 Tika 备用链路，保障生产环境稳定性。

### 7. 多模态语义理解

图片不再是盲区。文档内嵌图片经自动提取后上传至 OSS，由通义千问 VL 多模态模型生成中文语义描述，向量化存入 Milvus。用户提问时，系统语义匹配相关图片描述并在对话中直接渲染原始图片，实现「所见即所得」的知识查阅。

### 8. Dense + BM25 混合检索

企业级双路召回策略，兼顾语义理解与关键词精准匹配：

```
                          ┌── Dense路径: Embedding 向量 → Milvus ANN 检索
用户提问 ──→ HybridSearch ──┤                                     RRF 融合 → 排序结果
                          └── BM25路径: EmbeddedText → sparse_vector 稀疏检索
```

- **Dense（语义）**：`text-embedding-v4` 1024 维向量，Spring AI `VectorStore` 驱动
- **BM25（关键词）**：Milvus V2 SDK `EmbeddedText` + BM25 function，`chinese` 分词器
- **文档自动索引**：`MilvusV2InsertService` 仅传入 `content/metadata/embedding`，`sparse_vector` 由服务端 BM25 function 自动生成
- **降级机制**：BM25 单次失败不影响后续请求，自动回退纯 Dense

### 9. Small-to-Big 分层检索架构

借鉴竞赛级 RAG 系统设计理念，采用「小块索引 + 大页面回答」的双层存储策略：

```
存储层:
  页面全文 → MySQL document_pages（持久化原文）
  页面小块 → TokenTextSplitter 拆分 → Milvus 向量索引（高精度匹配）

检索层:
  用户提问 → HybridSearch(Dense+BM25) → RRF融合 → Milvus 向量召回小块 → MySQL 回表查询完整页面 → LLM 生成回答
```

小块保证检索精度，大页面提供充足上下文，兼顾精准命中与回答完整性。

### 10. RAG 质量评估体系

内置 RAG 评估管理能力：

- 通过 `/api/v1/admin/*` 端点管理评估任务
- 支持对问答结果进行质量打分与分析

### 11. 答案策略模式

基于策略模式的答案生成架构，根据意图分类自适应选择合适的回答策略：

- `RetrievalAnswerStrategy` — 检索式回答（默认）
- `CalculationAnswerStrategy` — 计算类回答
- `ConversationalAnswerStrategy` — 对话式回答（闲聊）
- `PlanningAnswerStrategy` — 规划类回答（拆解+汇总）

### 12. 会话记忆与检索域管理

- **级联知识库过滤**：前端知识库与文件两级联动选择，未选时全局检索，选定后精确限定检索域
- **检索指纹检测**：切换知识库或文件时自动清除旧会话记忆，防止上下文错乱
- **多标签页隔离**：sessionId 机制，同一用户多标签页互不干扰
- **有界对话记忆**：内建历史轮次限制机制，防止长对话将 RAG 指令挤出上下文窗口

### 13. 辅助功能模块

- **提示词模板管理**：CRUD 管理不同场景的系统提示词，前端可按名称选择
- **敏感词过滤**：支持敏感词及分类管理，问答中自动过滤
- **热词统计**：统计提问频次，辅助运营分析
- **定时任务**：`@EnableScheduling` 支持定时数据清理、统计等后台任务

---

## SuperSQL（Text-to-SQL 框架）

### 模块清单

SuperSQL 是一个独立的 Java NL2SQL 框架，位于 `SuperSQL/` 目录下，包含 7 个子模块：

```
SuperSQL/
├── super-sql-core               # 核心引擎：SQL生成、RAG检索、向量存储、重排序
├── super-sql-spring-boot-starter # Spring Boot 自动配置（含初始化训练）
├── super-sql-mybatis-plus       # MyBatis-Plus 集成（SQL执行映射器）
├── super-sql-console            # 演示控制台（REST API）
├── super-sql-mcp/               # MCP 协议支持
│   ├── super-sql-mcp-client     # MCP Client
│   ├── super-sql-mcp-stdio      # MCP Server (STDIO传输)
│   └── super-sql-mcp-webmvc     # MCP Server (WebMVC传输)
```

### 核心机制：AI 如何"读表"

SuperSQL **不直接连接数据库读取 Schema**，而是采用 RAG 思路：

```
训练阶段（表信息入库）
  │
  ├─ 启动初始化: SELECT * FROM INFORMATION_SCHEMA.COLUMNS
  │     → TrainingPlanGenerator 按 database.schema.table 分组
  │     → 生成字段文档: "xx表在xx数据库中, 字段: id INT, name VARCHAR..."
  │     → 向量化存入 Milvus (标签: DOCUMENTATION)
  │
  ├─ 手动训练 DDL: train("CREATE TABLE orders(id INT, ...)")
  │     → 向量化存入 Milvus (标签: DDL)
  │
  └─ 手动训练 SQL示例: train(question="上月销量TOP10", sql="SELECT ...")
        → 向量化存入 Milvus (标签: SQL)

推理阶段（自然语言 → SQL）
  │
  ├─ 用户问题 → 向量检索 (同时检索 DDL + DOCUMENTATION + SQL 三种标签)
  │
  ├─ 检索结果拼接 Prompt:
  │     ###Tables          [DDL 建表语句...]
  │     ###Additional Context [字段说明文档...]
  │     ###SQL Examples    [历史问答对: Q→SQL...]
  │     ###Response Guidelines [...]
  │
  ├─ LLM 推理:
  │     ├─ 上下文充足 → 直接输出 SQL
  │     ├─ 需要某一列的具体值 → 生成 intermediate_sql 探查 → 二次推理
  │     └─ 上下文不足 → 说明原因
  │
  └─ SQL 提取 → 校验 → 返回
```

### 自动配置

```yaml
super-sql:
  init-train: true       # 启动时自动从 INFORMATION_SCHEMA.COLUMNS 采集表信息
  scope: ALONE           # ALL=整库, ALONE=指定库
  schemas:
    - schema: xushu_rag  # 限定训练的数据库
```

---
### MCP Server 知识库服务

将知识库能力封装为 MCP Server，供外部 AI Agent 直接调用，实现「企业内部 Agent 注入知识库」的架构。

#### 暴露的工具

| 工具 | 说明 |
|------|------|
| `ask_knowledge` | 全流程 RAG 问答：意图分类 → 混合检索 → 上下文构建 → LLM 生成，返回自然语言回答 |
| `list_knowledge_bases` | 列出所有知识库（含文档数量），支持分页 |
| `upload_kb_file` | 通过 URL 上传文件到知识库，复用解析→切片→向量化全链路 |
| `get_upload_status` | 查询异步上传任务进度 |

#### 认证鉴权

- MCP Server 端点和 REST 上传接口共用 `X-MCP-Secret-Key` 鉴权
- `mcp_secret_key` 表支持多 key 共存，实现无感密钥轮换
- 所有 MCP 调用异步写入 `mcp_call_log` 审计日志表
- 页面 JWT 鉴权与 MCP 鉴权互不干扰，独立通道

#### 接入方式

在 Agent 客户端的 `mcp.json` 中配置：

```json
{
  "mcpServers": {
    "xushu-rag-kb": {
      "url": "http://{host}:8989/mcp",
      "type": "sse",
      "headers": {
        "X-MCP-Secret-Key": "{your_secret_key}"
      }
    }
  }
}
```

配合 `ziniu-rag` Skill 使用，实现企业微信文件自动入库、知识库智能问答。

---
## 技术栈

| 层级 | 技术选型 |
|------|----------|
| 应用框架 | Spring Boot 3.4 + Spring AI 1.1 + Spring AI Alibaba 1.1（Graph Agent） |
| Text-to-SQL | SuperSQL（自研 RAG-NL2SQL 框架） |
| 向量数据库 | Milvus 2.5（BM25 稀疏向量 + Dense 密集向量，RRF 融合） |
| 文本嵌入 | DashScope text-embedding-v4（1024 维） |
| 多模态模型 | 通义千问 VL（qwen-vl-plus） |
| 大语言模型 | 通义千问（qwen3-32b） |
| 关系数据库 | MySQL 8.0 + MyBatis-Plus |
| 缓存与会话 | Redis（会话模式、检索指纹、记忆隔离） |
| 对象存储 | 阿里云 OSS |
| MCP 协议 | Spring AI MCP Client + Self MCP Server（STDIO / SSE / Streamable HTTP） |
| 文档解析 | PDFBox + Tabula + Apache POI（XWPF/XSSF）+ Apache Tika |
| 前端 | Vue 3 + Element Plus + marked |

---

## 快速开始

### 前置要求

- JDK 17+
- MySQL 8.0+
- Milvus 2.5+
- Redis
- 阿里云 OSS（可选，用于图片存储）
- DashScope API Key

### 数据库初始化

完整建表语句参见 `src/main/resources/sql/init.sql`。

### 配置

```yaml
server:
  port: 8989

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen3-32b
      embedding:
        options:
          model: text-embedding-v4
      rerank:
        enabled: true
        model: qwen3-rerank
        base-url: https://dashscope.aliyuncs.com/compatible-api/v1/reranks
    vectorstore:
      milvus:
        client:
          host: ${MILVUS_HOST:localhost}
          port: 19530
        databaseName: "default"
        collectionName: "vector_store_v2"
        embeddingDimension: 1024
        indexType: IVF_FLAT
        metricType: COSINE
        initializeSchema: false  # 由 MilvusSchemaInitializer 创建（含 sparse_vector）
  datasource:
    url: jdbc:mysql://localhost:3306/ks_rag_new?serverTimezone=UTC
    username: root
    password: ${MYSQL_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
      database: 10

aliyun:
  alioss:
    endpoint: oss-cn-beijing.aliyuncs.com
    access-key-id: ${OSS_ACCESS_KEY}
    access-key-secret: ${OSS_SECRET_KEY}
    bucket-name: joseph-ai

# SuperSQL 配置
super-sql:
  init-train: false       # 生产环境建议 false，手动训练
  scope: ALONE            # ALL / ALONE
  schemas:
    - schema: xushu_rag

# 检索配置
retrieval:
  image:
    max-count: 3          # 图片独立Rerank后保留的最大图片数

# MCP 配置
mcp:
  gateway:
    secret-key: ${MCP_SECRET_KEY:default-mcp-key}
  tools:
    suggest:
      enabled: true       # 检索后是否注入工具推荐
```

### 启动

```bash
# 编译整个项目
mvn clean install -DskipTests

# 启动主应用
mvn spring-boot:run -pl xushu-rag-ai-back
```

---

## API 概览

### RAG 问答

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/chat/rag-graph` | POST | Graph Agent 工作流问答（SSE 流式输出，支持步骤回显） |
| `/api/v1/ai/rag-with-kb` | POST | RAG 问答（支持知识库 + 文件过滤，流式输出） |
| `/api/v1/ai/rag` | POST | RAG 问答（旧版，向前兼容） |
| `/api/v1/chat/stream` | POST | 基础对话流式输出 |

### 知识库管理

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/kb/list` | GET | 知识库列表 |
| `/api/v1/kb/create` | POST | 创建知识库 |
| `/api/v1/knowledge/file/upload-with-kb` | POST | 文件上传（自动解析、LLM 分类、向量化） |
| `/api/v1/knowledge/latest-docs/{kbId}` | GET | 获取知识库下最新版本文档列表 |
| `/api/v1/knowledge/latest-docs-batch` | GET | 批量查询多知识库文档 |
| `/api/v1/knowledge/contents` | GET | 文件管理查询 |

### MCP 网关

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/mcp/*` | — | MCP 工具网关相关端点 |

### 提示词模板

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/template/*` | — | 提示词模板 CRUD |

### 敏感词管理

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/sensitive/*` | — | 敏感词 CRUD |
| `/api/v1/category/*` | — | 敏感词分类管理 |

### 评估与统计

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/admin/*` | — | RAG 质量评估管理 |
| `/api/v1/frequency/*` | GET | 热词统计 |
| `/api/v1/log/*` | GET | 日志查询 |

### 用户与工具

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/user/*` | — | 用户管理 |
| `/api/v1/draw/image` | POST | 图片生成 |

### Text-to-SQL（通过 SuperSqlIntegrationService）

Text-to-SQL 能力通过 `SuperSqlIntegrationService` 注入到 RAG 工作流中，作为内部服务使用。同时 SuperSQL 的 console 模块提供独立 API：

| 端点 | 方法 | 说明 |
|------|------|------|
| `/superSql/train` | POST | 训练 DDL / SQL 示例 / 文档 |

---

## 工程结构

```
xushu-rag-ai/
├── xushu-rag-ai-back/                  # 主应用模块
│   └── src/main/java/com/xushu/rag/
│       ├── XushuRagAiApplication.java  # @SpringBootApplication 启动类
│       ├── advisor/                     # 对话记忆增强器（PromptChatMemoryAdvisor）
│       ├── annotation/                  # 自定义注解
│       ├── aop/                         # AOP 切面
│       ├── classifier/                  # LLM 意图分类器（结构化输出）
│       ├── config/                      # 配置层（9个配置类）
│       │   ├── MilvusSchemaInitializer.java  # Collection创建（含BM25 sparse_vector）
│       │   ├── MilvusV2ClientConfig.java     # Milvus V2 客户端
│       │   ├── GraphCallbackExecutorConfig.java # Graph SSE回调线程池
│       │   ├── AliOssProperties.java         # OSS属性配置
│       │   └── ...
│       ├── controller/                  # 控制器层（14个）
│       │   ├── GraphChatController.java      # Graph Agent 工作流入口
│       │   ├── AiRagController.java          # RAG问答核心
│       │   ├── ChatController.java           # 基础对话
│       │   ├── KnowledgeController.java      # 文档上传/解析/管理
│       │   ├── KnowledgeBaseController.java  # 知识库CRUD
│       │   ├── McpGatewayController.java     # MCP网关
│       │   ├── EvaluationController.java     # RAG质量评估
│       │   ├── PromptTemplateController.java # 提示词模板管理
│       │   ├── SensitiveWordController.java  # 敏感词管理
│       │   └── ...
│       ├── graph/                       # Graph Agent 工作流引擎
│       │   ├── RagGraphAgent.java            # 工作流构建器（@Service）
│       │   ├── SseFormatter.java             # SSE事件格式化
│       │   ├── StateKeys.java                # 状态Key常量
│       │   └── nodes/                        # 工作流节点（10个）
│       │       ├── QuestionInputNode.java    # 问题输入
│       │       ├── IntentClassifyNode.java   # 意图分类（LLM结构化输出）
│       │       ├── PromptRouteNode.java      # 提示词路由
│       │       ├── RetrievalNode.java        # Dense+BM25混合检索
│       │       ├── ContextBuildNode.java     # 上下文构建
│       │       ├── LLMGenerateNode.java      # LLM生成
│       │       ├── QueryDecomposeNode.java   # 查询拆解
│       │       ├── EscalationNode.java       # 转人工
│       │       ├── McpToolCallNode.java      # MCP工具调用
│       │       └── HybridSearchTestNode.java # BM25兼容性测试
│       ├── service/                     # 服务层（28个接口/实现）
│       │   ├── HybridSearchService.java      # Dense+BM25混合检索 + RRF融合
│       │   ├── MilvusV2InsertService.java    # V2 SDK文档插入（BM25 function）
│       │   ├── DocumentPageService.java      # 页面原文回表查询
│       │   ├── SuperSqlIntegrationService.java # SuperSQL集成（Text-to-SQL + 训练）
│       │   ├── McpClientManager.java         # MCP客户端生命周期管理
│       │   ├── IRerankStrategy.java          # 重排序策略接口
│       │   ├── AliOssFileService.java        # OSS文件服务
│       │   ├── PromptTemplateService.java    # 提示词模板服务
│       │   ├── SensitiveWordService.java     # 敏感词服务
│       │   └── impl/                         # 实现类（13个）
│       │       ├── Qwen3RerankStrategy.java       # 通义千问Cross-Encoder精排
│       │       ├── LLMRerankStrategy.java         # LLM重排序（降级）
│       │       ├── VersionFirstRerankStrategy.java # 版本优先策略
│       │       └── ...
│       ├── strategy/                    # 答案策略模式
│       │   ├── AnswerGenerationStrategy.java      # 策略接口
│       │   ├── AnswerStrategyRegistry.java        # 策略注册表
│       │   ├── RetrievalAnswerStrategy.java       # 检索式回答
│       │   ├── CalculationAnswerStrategy.java     # 计算类回答
│       │   ├── ConversationalAnswerStrategy.java  # 对话式回答
│       │   └── PlanningAnswerStrategy.java        # 规划类回答
│       ├── tools/
│       │   └── RagTool.java            # RAG工具（可被MCP暴露）
│       ├── structured/                  # LLM结构化输出定义
│       ├── scheduled/                   # 定时任务
│       ├── entity/                      # 数据实体（13个）
│       ├── mapper/                      # MyBatis映射（13个）
│       └── utils/                       # 工具类（文档解析、图片提取等）
│
├── SuperSQL/                            # Text-to-SQL 独立框架
│   ├── super-sql-core/                  # 核心引擎
│   │   └── src/main/java/com/aispace/supersql/
│   │       ├── engine/
│   │       │   ├── SqlEngine.java            # SQL引擎接口
│   │       │   ├── AbstractSqlEngine.java    # 抽象引擎（核心generateSql逻辑）
│   │       │   ├── SpringSqlEngine.java      # Spring实现
│   │       │   ├── RagEngine.java            # RAG引擎接口
│   │       │   ├── SpringRagEngine.java      # RAG实现（DDL/SQL/文档训练）
│   │       │   └── TrainingPlanGenerator.java # 训练计划生成器
│   │       ├── prompt/
│   │       │   └── SqlAssistantPrompt.java   # SQL生成Prompt模板
│   │       ├── builder/
│   │       │   ├── SqlpromptBuilder.java     # Prompt构建器
│   │       │   ├── RagOptions.java           # 检索参数
│   │       │   └── TrainBuilder.java         # 训练参数构建器
│   │       ├── vector/
│   │       │   ├── BaseVectorStore.java      # 向量存储接口
│   │       │   └── SpringVectorStore.java    # Spring实现
│   │       ├── rerank/
│   │       │   ├── RerankModel.java          # 重排序接口
│   │       │   └── DefaultRerankModel.java   # 默认实现
│   │       ├── util/
│   │       │   └── SqlExtractorUtils.java    # SQL提取工具
│   │       └── service/
│   │           └── IExecuteSqlService.java   # SQL执行服务接口
│   ├── super-sql-spring-boot-starter/  # 自动配置 + 初始化训练
│   │   └── configure/
│   │       ├── SuperSqlAutoConfiguration.java  # 自动装配（含INFORMATION_SCHEMA采集）
│   │       └── SuperSQLProperties.java         # 配置属性类
│   ├── super-sql-mybatis-plus/         # MyBatis-Plus SQL执行
│   ├── super-sql-console/              # 演示控制台
│   └── super-sql-mcp/                  # MCP协议封装
│       ├── super-sql-mcp-client/       # MCP Client
│       ├── super-sql-mcp-stdio/        # MCP Server (STDIO)
│       └── super-sql-mcp-webmvc/       # MCP Server (WebMVC)
│
└── openspec/                            # OpenSpec 需求规格文档
```

---

## 项目特点总结

| 维度 | 能力 |
|------|------|
| **知识问答** | Graph Agent 工作流 + Dense+BM25 混合检索 + Small-to-Big 分层架构 |
| **数据分析** | SuperSQL RAG 驱动的 Text-to-SQL，支持多表关联、中间SQL探查 |
| **工具调用** | MCP 标准协议，支持 STDIO/SSE/Streamable HTTP，懒加载连接池 |
| **多模态** | 文档图片自动抽取 + VL 模型语义描述 + 图片检索渲染 |
| **文档解析** | PDF/DOCX/XLSX 原生解析，表格结构化，Tika 降级兜底 |
| **情绪感知** | LLM 情绪检测，负面自动转人工，正面/中性继续服务 |
| **会话管理** | 检索指纹、知识库级联过滤、多标签页隔离、有界记忆 |
| **提示词工程** | 模板化管理、按场景路由、结构化输出（BeanOutputConverter） |
| **质量保障** | 重排序（Rerank）、评估体系、敏感词过滤 |
| **运营支撑** | 热词统计、日志查询、定时任务 |
| **可扩展性** | 策略模式答案生成、Graph Agent 节点可插拔、MCP 工具热接入 |
