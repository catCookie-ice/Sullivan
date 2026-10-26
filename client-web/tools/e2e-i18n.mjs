// 语言包回归：确认去掉 app.use(ElementPlus) 后，分页/空表格等
// Element Plus 内置文案仍然是中文（靠 App.vue 的 ConfigProvider 生效）。
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { mkdirSync, readFileSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const cfg = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const PORT = 9500 + Math.floor(Math.random() * 80)
const OUT = join(process.cwd(), '.shots')
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const child = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
  '--window-size=1440,900',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${join(OUT, `i18n-${Date.now()}`)}`,
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

const results = []
const check = (label, ok, detail = '') => {
  results.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  — ' + detail : ''}`)
}

for (const page of cfg.i18nPages) {
  await send('Page.navigate', { url: cfg.baseUrl + page })
  await sleep(2600)

  const bodyText = await evaluate(`document.body.innerText`)

  // Element Plus 分页的中文文案是「共 N 条」「前往」「页」；英文包是
  // "Total N" / "Go to" / "page"。只要出现英文这些词就说明语言包没生效。
  const pag = await evaluate(`
    (() => {
      const p = document.querySelector('.el-pagination');
      return p ? p.innerText.replace(/\\s+/g, ' ') : null;
    })()
  `)
  if (pag !== null) {
    check(`${page} 分页文案为中文`,
      !/Total|Go to|page size/i.test(pag) && (pag.length === 0 || /共|条|页|前往|条\/页/.test(pag)),
      pag.slice(0, 90))
  } else {
    check(`${page} 无分页组件（跳过）`, true)
  }

  // 表格空数据默认文案：「暂无数据」；英文为 "No Data"。
  const emptyText = await evaluate(`
    (() => {
      const e = document.querySelector('.el-table__empty-text');
      return e ? e.innerText.trim() : null;
    })()
  `)
  if (emptyText !== null) {
    check(`${page} 表格空态为中文`, /暂无数据|没有|无/.test(emptyText), emptyText)
  }

  check(`${page} 无英文 Element 文案`,
    !/Total \\d|Go to|No Data|Loading/i.test(bodyText),
    (bodyText.match(/Total \d+|Go to|No Data|Loading/g) || []).join(','))
}

console.log(`\n结果: ${results.filter(r => r.ok).length}/${results.length} 通过`)
ws.close()
child.kill()
process.exit(results.every(r => r.ok) ? 0 : 1)
