<template>
  <div class="layout-guardian">
    <a class="skip-to-content" href="#main-content">跳至主要内容</a>
    <AppNav brand="Sullivan 监护与志愿者端" :items="navItems" @logout="handleLogout" />
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
const isVolunteer = computed(() => auth.roles.includes('VOLUNTEER'))
const isGuardian = computed(() => auth.roles.includes('GUARDIAN'))

/**
 * 监护人端菜单。
 *
 * 按角色裁剪：
 *   - 「志愿者申请」只在还不是志愿者时出现（已是志愿者就没什么可申请的）；
 *   - 「切换到志愿者端」只在同时具备 GUARDIAN 与 VOLUNTEER 时出现。
 *
 * 「位置查看」与「公益服务」暂时隐藏：前者依赖的 GET /guardians/locations/{uid}
 * 与后者依赖的 /services 都尚未在服务端实现（见 /v3/api-docs），
 * 按 00 §6.2 第 3 条不展示不可用入口。页面文件保留，后端就绪后把 show 改回 true。
 */
const navItems = computed<NavItem[]>(() => [
  { index: '/guardian/bindings', label: '绑定管理' },
  { index: '/guardian/protectees', label: '创建受护人' },
  { index: '/guardian/locations', label: '位置查看', show: false },
  { index: '/guardian/alerts', label: '报警' },
  { index: '/guardian/services', label: '公益服务', show: false },
  { index: '/guardian/volunteer-apply', label: '志愿者申请', show: !isVolunteer.value },
  { index: '/guardian/realtime', label: '实时会话' },
  { index: '/guardian/voice', label: '语音留言' },
  { index: '/volunteer/activities', label: '志愿者端', show: isVolunteer.value && isGuardian.value },
])

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-guardian {
  min-height: 100vh;
}
.content {
  min-height: calc(100vh - 61px);
  background: transparent;
}
</style>
