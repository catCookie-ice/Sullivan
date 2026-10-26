<template>
  <div class="layout-admin">
    <a class="skip-to-content" href="#main-content">跳至主要内容</a>
    <AppNav brand="Sullivan 管理端" :items="navItems" @logout="handleLogout" />
    <main id="main-content" class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import AppNav from '@shared/nav/AppNav.vue'
import type { NavItem } from '@shared/nav/types'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

/**
 * 菜单按权限裁剪：缺权限的入口不显示，而不是显示后点进去报 403。
 * 与后端 AdminManagementController / AdminVolunteerController 要求的权限一一对应。
 */
const navItems = computed<NavItem[]>(() => [
  { index: '/users', label: '用户管理', show: auth.hasPermission('USER_MANAGE') },
  { index: '/organizations', label: '机构管理', show: auth.hasPermission('ORG_MANAGE') },
  { index: '/codes', label: '注册码' },
  { index: '/stats', label: '运营统计' },
  { index: '/volunteer-review', label: '志愿者审核', show: auth.hasPermission('VOLUNTEER_REVIEW') },
])

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-admin {
  min-height: 100vh;
}
.content {
  min-height: calc(100vh - 61px);
  background: transparent;
}
</style>
