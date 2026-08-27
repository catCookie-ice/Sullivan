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
          <el-input v-model="form.locationText" maxlength="300" placeholder="地点文本，如：朝阳公园南门" />
        </el-form-item>

        <el-form-item label="地图选点">
          <AmapPicker v-model="location" style="width: 100%" />
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
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createActivity } from '@/api/org'
import AmapPicker from '@/components/AmapPicker.vue'
import KeepAutoForm from '@shared/keep-auto/KeepAutoForm.vue'
import type { ActivityCreateRequest } from '@/types/api'

const router = useRouter()
const loading = ref(false)
const keepFormRef = ref<{ clearCache: () => void } | null>(null)

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
