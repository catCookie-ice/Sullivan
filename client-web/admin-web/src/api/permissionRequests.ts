import request from './request'

/** 权限申请记录。 */
export interface PermissionRequest {
  id: string
  permissionCode: string
  permissionName: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  reason: string
  decisionNote: string | null
  requestedBy: string
  handledBy: string | null
  handledAt: string | null
  createdAt: string
}

/** 权限字典项。 */
export interface PermissionOption {
  code: string
  name: string
}

// 发起权限申请（任意管理员，无需任何权限；只填权限与理由——申请给自己的权限）
export function createPermissionRequest(payload: { permissionCode: string; reason: string }) {
  return request.post<never, PermissionRequest>('/admin/permission-requests', payload)
}

// 我的申请
export function listMyPermissionRequests() {
  return request.get<never, PermissionRequest[]>('/admin/permission-requests/mine')
}

// 权限字典（表单下拉）
export function listPermissionOptions() {
  return request.get<never, PermissionOption[]>('/admin/permission-requests/catalog')
}

// 全部申请（ADMIN_GOVERN）
export function listPermissionRequests(status?: string) {
  return request.get<never, PermissionRequest[]>('/admin/permission-requests', { params: { status } })
}

// 审批：批准即给申请人开通；不能自批（ADMIN_GOVERN）
export function decidePermissionRequest(requestId: string, approve: boolean, note?: string) {
  return request.post<never, void>(`/admin/permission-requests/${requestId}/decision`, { approve, note })
}
