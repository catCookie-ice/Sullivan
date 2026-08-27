/**
 * keep_auto 表单草稿自动保存的工具集。
 *
 * 存储布局（localStorage）：
 *   - 键：`sullivan:keep_auto:<path>`，其中 <path> 由保存键（route.path 或 saveKey）派生。
 *   - 值：一个 JSON 字符串 `{"data": <草稿对象>, "updatedAt": <ISO 时间>}`。
 *
 * 各函数均为纯工具，不含 Vue 响应式；组件（KeepAutoForm.vue）负责编排保存/恢复/清除时序。
 */

/** 存储键前缀，避免与 auth 等应用裸键冲突。 */
const KEY_PREFIX = 'sullivan:keep_auto:'

/** 保存键 → 规范化存储键。保存键中可能含 `/`、`:` 等，安全转换为可读形式。 */
export function buildStorageKey(saveKey: string): string {
  return KEY_PREFIX + saveKey.replace(/[^a-zA-Z0-9._-]/g, '_')
}

/** 从对象中按点分路径取值（如 `user.contact.phone`），不存在返回 undefined。 */
export function getPath<T = any>(obj: Record<string, any>, path: string): T | undefined {
  if (!path) return undefined
  return path.split('.').reduce<any>((acc, key) => (acc == null ? undefined : acc[key]), obj)
}

/** 按点分路径给对象赋值（如 `setPath(obj, 'user.contact.phone', 'x')`）。 */
export function setPath(obj: Record<string, any>, path: string, value: any): void {
  const keys = path.split('.')
  let cur = obj
  for (let i = 0; i < keys.length - 1; i++) {
    const k = keys[i]
    if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {}
    cur = cur[k]
  }
  cur[keys[keys.length - 1]] = value
}

/**
 * 深拷贝后剔除指定点分路径（用于排除 password 等敏感字段），返回新对象，不改原对象。
 * 深拷贝保证对嵌套路径的 delete 不会污染原 model。
 */
export function omitPaths(source: Record<string, any>, paths: string[]): Record<string, any> {
  const result: Record<string, any> = JSON.parse(JSON.stringify(source ?? {}))
  for (const p of paths) {
    const keys = p.split('.')
    const parent = keys.length > 1 ? getPath<Record<string, any>>(result, keys.slice(0, -1).join('.')) : result
    if (parent && typeof parent === 'object') delete parent[keys[keys.length - 1]]
  }
  return result
}

/** 深比较两个 JSON 值是否相等（草稿中的值均为 JSON 可序列化类型）。 */
export function isEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

/** 草稿是否"空"：无 data 或 data 的所有字段为空字符串/undefined/null。 */
export function isDraftEmpty(data: Record<string, any> | undefined | null): boolean {
  if (!data) return true
  const values = Object.values(data)
  if (values.length === 0) return true
  return values.every((v) => v == null || v === '')
}

/** 读取草稿；无草稿或 JSON 损坏返回 null。 */
export function readDraft(saveKey: string): Record<string, any> | null {
  try {
    const raw = localStorage.getItem(buildStorageKey(saveKey))
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && 'data' in parsed ? (parsed.data as Record<string, any>) : null
  } catch {
    return null
  }
}

/** 写草稿。 */
export function writeDraft(saveKey: string, data: Record<string, any>): void {
  try {
    localStorage.setItem(
      buildStorageKey(saveKey),
      JSON.stringify({ data, updatedAt: new Date().toISOString() }),
    )
  } catch {
    // localStorage 不可用（隐私模式/配额满）时静默失败，不影响表单使用。
  }
}

/** 清除草稿。 */
export function removeDraft(saveKey: string): void {
  try {
    localStorage.removeItem(buildStorageKey(saveKey))
  } catch {
    // 同上，静默失败。
  }
}

export interface Debounced {
  (): void
  /** 立即执行尚未触发的最末一次调用。 */
  flush(): void
  /** 取消尚未触发的调用。 */
  cancel(): void
}

/**
 * 简单防抖：fn 在 wait 毫秒内被多次调用时只执行最后一次。
 * 返回带 flush / cancel 的防抖函数；flush 立即执行、cancel 取消待执行调用。
 * 本实现面向"保存草稿"这类零参数回调。
 */
export function debounce(fn: () => void, wait: number): Debounced {
  let timer: ReturnType<typeof setTimeout> | null = null
  const debounced = (() => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      fn()
    }, wait)
  }) as Debounced
  debounced.flush = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
      fn()
    }
  }
  debounced.cancel = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }
  return debounced
}

/** 子组件（KeepAutoFormItem）通过 provide/inject 与父组件（KeepAutoForm）通信的上下文。 */
export interface KeepAutoFormContext {
  registerField: (f: { prop: string; keepAuto: boolean }) => void
  unregisterField: (prop: string) => void
}

/** provide/inject 用的注入键。 */
export const KEEP_AUTO_FORM_KEY: unique symbol = Symbol('keepAutoForm')
