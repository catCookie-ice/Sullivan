import request from './request'
import type { VoiceMessageResponse } from '@/types/api'

// 语音留言：只走这五个端点。
// 音频**没有**可直接播放的地址——必须带 Bearer 令牌经 /audio 取回，见 fetchVoiceMessageAudio。

export interface VoiceMessageUpload {
  receiverUid: string
  durationMs: number
  sizeBytes: number
  file: Blob
  sessionId?: string
  contentType?: string
}

// 投递留言（multipart：音频是二进制，base64 会让体积涨三分之一）
export function createVoiceMessage(payload: VoiceMessageUpload) {
  const form = new FormData()
  form.append('receiverUid', payload.receiverUid)
  form.append('durationMs', String(payload.durationMs))
  form.append('sizeBytes', String(payload.sizeBytes))
  // 服务端按「file 的 Content-Type 优先、contentType 参数兜底」判断格式，
  // 两处都给上，避免某些浏览器把 blob 传成 application/octet-stream。
  if (payload.contentType) form.append('contentType', payload.contentType)
  if (payload.sessionId) form.append('sessionId', payload.sessionId)
  form.append('file', payload.file, payload.file instanceof File ? payload.file.name : 'message.webm')
  // 不手动设置 Content-Type：交给浏览器生成 multipart 边界
  return request.post<never, VoiceMessageResponse>('/voice-messages', form, { timeout: 60000 })
}

// 我的留言箱（收到的 + 发出的）
export function listVoiceMessages() {
  return request.get<never, VoiceMessageResponse[]>('/voice-messages')
}

// 标记已听（只有接收方本人可以标记，重复调用幂等）
export function markVoiceMessageListened(messageId: string) {
  return request.post<never, VoiceMessageResponse>(`/voice-messages/${messageId}/listened`)
}

// 删除留言（投递双方任一方可删，音频本体一并删除）
export function deleteVoiceMessage(messageId: string) {
  return request.delete<never, void>(`/voice-messages/${messageId}`)
}

// 取回音频：必须自己带 Authorization，所以不能用 <audio src>。
// 返回原始 Blob，调用方负责 createObjectURL 与后续 revokeObjectURL。
// 用裸 fetch 而不是 axios 实例：音频是二进制流，走响应拦截器（response.data）会白白多绕一层。
export async function fetchVoiceMessageAudio(messageId: string): Promise<Blob> {
  const token = localStorage.getItem('access_token')
  const response = await fetch(`/api/v1/voice-messages/${messageId}/audio`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    // 服务端带 Cache-Control: no-store，这里再显式声明一次，避免浏览器复用旧录音
    cache: 'no-store',
  })
  if (!response.ok) {
    // 401 时交由调用方提示重新登录，不在这里跳转：播放失败不该突然把用户踢回登录页
    throw new Error(response.status === 401 ? '登录已过期，请重新登录后再播放' : '音频取回失败')
  }
  return response.blob()
}
