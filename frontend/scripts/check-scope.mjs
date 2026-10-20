import { chromium } from 'playwright-core'

const WEB = process.env.WEB_URL || 'http://127.0.0.1:5174'
const CHROME =
  process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const page = await browser.newPage({ viewport: { width: 1440, height: 960 } })

await page.goto(`${WEB}/chat`, { waitUntil: 'networkidle' })
await page.getByRole('button', { name: /全部知识库/ }).click()
const panel = page.getByText('检索范围')
await panel.waitFor({ timeout: 5000 })
await page.screenshot({ path: 'shots/scope-open.png' })

await page.locator('[data-testid="composer"]').click()
await page.waitForTimeout(350)
const visible = await panel.isVisible().catch(() => false)
console.log(`scope panel visible after composer click: ${visible}`)
await page.screenshot({ path: 'shots/scope-closed.png' })

await browser.close()
process.exitCode = visible ? 1 : 0
