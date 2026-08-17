// 与 server-java 后端实际返回对齐的类型定义

// 登录 / 令牌
export interface TokenResponse {
  accessToken: string
  principalId: string
  principalType: string
  roles: string[]
  permissions: string[]
  // 未设置找回手机号/邮箱时登录返回 true，前端据此弹出设置提醒
  recoveryReminder?: boolean
}

// 注册 / 操作通用返回
export interface RegistrationResponse {
  id: string
  principalType: string
  status: string
  roles: string[]
}

// 机构（GET /organizations/{id}）
export interface OrganizationLookupResponse {
  id: string
  status: string
  loginName: string
  legalName: string
  publicName: string
}

// 分页
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

// 找回联系方式与找回密码
export interface RecoveryMethod {
  type: string // PHONE / EMAIL
  maskedPrefix: string
}

export interface RecoveryStartResponse {
  methods: RecoveryMethod[]
}

export interface RecoveryVerifyResponse {
  verified: boolean
}

export interface RecoveryStatusResponse {
  hasRecovery: boolean
  remindRequired: boolean
  methods: RecoveryMethod[]
}

// 公益活动创建请求（报名窗口 + 高德经纬度）
export interface ActivityCreateRequest {
  title: string
  description: string
  locationText: string
  locationLng?: number
  locationLat?: number
  startsAt: string
  endsAt: string
  registrationStartsAt: string
  registrationEndsAt: string
  maxVolunteers: number
}

// 公益活动
export interface ActivityResponse {
  id: string
  status: string
  organizationId: string
  organizationPublicName?: string
  title: string
  description: string
  locationText: string
  locationLng?: number
  locationLat?: number
  startsAt: string
  endsAt: string
  registrationStartsAt: string
  registrationEndsAt: string
  maxVolunteers: number
  approvedCount: number
  createdAt: string
}
