<template>
  <div class="notif-bell">
    <!-- 角标：未读数，上限 99，之后 99+；有未读时铃铛点亮。 -->
    <el-badge :value="unread" :max="99" :hidden="unread <= 0" class="notif-bell__badge">
      <button
        type="button"
        class="notif-bell__button"
        :class="{ 'is-active': unread > 0 }"
        aria-label="站内通知"
        @click="openDrawer"
        @mouseenter="hoverOpen"
        @mouseleave="hoverCloseTimer"
      >
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"
             :fill="unread > 0 ? 'currentColor' : 'none'"
             :stroke="'currentColor'" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.7 21a2 2 0 0 1-3.4 0" />
        </svg>
      </button>
    </el-badge>

    <!-- hover 简述：最近几条通知的标题 + 摘要，点击直达。 -->
    <el-popover
      :visible="popoverVisible"
      placement="bottom-end"
      :width="340"
      popper-class="notif-bell__popover"
    >
      <div class="notif-bell__panel" @mouseenter="hoverOpen" @mouseleave="hoverCloseTimer">
        <div class="notif-bell__panel-head">
          <span>通知</span>
          <el-button v-if="unread > 0" link size="small" @click="markAllRead">全部已读</el-button>
        </div>
        <p v-if="!recent.length" class="notif-bell__empty">暂无通知</p>
        <button v-for="item in recent" :key="item.id" type="button"
                class="notif-bell__item" :class="{ 'is-unread': item.status === 'UNREAD' }"
                @click="open(item)">
          <span class="notif-bell__item-title">
            <span v-if="item.status === 'UNREAD'" class="notif-bell__dot" aria-hidden="true" />
            {{ item.title }}
          </span>
          <span class="notif-bell__item-content">{{ item.content }}</span>
          <span class="notif-bell__item-time">{{ formatDateTime(item.createdAt) }}</span>
        </button>
        <button type="button" class="notif-bell__more" @click="openDrawer">查看全部</button>
      </div>
      <template #reference>
        <span class="notif-bell__anchor" />
      </template>
    </el-popover>

    <!-- 通知多时：抽屉 + 滚动加载更早。 -->
    <el-drawer v-model="drawerVisible" title="通知" direction="rtl" size="380px">
      <div class="notif-bell__drawer">
        <div class="notif-bell__panel-head">
          <span>全部通知</span>
          <el-button v-if="unread > 0" link size="small" @click="markAllRead">全部已读</el-button>
        </div>
        <div v-if="items.length" class="notif-bell__list">
          <button v-for="item in items" :key="item.id" type="button"
                  class="notif-bell__item" :class="{ 'is-unread': item.status === 'UNREAD' }"
                  @click="open(item)">
            <span class="notif-bell__item-title">
              <span v-if="item.status === 'UNREAD'" class="notif-bell__dot" aria-hidden="true" />
              {{ item.title }}
            </span>
            <span class="notif-bell__item-content">{{ item.content }}</span>
            <span class="notif-bell__item-time">{{ formatDateTime(item.createdAt) }}</span>
          </button>
        </div>
        <p v-else class="notif-bell__empty">暂无通知</p>
        <el-button v-if="hasMore" text style="width: 100%" :loading="loadingMore"
                   @click="loadMore">加载更早的通知</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { NotificationApi, NotificationItem } from './types'
import { formatDateTime } from '@/utils/display'

/**
 * 导航栏右上角的通知铃铛（org-web / user-web 共用）。
 *
 * - 角标未读数每 30 秒轮询一次（通知不是聊天，不用追着 5 秒）；
 * - hover 出最近通知简述，点击直达 link；
 * - 点铃铛开抽屉看全部，滚动「加载更早」。
 *
 * 后端按令牌的 principalType 区分收件箱（志愿者/机构各看各的），
 * 组件本身不知道角色，API 由宿主端注入（各自的 request 实例）。
 */
const props = defineProps<{ api: NotificationApi }>()

const POLL_MS = 30_000
const PAGE_SIZE = 20

