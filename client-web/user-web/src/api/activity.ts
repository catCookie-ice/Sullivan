import request from './request'
import type { ActivityResponse, PageResult } from '@/types/api'

/**
 * 公益活动（志愿者视角）。
 *
 * 服务端 `GET /activities` 对任何已登录用户开放，默认只返回 RECRUITING（招募中）。
 * 注意：MINI 范围内**没有「报名」端点** —— 服务端只有 `activity_enrollment` 表和
 * `GET /activities`，没有对外的报名接口。所以志愿者端只能浏览、不能报名，
 * 界面必须如实说明，不能放一个点了没反应的按钮。
 */
export function listPublicActivities(params: { status?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<ActivityResponse>>('/activities', { params })
}
