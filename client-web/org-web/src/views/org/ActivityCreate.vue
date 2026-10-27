<template>
  <div class="page-card">
    <el-card>
      <template #header>创建志愿活动</template>

      <KeepAutoForm ref="keepFormRef" keep-auto :model="form" label-width="120px" style="max-width: 760px">
        <el-form-item label="活动名" required>
          <el-input v-model="form.title" maxlength="160" show-word-limit placeholder="如：朝阳区视障人士徒步陪伴" />
        </el-form-item>
        <el-form-item label="志愿者要求" required>
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="活动说明与志愿者要求" />
        </el-form-item>
        <el-form-item label="活动地点" required>
          <div class="location-combo">
            <el-input ref="locationInputRef" v-model="form.locationText" maxlength="300"
                      placeholder="地点文本，如：朝阳公园南门"
                      aria-describedby="location-hint-live"
                      @input="onLocationTextInput" @keydown.tab.prevent="onLocationTab"
                      @keydown.esc="onLocationDismiss" @blur="onLocationDismiss" />
            <!-- 半透明补全提示：显示建议地址里「用户还没输入」的剩余部分。
                 aria-hidden 是因为它只是视觉补全；完整建议另由下方 #location-hint-live 播报给读屏器。 -->
            <span v-if="locationHint" class="location-hint" :style="{ left: locationHintLeft }"
                  aria-hidden="true">{{ locationHint }}</span>
          </div>
          <!-- 读屏器专用：补全建议通过 aria-live 播报，Tab 才可补全。替代纯视觉的半透明文字。 -->
          <span id="location-hint-live" class="sr-only" aria-live="polite">
            {{ locationHint ? `完整地址建议：${locationSuggestion}，按 Tab 补全` : '' }}
          </span>
          <!-- 可见提示：输入框行内空间不足时（长地名）半透明补全会被裁掉，
               这里给出完整建议原文，保证任何情况下都能先「看见」完整地址再决定是否 Tab。 -->
          <div v-if="locationHint" class="location-tip">
            完整地址：<span class="location-tip-addr">{{ locationSuggestion }}</span>
            <span class="location-tip-key">按 <kbd>Tab</kbd> 使用，<kbd>Esc</kbd> 保持原文</span>
          </div>
        </el-form-item>

        <el-form-item label="地图选点">
          <AmapPicker ref="amapPickerRef" v-model="location" style="width: 100%" />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="活动开始时间" required>
              <el-date-picker v-model="form.startsAt" type="datetime" placeholder="活动开始时间"
                              style="width: 100%" value-format="YYYY-MM-DD HH:mm:ss"
                              :disabled-date="disableBeforeEarliest" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="活动结束时间" required>
              <el-date-picker v-model="form.endsAt" type="datetime" placeholder="活动结束时间"
                              style="width: 100%" value-format="YYYY-MM-DD HH:mm:ss" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="报名开始时间" required>
              <el-date-picker v-model="form.registrationStartsAt" type="datetime" placeholder="报名开始时间"
                              style="width: 100%" value-format="YYYY-MM-DD HH:mm:ss"
                              :disabled-date="disableBeforeEarliest" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="报名结束时间" required>
              <el-date-picker v-model="form.registrationEndsAt" type="datetime" placeholder="报名结束时间"
                              style="width: 100%" value-format="YYYY-MM-DD HH:mm:ss" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="需要志愿者人数" required>
          <el-input-number v-model="form.maxVolunteers" :min="1" :max="9999" />
        </el-form-item>

        <el-form-item label="报名审核">
          <div class="review-switch">
            <el-switch v-model="form.reviewRequired" />
            <!-- 文本随开关联动：开 = 需审核（报名先待审），关 = 直接通过（报名即成功入群）。 -->
            <el-tag :type="form.reviewRequired ? 'warning' : 'info'" size="small">
              {{ form.reviewRequired ? '需审核' : '直接通过' }}
            </el-tag>
            <span class="review-hint">{{ form.reviewRequired
              ? '志愿者报名后进入待审核，机构在「报名审核」页批准才算报名成功并占名额入群。'
              : '志愿者报名即成功，自动占用名额并加入活动群聊。' }}</span>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">发布活动</el-button>
          <el-button @click="$router.back()">返回</el-button>
        </el-form-item>
      </KeepAutoForm>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createActivity } from '@/api/org'
import AmapPicker from '@/components/AmapPicker.vue'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'
import type { ActivityCreateRequest } from '@/types/api'

