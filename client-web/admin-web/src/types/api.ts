// 与 server-java 后端实际返回对齐的类型定义

export interface TokenResponse {
  accessToken: string
  principalId: string
  principalType: string
  roles: string[]
  permissions: string[]
}

// 注册成功响应（用户/机构/管理员）
export interface RegistrationResponse {
  id: string
  principalType: string
  status: string
  roles: string[]
}

// 分页（MyBatis-Plus 结构）
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

// 管理端用户列表项
export interface AdminUserResponse {
  id: string
  status: string
  loginName: string
  displayName: string
  roleCode: string
  roles: string[]
  alsoVolunteer: boolean
  createdAt: string
}

// 用户删除结果
export interface UserDeleteResult {
  accountDeleted: boolean
  action: 'ACCOUNT_DELETED' | 'VOLUNTEER_ROLE_REVOKED'
  message: string
}

// 管理端机构列表项
export interface AdminOrgResponse {
  id: string
  status: string
  loginName: string
  legalName: string
  publicName: string
  createdAt: string
}

// 管理员（GET /admins/{id}）
export interface AdminLookupResponse {
  id: string
  status: string
  loginName: string
  displayName: string
  roles: string[]
}

// 创建管理员注册码
export interface AdminCodeCreateRequest {
  initialRoleCode: string
  expiresAt: string
}

export interface AdminCodeResponse {
  code: string
  initialRoleCode: string
  expiresAt: string
}

// 管理员账号治理（GET /admin/admins 列表与详情）
export interface AdminAccountResponse {
  id: string
  status: 'ENABLED' | 'DISABLED'
  loginName: string
  displayName: string
  roles: string[]
  permissions: string[]
}

// 授予角色请求（expiresAt 空 = 永久）
export interface RoleGrantRequest {
  roleCode: string
  expiresAt?: string
}

// 管理员角色（权限集的具名打包）
export interface RoleResponse {
  id: string
  roleCode: string
  roleName: string
  system: boolean
  permissions: string[]
  memberCount: number
}

// 权限代码字典项
export interface PermissionResponse {
  code: string
  name: string
}

// 志愿者兼任申请（管理端审核）
export interface VolunteerApplicationAdminResponse {
  id: string
  status: string // PENDING / APPROVED / REJECTED / CANCELLED
  applicantUid: string
  loginName: string
  displayName: string
  applicantCreatedAt: string
  reason?: string
  decidedByAdminId?: string
  decidedAt?: string
  decisionNote?: string
}
