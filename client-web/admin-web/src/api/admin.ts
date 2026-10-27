import request from './request'
import type {
  PageResult,
  AdminUserResponse,
  AdminOrgResponse,
  UserDeleteResult,
  AdminCodeCreateRequest,
  AdminCodeResponse,
  VolunteerApplicationAdminResponse,
  AdminAccountResponse,
  RoleGrantRequest,
  RoleResponse,
  PermissionResponse,
} from '@/types/api'

// 用户列表（USER_MANAGE）
export function listUsers(params: { role?: string; keyword?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<AdminUserResponse>>('/admin/users', { params })
}

// 删除用户（USER_MANAGE）
export function deleteUser(userId: string) {
  return request.delete<never, UserDeleteResult>(`/admin/users/${userId}`)
}

// 机构列表（ORG_MANAGE）
export function listOrganizations(params: { status?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<AdminOrgResponse>>('/admin/organizations', { params })
}

// 删除机构（ORG_MANAGE）
export function deleteOrganization(organizationId: string) {
  return request.delete<never, void>(`/admin/organizations/${organizationId}`)
}

// 审核机构：通过/驳回（ORG_REVIEW）
export function reviewOrganization(
  organizationId: string,
  payload: { approved: boolean; note?: string },
) {
  return request.post<never, AdminOrgResponse>(`/admin/organizations/${organizationId}/review`, payload)
}

// 创建管理员注册码
export function createRegistrationCode(payload: AdminCodeCreateRequest) {
  return request.post<never, AdminCodeResponse>('/admin/registration-codes', payload)
}

// 志愿者兼任申请列表（VOLUNTEER_REVIEW）
export function listVolunteerApplications(params: {
  status?: string
  page?: number
  size?: number
}) {
  return request.get<never, PageResult<VolunteerApplicationAdminResponse>>(
    '/admin/volunteer-applications',
    { params },
  )
}

// 审核通过（事务内追加 VOLUNTEER 角色）
export function approveVolunteerApplication(applicationId: string) {
  return request.post<never, VolunteerApplicationAdminResponse>(
    `/admin/volunteer-applications/${applicationId}/approve`,
  )
}

// 审核拒绝（拒绝原因必填）
export function rejectVolunteerApplication(applicationId: string, reason: string) {
  return request.post<never, VolunteerApplicationAdminResponse>(
    `/admin/volunteer-applications/${applicationId}/reject`,
    { reason },
  )
}

// ============ 管理员治理（ADMIN_MANAGE 读 / ADMIN_GOVERN 写） ============

// 管理员列表（ADMIN_MANAGE）
export function listAdmins(params: { keyword?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<AdminAccountResponse>>('/admin/admins', { params })
}

// 管理员详情（ADMIN_MANAGE）
export function getAdmin(adminId: string) {
  return request.get<never, AdminAccountResponse>(`/admin/admins/${adminId}`)
}

// 启用/停用管理员（ADMIN_GOVERN）；停用立即生效
export function setAdminStatus(adminId: string, status: 'ENABLED' | 'DISABLED') {
  return request.post<never, AdminAccountResponse>(`/admin/admins/${adminId}/status`, { status })
}

// 授予角色（ADMIN_GOVERN）
export function grantAdminRole(adminId: string, payload: RoleGrantRequest) {
  return request.post<never, AdminAccountResponse>(`/admin/admins/${adminId}/roles`, payload)
}

// 撤销角色（ADMIN_GOVERN）
export function revokeAdminRole(adminId: string, roleCode: string) {
  return request.delete<never, AdminAccountResponse>(`/admin/admins/${adminId}/roles/${roleCode}`)
}

// 角色列表（ADMIN_MANAGE；注册码页也用它取可选角色）
export function listRoles() {
  return request.get<never, RoleResponse[]>('/admin/roles')
}

// 权限代码字典（ADMIN_MANAGE）
export function listPermissions() {
  return request.get<never, PermissionResponse[]>('/admin/permissions')
}

// 新建角色（ADMIN_GOVERN）
export function createRole(payload: { roleCode: string; roleName: string; permissionCodes: string[] }) {
  return request.post<never, RoleResponse>('/admin/roles', payload)
}

// 调整角色权限集（全量覆盖，ADMIN_GOVERN）
export function updateRolePermissions(roleCode: string, permissionCodes: string[]) {
  return request.put<never, RoleResponse>(`/admin/roles/${roleCode}/permissions`, { permissionCodes })
}

// 停用角色（ADMIN_GOVERN）
export function disableRole(roleCode: string) {
  return request.delete<never, void>(`/admin/roles/${roleCode}`)
}
