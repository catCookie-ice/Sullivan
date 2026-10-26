<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>运营统计</span>
          <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </template>

      <!-- 数据口径必须写在页面上：这些数字是「此刻按条件查到的总数」，
           不是历史累计报表，也不含被删除的账号。 -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="统计口径：当前有效账号 / 机构的实时总数（已逻辑删除的不计入）。数据来自各管理列表接口的 total。"
        style="margin-bottom: 16px"
      />

      <el-skeleton v-if="loading && !loaded" :rows="6" animated />

      <template v-else>
        <!-- 用户概况 -->
        <section class="stat-section">
          <h3 class="section-title">用户</h3>
          <el-alert
            v-if="!canUserManage"
            type="warning"
            :closable="false"
            show-icon
            title="当前账号缺少 USER_MANAGE 权限，无法读取用户统计。"
          />
          <template v-else>
            <div class="stat-cards">
              <div class="stat-card">
                <span class="stat-label">监护人</span>
                <strong class="stat-value">{{ counts.GUARDIAN }}</strong>
                <span class="stat-unit">人</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">受护人</span>
                <strong class="stat-value">{{ counts.PROTECTEE }}</strong>
                <span class="stat-unit">人</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">志愿者</span>
                <strong class="stat-value">{{ counts.VOLUNTEER }}</strong>
                <span class="stat-unit">人</span>
              </div>
            </div>

            <!-- 手绘风条形图：宽度按最大值归一，不引图表库，视觉上与墨线主题一致。 -->
            <div class="bar-chart" role="img" :aria-label="userBarSummary">
              <div v-for="bar in userBars" :key="bar.label" class="bar-row">
                <span class="bar-label">{{ bar.label }}</span>
                <div class="bar-track">
                  <div class="bar-fill" :class="bar.cls" :style="{ width: bar.percent }" />
                </div>
                <span class="bar-value">{{ bar.value }}</span>
              </div>
            </div>
            <p class="note">
              注：监护人兼任志愿者时同时计入「监护人」与「志愿者」，因此三项之和可能大于账号总数。
            </p>
          </template>
        </section>

        <!-- 机构概况 -->
        <section class="stat-section">
          <h3 class="section-title">公益机构</h3>
          <el-alert
            v-if="!canOrgManage"
            type="warning"
            :closable="false"
            show-icon
            title="当前账号缺少 ORG_MANAGE 权限，无法读取机构统计。"
          />
          <template v-else>
            <div class="stat-cards">
              <div class="stat-card">
                <span class="stat-label">待审核</span>
                <strong class="stat-value is-warn">{{ orgCounts.PENDING }}</strong>
                <span class="stat-unit">家</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">已通过</span>
                <strong class="stat-value is-ok">{{ orgCounts.APPROVED }}</strong>
                <span class="stat-unit">家</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">已驳回</span>
                <strong class="stat-value">{{ orgCounts.REJECTED }}</strong>
                <span class="stat-unit">家</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">已禁用</span>
                <strong class="stat-value">{{ orgCounts.DISABLED }}</strong>
                <span class="stat-unit">家</span>
              </div>
            </div>

            <div class="bar-chart" role="img" :aria-label="orgBarSummary">
              <div v-for="bar in orgBars" :key="bar.label" class="bar-row">
                <span class="bar-label">{{ bar.label }}</span>
                <div class="bar-track">
                  <div class="bar-fill" :class="bar.cls" :style="{ width: bar.percent }" />
                </div>
                <span class="bar-value">{{ bar.value }}</span>
              </div>
            </div>

            <!-- 待审核是唯一需要管理员动作的数字，单独给一个直达入口。 -->
            <el-button
              v-if="isPending(orgCounts.PENDING)"
              type="primary"
              size="small"
              style="margin-top: 12px"
              @click="$router.push('/organizations')"
            >
              去审核 {{ orgCounts.PENDING }} 家待审机构
            </el-button>
          </template>
        </section>

        <!-- 志愿者审核概况 -->
        <section class="stat-section">
          <h3 class="section-title">志愿者兼任申请</h3>
          <el-alert
            v-if="!canVolunteerReview"
            type="warning"
            :closable="false"
            show-icon
            title="当前账号缺少 VOLUNTEER_REVIEW 权限，无法读取申请统计。"
          />
          <template v-else>
            <div class="stat-cards">
              <div class="stat-card">
                <span class="stat-label">待审核</span>
                <strong class="stat-value is-warn">{{ appCounts.PENDING }}</strong>
                <span class="stat-unit">条</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">已通过</span>
                <strong class="stat-value is-ok">{{ appCounts.APPROVED }}</strong>
                <span class="stat-unit">条</span>
              </div>
              <div class="stat-card">
                <span class="stat-label">已拒绝</span>
                <strong class="stat-value">{{ appCounts.REJECTED }}</strong>
                <span class="stat-unit">条</span>
              </div>
            </div>
            <el-button
              v-if="isPending(appCounts.PENDING)"
              type="primary"
              size="small"
              style="margin-top: 12px"
              @click="$router.push('/volunteer-review')"
            >
              去审核 {{ appCounts.PENDING }} 条待审申请
            </el-button>
          </template>
        </section>

        <p v-if="failedParts.length" class="note is-warn">
          以下部分加载失败：{{ failedParts.join('、') }}。其余数字仍然可信。
        </p>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listOrganizations, listUsers, listVolunteerApplications } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'

