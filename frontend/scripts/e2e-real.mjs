import { chromium } from 'playwright-core'

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5174'
const API = process.env.API_URL || 'http://127.0.0.1:8080'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
const kbName = `E2E验收-${stamp}`
const fileName = `e2e-gateway-${stamp}.md`
const fileContent = `# 极光支付网关运维手册

## 重试策略

支付网关在请求超时后会自动重试，最大重试次数为 5 次。
首次重试间隔为 300 毫秒，之后按指数退避。
如果 5 次重试均失败，订单会进入人工复核队列。

## 告警阈值

连续 10 分钟内失败率超过 3% 时触发 P1 告警。
值班人员必须在 5 分钟内响应。

## 值班电话

支付平台值班电话为 400-800-1234。
`

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function apiJson(path, init) {
  const res = await fetch(`${API}${path}`, init)
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

async function waitDocStatus(documentId, expected, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const payload = await apiJson(`/api/documents/${documentId}`)
    const doc = payload.data
    if (expected.includes(doc.status)) return doc
    if (doc.status === 'FAILED' || doc.status === 'NEEDS_OCR') {
      throw new Error(`document failed: ${doc.status} ${doc.errorMessage || ''}`)
    }
    await new Promise((resolve) => setTimeout(resolve, 800))
  }
  throw new Error(`timeout waiting for ${expected.join('/')}`)
}