import { errorMessage } from '@/utils/error'
const router = useRouter()
const loading = ref(false)
const keepFormRef = ref<{ clearCache: () => void } | null>(null)
const amapPickerRef = ref<{
  map: any
  setLocation: (lng: number, lat: number, address?: string) => void
  flyTo: (lng: number, lat: number, address?: string) => void
} | null>(null)

// 输入框 → 地图：防抖 300ms，避免每次按键都地理编码。
const locationInputTimer = ref<ReturnType<typeof setTimeout> | null>(null)
/**
 * 「程序回填」的哨兵值。
 *
 * 取代原先的 `suppressLocationInput` 布尔开关。那个开关是**粘滞**的：它只在
 * `@input` 里被复位，而程序化改 `form.locationText` 并不会触发原生 input 事件，
 * 于是开关一旦置位就再没人复位——用户**下一次真实按键会被当成回显吞掉**，
 * 既不地理编码也不出补全，表现为「输入后没有任何反应」。
 *
 * 改成记录「上一次由程序写进去的文本」：只有当输入框当前值与它完全相等时，
 * 才认定为回显；比完即清空，天然不会跨按键残留。
 */
let lastProgrammaticText = ''
// geocodeText 设置 location 时置位，抑制 watch(location) 回填输入框（否则会覆盖用户原文）。
let suppressWatchBackfill = false

// 活动地点输入框 DOM（el-input 实例）与补全建议。
const locationInputRef = ref<{ $el: HTMLElement } | null>(null)
const locationSuggestion = ref('')
// 半透明提示文本 = 建议地址去掉用户已输入前缀后的剩余部分；仅当建议以输入开头且未完全补全时显示。
const locationHint = computed(() => {
  const text = form.locationText.trim()
  const sug = locationSuggestion.value
  if (!sug || !text || sug === text) return ''
  return sug.startsWith(text) ? sug.slice(text.length) : ''
})
// 复用的 canvas 2d 上下文：measureText 每敲一个字都会调用，不宜每次 new 一个 canvas。
let measureCtx: CanvasRenderingContext2D | null = null
// 提示文本的左偏移 = 输入框内边距 + 已输入文本的像素宽度。
const locationHintLeft = computed(() => {
  const text = form.locationText
  if (!text) return '11px'
  if (typeof document === 'undefined') return '11px'
  if (!measureCtx) {
    measureCtx = document.createElement('canvas').getContext('2d')
    if (measureCtx) measureCtx.font = '14px sans-serif'
  }
  if (measureCtx) {
    return `${11 + measureCtx.measureText(text).width}px`
  }
  // 极端兜底：中文按 14px 估宽。
  return `${11 + text.length * 14}px`
})

const form = reactive({
  title: '',
  description: '',
  locationText: '',
  startsAt: '' as string,
  endsAt: '' as string,
  registrationStartsAt: '' as string,
  registrationEndsAt: '' as string,
  // el-input-number 被手动清空后会把 null 写进模型，类型如实放宽，提交前再拦。
  maxVolunteers: 10 as number | null,
  // 报名是否需机构审核；开启后报名先进 PENDING，机构批准才占名额入群。
  reviewRequired: false,
})

const location = ref<{ lng?: number; lat?: number; address?: string }>({})

// —— 活动地点输入框 ↔ 地图选点 双向联动 ——

/** 输入框 @input：防抖后对文本地理编码，命中则让地图跳转到该地点。 */
function onLocationTextInput() {
  // 程序回填（地图→输入框方向）：不把回显当成用户输入去编码，避免死循环。
  // 只在「当前值与上次程序写入的完全一致」时认定回显，并且比完立刻清空哨兵，
  // 保证用户随后的真实按键一定走正常路径（见 lastProgrammaticText 的注释）。
  if (lastProgrammaticText && form.locationText === lastProgrammaticText) {
    lastProgrammaticText = ''
    return
  }
  lastProgrammaticText = ''
  const text = form.locationText.trim()
  // 用户继续输入、与建议前缀不一致时清掉建议；为空也清。
  if (!text || !locationSuggestion.value.startsWith(text)) {
    locationSuggestion.value = ''
  }
  if (!text) return
  if (locationInputTimer.value) clearTimeout(locationInputTimer.value)
  locationInputTimer.value = setTimeout(() => geocodeText(text), 300)
}

/** Tab 补全：把建议的完整地址填入输入框（用户也可以不改，保持原输入）。 */
function onLocationTab() {
  const sug = locationSuggestion.value
  if (!sug || !locationHint.value) return
  lastProgrammaticText = sug
  form.locationText = sug
  locationSuggestion.value = ''
}

