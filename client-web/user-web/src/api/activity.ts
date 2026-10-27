import request from './request'
import type {
  ActivityGroupResponse,
  ActivityResponse,
  EnrollmentResponse,
  GroupMemberResponse,
  GroupMessagePage,
  GroupMessageResponse,
  GroupReportResponse,
  PageResult,
} from '@/types/api'

/**
 * 公益活动与报名（志愿者视角）。
 *
 * `GET /activities` 对任何已登录用户开放；报名/撤回/我的报名走
 * `/activities/{id}/enrollments`、`/enrollments/*`。未开「需审核」的活动报名即通过
 * 并自动进入活动群聊；开了审核的先进 PENDING，机构批准后才算报名成功、才占名额。
 */
export function listPublicActivities(params: { status?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<ActivityResponse>>('/activities', { params })
}

// 报名活动：需审核的活动返回 PENDING，否则 APPROVED 并入群
export function enrollActivity(activityId: string) {
  return request.post<never, EnrollmentResponse>(`/activities/${activityId}/enrollments`)
}

// 撤回报名：APPROVED 撤回会退群并释放名额
export function cancelEnrollment(enrollmentId: string) {
  return request.post<never, EnrollmentResponse>(`/enrollments/${enrollmentId}/cancel`)
}

// 我的报名列表
export function listMyEnrollments(params: { page?: number; size?: number }) {
  return request.get<never, PageResult<EnrollmentResponse>>('/enrollments/mine', { params })
}

// ---------- 活动群聊 ----------

// 我的活动群
export function listMyActivityGroups() {
  return request.get<never, ActivityGroupResponse[]>('/activity-groups/mine')
}

// 群消息历史（游标：before=上一页最早一条的 createdAt）
export function listGroupMessages(groupId: string, params: { before?: string; limit?: number }) {
  return request.get<never, GroupMessagePage>(`/activity-groups/${groupId}/messages`, { params })
}

// 发送文字消息（违禁词会被后端拦截）
export function sendGroupMessage(groupId: string, content: string) {
  return request.post<never, GroupMessageResponse>(`/activity-groups/${groupId}/messages`, { content })
}

// 撤回自己发送的消息（发出后 30 分钟内；超窗后端返回 409）
export function withdrawOwnGroupMessage(messageId: string) {
  return request.post<never, void>(`/group-messages/${messageId}/withdraw`)
}

// 群成员
export function listGroupMembers(groupId: string) {
  return request.get<never, GroupMemberResponse[]>(`/activity-groups/${groupId}/members`)
}

// 举报消息
export function reportGroupMessage(messageId: string, reason: string) {
  return request.post<never, GroupReportResponse>(`/group-messages/${messageId}/report`, { reason })
}
