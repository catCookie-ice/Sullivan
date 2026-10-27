<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>志愿活动</span>
          <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </template>

      <!-- 报名已上线：直接通过的活动报名即入群；需审核的活动等机构批准。
           提示保留一句，说明「待审核 ≠ 报名成功」。 -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="报名成功（机构审核通过）后可进入活动群聊；名额只统计报名成功的人数。"
        style="margin-bottom: 16px"
      />

      <el-form :inline="true" class="filter-bar">
        <el-form-item label="状态">
          <el-select v-model="status" placeholder="全部" clearable style="width: 140px" @change="reload">
            <el-option label="全部" value="" />
            <el-option label="招募中" value="RECRUITING" />
            <el-option label="进行中" value="IN_PROGRESS" />
            <el-option label="已结束" value="ENDED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-skeleton v-if="loading && !rows.length" :rows="5" animated />
      <el-empty v-else-if="!rows.length" description="暂时没有符合条件的活动" />

      <!-- 卡片列表而非表格：志愿者在手机上刷活动，「一屏几条 + 关键信息」比表格可读得多。 -->
      <div v-else class="activity-grid">
        <article v-for="item in rows" :key="item.id" class="activity-card">
          <header class="activity-head">
            <el-tag :type="displayStatus(item).tag" size="small">{{ displayStatus(item).text }}</el-tag>
            <span v-if="registrationPhase(item) === 'open'" class="deadline">报名截止 {{ formatDateTime(item.registrationEndsAt) }}</span>
            <span v-else-if="registrationPhase(item) === 'upcoming'" class="deadline is-upcoming">
              {{ formatDateTime(item.registrationStartsAt) }} 开启报名
            </span>
            <span v-else class="deadline is-closed">报名已结束</span>
          </header>

          <h3 class="activity-title">{{ item.title }}</h3>
          <p class="activity-org">{{ item.organizationPublicName || '公益机构' }}</p>
          <p class="activity-desc">{{ item.description }}</p>

          <dl class="activity-meta">
            <div>
              <dt>时间</dt>
              <dd>{{ formatDateTime(item.startsAt) }} ~ {{ formatDateTime(item.endsAt) }}</dd>
            </div>
            <div>
              <dt>地点</dt>
              <dd>{{ item.locationText }}</dd>
            </div>
            <div>
              <dt>名额</dt>
              <dd>
                已报名 {{ item.approvedCount }} / {{ item.maxVolunteers }}
                <span v-if="item.approvedCount >= item.maxVolunteers" class="is-full">（已满）</span>
              </dd>
            </div>
          </dl>

          <footer class="activity-foot">
            <a
              v-if="item.locationLng != null && item.locationLat != null"
              :href="mapLink(item)"
              target="_blank"
              rel="noopener noreferrer"
              class="map-link"
            >在地图中查看地点</a>
            <div class="enroll-area">
              <el-tag v-if="item.reviewRequired" size="small" type="warning">需审核</el-tag>
              <el-tag v-if="myStatus(item) === 'PENDING'" size="small" type="warning">待机构审核</el-tag>
              <el-tag v-else-if="myStatus(item) === 'APPROVED'" size="small" type="success">已报名成功 · 可去群聊</el-tag>
              <el-tag v-else-if="myStatus(item) === 'REJECTED'" size="small" type="danger">已拒绝</el-tag>
              <el-button
                v-if="canEnroll(item)"
                size="small" type="primary"
                :loading="enrolling === item.id"
                @click="enroll(item)"
              >立即报名</el-button>
              <el-button
                v-else-if="myStatus(item) === 'APPROVED'"
                size="small"
                @click="$router.push('/volunteer/group-chat')"
              >进入群聊</el-button>
              <el-button size="small" text type="danger" @click="openReport(item)">举报</el-button>
            </div>
          </footer>
        </article>
      </div>

      <!-- 举报活动 -->
      <el-dialog v-model="reportVisible" title="举报活动" width="440px">
        <el-form label-width="70px">
          <el-form-item label="活动">
            <span>{{ reportTarget?.title }}</span>
          </el-form-item>
          <el-form-item label="理由">
            <el-input
              v-model="reportReason"
              type="textarea"
              :rows="3"
              maxlength="500"
              placeholder="请说明违规情况（500 字以内）"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reportVisible = false">取消</el-button>
          <el-button type="danger" :loading="reporting" @click="submitReport">提交举报</el-button>
        </template>
      </el-dialog>

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
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { enrollActivity, listMyEnrollments, listPublicActivities } from '@/api/activity'
import { createAbuseReport } from '@/api/abuse'
import type { ActivityResponse, EnrollmentResponse } from '@/types/api'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const rows = ref<ActivityResponse[]>([])
const status = ref('')
const page = ref(1)
const size = ref(12)
const total = ref(0)
const loading = ref(false)
const enrolling = ref('')
/** 我在已加载活动上的有效报名（PENDING/APPROVED 优先展示；REJECTED/CANCELLED 可再报名）。 */
const myEnrollments = ref<EnrollmentResponse[]>([])

// ---- 举报活动（治理入口：提交给举报治理管理员，匿名处理） ----
const reportVisible = ref(false)
const reporting = ref(false)
const reportTarget = ref<ActivityResponse>()
const reportReason = ref('')

function openReport(item: ActivityResponse) {
  reportTarget.value = item
  reportReason.value = ''
  reportVisible.value = true
}

async function submitReport() {
  if (!reportTarget.value) return
  if (!reportReason.value.trim()) {
    ElMessage.warning('请填写举报理由')
    return
  }
  reporting.value = true
  try {
    await createAbuseReport({
      category: 'ACTIVITY',
      targetId: reportTarget.value.id,
      reason: reportReason.value.trim(),
    })
    ElMessage.success('举报已提交，感谢你的反馈')
    reportVisible.value = false
  } catch (e) {
    ElMessage.error(errorMessage(e, '举报失败'))
  } finally {
    reporting.value = false
  }
}

