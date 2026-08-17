import request from './request'
import type {
  RecoveryStartResponse,
  RecoveryStatusResponse,
  RecoveryVerifyResponse,
  RegistrationResponse,
  TokenResponse,
  VolunteerApplicationResponse,
} from '@/types/api'

// 自然人端固定以 USER 主体登录
export function loginUser(loginName: string, password: string) {
  return request.post<never, TokenResponse>('/auth/login', {
    principalType: 'USER',
    loginName,
    password,
  })
}

// 自然人注册（监护人或志愿者）
export function registerUser(payload: {
  loginName: string
  password: string
  displayName: string
  role: 'GUARDIAN' | 'VOLUNTEER'
  recoveryPhone?: string
  recoveryEmail?: string
}) {
  return request.post<never, RegistrationResponse>('/auth/register/user', payload)
}

// 监护人提交志愿者兼任申请（需管理员审核）
export function submitVolunteerApplication(reason?: string) {
  return request.post<never, VolunteerApplicationResponse>('/users/me/roles/volunteer', { reason })
}

// 我的志愿者兼任申请列表
export function getMyVolunteerApplications() {
  return request.get<never, VolunteerApplicationResponse[]>('/users/me/roles/volunteer')
}

// 撤销我的 PENDING 申请
export function cancelVolunteerApplication(applicationId: string) {
  return request.post<never, VolunteerApplicationResponse>(
    `/users/me/roles/volunteer/cancel/${applicationId}`,
  )
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
