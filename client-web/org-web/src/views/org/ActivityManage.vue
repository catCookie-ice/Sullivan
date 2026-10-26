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
            <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
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
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
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
          <el-tag :type="statusTag(detail.status)">{{ statusText(detail.status) }}</el-tag>
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listMyActivities } from '@/api/org'
import type { ActivityResponse } from '@/types/api'
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

function statusTag(s: string) {
  const map: Record<string, string> = { RECRUITING: 'success', IN_PROGRESS: 'warning', ENDED: 'info', CANCELLED: 'danger' }
  return map[s] || 'info'
}

function statusText(s: string) {
  const map: Record<string, string> = { RECRUITING: '招募中', IN_PROGRESS: '进行中', ENDED: '已结束', CANCELLED: '已取消' }
  return map[s] || s
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
</style>
