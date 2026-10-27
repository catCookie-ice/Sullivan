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

// 公益活动创建请求（报名窗口 + 高德经纬度 + 是否需审核）
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
  reviewRequired?: boolean
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
  reviewRequired: boolean
  maxVolunteers: number
  approvedCount: number
  createdAt: string
}

// 活动报名（机构视角）
export interface EnrollmentSummary {
  total: number
  pending: number
  approved: number
  rejected: number
  cancelled: number
}

export interface EnrollmentResponse {
  id: string
  activityId: string
  activityTitle?: string
  organizationPublicName?: string
  volunteerUid: string
  volunteerLoginName?: string
  volunteerDisplayName?: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  decisionReason?: string
  decidedAt?: string
  createdAt: string
}

// 机构端报名分页（附统计）
export interface EnrollmentPage {
  records: EnrollmentResponse[]
  total: number
  page: number
  size: number
  summary: EnrollmentSummary
}

// 活动群聊
export interface ActivityGroupResponse {
  id: string
  activityId: string
  activityTitle?: string
  organizationId: string
  organizationPublicName?: string
  status: 'OPEN' | 'CLOSED' | 'SUSPENDED'
  startsAt?: string
  endsAt?: string
  memberCount: number
  createdAt: string
}

export interface GroupMessageResponse {
  id: string
  groupId: string
  senderUid: string
  senderType: 'VOLUNTEER' | 'ORGANIZATION'
  senderName?: string
  /** 已撤回的消息为空串（墓碑，不下发原文）。 */
  content: string
  createdAt: string
  /** 是否已撤回；true 时前端显示「xx 撤回了一条消息」。 */
  withdrawn: boolean
}

export interface GroupMessagePage {
  records: GroupMessageResponse[]
  hasMore: boolean
}

export interface GroupMemberResponse {
  id: string
  volunteerUid: string
  loginName?: string
  displayName?: string
  joinedAt: string
}

export interface GroupReportResponse {
  id: string
  messageId: string
  groupId: string
  reporterUid: string
  reporterName?: string
  messageContent?: string
  reason: string
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED'
  decisionNote?: string
  handledAt?: string
  createdAt: string
}
