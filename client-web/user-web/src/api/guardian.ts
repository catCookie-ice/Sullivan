import request from './request'
import type { RegistrationResponse, BindingResponse } from '@/types/api'

// 监护人创建受护人（建立永久初始绑定）
export function createProtectee(payload: {
  loginName: string
  password: string
  displayName: string
  emergencyNote?: string
}) {
  return request.post<never, RegistrationResponse>('/guardians/protectees', payload)
}

// 监护人发起绑定请求
export function requestBinding(protecteeId: string) {
  return request.post<never, BindingResponse>('/guardians/bindings', { protecteeId })
}

// 受护人确认绑定
export function acceptBinding(bindingId: string) {
  return request.post<never, BindingResponse>(`/guardians/bindings/${bindingId}/accept`)
}
