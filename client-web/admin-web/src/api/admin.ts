import request from './request'
import type {
  PageResult,
  AdminUserResponse,
  AdminOrgResponse,
  UserDeleteResult,
  AdminCodeCreateRequest,
  AdminCodeResponse,
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

// 创建管理员注册码
export function createRegistrationCode(payload: AdminCodeCreateRequest) {
  return request.post<never, AdminCodeResponse>('/admin/registration-codes', payload)
}
