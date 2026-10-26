// 验证 v-loading 指令在移除 app.use(ElementPlus) 后仍然注册成功：
// 触发一次真实请求，在 loading 期间断言 .el-loading-mask 出现。
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { mkdirSync, readFileSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const cfg = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const PORT = 9400 + Math.floor(Math.random() * 90)
const OUT = join(process.cwd(), '.shots')
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const child = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars', '--window-size=1440,900',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${join(OUT, `load-${Date.now()}`)}`,
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
const errors = []
ws.onmessage = (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id)
    pending.delete(m.id)
    m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
    return
  }
  if (m.method === 'Runtime.exceptionThrown') {
    const t = m.params.exceptionDetails?.exception?.description || ''
    if (/not a function|is not defined|Cannot read|resolve directive|SyntaxError/i.test(t)) errors.push(t.split('\n')[0])
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
const check = (l, ok, d = '') => { results.push({ l, ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'}  ${l}${d ? '  — ' + d : ''}`) }

await send('Page.navigate', { url: cfg.baseUrl + cfg.page })
await sleep(2600)

// 装一个观察器，记录 .el-loading-mask 是否曾经出现 —— 遮罩可能一闪而过，
// 只在某一瞬间截图容易漏掉。
await evaluate(`
  (() => {
    window.__loadingSeen = false;
    const obs = new MutationObserver(() => {
      if (document.querySelector('.el-loading-mask')) window.__loadingSeen = true;
    });
    obs.observe(document.body, { childList: true, subtree: true, attributes: true });
    window.__loadingObs = obs;
    return 'ok';
  })()
`)

// 点「查询」触发一次真实请求（会在请求期间显示 v-loading）
const clicked = await evaluate(`
  (() => {
    const btns = [...document.querySelectorAll('button')];
    const btn = btns.find(b => /查询|刷新/.test(b.innerText));
    if (!btn) return false;
    btn.click();
    return true;
  })()
`)
check('找到并点击查询/刷新按钮', clicked)

await sleep(400)
const seenNow = await evaluate(`!!document.querySelector('.el-loading-mask')`)
await sleep(1200)
const seenAny = await evaluate(`window.__loadingSeen === true`)

check('v-loading 指令生效（检测到 loading 遮罩）', seenNow || seenAny, `now=${seenNow} any=${seenAny}`)
check('无指令解析错误', errors.length === 0, errors.join(' | '))

// 数据仍然正常加载
const rowCount = await evaluate(`document.querySelectorAll('.el-table__row').length`)
check('表格数据正常加载', rowCount > 0, `rows=${rowCount}`)

console.log(`\n结果: ${results.filter(r => r.ok).length}/${results.length} 通过`)
ws.close()
child.kill()
process.exit(results.every(r => r.ok) ? 0 : 1)
