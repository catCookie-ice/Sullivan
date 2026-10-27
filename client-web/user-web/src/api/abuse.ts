import request from './request'

/** 我发起的举报。 */
export interface MyReport {
  id: string
  category: string
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED'
  reason: string
  decisionNote: string | null
  handledAt: string | null
  createdAt: string
}

/** 针对我的举报（仅被驳回的，供反诉；举报人匿名，无处置意见）。 */
export interface ReceivedReport {
  id: string
  category: string
  reason: string
  handledAt: string | null
  counterReportable: boolean
  createdAt: string
}

/** 我的处罚记录。 */
export interface MyPenalty {
  id: string
  category: string
  action: string
  presetName: string | null
  durationDays: number | null
  reason: string
  status: 'ACTIVE' | 'LIFTED' | 'EXPIRED'
  appliedAt: string
  expiresAt: string | null
  appealable: boolean
}

/** 发起举报。 */
export function createAbuseReport(payload: { category: string; targetId: string; reason: string }) {
  return request.post<never, MyReport>('/abuse-reports', payload)
}

/** 反向举报（匿名反诉）。 */
export function createCounterReport(sourceReportId: string, reason: string) {
  return request.post<never, MyReport>(`/abuse-reports/${sourceReportId}/counter`, { reason })
}

/** 我发起的举报。 */
export function listMyReports() {
  return request.get<never, MyReport[]>('/abuse-reports/mine')
}

/** 针对我的举报（举报人匿名）。 */
export function listReceivedReports() {
  return request.get<never, ReceivedReport[]>('/abuse-reports/received')
}

/** 我的处罚。 */
export function listMyPenalties() {
  return request.get<never, MyPenalty[]>('/penalties/mine')
}

/** 申诉处罚。 */
export function appealPenalty(penaltyId: string, reason: string) {
  return request.post<never, unknown>(`/penalties/${penaltyId}/appeal`, { reason })
}
