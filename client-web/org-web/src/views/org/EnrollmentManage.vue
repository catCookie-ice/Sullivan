<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>报名审核</span>
          <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </template>

      <el-skeleton v-if="loadingActivities && !activities.length" :rows="3" animated />
      <el-empty v-else-if="!activities.length" description="还没有发布过活动，先去「志愿活动」发布一个" />

      <template v-else>
        <el-form :inline="true" class="filter-bar">
          <el-form-item label="活动">
            <el-select v-model="activityId" style="width: 320px" @change="onActivityChange">
              <el-option v-for="item in activities" :key="item.id"
                         :label="item.title" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="status" placeholder="全部" clearable style="width: 140px" @change="reload">
              <el-option label="全部" value="" />
              <el-option label="待审核" value="PENDING" />
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已拒绝" value="REJECTED" />
              <el-option label="已撤回" value="CANCELLED" />
            </el-select>
          </el-form-item>
        </el-form>

        <!-- 名额只统计「已通过」；总报名含待审/已拒/已撤，方便机构评估热度。 -->
        <div v-if="summary" class="summary-row">
          <div class="summary-item">
            <span class="summary-num">{{ summary.total }}</span>
            <span class="summary-label">总报名</span>
          </div>
          <div class="summary-item is-pending">
            <span class="summary-num">{{ summary.pending }}</span>
            <span class="summary-label">待审核</span>
          </div>
          <div class="summary-item is-approved">
            <span class="summary-num">{{ summary.approved }} / {{ currentActivity?.maxVolunteers ?? '—' }}</span>
            <span class="summary-label">已通过（名额）</span>
          </div>
          <div class="summary-item">
            <span class="summary-num">{{ summary.rejected }}</span>
            <span class="summary-label">已拒绝</span>
          </div>
          <div class="summary-item">
            <span class="summary-num">{{ summary.cancelled }}</span>
            <span class="summary-label">已撤回</span>
          </div>
        </div>

        <el-skeleton v-if="loading && !rows.length" :rows="6" animated />
        <el-empty v-else-if="!rows.length" description="没有符合条件的报名" />

        <el-table v-else :data="rows" style="width: 100%">
          <el-table-column label="志愿者" min-width="160">
            <template #default="{ row }">
              <span class="volunteer-name">{{ row.volunteerDisplayName || row.volunteerLoginName || row.volunteerUid }}</span>
              <span v-if="row.volunteerDisplayName" class="volunteer-login">{{ row.volunteerLoginName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="报名时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="审核时间" width="170">
            <template #default="{ row }">{{ row.decidedAt ? formatDateTime(row.decidedAt) : '—' }}</template>
          </el-table-column>
          <el-table-column label="审核意见" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.decisionReason || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'PENDING'">
                <el-button size="small" type="primary" :loading="deciding === row.id"
                           @click="decide(row, true)">通过</el-button>
                <el-button size="small" :loading="deciding === row.id"
                           @click="decide(row, false)">拒绝</el-button>
              </template>
              <span v-else class="done-hint">已处理</span>
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
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { decideEnrollment, listActivityEnrollments, listMyActivities } from '@/api/org'
import type { ActivityResponse, EnrollmentResponse, EnrollmentSummary } from '@/types/api'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const activities = ref<ActivityResponse[]>([])
const activityId = ref('')
const status = ref('')
const rows = ref<EnrollmentResponse[]>([])
const summary = ref<EnrollmentSummary | null>(null)
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadingActivities = ref(false)
const deciding = ref('')

const currentActivity = computed(() => activities.value.find((item) => item.id === activityId.value))

onMounted(async () => {
  loadingActivities.value = true
  try {
    const result = await listMyActivities({ page: 1, size: 100 })
    activities.value = result.records
    if (result.records.length) {
      activityId.value = result.records[0].id
      await load()
    }
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loadingActivities.value = false
  }
})

function onActivityChange() {
  page.value = 1
  load()
}

async function reload() {
  page.value = 1
  await load()
}

async function load() {
  if (!activityId.value) return
  loading.value = true
  try {
    const result = await listActivityEnrollments(activityId.value, {
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = result.records
    summary.value = result.summary
    total.value = result.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function decide(row: EnrollmentResponse, approve: boolean) {
  let reason: string | undefined
  if (!approve) {
    const input = window.prompt('拒绝原因（可留空）') ?? ''
    reason = input.trim() || undefined
  }
  deciding.value = row.id
  try {
    await decideEnrollment(row.id, { approve, reason })
    ElMessage.success(approve ? '已通过，志愿者已加入活动群聊' : '已拒绝')
    await load()
  } catch (error) {
    // 名额已满等情况后端返回 409，报名行仍在待审，刷新后可改判他人。
    ElMessage.error(errorMessage(error))
    await load()
  } finally {
    deciding.value = ''
  }
}

function onPageChange(next: number) {
  page.value = next
  load()
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
.filter-bar {
  margin-bottom: 8px;
}
.summary-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin: 4px 0 16px;
}
.summary-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 18px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--paper-tint);
  min-width: 96px;
}
.summary-num {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink-1);
}
.summary-label {
  font-size: 12px;
  color: var(--ink-3);
}
.is-pending .summary-num {
  color: var(--terra, #b45309);
}
.is-approved .summary-num {
  color: var(--mist-dark, #15803d);
}
.volunteer-name {
  display: block;
  color: var(--ink-1);
}
.volunteer-login {
  display: block;
  font-size: 12px;
  color: var(--ink-3);
}
.done-hint {
  font-size: 12px;
  color: var(--ink-4);
}
</style>
