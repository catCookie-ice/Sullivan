import request from './request'
import type {
  PageResult,
  AdminUserResponse,
  AdminOrgResponse,
  UserDeleteResult,
  AdminCodeCreateRequest,
  AdminCodeResponse,
  VolunteerApplicationAdminResponse,
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
