<template>
  <div class="layout-org">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/profile">机构资料</el-menu-item>
      <el-menu-item index="/services">服务项目</el-menu-item>
      <el-menu-item index="/applications">服务申请</el-menu-item>
      <el-menu-item index="/activities">志愿活动</el-menu-item>
      <el-menu-item index="/enrollments">报名审核</el-menu-item>
      <el-menu-item style="float: right" @click="handleLogout">退出登录</el-menu-item>
    </el-menu>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

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
