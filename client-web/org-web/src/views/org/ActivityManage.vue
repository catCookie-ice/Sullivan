<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>志愿活动管理</span>
          <el-button type="primary" @click="$router.push('/activities/create')">新建活动</el-button>
        </div>
      </template>

      <el-form :inline="true">
        <el-form-item label="状态">
          <el-select v-model="status" clearable placeholder="全部" style="width: 140px" @change="reload">
            <el-option label="全部" value="" />
            <el-option label="招募中" value="RECRUITING" />
            <el-option label="进行中" value="IN_PROGRESS" />
            <el-option label="已结束" value="ENDED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">刷新</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading" border empty-text="还没有发布过活动">
        <el-table-column prop="title" label="活动名" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="displayStatus(row).tag">{{ displayStatus(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="locationText" label="地点" min-width="140" show-overflow-tooltip />
        <el-table-column label="活动时间" width="200">
          <template #default="{ row }">{{ format(row.startsAt) }} ~ {{ format(row.endsAt) }}</template>
        </el-table-column>
        <el-table-column label="报名窗口" width="200">
          <template #default="{ row }">{{ format(row.registrationStartsAt) }} ~ {{ format(row.registrationEndsAt) }}</template>
        </el-table-column>
        <el-table-column prop="maxVolunteers" label="需要人数" width="90" />
        <el-table-column label="已报名" width="110">
          <template #default="{ row }">
            <span :class="{ 'is-full': row.approvedCount >= row.maxVolunteers }">
              {{ row.approvedCount }} / {{ row.maxVolunteers }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openRoster(row)">报名名单</el-button>
            <el-button size="small" @click="openDetail(row)">详情</el-button>
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

    <!-- 详情：列表列宽有限，活动说明与经纬度只能在这里完整呈现。 -->
    <el-dialog v-model="detailVisible" title="活动详情" width="560px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="活动名">{{ detail.title }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="displayStatus(detail).tag">{{ displayStatus(detail).text }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="志愿者要求">
          <span class="pre-wrap">{{ detail.description }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="活动地点">{{ detail.locationText }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.locationLng != null && detail.locationLat != null" label="坐标">
          {{ detail.locationLng.toFixed(6) }}, {{ detail.locationLat.toFixed(6) }}
          <a :href="mapLink(detail)" target="_blank" rel="noopener noreferrer" class="map-link">
            在地图中查看
          </a>
        </el-descriptions-item>
        <el-descriptions-item label="活动时间">
          {{ format(detail.startsAt) }} ~ {{ format(detail.endsAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="报名窗口">
          {{ format(detail.registrationStartsAt) }} ~ {{ format(detail.registrationEndsAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="志愿者名额">
          {{ detail.approvedCount }} / {{ detail.maxVolunteers }}
        </el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ format(detail.createdAt) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 报名名单：查看该活动全部报名与成功报名的志愿者；审核动作在「报名审核」页做。 -->
    <el-dialog v-model="rosterVisible" :title="`报名名单 · ${rosterActivity?.title ?? ''}`" width="720px">
      <div class="roster-summary">
        <span>成功报名 <b>{{ rosterSummary?.approved ?? 0 }}</b> / {{ rosterActivity?.maxVolunteers ?? '—' }}（名额）</span>
        <el-divider direction="vertical" />
        <span>总报名 <b>{{ rosterSummary?.total ?? 0 }}</b></span>
        <el-divider direction="vertical" />
        <span>待审核 <b>{{ rosterSummary?.pending ?? 0 }}</b></span>
      </div>

      <el-tabs v-model="rosterTab" @tab-change="loadRoster">
        <el-tab-pane label="成功报名" name="approved" />
        <el-tab-pane label="全部报名" name="all" />
      </el-tabs>

      <el-table :data="rosterRows" v-loading="rosterLoading" max-height="380" empty-text="暂无报名记录">
        <el-table-column label="志愿者" min-width="160">
          <template #default="{ row }">
            <span class="volunteer-name">{{ row.volunteerDisplayName || row.volunteerLoginName || row.volunteerUid }}</span>
            <span v-if="row.volunteerDisplayName" class="volunteer-login">{{ row.volunteerLoginName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="enrollTag(row.status)" size="small">{{ enrollText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="报名时间" width="170">
          <template #default="{ row }">{{ format(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="审核信息" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.decidedAt">
              {{ format(row.decidedAt) }}<span v-if="row.decisionReason"> · {{ row.decisionReason }}</span>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="rosterVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listActivityEnrollments, listMyActivities } from '@/api/org'
import type { ActivityResponse, EnrollmentResponse, EnrollmentSummary } from '@/types/api'
import { formatDateTime } from '@/utils/display'

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
    const res = await listMyActivities({ status: status.value || undefined, page: page.value, size: size.value })
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
  // 服务端下发的是 `yyyy-MM-dd HH:mm:ss`，不能交给 new Date()（部分浏览器按 UTC 解析会偏 8 小时）。
  return formatDateTime(iso)
}

/** 报名窗口三态：未开始 / 开放中 / 已截止。 */
function registrationPhase(item: ActivityResponse): 'upcoming' | 'open' | 'closed' {
  if (item.status === 'CANCELLED' || item.status === 'ENDED') return 'closed'
  const now = Date.now()
  const start = parseLocalTime(item.registrationStartsAt)
  const end = parseLocalTime(item.registrationEndsAt)
  if (start != null && now < start) return 'upcoming'
  if (end != null && now > end) return 'closed'
  return 'open'
}

/**
 * 展示态：报名还没开始的活动显示「待开启」，不显示「招募中」。
 * 「待开启」不在数据库状态机里（库里仍是 RECRUITING），纯展示推导，
 * 与 user-web 活动列表的 displayStatus 同口径。
 */
function displayStatus(item: ActivityResponse): { text: string; tag: 'success' | 'warning' | 'info' | 'danger' } {
  if (item.status === 'RECRUITING' && registrationPhase(item) === 'upcoming') {
    return { text: '待开启', tag: 'info' }
  }
  const map: Record<string, { text: string; tag: 'success' | 'warning' | 'info' | 'danger' }> = {
    RECRUITING: { text: '招募中', tag: 'success' },
    IN_PROGRESS: { text: '进行中', tag: 'warning' },
    ENDED: { text: '已结束', tag: 'info' },
    CANCELLED: { text: '已取消', tag: 'danger' },
    SUSPENDED: { text: '已下架（处罚中）', tag: 'danger' },
  }
  return map[item.status] ?? { text: item.status, tag: 'info' }
}

/** `yyyy-MM-dd HH:mm:ss` → 本地时间毫秒（直接 new Date() 部分浏览器按 UTC 解析）。 */
function parseLocalTime(value: string): number | null {
  if (!value) return null
  const normalized = value.replace(' ', 'T')
  const time = new Date(normalized).getTime()
  return Number.isNaN(time) ? null : time
}

// —— 详情弹窗 ——
const detailVisible = ref(false)
const detail = ref<ActivityResponse | null>(null)

function openDetail(row: ActivityResponse) {
  detail.value = row
  detailVisible.value = true
}

/** 高德 URI 打点；参数顺序是「经度,纬度」，与后端存库顺序相反，别搞混。 */
function mapLink(row: ActivityResponse): string {
  return `https://uri.amap.com/marker?position=${row.locationLng},${row.locationLat}&name=${encodeURIComponent(row.title)}&src=sullivan&coordinate=gaode&callnative=0`
}

// —— 报名名单弹窗 ——
// 「成功报名」= APPROVED（占名额、已入群）；「全部报名」= 含待审/已拒/已撤，机构评估热度用。
const rosterVisible = ref(false)
const rosterActivity = ref<ActivityResponse | null>(null)
const rosterTab = ref<'approved' | 'all'>('approved')
const rosterRows = ref<EnrollmentResponse[]>([])
const rosterSummary = ref<EnrollmentSummary | null>(null)
const rosterLoading = ref(false)

function openRoster(row: ActivityResponse) {
  rosterActivity.value = row
  rosterTab.value = 'approved'
  rosterVisible.value = true
  loadRoster()
}

async function loadRoster() {
  if (!rosterActivity.value) return
  rosterLoading.value = true
  try {
    const result = await listActivityEnrollments(rosterActivity.value.id, {
      status: rosterTab.value === 'approved' ? 'APPROVED' : undefined,
      page: 1,
      size: 200,
    })
    rosterRows.value = result.records
    // 统计数字始终取全量口径，切页签不改变顶部汇总。
    rosterSummary.value = result.summary
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载报名名单失败'))
  } finally {
    rosterLoading.value = false
  }
}

function enrollText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', CANCELLED: '已撤回',
  }
  return map[status] ?? status
}

function enrollTag(status: string) {
  const map: Record<string, string> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', CANCELLED: 'info' }
  return (map[status] ?? 'info') as 'warning' | 'success' | 'danger' | 'info'
}
</script>

<style scoped>
/* 活动说明是多行文本，详情里要保留换行，否则一段一段黏成一行。 */
.pre-wrap {
  white-space: pre-wrap;
  word-break: break-word;
}
.is-full {
  color: var(--terra);
}
.map-link {
  margin-left: 8px;
  font-size: 12px;
  color: var(--mist-dark);
  text-decoration: underline;
}
.roster-summary {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--ink-2);
}
.roster-summary b {
  color: var(--ink-1);
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
</style>
