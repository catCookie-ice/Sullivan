import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 页面与权限一一对应（与后端各 Admin*Controller 的守卫码同源）：
 * 没有权限就不应看到、也不应能直达对应页面——菜单显隐 + 路由守卫双层拦截。
 * `permission` = 必须持有；`permissionAny` = 满足其一（聚合页）。
 * 权限申请页不要求任何权限：没有权限的管理员落地后仍有一条正式申请通道。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/auth/LoginView.vue'),
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/auth/RegisterView.vue'),
    },
    {
      path: '/',
      name: 'AdminLayout',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/users',
      children: [
        { path: 'users', name: 'UserManage', component: () => import('@/views/admin/UserManage.vue'), meta: { permission: 'USER_MANAGE' } },
        { path: 'organizations', name: 'OrgManage', component: () => import('@/views/admin/OrgManage.vue'), meta: { permission: 'ORG_MANAGE' } },
        { path: 'codes', name: 'RegistrationCodes', component: () => import('@/views/admin/RegistrationCode.vue'), meta: { permission: 'ADMIN_REGISTRATION_CODE_CREATE' } },
        { path: 'stats', name: 'Stats', component: () => import('@/views/admin/StatsDashboard.vue'), meta: { permissionAny: ['USER_MANAGE', 'ORG_MANAGE', 'VOLUNTEER_REVIEW'] } },
        { path: 'volunteer-review', name: 'VolunteerReview', component: () => import('@/views/admin/VolunteerReview.vue'), meta: { permission: 'VOLUNTEER_REVIEW' } },
        { path: 'abuse', name: 'AbuseGovern', component: () => import('@/views/admin/AbuseGovern.vue'), meta: { permission: 'ABUSE_REVIEW' } },
        { path: 'admins', name: 'AdminManage', component: () => import('@/views/admin/AdminManage.vue'), meta: { permission: 'ADMIN_MANAGE' } },
        { path: 'roles', name: 'RoleManage', component: () => import('@/views/admin/RoleManage.vue'), meta: { permission: 'ADMIN_MANAGE' } },
        { path: 'permission-requests', name: 'PermissionRequests', component: () => import('@/views/admin/PermissionRequests.vue') },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/error/NotFound.vue'),
    },
  ],
})

/** 缺权限时的落点：权限申请页（任意管理员可达）。 */
const FALLBACK_PATH = '/permission-requests'

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.name !== 'Login' && to.name !== 'Register' && !auth.accessToken) {
    return { name: 'Login' }
  }
  if (to.name === 'Login' && auth.accessToken) {
    return { path: '/' }
  }
  const required = to.meta.permission as string | undefined
  if (required && !auth.hasPermission(required)) {
    return { path: FALLBACK_PATH }
  }
  const anyOf = to.meta.permissionAny as string[] | undefined
  if (anyOf && !anyOf.some((code) => auth.hasPermission(code))) {
    return { path: FALLBACK_PATH }
  }
  return true
})

export default router
