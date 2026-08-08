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
      component: () => import('@/layouts/HomeRedirect.vue'),
    },
    {
      path: '/guardian',
      name: 'Guardian',
      component: () => import('@/layouts/GuardianLayout.vue'),
      redirect: '/guardian/bindings',
      children: [
        { path: 'bindings', name: 'Bindings', component: () => import('@/views/guardian/BindingManage.vue') },
        { path: 'protectees', name: 'Protectees', component: () => import('@/views/guardian/CreateProtectee.vue') },
        { path: 'locations', name: 'Locations', component: () => import('@/views/guardian/LocationView.vue') },
        { path: 'alerts', name: 'GuardianAlerts', component: () => import('@/views/guardian/AlertList.vue') },
        { path: 'services', name: 'GuardianServices', component: () => import('@/views/guardian/ServiceList.vue') },
      ],
    },
    {
      path: '/volunteer',
      name: 'Volunteer',
      component: () => import('@/layouts/VolunteerLayout.vue'),
      redirect: '/volunteer/activities',
      children: [
        { path: 'activities', name: 'Activities', component: () => import('@/views/volunteer/ActivityList.vue') },
        { path: 'enrollments', name: 'MyEnrollments', component: () => import('@/views/volunteer/MyEnrollments.vue') },
        { path: 'availability', name: 'Availability', component: () => import('@/views/volunteer/AvailabilityView.vue') },
        { path: 'alerts', name: 'VolunteerAlerts', component: () => import('@/views/volunteer/AlertList.vue') },
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
  // 无角色用户或纯受护人默认回监护人首页
  if (to.path === '/' && auth.accessToken) {
    const isVolunteer = auth.roles.includes('VOLUNTEER')
    const isGuardian = auth.roles.includes('GUARDIAN')
    if (isVolunteer && !isGuardian) return { path: '/volunteer' }
    return { path: '/guardian' }
  }
  return true
})

export default router
