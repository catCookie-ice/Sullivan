// 与 server-java 后端实际返回对齐的类型定义（受护人端精简版）

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

// 绑定关系（发起绑定 / 接受绑定 / 拒绝 / 取消 / 解除）
export interface BindingResponse {
  id: string
  status: string
  guardianId: string
  protecteeId: string
  permanent: boolean
  primary: boolean
  // 发起方账号 ID：等于 guardianId 表示监护人发起，等于 protecteeId 表示受护人发起
  requestedBy: string
}

// 实时会话
export interface RealtimeSessionResponse {
  id: string
  status: string // REQUESTED / ACTIVE / REJECTED / ENDED
  initiatorUid: string
  targetUid: string
  sessionType: string
  createdAt: string
}

// 自然人（GET /users/{id}）
export interface UserLookupResponse {
  id: string
  status: string
  loginName: string
  displayName: string
  roleCode: string
  roles: string[]
  alsoVolunteer: boolean
}

// 机构（GET /organizations/{id}）
export interface OrganizationLookupResponse {
  id: string
  status: string
  loginName: string
  legalName: string
  publicName: string
}

// 公益志愿活动（GET /activities）
export interface ActivityResponse {
  id: string
  status: string // RECRUITING / IN_PROGRESS / ENDED / CANCELLED
  organizationId: string
  organizationPublicName: string
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

// MyBatis-Plus 分页返回
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages?: number
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
