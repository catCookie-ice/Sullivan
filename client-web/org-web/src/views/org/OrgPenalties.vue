<template>
  <div class="penalties-page">
    <el-card>
      <template #header>机构处罚与申诉</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="封禁期间不能发布新活动；对处罚有异议可在处罚生效期间申诉（每条一次），撤销后相关限制会同步解除。"
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { appealOrgPenalty, listOrgPenalties, type OrgPenalty } from '@/api/abuse'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const penalties = ref<OrgPenalty[]>([])
const loading = ref(false)
const submitting = ref(false)
const appealVisible = ref(false)
const appealReason = ref('')
const appealTarget = ref<OrgPenalty>()

async function reload() {
  loading.value = true
  try {
    penalties.value = await listOrgPenalties()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function openAppeal(penalty: OrgPenalty) {
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
    await appealOrgPenalty(appealTarget.value.id, appealReason.value.trim())
    ElMessage.success('申诉已提交，等待处理')
    appealVisible.value = false
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
