<template>
  <div class="penalties-page">
    <el-card>
      <template #header>我的处罚与申诉</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="对处罚有异议可在处罚生效期间申诉（每条一次）；申诉撤销后相关限制会同步解除。"
        style="margin-bottom: 12px"
      />
      <el-table :data="penalties" v-loading="loading" border>
        <el-table-column prop="presetName" label="处罚" min-width="140" />
        <el-table-column prop="reason" label="依据" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="penaltyTag(row.status)">{{ penaltyText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生效时间" width="170">
          <template #default="{ row }">{{ format(row.appliedAt) }}</template>
        </el-table-column>
        <el-table-column label="到期" width="170">
          <template #default="{ row }">{{ row.expiresAt ? format(row.expiresAt) : '永久' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.appealable" size="small" type="warning" @click="openAppeal(row)">
              申诉
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>针对我的举报（仅被驳回的，供反诉）</template>
      <el-table :data="received" v-loading="loading" border>
        <el-table-column label="类别" width="90">
          <template #default="{ row }">{{ categoryText(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="举报理由" min-width="160" show-overflow-tooltip />
        <el-table-column label="举报时间" width="170">
          <template #default="{ row }">{{ format(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="驳回时间" width="170">
          <template #default="{ row }">{{ format(row.handledAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.counterReportable" size="small" type="danger" @click="openCounter(row)">
              反诉恶意举报
            </el-button>
            <span v-else>已反诉</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>我发起的举报</template>
      <el-table :data="mine" v-loading="loading" border>
        <el-table-column label="类别" width="90">
          <template #default="{ row }">{{ categoryText(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="举报理由" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="decisionNote" label="处置意见" min-width="140" show-overflow-tooltip />
        <el-table-column label="举报时间" width="170">
          <template #default="{ row }">{{ format(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 申诉对话框 -->
    <el-dialog v-model="appealVisible" title="申诉处罚" width="440px">
      <el-input
        v-model="appealReason"
        type="textarea"
        :rows="3"
        maxlength="500"
        placeholder="请填写申诉理由（说明处罚为何不应生效）"
      />
      <template #footer>
        <el-button @click="appealVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAppeal">提交申诉</el-button>
      </template>
    </el-dialog>

    <!-- 反诉对话框 -->
    <el-dialog v-model="counterVisible" title="反诉恶意举报" width="440px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="反诉以匿名方式提交，对方不会知道你是谁。若对方近期大量举报被驳回，系统将自动升级限制。"
        style="margin-bottom: 12px"
      />
      <el-input
        v-model="counterReason"
        type="textarea"
        :rows="3"
        maxlength="500"
        placeholder="请说明对方恶意举报的情况"
      />
      <template #footer>
        <el-button @click="counterVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitCounter">提交反诉</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  appealPenalty,
  createCounterReport,
  listMyPenalties,
  listMyReports,
  listReceivedReports,
  type MyPenalty,
  type MyReport,
  type ReceivedReport,
} from '@/api/abuse'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const penalties = ref<MyPenalty[]>([])
const received = ref<ReceivedReport[]>([])
const mine = ref<MyReport[]>([])
const loading = ref(false)
const submitting = ref(false)

const appealVisible = ref(false)
const appealReason = ref('')
const appealTarget = ref<MyPenalty>()
const counterVisible = ref(false)
const counterReason = ref('')
const counterTarget = ref<ReceivedReport>()

async function reload() {
  loading.value = true
  try {
    const [p, r, m] = await Promise.all([listMyPenalties(), listReceivedReports(), listMyReports()])
    penalties.value = p
    received.value = r
    mine.value = m
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openAppeal(penalty: MyPenalty) {
  appealTarget.value = penalty
  appealReason.value = ''
  appealVisible.value = true
}

async function submitAppeal() {
  if (!appealTarget.value || !appealReason.value.trim()) {
    ElMessage.warning('请填写申诉理由')
    return
  }
  submitting.value = true
  try {
    await appealPenalty(appealTarget.value.id, appealReason.value.trim())
    ElMessage.success('申诉已提交，等待处理')
    appealVisible.value = false
    reload()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}

function openCounter(report: ReceivedReport) {
  counterTarget.value = report
  counterReason.value = ''
  counterVisible.value = true
}

async function submitCounter() {
  if (!counterTarget.value || !counterReason.value.trim()) {
    ElMessage.warning('请填写反诉理由')
    return
  }
  submitting.value = true
  try {
    await createCounterReport(counterTarget.value.id, counterReason.value.trim())
    ElMessage.success('反诉已提交')
    counterVisible.value = false
    reload()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}

function format(value?: string | null) {
  return value ? formatDateTime(value) : '—'
}
function categoryText(category: string) {
  const map: Record<string, string> = {
    ACTIVITY: '活动',
    ORGANIZATION: '机构',
    GROUP: '群聊',
    USER: '个人',
    REPORT_ABUSE: '反诉',
  }
  return map[category] ?? category
}
function statusText(status: string) {
  const map: Record<string, string> = { PENDING: '待处理', RESOLVED: '已处置', DISMISSED: '已驳回' }
  return map[status] ?? status
}
function statusTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', RESOLVED: 'danger', DISMISSED: 'info' }
  return map[status] ?? 'info'
}
function penaltyText(status: string) {
  const map: Record<string, string> = { ACTIVE: '生效中', LIFTED: '已撤销', EXPIRED: '已到期' }
  return map[status] ?? status
}
function penaltyTag(status: string) {
  const map: Record<string, string> = { ACTIVE: 'danger', LIFTED: 'success', EXPIRED: 'info' }
  return map[status] ?? 'info'
}

onMounted(reload)
</script>

<style scoped>
.penalties-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
