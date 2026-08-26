import request from './request'
import type { RealtimeSessionResponse } from '@/types/api'

// 发起实时会话（与已绑定受护人/监护人）
export function createRealtimeSession(targetUid: string) {
  return request.post<never, RealtimeSessionResponse>('/realtime/sessions', {
    targetUid,
    sessionType: 'BASIC',
  })
}

// 我的会话列表
export function listMyRealtimeSessions() {
  return request.get<never, RealtimeSessionResponse[]>('/realtime/sessions')
}

// 接收方接受会话
export function acceptRealtimeSession(sessionId: string) {
  return request.post<never, RealtimeSessionResponse>(`/realtime/sessions/${sessionId}/accept`)
}

// 接收方拒绝会话
export function rejectRealtimeSession(sessionId: string) {
  return request.post<never, RealtimeSessionResponse>(`/realtime/sessions/${sessionId}/reject`)
}

// 任一方结束会话
export function endRealtimeSession(sessionId: string) {
  return request.post<never, RealtimeSessionResponse>(`/realtime/sessions/${sessionId}/end`)
}
