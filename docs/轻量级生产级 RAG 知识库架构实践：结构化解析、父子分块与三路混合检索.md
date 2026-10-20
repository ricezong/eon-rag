# 轻量级生产级 RAG 知识库架构实践：结构化解析、父子分块与三路混合检索

在构建检索增强生成（RAG）知识库系统时，单机与中小规模场景往往面临着“架构复杂度”与“检索准确率”之间的两难权衡：一方面，若直接照搬面向超大规模数据的分布式架构（独立部署 OCR 集群、专用向量数据库、全文检索引擎与重排模型服务），会导致运维成本激增；另一方面，若退化为简单的“纯文本提取 + 固定字符切分 + 单路向量检索”，又会在跨页表格、多级章节文档、专业术语与产品型号检索上频频失效。

本文面向单机生产级部署场景，系统阐述一套基于 **Spring Boot 3.5 + Spring AI 1.1 + PostgreSQL 16** 的轻量级 RAG 知识库架构方案。系统在不自建独立 OCR 服务与专用向量数据库的前提下，通过**多源结构化解析归一**、**父子两段式结构分块**以及**基于单实例 PostgreSQL 的三路混合检索与重排归并**，实现高召回率、低运维成本的端到端问答闭环。

## 1. 架构目标、设计边界与全链路视图

### 1.1 核心架构目标

1. **解析层格式归一**：将 PDF、Word、Excel、PPT、HTML、Markdown 等异构文档统一解析为带类型与层级的结构化中间表示（`Block` 序列），使下游分块层彻底脱离对原始文件格式的感知。
2. **父子两段式分块**：采用“小块检索、大块生成”的两级切片模型——子块（Child Chunk）聚焦细粒度语义以提升检索命中精度，父块（Parent Chunk）按文档章节与结构边界聚合以保障生成模型的上下文完整性。
3. **三路混合召回与上卷归并**：结合向量语义检索、中文全文检索与 Trigram 模糊匹配实现多路互补召回；全程**仅对子块建立索引与向量**，命中子块经 RRF 融合与 Rerank 重排后，按父块归并打分并裁剪上下文送入生成模型。

### 1.2 系统边界与工程约束

- **部署形态**：面向个人开发者或轻量级团队的单机容器化部署，问答/向量化/重排模型与数据库凭证均通过环境变量注入，缺失即快速失败（Fail-Fast），不提供硬编码默认值；云端解析启用时额外要求 LlamaParse 凭证，接口鉴权密钥按需启用。
- **架构克制（Non-Goals）**：不内置重型本地 OCR 与版面识别模型、不引入独立向量数据库（Milvus / Qdrant）或全文检索引擎（Elasticsearch）、不构建复杂的多租户权限体系与知识图谱。

### 1.3 总体数据流与技术栈基线

系统的核心数据流分为**异步文档摄入（Ingestion）**与**实时混合检索问答（Retrieval & Generation）**两条主链路：

```text
【异步摄入链路】
文档上传 → 转存自有临时文件 → 格式校验与解析路由（本地 POI/PDFBox 或云端 LlamaParse）
        → 产出有序 Block 序列 → 疑似扫描件检测（无文本层则阻断并标记 NEEDS_OCR）
        → 父块切分（按标题层级聚合，表格/幻灯片独立成块）
        → 子块切分（父块内按段落/句子滑窗，绝不跨父块）
        → 单事务落库（父子表 + 触发器生成中文全文索引），子块 embedding 为空，状态 CHUNKED
        → 用户检查/编辑切片 → 手动触发向量化 → 微批 embedding → 状态 COMPLETED

【实时查询链路】
用户提问 →（可选）查询改写/HyDE → 三路并行召回子块（仅 status=COMPLETED 且 embedding 非空；下推知识库过滤）
        → RRF 倒数秩融合（Top 50）→ 云端 Rerank 重排（Top 15~20 子块）
        → 按 parent_id 归并并计算父块综合得分 → 依次加载父块文本
        → 动态 Token 预算裁剪（超长父块降级为局部子块窗口）
        → 单趟模板直接拼接 → 流式生成回答 + 挂载父/子两级精确引用
```

支撑上述链路的核心组件与版本基线如下表所示（均对齐 Maven Central 稳定版）：

