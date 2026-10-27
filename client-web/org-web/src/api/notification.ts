import request from './request'
import type { NotificationApi, NotificationPage } from '@shared/notifications/types'

/**
 * 站内通知 API（org-web 实现）。
 *
 * 后端按令牌 principalType 区分收件箱：机构登录看到的是
 * 「新的报名待审核」等机构侧通知；接口与志愿者端共用一组路径。
 */
export const notificationApi: NotificationApi = {
  async unreadCount() {
    return request.get<never, { count: number }>('/notifications/unread-count')
  },

  async list(before?: string, limit = 20) {
    return request.get<never, NotificationPage>('/notifications', { params: { before, limit } })
  },

  async markRead(id: string) {
    await request.post<never, void>(`/notifications/${id}/read`)
  },

  async markAllRead() {
    await request.post<never, void>('/notifications/read-all')
  },
}
