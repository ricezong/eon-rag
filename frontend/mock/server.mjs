import http from 'node:http'

const PORT = Number(process.env.MOCK_PORT || 8080)
const now = Date.now()
const iso = (offsetMs) => new Date(now + offsetMs).toISOString()

const uuid = (n) => `${String(n).padStart(8, '0')}-1111-4111-8111-111111111111`

const kbs = [
  {
    id: uuid(1),
    name: '产品文档',
    description: '支付、账户与开放平台的产品说明与接入指南',
    color: '#4176e6',
    documentCount: 2,
    createdAt: iso(-86400000 * 30),
    updatedAt: iso(-3600000 * 2),
  },
  {
    id: uuid(2),
    name: '运维手册',
    description: '部署、监控、告警与故障处理流程',
    color: '#0e7490',
    documentCount: 2,
    createdAt: iso(-86400000 * 20),
    updatedAt: iso(-86400000 * 2),
  },
  {
    id: uuid(3),
    name: '研发规范',
    description: '接口设计、代码风格与评审约定',
    color: '#b45309',
    documentCount: 1,
    createdAt: iso(-86400000 * 10),
    updatedAt: iso(-86400000 * 5),
  },
]

const documents = [
  {
    id: uuid(101),
    fileName: '支付网关接入指南.pdf',
    fileType: 'pdf',
    fileSize: 2_512_896,
    status: 'COMPLETED',
    parseMode: 'AUTO',
    errorMessage: null,
    parentCount: 4,
    childCount: 5,
    createdAt: iso(-7200000),
    updatedAt: iso(-7000000),
    kbIds: [uuid(1)],
  },
  {
    id: uuid(102),
    fileName: '平台运维手册 v3.docx',
    fileType: 'docx',
    fileSize: 880_640,
    status: 'COMPLETED',
    parseMode: 'LOCAL',
    errorMessage: null,
    parentCount: 9,
    childCount: 38,
    createdAt: iso(-86400000),
    updatedAt: iso(-86000000),
    kbIds: [uuid(2)],
  },
  {
    id: uuid(103),
    fileName: '2026 价格表.xlsx',
    fileType: 'xlsx',
    fileSize: 122_880,
    status: 'CHUNKED',
    parseMode: 'AUTO',
    errorMessage: null,
    parentCount: 1,
    childCount: 1,
    createdAt: iso(-60000),
    updatedAt: iso(-30000),
    kbIds: [uuid(1)],
  },
  {
    id: uuid(104),
    fileName: '架构评审记录.md',
    fileType: 'md',
    fileSize: 36_864,
    status: 'COMPLETED',
    parseMode: 'LOCAL',
    errorMessage: null,
    parentCount: 6,
    childCount: 21,
    createdAt: iso(-86400000 * 2),
    updatedAt: iso(-86400000 * 2),
    kbIds: [uuid(3)],
  },
  {
    id: uuid(105),
    fileName: '扫描版采购合同.pdf',
    fileType: 'pdf',
    fileSize: 5_347_328,
    status: 'NEEDS_OCR',
    parseMode: 'AUTO',
    errorMessage:
      '未检测到有效文本层，疑似扫描件；当前未启用 OCR，请上传文字版文档或开启云端解析（整篇有效字符 12 低于阈值 50）',
    parentCount: 0,
    childCount: 0,
    createdAt: iso(-86400000 * 3),
    updatedAt: iso(-86400000 * 3),
    kbIds: [uuid(2)],
  },
  {
    id: uuid(106),
    fileName: '旧版接口说明.doc',
    fileType: 'doc',
    fileSize: 419_840,
    status: 'FAILED',
    parseMode: 'CLOUD',
    errorMessage: 'cloud parse requested but LLAMA_CLOUD_API_KEY not configured',
    parentCount: 0,
    childCount: 0,
    createdAt: iso(-86400000 * 6),
    updatedAt: iso(-86400000 * 6),
    kbIds: [],
  },
]

