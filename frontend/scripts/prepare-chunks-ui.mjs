const BASE = process.env.API_BASE || 'http://127.0.0.1:8080'
const stamp = Date.now()

async function api(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, options)
  const payload = await response.json()
  if (!response.ok || payload.code !== 'OK') {
    throw new Error(`${options.method || 'GET'} ${path}: ${payload.message || response.status}`)
  }
  return payload.data
}

const content = `# 父子块 UI 验收 ${stamp}

## 重试策略

支付网关最大重试次数为 5 次，首次重试间隔为 300 毫秒。后续按指数退避，第 2 次 600 毫秒，第 3 次 1200 毫秒，第 4 次 2400 毫秒，第 5 次 4800 毫秒。
仅对网络超时、502、503 重试，参数错误不重试。重试必须使用相同幂等键，避免重复扣款。
超过最大重试次数后进入死信队列，并记录请求 ID。死信队列保留 7 天，支持人工重放。
重放前必须校验交易状态，避免重复处理。监控按分钟聚合重试次数与成功率。
重试日志包含请求 ID、商户号、接口名、响应码、耗时与下一次重试时间。所有重试事件写入审计日志并保留 180 天。
重试任务使用独立线程池，核心线程数 8，队列容量 1000，队列满时拒绝并由上游降级。
每次重试后记录下一次执行时间，服务重启后从数据库恢复未完成任务。
重试与限流共用同一套监控指标，避免重复统计。

## 告警阈值

连续失败 10 次触发告警。告警通知发送到运维值班群，并附带请求 ID 与失败原因。

## 幂等要求

同一笔交易使用相同的幂等键，重复请求返回首次处理结果。幂等键保留 24 小时。
`

const form = new FormData()
form.append('file', new Blob([content], { type: 'text/markdown' }), `chunk-ui-${stamp}.md`)
const created = await api('/api/documents', { method: 'POST', body: form })
for (let i = 0; i < 60; i += 1) {
  const doc = await api(`/api/documents/${created.documentId}`)
  if (doc.status === 'CHUNKED') break
  if (doc.status === 'FAILED' || doc.status === 'NEEDS_OCR') {
    throw new Error(`${doc.status} ${doc.errorMessage || ''}`)
  }
  await new Promise((resolve) => setTimeout(resolve, 1000))
  if (i === 59) throw new Error('chunking timeout')
}
const started = await fetch(`${BASE}/api/documents/${created.documentId}/index`, { method: 'POST' })
if (started.status !== 202) throw new Error(`index start returned ${started.status}`)
for (let i = 0; i < 90; i += 1) {
  const doc = await api(`/api/documents/${created.documentId}`)
  if (doc.status === 'COMPLETED') {
    console.log(created.documentId)
    process.exit(0)
  }
  if (doc.status === 'INDEX_FAILED' || doc.status === 'FAILED') {
    throw new Error(`${doc.status} ${doc.errorMessage || ''}`)
  }
  await new Promise((resolve) => setTimeout(resolve, 1000))
}
throw new Error('indexing timeout')
