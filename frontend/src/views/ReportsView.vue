<template>
  <div class="reports-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>报表中心</span>
          <el-button type="primary" size="small" @click="createReport">新建报表</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>
          同一仪表盘不能把“财务发生”和“运营订单”当成可比序列：加入不同用途的报表项会被拒绝。
          每个报表项都显示用途、适用用户与引用身份。
        </template>
      </el-alert>

      <el-table :data="reports" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="报表名称" width="180" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="270">
          <template #default="{ row }">
            <el-button size="small" @click="viewReport(row)">查看</el-button>
            <el-button v-if="row.status === 'draft'" size="small" type="primary" @click="openAddItem(row)">加入运行</el-button>
            <el-button v-if="row.status === 'draft'" size="small" type="success" @click="publishReport(row)">发布</el-button>
            <el-button v-if="row.status === 'published'" size="small" type="warning" @click="openCorrect(row)">更正</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-card v-if="selectedReport" class="report-detail" shadow="never">
        <template #header>
          <span>报表详情: {{ selectedReport.name }}</span>
        </template>
        <el-table :data="reportItems" stripe size="small">
          <el-table-column prop="metricKey" label="指标" width="180" />
          <el-table-column label="展示口径" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="row.purpose === 'financial' ? 'success' : row.purpose === 'order_batch' ? 'warning' : 'info'">
                {{ row.purposeLabel || row.purpose || '无用途' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="applicableUsers" label="适用用户" width="130" />
          <el-table-column label="引用身份" min-width="260">
            <template #default="{ row }"><code>{{ row.referenceIdentity || '-' }}</code></template>
          </el-table-column>
          <el-table-column prop="runId" label="运行ID" width="90" />
        </el-table>
      </el-card>
    </el-card>

    <el-dialog v-model="addItemVisible" title="加入运行结果" width="560px">
      <el-form :model="addItemForm" label-width="100px">
        <el-form-item label="运行ID">
          <el-input-number v-model="addItemForm.runId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="指标版本ID">
          <el-input-number v-model="addItemForm.metricVersionId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="指标键">
          <el-input v-model="addItemForm.metricKey" placeholder="如 net_sales_financial" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addItemVisible = false">取消</el-button>
        <el-button type="primary" @click="saveItem">加入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="correctVisible" title="报表更正（同用途、同口径复演）" width="560px">
      <el-form :model="correctForm" label-width="120px">
        <el-form-item label="指标键">
          <el-input v-model="correctForm.metricKey" />
        </el-form-item>
        <el-form-item label="用途">
          <el-input v-model="correctForm.purpose" disabled />
        </el-form-item>
        <el-form-item label="新运行ID">
          <el-input-number v-model="correctForm.newRunId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="期望口径ID">
          <el-input v-model="correctForm.expectedSemanticsId" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="correctVisible = false">取消</el-button>
        <el-button type="primary" @click="saveCorrect">更正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/client'

const reports = ref([])
const selectedReport = ref(null)
const reportItems = ref([])
const addItemVisible = ref(false)
const addItemForm = ref({ runId: 1, metricVersionId: 1, metricKey: '' })
const correctVisible = ref(false)
const correctForm = ref({ metricKey: '', purpose: '', newRunId: 1, expectedSemanticsId: '' })

async function loadReports() {
  try {
    const res = await api.get('/reports')
    reports.value = res.data
  } catch (e) {
    ElMessage.error('加载报表失败: ' + (e.response?.data?.error || e.message))
  }
}

async function createReport() {
  try {
    const { value: name } = await ElMessageBox.prompt('请输入报表名称', '新建报表', {
      confirmButtonText: '确定', cancelButtonText: '取消'
    })
    await api.post('/reports', { name })
    ElMessage.success('报表创建成功')
    loadReports()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('创建失败: ' + (e.response?.data?.error || e.message))
  }
}

async function viewReport(row) {
  selectedReport.value = row
  try {
    const res = await api.get(`/reports/${row.id}/items`)
    reportItems.value = res.data
  } catch (e) {
    ElMessage.error('加载报表项失败: ' + (e.response?.data?.error || e.message))
  }
}

function openAddItem(row) {
  selectedReport.value = row
  addItemForm.value = { runId: 1, metricVersionId: 1, metricKey: '' }
  addItemVisible.value = true
}

async function saveItem() {
  try {
    await api.post(`/reports/${selectedReport.value.id}/items`, addItemForm.value)
    ElMessage.success('已加入')
    addItemVisible.value = false
    viewReport(selectedReport.value)
  } catch (e) {
    ElMessage.error('加入失败: ' + (e.response?.data?.error || e.message))
  }
}

async function publishReport(row) {
  try {
    await ElMessageBox.confirm('发布后将锁定用途与口径引用身份，确定发布？', '发布确认', {
      confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
    })
    await api.post(`/reports/${row.id}/publish`)
    ElMessage.success('报表发布成功')
    loadReports()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('发布失败: ' + (e.response?.data?.error || e.message))
  }
}

function openCorrect(row) {
  selectedReport.value = row
  const first = reportItems.value[0] || {}
  correctForm.value = {
    metricKey: first.metricKey || '',
    purpose: first.purpose || '',
    newRunId: 1,
    expectedSemanticsId: first.semanticsId || ''
  }
  correctVisible.value = true
}

async function saveCorrect() {
  try {
    await api.post(`/reports/${selectedReport.value.id}/correct`, {
      metricKey: correctForm.value.metricKey,
      purpose: correctForm.value.purpose,
      newRunId: correctForm.value.newRunId,
      expectedSemanticsId: correctForm.value.expectedSemanticsId
    })
    ElMessage.success('更正成功')
    correctVisible.value = false
    viewReport(selectedReport.value)
  } catch (e) {
    ElMessage.error('更正失败: ' + (e.response?.data?.error || e.message))
  }
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

function statusType(status) {
  const map = { draft: 'info', published: 'success', archived: 'warning' }
  return map[status] || 'info'
}

onMounted(() => {
  loadReports()
})
</script>

<style scoped>
.reports-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.report-detail {
  margin-top: 20px;
}
</style>