const parentChunks = [
  {
    id: uuid(201),
    documentId: uuid(101),
    seq: 0,
    title: '3.2 支付网关配置',
    tokenCount: 486,
    meta: JSON.stringify({ file_name: '支付网关接入指南.pdf', page_start: '12', page_end: '14' }),
    content:
      '## 3.2 支付网关配置\n\n接入支付网关前，需要先在开放平台创建应用并开通支付能力，然后配置回调地址与签名密钥。\n\n| 参数 | 必填 | 说明 |\n| --- | --- | --- |\n| mch_id | 是 | 商户号 |\n| notify_url | 是 | 异步通知地址 |\n| sign | 是 | 请求签名 |\n\n生产环境必须使用 HTTPS，并保证回调地址公网可达。',
  },
  {
    id: uuid(202),
    documentId: uuid(101),
    seq: 1,
    title: '4.1 签名规则',
    tokenCount: 512,
    meta: JSON.stringify({ file_name: '支付网关接入指南.pdf', page_start: '18', page_end: '19' }),
    content:
      '## 4.1 签名规则\n\n将所有非空参数按参数名 ASCII 升序拼接，末尾追加商户私钥，使用 HMAC-SHA256 计算摘要并转为大写。\n\n验签时使用平台公钥对响应签名进行校验，避免中间人篡改。',
  },
  {
    id: uuid(203),
    documentId: uuid(101),
    seq: 2,
    title: '5.3 回调失败排查',
    tokenCount: 438,
    meta: JSON.stringify({ file_name: '支付网关接入指南.pdf', page_start: '27', page_end: '27' }),
    content:
      '## 5.3 回调失败排查\n\n1. 检查 notify_url 是否可从公网访问；\n2. 检查防火墙是否放行平台出口 IP；\n3. 确认返回内容为纯文本 `SUCCESS`；\n4. 查看网关重试记录，确认是否已进入死信队列。',
  },
  {
    id: uuid(204),
    documentId: uuid(101),
    seq: 3,
    title: '6.1 限流与重试',
    tokenCount: 396,
    meta: JSON.stringify({ file_name: '支付网关接入指南.pdf', page_start: '31', page_end: '32' }),
    content:
      '## 6.1 限流与重试\n\n单商户默认 100 QPS。触发限流时返回 HTTP 429，建议采用指数退避重试，首次间隔 200ms，最大重试 3 次。',
  },
  {
    id: uuid(205),
    documentId: uuid(103),
    seq: 0,
    title: '价格表',
    tokenCount: 128,
    meta: JSON.stringify({ file_name: '2026 价格表.xlsx', sheet_name: '标准版' }),
    content:
      '## 价格表\n\n| 版本 | 月费 | 年费 |\n| --- | --- | --- |\n| 标准版 | 99 元 | 999 元 |\n| 专业版 | 299 元 | 2999 元 |',
  },
]

const childChunks = [
  {
    id: uuid(301),
    parentId: uuid(201),
    documentId: uuid(101),
    seq: 0,
    content:
      '## 3.2 支付网关配置\n\n接入支付网关前，需要先在开放平台创建应用并开通支付能力，然后配置回调地址与签名密钥。',
    contextPrefix: null,
    tokenCount: 148,
    meta: '{}',
    createdAt: iso(-7200000),
  },
  {
    id: uuid(302),
    parentId: uuid(201),
    documentId: uuid(101),
    seq: 1,
    content:
      '| 参数 | 必填 | 说明 |\n| --- | --- | --- |\n| mch_id | 是 | 商户号 |\n| notify_url | 是 | 异步通知地址 |\n| sign | 是 | 请求签名 |\n\n生产环境必须使用 HTTPS，并保证回调地址公网可达。',
    contextPrefix: null,
    tokenCount: 126,
    meta: '{}',
    createdAt: iso(-7200000),
  },
  {
    id: uuid(303),
    parentId: uuid(202),
    documentId: uuid(101),
    seq: 0,
    content:
      '## 4.1 签名规则\n\n将所有非空参数按参数名 ASCII 升序拼接，末尾追加商户私钥，使用 HMAC-SHA256 计算摘要并转为大写。\n\n验签时使用平台公钥对响应签名进行校验，避免中间人篡改。',
    contextPrefix: null,
    tokenCount: 172,
    meta: '{}',
    createdAt: iso(-7200000),
  },
  {
    id: uuid(304),
    parentId: uuid(203),
    documentId: uuid(101),
    seq: 0,
    content:
      '## 5.3 回调失败排查\n\n1. 检查 notify_url 是否可从公网访问；\n2. 检查防火墙是否放行平台出口 IP；\n3. 确认返回内容为纯文本 `SUCCESS`；\n4. 查看网关重试记录，确认是否已进入死信队列。',
    contextPrefix: null,
    tokenCount: 164,
    meta: '{}',
    createdAt: iso(-7200000),
  },
  {
    id: uuid(305),
    parentId: uuid(204),
    documentId: uuid(101),
    seq: 0,
    content:
      '## 6.1 限流与重试\n\n单商户默认 100 QPS。触发限流时返回 HTTP 429，建议采用指数退避重试，首次间隔 200ms，最大重试 3 次。',
    contextPrefix: null,
    tokenCount: 132,
    meta: '{}',
    createdAt: iso(-7200000),
  },
  {
    id: uuid(306),
    parentId: uuid(205),
    documentId: uuid(103),
    seq: 0,
    content:
      '## 价格表\n\n| 版本 | 月费 | 年费 |\n| --- | --- | --- |\n| 标准版 | 99 元 | 999 元 |\n| 专业版 | 299 元 | 2999 元 |',
    contextPrefix: null,
    tokenCount: 72,
    meta: '{}',
    createdAt: iso(-60000),
  },
]

