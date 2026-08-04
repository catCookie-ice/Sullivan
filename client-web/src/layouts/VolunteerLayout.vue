<template>
  <div class="layout-volunteer">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/volunteer/activities">活动列表</el-menu-item>
      <el-menu-item index="/volunteer/enrollments">我的报名</el-menu-item>
      <el-menu-item index="/volunteer/availability">可服务状态</el-menu-item>
      <el-menu-item index="/volunteer/alerts">附近求助</el-menu-item>
    </el-menu>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
