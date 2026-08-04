<template>
  <div class="binding-list">
    <h2>监护绑定管理 (FR-04, FR-05)</h2>
    <el-button type="primary" @click="showCreateDialog">发起绑定</el-button>
    <el-table :data="bindings" v-loading="loading">
      <el-table-column prop="protecteeUid" label="受护人" />
      <el-table-column prop="status" label="状态" />
      <el-table-column prop="createdAt" label="发起时间" />
      <el-table-column label="操作">
        <template #default="scope">
          <el-button size="small" v-if="scope.row.status === 'PENDING'"
                     @click="cancel(scope.row)">取消</el-button>
          <el-button size="small" v-if="scope.row.status === 'ACTIVE'"
                     @click="terminate(scope.row)">解除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const bindings = ref([])
const loading = ref(false)

function showCreateDialog() {}
function cancel(row: any) {}
function terminate(row: any) {}
</script>
