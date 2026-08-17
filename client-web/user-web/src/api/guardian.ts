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

// 监护人发起绑定（targetRole=PROTECTEE）
export function requestBinding(protecteeId: string) {
  return request.post<never, BindingResponse>('/bindings', { targetId: protecteeId, targetRole: 'PROTECTEE' })
}

// 受护人发起绑定（targetRole=GUARDIAN，受护人端使用）
export function requestBindingByProtectee(guardianId: string) {
  return request.post<never, BindingResponse>('/bindings', { targetId: guardianId, targetRole: 'GUARDIAN' })
}

// 接收方确认绑定
export function acceptBinding(bindingId: string) {
  return request.post<never, BindingResponse>(`/bindings/${bindingId}/accept`)
}

// 接收方拒绝绑定
export function rejectBinding(bindingId: string) {
  return request.post<never, BindingResponse>(`/bindings/${bindingId}/reject`)
}

// 发起方取消绑定请求
export function cancelBinding(bindingId: string) {
  return request.post<never, BindingResponse>(`/bindings/${bindingId}/cancel`)
}

// 任一方解除生效绑定（永久绑定会返回 403）
export function terminateBinding(bindingId: string) {
  return request.post<never, BindingResponse>(`/bindings/${bindingId}/terminate`)
}

// 我的绑定与请求列表
export function listMyBindings() {
  return request.get<never, BindingResponse[]>('/bindings')
}
