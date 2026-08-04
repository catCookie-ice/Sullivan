<template>
  <div class="layout-guardian">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/guardian/bindings">绑定管理</el-menu-item>
      <el-menu-item index="/guardian/locations">位置查看</el-menu-item>
      <el-menu-item index="/guardian/alerts">报警</el-menu-item>
      <el-menu-item index="/guardian/services">公益服务</el-menu-item>
    </el-menu>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
