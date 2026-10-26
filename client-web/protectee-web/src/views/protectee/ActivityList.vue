<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>公益志愿活动（只读浏览）</span>
        </div>
      </template>

      <el-form :inline="true">
        <el-form-item label="状态">
          <el-select v-model="status" clearable placeholder="全部" style="width: 140px">
            <el-option label="招募中" value="RECRUITING" />
            <el-option label="进行中" value="IN_PROGRESS" />
            <el-option label="已结束" value="ENDED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="title" label="活动名" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="organizationPublicName" label="机构" min-width="120" show-overflow-tooltip />
        <el-table-column prop="locationText" label="地点" min-width="140" show-overflow-tooltip />
        <el-table-column label="活动时间" width="200">
          <template #default="{ row }">{{ format(row.startsAt) }} ~ {{ format(row.endsAt) }}</template>
        </el-table-column>
        <el-table-column prop="maxVolunteers" label="需要人数" width="90" />
        <el-table-column prop="approvedCount" label="已报名" width="90" />
      </el-table>

      <el-pagination
        style="margin-top: 12px; justify-content: flex-end"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listPublicActivities } from '@/api/activity'
import type { ActivityResponse } from '@/types/api'

import { errorMessage } from '@/utils/error'
const rows = ref<ActivityResponse[]>([])
const status = ref<string>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const res = await listPublicActivities({ status: status.value || undefined, page: page.value, size: size.value })
    rows.value = res.records
    total.value = res.total
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  reload()
}

function format(iso: string) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

function statusTag(s: string) {
  const map: Record<string, string> = { RECRUITING: 'success', IN_PROGRESS: 'warning', ENDED: 'info', CANCELLED: 'danger' }
  return map[s] || 'info'
}

function statusText(s: string) {
  const map: Record<string, string> = { RECRUITING: '招募中', IN_PROGRESS: '进行中', ENDED: '已结束', CANCELLED: '已取消' }
  return map[s] || s
}
</script>
