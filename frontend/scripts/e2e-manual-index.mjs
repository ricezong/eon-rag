import assert from 'node:assert/strict'

const BASE = process.env.API_BASE || 'http://127.0.0.1:8080'
const stamp = Date.now()

async function api(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, options)
  const text = await response.text()
  let payload
  try {
    payload = JSON.parse(text)
  } catch {
    throw new Error(`${options.method || 'GET'} ${path} -> ${response.status}: ${text.slice(0, 300)}`)
  }
  if (!response.ok || (payload.code && payload.code !== 'OK')) {
    throw new Error(
      `${options.method || 'GET'} ${path} -> ${response.status}: ${payload.message || text.slice(0, 300)}`,
    )
  }
  return payload.data
}

async function upload() {
  const content = `# 手动向量化验收 ${stamp}

## 重试策略

支付网关最大重试次数为 5 次，首次重试间隔为 300 毫秒。后续按指数退避，第 2 次 600 毫秒，第 3 次 1200 毫秒，第 4 次 2400 毫秒，第 5 次 4800 毫秒。
仅对网络超时、502、503 重试，参数错误不重试。重试必须使用相同幂等键，避免重复扣款。
超过最大重试次数后进入死信队列，并记录请求 ID。死信队列保留 7 天，支持人工重放。
重放前必须校验交易状态，避免重复处理。监控按分钟聚合重试次数与成功率。
重试日志包含请求 ID、商户号、接口名、响应码、耗时与下一次重试时间。所有重试事件写入审计日志并保留 180 天。

## 告警阈值

连续失败 10 次触发告警。告警通知发送到运维值班群，并附带请求 ID 与失败原因。

## 幂等要求

同一笔交易使用相同的幂等键，重复请求返回首次处理结果。幂等键保留 24 小时。
`
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), `manual-index-${stamp}.md`)
  const created = await api('/api/documents', { method: 'POST', body: form })
  return created.documentId
}

async function waitStatus(documentId, expected, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const doc = await api(`/api/documents/${documentId}`)
    if (expected.includes(doc.status)) return doc
    if (doc.status === 'FAILED' || doc.status === 'NEEDS_OCR') {
      throw new Error(`unexpected status ${doc.status}: ${doc.errorMessage || ''}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 800))
  }
  throw new Error(`timeout waiting for ${expected.join('/')}`)
}

async function chat(question) {
  const response = await fetch(`${BASE}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ question }),
  })
  assert.equal(response.status, 200)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let citations = []
  let answer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      const event = frame.match(/^event:\s?(.+)$/m)?.[1]
      const data = [...frame.matchAll(/^data:\s?(.*)$/gm)].map((match) => match[1]).join('\n')
      if (event === 'citations') citations = JSON.parse(data)
      if (event === 'message') answer += data
    }
  }
  return { citations, answer }
}

const documentId = await upload()
console.log(`[doc] ${documentId}`)
try {
  const chunked = await waitStatus(documentId, ['CHUNKED'])
  assert.ok(chunked.childCount > 0)
  assert.equal(chunked.status, 'CHUNKED')
  console.log(`[chunked] parents=${chunked.parentCount} children=${chunked.childCount}`)

  const parents = await api(`/api/documents/${documentId}/parent-chunks?page=0&size=50`)
  const parent = parents.items[0]
  const children = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children`,
  )
  assert.ok(children.length > 0)
  const child = children[0]
  const edited = `${child.content.slice(0, Math.min(120, child.content.length))}\n\n手动编辑：待向量化前修改。`
  await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children/${child.id}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: edited }),
    },
  )
  const afterEdit = await api(`/api/documents/${documentId}`)
  assert.equal(afterEdit.status, 'CHUNKED')
  console.log('[pre-index edit] status stays CHUNKED')

  const beforeIndex = await chat('支付网关最大重试次数是多少？')
  assert.equal(beforeIndex.citations.length, 0, 'pending document must not be searchable')
  assert.match(beforeIndex.answer, /未找到/)
  console.log('[pre-index search] excluded from retrieval')

  const started = await fetch(`${BASE}/api/documents/${documentId}/index`, { method: 'POST' })
  assert.equal(started.status, 202)
  const completed = await waitStatus(documentId, ['COMPLETED'])
  assert.equal(completed.status, 'COMPLETED')
  console.log(`[indexed] status=${completed.status}`)

  const result = await chat('支付网关最大重试次数是多少？')
  assert.ok(result.citations.length > 0, 'expected citations after indexing')
  assert.match(result.answer, /5\s*次/)
  console.log(`[chat] citations=${result.citations.length} answerLength=${result.answer.length}`)

  const editedAfterIndex = `${edited}\n\n索引后编辑：自动重嵌。`
  await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children/${child.id}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: editedAfterIndex }),
    },
  )
  const afterPostEdit = await api(`/api/documents/${documentId}`)
  assert.equal(afterPostEdit.status, 'COMPLETED')
  const updatedChildren = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children`,
  )
  const updatedChild = updatedChildren.find((item) => item.id === child.id)
  assert.ok(updatedChild)
  assert.match(updatedChild.content, /索引后编辑/)
  console.log('[post-index edit] still COMPLETED and content updated')
  console.log('[RESULT] manual index E2E OK')
} finally {
  await api(`/api/documents/${documentId}`, { method: 'DELETE' }).catch((error) => {
    console.error(`[cleanup] ${error.message}`)
  })
}
