// 窄屏抽屉交互验证：点汉堡按钮 → 断言抽屉出现、菜单项可点、焦点可进入。
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { mkdirSync, writeFileSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const cfgPath = process.argv[2]
const { readFileSync } = await import('node:fs')
const cfg = JSON.parse(readFileSync(cfgPath, 'utf8'))

const PORT = 9700 + Math.floor(Math.random() * 100)
const OUT = join(process.cwd(), '.shots')
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const child = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
  '--window-size=390,844',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${join(OUT, `dr-${Date.now()}`)}`,
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

const results = []
const check = (label, ok, detail = '') => {
  results.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  — ' + detail : ''}`)
}

await send('Page.navigate', { url: cfg.baseUrl + '/login' })
await sleep(2500)

// 注入登录态
await evaluate(`
  localStorage.setItem('access_token', ${JSON.stringify(cfg.token)});
  localStorage.setItem('principal_id', ${JSON.stringify(cfg.principalId)});
  localStorage.setItem('principal_type', ${JSON.stringify(cfg.identity.principalType)});
  localStorage.setItem('roles', ${JSON.stringify(JSON.stringify(cfg.identity.roles))});
  localStorage.setItem('permissions', ${JSON.stringify(JSON.stringify(cfg.identity.permissions))});
  'ok'
`)

await send('Emulation.setDeviceMetricsOverride', {
  width: 390, height: 844, deviceScaleFactor: 2, mobile: true,
})
await send('Page.navigate', { url: cfg.baseUrl + cfg.checkNarrow })
await sleep(2600)

check('汉堡按钮存在', await evaluate(`!!document.querySelector('.app-nav__toggle')`))
check('桌面菜单已隐藏', await evaluate(`!document.querySelector('.app-nav__desktop')`))
check('aria-expanded 初始为 false', await evaluate(
  `document.querySelector('.app-nav__toggle').getAttribute('aria-expanded') === 'false'`,
))

// 点开抽屉
await evaluate(`document.querySelector('.app-nav__toggle').click(); 'ok'`)
await sleep(1200)

check('抽屉已打开', await evaluate(`!!document.querySelector('#app-nav-drawer')`))
check('aria-expanded 变为 true', await evaluate(
  `document.querySelector('.app-nav__toggle').getAttribute('aria-expanded') === 'true'`,
))
const links = await evaluate(
  `[...document.querySelectorAll('.app-nav__drawer-link')].map(a => a.textContent.trim())`,
)
check('抽屉内有菜单项', links.length > 0, links.join(' / '))

const activeCount = await evaluate(
  `document.querySelectorAll('.app-nav__drawer-link.is-active').length`,
)
check('当前页高亮且唯一', activeCount === 1, `active=${activeCount}`)

const shot = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(join(OUT, `${cfg.prefix}-drawer.png`), Buffer.from(shot.data, 'base64'))

// 点一个菜单项，确认跳转并自动关闭抽屉
const firstHref = await evaluate(`
  (() => {
    const a = [...document.querySelectorAll('.app-nav__drawer-link')].find(x => !x.classList.contains('is-logout'));
    return a ? a.getAttribute('href') : null;
  })()
`)
if (firstHref) {
  await evaluate(`
    (() => {
      const a = [...document.querySelectorAll('.app-nav__drawer-link')].find(x => !x.classList.contains('is-logout'));
      a.click(); return 'ok';
    })()
  `)
  await sleep(1800)
  const path = await evaluate(`location.pathname`)
  check('点击菜单可跳转', path === firstHref, `${path}`)
  // el-drawer 默认 destroy-on-close=false，关闭后节点仍留在 DOM 里（只是隐藏），
  // 所以不能用「节点是否存在」判断，要看它实际是否还可见。
  const stillVisible = await evaluate(`
    (() => {
      const el = document.querySelector('#app-nav-drawer');
      if (!el) return false;
      const box = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      const overlay = el.closest('.el-overlay');
      const overlayHidden = !overlay || getComputedStyle(overlay).display === 'none';
      return !overlayHidden && box.width > 0 && style.visibility !== 'hidden';
    })()
  `)
  check('跳转后抽屉关闭', !stillVisible)
}

console.log(`\n结果: ${results.filter(r => r.ok).length}/${results.length} 通过`)
ws.close()
child.kill()
process.exit(results.every(r => r.ok) ? 0 : 1)
