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
  // 双方 uid 只用于判断「我是哪一方」，**不作为界面显示**（见 00 §7 第 11 条）
  guardianId: string
  protecteeId: string
  permanent: boolean
  primary: boolean
  // 发起方账号 ID：等于 guardianId 表示监护人发起，等于 protecteeId 表示受护人发起
  requestedBy: string
  // 界面显示用：登录名必有，显示名可能为空（为空时回落到登录名）
  guardianLoginName?: string | null
  protecteeLoginName?: string | null
  guardianName?: string | null
  protecteeName?: string | null
}

// 会话类型：EMERGENCY 只能由服务端报警派发，客户端不可创建
export type RealtimeSessionType = 'BASIC' | 'VOICE' | 'VIDEO' | 'CAMERA' | 'EMERGENCY'

// 客户端自己可以发起的会话类型（EMERGENCY 传入会被服务端 403 拒绝）
export type ClientRealtimeSessionType = Exclude<RealtimeSessionType, 'EMERGENCY'>

// 实时会话
export interface RealtimeSessionResponse {
  id: string
  status: string // REQUESTED / ACTIVE / REJECTED / ENDED
  // 双方 uid 只用于判断「我是哪一方」，**不作为界面显示**
  initiatorUid: string
  targetUid: string
  sessionType: RealtimeSessionType
  // 服务端 LocalDateTime 一律是 `yyyy-MM-dd HH:mm:ss`（空格分隔），直接展示，不要交给 Date 解析
  createdAt: string
  // 界面显示用：登录名必有，显示名可能为空（为空时回落到登录名）
  initiatorLoginName?: string | null
  targetLoginName?: string | null
  initiatorName?: string | null
  targetName?: string | null
}

// WebRTC ICE 配置（GET /realtime/ice-servers）
export interface IceServerResponse {
  urls: string[]
  // 配置了 TURN 时才有；是短时有效凭据，不得缓存或写死
  username: string | null
  credential: string | null
  ttlSeconds: number
}

// 语音留言（响应里没有 uid，也没有可下载地址）
export interface VoiceMessageResponse {
  id: string
  status: string // SENT / LISTENED / DELETED
  senderLoginName: string | null
  senderName: string | null
  receiverLoginName: string | null
  receiverName: string | null
  myRole: 'SENDER' | 'RECEIVER'
  durationMs: number | null
  sizeBytes: number | null
  contentType: string | null
  createdAt: string
  listenedAt: string | null
}

// 自然人（GET /users/{id}，界面改用 GET /users/by-login-name/{loginName}）
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
