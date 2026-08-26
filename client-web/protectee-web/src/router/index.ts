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
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: () => import('@/views/auth/ForgotPasswordView.vue'),
    },
    {
      path: '/',
      component: () => import('@/layouts/HomeRedirect.vue'),
    },
    {
      path: '/',
      name: 'ProtecteeLayout',
      component: () => import('@/layouts/ProtecteeLayout.vue'),
      redirect: '/bindings',
      children: [
        { path: 'bindings', name: 'Bindings', component: () => import('@/views/protectee/BindingManage.vue') },
        { path: 'activities', name: 'Activities', component: () => import('@/views/protectee/ActivityList.vue') },
        { path: 'realtime', name: 'RealtimeDemo', component: () => import('@/views/protectee/RealtimeDemo.vue') },
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
const PUBLIC_NAMES = ['Login', 'ForgotPassword']

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
