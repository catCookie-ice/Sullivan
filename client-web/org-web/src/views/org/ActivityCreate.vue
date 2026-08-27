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
                      @input="onLocationTextInput" @keydown.tab.prevent="onLocationTab" />
            <span v-if="locationHint" class="location-hint" :style="{ left: locationHintLeft }" aria-hidden="true">{{ locationHint }}</span>
          </div>
        </el-form-item>

        <el-form-item label="地图选点">
          <AmapPicker ref="amapPickerRef" v-model="location" style="width: 100%" />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="活动开始时间" required>
              <el-date-picker v-model="form.startsAt" type="datetime" placeholder="活动开始时间"
                              style="width: 100%" value-format="YYYY-MM-DD HH:mm:ss" />
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
                              style="width: 100%" value-format="YYYY-MM-DD HH:mm:ss" />
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
// 程序回填输入框时置位，抑制随后的 @input 触发（防止"地图→输入框"再编码死循环）。
let suppressLocationInput = false
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
// 提示文本的左偏移 = 输入框内边距 + 已输入文本的像素宽度（用 canvas 测量，中文按 1 字符宽）。
const locationHintLeft = computed(() => {
  const text = form.locationText.trim()
  if (!text) return '11px'
  const measure = document.createElement('canvas').getContext('2d')
  if (measure) {
    measure.font = '14px sans-serif'
    return `${11 + measure.measureText(text).width}px`
  }
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
  maxVolunteers: 10,
})

const location = ref<{ lng?: number; lat?: number; address?: string }>({})

// —— 活动地点输入框 ↔ 地图选点 双向联动 ——

/** 输入框 @input：防抖后对文本地理编码，命中则让地图跳转到该地点。 */
function onLocationTextInput() {
  // 程序回填（地图→输入框方向）抑制：不把回填当成用户输入去编码，避免死循环。
  if (suppressLocationInput) {
    suppressLocationInput = false
    return
  }
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
  suppressLocationInput = true
  form.locationText = sug
  locationSuggestion.value = ''
}

/** 地理编码：把地点文本转成经纬度，地图定位 + 放置标记。 */
function geocodeText(text: string) {
  const AMap = (window as any).AMap
  if (!AMap) return
  AMap.plugin('AMap.Geocoder', () => {
    const geocoder = new AMap.Geocoder({ city: '全国' })
    geocoder.getLocation(text, (status: string, result: any) => {
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
      suppressLocationInput = true
      locationSuggestion.value = ''
      form.locationText = text
    }
  },
  // 关键：同步触发。geocodeText 里 suppressWatchBackfill=true → 赋值 location → 回调立即执行守卫生效；
  // 若默认 flush:'pre'（异步），等回调跑时守卫已被置回 false，回填照样发生 → 直接补全无选择。
  { flush: 'sync' },
)

async function submit() {
  if (!form.title || !form.description || !form.locationText) {
    return ElMessage.warning('请填写活动名、志愿者要求与活动地点')
  }
  if (!form.startsAt || !form.endsAt || !form.registrationStartsAt || !form.registrationEndsAt) {
    return ElMessage.warning('请填写完整的活动与报名时间')
  }
  if (new Date(form.startsAt).getTime() <= Date.now()) {
    return ElMessage.warning('活动开始时间必须晚于当前时间')
  }
  if (new Date(form.startsAt).getTime() >= new Date(form.endsAt).getTime()) {
    return ElMessage.warning('活动结束时间必须晚于开始时间')
  }
  if (
    new Date(form.registrationStartsAt).getTime() > new Date(form.registrationEndsAt).getTime() ||
    new Date(form.registrationEndsAt).getTime() > new Date(form.startsAt).getTime()
  ) {
    return ElMessage.warning('报名窗口必须满足：报名开始 <= 报名结束 <= 活动开始')
  }
  if (location.value.lng != null && location.value.lat == null) {
    return ElMessage.warning('经纬度必须同时填写或同时留空')
  }

  const payload: ActivityCreateRequest = {
    ...form,
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
    ElMessage.error(e.response?.data?.error || '发布失败')
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
</style>
