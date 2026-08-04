<template>
  <div class="stats-dashboard">
    <h2>运营统计 (FR-23)</h2>
    <el-row :gutter="20">
      <el-col :span="6" v-for="stat in stats" :key="stat.label">
        <el-card>
          <h3>{{ stat.label }}</h3>
          <p class="stat-value">{{ stat.value }}</p>
        </el-card>
      </el-col>
    </el-row>
    <div ref="chartRef" style="height: 400px; margin-top: 20px;"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

const stats = ref([
  { label: '用户总数', value: 0 },
  { label: '认证机构', value: 0 },
  { label: '本月申请', value: 0 },
  { label: '本月报警', value: 0 },
])

onMounted(() => {
  // TODO(M6): ECharts 统计看板
})
</script>
