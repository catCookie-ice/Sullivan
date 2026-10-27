<template>
  <header class="app-nav">
    <div class="app-nav__bar">
      <!-- 窄屏：汉堡按钮 + 抽屉。宽屏：整条横向便签菜单。 -->
      <button
        v-if="isNarrow"
        type="button"
        class="app-nav__toggle"
        :aria-expanded="drawerOpen ? 'true' : 'false'"
        aria-controls="app-nav-drawer"
        aria-label="打开导航菜单"
        @click="drawerOpen = true"
      >
        <span class="app-nav__toggle-bars" aria-hidden="true" />
      </button>

      <span class="app-nav__brand">{{ brand }}</span>

      <nav v-if="!isNarrow" aria-label="主导航" class="app-nav__desktop">
        <el-menu
          :default-active="activeIndex"
          mode="horizontal"
          class="app-nav__menu"
          :ellipsis="false"
          @select="onSelect"
        >
          <el-menu-item v-for="item in visibleItems" :key="item.index" :index="item.index">
            {{ item.label }}
          </el-menu-item>
          <el-menu-item class="app-nav__logout" index="__logout" @click="emit('logout')">
            退出登录
          </el-menu-item>
        </el-menu>
      </nav>

      <!-- 右上角动作区（通知铃铛等）：宽窄屏都渲染，宿主布局通过插槽注入。 -->
      <div class="app-nav__actions">
        <slot name="actions" />
      </div>
    </div>

    <!-- 抽屉：用 router-link 列表而不是 el-menu，窄屏下点击区域更大、语义也更直接。 -->
    <el-drawer
      v-model="drawerOpen"
      direction="rtl"
      size="260px"
      :with-header="false"
      :title="`${brand} 导航`"
    >
      <nav id="app-nav-drawer" aria-label="主导航（窄屏）" class="app-nav__drawer">
        <p class="app-nav__drawer-title">{{ brand }}</p>
        <router-link
          v-for="item in visibleItems"
          :key="item.index"
          :to="item.index"
          class="app-nav__drawer-link"
          :class="{ 'is-active': isActive(item.index) }"
          :aria-current="isActive(item.index) ? 'page' : undefined"
          @click="drawerOpen = false"
        >
          {{ item.label }}
        </router-link>
        <button type="button" class="app-nav__drawer-link is-logout" @click="onLogout">
          退出登录
        </button>
      </nav>
    </el-drawer>
  </header>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { NavItem } from './types'

const props = defineProps<{
  /** 左上角品牌文案，如「Sullivan 管理端」。 */
  brand: string
  items: NavItem[]
}>()

const emit = defineEmits<{ (e: 'logout'): void }>()

const route = useRoute()
const router = useRouter()

const drawerOpen = ref(false)

/**
 * 窄屏判定。
 *
 * 用 matchMedia 而不是监听 resize：不用在每个滚动/拖动窗口事件里读 innerWidth，
 * 只在跨过断点时触发一次。900px 是这套导航的临界点 —— 四个端的菜单项最多 9 个，
 * 少于 900px 时横向排布必然溢出。
 */
const NARROW_QUERY = '(max-width: 900px)'
const isNarrow = ref(false)
let mql: MediaQueryList | null = null

function syncNarrow(e: MediaQueryList | MediaQueryListEvent) {
  isNarrow.value = e.matches
  // 从窄屏切回宽屏时抽屉必须收起，否则会留一个悬浮层盖住内容。
  if (!e.matches) drawerOpen.value = false
}

onMounted(() => {
  mql = window.matchMedia(NARROW_QUERY)
  syncNarrow(mql)
  // 老 Safari 只支持 addListener；这里两者都兼容。
  if (mql.addEventListener) mql.addEventListener('change', syncNarrow)
  else (mql as any).addListener?.(syncNarrow)
})

onBeforeUnmount(() => {
  if (!mql) return
  if (mql.removeEventListener) mql.removeEventListener('change', syncNarrow)
  else (mql as any).removeListener?.(syncNarrow)
})

const visibleItems = computed(() => props.items.filter((i) => i.show !== false))

/**
 * 当前高亮项。
 *
 * 用 `startsWith` 而不是全等：`/activities/create` 是 `/activities` 的子路由，
 * 全等匹配会让「新建活动」页上没有任何菜单项高亮。
 * 反过来要避免 `/users` 命中 `/users-archive`，所以补一个「后一位必须是分隔符或结尾」的判断。
 */
