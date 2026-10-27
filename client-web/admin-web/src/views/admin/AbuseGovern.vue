<template>
  <div class="page-card">
    <el-card>
      <template #header>举报治理（ABUSE_REVIEW）</template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="处罚只能从「生效中」的预设中选择，不能随意设置；新预设由管理员申请、另一名管理员批准。驳回恶意举报达到阈值会自动锁定举报人。"
        style="margin-bottom: 16px"
      />

      <el-tabs v-model="tab">
        <!-- 举报处置 -->
        <el-tab-pane label="举报处置" name="reports">
          <el-form :inline="true">
            <el-form-item label="状态">
              <el-select v-model="reportStatus" clearable placeholder="全部" style="width: 130px">
                <el-option label="待处理" value="PENDING" />
                <el-option label="已处置" value="RESOLVED" />
                <el-option label="已驳回" value="DISMISSED" />
              </el-select>
            </el-form-item>
            <el-form-item label="类别">
              <el-select v-model="reportCategory" clearable placeholder="全部" style="width: 140px">
                <el-option label="活动" value="ACTIVITY" />
                <el-option label="机构" value="ORGANIZATION" />
                <el-option label="群聊" value="GROUP" />
                <el-option label="个人" value="USER" />
                <el-option label="反向举报" value="REPORT_ABUSE" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reloadReports()">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="reportGroups" v-loading="reportsLoading" border>
            <el-table-column type="expand">
              <template #default="{ row }">
                <el-table :data="row.reports" size="small" border style="margin: 8px 16px">
                  <el-table-column prop="reporterName" label="举报人" width="120" />
                  <el-table-column prop="reason" label="理由" min-width="160" show-overflow-tooltip />
                  <el-table-column label="状态" width="90">
                    <template #default="{ row: item }">
                      <el-tag :type="reportStatusTag(item.status)">{{ reportStatusText(item.status) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="举报时间" width="170">
                    <template #default="{ row: item }">{{ format(item.createdAt) }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="190" fixed="right">
                    <template #default="{ row: item }">
                      <el-button v-if="item.status === 'PENDING'" size="small" type="primary" @click="openDecision(item)">
                        处置
                      </el-button>
                      <el-button size="small" @click="openHistory(item)">档案</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
            </el-table-column>
            <el-table-column label="类别" width="100">
              <template #default="{ row }">
                <el-tag :type="categoryTag(row.category)">{{ categoryText(row.category) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="targetSummary" label="举报对象" min-width="150" show-overflow-tooltip />
            <el-table-column label="举报数" width="80">
              <template #default="{ row }">{{ row.reportCount }}</template>
            </el-table-column>
            <el-table-column label="待处理" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.pendingCount > 0" type="danger">{{ row.pendingCount }}</el-tag>
                <span v-else>0</span>
              </template>
            </el-table-column>
            <el-table-column label="最新状态" width="100">
              <template #default="{ row }">
                <el-tag :type="reportStatusTag(row.latestStatus)">{{ reportStatusText(row.latestStatus) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近举报" width="170">
              <template #default="{ row }">{{ format(row.latestCreatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.pendingCount > 0"
                  size="small"
                  type="primary"
                  @click="openDecision(firstPendingReport(row)!)"
                >
                  处置
                </el-button>
                <el-button size="small" @click="openHistory(row.reports[0])">档案</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 预设处罚 -->
        <el-tab-pane label="预设处罚" name="presets">
          <el-form :inline="true">
            <el-form-item label="状态">
              <el-select v-model="presetStatus" clearable placeholder="全部" style="width: 130px">
                <el-option label="待审批" value="PENDING" />
                <el-option label="生效中" value="ACTIVE" />
                <el-option label="已驳回" value="REJECTED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reloadPresets()">查询</el-button>
              <el-button type="success" @click="presetDialogVisible = true">申请新预设</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="presets" v-loading="presetsLoading" border>
            <el-table-column prop="name" label="处罚名" min-width="150" />
            <el-table-column label="对象" width="90">
              <template #default="{ row }">{{ categoryText(row.category) }}</template>
            </el-table-column>
            <el-table-column label="动作" width="150">
              <template #default="{ row }">{{ actionText(row.action) }}</template>
            </el-table-column>
            <el-table-column label="封禁天数" width="90">
              <template #default="{ row }">{{ row.durationDays ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="组合" min-width="150">
              <template #default="{ row }">
                <template v-if="row.parts && row.parts.length">
                  <el-tag
                    v-for="part in row.parts"
                    :key="part.targetKind + part.action"
                    size="small"
                    style="margin-right: 4px"
                  >
                    {{ categoryText(part.targetKind) }}·{{ actionText(part.action) }}
                    <template v-if="part.durationDays"> {{ part.durationDays }}天</template>
                  </el-tag>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="140" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="presetStatusTag(row.status)">{{ presetStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button size="small" type="success" @click="approve(row.id, true)">批准</el-button>
                  <el-button size="small" type="danger" @click="approve(row.id, false)">驳回</el-button>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 申诉 -->
        <el-tab-pane label="申诉处理" name="appeals">
          <el-form :inline="true">
            <el-form-item label="状态">
              <el-select v-model="appealStatus" clearable placeholder="全部" style="width: 130px">
                <el-option label="待处理" value="PENDING" />
                <el-option label="已维持" value="UPHELD" />
                <el-option label="已撤销" value="OVERTURNED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="reloadAppeals()">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="appeals" v-loading="appealsLoading" border>
            <el-table-column label="申诉人类型" width="100">
              <template #default="{ row }">{{ row.appellantType === 'USER' ? '个人' : '机构' }}</template>
            </el-table-column>
            <el-table-column prop="penaltyId" label="处罚" width="240" show-overflow-tooltip />
            <el-table-column prop="reason" label="申诉理由" min-width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="appealStatusTag(row.status)">{{ appealStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="申诉时间" width="170">
              <template #default="{ row }">{{ format(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button size="small" type="danger" @click="onDecideAppeal(row, false)">撤销处罚</el-button>
                  <el-button size="small" @click="onDecideAppeal(row, true)">维持</el-button>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 处置对话框 -->
    <el-dialog v-model="decisionVisible" title="处置举报" width="520px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="举报对象">
          <span>{{ currentReport?.targetSummary }}</span>
        </el-form-item>
        <el-form-item label="举报理由">
          <span>{{ currentReport?.reason }}</span>
        </el-form-item>
        <el-form-item label="处置">
          <el-radio-group v-model="decision.dismiss">
            <el-radio :value="true">驳回举报</el-radio>
            <el-radio :value="false">施加处罚</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!decision.dismiss" label="预设处罚">
          <el-select
            v-model="decision.presetId"
            :placeholder="applicablePresets.length ? '选择预设处罚' : '该对象暂无可用预设'"
            style="width: 100%"
          >
            <el-option
              v-for="preset in applicablePresets"
              :key="preset.id"
              :label="preset.name"
              :value="preset.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="处置意见">
          <el-input v-model="decision.note" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decisionVisible = false">取消</el-button>
        <el-button type="primary" :loading="deciding" @click="submitDecision">提交</el-button>
      </template>
    </el-dialog>

    <!-- 目标档案抽屉 -->
    <el-drawer v-model="historyVisible" title="目标档案（历史被举报 + 违规记录）" size="560px">
      <template v-if="history">
        <h4>被举报记录（{{ history.reports.length }}）</h4>
        <el-table :data="history.reports" size="small" border>
          <el-table-column label="类别" width="90">
            <template #default="{ row }">{{ categoryText(row.category) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="理由" min-width="120" show-overflow-tooltip />
          <el-table-column prop="reporterName" label="举报人" width="100" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">{{ reportStatusText(row.status) }}</template>
          </el-table-column>
        </el-table>
        <h4 style="margin-top: 16px">违规（处罚）记录（{{ history.penalties.length }}）</h4>
        <el-table :data="history.penalties" size="small" border>
          <el-table-column prop="presetName" label="处罚" min-width="120" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'danger' : 'info'">
                {{ row.status === 'ACTIVE' ? '生效中' : row.status === 'LIFTED' ? '已撤销' : '已到期' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ format(row.appliedAt) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 申请新预设对话框 -->
    <el-dialog v-model="presetDialogVisible" title="申请新增预设处罚" width="640px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="申请后需由另一名管理员批准才会生效（不能自批）。"
        style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="处罚名">
          <el-input v-model="presetForm.name" maxlength="100" placeholder="如：下架并封禁机构3天" />
        </el-form-item>
        <el-form-item label="作用对象">
          <el-select v-model="presetForm.category" style="width: 100%" @change="onCategoryChange">
            <el-option label="活动" value="ACTIVITY" />
            <el-option label="机构" value="ORGANIZATION" />
            <el-option label="活动群聊" value="GROUP" />
            <el-option label="个人" value="USER" />
          </el-select>
        </el-form-item>
        <el-form-item label="主惩罚">
          <el-select v-model="presetForm.action" style="width: 100%">
            <el-option v-for="option in actionOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <!-- 主处罚时长：暂时类必填；其余留空 = 永久 -->
        <el-form-item v-if="actionNeedsDays || actionAllowsDays" label="处罚天数">
          <el-input-number v-model="presetForm.durationDays" :min="1" :max="365" />
          <span style="margin-left: 8px; color: var(--el-text-color-secondary)">
            {{ actionNeedsDays ? '必填（处罚生效天数，到期自动恢复）' : '留空 = 永久' }}
          </span>
        </el-form-item>
        <!-- 附带惩罚：作用于另一对象，取该对象的基础惩罚；可复合共存；时长留空=跟随主处罚 -->
        <el-form-item v-if="presetForm.category !== 'USER'" label="附带惩罚">
          <div style="width: 100%">
            <div
              v-for="(part, index) in presetForm.parts"
              :key="index"
              style="display: flex; gap: 8px; margin-bottom: 8px; align-items: center"
            >
              <el-select v-model="part.targetKind" style="width: 110px" @change="part.action = ''">
                <el-option
                  v-for="kind in partKindOptions"
                  :key="kind.value"
                  :label="kind.label"
                  :value="kind.value"
                />
              </el-select>
              <el-select v-model="part.action" style="width: 200px" placeholder="基础惩罚">
                <el-option
                  v-for="option in partActionOptions(part.targetKind)"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <el-input-number v-model="part.durationDays" :min="1" :max="365" placeholder="天数" />
              <el-button type="danger" text @click="presetForm.parts.splice(index, 1)">删除</el-button>
            </div>
            <el-button
              v-if="presetForm.parts.length < 2"
              size="small"
              @click="presetForm.parts.push({ targetKind: partKindOptions[0]!.value, action: '', durationDays: undefined })"
            >
              添加附带惩罚
            </el-button>
            <div style="color: var(--el-text-color-secondary); font-size: 12px">
              作用于另一对象；天数留空 = 跟随主处罚时长（主处罚永久则同为永久）
            </div>
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="presetForm.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="presetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingPreset" @click="submitPreset">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listAbuseReportsGrouped,
  getTargetHistory,
  decideReport,
  listPresets,
  createPreset,
  approvePreset,
  listAppeals,
  decideAppeal,
  type AdminAbuseReport,
  type MergedReportGroup,
  type PresetPenalty,
  type AbuseAppeal,
  type TargetHistory,
} from '@/api/abuse'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const tab = ref('reports')

// ---------- 举报处置（按对象合并：同一对象一条，展开看明细） ----------
const reportGroups = ref<MergedReportGroup[]>([])
const reportsLoading = ref(false)
const reportStatus = ref<string>()
const reportCategory = ref<string>()

async function reloadReports() {
  reportsLoading.value = true
  try {
    reportGroups.value = await listAbuseReportsGrouped({
      status: reportStatus.value,
      category: reportCategory.value,
    })
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    reportsLoading.value = false
  }
}

function firstPendingReport(group: MergedReportGroup) {
  return group.reports.find((item) => item.status === 'PENDING')
}

// ---------- 处置 ----------
const decisionVisible = ref(false)
const deciding = ref(false)
const currentReport = ref<AdminAbuseReport>()
const decision = ref<{ dismiss: boolean; presetId?: string; note?: string }>({ dismiss: true })

const presets = ref<PresetPenalty[]>([])
const presetsLoading = ref(false)
const presetStatus = ref<string>()
const activePresets = computed(() => presets.value.filter((preset) => preset.status === 'ACTIVE'))

/** 处置对话框的目标对象种类：按举报携带的目标 id 推断（对个人的举报 REPORT_ABUSE 只有 targetUserId）。 */
const decisionTargetKind = computed(() => {
  const report = currentReport.value
  if (!report) return undefined
  if (report.targetActivityId) return 'ACTIVITY'
  if (report.targetOrgId) return 'ORGANIZATION'
  if (report.targetGroupId) return 'GROUP'
  return 'USER'
})

/** 只显示主惩罚作用于该对象种类基础惩罚集的预设（与后端 BASE_ACTIONS 同源）。 */
const applicablePresets = computed(() => {
  const kind = decisionTargetKind.value
  const baseActions = kind ? (ACTION_MATRIX[kind] ?? []).map((option) => option.value) : []
  return activePresets.value.filter((preset) => baseActions.includes(preset.action))
})

async function reloadPresets() {
  presetsLoading.value = true
  try {
    presets.value = await listPresets(presetStatus.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    presetsLoading.value = false
  }
}

function openDecision(report: AdminAbuseReport) {
  currentReport.value = report
  decision.value = { dismiss: true }
  decisionVisible.value = true
}

async function submitDecision() {
  if (!currentReport.value) return
  if (!decision.value.dismiss && !decision.value.presetId) {
    ElMessage.warning('请选择一项预设处罚')
    return
  }
  deciding.value = true
  try {
    await decideReport(currentReport.value.id, decision.value)
    ElMessage.success('已处置')
    decisionVisible.value = false
    reloadReports()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    deciding.value = false
  }
}

// ---------- 档案 ----------
const historyVisible = ref(false)
const history = ref<TargetHistory>()

async function openHistory(report: AdminAbuseReport) {
  const category =
    report.category === 'REPORT_ABUSE'
      ? 'USER'
      : (report.category as string)
  const targetId =
    report.targetActivityId ?? report.targetOrgId ?? report.targetGroupId ?? report.targetUserId
  if (!targetId) return
  try {
    history.value = await getTargetHistory(category, targetId)
    historyVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

// ---------- 预设（组合式：主基础惩罚 + 跨对象附带组件） ----------
const presetDialogVisible = ref(false)
const creatingPreset = ref(false)
const presetForm = ref<{
  name: string
  category: string
  action: string
  durationDays?: number | null
  parts: { targetKind: string; action: string; durationDays?: number | null }[]
  description?: string
}>({ name: '', category: 'ACTIVITY', action: 'TEMP_DELIST', durationDays: undefined, parts: [], description: '' })

/** 每种对象自己的基础惩罚集；与后端 BASE_ACTIONS 同源（needsDays 蕴含 allowsDays）。 */
const ACTION_MATRIX: Record<string, { value: string; label: string; needsDays?: boolean; allowsDays?: boolean }[]> = {
  ACTIVITY: [
    { label: '暂时下架（群聊暂时封禁，到期自动恢复）', value: 'TEMP_DELIST', needsDays: true, allowsDays: true },
    { label: '下架活动（永久，群聊解散）', value: 'DELIST' },
  ],
  ORGANIZATION: [
    { label: '封禁机构（其活动/群聊至少暂时下架封禁同时长；到期自动恢复）', value: 'BAN_ORG', allowsDays: true },
    { label: '禁止发布活动（不影响既有活动与群聊；到期自动恢复）', value: 'BAN_PUBLISH', allowsDays: true },
  ],
  GROUP: [
    { label: '暂时封禁群聊（到期自动恢复）', value: 'BAN_GROUP', needsDays: true, allowsDays: true },
    { label: '永久封禁群聊（解散；可附带下架活动/处罚机构）', value: 'PERM_CLOSE' },
  ],
  USER: [
    { label: '封禁用户（连带禁用账户+踢出未结束活动）', value: 'BAN_USER', allowsDays: true },
  ],
}

/** 附带惩罚可作用的对象（活动/机构/群聊互相附带；不含个人）。 */
const PART_KINDS: { value: string; label: string }[] = [
  { value: 'ACTIVITY', label: '活动' },
  { value: 'ORGANIZATION', label: '机构' },
  { value: 'GROUP', label: '群聊' },
]
const partKindOptions = computed(() =>
  PART_KINDS.filter((kind) => kind.value !== presetForm.value.category))
const partActionOptions = (kind: string) => ACTION_MATRIX[kind] ?? []

const actionOptions = computed(() => ACTION_MATRIX[presetForm.value.category] ?? [])

const currentAction = computed(() =>
  actionOptions.value.find((option) => option.value === presetForm.value.action))

const actionNeedsDays = computed(() => currentAction.value?.needsDays === true)
// 需要天数必然允许携带天数（派生层兜底，矩阵漏标也不会把必填天数清掉）。
const actionAllowsDays = computed(() =>
  currentAction.value != null && (currentAction.value.allowsDays === true || currentAction.value.needsDays === true))

function onCategoryChange() {
  presetForm.value.action = actionOptions.value[0]?.value ?? ''
  presetForm.value.durationDays = undefined
  presetForm.value.parts = []
}

async function submitPreset() {
  if (!presetForm.value.name.trim()) {
    ElMessage.warning('请填写处罚名')
    return
  }
  if (actionNeedsDays.value && !presetForm.value.durationDays) {
    ElMessage.warning('该基础处罚必须填写处罚天数')
    return
  }
  // 附带组件：跨对象、须选基础惩罚；同一对象不重复（后端同样校验）。
  const parts = presetForm.value.parts
    .filter((part) => part.targetKind && part.action)
    .map((part) => ({ targetKind: part.targetKind, action: part.action, durationDays: part.durationDays }))
  if (parts.length !== presetForm.value.parts.length) {
    ElMessage.warning('附带惩罚须选择对象和基础惩罚')
    return
  }
  creatingPreset.value = true
  try {
    // 纯永久动作不携带主处罚天数；附带时长留空 = 跟随主处罚时长。
    const days = actionAllowsDays.value ? presetForm.value.durationDays : undefined
    await createPreset({
      ...presetForm.value,
      name: presetForm.value.name.trim(),
      durationDays: days,
      parts,
    })
    ElMessage.success('申请已提交，等待其他管理员批准')
    presetDialogVisible.value = false
    reloadPresets()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    creatingPreset.value = false
  }
}

async function approve(presetId: string, ok: boolean) {
  try {
    await ElMessageBox.confirm(ok ? '批准该预设处罚生效？' : '驳回该预设处罚申请？', '审批预设处罚')
  } catch {
    return
  }
  try {
    await approvePreset(presetId, ok)
    ElMessage.success(ok ? '已批准' : '已驳回')
    reloadPresets()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

// ---------- 申诉 ----------
const appeals = ref<AbuseAppeal[]>([])
const appealsLoading = ref(false)
const appealStatus = ref<string>()

async function reloadAppeals() {
  appealsLoading.value = true
  try {
    appeals.value = await listAppeals(appealStatus.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    appealsLoading.value = false
  }
}

async function onDecideAppeal(appeal: AbuseAppeal, uphold: boolean) {
  try {
    await ElMessageBox.confirm(
      uphold ? '维持该处罚？' : '撤销该处罚？撤销后相关限制同步解除。',
      '处理申诉',
    )
  } catch {
    return
  }
  try {
    await decideAppeal(appeal.id, uphold)
    ElMessage.success(uphold ? '已维持处罚' : '已撤销处罚')
    reloadAppeals()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

// ---------- 展示辅助 ----------
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
function categoryTag(category: string) {
  return category === 'REPORT_ABUSE' ? 'warning' : 'info'
}
function reportStatusText(status: string) {
  const map: Record<string, string> = { PENDING: '待处理', RESOLVED: '已处置', DISMISSED: '已驳回' }
  return map[status] ?? status
}
function reportStatusTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', RESOLVED: 'danger', DISMISSED: 'info' }
  return map[status] ?? 'info'
}
function presetStatusText(status: string) {
  const map: Record<string, string> = { PENDING: '待审批', ACTIVE: '生效中', REJECTED: '已驳回' }
  return map[status] ?? status
}
function presetStatusTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', ACTIVE: 'success', REJECTED: 'info' }
  return map[status] ?? 'info'
}
function appealStatusText(status: string) {
  const map: Record<string, string> = { PENDING: '待处理', UPHELD: '已维持', OVERTURNED: '已撤销' }
  return map[status] ?? status
}
function appealStatusTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', UPHELD: 'danger', OVERTURNED: 'success' }
  return map[status] ?? 'info'
}
function actionText(action: string) {
  const map: Record<string, string> = {
    DELIST: '下架活动（永久）',
    TEMP_DELIST: '暂时下架',
    PERM_CLOSE: '永久封禁群聊',
    BAN_GROUP: '暂时封禁群聊',
    BAN_ORG: '封禁机构',
    BAN_PUBLISH: '禁止发布活动',
    BAN_USER: '封禁用户',
    // V8 之前的存量动作名（旧处罚记录展示用）。
    DELIST_AND_BAN_ORG: '下架并封禁机构',
    DELIST_AND_BAN_PUBLISH: '下架并禁止发布',
    PERM_CLOSE_GROUP_DELIST_ACTIVITY: '永久封禁群聊并下架活动',
    PERM_CLOSE_GROUP_BAN_ORG: '永久封禁群聊并附带机构处罚',
  }
  return map[action] ?? action
}

onMounted(() => {
  reloadReports()
  reloadPresets()
  reloadAppeals()
})
</script>
