import request from './request'
import type { BindingResponse } from '@/types/api'

// 我的绑定与请求列表（作为监护人或受护人参与的全部关系）
export function listMyBindings() {
  return request.get<never, BindingResponse[]>('/bindings')
}

// 受护人发起绑定（targetRole=GUARDIAN，目标为监护人）
export function requestBindingByProtectee(guardianId: string) {
  return request.post<never, BindingResponse>('/bindings', {
    targetId: guardianId,
    targetRole: 'GUARDIAN',
  })
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
