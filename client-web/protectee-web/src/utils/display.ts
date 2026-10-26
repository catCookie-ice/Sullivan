/**
 * 界面展示用的格式化函数。
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

/** 会话类型的中文名。EMERGENCY 虽然客户端不能创建，但可能作为接收方收到。 */
export function sessionTypeLabel(type?: string | null): string {
  switch (type) {
    case 'BASIC':
      return '文本会话'
    case 'VOICE':
      return '语音通话'
    case 'VIDEO':
      return '视频通话'
    case 'CAMERA':
      return '摄像头查看'
    case 'EMERGENCY':
      return '紧急会话'
    default:
      return type || '未知类型'
  }
}

/** 会话类型对应的标签色：紧急用红色，避免与普通通话混为一谈。 */
export function sessionTypeTagType(type?: string | null): 'danger' | 'warning' | 'success' | 'info' {
  switch (type) {
    case 'EMERGENCY':
      return 'danger'
    case 'CAMERA':
      return 'warning'
    case 'VOICE':
    case 'VIDEO':
      return 'success'
    default:
      return 'info'
  }
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

/** 录音时长：mm:ss，超过 1 小时用 h:mm:ss。 */
export function formatDuration(durationMs?: number | null): string {
  if (durationMs == null || durationMs < 0) return '—'
  const total = Math.round(durationMs / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60)
    return `${hours}:${String(minutes % 60).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  }
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

/** 文件大小：给用户看 kB/MB 比给字节数有意义。 */
export function formatBytes(bytes?: number | null): string {
  if (bytes == null || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} kB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
