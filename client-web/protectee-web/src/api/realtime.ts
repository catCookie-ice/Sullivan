import request from './request'
import type {
  ClientRealtimeSessionType,
  IceServerResponse,
  RealtimeSessionResponse,
} from '@/types/api'

// 发起实时会话（与已绑定受护人/监护人）。
// targetUid 是**用户主键**而不是登录名：界面上让用户输入登录名，到这里之前必须已查成 uid。
// sessionType 只允许 BASIC / VOICE / VIDEO / CAMERA——传 EMERGENCY 服务端返回 403。
export function createRealtimeSession(targetUid: string, sessionType: ClientRealtimeSessionType = 'BASIC') {
  return request.post<never, RealtimeSessionResponse>('/realtime/sessions', {
    targetUid,
    sessionType,
  })
}

// 我的会话列表
export function listMyRealtimeSessions() {
  return request.get<never, RealtimeSessionResponse[]>('/realtime/sessions')
}

// 接收方接受会话：服务端一次调用即 REQUESTED → ACTIVE，发起方无需再操作
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

// ICE 配置：STUN/TURN 由服务端下发，前端不得硬编码。
// 返回的 username/credential 是短时凭据，只在本轮通话里用掉，不要缓存到 localStorage。
export function getIceServers() {
  return request.get<never, IceServerResponse>('/realtime/ice-servers')
}
