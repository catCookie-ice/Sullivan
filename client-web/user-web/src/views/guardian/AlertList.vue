<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>报警</span>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <!-- 只看未结束的：监护人打开这个页面的第一诉求是「现在有没有人需要我」，
           历史记录折叠到「全部」标签里，不占首屏。 -->
      <el-tabs v-model="tab">
        <el-tab-pane name="open">
          <template #label>
            待处理
            <el-badge v-if="openAlerts.length" :value="openAlerts.length" type="danger" class="tab-badge" />
          </template>
          <el-skeleton v-if="loading && !alerts.length" :rows="4" animated />
          <el-empty v-else-if="!openAlerts.length" description="当前没有待处理的报警" />
          <div v-else class="alert-list">
            <article v-for="alert in openAlerts" :key="alert.id" class="alert-item is-open">
              <header class="alert-head">
                <div class="alert-title">
                  <el-tag :type="typeTag(alert.alertType)" size="small">{{ typeText(alert.alertType) }}</el-tag>
                  <el-tag :type="statusTag(alert.status)" size="small">{{ statusText(alert.status) }}</el-tag>
                  <span class="alert-who">{{ alertOrigin(alert) }}</span>
                </div>
                <span class="alert-time">{{ formatDateTime(alert.createdAt) }}</span>
              </header>

              <!-- 位置新鲜度直接决定这条报警的可信度：分钟级旧坐标会把人往错的方向引。 -->
              <div class="alert-body">
                <div class="alert-row">
                  <span class="label">位置</span>
                  <span v-if="alert.location" class="value">
                    {{ coordText(alert.location) }}
                    <a
                      :href="mapLink(alert)"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="map-link"
                    >在地图中查看</a>
                  </span>
                  <span v-else class="value is-muted">未上报位置</span>
                </div>
                <div v-if="alert.location" class="alert-row">
                  <span class="label">位置时间</span>
                  <span class="value" :class="{ 'is-warn': isStale(alert.location) }">
                    {{ freshnessText(alert.location) }}
                  </span>
                </div>
                <div class="alert-row">
                  <span class="label">通知情况</span>
                  <span class="value">{{ routeText(alert) }}</span>
                </div>
              </div>

              <footer class="alert-foot">
                <el-button size="small" type="primary" @click="openDetail(alert)">查看详情</el-button>
                <!-- 结束报警只有受护人本人能做；监护人这里不显示按钮，免得点了才被告知 403。 -->
                <el-button
                  v-if="isMyOwnAlert(alert)"
                  size="small"
                  type="warning"
                  @click="handleResolve(alert)"
                >
                  结束报警
                </el-button>
              </footer>
            </article>
          </div>
        </el-tab-pane>

        <el-tab-pane label="全部" name="all">
          <el-table :data="alerts" v-loading="loading" border empty-text="还没有报警记录">
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag :type="typeTag(row.alertType)" size="small">{{ typeText(row.alertType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="130">
              <template #default="{ row }">{{ isMyOwnAlert(row) ? '我发起' : '他人向我求助' }}</template>
            </el-table-column>
            <el-table-column label="位置" min-width="180">
              <template #default="{ row }">
                <span v-if="row.location">{{ coordText(row.location) }}</span>
                <span v-else class="is-muted">未上报</span>
              </template>
            </el-table-column>
            <el-table-column label="位置时效" width="160">
              <template #default="{ row }">
                <span v-if="row.location" :class="{ 'is-warn': isStale(row.location) }">
                  {{ freshnessText(row.location) }}
                </span>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button size="small" @click="openDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 详情：位置、通知了谁、自动呼叫状态 -->
    <el-dialog v-model="detailVisible" title="报警详情" width="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="发起时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ typeText(detail.alertType) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ alertOrigin(detail) }}</el-descriptions-item>
          <el-descriptions-item label="位置">
            <template v-if="detail.location">
              {{ coordText(detail.location) }}
              <a :href="mapLink(detail)" target="_blank" rel="noopener noreferrer" class="map-link">
                在地图中查看
              </a>
            </template>
            <span v-else class="is-muted">未上报位置</span>
          </el-descriptions-item>
          <el-descriptions-item label="位置时效">
            <span v-if="detail.location" :class="{ 'is-warn': isStale(detail.location) }">
              {{ freshnessText(detail.location) }}
            </span>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="通知说明">{{ routeText(detail) }}</el-descriptions-item>
          <el-descriptions-item label="自动呼叫">{{ autoSessionText(detail.autoSessionState) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.resolvedAt" label="结束时间">
            {{ formatDateTime(detail.resolvedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.deliveries.length">
          <el-divider>逐个接收人的通知结果</el-divider>
          <el-table :data="detail.deliveries" size="small" border>
            <el-table-column prop="dispatchOrder" label="顺序" width="70" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="deliveryTag(row.status)" size="small">{{ deliveryText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="160">
              <template #default="{ row }">
                <span v-if="row.failureReason" class="is-warn">{{ row.failureReason }}</span>
                <span v-else-if="row.sessionId">已建立会话</span>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyAlerts, resolveAlert } from '@/api/alert'
import type { AlertLocation, AlertResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const auth = useAuthStore()
const myUid = auth.principalId

const alerts = ref<AlertResponse[]>([])
const loading = ref(false)
const tab = ref<'open' | 'all'>('open')
const detailVisible = ref(false)
const detail = ref<AlertResponse | null>(null)

/**
 * 未结束的报警。
 *
 * 除 RESOLVED 外都算未结束：DISPATCH_FAILED / NO_RECIPIENT 恰恰是最需要人工介入的
 * 状态（通知没送出去），把它们从「待处理」里过滤掉等于把问题藏起来。
 */
const openAlerts = computed(() => alerts.value.filter((a) => a.status !== 'RESOLVED'))

onMounted(load)

async function load() {
  loading.value = true
  try {
    alerts.value = await listMyAlerts()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载报警失败'))
  } finally {
    loading.value = false
  }
}

function isMyOwnAlert(alert: AlertResponse): boolean {
  return alert.protecteeUid === myUid
}

/** 「来源」：自己发起 vs 别人向我求助。uid 不展示，只用它做身份比对。 */
function alertOrigin(alert: AlertResponse): string {
  return isMyOwnAlert(alert) ? '我发起' : '他人向我求助'
}

function coordText(loc: AlertLocation): string {
  return `${Number(loc.latitude).toFixed(6)}, ${Number(loc.longitude).toFixed(6)}`
}

/** 地图链接用高德 URI，与项目里活动选点用的同一家；参数顺序是「经度,纬度」。 */
function mapLink(alert: AlertResponse): string {
  const loc = alert.location
  if (!loc) return undefined as unknown as string
  const lng = Number(loc.longitude)
  const lat = Number(loc.latitude)
  return `https://uri.amap.com/marker?position=${lng},${lat}&name=${encodeURIComponent('报警位置')}&src=sullivan&coordinate=gaode&callnative=0`
}

/**
 * 位置是否已经不可信。
 *
 * 阈值与服务端 `LocationService.STALE_AFTER` 保持一致（10 分钟）：
 * 一个人正常步速 10 分钟能走出约 800 米，「他现在在哪」和这个坐标已经不是同一个问题了。
 */
const STALE_SECONDS = 10 * 60

function isStale(loc: AlertLocation): boolean {
  return (loc.ageSeconds ?? 0) > STALE_SECONDS
}

function freshnessText(loc: AlertLocation): string {
  const age = loc.ageSeconds
  if (age == null) return '未知'
  if (age < 60) return `${Math.max(0, Math.round(age))} 秒前`
  const minutes = Math.round(age / 60)
  if (minutes < 60) {
    return isStale(loc) ? `${minutes} 分钟前（可能已过时）` : `${minutes} 分钟前`
  }
  const hours = Math.round(minutes / 60)
  return `${hours} 小时前（可能已过时）`
}

function routeText(alert: AlertResponse): string {
  if (alert.routeNote) return alert.routeNote
  const map: Record<string, string> = {
    ALL_GUARDIANS: '已通知监护人',
    NEARBY_VOLUNTEERS: '已通知附近志愿者',
    NONE: '没有可通知的接收人',
  }
  return map[alert.routeType] || '—'
}

function autoSessionText(state: string): string {
  const map: Record<string, string> = {
    NONE: '未安排',
    SCHEDULED: '已排期，稍后自动呼叫',
    RUNNING: '正在呼叫',
    SKIPPED: '已跳过（用户关闭了自动呼叫）',
    DONE: '已结束',
  }
  return map[state] || state
}

function typeText(type: string): string {
  const map: Record<string, string> = {
    MANUAL: '主动报警',
    VOICE_HELP: '语音求助',
    COLLISION: '碰撞报警',
  }
  return map[type] || type
}

function typeTag(_type: string): 'danger' | 'warning' | 'info' {
  // 三种报警都是求助信号，一律用 danger 色，不做「哪个更严重」的视觉分级，
  // 免得监护人以为橙色那条可以先放一放。
  return 'danger'
}

function statusText(status: string): string {
  const map: Record<string, string> = {
    CREATED: '已创建',
    DISPATCHING: '通知中',
    DISPATCHED: '已通知',
    ACKNOWLEDGED: '已确认',
    RESOLVED: '已结束',
    NO_RECIPIENT: '无接收人',
    DISPATCH_FAILED: '通知失败',
  }
  return map[status] || status
}

function statusTag(status: string): 'danger' | 'warning' | 'success' | 'info' {
  if (status === 'RESOLVED') return 'success'
  if (status === 'NO_RECIPIENT' || status === 'DISPATCH_FAILED') return 'warning'
  if (status === 'CREATED' || status === 'DISPATCHING') return 'warning'
  return 'danger'
}

function deliveryText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待送达',
    SENT: '已发送',
    DELIVERED: '已送达',
    ACKNOWLEDGED: '已确认',
    FAILED: '送达失败',
  }
  return map[status] || status
}

function deliveryTag(status: string): 'danger' | 'warning' | 'success' | 'info' {
  if (status === 'ACKNOWLEDGED' || status === 'DELIVERED') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function openDetail(alert: AlertResponse) {
  detail.value = alert
  detailVisible.value = true
}

async function handleResolve(alert: AlertResponse) {
  try {
    await ElMessageBox.confirm('确认结束这条报警？结束后将同时取消尚未执行的自动呼叫。', '结束报警', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await resolveAlert(alert.id)
    ElMessage.success('报警已结束')
    await load()
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '结束报警失败'))
  }
}
</script>

<style scoped>
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.tab-badge {
  margin-left: 4px;
  vertical-align: middle;
}
.alert-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 待处理报警用左侧陶土红竖条标出，一眼能在列表里定位到。 */
.alert-item {
  border: 1px solid var(--line-strong);
  border-left: 4px solid var(--terra);
  border-radius: 10px 4px 12px 5px / 5px 11px 4px 12px;
  background: var(--paper-card);
  padding: 12px 14px;
}
.alert-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.alert-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.alert-who {
  font-family: var(--font-hand);
  color: var(--ink-2);
}
.alert-time {
  font-size: 12px;
  color: var(--ink-3);
}
.alert-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
.alert-row {
  display: flex;
  gap: 8px;
}
.alert-row .label {
  flex: 0 0 68px;
  color: var(--ink-3);
}
.alert-row .value {
  color: var(--ink-2);
  word-break: break-all;
}
.alert-foot {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.map-link {
  margin-left: 8px;
  font-size: 12px;
  color: var(--mist-dark);
  text-decoration: underline;
}
.is-muted {
  color: var(--ink-4);
}
.is-warn {
  color: var(--mustard);
}
</style>
