<template>
  <div class="page-card">
    <el-card>
      <template #header>
        <div class="card-head">
          <span>志愿活动</span>
          <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        </div>
      </template>

      <!-- 报名入口在 MINI 里还没有对外接口，必须讲清楚，否则用户会一直找「报名」按钮。 -->
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="当前可浏览公益机构发布的志愿活动。在线报名功能尚未开放，如需参与请联系活动发布机构。"
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
            <el-tag :type="statusTag(item.status)" size="small">{{ statusText(item.status) }}</el-tag>
            <span v-if="isRegistrationOpen(item)" class="deadline">报名截止 {{ formatDateTime(item.registrationEndsAt) }}</span>
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
          </footer>
        </article>
      </div>

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
import { listPublicActivities } from '@/api/activity'
import type { ActivityResponse } from '@/types/api'
import { formatDateTime } from '@/utils/display'
import { errorMessage } from '@/utils/error'

const rows = ref<ActivityResponse[]>([])
const status = ref('')
const page = ref(1)
const size = ref(12)
const total = ref(0)
const loading = ref(false)

onMounted(reload)

async function reload() {
  loading.value = true
  try {
    const res = await listPublicActivities({
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '加载活动失败'))
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  reload()
}

/** 报名窗口是否还开着（且活动本身还能报名）。 */
function isRegistrationOpen(item: ActivityResponse): boolean {
  if (item.status === 'CANCELLED' || item.status === 'ENDED') return false
  const now = Date.now()
  const start = parseTime(item.registrationStartsAt)
  const end = parseTime(item.registrationEndsAt)
  if (start == null || end == null) return false
  return now >= start && now <= end
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

function statusTag(s: string): 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
    RECRUITING: 'success',
    IN_PROGRESS: 'warning',
    ENDED: 'info',
    CANCELLED: 'danger',
  }
  return map[s] || 'info'
}

function statusText(s: string): string {
  const map: Record<string, string> = {
    RECRUITING: '招募中',
    IN_PROGRESS: '进行中',
    ENDED: '已结束',
    CANCELLED: '已取消',
  }
  return map[s] || s
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
.map-link {
  font-size: 12px;
  color: var(--mist-dark);
  text-decoration: underline;
}
</style>
