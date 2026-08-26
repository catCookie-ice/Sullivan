import request from './request'
import type { UserLookupResponse, OrganizationLookupResponse } from '@/types/api'

// 按唯一标识查找自然人
export function getUser(userId: string) {
  return request.get<never, UserLookupResponse>(`/users/${userId}`)
}

// 按唯一标识查找公益机构
export function getOrganization(organizationId: string) {
  return request.get<never, OrganizationLookupResponse>(`/organizations/${organizationId}`)
}
