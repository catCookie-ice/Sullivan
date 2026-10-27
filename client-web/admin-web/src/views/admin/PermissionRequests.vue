<template>
  <div class="page-card">
    <el-card>
      <template #header>权限申请</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="没有权限就看不到对应页面。在这里申请「给自己开通一项权限」：只需要选择权限、填写理由——发起不需要任何权限（只要是管理员）；由持 ADMIN_GOVERN 的管理员审批，批准后自动开通。不能审批自己发起的申请。"
        style="margin-bottom: 16px"
      />

      <el-tabs v-model="tab">
        <!-- 我的申请 -->
        <el-tab-pane label="我的申请" name="mine">
          <el-button type="primary" style="margin-bottom: 12px" @click="openApply">发起新申请</el-button>
          <el-table :data="mine" v-loading="mineLoading" border>
            <el-table-column label="申请权限" min-width="200">
              <template #default="{ row }">
                {{ row.permissionCode }}<span v-if="row.permissionName">（{{ row.permissionName }}）</span>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="理由" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="decisionNote" label="审批意见" min-width="130" show-overflow-tooltip />
            <el-table-column label="申请时间" width="170">
              <template #default="{ row }">{{ format(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 审批（仅 ADMIN_GOVERN 可见） -->
        <el-tab-pane v-if="auth.hasPermission('ADMIN_GOVERN')" label="待我审批" name="review">
          <el-form :inline="true">
            <el-form-item label="状态">
              <el-select v-model="reviewStatus" clearable placeholder="全部" style="width: 130px">
                <el-option label="待审批" value="PENDING" />
                <el-option label="已批准" value="APPROVED" />
                <el-option label="已驳回" value="REJECTED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reloadReview()">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="review" v-loading="reviewLoading" border>
            <el-table-column label="申请人" width="130">
              <template #default="{ row }">{{ row.requestedBy }}</template>
            </el-table-column>
            <el-table-column label="申请权限" min-width="180">
              <template #default="{ row }">
                {{ row.permissionCode }}<span v-if="row.permissionName">（{{ row.permissionName }}）</span>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="理由" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="申请时间" width="170">
              <template #default="{ row }">{{ format(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button size="small" type="success" @click="onDecide(row, true)">批准</el-button>
                  <el-button size="small" type="danger" @click="onDecide(row, false)">驳回</el-button>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 发起申请对话框：只有权限与理由 -->
    <el-dialog v-model="applyVisible" title="申请开通权限" width="440px">
      <el-form label-width="90px">
        <el-form-item label="申请权限">
          <el-select v-model="form.permissionCode" filterable style="width: 100%">
            <el-option
              v-for="option in options"
              :key="option.code"
              :label="`${option.code}（${option.name}）`"
              :value="option.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申请理由">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="说明为什么需要这项权限"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createPermissionRequest,
  decidePermissionRequest,
  listMyPermissionRequests,
  listPermissionOptions,
  listPermissionRequests,
  type PermissionOption,
  type PermissionRequest,
} from '@/api/permissionRequests'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const auth = useAuthStore()
const tab = ref('mine')

// ---------- 我的申请 ----------
const mine = ref<PermissionRequest[]>([])
const mineLoading = ref(false)

async function reloadMine() {
  mineLoading.value = true
  try {
    mine.value = await listMyPermissionRequests()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    mineLoading.value = false
  }
}

// ---------- 发起（只有权限与理由） ----------
const applyVisible = ref(false)
const submitting = ref(false)
const options = ref<PermissionOption[]>([])
const form = ref<{ permissionCode: string; reason: string }>({ permissionCode: '', reason: '' })

function openApply() {
  form.value = { permissionCode: '', reason: '' }
  applyVisible.value = true
  if (options.value.length === 0) {
    listPermissionOptions()
      .then((rows) => {
        options.value = rows
      })
      .catch((error) => ElMessage.error(errorMessage(error)))
  }
}

async function submitApply() {
  if (!form.value.permissionCode || !form.value.reason.trim()) {
    ElMessage.warning('请选择权限并填写理由')
    return
  }
  submitting.value = true
  try {
    await createPermissionRequest({
      permissionCode: form.value.permissionCode,
      reason: form.value.reason.trim(),
    })
    ElMessage.success('申请已提交，等待 ADMIN_GOVERN 管理员审批')
    applyVisible.value = false
    reloadMine()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}

// ---------- 审批 ----------
const review = ref<PermissionRequest[]>([])
const reviewLoading = ref(false)
const reviewStatus = ref<string>()

async function reloadReview() {
  reviewLoading.value = true
  try {
    review.value = await listPermissionRequests(reviewStatus.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    reviewLoading.value = false
  }
}

async function onDecide(row: PermissionRequest, approve: boolean) {
  try {
    await ElMessageBox.confirm(
      approve ? `批准后将为申请人开通 ${row.permissionCode}。确认？` : '驳回该申请？',
      '审批权限申请',
    )
  } catch {
    return
  }
  try {
    await decidePermissionRequest(row.id, approve)
    ElMessage.success(approve ? '已批准并开通' : '已驳回')
    reloadReview()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function format(value?: string | null) {
  return value ? formatDateTime(value) : '—'
}
function statusText(status: string) {
  const map: Record<string, string> = { PENDING: '待审批', APPROVED: '已批准', REJECTED: '已驳回' }
  return map[status] ?? status
}
function statusTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'info' }
  return map[status] ?? 'info'
}

onMounted(reloadMine)
</script>
