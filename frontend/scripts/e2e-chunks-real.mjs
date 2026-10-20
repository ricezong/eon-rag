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
  const content = `# 父子块编辑验收 ${stamp}

## 重试策略

支付网关最大重试次数为 5 次，首次重试间隔为 300 毫秒。后续按指数退避，第 2 次 600 毫秒，第 3 次 1200 毫秒，第 4 次 2400 毫秒，第 5 次 4800 毫秒。
仅对网络超时、502、503 重试，参数错误不重试。重试必须使用相同幂等键，避免重复扣款。
超过最大重试次数后进入死信队列，并记录请求 ID。死信队列保留 7 天，支持人工重放。
重放前必须校验交易状态，避免重复处理。监控按分钟聚合重试次数与成功率。
重试日志包含请求 ID、商户号、接口名、响应码、耗时与下一次重试时间。所有重试事件写入审计日志并保留 180 天。
只读查询接口可以适当增加重试次数，但不得超过 8 次；写接口超过 3 次仍失败时必须先查询交易状态，再决定是否重试。
批量请求按单笔交易拆分重试，避免整批失败。重试期间保持请求参数与签名不变，时间戳除外。
监控告警按商户维度聚合，异常商户自动降速。压测环境禁止连接生产支付网关。

## 告警阈值

连续失败 10 次触发告警。告警通知发送到运维值班群，并附带请求 ID 与失败原因。

## 幂等要求

同一笔交易使用相同的幂等键，重复请求返回首次处理结果。幂等键保留 24 小时。
`
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), `chunk-edit-${stamp}.md`)
  const created = await api('/api/documents', { method: 'POST', body: form })
  const documentId = created.documentId
  for (let i = 0; i < 60; i += 1) {
    const doc = await api(`/api/documents/${documentId}`)
    if (doc.status === 'CHUNKED') break
    if (doc.status === 'FAILED' || doc.status === 'NEEDS_OCR') {
      throw new Error(`ingestion failed: ${doc.status} ${doc.errorMessage || ''}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
    if (i === 59) throw new Error('chunking timeout')
  }
  const started = await fetch(`${BASE}/api/documents/${documentId}/index`, { method: 'POST' })
  if (started.status !== 202) throw new Error(`index start returned ${started.status}`)
  for (let i = 0; i < 90; i += 1) {
    const doc = await api(`/api/documents/${documentId}`)
    if (doc.status === 'COMPLETED') return { documentId, doc }
    if (doc.status === 'INDEX_FAILED' || doc.status === 'FAILED') {
      throw new Error(`indexing failed: ${doc.status} ${doc.errorMessage || ''}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error('indexing timeout')
}

const { documentId } = await upload()
console.log(`[doc] ${documentId}`)

try {
  const page = await api(`/api/documents/${documentId}/parent-chunks?page=0&size=50`)
  assert.ok(page.items.length >= 2, 'expected at least 2 parents')

  let parent = null
  let children = []
  for (const candidate of page.items) {
    const list = await api(
      `/api/documents/${documentId}/parent-chunks/${candidate.id}/children`,
    )
    if (list.length >= 2) {
      parent = candidate
      children = list
      break
    }
  }
  assert.ok(parent, 'expected a parent with at least 2 children')
  console.log(`[parent] ${parent.title} seq=${parent.seq}`)
  console.log(`[children] ${children.length}`)

  const first = children[0]
  const editedContent = `${first.content.slice(0, Math.min(160, first.content.length))}\n\n人工补充：已编辑。`
  const updated = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children/${first.id}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: editedContent }),
    },
  )
  assert.equal(updated.content, editedContent)
  assert.ok(updated.tokenCount > 0)
  console.log(`[edit child] token=${updated.tokenCount}`)

  const merged = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children/merge`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ childIds: [children[0].id, children[1].id] }),
    },
  )
  assert.ok(merged.content.includes('人工补充'))
  const afterMerge = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children`,
  )
  assert.equal(afterMerge.length, children.length - 1)
  console.log(`[merge] children=${afterMerge.length}`)

  const secondMarker = children[1].content.slice(0, 30)
  const marker = merged.content.indexOf(secondMarker)
  const offset =
    marker > 20 && marker < merged.content.length - 20
      ? marker
      : Math.floor(merged.content.length / 2)
  const split = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children/${merged.id}/split`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ offset }),
    },
  )
  assert.equal(split.length, 2)
  assert.equal(split[0].id, merged.id)
  const afterSplit = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children`,
  )
  assert.ok(afterSplit.length >= 2)
  console.log(`[split] children=${afterSplit.length}`)

  const idsBefore = afterSplit.map((item) => item.id)
  const parentEdited = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: parent.title,
        content: `${parent.content}\n\n## 人工补充\n\n父块编辑后重建子块。`,
        rebuildChildren: false,
      }),
    },
  )
  assert.ok(parentEdited.content.includes('人工补充'))
  const afterKeep = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children`,
  )
  assert.deepEqual(afterKeep.map((item) => item.id), idsBefore, 'keep children must preserve ids')
  console.log('[parent edit keep] ids preserved')

  await api(`/api/documents/${documentId}/parent-chunks/${parent.id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      title: parent.title,
      content: parentEdited.content,
      rebuildChildren: true,
    }),
  })
  const afterRebuild = await api(
    `/api/documents/${documentId}/parent-chunks/${parent.id}/children`,
  )
  assert.ok(afterRebuild.length > 0)
  assert.notDeepEqual(
    afterRebuild.map((item) => item.id),
    idsBefore,
    'rebuild must replace child ids',
  )
  console.log(`[parent edit rebuild] children=${afterRebuild.length}`)

  const single = page.items.find((item) => item.id !== parent.id)
  if (single) {
    const singleChildren = await api(
      `/api/documents/${documentId}/parent-chunks/${single.id}/children`,
    )
    if (singleChildren.length === 1) {
      await api(
        `/api/documents/${documentId}/parent-chunks/${single.id}/children/${singleChildren[0].id}`,
        { method: 'DELETE' },
      )
      const empty = await api(
        `/api/documents/${documentId}/parent-chunks/${single.id}/children`,
      )
      assert.equal(empty.length, 0)
      const parentStillThere = await api(
        `/api/documents/${documentId}/parent-chunks/${single.id}`,
      )
      assert.equal(parentStillThere.id, single.id)
      console.log('[delete last child] parent kept')

      await api(`/api/documents/${documentId}/parent-chunks/${single.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: single.title,
          content: single.content,
          rebuildChildren: true,
        }),
      })
      const restored = await api(
        `/api/documents/${documentId}/parent-chunks/${single.id}/children`,
      )
      assert.ok(restored.length > 0)
      console.log('[empty parent rebuild] restored')
    }
  }

  await api(`/api/documents/${documentId}/parent-chunks/${parent.id}`, {
    method: 'DELETE',
  })
  console.log('[delete parent] ok')
  console.log('[RESULT] chunk edit API OK')
} finally {
  try {
    await api(`/api/documents/${documentId}`, { method: 'DELETE' })
    console.log('[cleanup] document deleted')
  } catch (error) {
    console.error(`[cleanup] failed: ${error.message}`)
  }
}
