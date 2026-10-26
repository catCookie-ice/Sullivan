<template>
  <div class="layout-org">
    <a class="skip-to-content" href="#main-content">跳至主要内容</a>
    <AppNav brand="Sullivan 公益机构端" :items="navItems" @logout="handleLogout" />
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
 * 机构端菜单。
 *
 * 「服务项目 / 服务申请 / 报名审核」三个入口对应的后端接口**尚未实现**
 * （运行中的 /v3/api-docs 里没有 /services、/applications、/enrollments），
 * 只存在于设计文档。按 00 §6.2 第 3 条「MINI UI 不展示不可用入口」，
 * 这里先把它们从菜单隐藏；页面文件保留，后端就绪后把 show 改回 true 即可。
 */
const navItems: NavItem[] = [
  { index: '/profile', label: '机构资料' },
  { index: '/services', label: '服务项目', show: false },
  { index: '/applications', label: '服务申请', show: false },
  { index: '/activities', label: '志愿活动' },
  { index: '/enrollments', label: '报名审核', show: false },
]

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-org {
  min-height: 100vh;
}
.content {
  min-height: calc(100vh - 61px);
  background: transparent;
}
</style>
