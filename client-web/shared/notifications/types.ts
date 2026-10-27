/** 站内通知共享类型与 API 口径（org-web / user-web 共用，宿主端注入各自 request 实例）。 */

// 与后端 NotificationResponse 对齐
export interface NotificationItem {
  id: string
  // ENROLLMENT_PENDING / ENROLLMENT_APPROVED / ENROLLMENT_REJECTED / GROUP_MESSAGE_WITHDRAWN
  type: string
  title: string
  content: string
  /** 点击跳转的前端路由（按接收端由后端拼接）。 */
  link: string
  status: 'UNREAD' | 'READ'
  readAt?: string | null
  createdAt: string
}

export interface NotificationPage {
  records: NotificationItem[]
  hasMore: boolean
}

/** 通知铃铛需要的最小 API 面；由各端用自己的 request 实例实现后注入。 */
export interface NotificationApi {
  unreadCount(): Promise<{ count: number }>
  list(before?: string, limit?: number): Promise<NotificationPage>
  markRead(id: string): Promise<void>
  markAllRead(): Promise<void>
}
