# Joseph.zhou RAG-AI 知识库系统

基于徐庶 RAG 扩展，基于 Spring AI Alibaba + Graph Agent 工作流引擎的企业级 RAG 知识库平台，融合多模态语义理解、分层检索架构、智能意图分类与 MCP 工具调用，为组织提供精准、高效、可追溯的智能问答体验。

---

## 系统架构

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

---

## 核心技术优势

### Graph Agent 工作流引擎

基于 Spring AI Alibaba Graph 构建的可视化工作流引擎，将 RAG 问答过程分解为可观测、可扩展的有向图：

- **9 个专用节点**：QuestionInput → IntentClassify → QueryDecompose / PromptRoute → Retrieval → ContextBuild → LLMGenerate + Escalation + McpToolCall
- **条件分支路由**：根据意图分类结果动态选择执行路径，避免不必要的检索和生成
- **实时 SSE 步骤回显**：每个节点完成时立即推送步骤事件，用户可见「正在思考 → 正在检索 → 正在生成」
- **客户端断开保护**：SSE 取消时设置短路标志，各节点检查后提前返回，避免浪费算力

### 智能意图分类（LLM 结构化输出）

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

### MCP 工具集成

兼容 MCP（Model Context Protocol）标准，支持通过配置文件动态接入外部工具服务：

- **传输方式**：STDIO / HTTP SSE / Streamable HTTP（自动识别）
- **懒加载**：首次使用时创建连接，连接池化管理
- **工具发现**：自动获取 MCP Server 暴露的工具列表，用于意图分类和路由
- **已集成**：高德地图（路线规划、POI 搜索、天气查询）、紫牛本地服务

### 人工客服转接

双模式转接机制，保障用户满意度：

- **主动转接**：用户点击「转人工」按钮，立即接入人工客服
- **被动转接**：意图分类检测到负面情绪（negative），自动触发转人工
- **会话模式管理**：基于 Redis 的会话模式标记，人工服务期间 AI 不干扰，超时自动回 AI

### 多格式文档引擎

支持三大主流办公文档格式，全程无外部脚本依赖：

| 格式 | 技术 | 能力 |
|------|------|------|
| PDF | PDFBox + Tabula | 文本提取 · 表格结构化（Markdown 输出） · 内嵌图片抽取 |
| DOCX | Apache POI XWPF | 段落解析 · 表格还原 · 图片导出 |
| XLSX | Apache POI XSSF | 行列级结构化 · 多 Sheet 独立分块 |

解析失败自动降级至 Tika 备用链路，保障生产环境稳定性。

### 多模态语义理解

图片不再是盲区。文档内嵌图片经自动提取后上传至 OSS，由通义千问 VL 多模态模型生成中文语义描述，向量化存入 Milvus。用户提问时，系统语义匹配相关图片描述并在对话中直接渲染原始图片，实现「所见即所得」的知识查阅。

### Dense + BM25 混合检索

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

### Small-to-Big 分层检索架构

借鉴竞赛级 RAG 系统设计理念，采用「小块索引 + 大页面回答」的双层存储策略：

```
存储层:
  页面全文 → MySQL document_pages（持久化原文）
  页面小块 → TokenTextSplitter 拆分 → Milvus 向量索引（高精度匹配）

检索层:
  用户提问 → HybridSearch(Dense+BM25) → RRF融合 → Milvus 向量召回小块 → MySQL 回表查询完整页面 → LLM 生成回答
```

小块保证检索精度，大页面提供充足上下文，兼顾精准命中与回答完整性。

### 会话记忆与检索域管理

- **级联知识库过滤**：前端知识库与文件两级联动选择，未选时全局检索，选定后精确限定检索域
- **检索指纹检测**：切换知识库或文件时自动清除旧会话记忆，防止上下文错乱
- **多标签页隔离**：sessionId 机制，同一用户多标签页互不干扰
- **有界对话记忆**：内建历史轮次限制机制，防止长对话将 RAG 指令挤出上下文窗口

