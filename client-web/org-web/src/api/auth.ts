import request from './request'
import type { TokenResponse } from '@/types/api'

// 机构端固定以 ORGANIZATION 主体登录
export function loginOrganization(loginName: string, password: string) {
  return request.post<never, TokenResponse>('/auth/login', {
    principalType: 'ORGANIZATION',
    loginName,
    password,
  })
}
