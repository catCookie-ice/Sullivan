import request from './request'
import type { TokenResponse, RegistrationResponse } from '@/types/api'

// 管理端固定以 ADMIN 主体登录
export function loginAdmin(loginName: string, password: string) {
  return request.post<never, TokenResponse>('/auth/login', {
    principalType: 'ADMIN',
    loginName,
    password,
  })
}

// 使用一次性注册码注册管理员（公开接口）
export function registerAdmin(payload: {
  loginName: string
  password: string
  displayName: string
  registrationCode: string
}) {
  return request.post<never, RegistrationResponse>('/auth/register/admin', payload)
}