function isActive(index: string): boolean {
  const path = route.path
  if (path === index) return true
  if (!path.startsWith(index)) return false
  const next = path.charAt(index.length)
  return next === '/' || next === ''
}

const activeIndex = computed(() => {
  // 取最长匹配，避免父路径先命中。
  const matched = visibleItems.value
    .filter((i) => isActive(i.index))
    .sort((a, b) => b.index.length - a.index.length)[0]
  return matched?.index || route.path
})

/** el-menu 的 select：`router` 属性在这里没开，改为显式跳转，便于排除 __logout。 */
function onSelect(index: string) {
  if (index === '__logout') return
  if (route.path !== index) router.push(index)
}

function onLogout() {
  drawerOpen.value = false
  emit('logout')
}
</script>

<style scoped>
.app-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: var(--paper-deep);
  border-bottom: 1px dashed var(--line);
}
.app-nav__bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 16px 8px 20px;
  min-height: 61px;
  box-sizing: border-box;
}
.app-nav__brand {
  font-family: var(--font-hand);
  font-size: 20px;
  color: var(--ink);
  white-space: nowrap;
  position: relative;
  padding-right: 8px;
}
.app-nav__brand::before {
  content: "";
  display: inline-block;
  width: 10px;
  height: 10px;
  border: 1.5px solid var(--mist);
  border-radius: 50% 45% 55% 40% / 50% 60% 40% 55%;
  margin-right: 8px;
  transform: rotate(-15deg);
}
.app-nav__brand::after {
  content: "";
  position: absolute;
  left: 24px;
  right: 4px;
  bottom: -2px;
  height: 5px;
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='6' viewBox='0 0 12 6'%3E%3Cpath d='M0 3 Q3 0 6 3 T12 3' fill='none' stroke='%235f7a92' stroke-width='1.4' stroke-linecap='round'/%3E%3C/svg%3E")
    repeat-x left bottom / 12px 6px;
}
.app-nav__desktop {
  flex: 1;
  min-width: 0;
}
.app-nav__menu {
  background: transparent;
  border-bottom: none;
}
/* 退出登录靠右，与业务入口分开，避免误点。 */
.app-nav__logout {
  margin-left: auto !important;
}
/* 右上角动作区：宽屏时菜单已撑满，窄屏时靠 margin-left:auto 顶到最右。 */
.app-nav__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding-left: 8px;
}

/* —— 窄屏汉堡按钮：44px 触控目标，符合无障碍最小点击区域 —— */
.app-nav__toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-right: 4px;
  border: 1px solid var(--line-strong);
  border-radius: 9px 4px 8px 5px / 5px 9px 4px 8px;
  background: var(--paper-tint);
  cursor: pointer;
  flex: 0 0 auto;
}
.app-nav__toggle-bars,
.app-nav__toggle-bars::before,
.app-nav__toggle-bars::after {
  display: block;
  width: 20px;
  height: 2px;
  background: var(--ink-2);
  border-radius: 1px;
}
.app-nav__toggle-bars {
  position: relative;
}
.app-nav__toggle-bars::before,
.app-nav__toggle-bars::after {
  content: "";
  position: absolute;
  left: 0;
}
.app-nav__toggle-bars::before {
  top: -6px;
}
.app-nav__toggle-bars::after {
  top: 6px;
}
.app-nav__toggle:hover {
  background: #e9e2d2;
}

/* —— 抽屉内导航 —— */
.app-nav__drawer {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.app-nav__drawer-title {
  font-family: var(--font-hand);
  font-size: 16px;
  color: var(--ink);
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--line);
}
.app-nav__drawer-link {
  display: block;
  padding: 12px 14px;
  border-radius: 10px 4px 8px 3px / 4px 9px 3px 7px;
  border: 1px solid transparent;
  color: var(--ink-2);
  text-decoration: none;
  font-family: var(--font-hand);
  font-size: 15px;
  background: var(--paper-tint);
  /* 触控目标 ≥44px */
  min-height: 44px;
  box-sizing: border-box;
  cursor: pointer;
  text-align: left;
  width: 100%;
}
.app-nav__drawer-link.is-active {
  background: var(--paper-card);
  border-color: var(--line-strong);
  color: var(--mist-dark);
  font-weight: 600;
}
.app-nav__drawer-link.is-logout {
  margin-top: 12px;
  background: transparent;
  border-color: var(--line);
  color: var(--ink-3);
}
</style>