/** Esc / 失焦：放弃补全建议，保留用户原文。 */
function onLocationDismiss() {
  locationSuggestion.value = ''
}

/** 地理编码：把地点文本转成经纬度，地图定位 + 放置标记。 */
function geocodeText(text: string) {
  const AMap = (window as any).AMap
  if (!AMap) return
  AMap.plugin('AMap.Geocoder', () => {
    const geocoder = new AMap.Geocoder({ city: '全国' })
    geocoder.getLocation(text, (status: string, result: any) => {
      // 请求在途期间用户又改了文本：这次结果已经过期，直接丢弃。
      // 不丢的话，旧结果会把补全建议盖回用户早已改掉的内容。
      if (form.locationText.trim() !== text) return
      if (status !== 'complete' || !result?.geocodes?.length) {
        // 未命中不强行改 location，避免误清空已选点；失败多为 INVALID_USER_SCODE（安全密钥）。
        console.warn('[ActivityCreate] 地理编码失败 status=', status, 'info=', result?.info, '文本=', text)
        return ElMessage.info(`未找到地点：「${text}」`)
      }
      const g = result.geocodes[0]
      const lng = g.location?.lng
      const lat = g.location?.lat
      if (lng == null || lat == null) {
        console.warn('[ActivityCreate] 地理编码结果无坐标', text, g)
        return
      }
      console.info('[ActivityCreate] 地理编码成功', text, '→', lng, lat, '地址=', g.formattedAddress)
      // 记录补全建议（完整地址），输入框后半段半透明显示，可 Tab 补全或保持原文。
      locationSuggestion.value = g.formattedAddress || text
      // 用 flyTo 强制地图居中+缩放+放标记，确保视图移动（比 setLocation 更直接）。
      amapPickerRef.value?.flyTo(lng, lat)
      // 更新 location（供提交带经纬度），但抑制 watch(location) 回填输入框——用户输入应保持不变，
      // 只有 Tab 主动补全才替换。
      suppressWatchBackfill = true
      location.value = { lng, lat, address: g.formattedAddress || text }
      suppressWatchBackfill = false
    })
  })
}

/** 地图选点 → 输入框：AmapPicker 选点/拖拽后更新 location，把 address 写回 locationText。 */
watch(
  () => location.value,
  (loc, prev) => {
    // geocodeText 引起的 location 更新不在此回填输入框（由用户选择 Tab 或保持原文）。
    if (suppressWatchBackfill) return
    // 仅在坐标确实变化时回填（避免连续选同一点、或 address 相同导致 watch 不触发）。
    const moved = !prev || prev.lng !== loc?.lng || prev.lat !== loc?.lat
    if (!moved || !loc || loc.lng == null || loc.lat == null) return
    // 逆地理编码可能失败（address 为 undefined），此时回填坐标作为可读文本，保证输入框有变化。
    const text = loc.address || `${loc.lng.toFixed(6)}, ${loc.lat.toFixed(6)}`
    if (text !== form.locationText) {
      locationSuggestion.value = ''
      // 记录回显文本，让随后可能触发的 @input 识别为「程序写入」而不是用户输入。
      lastProgrammaticText = text
      form.locationText = text
    }
  },
  // 关键：同步触发。geocodeText 里 suppressWatchBackfill=true → 赋值 location → 回调立即执行守卫生效；
  // 若默认 flush:'pre'（异步），等回调跑时守卫已被置回 false，回填照样发生 → 直接补全无选择。
  { flush: 'sync' },
)

// —— 时间/人数校验：与后端 VolunteerActivityService + TimeUtil 同口径（前后端双向校验） ——

/**
 * 12:00 规则：「开始时间」（活动开始、报名开始都算）允许的最早时刻。
 * 12:00 之前创建 → 今天零点；12:00 及之后 → 明天零点。正好 12:00 整按「之后」处理，
 * 与后端 TimeUtil.earliestAllowedStart 保持一字不差的口径。
 */
function earliestAllowedStart(now: Date = new Date()): Date {
  const day = new Date(now)
  day.setHours(0, 0, 0, 0)
  if (now.getHours() < 12) return day
  const next = new Date(day)
  next.setDate(next.getDate() + 1)
  return next
}

/** 开始时间选择器里禁用早于下限的整天（下限随创建时刻跨过 12:00 动态变化）。 */
function disableBeforeEarliest(date: Date): boolean {
  return date.getTime() < earliestAllowedStart().getTime()
}

