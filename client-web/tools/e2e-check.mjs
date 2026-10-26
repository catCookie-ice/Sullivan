// 用 Chrome DevTools Protocol 驱动 Edge 做端到端检查：
// 登录拿令牌 → 注入 localStorage → 逐页访问 → 断言关键元素 → 截图 → 汇总运行时错误。
//
// 用法：node tools/e2e-check.mjs <config.json>
// 配置示例见 tools/e2e-config.example.json。
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const cfgPath = process.argv[2]
if (!cfgPath) {
  console.error('用法: node tools/e2e-check.mjs <config.json>')
  process.exit(2)
}
const cfg = JSON.parse(readFileSync(cfgPath, 'utf8'))
const { baseUrl, prefix, loginName, password, principalType, pages } = cfg

const PORT = 9333 + Math.floor(Math.random() * 500)
const OUT = join(process.cwd(), '.shots')
mkdirSync(OUT, { recursive: true })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const child = spawn(
  EDGE,
  [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--hide-scrollbars',
    '--window-size=1440,900',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${join(OUT, `cdp-${prefix}-${Date.now()}`)}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
)

async function getWsUrl() {
  for (let i = 0; i < 80; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`)
      const list = await res.json()
      const page = list.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl
    } catch {
      /* 还没起来 */
    }
    await sleep(250)
  }
  throw new Error('DevTools 未就绪')
}

const ws = new WebSocket(await getWsUrl())
await new Promise((res, rej) => {
  ws.onopen = res
  ws.onerror = rej
})

let msgId = 0
const pending = new Map()
const runtimeErrors = []

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
    return
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    const d = msg.params.exceptionDetails
    const text = d.exception?.description || d.text || ''
    // 只关心真正的代码缺陷；业务 401/403 由界面自己提示，不算前端 bug。
    if (/is not a function|is not defined|Cannot read|SyntaxError|Failed to resolve|hydration/i.test(text)) {
      runtimeErrors.push(text.split('\n')[0])
    }
  }
}

function send(method, params = {}) {
  const id = ++msgId
  ws.send(JSON.stringify({ id, method, params }))
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }))
}

await send('Page.enable')
await send('Runtime.enable')

async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(cfg.settleMs || 2200)
}

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  })
  if (r.exceptionDetails) {
    throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description || ''))
  }
  return r.result.value
}

async function shot(name) {
  const r = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(OUT, `${prefix}-${name}.png`), Buffer.from(r.data, 'base64'))
}

const results = []
function check(label, ok, detail = '') {
  results.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  — ' + detail : ''}`)
}

try {
  // —— 登录态：优先用配置里的现成令牌，否则走接口登录 ——
  await goto(`${baseUrl}/login`)
  check('登录页渲染', await evaluate(`!!document.querySelector('.login-card')`))

  let identity = null
  if (cfg.token) {
    await evaluate(`
      localStorage.setItem('access_token', ${JSON.stringify(cfg.token)});
      'ok'
    `)
    identity = cfg.identity || { principalId: '', roles: [], permissions: [] }
    check('注入调试令牌', true, `roles=${(identity.roles || []).join(',')}`)
  } else {
    const raw = await evaluate(`
      (async () => {
        const res = await fetch('/api/v1/auth/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            principalType: ${JSON.stringify(principalType)},
            loginName: ${JSON.stringify(loginName)},
            password: ${JSON.stringify(password)}
          })
        });
        if (!res.ok) return 'HTTP ' + res.status;
        return JSON.stringify(await res.json());
      })()
    `)
    if (typeof raw === 'string' && raw.startsWith('{')) {
      identity = JSON.parse(raw)
      check('登录成功', true, `roles=${(identity.roles || []).join(',')}`)
    } else {
      check('登录成功', false, String(raw))
    }
  }

  if (identity) {
    // 前端的 auth store 从这几个键恢复会话，键名必须与 utils/session.ts 里的
    // AUTH_STORAGE_KEYS 完全一致，否则路由守卫会把人踢回登录页。
    await evaluate(`
      localStorage.setItem('access_token', ${JSON.stringify(cfg.token || '')} || localStorage.getItem('access_token'));
      localStorage.setItem('principal_id', ${JSON.stringify(identity.principalId || cfg.principalId || '')});
      localStorage.setItem('principal_type', ${JSON.stringify(identity.principalType || principalType)});
      localStorage.setItem('roles', ${JSON.stringify(JSON.stringify(identity.roles || []))});
      localStorage.setItem('permissions', ${JSON.stringify(JSON.stringify(identity.permissions || []))});
      'ok'
    `)
  }

  // —— 逐页检查 ——
  for (const p of pages) {
    await goto(baseUrl + p.path)
    let ok = true
    let detail = ''
    if (p.selector) {
      ok = await evaluate(`!!document.querySelector(${JSON.stringify(p.selector)})`)
      if (!ok) detail = `缺少 ${p.selector}`
    }
    if (ok && p.mustContain) {
      for (const text of p.mustContain) {
        const found = await evaluate(`document.body.innerHTML.includes(${JSON.stringify(text)})`)
        if (!found) {
          ok = false
          detail = `缺少文案「${text}」`
          break
        }
      }
    }
    if (ok && p.mustNotContain) {
      for (const text of p.mustNotContain) {
        const found = await evaluate(`document.body.innerHTML.includes(${JSON.stringify(text)})`)
        if (found) {
          ok = false
          detail = `不应出现「${text}」`
          break
        }
      }
    }
    check(`${p.path}`, ok, detail)
    if (p.shot !== false) await shot(p.name || p.path.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, ''))
  }

  // —— 窄屏：导航应切换成抽屉按钮，且不出现横向溢出 ——
  if (cfg.checkNarrow) {
    await send('Emulation.setDeviceMetricsOverride', {
      width: 390,
      height: 844,
      deviceScaleFactor: 2,
      mobile: true,
    })
    await goto(baseUrl + cfg.checkNarrow)
    const hasToggle = await evaluate(`!!document.querySelector('.app-nav__toggle')`)
    const noOverflow = await evaluate(
      `document.documentElement.scrollWidth <= document.documentElement.clientWidth + 2`,
    )
    check('窄屏出现抽屉按钮', hasToggle)
    check('窄屏无横向溢出', noOverflow, await evaluate(`document.documentElement.scrollWidth + '/' + document.documentElement.clientWidth`))
    await shot('narrow')
    await send('Emulation.clearDeviceMetricsOverride')
  }
} finally {
  console.log('\n--- 运行时错误 ---')
  if (!runtimeErrors.length) console.log('（无）')
  else runtimeErrors.forEach((e) => console.log('  ' + e))

  const passed = results.filter((r) => r.ok).length
  console.log(`\n结果: ${passed}/${results.length} 通过`)
  writeFileSync(join(OUT, `${prefix}-report.json`), JSON.stringify({ results, runtimeErrors }, null, 2), 'utf8')

  ws.close()
  child.kill()
  process.exit(passed === results.length && !runtimeErrors.length ? 0 : 1)
}
