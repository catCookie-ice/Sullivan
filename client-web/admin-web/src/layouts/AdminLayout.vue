<template>
  <div class="layout-admin">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/users">用户管理</el-menu-item>
      <el-menu-item index="/organizations">机构管理</el-menu-item>
      <el-menu-item index="/codes">注册码</el-menu-item>
      <el-menu-item index="/stats">运营统计</el-menu-item>
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
