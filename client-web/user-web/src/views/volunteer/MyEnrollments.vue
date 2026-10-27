<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>我的报名</span>
          <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </template>

      <el-skeleton v-if="loading && !rows.length" :rows="5" animated />
      <el-empty v-else-if="!rows.length" description="还没有报名过活动，去「活动列表」看看吧" />

      <el-table v-else :data="rows" style="width: 100%">
        <el-table-column label="活动" min-width="200">
          <template #default="{ row }">
            <span class="activity-title">{{ row.activityTitle || row.activityId }}</span>
            <span class="activity-org">{{ row.organizationPublicName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="报名时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="审核信息" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.decidedAt">
              {{ formatDateTime(row.decidedAt) }}<span v-if="row.decisionReason"> · {{ row.decisionReason }}</span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'APPROVED'"
              size="small" type="primary"
              @click="$router.push({ path: '/volunteer/group-chat', query: { activityId: row.activityId } })"
            >进入群聊</el-button>
            <el-button
              v-if="row.status === 'PENDING' || row.status === 'APPROVED'"
              size="small" :loading="cancelling === row.id"
              @click="cancel(row)"
            >{{ row.status === 'PENDING' ? '撤回报名' : '取消报名' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
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
import { cancelEnrollment, listMyEnrollments } from '@/api/activity'
import type { EnrollmentResponse } from '@/types/api'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const rows = ref<EnrollmentResponse[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const loading = ref(false)
const cancelling = ref('')

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const result = await listMyEnrollments({ page: page.value, size: size.value })
    rows.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function cancel(row: EnrollmentResponse) {
  const text = row.status === 'PENDING'
    ? '撤回该报名？'
    : '取消该报名？已通过的名额会释放，同时退出活动群聊。'
  if (!window.confirm(text)) return
  cancelling.value = row.id
  try {
    await cancelEnrollment(row.id)
    ElMessage.success('已撤回')
    await reload()
  } catch (error) {
    ElMessage.error(errorMessage(error))
    await reload()
  } finally {
    cancelling.value = ''
  }
}

function onPageChange(next: number) {
  page.value = next
  reload()
}

function statusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', CANCELLED: '已撤回',
  }
  return map[status] ?? status
}

function statusTag(status: string) {
  const map: Record<string, string> = {
    PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info',
  }
  return (map[status] ?? 'info') as 'warning' | 'success' | 'danger' | 'info'
}
</script>

<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.activity-title {
  display: block;
  color: var(--ink-1);
}
.activity-org {
  display: block;
  font-size: 12px;
  color: var(--ink-3);
}
</style>
