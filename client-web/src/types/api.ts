// 通用 API 响应类型
export interface ApiResponse<T> {
  success: boolean
  data: T
  error?: ApiError
}

export interface ApiError {
  code: string
  message: string
  traceId: string
}

// 翻页
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

// 令牌
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  principalId: string
  role: string
}
