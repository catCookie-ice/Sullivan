import axios from 'axios'
import { clearAuthStorage } from '@/utils/session'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 401 只跳一次登录页。
 *
 * 页面加载时常常并发好几个请求，令牌一过期就会一起 401。
 * 若每个都执行 `window.location.href = '/login'`，浏览器会连续发起多次整页跳转，
 * 表现为登录页反复刷新、甚至把刚要显示的错误提示冲掉。
 * 这里用模块级开关保证只跳一次。
 */
let redirectingToLogin = false

request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      // 只用 clearAuthStorage，不用 localStorage.clear()：
      // 后者会连 @shared/keep-auto 的表单草稿一起抹掉。
      clearAuthStorage()
      if (!redirectingToLogin && window.location.pathname !== '/login') {
        redirectingToLogin = true
        // 记下来源页，登录后可以回到原处；仅用于同源相对路径。
        const from = window.location.pathname + window.location.search
        window.location.href = `/login?redirect=${encodeURIComponent(from)}`
      }
    }
    return Promise.reject(error)
  },
)

export default request
