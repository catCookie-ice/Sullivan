import request from './request'
import type {
  ActivityCreateRequest,
  ActivityGroupResponse,
  ActivityResponse,
  EnrollmentPage,
  EnrollmentResponse,
  GroupMessagePage,
  GroupMessageResponse,
  GroupReportResponse,
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

// ---------- 报名审核 ----------

// 某活动的报名列表（含各状态统计；total 为总报名人数）
export function listActivityEnrollments(
  activityId: string,
  params: { status?: string; page?: number; size?: number },
) {
  return request.get<never, EnrollmentPage>(`/org/activities/${activityId}/enrollments`, { params })
}

// 审核报名：approve=true 通过（占名额并入群）
export function decideEnrollment(
  enrollmentId: string,
  payload: { approve: boolean; reason?: string },
) {
  return request.post<never, EnrollmentResponse>(`/org/activity-enrollments/${enrollmentId}/decision`, payload)
}

// ---------- 活动群聊（机构=群管理员） ----------

// 本机构的群列表
export function listOrgActivityGroups() {
  return request.get<never, ActivityGroupResponse[]>('/org/activity-groups')
}

// 群消息历史（游标：before=上一页最早一条的 createdAt）
export function listOrgGroupMessages(groupId: string, params: { before?: string; limit?: number }) {
  return request.get<never, GroupMessagePage>(`/org/activity-groups/${groupId}/messages`, { params })
}

// 机构发言
export function sendOrgGroupMessage(groupId: string, content: string) {
  return request.post<never, GroupMessageResponse>(`/org/activity-groups/${groupId}/messages`, { content })
}

// 撤回消息
export function withdrawOrgGroupMessage(messageId: string) {
  return request.post<never, void>(`/org/group-messages/${messageId}/withdraw`)
}

// 本群的举报列表
export function listOrgGroupReports(groupId: string, params: { status?: string }) {
  return request.get<never, GroupReportResponse[]>(`/org/activity-groups/${groupId}/reports`, { params })
}

// 处理举报：withdrawMessage=true 受理并撤回消息
export function decideGroupReport(
  reportId: string,
  payload: { withdrawMessage: boolean; note?: string },
) {
  return request.post<never, GroupReportResponse>(`/org/group-message-reports/${reportId}/decision`, payload)
}
