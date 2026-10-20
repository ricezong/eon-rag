import { chromium } from 'playwright-core'

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5174'
const API = process.env.API_URL || 'http://127.0.0.1:8080'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14)
const kbName = `CRUD验收-${stamp}`
const kbEdited = `${kbName}-已编辑`
const fileName = `crud-${stamp}.md`

const fileContent = `# CRUD 验收文档\n\n## 配置说明\n\n这是一个用于验证文档删除与解绑的临时文档。\n重复内容用于产生足够 token。\n`.repeat(3)

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function apiJson(path, init) {
  const res = await fetch(`${API}${path}`, init)
  return res.json().catch(() => ({}))
}

async function cleanup() {
  try {
    const docs = await apiJson('/api/documents')
    for (const doc of docs.data ?? []) {
      if (doc.fileName === fileName) await fetch(`${API}/api/documents/${doc.id}`, { method: 'DELETE' })
    }
    const kbs = await apiJson('/api/kbs')
    for (const kb of kbs.data ?? []) {
      if (kb.name === kbName || kb.name === kbEdited) {
        await fetch(`${API}/api/kbs/${kb.id}`, { method: 'DELETE' })
      }
    }
  } catch (error) {
    console.log(`[cleanup] ${error.message}`)
  }
}

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })
const errors = []
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})

let failed = false
try {
  // 1. 新建知识库
  await page.goto(`${WEB}/kbs`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '新建知识库' }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('#kb-name').fill(kbName)
  await dialog.locator('#kb-description').fill('CRUD 验收临时知识库')
  await dialog.getByRole('button', { name: '创建知识库' }).click()
  await page.waitForFunction((name) => document.body.innerText.includes(name), kbName)
  console.log('[crud] KB created')

  // 2. 上传文档
  await page.goto(`${WEB}/documents`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '上传文档' }).first().click()
  const upload = page.getByRole('dialog')
  await upload.locator('input[type="file"]').setInputFiles({
    name: fileName,
    mimeType: 'text/markdown',
    buffer: Buffer.from(fileContent),
  })
  await upload.locator('#parse-mode').selectOption('LOCAL')
  await upload.getByRole('button', { name: /开始上传/ }).click()
  await page.waitForTimeout(1200)
  const finish = upload.getByRole('button', { name: '完成', exact: true })
  if (await finish.count()) await finish.click()
  await page.waitForFunction(
    (name) => {
      const row = [...document.querySelectorAll('tbody tr')].find((item) =>
        item.textContent?.includes(name),
      )
      return Boolean(row?.textContent?.includes('待向量化'))
    },
    fileName,
    { timeout: 120_000 },
  )
  console.log('[crud] document chunked (pending indexing)')

  const docs = await apiJson('/api/documents')
  const doc = (docs.data ?? []).find((item) => item.fileName === fileName)
  assert(doc, 'uploaded document missing')
  const kbs = await apiJson('/api/kbs')
  const kb = (kbs.data ?? []).find((item) => item.name === kbName)
  assert(kb, 'created KB missing')

  // 3. 绑定 / 解绑
  await page.goto(`${WEB}/kbs`, { waitUntil: 'networkidle' })
  await page.locator('article').filter({ hasText: kbName }).click()
  await page.getByRole('button', { name: '绑定文档' }).first().click()
  const bind = page.getByRole('dialog')
  await bind.getByText(fileName).click()
  await bind.getByRole('button', { name: /^绑定/ }).click()
  await bind.waitFor({ state: 'hidden' })
  await page.getByText(fileName).first().waitFor()
  console.log('[crud] bound')

  await page.getByRole('button', { name: '解除绑定' }).first().click()
  const unbindConfirm = page.getByRole('dialog')
  await unbindConfirm.getByRole('button', { name: '解除绑定', exact: true }).click()
  await page.waitForFunction((name) => !document.body.innerText.includes(name), fileName)
  const afterUnbind = await apiJson(`/api/kbs/${kb.id}/documents`)
  assert((afterUnbind.data ?? []).length === 0, 'unbind did not remove document from KB')
  console.log('[crud] unbound')

  // 4. 编辑知识库
  await page.getByRole('button', { name: '编辑' }).click()
  const edit = page.getByRole('dialog')
  await edit.locator('#kb-name').fill(kbEdited)
  await edit.getByRole('button', { name: '保存修改' }).click()
  await page.waitForFunction((name) => document.body.innerText.includes(name), kbEdited)
  console.log('[crud] KB edited')

  // 5. 删除文档（详情页）
  await page.goto(`${WEB}/documents/${doc.id}`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '删除' }).first().click()
  const docDelete = page.getByRole('dialog')
  await docDelete.getByRole('button', { name: '删除', exact: true }).click()
  await page.waitForURL('**/documents')
  const docsAfter = await apiJson('/api/documents')
  assert(!(docsAfter.data ?? []).some((item) => item.id === doc.id), 'document still exists after UI delete')
  console.log('[crud] document deleted')

  // 6. 删除知识库（详情页）
  const kbsAfter = await apiJson('/api/kbs')
  const editedKb = (kbsAfter.data ?? []).find((item) => item.name === kbEdited)
  assert(editedKb, 'edited KB missing')
  await page.goto(`${WEB}/kbs/${editedKb.id}`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '删除' }).first().click()
  const kbDelete = page.getByRole('dialog')
  await kbDelete.getByRole('button', { name: '删除', exact: true }).click()
  await page.waitForURL('**/kbs')
  const kbsFinal = await apiJson('/api/kbs')
  assert(!(kbsFinal.data ?? []).some((item) => item.id === editedKb.id), 'KB still exists after UI delete')
  console.log('[crud] KB deleted')
} catch (error) {
  failed = true
  console.error(`[crud] FAILED: ${error.message}`)
  await page.screenshot({ path: 'shots/e2e-crud-failure.png' }).catch(() => {})
} finally {
  await cleanup()
  await browser.close()
  console.log(errors.length ? `[crud] browser errors: ${errors.join(' | ')}` : '[crud] browser errors: none')
  process.exitCode = failed ? 1 : 0
}
