import request from './request'
import type {
  ActivityCreateRequest,
  ActivityResponse,
  OrganizationLookupResponse,
  PageResult,
} from '@/types/api'

// 按唯一标识查找公益机构
export function getOrganization(organizationId: string) {
  return request.get<never, OrganizationLookupResponse>(`/organizations/${organizationId}`)
}

// 机构创建志愿活动
export function createActivity(payload: ActivityCreateRequest) {
  return request.post<never, ActivityResponse>('/activities', payload)
}

// 我的活动列表
export function listMyActivities(params: { status?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<ActivityResponse>>('/activities/mine', { params })
}
