import request from './request'
import type {
  RecoveryStartResponse,
  RecoveryStatusResponse,
  RecoveryVerifyResponse,
  TokenResponse,
} from '@/types/api'

// 受护人端固定以 USER 主体登录
export function loginUser(loginName: string, password: string) {
  return request.post<never, TokenResponse>('/auth/login', {
    principalType: 'USER',
    loginName,
    password,
  })
}

// 找回密码：开始（返回可用方式与不加密前缀）
export function passwordRecoveryStart(principalType: string, loginName: string) {
  return request.post<never, RecoveryStartResponse>('/auth/password-recovery/start', {
    principalType,
    loginName,
  })
}

// 找回密码：补全联系方式并验证
export function passwordRecoveryVerify(
  principalType: string,
  loginName: string,
  recoveryType: string,
  fullContact: string,
) {
  return request.post<never, RecoveryVerifyResponse>('/auth/password-recovery/verify', {
    principalType,
    loginName,
    recoveryType,
    fullContact,
  })
}

// 我的找回联系方式状态
export function getMyRecovery() {
  return request.get<never, RecoveryStatusResponse>('/auth/me/recovery')
}

// 设置/更新找回联系方式（手机号与邮箱至少填一个）
export function setRecoveryContact(payload: { recoveryPhone?: string; recoveryEmail?: string }) {
  return request.put<never, RecoveryStatusResponse>('/auth/me/recovery', payload)
}

// “下次再说”：一周后再提醒
export function recoveryRemindLater() {
  return request.post<never, { ok: boolean }>('/auth/me/recovery/remind-later')
}

// “不再提醒”
export function recoveryDismiss() {
  return request.post<never, { ok: boolean }>('/auth/me/recovery/dismiss')
}
