import request from './request'
import type { TokenResponse, RegistrationResponse } from '@/types/api'

// 自然人端固定以 USER 主体登录
export function loginUser(loginName: string, password: string) {
  return request.post<never, TokenResponse>('/auth/login', {
    principalType: 'USER',
    loginName,
    password,
  })
}

// 监护人兼任志愿者
export function addVolunteerRole() {
  return request.post<never, RegistrationResponse>('/users/me/roles/volunteer')
}