/** 今天零点（用于判断下限是「今天」还是「明天」，拼进提示文案）。 */
function startOfToday(): Date {
  const day = new Date()
  day.setHours(0, 0, 0, 0)
  return day
}

/** 与后端错误提示一致的 yyyy-MM-dd HH:mm:ss 展示。 */
function formatDateTime(value: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${value.getFullYear()}-${p(value.getMonth() + 1)}-${p(value.getDate())} `
    + `${p(value.getHours())}:${p(value.getMinutes())}:${p(value.getSeconds())}`
}

async function submit() {
  if (!form.title || !form.description || !form.locationText) {
    return ElMessage.warning('请填写活动名、志愿者要求与活动地点')
  }
  if (!form.startsAt || !form.endsAt || !form.registrationStartsAt || !form.registrationEndsAt) {
    return ElMessage.warning('请填写完整的活动与报名时间')
  }
  // 人数必须是 > 0 的数字（清空后的 null 也要拦下；与后端 @Min(1) + 服务端兜底同口径）。
  // 提取成局部变量：守卫的类型收窄能带进 payload（对 form 整体展开时属性收窄会丢失）。
  const maxVolunteers = form.maxVolunteers
  if (maxVolunteers == null || !Number.isFinite(maxVolunteers) || maxVolunteers < 1) {
    return ElMessage.warning('需要的志愿者人数必须是大于 0 的数字')
  }

  const startsAt = new Date(form.startsAt).getTime()
  const endsAt = new Date(form.endsAt).getTime()
  const regStart = new Date(form.registrationStartsAt).getTime()
  const regEnd = new Date(form.registrationEndsAt).getTime()

  // 12:00 规则：两个「开始时间」都不早于今天（中午前创建）/ 明天（中午后创建）零点。
  const floor = earliestAllowedStart()
  const floorDesc = floor.getTime() === startOfToday().getTime() ? '今天' : '明天'
  const floorText = `${floorDesc}（${formatDateTime(floor)}）`
  if (startsAt < floor.getTime()) {
    return ElMessage.warning(`活动开始时间不能早于${floorText}`)
  }
  if (regStart < floor.getTime()) {
    return ElMessage.warning(`报名开始时间不能早于${floorText}`)
  }
  if (startsAt <= Date.now()) {
    return ElMessage.warning('活动开始时间必须晚于当前时间')
  }
  // 「不能早于」允许相等：结束时间只要不早于开始时间即可（与后端一致）。
  if (endsAt < startsAt) {
    return ElMessage.warning('活动结束时间不能早于开始时间')
  }
  if (regStart > startsAt) {
    return ElMessage.warning('报名开始时间不能晚于活动开始时间')
  }
  if (regEnd < regStart) {
    return ElMessage.warning('报名结束时间不能早于报名开始时间')
  }
  if (regEnd > startsAt) {
    return ElMessage.warning('报名截止时间不能晚于活动开始时间')
  }
  if (location.value.lng != null && location.value.lat == null) {
    return ElMessage.warning('经纬度必须同时填写或同时留空')
  }

  const payload: ActivityCreateRequest = {
    ...form,
    maxVolunteers,
    locationLng: location.value.lng,
    locationLat: location.value.lat,
  }
  loading.value = true
  try {
    const res = await createActivity(payload)
    ElMessage.success(`活动已发布：${res.title}`)
    keepFormRef.value?.clearCache()
    router.push('/activities')
  } catch (e: any) {
    ElMessage.error(errorMessage(e, '发布失败'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.location-combo {
  position: relative;
  width: 100%;
}
/* 半透明补全提示：紧跟输入文本之后，显示建议地址的剩余部分，可 Tab 补全。 */
.location-hint {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ink-3, #999);
  opacity: 0.6;
  font-size: 14px;
  line-height: 1;
  pointer-events: none;
  white-space: pre;
  max-width: calc(100% - 24px);
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 完整建议行：半透明补全被裁掉时的兜底，也承载「Tab / Esc 怎么用」的说明。 */
.location-tip {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}
.location-tip-addr {
  color: var(--ink-2);
  word-break: break-all;
}
.location-tip-key {
  color: var(--ink-4);
}
.location-tip kbd {
  display: inline-block;
  padding: 0 4px;
  border: 1px solid var(--line-strong);
  border-bottom-width: 2px;
  border-radius: 4px;
  background: var(--paper-tint);
  font-family: var(--font-code);
  font-size: 11px;
  color: var(--ink-2);
}

.review-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.review-hint {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
  max-width: 480px;
}
</style>
