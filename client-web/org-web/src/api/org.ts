import request from './request'
import type { OrganizationLookupResponse } from '@/types/api'

// 按唯一标识查找公益机构
export function getOrganization(organizationId: string) {
  return request.get<never, OrganizationLookupResponse>(`/organizations/${organizationId}`)
}
