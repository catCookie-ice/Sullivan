// 与 server-java 后端实际返回对齐的类型定义

export interface TokenResponse {
  accessToken: string
  principalId: string
  principalType: string
  roles: string[]
  permissions: string[]
}

// 注册 / 操作通用返回
export interface RegistrationResponse {
  id: string
  principalType: string
  status: string
  roles: string[]
}

// 绑定关系（创建受护人 / 发起绑定 / 接受绑定）
export interface BindingResponse {
  id: string
  status: string
  guardianId: string
  protecteeId: string
  permanent: boolean
  primary: boolean
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
