import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/auth/LoginView.vue'),
    },
    {
      path: '/guardian',
      name: 'Guardian',
      component: () => import('@/layouts/GuardianLayout.vue'),
      children: [
        { path: 'bindings', name: 'Bindings', component: () => import('@/views/guardian/BindingList.vue') },
        { path: 'locations', name: 'Locations', component: () => import('@/views/guardian/LocationView.vue') },
        { path: 'alerts', name: 'GuardianAlerts', component: () => import('@/views/guardian/AlertList.vue') },
        { path: 'services', name: 'GuardianServices', component: () => import('@/views/guardian/ServiceList.vue') },
      ],
    },
    {
      path: '/volunteer',
      name: 'Volunteer',
      component: () => import('@/layouts/VolunteerLayout.vue'),
      children: [
        { path: 'activities', name: 'Activities', component: () => import('@/views/volunteer/ActivityList.vue') },
        { path: 'enrollments', name: 'MyEnrollments', component: () => import('@/views/volunteer/MyEnrollments.vue') },
        { path: 'availability', name: 'Availability', component: () => import('@/views/volunteer/AvailabilityView.vue') },
        { path: 'alerts', name: 'VolunteerAlerts', component: () => import('@/views/volunteer/AlertList.vue') },
      ],
    },
    {
      path: '/organization',
      name: 'Organization',
      component: () => import('@/layouts/OrgLayout.vue'),
      children: [
        { path: 'profile', name: 'OrgProfile', component: () => import('@/views/organization/OrgProfile.vue') },
        { path: 'services', name: 'OrgServices', component: () => import('@/views/organization/ServiceManage.vue') },
        { path: 'applications', name: 'OrgApplications', component: () => import('@/views/organization/ApplicationManage.vue') },
        { path: 'activities', name: 'OrgActivities', component: () => import('@/views/organization/ActivityManage.vue') },
        { path: 'enrollments', name: 'OrgEnrollments', component: () => import('@/views/organization/EnrollmentManage.vue') },
      ],
    },
    {
      path: '/admin',
      name: 'Admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
        { path: 'org-review', name: 'OrgReview', component: () => import('@/views/admin/OrgReview.vue') },
        { path: 'users', name: 'UserGovern', component: () => import('@/views/admin/UserGovern.vue') },
        { path: 'admins', name: 'AdminManage', component: () => import('@/views/admin/AdminManage.vue') },
        { path: 'stats', name: 'Stats', component: () => import('@/views/admin/StatsDashboard.vue') },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/error/NotFound.vue'),
    },
  ],
})

export default router
