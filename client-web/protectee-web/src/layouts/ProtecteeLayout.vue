<template>
  <div class="layout-protectee">
    <a class="skip-to-content" href="#main-content">跳至主要内容</a>
    <AppNav brand="Sullivan 受护人端" :items="navItems" @logout="handleLogout" />
    <main id="main-content" class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppNav from '@shared/nav/AppNav.vue'
import type { NavItem } from '@shared/nav/types'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

/**
 * 受护人端菜单。
 *
 * 按 00 §8.1「按角色裁剪功能入口」：受护人不是活动参与方，活动面向志愿者报名，
 * 因此这里**不提供**活动入口 —— 受护人只需绑定、会话与留言。
 *
 * 保留现有四项：绑定管理 / 实时会话 / 语音留言，
 * 以及登录后由实时会话页承载的紧急联络。
 */
const navItems: NavItem[] = [
  { index: '/bindings', label: '绑定管理' },
  { index: '/realtime', label: '实时会话' },
  { index: '/voice', label: '语音留言' },
]

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-protectee {
  min-height: 100vh;
}
.content {
  min-height: calc(100vh - 61px);
  background: transparent;
}
</style>
