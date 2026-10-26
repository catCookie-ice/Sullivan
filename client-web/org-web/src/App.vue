<template>
  <!--
    中文语言包通过 ConfigProvider 提供，而不是 app.use(ElementPlus, { locale })。
    后者会把整个组件库全量注册进主包，使按需引入失效（UI-07 包体积问题的根因）。
    ConfigProvider 只影响「文本类」默认值（分页「共 N 条」、日期面板月份名、
    表格空数据文案等），不注册任何组件，因此不增加体积。
  -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