const enrollmentByActivity = computed(() => {
  const map = new Map<string, EnrollmentResponse>()
  for (const enrollment of myEnrollments.value) {
    const existing = map.get(enrollment.activityId)
    // 一行 PENDING/APPROVED 比已拒绝/已撤回更值得展示。
    if (!existing || (existing.status === 'PENDING' || existing.status === 'APPROVED'
      ? false
      : enrollment.status === 'PENDING' || enrollment.status === 'APPROVED')) {
      map.set(enrollment.activityId, enrollment)
    }
  }
  return map
})

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const [res, mine] = await Promise.all([
      listPublicActivities({
        status: status.value || undefined,
        page: page.value,
        size: size.value,
      }),
      listMyEnrollments({ page: 1, size: 100 }),
    ])
    rows.value = res.records
    total.value = res.total
    myEnrollments.value = mine.records
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载活动失败'))
  } finally {
    loading.value = false
  }
}

function myStatus(item: ActivityResponse): string | null {
  return enrollmentByActivity.value.get(item.id)?.status ?? null
}

/** 可报名 = 活动招募中、窗口开着、没满员、没有待审/已通过的报名。 */
function canEnroll(item: ActivityResponse): boolean {
  if (!isRegistrationOpen(item) || item.approvedCount >= item.maxVolunteers) return false
  const current = myStatus(item)
  return current !== 'PENDING' && current !== 'APPROVED'
}

async function enroll(item: ActivityResponse) {
  const confirmText = item.reviewRequired
    ? `报名「${item.title}」？该活动需机构审核，审核通过才算报名成功。`
    : `报名「${item.title}」？报名成功后将自动加入活动群聊。`
  if (!window.confirm(confirmText)) return
  enrolling.value = item.id
  try {
    const result = await enrollActivity(item.id)
    ElMessage.success(result.status === 'APPROVED' ? '报名成功，已加入活动群聊' : '已提交报名，等待机构审核')
    await reload()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '报名失败'))
  } finally {
    enrolling.value = ''
  }
}

function onPageChange(p: number) {
  page.value = p
  reload()
}

/** 报名窗口三态：未开始 / 开放中 / 已截止（未开始时展示态叫「待开启」）。 */
function registrationPhase(item: ActivityResponse): 'upcoming' | 'open' | 'closed' {
  if (item.status === 'CANCELLED' || item.status === 'ENDED') return 'closed'
  const now = Date.now()
  const start = parseTime(item.registrationStartsAt)
  const end = parseTime(item.registrationEndsAt)
  if (start != null && now < start) return 'upcoming'
  if (end != null && now > end) return 'closed'
  return 'open'
}

/** 报名窗口是否还开着（且活动本身还能报名）。 */
function isRegistrationOpen(item: ActivityResponse): boolean {
  return registrationPhase(item) === 'open'
}

/**
 * 展示态：「待开启」不在数据库状态机里（库里仍是 RECRUITING），是纯展示推导——
 * 报名还没开始的活动对志愿者来说不是「招募中」，标成招募中会误导人去点报名。
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
    SUSPENDED: { text: '已下架', tag: 'danger' },
  }
  return map[item.status] ?? { text: item.status, tag: 'info' }
}

function parseTime(value: string): number | null {
  if (!value) return null
  const m = String(value).replace('T', ' ').match(/^(\d{4})-(\d{2})-(\d{2})(?:[ ](\d{2}):(\d{2})(?::(\d{2}))?)?/)
  if (!m) return null
  const [, y, mo, d, h, mi, s] = m
  return new Date(Number(y), Number(mo) - 1, Number(d), Number(h || 0), Number(mi || 0), Number(s || 0)).getTime()
}

function mapLink(item: ActivityResponse): string {
  return `https://uri.amap.com/marker?position=${item.locationLng},${item.locationLat}&name=${encodeURIComponent(item.title)}&src=sullivan&coordinate=gaode&callnative=0`
}
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filter-bar :deep(.el-form-item) {
  margin-bottom: 8px;
}
.activity-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}
.activity-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line-strong);
  border-radius: 12px 5px 14px 6px / 6px 13px 5px 12px;
  background: var(--paper-card);
  padding: 14px;
  box-shadow: 2px 2px 0 -1px var(--paper-card), 2px 2px 0 0 var(--line);
}
.activity-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.deadline {
  font-size: 12px;
  color: var(--moss);
}
.deadline.is-closed {
  color: var(--ink-4);
}
.deadline.is-upcoming {
  color: var(--ink-2);
}
.activity-title {
  font-family: var(--font-hand);
  font-size: 17px;
  font-weight: 400;
  color: var(--ink);
  margin-bottom: 4px;
  line-height: 1.35;
}
.activity-org {
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 8px;
}
.activity-desc {
  font-size: 13px;
  color: var(--ink-2);
  line-height: 1.6;
  margin-bottom: 10px;
  /* 长说明最多 4 行，避免一张卡片把整屏占满。 */
  display: -webkit-box;
  -webkit-line-clamp: 4;
  line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.activity-meta {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.activity-meta > div {
  display: flex;
  gap: 8px;
}
.activity-meta dt {
  flex: 0 0 34px;
  color: var(--ink-3);
}
.activity-meta dd {
  color: var(--ink-2);
  word-break: break-all;
}
.is-full {
  color: var(--terra);
}
.activity-foot {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}
.enroll-area {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.map-link {
  font-size: 12px;
  color: var(--mist-dark);
  text-decoration: underline;
}
</style>