const citations = [
  {
    index: 1,
    title: '3.2 支付网关配置',
    documentId: uuid(101),
    parentId: uuid(201),
    meta: { file_name: '支付网关接入指南.pdf', page_start: '12', page_end: '14' },
    children: [
      { childId: uuid(301), seq: 2, score: 0.913 },
      { childId: uuid(302), seq: 3, score: 0.842 },
    ],
  },
  {
    index: 2,
    title: '4.1 签名规则',
    documentId: uuid(101),
    parentId: uuid(202),
    meta: { file_name: '支付网关接入指南.pdf', page_start: '18', page_end: '19' },
    children: [{ childId: uuid(303), seq: 5, score: 0.874 }],
  },
  {
    index: 3,
    title: '5.3 回调失败排查',
    documentId: uuid(101),
    parentId: uuid(203),
    meta: { file_name: '支付网关接入指南.pdf', page_start: '27', page_end: '27' },
    children: [{ childId: uuid(304), seq: 1, score: 0.812 }],
  },
]

const answerFrames = [
  '## 支付网关配置步骤\n\n根据文档，配置支付网关需要完成三步：\n\n',
  '1. **申请商户号**：在开放平台创建应用并开通支付能力 [1]。\n2. **配置回调地址**：将 `notify_url` 指向你的 HTTPS 服务 [1]。\n3. **校验签名**：使用商户私钥对请求参数签名 [2]。\n\n',
  '| 参数 | 必填 | 说明 |\n| --- | --- | --- |\n| `mch_id` | 是 | 商户号 |\n| `notify_url` | 是 | 异步通知地址 |\n| `sign` | 是 | 请求签名 |\n\n',
  '> 注意：生产环境必须使用 HTTPS，且回调地址需要公网可达 [3]。\n\n',
  '如果需要，我可以继续展开签名算法的示例代码。',
]

function json(res, payload, status = 200) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  res.end(body)
}

function ok(res, data) {
  json(res, { code: 'OK', message: null, data })
}

function fail(res, status, message) {
  json(res, { code: 'ERROR', message, data: null }, status)
}

function readBody(req) {
  return new Promise((resolve) => {
    let body = ''
    req.on('data', (chunk) => {
      body += chunk
      if (body.length > 2_000_000) req.destroy()
    })
    req.on('end', () => resolve(body))
  })
}

function sse(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  })
}

function sendEvent(res, event, data) {
  const lines = String(data).split('\n')
  res.write(`event: ${event}\n`)
  for (const line of lines) res.write(`data: ${line}\n`)
  res.write('\n')
}

let nextChildId = 1000

function estimateTokens(text) {
  return Math.ceil(
    [...String(text)].reduce(
      (total, char) => total + (char.codePointAt(0) > 0x2e7f ? 1.5 : 0.25),
      0,
    ),
  )
}

function findParent(documentId, parentId) {
  return parentChunks.find((item) => item.documentId === documentId && item.id === parentId)
}

function findChild(parentId, childId) {
  return childChunks.find((item) => item.parentId === parentId && item.id === childId)
}

