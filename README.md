# Eon RAG

轻量级生产级 RAG 知识库：**结构化解析 → 父子两段式分块 → 三路混合检索 → 重排上卷 → 流式生成**。

后端为 Spring Boot 3.5 + Spring AI 1.1 的单体服务，数据库只用**单实例 PostgreSQL 16**（`pgvector` + `zhparser` + `pg_trgm`），不引入独立向量数据库、全文检索引擎或自建 OCR 服务；前端为 Vue 3 + Vite 的单页应用。

---

## 目录

- [1. 项目简介与核心特性](#1-项目简介与核心特性)
- [2. 系统架构与数据流](#2-系统架构与数据流)
- [3. 技术栈与运行环境要求](#3-技术栈与运行环境要求)
- [4. 目录结构说明](#4-目录结构说明)
- [5. 安装配置与本地启动](#5-安装配置与本地启动)
- [6. 配置项参考](#6-配置项参考)
- [7. 接口调用示例](#7-接口调用示例)
- [8. 界面使用说明](#8-界面使用说明)
- [9. 测试与构建](#9-测试与构建)
- [10. 常见问题与注意事项](#10-常见问题与注意事项)

---

## 1. 项目简介与核心特性

### 1.1 项目定位

面向个人开发者与轻量团队的**单机容器化部署**方案：模型凭证与数据库密码全部通过环境变量注入，缺失即启动失败（Fail-Fast），不提供硬编码默认值。

架构设计文档见 [`docs/轻量级生产级 RAG 知识库架构实践：结构化解析、父子分块与三路混合检索.md`](docs/轻量级生产级%20RAG%20知识库架构实践：结构化解析、父子分块与三路混合检索.md)。

### 1.2 核心特性

| 特性 | 实现说明 |
| :--- | :--- |
| **多源结构化解析归一** | PDF / Word / Excel / PPT / HTML / Markdown / TXT 统一解析为有序 `Block` 序列（含类型、标题层级、页码、表名、片序等定位元数据），下游分块层脱离对原始格式的感知 |
| **本地 / 云端双解析路由** | 本地走 POI / PDFBox / Tika / jsoup / flexmark；可选启用 LlamaParse 云端解析，按格式白名单分流，云端失败自动回退本地 |
| **父子两段式分块** | 子块（默认 ≤ 320 Token）只建索引与向量，负责细粒度召回；父块（默认 ≤ 1200 Token）按章节/表格/幻灯片聚合，负责生成上下文完整性 |
| **三路混合召回** | 向量余弦（HNSW）+ zhparser 中文全文（GIN）+ pg_trgm 模糊（GIN）三路并行，单路失败仅降级不整体失败 |
| **RRF 融合 + 云端 Rerank** | 倒数秩融合消除三路分数量纲差异（Top 50），再经百炼 `qwen3-rerank` 重排（Top 15），重排失败自动降级为 RRF 前 15 |
| **父块上卷与动态预算裁剪** | 命中子块按父块归并打分 `max(score) + α×(命中数-1)`，按 Token 预算依次装入；单个父块超预算时降级为「命中子块 ± 邻接子块」局部窗口 |
| **手动向量化（human-in-the-loop）** | 上传只做解析与分块（`CHUNKED`），用户检查/编辑切片后手动触发向量化（`COMPLETED`），避免为不满意的切片付费 |
| **切片在线编辑** | 父块编辑/删除、子块编辑/删除/连续合并/光标拆分；`COMPLETED` 文档编辑后自动重嵌受影响子块 |
| **SSE 流式问答 + 两级引用** | 事件序列 `citations → message → done`（异常 `error`），引用可回指父块原文与命中的具体子块 |
| **知识库分类** | 文档与知识库多对多绑定，检索时把知识库范围下推到 SQL 过滤 |
| **可选增强** | HyDE 假设性文档改写（仅用于向量通道）、Contextual Retrieval 上下文前缀增强，默认均关闭 |
| **接口鉴权** | 配置 `KB_API_KEY` 后强制校验 `X-API-Key` 请求头 |

### 1.3 设计边界（Non-Goals）

- 不内置重型本地 OCR 与版面识别模型（扫描件会被阻断并标记 `NEEDS_OCR`）。
- 不引入 Milvus / Qdrant / Elasticsearch 等独立中间件。
- 不构建多租户权限体系与知识图谱。
- 不做服务端多轮会话：历史会话仅存浏览器 IndexedDB，每次提问独立检索。

---

## 2. 系统架构与数据流

### 2.1 总体数据流

```text
【异步摄入链路】
POST /api/documents（multipart）
  → 转存自有临时目录（kb.ingest.temp-dir）
  → 登记 documents 记录（PROCESSING）+ 提交 ingestExecutor 异步任务
  → ParseRouter 解析路由（AUTO / LOCAL / CLOUD）
      LOCAL：PdfParser / WordParser / ExcelParser / PptParser / HtmlParser / MarkdownParser / TxtParser
      CLOUD：LlamaParseClient → Markdown → MarkdownParser
  → ScanDetector 扫描件检测（无文本层 → NEEDS_OCR）
  → ParentChunker 父块切分 → ChildChunker 子块切分（子块绝不跨父块）
  → ContextPrefixGenerator 可选上下文前缀（默认关闭）
  → 单事务落库 parent_chunks + child_chunks，embedding 为空，状态置 CHUNKED
  → 用户在界面检查/编辑切片 → POST /api/documents/{id}/index
  → VectorizationService 按批（默认 10）向量化写回 → 状态 COMPLETED

【实时查询链路】
POST /api/chat（SSE）
  → RetrievalService
      ① HyDE 改写（可选，失败降级原提问）
      ② HybridRetriever 三路并行召回（仅 status=COMPLETED 且 embedding 非空；下推 kbIds 过滤）
      ③ RrfFuser 倒数秩融合（Top 50）
      ④ RerankClient 云端重排（Top 15，失败降级 RRF 前 15）
      ⑤ ParentAggregator 父块归并 + 动态 Token 预算裁剪
  → 发送 citations 事件（父子两级引用）
  → PromptRenderer 拼接 <context> 提示词
  → ChatModel 流式生成 → message 增量事件 → done
```

### 2.2 模块划分

| 包 | 职责 | 关键类 |
| :--- | :--- | :--- |
| `api` | REST 接口层 | `DocumentController`、`DocumentChunkController`、`DocumentIndexController`、`KbController`、`ChatController`、`TokenCountController` |
| `parse` | 解析与 Block IR | `ParseRouter`、`DocumentParser`（SPI）、`Block`、`ScanDetector`、`TableMarkdown` |
| `parse.local` | 本地解析器 | `PdfParser`、`WordParser`、`ExcelParser`、`PptParser`、`HtmlParser`、`MarkdownParser`、`TxtParser`、`TikaFallback` |
| `parse.cloud` | 云端解析器 | `LlamaParseClient`（`@ConditionalOnProperty` 按需装配） |
| `chunk` | 分块与 Token 计量 | `ParentChunker`、`ChildChunker`、`ChunkEditService`、`TokenCounter` / `JtokkitTokenCounter` |
| `ingest` | 异步摄入流水线 | `IngestPipeline`、`ContextPrefixGenerator` |
| `index` | 手动向量化 | `VectorizationService` |
| `search` | 检索编排 | `RetrievalService`、`HybridRetriever`、`RrfFuser`、`RerankClient`、`ParentAggregator`、`HydeRewriter`、`ContextSegment` |
| `chat` | 问答生成 | `ChatService`、`PromptRenderer` |
| `config` | 配置与切面 | `AiModelConfig`、`AsyncExecutorConfig`、`WebFilterConfig`、`ApiKeyAuthFilter`、`StartupCredentialValidator`、`config.properties.*` |
| `repo` | 持久层 | `entity` / `mapper`（MyBatis XML 在 `src/main/resources/mapper`） |
| `common` | 通用契约 | `ApiResponse`、`PageResult`、`ErrorCode`、`BizException`、`GlobalExceptionHandler`、`DocumentStatus`、`ParseMode`、`EmbeddingSupport` |

### 2.3 数据模型

由 `docker/init/001_schema.sql` 幂等创建：

| 表 | 说明 |
| :--- | :--- |
| `documents` | 文档主表：文件名/类型/大小、状态机、解析模式、父子块计数、错误信息 |
| `parent_chunks` | 父块：`(document_id, seq)` 唯一，存完整上下文文本与结构元数据，**不建向量索引** |
| `child_chunks` | 子块：`(parent_id, seq)` 唯一，存 `embedding vector(1024)`、`search_content tsvector`、`context_prefix` |
| `knowledge_bases` | 知识库分类（名称唯一） |
| `document_knowledge_bases` | 文档与知识库多对多关联，外键 `ON DELETE CASCADE` |

关键索引与触发器：

- `idx_child_embedding`：HNSW 余弦索引（`m=16, ef_construction=64`）。
- `idx_child_search`：中文全文 GIN 索引；由 `trg_child_chunks_tsvector` 触发器自动维护（`context_prefix` 权重 B、`content` 权重 A）。
- `idx_child_trgm`：`content` 的 `gin_trgm_ops` GIN 索引。
- 删除 `documents` 主表记录即级联清理父子块。

### 2.4 文档状态机

```text
PROCESSING ──┬─→ CHUNKED ──→ INDEXING ──→ COMPLETED ──→（编辑）COMPLETED
             │                │                          （编辑且重嵌失败）
             │                └────────→ INDEX_FAILED ──→（重试 index）INDEXING
             ├─→ NEEDS_OCR      （疑似扫描件，无文本层）
             └─→ FAILED         （解析失败）
```

- 只有 `CHUNKED` / `INDEX_FAILED` 可进入 `INDEXING`（否则返回 `INDEX_CONFLICT`）。
- 只有 `CHUNKED` / `INDEX_FAILED` / `COMPLETED` 允许编辑切片（否则返回 `DOC_NOT_EDITABLE`）。
- **只有 `COMPLETED` 且 `embedding IS NOT NULL` 的子块参与检索**（`indexedFilter`）。

---

## 3. 技术栈与运行环境要求

### 3.1 后端

| 层次 | 组件 | 版本 |
| :--- | :--- | :--- |
| 运行时 | JDK | 17 |
| 框架 | Spring Boot（Web / Validation / Actuator） | 3.5.15 |
| 模型抽象 | Spring AI BOM + `spring-ai-starter-model-openai` | 1.1.8 |
| 分词 | jtokkit（随 Spring AI 传递依赖，`CL100K_BASE`） | — |
| 持久层 | MyBatis Spring Boot Starter + PostgreSQL JDBC | 3.0.5 |
| 本地解析 | Apache POI（`poi-ooxml`） | 5.5.1 |
| 本地解析 | Apache PDFBox | 3.0.5 |
| 本地解析 | Apache Tika（`tika-core` + `tika-parsers-standard-package`） | 3.2.3 |
| 本地解析 | jsoup / flexmark（`html2md-converter` + `ext-tables`） | 1.23.2 / 0.64.8 |
| 云端解析 | LlamaParse 官方 Java SDK（`ai.llamaindex:llama-cloud`） | 1.7.0 |
| 构建 | Maven（Spring Boot Maven Plugin） | — |

### 3.2 数据库

- PostgreSQL 16（`pgvector/pgvector:pg16` 基座镜像）。
- 扩展：`pgcrypto`、`vector`、`pg_trgm`、`zhparser`（Dockerfile 内编译 SCWS + zhparser）。
- 全文检索配置：`zh_cfg`（PARSER = zhparser）。
- **硬约束**：实例初始化时 `LC_CTYPE` 必须为 `C.UTF-8` 或 `en_US.utf8`（compose 中由 `POSTGRES_INITDB_ARGS` 保证），否则 `pg_trgm` 会忽略所有非 ASCII 中文字符。

### 3.3 前端

| 组件 | 版本要求 |
| :--- | :--- |
| Node.js | 22+（镜像 `node:22-alpine`） |
| pnpm | 11（`packageManager: pnpm@11.28.5`，用 `corepack enable` 激活） |
| Vue | 3.5 |
| Vite | 7 |
| TypeScript | 5.9（`vue-tsc` 3.3） |
| Tailwind CSS | 4（`@tailwindcss/vite`） |
| 其他 | vue-router 4、Pinia 3、@tanstack/vue-query 5、markdown-it 14、dompurify 3、idb-keyval 6 |

### 3.4 端口一览

| 服务 | 默认地址 | 说明 |
| :--- | :--- | :--- |
| 后端 API | `http://127.0.0.1:8080` | `server.port=8080` |
| 前端开发服务器 | `http://127.0.0.1:5173` | Vite dev，`/api` 代理到后端 |
| PostgreSQL | `127.0.0.1:15432` | 宿主端口映射到容器 5432 |

---

## 4. 目录结构说明

```text
eon-rag/
├─ pom.xml                         后端 Maven 构建（Spring Boot 3.5.15，Java 17）
├─ docker/
│  ├─ Dockerfile                   PostgreSQL 定制镜像：pgvector 基座 + SCWS + zhparser
│  ├─ docker-compose.yml           仅编排 PostgreSQL（宿主 15432 → 容器 5432）
│  └─ init/001_schema.sql          幂等建表：扩展、表、索引、触发器
├─ docs/
│  └─ 轻量级生产级 RAG 知识库架构实践：结构化解析、父子分块与三路混合检索.md
└─ frontend/
   ├─ package.json / pnpm-lock.yaml / pnpm-workspace.yaml
   ├─ vite.config.ts               端口 5173，/api 代理到 VITE_API_PROXY（默认 8080）
   ├─ Dockerfile                   node:22-alpine 构建 → nginx:1.27-alpine 托管
   ├─ nginx.conf                   SPA 回退、/api 反代（关闭 proxy_buffering 支持 SSE）
   ├─ mock/server.mjs              离线 mock API（含 SSE），默认 8080
   ├─ scripts/                     Playwright 截图与 E2E 脚本
   └─ src/
      ├─ app/                      AppShell、SidebarNav、TopBar
      ├─ features/
      │  ├─ chat/                  ChatComposer、ChatMessage、CitationRail
      │  ├─ documents/             UploadDialog、ChildChunk 编辑、ParentChunk 编辑
      │  └─ kbs/                   KbFormDialog、BindDocumentsDialog
      ├─ components/               UI 与品牌组件
      ├─ lib/                      api、sse、markdown、format、storage(IndexedDB)、tokens
      ├─ pages/                    ChatPage、DocumentsPage、DocumentDetailPage、KbsPage、KbDetailPage
      ├─ stores/                   Pinia：chat、ui、toast
      ├─ styles/main.css           设计令牌与组件样式
      └─ types/                    接口与聊天类型
```

后端源码（`src/main/java/cn/kong/eon/rag/`）与资源（`src/main/resources/`）：

```text
src/main/
├─ java/cn/kong/eon/rag/
│  ├─ RagApplication.java          入口（排除 Spring AI 向量化自动配置）
│  ├─ api/                         REST 控制器与 DTO
│  ├─ chat/                        ChatService（SSE）、PromptRenderer
│  ├─ chunk/                       Parent/Child 切分器、编辑服务、Token 计数器
│  ├─ common/                      ApiResponse、PageResult、异常、枚举、工具
│  ├─ config/                      模型装配、线程池、鉴权过滤器、启动校验、properties
│  ├─ index/                       VectorizationService
│  ├─ ingest/                      IngestPipeline、ContextPrefixGenerator
│  ├─ parse/                       ParseRouter、Block、ScanDetector 与 local/cloud 解析器
│  ├─ repo/                        entity 与 mapper
│  ├─ search/                      检索编排与三路召回、融合、重排、归并
│  └─ typehandler/                 UUIDTypeHandler
└─ resources/
   ├─ application.yml              全部默认配置（见第 6 节）
   └─ mapper/*.xml                 MyBatis SQL（三路召回 SQL 在此）
```

---

## 5. 安装配置与本地启动

### 5.1 前置检查

- JDK 17+、Maven 3.8+
- Docker 与 Docker Compose（用于 PostgreSQL）
- Node.js 22+ 与 pnpm 11（仅前端需要）

### 5.2 第一步：启动数据库

`docker/docker-compose.yml` **只编排 PostgreSQL 一个服务**（应用本身按 5.3 在本地启动）。

```bash
cd docker
export KB_DB_PASSWORD='your-strong-password'
docker compose up -d --build
```

首次启动会自动完成：

1. 按 `POSTGRES_DB` 建库 `eon_rag`；
2. 执行 `./init/001_schema.sql` 创建扩展、表、索引与触发器。

> **注意**：init 脚本**仅在数据卷为空时执行**。修改了建表脚本需要重跑时，执行 `docker compose down -v` 后再 `up`。

镜像标签为 `eon-postgres:16`，容器名为 `eon-rag-db`，宿主端口 `15432`。

### 5.3 第二步：启动后端

**必填环境变量**（缺失会由 `StartupCredentialValidator` 在启动期直接抛错）：

| 变量 | 用途 |
| :--- | :--- |
| `KB_DB_PASSWORD` | 数据库密码，须与 5.2 中 `POSTGRES_PASSWORD` 一致 |
| `DEEPSEEK_API_KEY` | 对话生成模型密钥（OpenAI 兼容协议） |
| `DASHSCOPE_EMBEDDING_API_KEY` | 百炼向量化密钥 |
| `DASHSCOPE_RERANK_API_KEY` | 百炼重排密钥 |
| `LLAMA_CLOUD_API_KEY` | 仅当 `LLAMA_CLOUD_ENABLED=true` 时必填 |

Linux / macOS：

```bash
export KB_DB_PASSWORD='your-strong-password'
export DEEPSEEK_API_KEY='sk-xxx'
export DASHSCOPE_EMBEDDING_API_KEY='sk-xxx'
export DASHSCOPE_RERANK_API_KEY='sk-xxx'

mvn spring-boot:run
```

Windows（PowerShell）：

```powershell
$env:KB_DB_PASSWORD='your-strong-password'
$env:DEEPSEEK_API_KEY='sk-xxx'
$env:DASHSCOPE_EMBEDDING_API_KEY='sk-xxx'
$env:DASHSCOPE_RERANK_API_KEY='sk-xxx'

mvn spring-boot:run
```

打包后运行：

```bash
mvn clean package -DskipTests
java -jar target/eon-rag-1.0.0.jar
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

> 默认只暴露 `health` 端点，可用 `KB_ACTUATOR_EXPOSURE=health,info` 扩展。

### 5.4 第三步：启动前端

```bash
cd frontend
corepack enable
pnpm install
pnpm dev          # http://127.0.0.1:5173
```

开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`，可用环境变量覆盖：

```bash
VITE_API_PROXY=http://127.0.0.1:9090 pnpm dev
```

**无密钥 / 无数据库时**可用内置 mock 联调界面（mock 默认监听 8080，可用 `MOCK_PORT` 覆盖）：

```bash
# 终端 1
pnpm mock
# 终端 2
pnpm dev
```

mock 覆盖知识库、文档、父子块编辑、手动向量化与 `POST /api/chat` 的 SSE 事件。

### 5.5 生产部署（可选）

前后端容器编排与数据库编排**分开在两个文件**，互不依赖：

| 文件 | 负责 |
| :--- | :--- |
| `docker/docker-compose.yml` | 仅数据库（宿主端口 15432） |
| `docker/docker-compose.app.yml` | 后端 `app` + 前端 `web` |

```bash
# 1) 先起数据库
cd docker
export KB_DB_PASSWORD='...'
docker compose up -d --build

# 2) 再起前后端（4 个模型 Key 同样要 export）
export DEEPSEEK_API_KEY='...' DASHSCOPE_EMBEDDING_API_KEY='...' DASHSCOPE_RERANK_API_KEY='...'
docker compose -f docker-compose.app.yml up -d --build
```

起来后前端在 `http://127.0.0.1:8090`，后端在 `http://127.0.0.1:8080`。`web` 容器里的 Nginx 把 `/api` 反代到同一编排内的 `app:8080`，浏览器只访问一个端口，无需 CORS。后端容器通过 `host.docker.internal:15432` 连数据库，也就是数据库 compose 发布到宿主的端口；两个编排不共享 compose 网络，各自独立启停。

前端镜像也可以单独构建后挂到别的编排里：

```bash
cd frontend
docker build -t eon-rag-web:1.0.0 .
```

这种用法下把 `nginx.conf` 里的 `proxy_pass` 改成实际后端地址即可。

`nginx.conf` 已处理：SPA 路由回退、`/api` 反代并关闭 `proxy_buffering` 以支持 SSE、`client_max_body_size 60m` 匹配 50MB 上传上限、带 hash 的静态资源长缓存。

---

## 6. 配置项参考

所有默认值来自 `src/main/resources/application.yml`，均可用环境变量覆盖。

### 6.1 环境变量清单

| 环境变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `KB_DB_URL` | `jdbc:postgresql://127.0.0.1:15432/eon_rag` | 数据库连接串 |
| `KB_DB_USERNAME` | `postgres` | 数据库用户 |
| `KB_DB_PASSWORD` | 空（**必填**） | 数据库密码 |
| `DEEPSEEK_API_KEY` | 空（**必填**） | 对话生成密钥 |
| `DEEPSEEK_BASE_URL` | `https://api.xiaomimimo.com` | **不可带 `/v1`**，Spring AI 会自行拼接 |
| `DASHSCOPE_EMBEDDING_API_KEY` | 空（**必填**） | 百炼向量化密钥 |
| `DASHSCOPE_EMBEDDING_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` | 向量化端点 |
| `DASHSCOPE_RERANK_API_KEY` | 空（**必填**） | 百炼重排密钥 |
| `DASHSCOPE_RERANK_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-api/v1` | 重排端点（**前缀与向量化不同，不可混用**） |
| `KB_PARSE_MODE` | `AUTO` | `AUTO` / `LOCAL` / `CLOUD` |
| `LLAMA_CLOUD_ENABLED` | `false` | 是否启用云端解析客户端 |
| `LLAMA_CLOUD_API_KEY` | 空 | LlamaParse 密钥 |
| `LLAMA_CLOUD_BASE_URL` | `https://api.cloud.llamaindex.ai` | LlamaParse 端点 |
| `LLAMA_CLOUD_TIER` | `cost_effective` | `fast` / `cost_effective` / `agentic` / `agentic_plus` |
| `LLAMA_CLOUD_VERSION` | `latest` | 解析接口版本 |
| `KB_CONTEXT_ENRICHMENT_ENABLED` | `false` | 子块上下文前缀增强（会额外调用大模型） |
| `KB_CHAT_SSE_TIMEOUT_MS` | `120000` | SSE 流最长持续时间 |
| `KB_API_KEY` | 空 | 非空时强制校验 `X-API-Key` 请求头 |
| `KB_LOG_LEVEL` | `INFO` | `cn.kong.eon.rag` 日志级别 |
| `KB_ACTUATOR_EXPOSURE` | `health` | Actuator 暴露端点 |
| `MOCK_PORT` | `8080` | 仅前端 mock 服务器使用 |
| `VITE_API_PROXY` | `http://127.0.0.1:8080` | 仅前端开发代理使用 |

### 6.2 关键业务参数（`kb.*`）

```yaml
kb:
  embedding:
    model: text-embedding-v4        # 百炼向量模型
    dimensions: 1024                # 与 child_chunks.embedding vector(1024) 一致
    batch-size: 10                  # 百炼单批硬性上限，不可调大
  rerank:
    model: qwen3-rerank
  parse:
    mode: AUTO
    cloud-types: pdf,doc,docx,ppt,pptx   # AUTO 模式优先分流云端的白名单
    max-file-size: 52428800              # 50MB
    min-text-chars: 50                   # 整篇有效字符低于该值判定疑似扫描件
  chunk:
    parent:  { max-tokens: 1200, min-tokens: 200, heading-level: 2 }
    child:   { max-tokens: 320, merge-max-tokens: 640, overlap-tokens: 48 }
  ingest:
    temp-dir: ${java.io.tmpdir}/eon-rag
  search:
    vector-top-k: 20
    fulltext-top-k: 20
    fuzzy-top-k: 10
    fuzzy-similarity-threshold: 0.2      # 中文密度高，由默认 0.3 调低
    rrf-k: 60
    rrf-top-n: 50                       # 送 Rerank 的候选宽度
    rerank-top-n: 15
    parent-hit-bonus-weight: 0.05       # 父块命中数加分 α
    hyde-enabled: false
  chat:
    max-context-tokens: 6000
    output-reserve-tokens: 1000
    sse-timeout-ms: 120000
  context-enrichment:
    enabled: false
  security:
    api-key: ""                          # 非空即启用鉴权
  thread-pool:
    ingest: { core: 2, max: 4, queue: 50 }
    search: { core: 8, max: 16, queue: 100 }
```

上下文预算计算：`max-context-tokens - output-reserve-tokens - 模板固定开销(200) - 问题 Token`。

### 6.3 支持的解析格式

| 模式 | 覆盖格式 |
| :--- | :--- |
| `LOCAL` 本地解析 | `pdf`、`doc`、`docx`、`ppt`、`pptx`、`xls`、`xlsx`、`html`、`htm`、`md`、`markdown`、`txt` |
| `CLOUD` 云端解析 | LlamaParse 支持的扩展格式（需 `LLAMA_CLOUD_ENABLED=true`） |
| `AUTO` | `cloud-types` 白名单内的格式且云端客户端已装配时走云端，云端异常自动回退本地；其余走本地 |

> `AUTO` 模式下若未启用云端客户端，所有格式都走本地解析。

---

## 7. 接口调用示例

### 7.1 统一响应约定

成功响应统一包装：

```json
{ "code": "OK", "message": null, "data": { } }
```

失败响应：

```json
{ "code": "DOC_NOT_FOUND", "message": "文档不存在：xxx", "data": null }
```

HTTP 状态码映射（`GlobalExceptionHandler`）：

| 错误码 | HTTP |
| :--- | :--- |
| `UNAUTHORIZED` | 401 |
| `FILE_TOO_LARGE` | 413 |
| `DOC_NOT_FOUND` / `KB_NOT_FOUND` / `PARENT_NOT_FOUND` / `CHUNK_NOT_FOUND` | 404 |
| `KB_NAME_EXISTS` / `DOC_NOT_EDITABLE` / `CHUNK_CONFLICT` / `INDEX_CONFLICT` | 409 |
| `NEEDS_OCR` / `INVALID_CHUNK_OPERATION` / `TOKEN_LIMIT_EXCEEDED` | 422 |
| `EMBEDDING_FAILED` | 502 |
| 其余 | 500 |

鉴权：`KB_API_KEY` 非空时，所有 `/api/*` 请求需带 `X-API-Key` 头；`OPTIONS` 预检与 `/actuator/**` 自动放行。

### 7.2 接口清单

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| POST | `/api/chat` | SSE 流式问答 |
| POST | `/api/documents` | 上传文档（multipart） |
| GET | `/api/documents` | 文档列表（含 `kbIds`） |
| GET | `/api/documents/{id}` | 文档详情 |
| GET | `/api/documents/{id}/parent-chunks` | 父块分页列表（`page`、`size`，size 上限 200） |
| GET | `/api/documents/{id}/parent-chunks/{parentId}` | 父块详情（引用定位） |
| DELETE | `/api/documents/{id}` | 删除文档（级联清理父子块） |
| POST | `/api/documents/{docId}/index` | 手动触发向量化 |
| GET | `/api/documents/{docId}/parent-chunks/{parentId}/children` | 子块列表 |
| PUT | `/api/documents/{docId}/parent-chunks/{parentId}` | 编辑父块 |
| DELETE | `/api/documents/{docId}/parent-chunks/{parentId}` | 删除父块 |
| PUT | `/api/documents/{docId}/parent-chunks/{parentId}/children/{childId}` | 编辑子块 |
| DELETE | `/api/documents/{docId}/parent-chunks/{parentId}/children/{childId}` | 删除子块 |
| POST | `/api/documents/{docId}/parent-chunks/{parentId}/children/merge` | 合并连续子块 |
| POST | `/api/documents/{docId}/parent-chunks/{parentId}/children/{childId}/split` | 按光标位置拆分子块 |
| GET | `/api/kbs` | 知识库列表 |
| POST | `/api/kbs` | 创建知识库 |
| GET | `/api/kbs/{id}` | 知识库详情 |
| PUT | `/api/kbs/{id}` | 更新知识库 |
| DELETE | `/api/kbs/{id}` | 删除知识库（仅清关联，不动文档） |
| POST | `/api/kbs/{id}/documents/{documentId}` | 绑定文档 |
| DELETE | `/api/kbs/{id}/documents/{documentId}` | 解绑文档 |
| GET | `/api/kbs/{id}/documents` | 知识库下的文档 |
| POST | `/api/tokens/count` | 精确 Token 计数（一次最多 8 段） |

### 7.3 上传文档

```bash
curl -X POST http://127.0.0.1:8080/api/documents \
  -H "X-API-Key: your-api-key" \
  -F "file=@./产品手册.pdf" \
  -F "kbIds=<知识库UUID>" \
  -F "parseMode=AUTO"
```

```json
{ "code": "OK", "message": null, "data": { "documentId": "b0f1..." } }
```

- 返回 `202 Accepted`，摄入在后台异步进行。
- `kbIds` 可重复传多个；`parseMode` 仅接受 `AUTO` / `LOCAL` / `CLOUD`，其余回落 `AUTO`。

### 7.4 轮询状态并触发向量化

```bash
# 查看文档状态
curl http://127.0.0.1:8080/api/documents/<documentId>

# 状态为 CHUNKED（或 INDEX_FAILED）时手动向量化
curl -X POST http://127.0.0.1:8080/api/documents/<documentId>/index
```

```json
{ "code": "OK", "message": null, "data": { "documentId": "b0f1...", "status": "INDEXING" } }
```

### 7.5 流式问答（SSE）

```bash
curl -N -X POST http://127.0.0.1:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"question":"支付网关如何配置回调地址？","kbIds":["<知识库UUID>"]}'
```

事件序列：

```text
event: citations
data: [{"index":1,"title":"回调配置","documentId":"...","parentId":"...",
        "meta":{"file_name":"产品手册.pdf","page_start":"12","page_end":"13"},
        "children":[{"childId":"...","seq":3,"score":0.87}]}]

event: message
data: 支付网关的回调地址可在

event: message
data: 「商户平台 → 开发配置」中修改……

event: done
data: {}
```

- `kbIds` 为空数组表示全库检索。
- 无检索结果时直接推送一条 `message: 知识库中未找到相关信息。` 后 `done`。
- 超时或异常以 `event: error` 收尾（`data` 为提示文本）。

### 7.6 切片编辑

```bash
# 编辑父块（默认按新正文重建子块）
curl -X PUT http://127.0.0.1:8080/api/documents/<docId>/parent-chunks/<parentId> \
  -H "Content-Type: application/json" \
  -d '{"title":"回调配置","content":"……","rebuildChildren":true}'

# 合并连续子块
curl -X POST http://127.0.0.1:8080/api/documents/<docId>/parent-chunks/<parentId>/children/merge \
  -H "Content-Type: application/json" \
  -d '{"childIds":["<childId1>","<childId2>"]}'

# 按光标位置拆分（offset 为 Java 字符下标，对应 textarea 的 selectionStart）
curl -X POST http://127.0.0.1:8080/api/documents/<docId>/parent-chunks/<parentId>/children/<childId>/split \
  -H "Content-Type: application/json" \
  -d '{"offset":128}'

# 精确 Token 计数
curl -X POST http://127.0.0.1:8080/api/tokens/count \
  -H "Content-Type: application/json" \
  -d '{"texts":["第一段","第二段"]}'
```

编辑语义（对齐 Dify 父子分段）：

- 子块只影响检索，编辑子块**不改变**父块内容。
- 父块编辑默认按新正文重建子块；传 `"rebuildChildren": false` 可保留原子块。
- 删除最后一个子块时保留空父块。
- Token 上限：父块 1200；子块编辑/拆分 320；人工合并 640（对应 `kb.chunk.*`）。
- `COMPLETED` 文档编辑后自动重嵌受影响子块；`CHUNKED` / `INDEX_FAILED` 只保存文本。

### 7.7 知识库

```bash
curl -X POST http://127.0.0.1:8080/api/kbs \
  -H "Content-Type: application/json" \
  -d '{"name":"产品文档","description":"支付与账户说明","color":"#4176E6"}'

curl -X POST http://127.0.0.1:8080/api/kbs/<kbId>/documents/<documentId>
```

---

## 8. 界面使用说明

前端路由（`frontend/src/router/index.ts`）：

| 路径 | 页面 | 功能 |
| :--- | :--- | :--- |
| `/chat` | 问答 | 选择知识库范围提问、SSE 流式回答、Markdown 渲染、`[n]` 引用联动、本地历史 |
| `/documents` | 文档 | 上传、状态轮询、筛选、删除 |
| `/documents/:id` | 文档详情 | 手动向量化、父块浏览与编辑、子块编辑/删除/合并/拆分 |
| `/kbs` | 知识库 | CRUD、文档绑定/解绑 |

使用流程：

1. 在「知识库」页创建知识库（可选）。
2. 在「文档」页上传文件，可指定目标知识库与解析模式。
3. 上传只做解析与分块，文档进入 `CHUNKED`（待向量化）；列表中含 `PROCESSING` / `INDEXING` 时每 2.5 秒自动轮询。
4. 进入文档详情检查切片，按需编辑父块/子块。
5. 点击「向量化」→ 状态变为 `INDEXING` → `COMPLETED`。
6. 在「问答」页选择知识库范围提问，答案以流式输出，右侧引用栏可回看父块原文。

约定：

- 上传不限制扩展名；无法解析的格式会在摄入后标记 `FAILED` 或 `UNSUPPORTED_FORMAT`。
- Token 展示优先复用后端持久化的 `token_count`；编辑中的文本调用 `POST /api/tokens/count` 使用与后端同一套 jtokkit `CL100K_BASE` 计数，网络异常时回退本地估算并标注 `≈`。
- 摄入阶段使用不确定态动画，不伪造百分比。
- 前端固定同源访问，不提供 API 地址/Key 配置入口；收到 401 时仅提示检查后端鉴权配置。
- 本地历史存 IndexedDB（最多 20 个会话），每次提问独立检索，不向服务端发送历史。

界面截图：`cd frontend && pnpm shot` 会用 Playwright 生成截图到 `frontend/shots/`（该目录默认被 `.gitignore` 忽略）。

---

## 9. 测试与构建

### 后端

```bash
mvn test                      # 单元测试（Chunkers、PromptRenderer、RrfFuser、ParentAggregator、LocalParsers 等）
mvn clean package -DskipTests # 打包
```

Schema 与三路检索 SQL 集成测试（`SchemaSqlIT`）默认跳过，需同时满足：

- 本机 Docker 可用；
- 已构建镜像 `eon-postgres:16`（即执行过 `docker compose build`）；
- 环境变量 `EON_RAG_IT=1`。

```bash
cd docker && docker compose build    # 产出 eon-postgres:16
cd .. && EON_RAG_IT=1 mvn -Dtest=SchemaSqlIT test
```

### 前端

```bash
cd frontend
pnpm typecheck    # vue-tsc
pnpm build        # 产物在 dist/
pnpm preview      # 本地预览构建产物
```

真实后端联调与 E2E（后端需运行在 8080，脚本会创建并清理临时数据）：

```bash
node scripts/check-real.mjs   # 校验文档/父块/知识库，并跑一次上传（自动清理）
pnpm test:e2e                 # 建库 → 上传 → 解析分块 → 手动向量化 → 绑定 → 问答 → 引用 → 历史恢复
pnpm test:crud                # 知识库 CRUD、绑定/解绑、文档删除
pnpm test:interactions        # 缺少扩展名拦截、停止生成
pnpm test:chunks              # 父块编辑/重建、子块编辑/合并/拆分/删除
pnpm test:manual              # 上传 → CHUNKED → 编辑 → 手动向量化 → 检索 → 编辑自动重嵌
pnpm probe:chat               # 仅探测 /api/chat 的 SSE 与引用数量
```

---

## 10. 常见问题与注意事项

### 10.1 启动失败

| 现象 | 原因与处理 |
| :--- | :--- |
| 启动即抛 `XXX 未配置` | `StartupCredentialValidator` 的 Fail-Fast 校验。补齐 `DEEPSEEK_API_KEY`、`KB_DB_PASSWORD`、`DASHSCOPE_EMBEDDING_API_KEY`、`DASHSCOPE_RERANK_API_KEY`；启用云端解析时还需 `LLAMA_CLOUD_API_KEY` |
| 数据库连接失败 | 确认 compose 已启动且端口为 `15432`；`KB_DB_PASSWORD` 与 `POSTGRES_PASSWORD` 一致 |
| 请求全部 401 | 设置了 `KB_API_KEY`，需在请求头带 `X-API-Key`。`/actuator/**` 与 `OPTIONS` 不受影响 |

### 10.2 文档处理

| 现象 | 原因与处理 |
| :--- | :--- |
| 状态 `NEEDS_OCR` | 疑似扫描件无文本层（整篇有效字符 < 50，PDF 另有页均 < 10 校验）。请上传文字版文档，或启用云端解析并选择 `CLOUD` 模式 |
| 状态 `FAILED` | 查看文档详情的 `errorMessage`；本地不支持的格式请切换 `CLOUD` 或把格式加入 `cloud-types` |
| 上传报 `FILE_TOO_LARGE` | 单文件上限 50MB（`kb.parse.max-file-size`）。Nginx 反代时 `client_max_body_size` 已设为 60m |
| 文档已 `COMPLETED` 但检索不到 | 检索 SQL 同时要求 `embedding IS NOT NULL` 且文档 `status = 'COMPLETED'`；编辑后若重嵌失败会回落 `INDEX_FAILED`，需重新触发 `/index` |
| 触发 `/index` 返回 409 `INDEX_CONFLICT` | 仅 `CHUNKED` / `INDEX_FAILED` 可进入 `INDEXING`；`PROCESSING` 需等摄入完成 |
| 编辑提示 `DOC_NOT_EDITABLE` | 仅 `CHUNKED` / `INDEX_FAILED` / `COMPLETED` 可编辑切片 |
| 编辑提示 `CHUNK_CONFLICT` | 内容已被并发操作修改，刷新后重试（更新走 `... WHERE content = <旧值>` 乐观锁） |
| 合并被拒 | 只能合并**同一父块内连续**的子块，且合并后 ≤ 640 Token |
| 拆分被拒 | `offset` 必须落在内容中间，两段都不能为空，且各 ≤ 320 Token |

### 10.3 检索与问答

| 现象 | 原因与处理 |
| :--- | :--- |
| 中文全文检索无结果 | 检查数据库 `LC_CTYPE` 是否为 `C.UTF-8` / `en_US.utf8`；init 脚本依赖该设置，否则 `pg_trgm` 会忽略中文 |
| 三路中某一路无结果 | 属正常降级：单路失败仅记 `warn` 日志并参与剩余通道融合。可临时开启 `--logging.level.cn.kong.eon.rag=DEBUG` 查看 `retrieval channel=...` 日志 |
| 答案质量差 | 可调大 `kb.search.rrf-top-n` 增加重排候选宽度，或开启 `kb.search.hyde-enabled=true`（会增加一次大模型调用与首字延迟） |
| 引用不完整 | 上下文受 Token 预算约束：`max-context-tokens - output-reserve-tokens - 200 - 问题 Token`。超预算的高分父块之后会被裁剪 |
| 生成超时 | 默认 120 秒（`KB_CHAT_SSE_TIMEOUT_MS`），超时推送 `error` 事件并中止生成 |
| 答案「未找到相关信息」 | 所选知识库范围内无 `COMPLETED` 文档，或确实无命中 |

### 10.4 其他注意事项

- **重排与向量化端点前缀不同**：向量化 `.../compatible-mode`，重排 `.../compatible-api/v1`，不可混用。
- **`DEEPSEEK_BASE_URL` 不要带 `/v1`**：Spring AI 会自行拼接 `/v1/chat/completions`，否则会产生 `/v1/v1/` 路径。
- **向量维度固定 1024**：修改 `kb.embedding.dimensions` 必须同步修改 `child_chunks.embedding vector(1024)` 与已有索引。
- **`batch-size` 不可调大**：百炼 `text-embedding-v4` 单批硬性上限为 10。
- **开启上下文前缀增强会额外调用大模型**：`KB_CONTEXT_ENRICHMENT_ENABLED=true` 时摄入阶段对每个「父块无标题且以代词开头」的子块调用一次生成。
- **临时文件**：摄入会把上传流转存到 `kb.ingest.temp-dir`（默认 `${java.io.tmpdir}/eon-rag`），任务结束后在 `finally` 中清理。
- **线程池队列满时回落到调用线程**（`CallerRunsPolicy`），此时上传/索引请求会同步阻塞。
- **前端列表为客户端筛选**，后端分页接口目前仅 `GET /api/documents/{id}/parent-chunks` 提供（`size` 上限 200）。
- **未实现**：服务端多轮会话、仪表盘、命令面板、文档在线预览。

---

## 相关文档

- [`docs/轻量级生产级 RAG 知识库架构实践：结构化解析、父子分块与三路混合检索.md`](docs/轻量级生产级%20RAG%20知识库架构实践：结构化解析、父子分块与三路混合检索.md) — 架构设计与实现细节
- [`frontend/README.md`](frontend/README.md) — 前端模块、视觉规范与实现约定
