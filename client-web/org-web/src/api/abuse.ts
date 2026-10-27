import request from './request'

/** 机构的处罚记录。 */
export interface OrgPenalty {
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

/** 机构的处罚。 */
export function listOrgPenalties() {
  return request.get<never, OrgPenalty[]>('/org/penalties/mine')
}

/** 申诉处罚。 */
export function appealOrgPenalty(penaltyId: string, reason: string) {
  return request.post<never, unknown>(`/org/penalties/${penaltyId}/appeal`, { reason })
}