---

## 技术栈

| 层级 | 技术选型 |
|------|----------|
| 应用框架 | Spring Boot 3.4 + Spring AI 1.1 + Spring AI Alibaba 1.1（Graph Agent） |
| 向量数据库 | Milvus 2.5（BM25 稀疏向量 + Dense 密集向量，RRF 融合） |
| 文本嵌入 | DashScope text-embedding-v4（1024 维） |
| 多模态模型 | 通义千问 VL（qwen-vl-plus） |
| 大语言模型 | 通义千问 |
| 关系数据库 | MySQL 8.0 + MyBatis-Plus |
| 缓存与会话 | Redis（会话模式、检索指纹、记忆隔离） |
| 对象存储 | 阿里云 OSS |
| MCP 协议 | Spring AI MCP Client（STDIO / SSE / Streamable HTTP） |
| 前端 | Vue 3 + Element Plus + marked |

---

## 快速开始

### 数据库初始化



完整建表语句参见 `src/main/resources/sql/init.sql`。

### 配置

```yaml
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
    vectorstore:
      milvus:
        client:
          host: ${MILVUS_HOST}
          port: 19530
        collectionName: vector_store_v2
        embeddingDimension: 1024
        initializeSchema: false  # 由 MilvusSchemaInitializer 创建（含 BM25 sparse_vector）
  datasource:
    url: jdbc:mysql://localhost:3306/ks_rag_new
    username: root
    password: ${MYSQL_PASSWORD}
```

### 启动

```bash
./mvnw spring-boot:run -pl xushu-rag-ai-back
```

---

## API 概览

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/ai/rag-with-kb` | POST | RAG 问答（支持知识库 + 文件过滤，流式输出） |
| `/api/v1/knowledge/file/upload-with-kb` | POST | 文件上传（自动解析、LLM 分类、向量化） |
| `/api/v1/knowledge/latest-docs/{kbId}` | GET | 获取知识库下最新版本文档列表 |
| `/api/v1/knowledge/latest-docs-batch` | GET | 批量查询多知识库文档 |
| `/api/v1/knowledge/contents` | GET | 文件管理查询 |
| `/api/v1/kb/list` | GET | 知识库列表 |
| `/api/v1/kb/create` | POST | 创建知识库 |

---

## 工程结构

```
xushu-rag-ai-back/
├── controller/
│   ├── AiRagController.java              # RAG 问答核心控制器
│   ├── KnowledgeController.java          # 文档上传、解析、管理
│   └── KnowledgeBaseController.java      # 知识库 CRUD
├── service/
│   ├── HybridSearchService.java           # Dense+BM25 混合检索 + RRF 融合
│   ├── MilvusV2InsertService.java         # V2 SDK 文档插入（BM25 function 自动索引）
│   ├── IRerankStrategy.java               # 重排序策略接口
│   ├── DocumentPageService.java           # 页面原文回表查询服务
│   └── impl/
│       ├── VersionFirstRerankStrategy.java
│       ├── Qwen3RerankStrategy.java       # 通义千问 Cross-Encoder 精排
│       └── LLMRerankStrategy.java         # LLM 重排序（降级方案）
├── graph/                                 # Graph Agent 工作流
│   ├── RagGraphAgent.java                 # 工作流构建器
│   └── nodes/                             # 工作流节点（9 个）
├── config/
│   ├── MilvusSchemaInitializer.java       # Collection 自动创建（V2 SDK + chinese 分词器）
│   └── MilvusV2ClientConfig.java          # Milvus V2 客户端 Bean
├── utils/
│   ├── JavaDocumentParser.java            # 多格式文档解析引擎
│   ├── ImageExtractor.java                # 文档图片提取器
│   └── ImageDescriber.java                # 多模态图片语义描述
├── entity/                                # 数据实体
└── mapper/                                # MyBatis 映射
```
