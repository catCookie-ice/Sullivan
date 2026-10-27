import request from './request'
import type { NotificationApi, NotificationPage } from '@shared/notifications/types'

/**
 * 站内通知 API（user-web 实现）。
 *
 * 志愿者登录看到的是「报名审核通过 / 报名未通过 / 机构撤回了一条消息」等
 * 志愿者侧通知；接口与机构端共用一组路径，后端按 principalType 区分收件箱。
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
