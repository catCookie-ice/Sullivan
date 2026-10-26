import request from './request'
import type { UserLookupResponse, OrganizationLookupResponse } from '@/types/api'

// 按登录名查找自然人——界面唯一应当使用的查找方式。
// 登录名可能含空格、斜杠等字符，必须整段编码，否则会被当成路径分隔符而 404。
export function getUserByLoginName(loginName: string) {
  return request.get<never, UserLookupResponse>(
    `/users/by-login-name/${encodeURIComponent(loginName.trim())}`,
  )
}

// 按唯一标识查找公益机构。
// 机构没有「按登录名查找」的端点，且机构 ID 只在本机构自己的资料页出现（不是自然人 uid），
// 所以这里保留按 ID 查询。
export function getOrganization(organizationId: string) {
  return request.get<never, OrganizationLookupResponse>(`/organizations/${organizationId}`)
}
