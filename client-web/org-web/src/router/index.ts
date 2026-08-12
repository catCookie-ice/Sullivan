import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: () => import('@/views/auth/ForgotPasswordView.vue'),
    },
    {
      path: '/',
      name: 'OrgLayout',
      component: () => import('@/layouts/OrgLayout.vue'),
      redirect: '/profile',
      children: [
        { path: 'profile', name: 'OrgProfile', component: () => import('@/views/org/OrgProfile.vue') },
        { path: 'services', name: 'OrgServices', component: () => import('@/views/org/ServiceManage.vue') },
        { path: 'applications', name: 'OrgApplications', component: () => import('@/views/org/ApplicationManage.vue') },
        { path: 'activities', name: 'OrgActivities', component: () => import('@/views/org/ActivityManage.vue') },
        { path: 'enrollments', name: 'OrgEnrollments', component: () => import('@/views/org/EnrollmentManage.vue') },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/error/NotFound.vue'),
    },
  ],
})

// 免登录即可访问的公开页面
const PUBLIC_NAMES = ['Login', 'Register', 'ForgotPassword']

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!PUBLIC_NAMES.includes(to.name as string) && !auth.accessToken) {
    return { name: 'Login' }
  }
  if (to.name === 'Login' && auth.accessToken) {
    return { path: '/' }
  }
  return true
})

export default router
