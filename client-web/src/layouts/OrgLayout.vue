<template>
  <div class="layout-org">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/organization/profile">机构资料</el-menu-item>
      <el-menu-item index="/organization/services">服务项目</el-menu-item>
      <el-menu-item index="/organization/applications">服务申请</el-menu-item>
      <el-menu-item index="/organization/activities">志愿活动</el-menu-item>
      <el-menu-item index="/organization/enrollments">报名审核</el-menu-item>
    </el-menu>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
