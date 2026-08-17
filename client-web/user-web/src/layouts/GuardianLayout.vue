<template>
  <div class="layout-guardian">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/guardian/bindings">绑定管理</el-menu-item>
      <el-menu-item index="/guardian/protectees">创建受护人</el-menu-item>
      <el-menu-item index="/guardian/locations">位置查看</el-menu-item>
      <el-menu-item index="/guardian/alerts">报警</el-menu-item>
      <el-menu-item index="/guardian/services">公益服务</el-menu-item>
      <el-menu-item v-if="!isVolunteer" index="/guardian/volunteer-apply">志愿者申请</el-menu-item>
      <el-menu-item index="/guardian/realtime">实时会话</el-menu-item>
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
const isVolunteer = computed(() => auth.roles.includes('VOLUNTEER'))

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