const router = useRouter()
const unread = ref(0)
const items = ref<NotificationItem[]>([])
const hasMore = ref(false)
const loadingMore = ref(false)
const drawerVisible = ref(false)
const popoverVisible = ref(false)
let pollTimer: number | undefined
let hoverTimer: number | undefined
/** hover 面板与抽屉共用一份已加载数据；抽屉里继续加载时同步刷新 hover 列表。 */
const recent = computed(() => items.value.slice(0, 8))

onMounted(async () => {
  await Promise.all([refreshUnread(), reload()])
  pollTimer = window.setInterval(refreshUnread, POLL_MS)
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) window.clearInterval(pollTimer)
  if (hoverTimer !== undefined) window.clearTimeout(hoverTimer)
})

async function refreshUnread() {
  try {
    unread.value = (await props.api.unreadCount()).count
  } catch {
    // 轮询失败静默：通知角标不值得弹错误提示。
  }
}

async function reload() {
  try {
    const page = await props.api.list(undefined, PAGE_SIZE)
    items.value = page.records
    hasMore.value = page.hasMore
  } catch {
    // 同上，静默。
  }
}

async function loadMore() {
  const oldest = items.value[items.value.length - 1]
  if (!oldest || loadingMore.value) return
  loadingMore.value = true
  try {
    const page = await props.api.list(oldest.createdAt, PAGE_SIZE)
    items.value = [...items.value, ...page.records]
    hasMore.value = page.hasMore
  } finally {
    loadingMore.value = false
  }
}

function hoverOpen() {
  if (hoverTimer !== undefined) window.clearTimeout(hoverTimer)
  popoverVisible.value = true
}

function hoverCloseTimer() {
  if (hoverTimer !== undefined) window.clearTimeout(hoverTimer)
  // 短暂延迟关闭：给鼠标移进面板的时间。
  hoverTimer = window.setTimeout(() => {
    popoverVisible.value = false
  }, 300)
}

function openDrawer() {
  popoverVisible.value = false
  drawerVisible.value = true
  reload()
}

async function open(item: NotificationItem) {
  popoverVisible.value = false
  drawerVisible.value = false
  if (item.status === 'UNREAD') {
    try {
      await props.api.markRead(item.id)
      item.status = 'READ'
      unread.value = Math.max(0, unread.value - 1)
    } catch {
      // 已读失败不拦跳转。
    }
  }
  if (item.link) router.push(item.link)
}

async function markAllRead() {
  try {
    await props.api.markAllRead()
    items.value = items.value.map((item) => ({ ...item, status: 'READ' }))
    unread.value = 0
  } catch {
    // 静默。
  }
}
</script>

<style scoped>
.notif-bell {
  display: inline-flex;
  align-items: center;
}
.notif-bell__anchor {
  display: inline-flex;
}
.notif-bell__button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: 1px solid var(--line);
  border-radius: 10px 4px 8px 5px / 5px 10px 4px 8px;
  background: var(--paper-tint);
  color: var(--ink-3);
  cursor: pointer;
}
.notif-bell__button.is-active {
  color: var(--terra, #b45309);
  border-color: var(--line-strong);
}
.notif-bell__panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  color: var(--ink-1);
  margin-bottom: 8px;
}
.notif-bell__empty {
  text-align: center;
  color: var(--ink-4);
  font-size: 13px;
  padding: 18px 0;
}
.notif-bell__item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}
.notif-bell__item:hover {
  background: var(--paper-tint);
}
.notif-bell__item.is-unread {
  background: var(--paper-tint);
  border-color: var(--line);
}
.notif-bell__item-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-1);
}
.notif-bell__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--terra, #b45309);
  flex: 0 0 auto;
}
.notif-bell__item-content {
  font-size: 12px;
  color: var(--ink-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.notif-bell__item-time {
  font-size: 11px;
  color: var(--ink-4);
}
.notif-bell__more {
  width: 100%;
  text-align: center;
  padding: 8px 0 2px;
  border: none;
  background: transparent;
  color: var(--mist-dark);
  font-size: 13px;
  cursor: pointer;
}
.notif-bell__drawer {
  display: flex;
  flex-direction: column;
}
.notif-bell__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}
</style>