/**
 * 为什么是前端聚合而不是 GET /admin/stats/overview：
 * 该端点目前只存在于设计文档（docs/final/api/openapi.yaml），运行中的服务端并未实现
 * （/v3/api-docs 里没有它）。这里改为并发调用三个真实列表接口、各取 `total`。
 * 等后端补上聚合端点，只需替换 loadAll 的实现，模板与图表不用动。
 */

const auth = useAuthStore()
const canUserManage = computed(() => auth.hasPermission('USER_MANAGE'))
const canOrgManage = computed(() => auth.hasPermission('ORG_MANAGE'))
const canVolunteerReview = computed(() => auth.hasPermission('VOLUNTEER_REVIEW'))

const loading = ref(false)
const loaded = ref(false)
const failedParts = ref<string[]>([])

const counts = reactive<Record<'GUARDIAN' | 'PROTECTEE' | 'VOLUNTEER', number | '—'>>({
  GUARDIAN: '—',
  PROTECTEE: '—',
  VOLUNTEER: '—',
})

const orgCounts = reactive<Record<'PENDING' | 'APPROVED' | 'REJECTED' | 'DISABLED', number | '—'>>({
  PENDING: '—',
  APPROVED: '—',
  REJECTED: '—',
  DISABLED: '—',
})

const appCounts = reactive<Record<'PENDING' | 'APPROVED' | 'REJECTED', number | '—'>>({
  PENDING: '—',
  APPROVED: '—',
  REJECTED: '—',
})

/** 只取总数：size=1 让服务端不必回传整页记录。 */
async function countUsers(role: string): Promise<number> {
  const res = await listUsers({ role, page: 1, size: 1 })
  return res.total
}

async function countOrgs(status: string): Promise<number> {
  const res = await listOrganizations({ status, page: 1, size: 1 })
  return res.total
}

async function countApps(status: string): Promise<number> {
  const res = await listVolunteerApplications({ status, page: 1, size: 1 })
  return res.total
}

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  failedParts.value = []
  try {
    // 三块相互独立：用 allSettled 让「某一块没权限/失败」不影响其它块出数字。
    const jobs: Promise<void>[] = []

    if (canUserManage.value) {
      for (const role of ['GUARDIAN', 'PROTECTEE', 'VOLUNTEER'] as const) {
        jobs.push(
          countUsers(role)
            .then((n) => {
              counts[role] = n
            })
            .catch(() => {
              failedParts.value.push(`用户·${roleLabel(role)}`)
            }),
        )
      }
    }

    if (canOrgManage.value) {
      for (const status of ['PENDING', 'APPROVED', 'REJECTED', 'DISABLED'] as const) {
        jobs.push(
          countOrgs(status)
            .then((n) => {
              orgCounts[status] = n
            })
            .catch(() => {
              failedParts.value.push(`机构·${orgStatusLabel(status)}`)
            }),
        )
      }
    }

    if (canVolunteerReview.value) {
      for (const status of ['PENDING', 'APPROVED', 'REJECTED'] as const) {
        jobs.push(
          countApps(status)
            .then((n) => {
              appCounts[status] = n
            })
            .catch(() => {
              failedParts.value.push(`申请·${orgStatusLabel(status)}`)
            }),
        )
      }
    }

    await Promise.all(jobs)
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function roleLabel(role: string): string {
  const map: Record<string, string> = { GUARDIAN: '监护人', PROTECTEE: '受护人', VOLUNTEER: '志愿者' }
  return map[role] || role
}

/** 计数可能是 '—'（未加载/无权限/加载失败），只有拿到确切正整数才显示「去审核」。 */
function isPending(value: number | '—'): boolean {
  return typeof value === 'number' && value > 0
}

function orgStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    DISABLED: '已禁用',
  }
  return map[status] || status
}

