<template>
  <div class="layout-volunteer">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/volunteer/activities">活动列表</el-menu-item>
      <el-menu-item index="/volunteer/enrollments">我的报名</el-menu-item>
      <el-menu-item index="/volunteer/availability">可服务状态</el-menu-item>
      <el-menu-item index="/volunteer/alerts">附近求助</el-menu-item>
      <el-menu-item v-if="isGuardian" @click="goGuardian">切换监护人视角</el-menu-item>
      <el-menu-item style="float: right" @click="handleLogout">退出登录</el-menu-item>
    </el-menu>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const isGuardian = computed(() => auth.roles.includes('GUARDIAN'))

function goGuardian() {
  router.push('/guardian')
}

function handleLogout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.content {
  min-height: calc(100vh - 60px);
  background: #f5f7fa;
}
</style>
