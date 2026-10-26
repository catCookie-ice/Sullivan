/**
 * 界面展示用的格式化函数（管理端/机构端共用同一口径）。
 *
 * 核心约定：**自然人一律用「显示名 / 登录名」展示，绝不展示 uid**。
 * uid 只是服务端保证唯一性的内部标识，出现在界面上既不可读也不该被用户抄写。
 */

/** 主显示名：显示名 > 登录名 > 未知用户。两者都空说明数据有问题，也要给一个可读兜底。 */
export function personLabel(displayName?: string | null, loginName?: string | null): string {
  const name = (displayName || '').trim()
  if (name) return name
  const login = (loginName || '').trim()
  if (login) return login
  return '未知用户'
}

/**
 * 次级说明：主显示名用的是显示名时，把登录名补在括号里，
 * 便于同名时区分（例如两个「张阿姨」）。主显示名本身就是登录名时不再重复。
 */
export function personSubLabel(displayName?: string | null, loginName?: string | null): string {
  const name = (displayName || '').trim()
  const login = (loginName || '').trim()
  if (!login) return ''
  if (!name || name === login) return ''
  return `（${login}）`
}

/**
 * 时间展示。
 *
 * 服务端把所有 LocalDateTime 序列化成 `yyyy-MM-dd HH:mm:ss`（空格分隔，不是 ISO 的 T），
 * 交给 `new Date()` 在部分浏览器里会被当成 UTC 而偏 8 小时，因此**原样展示**，不做时区换算。
 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

/**
 * 把服务端时间串解析成毫秒时间戳，仅用于「距今多久」「还差几天」这类比较。
 *
 * 不做 `new Date('2026-08-01 09:00:00')`：空格分隔的写法在 Safari / 部分内核里
 * 会解析成非法日期（NaN），在 Chrome 里又按本地时区解释，同一份代码两种结果。
 * 这里显式按 `年-月-日 时:分:秒` 拆分，走本地时区构造，各浏览器一致。
 */
export function parseServerDateTime(value?: string | null): number | null {
  if (!value) return null
  const m = String(value)
    .replace('T', ' ')
    .match(/^(\d{4})-(\d{2})-(\d{2})(?:[ ](\d{2}):(\d{2})(?::(\d{2}))?)?/)
  if (!m) {
    const fallback = new Date(value).getTime()
    return Number.isNaN(fallback) ? null : fallback
  }
  const [, y, mo, d, h, mi, s] = m
  return new Date(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h || 0),
    Number(mi || 0),
    Number(s || 0),
  ).getTime()
}

/** 距今多少个整天（向下取整）；解析失败返回 null，由调用方决定怎么显示。 */
export function daysSince(value?: string | null): number | null {
  const ts = parseServerDateTime(value)
  if (ts == null) return null
  return Math.floor((Date.now() - ts) / 86400000)
}

/** 站内跳转用的规范化绝对路径：确保以 `/` 开头，避免出现 `//users` 这种被服务器判成协议相对的写法。 */
export function normalizePath(path: string): string {
  const trimmed = (path || '').trim()
  if (!trimmed) return '/'
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`
}
