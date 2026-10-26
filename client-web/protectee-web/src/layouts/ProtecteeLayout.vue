<template>
  <div class="layout-protectee">
    <div class="layout-nav">
      <span class="layout-brand">Sullivan 受护人端</span>
      <el-menu :router="true" mode="horizontal" class="nav-menu">
        <el-menu-item index="/bindings">绑定管理</el-menu-item>
        <el-menu-item index="/activities">活动浏览</el-menu-item>
        <el-menu-item index="/realtime">实时会话</el-menu-item>
        <el-menu-item index="/voice">语音留言</el-menu-item>
        <el-menu-item style="float: right" @click="handleLogout">退出登录</el-menu-item>
      </el-menu>
    </div>
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
.layout-protectee {
  min-height: 100vh;
}
.layout-nav {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 16px 8px 20px;
  background: var(--paper-deep);
  border-bottom: 1px dashed var(--line);
}
.layout-brand {
  font-family: var(--font-hand);
  font-size: 20px;
  color: var(--ink);
  white-space: nowrap;
  position: relative;
  padding-right: 8px;
}
.layout-brand::before {
  content: "";
  display: inline-block;
  width: 10px;
  height: 10px;
  border: 1.5px solid var(--mist);
  border-radius: 50% 45% 55% 40% / 50% 60% 40% 55%;
  margin-right: 8px;
  transform: rotate(-15deg);
}
.layout-brand::after {
  content: "";
  position: absolute;
  left: 24px;
  right: 4px;
  bottom: -2px;
  height: 5px;
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='6' viewBox='0 0 12 6'%3E%3Cpath d='M0 3 Q3 0 6 3 T12 3' fill='none' stroke='%235f7a92' stroke-width='1.4' stroke-linecap='round'/%3E%3C/svg%3E")
    repeat-x left bottom / 12px 6px;
}
.nav-menu {
  flex: 1;
  min-width: 0;
  background: transparent;
  border-bottom: none;
}
.content {
  min-height: calc(100vh - 61px);
  background: transparent;
}
</style>
