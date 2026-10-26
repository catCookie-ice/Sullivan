// 诊断：手写字体对数字/汉字的字形覆盖情况。
// 用法：node tools/font-probe.mjs <url>
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { mkdirSync } from 'node:fs'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const url = process.argv[2] || 'http://localhost:5178/login'
const PORT = 9800 + Math.floor(Math.random() * 150)
const OUT = join(process.cwd(), '.shots')
mkdirSync(OUT, { recursive: true })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const child = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--no-sandbox',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${join(OUT, `fp-${Date.now()}`)}`,
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
await send('Page.navigate', { url })
await sleep(3500)

const result = await evaluate(`(async () => {
  // 等字体就绪，最多 5 秒。
  try { await Promise.race([document.fonts.ready, new Promise(r => setTimeout(r, 5000))]) } catch {}

  const family = getComputedStyle(document.body).getPropertyValue('--font-hand').trim();
  const loaded = [...document.fonts].filter(f => f.status === 'loaded').map(f => f.family + ' ' + f.weight);

  // 用 canvas 逐个字符测量：若渲染宽度与「豆腐块」宽度一致或为 0，说明缺字形。
  const c = document.createElement('canvas');
  const ctx = c.getContext('2d');

  function measure(ch, font) {
    ctx.font = '48px ' + font;
    const w = ctx.measureText(ch).width;
    // 用两个不可能相同的字符做参照，检测 fallback 是否被触发
    return w;
  }

  const chars = ['0','1','2','3','4','5','6','7','8','9','以','上','家','人'];
  const handWidths = {}, sysWidths = {};
  for (const ch of chars) {
    handWidths[ch] = +measure(ch, "${'ZCOOL KuaiLe'}").toFixed(2);
    sysWidths[ch]  = +measure(ch, "sans-serif").toFixed(2);
  }

  const checks = {};
  for (const fam of ['ZCOOL KuaiLe', 'Ma Shan Zheng']) {
    checks[fam] = document.fonts.check('16px "' + fam + '"');
  }

  return { family, loaded, handWidths, sysWidths, checks };
})()`)

console.log(JSON.stringify(result, null, 2))
ws.close()
child.kill()
