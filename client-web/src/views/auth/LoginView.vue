<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2>Sullivan 公益服务平台</h2>
      <p class="role-tip">监护人 / 志愿者 / 公益机构 / 管理员</p>
      <el-form>
        <el-form-item label="账号">
          <el-input v-model="loginName" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const loginName = ref('')
const password = ref('')

function handleLogin() {
  // TODO(M2): 调用登录 API
  router.push('/guardian')
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: #f5f7fa;
}
.login-card {
  width: 400px;
}
</style>
