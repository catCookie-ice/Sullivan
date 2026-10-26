/**
 * 请求错误的统一取文案逻辑。
 *
 * 后端所有错误都走 `ApiErrorHandler`，响应体形如 `{ "error": "中文说明" }`。
 * 但不是所有失败都有响应体：
 *   - 断网 / 后端未启动 → 没有 response；
 *   - 超时 → `code === 'ECONNABORTED'`；
 *   - 网关 502/504 → 响应体是 HTML，`data.error` 不存在。
 * 直接写 `e.response?.data?.error || '操作失败'` 这三类都会退化成笼统的兜底文案，
 * 用户看不出是「网断了」还是「参数错了」。这里把它们分开。
 */

export function errorMessage(error: any, fallback = '操作失败'): string {
  // 业务错误：后端明确给了中文说明，优先用。
  const serverMessage = error?.response?.data?.error
  if (typeof serverMessage === 'string' && serverMessage.trim()) {
    return serverMessage
  }

  // 超时：axios 的 ECONNABORTED，或请求在途被主动取消（AbortController）。
  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return '请求超时，请检查网络后重试'
  }
  if (error?.code === 'ERR_CANCELED') {
    return '请求已取消'
  }

  // 没有 response：请求根本没到达服务端。
  if (!error?.response) {
    return '网络异常，无法连接服务器，请稍后重试'
  }

  // 有状态码但没有可读的 error 字段（网关页、静态 404 等）。
  const status = error.response.status
  if (status === 401) return '登录已失效，请重新登录'
  if (status === 403) return '没有权限执行该操作'
  if (status === 404) return '请求的资源不存在'
  if (status >= 500) return '服务器开小差了，请稍后重试'
  return fallback
}
