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
      path: '/',
      name: 'AdminLayout',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/users',
      children: [
        { path: 'users', name: 'UserManage', component: () => import('@/views/admin/UserManage.vue') },
        { path: 'organizations', name: 'OrgManage', component: () => import('@/views/admin/OrgManage.vue') },
        { path: 'codes', name: 'RegistrationCodes', component: () => import('@/views/admin/RegistrationCode.vue') },
        { path: 'stats', name: 'Stats', component: () => import('@/views/admin/StatsDashboard.vue') },
        { path: 'volunteer-review', name: 'VolunteerReview', component: () => import('@/views/admin/VolunteerReview.vue') },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/error/NotFound.vue'),
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.name !== 'Login' && !auth.accessToken) {
    return { name: 'Login' }
  }
  if (to.name === 'Login' && auth.accessToken) {
    return { path: '/' }
  }
  return true
})

export default router
