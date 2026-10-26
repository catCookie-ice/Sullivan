import request from './request'
import type { AlertResponse, AlertSettingResponse } from '@/types/api'

/**
 * 报警（受护人本人 + 作为监护人收到的那一份）。
 *
 * 服务端的 `GET /alerts` 一个接口返回两类：**我发起的**（我是受护人）和
 * **投递给我的**（我是监护人）。因此监护人端不需要另一个端点 —— 同一次调用
 * 里就已经包含「谁在向我求助」。
 */
export function listMyAlerts() {
  return request.get<never, AlertResponse[]>('/alerts')
}

export function getAlert(alertId: string) {
  return request.get<never, AlertResponse>(`/alerts/${alertId}`)
}

/** 结束报警：服务端只允许受护人本人，监护人调用会得到 403。 */
export function resolveAlert(alertId: string) {
  return request.post<never, AlertResponse>(`/alerts/${alertId}/resolve`)
}

/** 报警偏好（自动呼叫开关与延迟秒数）。 */
export function getAlertSetting() {
  return request.get<never, AlertSettingResponse>('/me/alert-setting')
}

export function updateAlertSetting(autoSessionEnabled: boolean) {
  return request.put<never, AlertSettingResponse>('/me/alert-setting', { autoSessionEnabled })
}
