<template>
  <div class="layout-admin">
    <el-menu :router="true" mode="horizontal">
      <el-menu-item index="/admin/org-review">机构审核</el-menu-item>
      <el-menu-item index="/admin/users">用户治理</el-menu-item>
      <el-menu-item index="/admin/admins">管理员管理</el-menu-item>
      <el-menu-item index="/admin/stats">运营统计</el-menu-item>
    </el-menu>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
