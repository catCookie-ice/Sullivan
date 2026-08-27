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
          <el-input v-model="form.locationText" maxlength="300" placeholder="地点文本，如：朝阳公园南门"
                    @input="onLocationTextInput" />
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
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createActivity } from '@/api/org'
import AmapPicker from '@/components/AmapPicker.vue'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'
import type { ActivityCreateRequest } from '@/types/api'

const router = useRouter()
const loading = ref(false)
const keepFormRef = ref<{ clearCache: () => void } | null>(null)
const amapPickerRef = ref<{ map: any; setLocation: (lng: number, lat: number, address?: string) => void } | null>(null)

// 输入框 → 地图：防抖 300ms，避免每次按键都地理编码。
const locationInputTimer = ref<ReturnType<typeof setTimeout> | null>(null)
// 程序回填输入框时置位，抑制随后的 @input 触发（防止"地图→输入框"再编码死循环）。
let suppressLocationInput = false

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
  if (!text) return
  if (locationInputTimer.value) clearTimeout(locationInputTimer.value)
  locationInputTimer.value = setTimeout(() => geocodeText(text), 300)
}

/** 地理编码：把地点文本转成经纬度，地图定位 + 放置标记。 */
function geocodeText(text: string) {
  const AMap = (window as any).AMap
  if (!AMap) return
  AMap.plugin('AMap.Geocoder', () => {
    const geocoder = new AMap.Geocoder({ city: '全国' })
    geocoder.getLocation(text, (status: string, result: any) => {
      if (status !== 'complete' || !result?.geocodes?.length) {
        // 未命中不强行改 location，避免误清空已选点。
        return ElMessage.info(`未找到地点：「${text}」`)
      }
      const g = result.geocodes[0]
      const lng = g.location?.lng
      const lat = g.location?.lat
      if (lng == null || lat == null) return
      // 只定位地图+标记，不 emit（避免反向覆盖输入框正在编辑的文本）。
      // address 仍记录到 location，供提交时携带完整信息。
      amapPickerRef.value?.setLocation(lng, lat)
      location.value = { lng, lat, address: g.formattedAddress || text }
    })
  })
}

/** 地图选点 → 输入框：AmapPicker 选点/拖拽后更新 location，把 address 写回 locationText。 */
watch(
  () => location.value?.address,
  (addr) => {
    if (addr && addr !== form.locationText) {
      suppressLocationInput = true
      form.locationText = addr
    }
  },
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