interface Bar {
  label: string
  value: number | '—'
  percent: string
  cls: string
}

/** 把一组计数值转成条形图数据；宽度按本组最大值归一。 */
function toBars(
  entries: Array<{ label: string; value: number | '—'; cls: string }>,
): Bar[] {
  const nums = entries.map((e) => (typeof e.value === 'number' ? e.value : 0))
  const max = Math.max(...nums, 1)
  return entries.map((e) => ({
    label: e.label,
    value: e.value,
    // 最小给 2%，否则「0」与「1」在图上完全看不出区别。
    percent: `${typeof e.value === 'number' && e.value > 0 ? Math.max(2, (e.value / max) * 100) : 0}%`,
    cls: e.cls,
  }))
}

const userBars = computed<Bar[]>(() =>
  toBars([
    { label: '监护人', value: counts.GUARDIAN, cls: 'is-mist' },
    { label: '受护人', value: counts.PROTECTEE, cls: 'is-moss' },
    { label: '志愿者', value: counts.VOLUNTEER, cls: 'is-mustard' },
  ]),
)

const orgBars = computed<Bar[]>(() =>
  toBars([
    { label: '待审核', value: orgCounts.PENDING, cls: 'is-mustard' },
    { label: '已通过', value: orgCounts.APPROVED, cls: 'is-moss' },
    { label: '已驳回', value: orgCounts.REJECTED, cls: 'is-terra' },
    { label: '已禁用', value: orgCounts.DISABLED, cls: 'is-grey' },
  ]),
)

const userBarSummary = computed(
  () => `用户分布：监护人 ${counts.GUARDIAN}，受护人 ${counts.PROTECTEE}，志愿者 ${counts.VOLUNTEER}`,
)

const orgBarSummary = computed(
  () =>
    `机构分布：待审核 ${orgCounts.PENDING}，已通过 ${orgCounts.APPROVED}，已驳回 ${orgCounts.REJECTED}，已禁用 ${orgCounts.DISABLED}`,
)
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat-section {
  margin-bottom: 24px;
}
.section-title {
  font-family: var(--font-hand);
  font-size: 17px;
  font-weight: 400;
  color: var(--ink);
  margin-bottom: 10px;
  padding-bottom: 4px;
  border-bottom: 1px dashed var(--line);
}
.stat-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
/* 数字卡片：纸面 + 手绘非对称圆角，与全站卡片语言一致。 */
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  background: var(--paper-card);
  border: 1px solid var(--line-strong);
  border-radius: 12px 5px 14px 6px / 6px 13px 5px 12px;
  box-shadow: 2px 2px 0 -1px var(--paper-card), 2px 2px 0 0 var(--line);
}
.stat-label {
  font-size: 12px;
  color: var(--ink-3);
}
.stat-value {
  font-family: var(--font-num);
  font-variant-numeric: tabular-nums;
  font-size: 30px;
  line-height: 1.1;
  color: var(--ink);
  font-weight: 400;
}
.stat-value.is-warn {
  color: var(--mustard);
}
.stat-value.is-ok {
  color: var(--moss);
}
.stat-unit {
  font-size: 11px;
  color: var(--ink-4);
}
.bar-chart {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 560px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.bar-label {
  flex: 0 0 60px;
  color: var(--ink-3);
}
.bar-track {
  flex: 1;
  height: 14px;
  background: var(--paper-tint);
  border: 1px solid var(--line);
  border-radius: 7px 3px 6px 4px / 4px 7px 3px 6px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 6px 2px 5px 3px / 3px 6px 2px 5px;
  transition: width 0.35s ease;
}
.bar-fill.is-mist {
  background: var(--mist);
}
.bar-fill.is-moss {
  background: var(--moss);
}
.bar-fill.is-mustard {
  background: var(--mustard);
}
.bar-fill.is-terra {
  background: var(--terra);
}
.bar-fill.is-grey {
  background: var(--warm-grey);
}
.bar-value {
  flex: 0 0 40px;
  text-align: right;
  color: var(--ink-2);
  /* 数字阅读优先：手写体的 0 是空心方框，条形图末端读数会显得像缺字形。 */
  font-family: var(--font-num);
  font-variant-numeric: tabular-nums;
}
.note {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-4);
  line-height: 1.6;
}
.note.is-warn {
  color: var(--mustard);
}
</style>
