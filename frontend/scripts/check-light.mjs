import { chromium } from 'playwright-core'

const BASE = process.env.BASE_URL || 'http://127.0.0.1:5176'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })

const errors = []
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})
page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))

async function shot(name) {
  await page.waitForTimeout(350)
  await page.screenshot({ path: `shots/${name}.png`, fullPage: false })
  console.log(`[shot] ${name}`)
}

await page.goto(`${BASE}/chat`, { waitUntil: 'networkidle' })
await page.fill('[data-testid="composer"]', '如何配置支付网关？')
await page.click('[data-testid="send"]')
await page.getByRole('button', { name: /收起引用/ }).waitFor({ timeout: 10000 })
await shot('light-rail-open')

await page.getByRole('button', { name: /收起引用/ }).click()
await page.waitForTimeout(300)
if (await page.getByText('引用来源').last().isVisible().catch(() => false)) {
  throw new Error('citation rail still visible after collapse')
}
await shot('light-rail-closed')

await page.locator('button[aria-label="打开引用栏"]').click()
await page.waitForTimeout(300)
await shot('light-rail-reopened')

await page.getByRole('button', { name: '新对话' }).first().click()
await page.waitForTimeout(500)
await shot('light-new-chat')

const previous = page.locator('aside').first().getByRole('button', { name: /如何配置支付网关/ })
if (await previous.count()) {
  await previous.first().click()
  await page.waitForTimeout(500)
  await shot('light-history-restored')
}

const deleteButtons = page.getByRole('button', { name: '删除会话' })
const sessionsBeforeDelete = await deleteButtons.count()
if (sessionsBeforeDelete > 0) {
  await deleteButtons.first().click({ force: true })
  await page.waitForTimeout(400)
  const sessionsAfterDelete = await deleteButtons.count()
  if (sessionsAfterDelete >= sessionsBeforeDelete) {
    throw new Error(`history delete did not remove a session: ${sessionsBeforeDelete} -> ${sessionsAfterDelete}`)
  }
  await shot('light-history-deleted')
}

await page.getByRole('button', { name: '收起侧栏' }).click()
await page.waitForTimeout(300)
await shot('light-sidebar-collapsed')
await page.getByRole('button', { name: '展开侧栏' }).click()

await page.setViewportSize({ width: 390, height: 844 })
await page.goto(`${BASE}/chat`, { waitUntil: 'networkidle' })
await page.getByRole('button', { name: '打开导航' }).click()
await page.waitForTimeout(350)
await shot('light-mobile-nav')
const overflow = await page.evaluate(
  () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
)
console.log(`[overflow] ${overflow}`)
console.log(`[errors] ${errors.length}`)
for (const error of errors) console.log(error)
await browser.close()
