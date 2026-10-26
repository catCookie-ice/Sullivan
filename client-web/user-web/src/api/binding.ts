import request from './request'
import type { BindingResponse } from '@/types/api'

// 我的绑定与请求列表（作为监护人或受护人参与的全部关系）
export function listMyBindings() {
  return request.get<never, BindingResponse[]>('/bindings')
}

// 监护人发起绑定（targetRole=PROTECTEE，目标为受护人）。
// targetId 是**受护人主键 uid**：界面输入登录名后由调用方先查成 uid。
export function requestBindingByGuardian(protecteeUid: string) {
  return request.post<never, BindingResponse>('/bindings', {
    targetId: protecteeUid,
    targetRole: 'PROTECTEE',
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
