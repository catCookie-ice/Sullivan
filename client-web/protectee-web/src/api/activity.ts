import request from './request'
import type { ActivityResponse, PageResult } from '@/types/api'

// 公开活动列表（默认只返回 RECRUITING；受护人只读浏览）
export function listPublicActivities(params: {
  status?: string
  page?: number
  size?: number
}) {
  return request.get<never, PageResult<ActivityResponse>>('/activities', { params })
}
