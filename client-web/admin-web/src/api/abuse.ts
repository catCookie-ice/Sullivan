import request from './request'
import type { PageResult } from '@/types/api'

/** 举报记录（管理员视角）。 */
export interface AdminAbuseReport {
  id: string
  category: 'ACTIVITY' | 'ORGANIZATION' | 'GROUP' | 'USER' | 'REPORT_ABUSE'
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED'
  reporterUid: string
  reporterName: string
  reason: string
  targetActivityId: string | null
  targetOrgId: string | null
  targetGroupId: string | null
  targetUserId: string | null
  targetSummary: string | null
  sourceReportId: string | null
  handledBy: string | null
  handledAt: string | null
  decisionNote: string | null
  penaltyId: string | null
  createdAt: string
}

/** 预设处罚的附带组件（跨对象）。 */
export interface PresetPenaltyPart {
  targetKind: 'ACTIVITY' | 'ORGANIZATION' | 'GROUP'
  action: string
  /** 附带时长；NULL=跟随主处罚时长 */
  durationDays: number | null
}

/** 预设处罚（组合式：主基础惩罚 + 附带组件）。 */
export interface PresetPenalty {
  id: string
  name: string
  category: 'ACTIVITY' | 'ORGANIZATION' | 'GROUP' | 'USER'
  /** 主基础惩罚 */
  action: string
  durationDays: number | null
  /** 附带组件（可复合共存） */
  parts: PresetPenaltyPart[]
  description: string | null
  status: 'PENDING' | 'ACTIVE' | 'REJECTED'
  createdBy: string
  approvedBy: string | null
  handledAt: string | null
  createdAt: string
}

/** 处罚记录。 */
export interface PenaltyRecord {
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

/** 申诉记录。 */
export interface AbuseAppeal {
  id: string
  penaltyId: string
  appellantType: 'USER' | 'ORGANIZATION'
  status: 'PENDING' | 'UPHELD' | 'OVERTURNED'
  reason: string
  decisionNote: string | null
  handledAt: string | null
  createdAt: string
}

/** 目标档案：历史被举报 + 违规记录。 */
export interface TargetHistory {
  reports: AdminAbuseReport[]
  penalties: PenaltyRecord[]
}

// 举报列表（ABUSE_REVIEW）
export function listAbuseReports(params: { status?: string; category?: string; page?: number; size?: number }) {
  return request.get<never, PageResult<AdminAbuseReport>>('/admin/abuse/reports', { params })
}

/** 同一对象的举报合并视图。 */
export interface MergedReportGroup {
  category: AdminAbuseReport['category']
  targetSummary: string | null
  targetActivityId: string | null
  targetOrgId: string | null
  targetGroupId: string | null
  targetUserId: string | null
  reportCount: number
  pendingCount: number
  latestStatus: AdminAbuseReport['status']
  latestCreatedAt: string
  reports: AdminAbuseReport[]
}

// 举报列表（按对象合并：同一对象一条，展开看每条举报）
export function listAbuseReportsGrouped(params: { status?: string; category?: string }) {
  return request.get<never, MergedReportGroup[]>('/admin/abuse/reports/grouped', { params })
}

// 目标档案
export function getTargetHistory(category: string, targetId: string) {
  return request.get<never, TargetHistory>('/admin/abuse/targets/history', {
    params: { category, targetId },
  })
}

// 处置举报：驳回或施加预设处罚
export function decideReport(
  reportId: string,
  payload: { dismiss: boolean; presetId?: string; note?: string },
) {
  return request.post<never, void>(`/admin/abuse/reports/${reportId}/decision`, payload)
}

// 预设处罚列表
export function listPresets(status?: string) {
  return request.get<never, PresetPenalty[]>('/admin/abuse/presets', { params: { status } })
}

// 申请新增预设处罚（组合式）
export function createPreset(payload: {
  name: string
  category: string
  /** 主基础惩罚 */
  action: string
  /** 主处罚时长；不填=永久（纯永久动作不填） */
  durationDays?: number | null
  /** 附带组件（跨对象，可复合共存；时长不填=跟随主处罚时长） */
  parts?: { targetKind: string; action: string; durationDays?: number | null }[]
  description?: string
}) {
  return request.post<never, PresetPenalty>('/admin/abuse/presets', payload)
}

// 审批预设处罚（另一名管理员；不能自批）
export function approvePreset(presetId: string, approve: boolean) {
  return request.post<never, void>(`/admin/abuse/presets/${presetId}/approval`, { approve })
}

// 申诉列表
export function listAppeals(status?: string) {
  return request.get<never, AbuseAppeal[]>('/admin/abuse/appeals', { params: { status } })
}

// 处理申诉：维持 / 撤销
export function decideAppeal(appealId: string, uphold: boolean, note?: string) {
  return request.post<never, void>(`/admin/abuse/appeals/${appealId}/decision`, { uphold, note })
}
