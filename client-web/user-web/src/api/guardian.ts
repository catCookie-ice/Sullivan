import request from './request'
import type { RegistrationResponse } from '@/types/api'

// 监护人创建受护人（建立永久初始绑定）
export function createProtectee(payload: {
  loginName: string
  password: string
  displayName: string
  emergencyNote?: string
}) {
  return request.post<never, RegistrationResponse>('/guardians/protectees', payload)
}

// 绑定的发起/确认/拒绝/取消/解除已全部移到 ./binding.ts：
// 监护人端只保留「以监护人身份绑定受护人」一个方向，避免同一端点出现两种签名。
