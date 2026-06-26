# Joseph.zhou RAG-AI 知识库系统

在徐庶老师的基础上
基于 Spring AI + Milvus 的企业级 RAG 知识库平台，融合多模态语义理解、分层检索架构与自适应上下文管理，为组织提供精准、高效、可追溯的智能问答体验。

---

## 核心技术优势

### 纯 Java 多格式文档引擎

支持三大主流办公文档格式，全程无外部脚本依赖：

| 格式 | 技术 | 能力 |
|------|------|------|
| PDF | PDFBox + Tabula | 文本提取 · 表格结构化（Markdown 输出） · 内嵌图片抽取 |
| DOCX | Apache POI XWPF | 段落解析 · 表格还原 · 图片导出 |
| XLSX | Apache POI XSSF | 行列级结构化 · 多 Sheet 独立分块 |

解析失败自动降级至 Tika 备用链路，保障生产环境稳定性。

### 多模态语义理解

图片不再是盲区。文档内嵌图片经自动提取后上传至 OSS，由通义千问 VL 多模态模型生成中文语义描述，向量化存入 Milvus。用户提问时，系统语义匹配相关图片描述并在对话中直接渲染原始图片，实现「所见即所得」的知识查阅。

### Small-to-Big 分层检索架构

借鉴竞赛级 RAG 系统设计理念，采用「小块索引 + 大页面回答」的双层存储策略：

```
存储层:
  页面全文 → MySQL document_pages（持久化原文）
  页面小块 → TokenTextSplitter 拆分 → Milvus 向量索引（高精度匹配）

检索层:
  用户提问 → Milvus 向量召回小块 → MySQL 回表查询完整页面 → LLM 生成回答
```

小块保证检索精度，大页面提供充足上下文，兼顾精准命中与回答完整性。

### 智能过滤与上下文管理

- **级联知识库过滤**：前端知识库与文件两级联动选择，未选时全局检索，选定后精确限定检索域
- **有界对话记忆**：内建历史轮次限制机制，防止长对话将 RAG 指令挤出上下文窗口
- **策略模式重排序**：预设 `IRerankStrategy` 扩展接口，当前版本优先策略，后续可无缝接入 LLM 语义重排

---

## 技术栈

| 层级 | 技术选型 |
|------|----------|
| 应用框架 | Spring Boot 3.4 + Spring AI 1.0 |
| 向量数据库 | Milvus |
| 文本嵌入 | DashScope text-embedding-v1（1536 维） |
| 多模态模型 | 通义千问 VL（qwen-vl-plus） |
| 大语言模型 | 通义千问 |
| 关系数据库 | MySQL 8.0 + MyBatis-Plus |
| 对象存储 | 阿里云 OSS |
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
        collectionName: vector_store
        embeddingDimension: 1536
        initializeSchema: true
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
│   ├── IRerankStrategy.java              # 重排序策略接口（Phase 2 预留）
│   ├── DocumentPageService.java          # 页面原文回表查询服务
│   └── impl/
│       ├── VersionFirstRerankStrategy.java
│       └── DocumentPageServiceImpl.java
├── utils/
│   ├── JavaDocumentParser.java           # 多格式文档解析引擎
│   ├── ImageExtractor.java               # 文档图片提取器
│   └── ImageDescriber.java               # 多模态图片语义描述
├── entity/                               # 数据实体
├── mapper/                               # MyBatis 映射
└── config/                               # Spring 配置
```