function renumberChildren(parentId) {
  const list = childChunks
    .filter((item) => item.parentId === parentId)
    .sort((a, b) => a.seq - b.seq)
  list.forEach((item, index) => {
    item.seq = index
  })
}

function renumberParents(documentId) {
  const list = parentChunks
    .filter((item) => item.documentId === documentId)
    .sort((a, b) => a.seq - b.seq)
  list.forEach((item, index) => {
    item.seq = index
  })
}

function refreshCounts(documentId) {
  const doc = documents.find((item) => item.id === documentId)
  if (!doc) return
  doc.parentCount = parentChunks.filter((item) => item.documentId === documentId).length
  doc.childCount = childChunks.filter((item) => item.documentId === documentId).length
  doc.updatedAt = new Date().toISOString()
}

function rebuildChildren(parent) {
  for (let i = childChunks.length - 1; i >= 0; i -= 1) {
    if (childChunks[i].parentId === parent.id) childChunks.splice(i, 1)
  }
  const parts = parent.content
    .split(/\n{2,}/)
    .map((part) => part.trim())
    .filter(Boolean)
  const source = []
  for (let i = 0; i < parts.length; i += 1) {
    const part = parts[i]
    if (/^#{1,6}\s/.test(part) && i + 1 < parts.length) {
      source.push(`${part}\n\n${parts[i + 1]}`)
      i += 1
    } else {
      source.push(part)
    }
  }
  const finalParts = source.length > 0 ? source : [parent.content]
  finalParts.forEach((content, index) => {
    childChunks.push({
      id: uuid(nextChildId++),
      parentId: parent.id,
      documentId: parent.documentId,
      seq: index,
      content,
      contextPrefix: null,
      tokenCount: estimateTokens(content),
      meta: '{}',
      createdAt: new Date().toISOString(),
    })
  })
  renumberChildren(parent.id)
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host}`)
  const path = url.pathname
  const method = req.method || 'GET'

  if (path === '/api/tokens/count' && method === 'POST') {
    const body = JSON.parse((await readBody(req)) || '{}')
    const texts = Array.isArray(body.texts) ? body.texts : []
    return ok(
      res,
      texts.map((text) => Math.ceil(String(text).length * 0.6)),
    )
  }

  if (path === '/api/kbs' && method === 'GET') return ok(res, kbs)
  if (path === '/api/kbs' && method === 'POST') {
    const body = JSON.parse((await readBody(req)) || '{}')
    const kb = {
      id: uuid(Date.now() % 100000),
      name: body.name,
      description: body.description || '',
      color: body.color || '#0e7490',
      documentCount: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    }
    kbs.push(kb)
    return ok(res, kb)
  }

  const kbMatch = path.match(/^\/api\/kbs\/([^/]+)$/)
  if (kbMatch) {
    const kb = kbs.find((item) => item.id === kbMatch[1])
    if (!kb) return fail(res, 404, '知识库不存在')
    if (method === 'GET') return ok(res, kb)
    if (method === 'PUT') {
      const body = JSON.parse((await readBody(req)) || '{}')
      Object.assign(kb, {
        name: body.name ?? kb.name,
        description: body.description ?? kb.description,
        color: body.color ?? kb.color,
        updatedAt: new Date().toISOString(),
      })
      return ok(res, kb)
    }
    if (method === 'DELETE') {
      const index = kbs.indexOf(kb)
      if (index >= 0) kbs.splice(index, 1)
      return ok(res, null)
    }
  }

  const kbDocsMatch = path.match(/^\/api\/kbs\/([^/]+)\/documents$/)
  if (kbDocsMatch && method === 'GET') {
    const list = documents
      .filter((doc) => doc.kbIds.includes(kbDocsMatch[1]))
      .map((doc) => ({ ...doc, kbIds: [kbDocsMatch[1]] }))
    return ok(res, list)
  }

  const bindMatch = path.match(/^\/api\/kbs\/([^/]+)\/documents\/([^/]+)$/)
  if (bindMatch) {
    const doc = documents.find((item) => item.id === bindMatch[2])
    if (!doc) return fail(res, 404, '文档不存在')
    if (method === 'POST') {
      if (!doc.kbIds.includes(bindMatch[1])) doc.kbIds.push(bindMatch[1])
      return ok(res, null)
    }
    if (method === 'DELETE') {
      doc.kbIds = doc.kbIds.filter((item) => item !== bindMatch[1])
      return ok(res, null)
    }
  }

  if (path === '/api/documents' && method === 'GET') return ok(res, documents)
  if (path === '/api/documents' && method === 'POST') {
    await readBody(req)
    const doc = {
      id: uuid(900 + Math.floor(Math.random() * 90)),
      fileName: '新上传文档.pdf',
      fileType: 'pdf',
      fileSize: 1_048_576,
      status: 'PROCESSING',
      parseMode: 'AUTO',
      errorMessage: null,
      parentCount: 0,
      childCount: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      kbIds: [],
    }
    documents.unshift(doc)
    return ok(res, { documentId: doc.id })
  }

  const docMatch = path.match(/^\/api\/documents\/([^/]+)$/)
  if (docMatch) {
    const doc = documents.find((item) => item.id === docMatch[1])
    if (!doc) return fail(res, 404, '文档不存在')
    if (method === 'GET') return ok(res, doc)
    if (method === 'DELETE') {
      const index = documents.indexOf(doc)
      if (index >= 0) documents.splice(index, 1)
      return ok(res, null)
    }
  }

  const chunksMatch = path.match(/^\/api\/documents\/([^/]+)\/parent-chunks$/)
  if (chunksMatch && method === 'GET') {
    const page = Number(url.searchParams.get('page') || 0)
    const size = Number(url.searchParams.get('size') || 10)
    const all = parentChunks.filter((item) => item.documentId === chunksMatch[1])
    return ok(res, {
      items: all.slice(page * size, page * size + size),
      total: all.length,
      page,
      size,
    })
  }

  const indexMatch = path.match(/^\/api\/documents\/([^/]+)\/index$/)
  if (indexMatch && method === 'POST') {
    const doc = documents.find((item) => item.id === indexMatch[1])
    if (!doc) return fail(res, 404, '文档不存在')
    if (!['CHUNKED', 'INDEX_FAILED'].includes(doc.status)) {
      return fail(res, 409, '文档当前状态不可向量化')
    }
    doc.status = 'COMPLETED'
    doc.errorMessage = null
    doc.updatedAt = new Date().toISOString()
    return ok(res, { documentId: doc.id, status: 'COMPLETED' })
  }

  const childrenMatch = path.match(
    /^\/api\/documents\/([^/]+)\/parent-chunks\/([^/]+)\/children$/,
  )
  if (childrenMatch) {
    const parent = findParent(childrenMatch[1], childrenMatch[2])
    if (!parent) return fail(res, 404, '父块不存在')
    if (method === 'GET') {
      return ok(
        res,
        childChunks
          .filter((item) => item.parentId === parent.id)
          .sort((a, b) => a.seq - b.seq),
      )
    }
  }

  const mergeMatch = path.match(
    /^\/api\/documents\/([^/]+)\/parent-chunks\/([^/]+)\/children\/merge$/,
  )
  if (mergeMatch && method === 'POST') {
    const parent = findParent(mergeMatch[1], mergeMatch[2])
    if (!parent) return fail(res, 404, '父块不存在')
    const body = JSON.parse((await readBody(req)) || '{}')
    const ids = Array.isArray(body.childIds) ? body.childIds : []
    const selected = ids
      .map((id) => findChild(parent.id, id))
      .filter((item) => item !== undefined)
      .sort((a, b) => a.seq - b.seq)
    if (selected.length < 2) return fail(res, 422, '至少选择两个子块')
    for (let i = 1; i < selected.length; i += 1) {
      if (selected[i].seq !== selected[i - 1].seq + 1) {
        return fail(res, 422, '只能合并连续子块')
      }
    }
    const first = selected[0]
    first.content = selected.map((item) => item.content.replace(/\n+$/g, '')).join('\n\n')
    first.tokenCount = estimateTokens(first.content)
    for (const item of selected.slice(1)) {
      const index = childChunks.indexOf(item)
      if (index >= 0) childChunks.splice(index, 1)
    }
    renumberChildren(parent.id)
    refreshCounts(parent.documentId)
    return ok(res, first)
  }

  const splitMatch = path.match(
    /^\/api\/documents\/([^/]+)\/parent-chunks\/([^/]+)\/children\/([^/]+)\/split$/,
  )
  if (splitMatch && method === 'POST') {
    const parent = findParent(splitMatch[1], splitMatch[2])
    if (!parent) return fail(res, 404, '父块不存在')
    const child = findChild(parent.id, splitMatch[3])
    if (!child) return fail(res, 404, '子块不存在')
    const body = JSON.parse((await readBody(req)) || '{}')
    const offset = Number(body.offset)
    if (!Number.isInteger(offset) || offset <= 0 || offset >= child.content.length) {
      return fail(res, 422, '拆分位置不合法')
    }
    const left = child.content.slice(0, offset).trim()
    const right = child.content.slice(offset).trim()
    if (!left || !right) return fail(res, 422, '拆分后两段不能为空')
    child.content = left
    child.tokenCount = estimateTokens(left)
    const second = {
      id: uuid(nextChildId++),
      parentId: parent.id,
      documentId: parent.documentId,
      seq: child.seq,
      content: right,
      contextPrefix: child.contextPrefix,
      tokenCount: estimateTokens(right),
      meta: child.meta ?? '{}',
      createdAt: new Date().toISOString(),
    }
    childChunks.push(second)
    renumberChildren(parent.id)
    refreshCounts(parent.documentId)
    return ok(res, [child, second])
  }

  const childMatch = path.match(
    /^\/api\/documents\/([^/]+)\/parent-chunks\/([^/]+)\/children\/([^/]+)$/,
  )
  if (childMatch) {
    const parent = findParent(childMatch[1], childMatch[2])
    if (!parent) return fail(res, 404, '父块不存在')
    const child = findChild(parent.id, childMatch[3])
    if (!child) return fail(res, 404, '子块不存在')
    if (method === 'PUT') {
      const body = JSON.parse((await readBody(req)) || '{}')
      const content = String(body.content ?? '').trim()
      if (!content) return fail(res, 422, '子块内容不能为空')
      child.content = content
      child.tokenCount = estimateTokens(content)
      refreshCounts(parent.documentId)
      return ok(res, child)
    }
    if (method === 'DELETE') {
      const index = childChunks.indexOf(child)
      if (index >= 0) childChunks.splice(index, 1)
      renumberChildren(parent.id)
      refreshCounts(parent.documentId)
      return ok(res, null)
    }
  }

  const parentMatch = path.match(/^\/api\/documents\/([^/]+)\/parent-chunks\/([^/]+)$/)
  if (parentMatch) {
    const chunk = findParent(parentMatch[1], parentMatch[2])
    if (!chunk) return fail(res, 404, '父块不存在')
    if (method === 'GET') return ok(res, chunk)
    if (method === 'PUT') {
      const body = JSON.parse((await readBody(req)) || '{}')
      const content = String(body.content ?? '').trim()
      if (!content) return fail(res, 422, '父块内容不能为空')
      const changed = content !== chunk.content
      chunk.title = body.title ? String(body.title).trim() : null
      chunk.content = content
      chunk.tokenCount = estimateTokens(content)
      if (body.rebuildChildren !== false && changed) {
        rebuildChildren(chunk)
      }
      refreshCounts(chunk.documentId)
      return ok(res, chunk)
    }
    if (method === 'DELETE') {
      for (let i = childChunks.length - 1; i >= 0; i -= 1) {
        if (childChunks[i].parentId === chunk.id) childChunks.splice(i, 1)
      }
      const index = parentChunks.indexOf(chunk)
      if (index >= 0) parentChunks.splice(index, 1)
      renumberParents(chunk.documentId)
      refreshCounts(chunk.documentId)
      return ok(res, null)
    }
  }

  if (path === '/api/chat' && method === 'POST') {
    await readBody(req)
    sse(res)
    let closed = false
    res.on('close', () => {
      closed = true
    })

    const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
    ;(async () => {
      await wait(320)
      if (closed) return
      sendEvent(res, 'citations', JSON.stringify(citations))

      for (const frame of answerFrames) {
        await wait(190)
        if (closed) return
        sendEvent(res, 'message', frame)
      }

      await wait(220)
      if (closed) return
      sendEvent(res, 'done', '{}')
      res.end()
    })()
    return
  }

  if (path === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    return res.end('ok')
  }

  fail(res, 404, `mock 未实现: ${method} ${path}`)
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock] listening on http://127.0.0.1:${PORT}`)
})
