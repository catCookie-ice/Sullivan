// 交互验证：org-web 活动管理的「详情」弹窗。
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const cfg = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const PORT = 9600 + Math.floor(Math.random() * 90)
const OUT = join(process.cwd(), '.shots')
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const child = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
  '--window-size=1440,900',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${join(OUT, `dlg-${Date.now()}`)}`,
  'about:blank',
], { stdio: 'ignore' })

async function wsUrl() {
  for (let i = 0; i < 80; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
      const p = list.find((t) => t.type === 'page')
      if (p?.webSocketDebuggerUrl) return p.webSocketDebuggerUrl
    } catch {}
    await sleep(250)
  }
  throw new Error('devtools timeout')
}

const ws = new WebSocket(await wsUrl())
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j })
let id = 0
const pending = new Map()
ws.onmessage = (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id)
    pending.delete(m.id)
    m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
  }
}
const send = (method, params = {}) => {
  const i = ++id
  ws.send(JSON.stringify({ id: i, method, params }))
  return new Promise((res, rej) => pending.set(i, { resolve: res, reject: rej }))
}
const evaluate = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text)
  return r.result.value
}

await send('Page.enable')
await send('Runtime.enable')
await send('Page.navigate', { url: cfg.baseUrl + '/login' })
await sleep(2500)

await evaluate(`
  localStorage.setItem('access_token', ${JSON.stringify(cfg.token)});
  localStorage.setItem('principal_id', ${JSON.stringify(cfg.principalId)});
  localStorage.setItem('principal_type', ${JSON.stringify(cfg.identity.principalType)});
  localStorage.setItem('roles', ${JSON.stringify(JSON.stringify(cfg.identity.roles))});
  localStorage.setItem('permissions', ${JSON.stringify(JSON.stringify(cfg.identity.permissions))});
  'ok'
`)

await send('Page.navigate', { url: cfg.baseUrl + cfg.page })
await sleep(2800)

const results = []
const check = (label, ok, detail = '') => {
  results.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  — ' + detail : ''}`)
}

check('详情按钮存在', await evaluate(`!!document.querySelector('.el-table__row .el-button')`))

await evaluate(`
  (() => {
    const btn = document.querySelector('.el-table__row .el-button');
    if (!btn) return 'no-btn';
    btn.click();
    return 'clicked';
  })()
`)
await sleep(1200)

const dialogShown = await evaluate(`
  (() => {
    const d = document.querySelector('.el-dialog');
    if (!d) return false;
    const ov = d.closest('.el-overlay');
    return !!ov && getComputedStyle(ov).display !== 'none' && d.getBoundingClientRect().width > 0;
  })()
`)
check('详情弹窗打开', dialogShown)

const dialogText = await evaluate(`
  (() => {
    const d = document.querySelector('.el-dialog');
    return d ? d.textContent.replace(/\\s+/g, ' ').trim().slice(0, 400) : '';
  })()
`)
check('弹窗含活动名', dialogText.includes('朝阳公园视障徒步陪伴'), dialogText.slice(0, 80))
check('弹窗含活动说明', dialogText.includes('陪伴视障人士'))
check('弹窗含坐标', /116\.\d+/.test(dialogText))

const shot = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(join(OUT, `${cfg.prefix}-detail-dialog.png`), Buffer.from(shot.data, 'base64'))

console.log(`\n结果: ${results.filter(r => r.ok).length}/${results.length} 通过`)
ws.close()
child.kill()
process.exit(results.every(r => r.ok) ? 0 : 1)
