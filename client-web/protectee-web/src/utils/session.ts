/**
 * 会话存储的读写边界。
 *
 * 为什么单独一个模块：登出与 401 拦截都需要「清掉登录态」，但**不能**用
 * `localStorage.clear()` —— 那是按源清空，会把同源下所有键一起抹掉，
 * 包括 `@shared/keep-auto` 存的表单草稿（`sullivan:keep_auto:*`）。
 * 用户填了一半的活动表单，因为一次令牌过期就被清空，是不可接受的。
 *
 * 这里显式列出属于登录态的键，只删这些。
 */

/** 属于登录态、登出时必须清除的键。新增登录态字段时同步加到这里。 */
export const AUTH_STORAGE_KEYS = [
  'access_token',
  'principal_id',
  'principal_type',
  'roles',
  'permissions',
] as const

/** 只清登录态，保留草稿等其它同源数据。 */
export function clearAuthStorage(): void {
  for (const key of AUTH_STORAGE_KEYS) {
    localStorage.removeItem(key)
  }
}

/** 当前是否持有令牌（不校验是否过期，过期由服务端 401 兜底）。 */
export function hasAccessToken(): boolean {
  return Boolean(localStorage.getItem('access_token'))
}