| 架构层次 | 核心组件 | Maven 坐标 / 镜像 | 版本基线 | 架构职责说明 |
| :--- | :--- | :--- | :--- | :--- |
| **运行时与框架** | Java / Spring Boot | [org.springframework.boot:spring-boot-starter-parent](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Forg.springframework.boot%2Fspring-boot%2F3.5.15) | `JDK 17` / `3.5.15` | 提供 Web、SSE 流式响应、`RestClient` 与事务管理基座 |
| **模型抽象层** | Spring AI | [org.springframework.ai:spring-ai-bom](https://www.google.com/search?q=https%3A%2F%2Fdocs.spring.io%2Fspring-ai%2Freference%2Fgetting-started.html) | `1.1.8` | 引入 [spring-ai-starter-model-openai](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Forg.springframework.ai%2Fspring-ai-starter-model-openai) 统一对接 OpenAI 兼容协议与内置 `jtokkit` 分词器 |
| **云端解析** | LlamaParse SDK | [ai.llamaindex:llama-cloud](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Fai.llamaindex%2Fllama-cloud-client-okhttp%2F1.7.0) | `1.7.0` | 官方 v2 Java SDK，原生支持 130+ 格式云端版面解析、OCR 与分页 Markdown 提取 |
| **本地解析** | Apache POI / PDFBox / Tika | [org.apache.poi:poi-ooxml](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Forg.apache.poi%2Fpoi-ooxml%2F5.5.1) 等 | `5.5.1` | 负责本地 Office 文档（DOCX/XLSX/XLS/PPTX）与文字版 PDF 的结构化提取 |
| **HTML 与 MD** | jsoup / flexmark | [org.jsoup:jsoup](https://www.google.com/search?q=https%3A%2F%2Fcentral.sonatype.com%2Fartifact%2Forg.jsoup%2Fjsoup%2Fversions) / [flexmark-html2md-converter](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Fcom.vladsch.flexmark%2Fflexmark-html2md-converter%2F0.64.8) | `1.23.2` / `0.64.8` | 负责 HTML 安全消毒、DOM 表格转 Markdown 以及 Markdown AST 结构解析 |
| **持久层与存储** | MyBatis / PostgreSQL | [org.mybatis.spring.boot:mybatis-spring-boot-starter](https://www.google.com/search?q=https%3A%2F%2Fgithub.com%2Fmybatis%2Fspring-boot-starter%2Freleases) | `3.0.5` / `PG 16` | 单实例集成 `pgvector`、`zhparser`、`pg_trgm`，由 MyBatis 执行三路召回与批量写入 |

## 2. 多源文档解析层：从异构文件到统一结构化序列

### 2.1 结构化中间表示契约（Block IR）

许多初级 RAG 系统直接使用通用文本抽取器（例如 Spring AI 提供的 [TikaDocumentReader](https://www.google.com/search?q=https%3A%2F%2Fdocs.spring.io%2Fspring-ai%2Freference%2Fapi%2Fetl-pipeline.html)）读取全部文档。然而，`TikaDocumentReader` 底层默认采用 Apache Tika 的 `BodyContentHandler`，会将整篇文档压平为单一纯文本字符串，彻底抹平章节标题层级、表格行列边界以及 PDF 页码元数据。一旦解析层丢失结构信息，下游分块层就只能退化为按固定字符数机械截断。

为此，系统将解析层的输出契约固定为有序的结构化中间表示 **`Block`**：

- `type`：块类型枚举，包含 `HEADING`（标题）、`PARAGRAPH`（段落）、`TABLE`（表格）、`LIST`（列表）、`CODE`（代码块）、`SLIDE`（幻灯片）。
- `text`：块文本内容（其中 `TABLE` 统一序列化为标准 Markdown 管道表格文本）。
- `level`：标题层级 `1..6`（仅 `HEADING` 有效，非标题块固定为 `0`）。
- `meta`：结构化定位元数据（包含来源文件名 `file_name`、PDF 页码 `page_number`、Excel 工作表名 `sheet_name`、PPT 幻灯片序号 `slide_number` 等），供问答阶段精确回指原文位置。

解析契约严格保证两点：**其一，输出的 `Block` 列表顺序即文档的自然阅读顺序；其二，表格、代码块与单页幻灯片在解析阶段始终作为原子块（Atomic Block）完整输出，绝不在解析层切碎。**

### 2.2 本地多格式解析器分工

为确保每类格式都能准确还原上述 `Block` 结构，本地解析路径摒弃“一刀切”的纯文本抽取，按文件格式特征采用针对性的底层解析组件：

| 文档格式 | 本地解析组件 | 结构化提取与元数据还原策略 |
| :--- | :--- | :--- |
| **PDF（文字版）** | Apache PDFBox | 逐页提取文本流并将页码写入 `meta.page_number`；结合行字号跳变、加粗属性与单行短文本特征启发式识别章节标题。 |
| **DOCX** | Apache POI（`XWPF`） | 按序遍历 `XWPFDocument.getBodyElements()`：`XWPFParagraph` 根据 `Heading 1..6` 样式或大纲级别映射 `level`；`XWPFTable` 直接读取单元格矩阵并序列化为原子 Markdown `TABLE` 块。 |
| **DOC（二进制旧版）** | Apache Tika（XHTML 模式） | 调用 `AutoDetectParser` 配合 `ToXMLContentHandler` 导出结构化 XHTML，再交由 `jsoup` + `flexmark` 转换为 `Block` 序列，完整保留旧版 Word 表格与标题。 |
| **XLSX / XLS** | Apache POI（`SS`） | 利用 `poi-ooxml` 传递包含的核心包，通过 `WorkbookFactory.create()` 按文件头魔数自动识别 `.xls` 与 `.xlsx`；每个工作表产出一个原子 `TABLE` 块（首行为表头，`sheet_name` 写入 `meta`）。 |
| **PPTX** | Apache POI（`XSLF`） | 遍历 `XMLSlideShow`：每页幻灯片产出一个 `SLIDE` 块，携带 `slide_number`，优先提取标题占位符作为块标题，正文按文本框阅读顺序拼接。 |
| **HTML** | `jsoup` + `flexmark` | 先经 [org.jsoup:jsoup（1.23.2）](https://www.google.com/search?q=https%3A%2F%2Fcentral.sonatype.com%2Fartifact%2Forg.jsoup%2Fjsoup%2Fversions) 清除脚本与危险属性以防范 XSS，再由 [flexmark-html2md-converter](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Fcom.vladsch.flexmark%2Fflexmark-html2md-converter%2F0.64.8)（启用 `flexmark-ext-tables` 表格扩展）转为 Markdown 后统一解析。 |
| **TXT / MD** | `flexmark` AST | 解析 Markdown 抽象语法树，按 `#` 标题、围栏代码块、列表与管道表格直接映射为对应类型的 `Block`。 |

### 2.3 本地与云端自适应分流（LlamaParse 协同）

根据 [LlamaParse 官方支持格式规范](https://www.google.com/search?q=https%3A%2F%2Fdevelopers.llamaindex.ai%2Fllamaparse%2Fgeneral%2Fsupported_document_types%2F)，LlamaParse 原生支持 **4 大类共 130+ 种文件格式**，包括版面与办公文档（`pdf`、`docx`、`doc`、`pptx`、`ppt`、`rtf`、`epub`、`pages`、`wps` 等）、电子表格（`xlsx`、`xls`、`csv`、`tsv`、`ods`、`et` 等）、图片与网页（`jpg`、`png`、`webp`、`tiff`、`svg`、`html` 等）以及音视频（`mp3`、`mp4`、`wav`、`m4a` 等）。

既然云端 LlamaParse 几乎全格式通吃，**为什么系统还要保留本地解析器，并通过 `kb.parse.cloud-types` 白名单控制云端分流？** 核心原因在于**成本、延迟与解析确定性的工程权衡**：

- **纯文本与标记语言（`TXT / MD / HTML`）**：本身即具备清晰的字符流或标签结构，本地通过 `flexmark` 与 `jsoup` 毫秒级即可完成零成本、100% 确定性的无损转换；若上传云端轮询，既白白消耗按页配额，又凭空增加数秒网络 I/O 延迟。
- **规整电子表格（`XLSX / XLS`）**：本地 Apache POI 直接遍历单元格矩阵生成 Markdown 表格速度快且零配额消耗；仅当遇到包含复杂非对称合并表头或内嵌图表的复杂报表时，才需按需切入云端解析。
- **复杂版面与视觉重度文档（`PDF / DOC / DOCX / PPT / PPTX`）**：这类文档普遍存在双栏排版、无线框跨页表格、图文混排或旧版二进制结构，正是本地 PDFBox/Tika 的短板而云端版面视觉模型最擅长处理的领域。

基于这一权衡，系统引入 [LlamaParse 官方 Java SDK（ai.llamaindex:llama-cloud）](https://www.google.com/search?q=https%3A%2F%2Fdevelopers.llamaindex.ai%2Fllamaparse%2Fquickstart%2F) 并设计三种解析路由模式（`kb.parse.mode`）：

- **`AUTO`（默认按需分流模式）**：当 `kb.parse.cloud.enabled=true`、上传文件属于 `kb.parse.cloud-types` 白名单（默认配置为 `PDF,DOC,DOCX,PPT,PPTX`，亦可按需追加 XLSX 或图片格式）且已注入 `LLAMA_CLOUD_API_KEY` 时，自动通过 [LlamaCloudOkHttpClient](https://www.google.com/search?q=https%3A%2F%2Fgithub.com%2Frun-llama%2Fllama-parse-java) 提交异步解析任务并轮询获取按页 Markdown 结果（`expand=["markdown"]`）；其余确定性格式直接走本地毫秒级解析。
- **`CLOUD` / `LOCAL`（显式指定模式）**：支持在单次上传时指定 `CLOUD` 将任意 LlamaParse 支持的格式强制送往云端深度解析，或指定 `LOCAL` 强制走本地离线解析；`CLOUD` 启用但未配置密钥时启动期失败，`enabled=false` 时跳过云端客户端 Bean 装配，确保纯本地零依赖启动。
- **异常无缝回退**：在 `AUTO` 模式下，若云端服务超时、配额耗尽或返回空结果，解析层自动捕获异常并回退至本地解析器兜底，同时将回退事件记入文档日志；无论走哪条路径，最终均输出满足同一契约的 `Block` 序列。
- **上传边界**：上传接口只做大小校验，不限制扩展名；本地解析器覆盖常见办公/文本格式，其他格式需启用云端解析并通过 `CLOUD`（或加入 `cloud-types`）处理。

### 2.4 疑似扫描件检测与显式阻断

在未启用云端 OCR 的纯本地模式下（或云端回退本地后），若用户上传了纯图片扫描版 PDF，本地解析器提取出的文本往往为空或仅有零星乱码。如果系统静默将其标记为“处理成功”，会导致文档在库中显示正常却永远无法被检索命中，极大增加排障成本。

因此，解析层在产出 `Block` 序列后立即执行**有效文本密度校验**：统计全文档去除空白符后的有效字符数，若整篇文档有效字符低于阈值（默认 `50` 字），或 PDF 文档平均每页有效字符低于 `10` 字，系统立即判定为疑似扫描件，将文档状态置为 `NEEDS_OCR`，写入明确的可操作提示（“未检测到有效文本层，疑似扫描件；当前未启用 OCR，请上传文字版文档或开启云端解析”），并直接终止后续分块与向量化流程，不产生任何无效切片。

## 3. 两段式结构分块：兼顾检索精度与生成上下文

向量检索天然偏好语义单一的短文本（250~400 Token），切片过长会导致核心特征被背景噪声稀释；而大模型生成回答却依赖上下文完整的长段落（800~1500 Token），切片过碎会导致指代不明与条件缺失。系统采用**父子两段式结构分块（Parent-Child Chunking）**化解这一矛盾。

### 3.1 父子块职责与尺寸边界

| 维度 | 父块（Parent Chunk） | 子块（Child Chunk） |
| :--- | :--- | :--- |
| **核心职责** | 作为生成模型的完整阅读上下文与引用编号单元 | 作为向量化与三路混合检索的最小命中单元 |
| **目标尺寸** | `800 ~ 1500 Token`（默认上限 `1200`，下限 `200`） | `250 ~ 400 Token`（默认上限 `320`） |
| **切分依据** | 文档结构边界：一/二级标题、独立表格、独立幻灯片 | 父块内部边界：按自然段落与句子标点滑窗切分 |
| **索引策略** | **仅存原文文本，不建向量与全文索引** | **建立 1024 维 HNSW 向量索引、中文全文索引与 Trigram 索引** |
| **滑窗重叠** | 无重叠（`0 Token`） | 相邻子块重叠 `48 Token` |

### 3.2 父块切分算法：按章节聚合与表格独占

父块切分器顺序消费解析层产出的 `Block` 序列，核心聚合逻辑如下：

```text
1. 遇到 level <= 2 的 HEADING 块 → 结束上一个父块，开启新父块，并将该标题记录为新父块 title
2. 遇到普通文本块（PARAGRAPH / LIST / CODE / level >= 3 的子标题）→ 尝试累加进当前父块
3. 若累加后将超过 chunk.parent.max-tokens（1200 Token）：
   - 若待加入块是 TABLE 或 SLIDE → 先结束当前文本父块，让该原子块单独开启新父块
   - 若待加入块是普通文本块 → 在当前位置切断，开启新文本父块并沿用最近一次章节标题
4. 文档遍历结束时，若尾块不足 chunk.parent.min-tokens（200 Token）→ 并入前一个同类型文本父块
```

针对表格（`TABLE`）与幻灯片（`SLIDE`），系统实施严格的**独占父块与表头重放规则**：

- **正文与表格隔离**：任何一个父块内绝不混入表格或幻灯片以外的普通正文，正文段落不会跨过表格继续拼接，防止表格数据污染正文语流。
- **超长表格按行拆块并重放表头**：当单张大表超过父块 Token 上限时，按行窗口拆分为多个纯表格父块；由于脱离表头的数据行毫无语义，每个拆分出的表父块都会**自动复制携带原始 Markdown 表头行与分隔行**。
- **幻灯片按页成块**：单页幻灯片默认独占一个父块；若单页文本超限，则按幻灯片内部文本框边界拆分。

### 3.3 子块切分算法：父块内局部滑窗

子块切分器在每个父块内部独立运行，**子块边界绝不跨越父块**：

```text
遍历每个父块：
1. 若父块为原子块（TABLE / SLIDE）且总 Token 数 <= chunk.child.max-tokens（320 Token）：
   → 整个父块直接作为 1 个子块输出（父子比 1:1）
2. 若父块为超长表格（> 320 Token）：
   → 按表格行滑窗切分子块，每个子块同样前置挂载 Markdown 表头行
3. 若父块为普通文本块：
   → 按「自然段落 (\n\n) → 中文/英文句子标点 (。！？；.!?\n)」两级粒度顺序累积
   → 累积达到 320 Token 时落盘一个子块，并回退保留末尾 48 Token 作为下一个子块的滑窗重叠前缀
```

### 3.4 精准 Token 计量与上下文前缀增强

在中文场景下，单个汉字的信息密度远高于单个英文字符，按字符数切分会系统性导致中英文切片失衡。系统将 Token 计量收敛至 `TokenCounter` 接口，默认直接复用 [spring-ai-starter-model-openai](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Forg.springframework.ai%2Fspring-ai-starter-model-openai) 传递依赖中自带的 `com.knuddels:jtokkit` 分词库（基于 `CL100K_BASE` / `O200K_BASE` 编码），无需引入任何额外依赖即可实现精准分词计数；同时保留“中文字符 × 1.5 + 其它字符 × 0.25”的零开销估算实现作为降级兜底。

此外，针对跨段指代消解难题（如子块开头出现“该方案”“上述指标”而丢失主语），系统支持可选的**上下文前缀增强（Contextual Retrieval）**机制：参考 [Anthropic Contextual Retrieval 架构实践](https://www.google.com/search?q=https%3A%2F%2Fwww.anthropic.com%2Fengineering%2Fcontextual-retrieval)，在入库阶段利用轻量模型根据文档标题与章节摘要为子块生成 50~100 Token 的背景前缀（例如：“本段出自《XX 系统规范》第 3 章，主要阐述…”），独立保存在子块的 `context_prefix` 字段中。向量化与生成全文检索索引时将 `context_prefix` 与 `content` 拼接后输入，从而在不污染原文展示的前提下显著降低 Top-20 检索漏召率。该特性默认关闭（`context-enrichment.enabled=false`），可在生产环境中仅针对“不含小标题且以代词开头”的子块按需开启以控制模型调用成本。

## 4. 单机一体化存储与高可靠摄入流水线

### 4.1 为什么基于单实例 PostgreSQL 统一承载三路索引？

在单机部署约束下，同时维护关系型数据库、独立向量库（如 Milvus）和搜索引擎（如 Elasticsearch）会带来高昂的内存开销与跨组件数据一致性难题。PostgreSQL 16 生态能够在一个数据库实例内原生集齐三路检索所需的全部索引能力：

- **向量检索（`pgvector`）**：支持 1024 维 `vector` 类型与高性能 HNSW 图索引（余弦距离 `vector_cosine_ops`）。
- **中文全文检索（[zhparser](https://www.google.com/search?q=https%3A%2F%2Fgithub.com%2Famutu%2Fzhparser)）**：基于 SCWS 分词引擎的 PostgreSQL 中文全文检索扩展，可将中文长句切分为词元并生成 `tsvector` 倒排 GIN 索引。
- **模糊匹配（`pg_trgm`）**：PostgreSQL 内置的三元组（Trigram）扩展，通过 `gin_trgm_ops` 索引高效支撑产品型号串、英文缩写与局部错别字的相似度检索。

容器化部署时，可基于官方 `pgvector/pgvector:pg16` 镜像编译安装 `scws` 与 `zhparser`（或基于 [zhparser/zhparser:bookworm-16 镜像](https://www.google.com/search?q=https%3A%2F%2Fhub.docker.com%2Fr%2Fabcfy2%2Fzhparser) 叠加安装 `pgvector`）。**需要特别注意一项关键底层约束**：根据 [PostgreSQL pg\_trgm 中文模糊查询机制](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.aliyun.com%2Farticle%2F672293)，`pg_trgm` 依赖操作系统的字符分类函数提取三元组，若数据库初始化时 `lc_ctype` 为默认的 `"C"` 或 `"POSIX"`，`pg_trgm` 会完全忽略所有非 ASCII 中文字符。因此容器启动时必须显式指定 UTF-8 区域设置：

```yaml
environment:
  POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale=C.UTF-8"
```

### 4.2 持久层选型：为什么弃用 `PgVectorStore` 而采用 MyBatis 直写？

Spring AI 提供了开箱即用的 [spring-ai-starter-vector-store-pgvector（PgVectorStore）](https://www.google.com/search?q=https%3A%2F%2Fdocs.spring.io%2Fspring-ai%2Freference%2Fapi%2Fvectordbs%2Fpgvector.html)，但在生产级父子分块架构中，系统明确不引入 `PgVectorStore`，而是采用 **`EmbeddingModel` + MyBatis** 直接操作数据库，原因在于两处底层机制冲突：

1. **表结构硬编码与外键非空冲突**：[PgVectorStore 官方 API 规范](https://www.google.com/search?q=https%3A%2F%2Fdocs.spring.io%2Fspring-ai%2Fdocs%2Fcurrent%2Fapi%2Forg%2Fspringframework%2Fai%2Fvectorstore%2Fpgvector%2FPgVectorStore.html)硬编码要求目标表包含 `id (uuid)`、`content (text)`、`metadata (jsonb)`、`embedding (vector)` 四列。而在父子分块模型中，`child_chunks` 表必须包含 `parent_id`、`document_id`、`seq` 等带有 `NOT NULL` 与 `ON DELETE CASCADE` 外键约束的业务列。若先调用 `PgVectorStore.add()` 插入向量行，会直接触发非空约束报错。
2. **自定义多路检索 SQL 需求**：查询侧需要并行执行向量距离、中文全文 `ts_rank_cd` 与 Trigram 相似度三路查询，并下推多对多的知识库关联过滤子查询，`PgVectorStore` 单一的 `similaritySearch()` 接口无法满足复杂 SQL 编排。

因此，系统仅通过 [spring-ai-starter-model-openai](https://www.google.com/search?q=https%3A%2F%2Fmvnrepository.com%2Fartifact%2Forg.springframework.ai%2Fspring-ai-starter-model-openai) 注入 `EmbeddingModel` 获取 `float[]` 向量数组；解析分块阶段由 MyBatis 单事务写入父块与空向量子块，手动向量化阶段再按批写回 `embedding`，同样通过 `::vector` 转换完成。

### 4.3 完整数据库 Schema 与自动化触发器设计

系统核心数据模型由三张内容表（`documents`、`parent_chunks`、`child_chunks`）与两张知识库分类表（`knowledge_bases`、`document_knowledge_bases`）构成。以下初始化 SQL 脚本具备完整幂等性，可直接用于生产环境建表：

```sql
-- ============================================================================
-- 1. 启用核心扩展（要求数据库实例初始化时 LC_CTYPE 为 C.UTF-8 或 en_US.utf8）
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS zhparser;

-- ============================================================================
-- 2. 配置 zhparser 中文全文检索词典映射
-- 覆盖名词(n)、动词(v)、形容词(a)、习语(i)、叹词/英文数字词元(e)、成语(l)、简称(j)、
-- 区别词(b)、副词(d)、方位词(f)、数词(m)、量词(q)、代词(r)、处所词(s)、时间词(t)、
-- 自定义词条/未知专名(x)、状态词(z)，过滤纯标点符号词性(u/w)
-- ============================================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zh_cfg') THEN
        CREATE TEXT SEARCH CONFIGURATION zh_cfg (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION zh_cfg
            ADD MAPPING FOR n,v,a,i,e,l,j,b,d,f,m,q,r,s,t,x,z WITH simple;
    END IF;
END
$$;

-- ============================================================================
-- 3. 通用 updated_at 自动刷新触发器函数
-- ============================================================================
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 4. 文档主表：记录解析模式、状态机与父子切片统计
-- ============================================================================
CREATE TABLE IF NOT EXISTS documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name     VARCHAR(500) NOT NULL,
    file_type     VARCHAR(20)  NOT NULL,
    file_size     BIGINT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                  CHECK (status IN ('PROCESSING', 'CHUNKED', 'INDEXING', 'INDEX_FAILED',
                                    'COMPLETED', 'NEEDS_OCR', 'FAILED')),
    parse_mode    VARCHAR(20)  NOT NULL DEFAULT 'AUTO'
                  CHECK (parse_mode IN ('AUTO', 'LOCAL', 'CLOUD')),
    error_message TEXT,
    parent_count  INT NOT NULL DEFAULT 0,
    child_count   INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents (status);

CREATE OR REPLACE TRIGGER trg_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================================
-- 5. 父块表：仅存储完整上下文文本与结构元数据，不建向量索引
-- ============================================================================
CREATE TABLE IF NOT EXISTS parent_chunks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq         INT  NOT NULL,
    title       VARCHAR(500),
    content     TEXT NOT NULL,
    token_count INT  NOT NULL DEFAULT 0,
    meta        JSONB NOT NULL DEFAULT '{}'::jsonb, -- 页码范围 / 表名 / 幻灯片范围
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_parent_doc_seq UNIQUE (document_id, seq)
);

-- ============================================================================
-- 6. 子块表：三路混合检索的核心单元（向量 + 中文全文 + Trigram）
-- ============================================================================
CREATE TABLE IF NOT EXISTS child_chunks (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id      UUID NOT NULL REFERENCES parent_chunks(id) ON DELETE CASCADE,
    document_id    UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq            INT  NOT NULL,
    content        TEXT NOT NULL,
    context_prefix TEXT,                            -- 上下文增强前缀，可为空
    embedding      vector(1024),                   -- 手动向量化前为空；完成后写入
    search_content tsvector,                        -- 由触发器自动维护的中文全文向量
    token_count    INT  NOT NULL DEFAULT 0,
    meta           JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_child_parent_seq UNIQUE (parent_id, seq)
);

-- HNSW 向量余弦距离索引
CREATE INDEX IF NOT EXISTS idx_child_embedding
    ON child_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 中文全文检索 GIN 索引
CREATE INDEX IF NOT EXISTS idx_child_search
    ON child_chunks USING gin (search_content);

-- Trigram 模糊匹配 GIN 索引
CREATE INDEX IF NOT EXISTS idx_child_trgm
    ON child_chunks USING gin (content gin_trgm_ops);

-- 关联过滤与邻接窗口查询索引
CREATE INDEX IF NOT EXISTS idx_child_doc ON child_chunks (document_id);

-- 自动维护子块中文全文检索向量（拼接 context_prefix 与 content）
CREATE OR REPLACE FUNCTION child_chunks_tsvector_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.search_content :=
        setweight(to_tsvector('zh_cfg', COALESCE(NEW.context_prefix, '')), 'B') ||
        setweight(to_tsvector('zh_cfg', COALESCE(NEW.content, '')), 'A');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_child_chunks_tsvector
    BEFORE INSERT OR UPDATE OF content, context_prefix ON child_chunks
    FOR EACH ROW EXECUTE FUNCTION child_chunks_tsvector_trigger();

-- ============================================================================
-- 7. 知识库分类表与文档多对多关联表
-- ============================================================================
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    color       VARCHAR(7)   NOT NULL DEFAULT '#3b82f6',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_name ON knowledge_bases (name);

CREATE OR REPLACE TRIGGER trg_knowledge_bases_updated_at
    BEFORE UPDATE ON knowledge_bases
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TABLE IF NOT EXISTS document_knowledge_bases (
    document_id       UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (document_id, knowledge_base_id)
);

CREATE INDEX IF NOT EXISTS idx_dkb_kb  ON document_knowledge_bases (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_dkb_doc ON document_knowledge_bases (document_id);
```

**Schema 关键设计亮点**：

- **外键级联清理**：`parent_chunks` 与 `child_chunks` 均配置 `ON DELETE CASCADE`，删除文档或重建索引时只需删除 `documents` 主表记录，数据库自动清理所有关联父子块，无需业务层编写多步删除逻辑。
- **子块冗余 `document_id` 与复合唯一约束**：子块直接冗余 `document_id`，按知识库过滤时无需 `JOIN parent_chunks`；同时 `UNIQUE (parent_id, seq)` 约束天然充当了降级裁剪时“按 `parent_id` 与相邻 `seq` 加载左右邻接子块”的复合 B-Tree 索引。
- **主次权重全文触发器**：`child_chunks_tsvector_trigger` 对子块正文赋予 `'A'` 级最高权重，对可选的上下文增强前缀赋予 `'B'` 级次级权重，使 `ts_rank_cd` 打分天然优先匹配正文词元。

### 4.4 异步摄入与手动向量化流水线

文档摄入采用“解析分块”和“向量化”两阶段设计，在保障接口高响应速度的同时，让用户在建立索引前检查并修正切片：

1. **上传文件自有转存**：Web 容器处理 `MultipartFile` 时创建的底层临时文件会在 HTTP 请求返回瞬间被自动清理。因此上传接口在落盘 `documents`（状态 `PROCESSING`）后，第一时间将文件流拷贝至应用自管临时目录，再提交异步线程池并立即向前端返回文档 ID；异步任务无论成功或异常，均在 `finally` 块中彻底清理自有临时文件。
2. **解析分块与空向量落库**：异步任务完成解析、父子分块后，在单个本地事务内批量 `INSERT` `parent_chunks` 与 `child_chunks`（`embedding = NULL`），回填计数并将状态置为 `CHUNKED`。此时文档不参与检索，用户可在前端检查、编辑、删除、合并或拆分切片。
3. **手动触发微批向量化**：用户确认切片后调用 `POST /api/documents/{id}/index`，状态 CAS 为 `INDEXING`。后台只加载 `embedding IS NULL` 的子块，严格按 `embedding.batch-size = 10` 切片调用 `EmbeddingModel`，每批成功后在事务内写回向量；全部完成置为 `COMPLETED`，失败置为 `INDEX_FAILED` 并保留已成功批次，重试只补空向量。`COMPLETED` 文档编辑切片时自动重嵌受影响块，`CHUNKED` / `INDEX_FAILED` 阶段编辑只保存文本。

## 5. 三路混合检索、重排与父块归并

### 5.1 查询改写与多通道输入分离

检索入口支持可选的查询改写（Query Rewrite）或假设性文档生成（HyDE，默认关闭 `search.hyde-enabled=false`）。当启用改写或 HyDE 时，**生成的扩展文本仅作为向量检索通道的输入**；而中文全文检索与 Trigram 模糊匹配始终使用用户的**原始提问文本**，确保用户输入的精确型号、专有名词与字面关键词不被大模型改写丢失。

### 5.2 三路并行召回与索引友好型 SQL 设计

系统利用 `CompletableFuture` 并行发起三路子块召回，任一通道超时或异常仅丢弃该路结果，不中断整体问答。为了确保三路查询在高并发下均能精准命中各自的 HNSW 与 GIN 索引（避免退化为全表顺序扫描），MyBatis Mapper 中的查询 SQL 严格遵循以下写法规范：

```sql
-- ============================================================================
-- 通道 1：向量语义检索（默认 Top 20）
-- 注意：ORDER BY 必须直接使用 <=> 距离运算符升序排列，才能触发 HNSW 索引扫描；
-- 若在 ORDER BY 中写 (1 - (embedding <=> ...)) DESC，会导致 HNSW 索引失效！
-- ============================================================================
SELECT
    c.id,
    c.parent_id,
    c.document_id,
    c.seq,
    c.content,
    c.token_count,
    c.meta,
    1 - (c.embedding <=> #{queryVector}::vector) AS raw_score
FROM child_chunks c
WHERE 1 = 1
  -- 未完成手动向量化的文档整篇不参与检索
  AND c.embedding IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM documents d
      WHERE d.id = c.document_id AND d.status = 'COMPLETED'
  )
  -- 若传入了 knowledgeBaseIds，下推关联过滤
  AND c.document_id IN (
      SELECT dkb.document_id
      FROM document_knowledge_bases dkb
      WHERE dkb.knowledge_base_id IN (#{kbId1}, #{kbId2})
  )
ORDER BY c.embedding <=> #{queryVector}::vector ASC
LIMIT #{vectorTopK};

-- ============================================================================
-- 通道 2：中文全文检索（默认 Top 20）
-- 注意：使用 websearch_to_tsquery 而非 to_tsquery，防止自然语言提问中包含
-- 问号、括号、连字符等特殊字符时引发 tsquery 语法解析报错
-- ============================================================================
SELECT
    c.id,
    c.parent_id,
    c.document_id,
    c.seq,
    c.content,
    c.token_count,
    c.meta,
    ts_rank_cd(c.search_content, q.query) AS raw_score
FROM child_chunks c,
     websearch_to_tsquery('zh_cfg', #{rawQuery}) q
WHERE c.search_content @@ q.query
  AND c.embedding IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM documents d
      WHERE d.id = c.document_id AND d.status = 'COMPLETED'
  )
  AND c.document_id IN (
      SELECT dkb.document_id
      FROM document_knowledge_bases dkb
      WHERE dkb.knowledge_base_id IN (#{kbId1}, #{kbId2})
  )
ORDER BY raw_score DESC
LIMIT #{fulltextTopK};

-- ============================================================================
-- 通道 3：Trigram 模糊匹配（默认 Top 10）
-- 注意：必须在同一事务内先执行 SET LOCAL 调低阈值，且 WHERE 子句必须使用 % 运算符
-- 才能命中 gin_trgm_ops 索引；若仅写 WHERE similarity(...) >= 0.2 会退化为全表扫描！
-- ============================================================================
SET LOCAL pg_trgm.similarity_threshold = 0.2;

SELECT
    c.id,
    c.parent_id,
    c.document_id,
    c.seq,
    c.content,
    c.token_count,
    c.meta,
    similarity(c.content, #{rawQuery}) AS raw_score
FROM child_chunks c
WHERE c.content % #{rawQuery}
  AND c.embedding IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM documents d
      WHERE d.id = c.document_id AND d.status = 'COMPLETED'
  )
  AND c.document_id IN (
      SELECT dkb.document_id
      FROM document_knowledge_bases dkb
      WHERE dkb.knowledge_base_id IN (#{kbId1}, #{kbId2})
  )
ORDER BY c.content <-> #{rawQuery} ASC
LIMIT #{fuzzyTopK};
```

### 5.3 RRF 倒数秩融合与云端 Rerank 重排

三路召回各自产出的分数量纲截然不同（余弦相似度、词频覆盖度、三元组重合率），直接加权求和极易因某一通道分值偏大而主导排序。系统采用两阶段精排：

1. **RRF（Reciprocal Rank Fusion）融合**：仅依据子块在各通道内的排名 `rank`（从 1 开始）计算融合得分 $\text{Score}_{\text{RRF}}(d) = \sum_{m \in M} \frac{1}{k + \text{rank}_m(d)}$（默认平滑常数 $k = 60$），按 `id` 去重后截取前 **50 个候选子块**。
2. **云端 Rerank 交叉重排**：使用 Spring `RestClient` 调用 [阿里云百炼 qwen3-rerank 模型](https://www.google.com/search?q=https%3A%2F%2Fhelp.aliyun.com%2Fzh%2Fmodel-studio%2Frerank)，免 SDK 发送 HTTP 请求至 `POST https://dashscope.aliyuncs.com/compatible-api/v1/reranks`（注意：百炼重排接口路径前缀为 `/compatible-api/v1`，与 Embedding 的 `/compatible-mode/v1` 路径不同，配置中需独立指定完整 URL）。请求传入 `query`、50 个候选子块文本数组 `documents` 与 `top_n`（默认 `15`），根据返回的 `relevance_score` 截取前 15 个高相关子块。若重排接口超时或异常，自动降级为直接取 RRF 前 15 个子块，保障问答服务高可用。

### 5.4 父块上卷归并与动态上下文预算裁剪

拿到重排后的 Top-N 子块后，系统执行**“子块上卷归并父块”**的核心转换：

```text
1. 将命中的 Top-N 子块按 parent_id 分组聚合：
   - 同一父块下若命中多个子块，父块仅保留一份，并将命中的子块列表及得分挂入该父块引用明细
2. 计算每个候选父块的综合排序得分：
   - parent_score = max(归属子块 rerank 得分) + α * (命中子块数 - 1)，默认 α = 0.05
3. 按 parent_score 降序遍历候选父块，依次扣减可用上下文 Token 预算：
   - 可用预算 = chat.max-context-tokens (6000) - 系统提示词 - 用户提问 - chat.output-reserve-tokens (1000)
   - 若当前父块 token_count <= 剩余预算 → 完整纳入该父块文本
   - 若当前父块 token_count > 剩余预算 → 触发优雅降级：改取「该父块内得分最高的命中子块 + 左右相邻子块 (seq ± 1)」拼接窗口
   - 若连降级后的局部子块窗口也超出剩余预算 → 立即停止追加，绝不塞入被硬截断的残缺文本
```

**父块打分公式的量纲设计**：`qwen3-rerank` 输出的 `relevance_score` 位于 `[0, 1]` 区间，而命中子块数为正整数 `1, 2, 3...`。通过引入小权重系数 $\alpha = 0.05$，既能在最高子块得分相近时优先排列多点命中的核心章节，又能防止“命中 2 个低分边缘子块的父块”反超“命中 1 个高分精准子块的父块”。

### 5.5 引用严格对齐与 Prompt 拼接边界

在生成阶段，由于送入大模型阅读的是归并后的**父块文本**（或降级窗口），提示词中的上下文编号严格按父块顺序标记为 `[1]`、`[2]`、`[3]`；同时，后端在 SSE 流式返回的元数据中，为每个父块编号附带其底层命中的子块序号、页码/工作表名与重排得分，支持前端点击角标后直接高亮定位至具体子块段落。

Prompt 渲染器使用 `StringBuilder` 直接拼接模板、资料和问题，不做占位符替换，因此不会发生 `{question}` / `{context}` 被二次解析的问题。`<context>` 是提示词中的资料边界，不是安全沙箱：正文未转义 `</context>`，文档内嵌的指令文本仍可能影响模型行为；当前实现不宣称对提示词注入提供强隔离。

## 6. 检索质量评测体系与生产参数基线

### 6.1 全量生产配置参数基线

系统将所有可调超参收敛至统一配置前缀下，生产环境推荐基线如下：

| 模块类别 | 配置项名称 | 推荐默认值 | 参数作用与约束说明 |
| :--- | :--- | :--- | :--- |
| **解析层** | `kb.parse.mode` | `AUTO` | 解析路由模式：`AUTO`（按白名单分流）/ `LOCAL`（强制本地）/ `CLOUD`（强制云端） |
| **解析层** | `kb.parse.cloud-types` | `PDF,DOC,DOCX,PPT,PPTX` | `AUTO` 模式下优先分流至 LlamaParse 的格式白名单（可按需扩展图片或复杂表格格式） |
| **解析层** | `kb.parse.min-text-chars` | `50` | 疑似扫描件拦截阈值（整篇有效字符 `< 50` 或 PDF 页均 `< 10` 即阻断） |
| **分块层** | `kb.chunk.parent.max-tokens` | `1200` | 父块聚合最大 Token 上限 |
| **分块层** | `kb.chunk.parent.min-tokens` | `200` | 父块尾块最小合并阈值 |
| **分块层** | `kb.chunk.child.max-tokens` | `320` | 子块滑窗最大 Token 上限 |
| **分块层** | `kb.chunk.child.overlap-tokens` | `48` | 相邻子块滑窗重叠 Token 数 |
| **向量化** | `kb.embedding.batch-size` | `10` | **启动校验 1~10**，对齐百炼 `text-embedding-v4` 单次请求文本数上限 |
| **检索层** | `kb.search.vector-top-k` | `20` | 向量余弦检索通道召回子块数 |
| **检索层** | `kb.search.fulltext-top-k` | `20` | `zhparser` 中文全文检索通道召回子块数 |
| **检索层** | `kb.search.fuzzy-top-k` | `10` | `pg_trgm` 模糊匹配通道召回子块数 |
| **检索层** | `kb.search.fuzzy-similarity-threshold` | `0.2` | `pg_trgm` 会话级相似度阈值（针对中文字符密度由默认 `0.3` 调低至 `0.2`） |
| **检索层** | `kb.search.rrf-k` | `60` | RRF 倒数秩融合平滑常数 |
| **检索层** | `kb.search.rerank-top-n` | `15` | `qwen3-rerank` 重排后保留进入父块归并的子块数 |
| **检索层** | `kb.search.parent-hit-bonus-weight` | `0.05` | 父块归并时每多命中一个子块的加分权重 $\alpha$ |
| **检索层** | `kb.search.hyde-enabled` | `false` | 是否对向量通道启用 HyDE 假设性文档改写 |
| **生成层** | `kb.chat.max-context-tokens` | `6000` | 送入生成模型的检索上下文最大 Token 预算 |
| **生成层** | `kb.chat.output-reserve-tokens` | `1000` | 为生成模型输出预留的 Token 预算 |
| **增强层** | `kb.context-enrichment.enabled` | `false` | 是否在摄入阶段启用子块上下文前缀生成（Contextual Retrieval） |
| **安全层** | `kb.security.api-key` | 环境变量注入 | 接口鉴权密钥（非空时强制校验 `X-API-Key` 请求头，自动放行 `OPTIONS` 预检请求） |

### 6.2 对照评测体系与工程总结

为避免凭主观直觉调参，系统采用**金标评测集驱动（Evaluation-Driven）**的参数迭代方法：从 5~10 篇涵盖多级章节、跨页数据表与讲义幻灯片的真实业务文档中，构建 **30 条标准问答对**，每条均明确标注金标答案所在的文档与原始段落。围绕以下四项核心指标开展对照实验（对比“无父子结构的普通递归分块”、“父子两段式分块”与“父子分块 + 上下文前缀增强”）：

1. **Recall@15（父块召回覆盖率）**：重排后归并出的父块集合完整包含金标答案段落的比例。
2. **端到端答案准确率**：基于 0/1 二值人工盲测（30 条约耗时 20 分钟），同时考核事实无幻觉与引用角标 `[1][2]` 的对应准确性。
3. **文档父子切片比**：监控单文档平均父子块比例（健康区间为 `1:3 ~ 1:5`），排查是否存在过度切碎的尾块。
4. **分阶段延迟分布**：分别采集三路并行召回耗时、Rerank 网络耗时与流式首字延迟（TTFT）。

总结而言，这套架构的核心思想在于**“把复杂性留在数据结构与 SQL 编排内，把简洁性留给基础设施”**：通过 `Block` 结构化契约守住解析质量底线，用“子块检索 + 父块生成”兼顾查准率与上下文连贯度，并充分挖掘单实例 PostgreSQL 在向量、中文分词与模糊匹配上的融合潜力，使轻量级单机部署同样能够交付生产级的 RAG 问答体验。