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
 * 与后端各管理 Controller 要求的权限一一对应：
 * 用户/机构/注册码/志愿者审核见 AdminManagementController 等；
 * 管理员管理需 ADMIN_MANAGE（HR 可看），角色权限页同样以 ADMIN_MANAGE 进入、
 * 页内写操作要求 ADMIN_GOVERN。
 */
const navItems = computed<NavItem[]>(() => [
  { index: '/users', label: '用户管理', show: auth.hasPermission('USER_MANAGE') },
  { index: '/organizations', label: '机构管理', show: auth.hasPermission('ORG_MANAGE') },
  { index: '/volunteer-review', label: '志愿者审核', show: auth.hasPermission('VOLUNTEER_REVIEW') },
  { index: '/abuse', label: '举报治理', show: auth.hasPermission('ABUSE_REVIEW') },
  { index: '/admins', label: '管理员管理', show: auth.hasPermission('ADMIN_MANAGE') },
  { index: '/roles', label: '角色权限', show: auth.hasPermission('ADMIN_MANAGE') },
  { index: '/codes', label: '注册码', show: auth.hasPermission('ADMIN_REGISTRATION_CODE_CREATE') },
  { index: '/stats', label: '运营统计', show: auth.hasPermission('USER_MANAGE') || auth.hasPermission('ORG_MANAGE') || auth.hasPermission('VOLUNTEER_REVIEW') },
  // 权限申请：唯一不要求任何权限的页面——没有权限的管理员从这里申请。
  { index: '/permission-requests', label: '权限申请' },
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