async function cleanup() {
  try {
    const docs = await apiJson('/api/documents')
    for (const doc of docs.data ?? []) {
      if (doc.fileName === fileName) {
        await fetch(`${API}/api/documents/${doc.id}`, { method: 'DELETE' })
      }
    }
    const kbs = await apiJson('/api/kbs')
    for (const kb of kbs.data ?? []) {
      if (kb.name === kbName) {
        await fetch(`${API}/api/kbs/${kb.id}`, { method: 'DELETE' })
      }
    }
  } catch (error) {
    console.log(`[cleanup] ${error.message}`)
  }
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
const browserErrors = []
page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error.message}`))
page.on('console', (message) => {
  if (message.type() === 'error') browserErrors.push(`console: ${message.text()}`)
})

let failed = false
try {
  console.log(`[e2e] kb=${kbName} doc=${fileName}`)

  // 1. 新建知识库
  await page.goto(`${WEB}/kbs`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '新建知识库' }).first().click()
  const kbDialog = page.getByRole('dialog')
  await kbDialog.locator('#kb-name').fill(kbName)
  await kbDialog.locator('#kb-description').fill('真实后端 E2E 验收知识库')
  await kbDialog.getByRole('button', { name: '创建知识库' }).click()
  await page.waitForFunction((name) => document.body.innerText.includes(name), kbName)
  console.log('[e2e] KB created')

  // 2. 上传文档
  await page.goto(`${WEB}/documents`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '上传文档' }).first().click()
  const uploadDialog = page.getByRole('dialog')
  await uploadDialog.locator('input[type="file"]').setInputFiles({
    name: fileName,
    mimeType: 'text/markdown',
    buffer: Buffer.from(fileContent),
  })
  await uploadDialog.locator('#parse-mode').selectOption('LOCAL')
  await uploadDialog.getByRole('button', { name: /开始上传/ }).click()
  await page.waitForTimeout(1200)
  const submitted = await uploadDialog.getByText(/已提交/).count()
  assert(submitted > 0, 'upload dialog did not show submitted state')
  const finish = uploadDialog.getByRole('button', { name: '完成', exact: true })
  if (await finish.count()) await finish.click()
  console.log('[e2e] upload accepted')

  // 3. 等待解析/分块完成，再手动触发向量化
  const docs = await apiJson('/api/documents')
  const doc = (docs.data ?? []).find((item) => item.fileName === fileName)
  assert(doc, 'document not found after upload')
  const chunked = await waitDocStatus(doc.id, ['CHUNKED'])
  console.log(`[e2e] chunked: parents=${chunked.parentCount} children=${chunked.childCount}`)

  const indexResponse = await fetch(`${API}/api/documents/${doc.id}/index`, { method: 'POST' })
  assert(indexResponse.status === 202, `index start returned ${indexResponse.status}`)
  const completed = await waitDocStatus(doc.id, ['COMPLETED'])
  console.log(`[e2e] indexed: parents=${completed.parentCount} children=${completed.childCount}`)

  await page.goto(`${WEB}/documents`, { waitUntil: 'networkidle' })
  await page.waitForFunction(
    (name) => {
      const rows = [...document.querySelectorAll('tbody tr')]
      const row = rows.find((item) => item.textContent?.includes(name))
      return Boolean(row?.textContent?.includes('已完成'))
    },
    fileName,
    { timeout: 30_000 },
  )

  // 4. 文档详情父块
  await page.goto(`${WEB}/documents/${doc.id}`, { waitUntil: 'networkidle' })
  await page.getByText('父块内容').waitFor({ timeout: 15_000 })
  const chunkCards = await page.locator('article').count()
  assert(chunkCards > 0, 'document detail has no parent chunk cards')
  await page.screenshot({ path: 'shots/e2e-document-detail.png' })
  console.log(`[e2e] parent chunks rendered: ${chunkCards}`)

  // 5. 绑定到知识库
  await page.goto(`${WEB}/kbs`, { waitUntil: 'networkidle' })
  await page.locator('article').filter({ hasText: kbName }).click()
  await page.getByRole('button', { name: '绑定文档' }).first().click()
  const bindDialog = page.getByRole('dialog')
  await bindDialog.getByText(fileName).click()
  await bindDialog.getByRole('button', { name: /^绑定/ }).click()
  await bindDialog.waitFor({ state: 'hidden', timeout: 15_000 })
  await page.getByText(fileName).first().waitFor({ timeout: 15_000 })
  await page.screenshot({ path: 'shots/e2e-kb-detail.png' })
  console.log('[e2e] document bound to KB')

  // 6. 真实问答
  await page.goto(`${WEB}/chat`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '新对话' }).last().click()
  await page.getByRole('button', { name: /全部知识库/ }).click()
  await page.locator('label').filter({ hasText: kbName }).click()
  await page.locator('[data-testid="composer"]').click()
  await page.fill(
    '[data-testid="composer"]',
    '支付网关最大重试次数是多少？首次重试间隔是多少毫秒？',
  )
  await page.click('[data-testid="send"]')
  console.log('[e2e] chat sent, waiting for answer…')

  await page.waitForFunction(
    () => {
      const text = document.body.innerText
      const hasNumber = text.includes('300') && (text.includes('5') || text.includes('五'))
      const hasCitation = document.querySelectorAll('.cite').length > 0
      const streaming = document.querySelector('.stream-caret')
      return hasNumber && hasCitation && !streaming
    },
    null,
    { timeout: 130_000 },
  )

  const answerText = await page.locator('.chat-markdown').last().innerText()
  assert(answerText.includes('300'), `answer does not contain 300: ${answerText.slice(0, 200)}`)
  assert(/5/.test(answerText) || answerText.includes('五'), 'answer does not contain retry count')
  const citeCount = await page.locator('.cite').count()
  assert(citeCount > 0, 'no citation chips rendered')
  await page.screenshot({ path: 'shots/e2e-chat.png' })
  console.log(`[e2e] chat done, citations=${citeCount}`)

  // 7. 引用联动与原文
  await page.locator('.cite').first().click()
  await page.waitForTimeout(500)
  const sourceButton = page.getByText('查看原文').first()
  assert((await sourceButton.count()) > 0, 'citation source action missing')
  await sourceButton.click()
  await page.waitForTimeout(800)
  const sourceText = await page.locator('.source-preview').first().innerText()
  assert(sourceText.includes('重试'), `source preview mismatch: ${sourceText.slice(0, 120)}`)
  await page.screenshot({ path: 'shots/e2e-citation.png' })
  console.log('[e2e] citation source rendered')

  // 8. 刷新后本地历史恢复
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForFunction(
    () => document.body.innerText.includes('300') && document.querySelectorAll('.cite').length > 0,
    null,
    { timeout: 15_000 },
  )
  await page.screenshot({ path: 'shots/e2e-history-restored.png' })
  console.log('[e2e] local history restored after reload')
} catch (error) {
  failed = true
  console.error(`[e2e] FAILED: ${error.message}`)
  await page.screenshot({ path: 'shots/e2e-failure.png' }).catch(() => {})
  const docs = await apiJson('/api/documents')
  const doc = (docs.data ?? []).find((item) => item.fileName === fileName)
  if (doc) console.error(`[e2e] doc status=${doc.status} error=${doc.errorMessage}`)
} finally {
  await cleanup()
  await browser.close()
  console.log(browserErrors.length ? `[e2e] browser errors: ${browserErrors.join(' | ')}` : '[e2e] browser errors: none')
  process.exitCode = failed ? 1 : 0
}
