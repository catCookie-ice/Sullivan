<template>
  <div class="page-card">
    <el-card>
      <template #header>志愿者兼任审核（VOLUNTEER_REVIEW）</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="审核通过将追加 VOLUNTEER 角色；系统会再次校验注册时长（默认满 90 天）。拒绝必须填写原因。"
        style="margin-bottom: 16px"
      />

      <el-form :inline="true">
        <el-form-item label="状态">
          <el-select v-model="status" clearable placeholder="全部" style="width: 140px">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已拒绝" value="REJECTED" />
            <el-option label="已撤销" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="loginName" label="登录名" width="120" />
        <el-table-column prop="displayName" label="显示名" width="100" />
        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">{{ format(row.applicantCreatedAt) }}</template>
        </el-table-column>
        <el-table-column label="注册天数" width="90">
          <template #default="{ row }">{{ daysSinceLabel(row.applicantCreatedAt) }}</template>
        </el-table-column>
        <el-table-column label="申请时间" width="180">
          <template #default="{ row }">{{ format(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="理由" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="success" @click="approve(row)">通过</el-button>
              <el-button size="small" type="danger" @click="reject(row)">拒绝</el-button>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listVolunteerApplications,
  approveVolunteerApplication,
  rejectVolunteerApplication,
} from '@/api/admin'
import type { VolunteerApplicationAdminResponse } from '@/types/api'
import { daysSince, formatDateTime } from '@/utils/display'

import { errorMessage } from '@/utils/error'
const rows = ref<VolunteerApplicationAdminResponse[]>([])
const status = ref<string>('PENDING')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const res = await listVolunteerApplications({ status: status.value || undefined, page: page.value, size: size.value })
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

async function approve(row: VolunteerApplicationAdminResponse) {
  try {
    const res = await approveVolunteerApplication(row.id)
    ElMessage.success(`已通过，状态：${res.status}`)
    reload()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '审核失败'))
  }
}

async function reject(row: VolunteerApplicationAdminResponse) {
  const { value } = await ElMessageBox.prompt('拒绝原因（必填）', '拒绝申请', {
    inputValidator: (v) => (v && v.trim() ? true : '拒绝原因必填'),
    inputPlaceholder: '请输入拒绝原因',
  }).catch(() => ({ value: '' }))
  if (!value) return
  try {
    const res = await rejectVolunteerApplication(row.id, value)
    ElMessage.success(`已拒绝，状态：${res.status}`)
    reload()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '审核失败'))
  }
}

function daysSinceLabel(iso: string) {
  // 用显式解析而不是 new Date(iso)：服务端的 `yyyy-MM-dd HH:mm:ss` 在 Safari 是 NaN、
  // 在 Chrome 按本地时区解释，直接算天数会得到「NaN 天」或偏差一天。
  const days = daysSince(iso)
  return days == null ? '—' : `${days} 天`
}

function format(iso: string) {
  return formatDateTime(iso)
}

function statusTag(s: string) {
  const map: Record<string, string> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info' }
  return map[s] || 'info'
}

function statusText(s: string) {
  const map: Record<string, string> = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', CANCELLED: '已撤销' }
  return map[s] || s
}
</script>
