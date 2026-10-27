<template>
  <div class="layout-volunteer">
    <a class="skip-to-content" href="#main-content">跳至主要内容</a>
    <AppNav brand="Sullivan 志愿者端" :items="navItems" @logout="handleLogout">
      <template #actions>
        <NotificationBell :api="notificationApi" />
      </template>
    </AppNav>
    <main id="main-content" class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import AppNav from '@shared/nav/AppNav.vue'
import NotificationBell from '@shared/notifications/NotificationBell.vue'
import type { NavItem } from '@shared/nav/types'
import { useAuthStore } from '@/stores/auth'
import { notificationApi } from '@/api/notification'

const router = useRouter()
const auth = useAuthStore()
const isGuardian = computed(() => auth.roles.includes('GUARDIAN'))

/**
 * 志愿者端菜单。
 *
 * 「我的报名 / 活动群聊」接口已上线（V2），正常展示。
 * 「可服务状态 / 附近求助」暂时隐藏：可服务状态接口尚未在服务端实现；
 * 附近求助也还没有面向志愿者的报警查询端点 —— GET /alerts 只返回「自己发起的」
 * 与「投递给自己的」，志愿者收不到无监护人场景的广播。
 * 按 00 §6.2 第 3 条不展示不可用入口。页面文件保留，后端就绪后把 show 改回 true。
 */
const navItems = computed<NavItem[]>(() => [
  { index: '/volunteer/activities', label: '活动列表' },
  { index: '/volunteer/enrollments', label: '我的报名' },
  { index: '/volunteer/group-chat', label: '活动群聊' },
  { index: '/volunteer/penalties', label: '处罚与举报' },
  { index: '/volunteer/availability', label: '可服务状态', show: false },
  { index: '/volunteer/alerts', label: '附近求助', show: false },
  { index: '/guardian', label: '监护人端', show: isGuardian.value },
])

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout-volunteer {
  min-height: 100vh;
}
.content {
  min-height: calc(100vh - 61px);
  background: transparent;
}
</style>
