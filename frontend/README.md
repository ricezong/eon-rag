# Eon RAG Web

Eon RAG 的前端模块：Vue 3.5 + TypeScript + Vite + Tailwind CSS 4，DeepSeek Harness 风格浅色视觉（白/浅灰中性底、黑色主按钮、`#4176E6` 蓝色强调）。

- 文档中心：上传、解析/分块、手动向量化、状态轮询、筛选、详情、父块浏览与编辑、子块编辑/删除/连续合并/光标拆分
- 知识库：CRUD、文档绑定/解绑
- 问答：POST SSE 流式回答、Markdown 渲染、`[n]` 引用联动、父块原文、本地历史

视觉为 DeepSeek Harness 风格浅色：白/浅灰中性底、黑色主按钮、`#4176E6` 蓝色强调、细边框弱阴影；侧栏承载最近会话，引用栏默认收起、有引用时自动展开。

界面截图见 [`../docs/images/frontend-chat.png`](../docs/images/frontend-chat.png)、
[`../docs/images/frontend-chunks.png`](../docs/images/frontend-chunks.png)、
[`../docs/images/frontend-manual-index.png`](../docs/images/frontend-manual-index.png)。

上传只做解析/分块，文档进入“待向量化”；用户检查/编辑切片后，在文档详情点击“向量化”才写入 embedding。未完成索引的文档整篇不参与检索；`COMPLETED` 后编辑会自动重嵌受影响子块。

上传不限制扩展名；本地解析覆盖常见办公/文本格式，其他格式需后端启用云端解析并选择 `CLOUD`（或加入 `cloud-types`）。

Token 展示优先复用后端持久化的 `token_count`；编辑中的文本调用 `POST /api/tokens/count` 使用后端同一套 jtokkit `CL100K_BASE` 精确计数，网络异常时才回退到本地估算并标注 `≈`。

## 开发

要求 Node 22+、pnpm 11。

```bash
cd frontend
corepack enable
pnpm install
pnpm dev          # http://127.0.0.1:5173
```

开发服务器把 `/api` 代理到 `http://127.0.0.1:8080`（后端默认端口），可用环境变量覆盖：

```bash
VITE_API_PROXY=http://127.0.0.1:9090 pnpm dev
```

后端未启动时，可用内置 mock 联调界面：

```bash
# 终端 1
pnpm mock         # 127.0.0.1:8080
# 终端 2
pnpm dev
```

mock 覆盖知识库、文档、父子块编辑、手动向量化（`POST /api/documents/{id}/index`）与 `POST /api/chat` 的 SSE 事件，便于无密钥、无数据库时开发 UI。

父子块编辑遵循 Dify 模式：子块只影响检索，编辑不会改变父块；父块编辑默认按新正文重建子块。删除最后一个子块时保留空父块。普通子块编辑/拆分上限 320 Token，人工合并上限 640 Token。

真实后端联调（后端需运行在 8080）：

```bash
node scripts/check-real.mjs   # 校验文档/父块/知识库，并跑一次上传（自动清理）
```

完整 E2E（需要真实模型密钥，脚本会创建并清理临时知识库/文档）：

```bash
pnpm test:e2e           # 建库 → 上传 → 解析分块 → 手动向量化 → 绑定 → 问答 → 引用 → 历史恢复
pnpm test:crud          # 知识库 CRUD、绑定/解绑、文档删除、待向量化文档操作
pnpm test:interactions  # 缺少扩展名拦截、停止生成
pnpm test:chunks        # 父块编辑/重建、子块编辑/合并/拆分/删除、空父块保留
pnpm test:manual        # 上传→CHUNKED→编辑→手动向量化→检索→编辑自动重嵌
pnpm probe:chat         # 仅探测 /api/chat 的 SSE 与引用数量
```

## 构建

```bash
pnpm typecheck    # vue-tsc
pnpm build        # 产物在 dist/
pnpm preview      # 本地预览构建产物
```

## 部署

推荐由 Nginx 同域反代后端，浏览器只访问一个域名，无需 CORS：

```bash
# 仓库根目录
cd docker
DEEPSEEK_API_KEY=... DASHSCOPE_EMBEDDING_API_KEY=... docker compose up -d --build
# 打开 http://127.0.0.1:8088
```

单独构建前端镜像：

```bash
docker build -t eon-rag-web:1.0.0 .
```

`nginx.conf` 已处理：

- SPA 路由回退 `try_files ... /index.html`
- `/api` 反代到 `app:8080`，并关闭 `proxy_buffering` 以支持 SSE
- `client_max_body_size 60m` 匹配 50MB 上传上限
- 带 hash 的静态资源长缓存、`index.html` 不缓存

## 目录

```text
frontend/
├─ mock/server.mjs         离线 mock API（含 SSE）
├─ nginx.conf              生产反代与缓存配置
├─ Dockerfile              node build → nginx
├─ scripts/shot.mjs        Playwright 截图脚本
└─ src/
   ├─ app/                 AppShell、侧栏、顶栏
   ├─ features/
   │  ├─ chat/             消息、引用栏、输入区
   │  ├─ documents/        上传弹窗、切片编辑
   │  └─ kbs/              表单、绑定文档
   ├─ components/          UI 与品牌组件
   ├─ lib/                 api、SSE、Markdown、格式化、IndexedDB
   ├─ pages/               路由页面
   ├─ stores/              Pinia：chat、ui、toast
   ├─ styles/main.css      设计令牌与组件样式
   └─ types/               接口与聊天类型
```

## 实现约定

- **设计令牌**：`src/styles/main.css` 的 `@theme` 与 `:root`；组件类放在 `@layer components`，确保 Tailwind 工具类可覆盖。
- **SSE**：问答是 `POST /api/chat`，用 `fetch` + `ReadableStream` 解析 `citations / message / done / error`；`message` 增量 60ms 合批后再渲染 Markdown。
- **引用**：markdown-it 自定义 inline 规则把 `[n]` 渲染为 `button.cite`；点击联动右侧来源卡，原文按 Markdown 安全渲染（DOMPurify）。
- **本地历史**：会话存 IndexedDB（最多 20 个），每次提问独立检索，不向服务端发送历史。
- **鉴权**：前端固定同源访问，不提供 API 地址/Key 配置入口；401 仅 Toast 提示检查后端鉴权配置。
- **进度**：后端只有 `PROCESSING`，摄入阶段使用不确定态动画，不伪造百分比。

设计与接口约定详见 [`../docs/前端设计方案.md`](../docs/前端设计方案.md)。

## 已知限制

- 未做服务端多轮会话；历史仅本地。
- 未实现仪表盘、命令面板、文档在线预览。
- 文档/知识库列表暂为客户端筛选；后端分页接口为后续项。
- 依赖后端 `GET /api/documents/{id}/parent-chunks` 与修复后的按知识库解绑接口。
