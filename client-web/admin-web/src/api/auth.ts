import request from './request'
import type { TokenResponse } from '@/types/api'

// 管理端固定以 ADMIN 主体登录
export function loginAdmin(loginName: string, password: string) {
  return request.post<never, TokenResponse>('/auth/login', {
    principalType: 'ADMIN',
    loginName,
    password,
  })
}
