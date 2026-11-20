<template>
  <div class="runs-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>运行结果</span>
          <el-button type="primary" size="small" @click="runDialog = true">运行指标</el-button>
        </div>
      </template>

      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>
          每个运行结果都固定“用途 + 口径版本 + 归属修订”。未选择用途的请求会被拒绝（不默认为最近使用分支）；
          财务发生与运营订单不可互换，也不会被当成可比序列。
        </template>
      </el-alert>

      <el-table :data="runs" stripe style="width: 100%" @row-click="selectRun">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="metricKey" label="指标" width="200" />
        <el-table-column label="口径身份" width="170">
          <template #default="{ row }">
            <el-tag v-if="row.purpose" size="small" :type="row.purpose === 'financial' ? 'success' : row.purpose === 'order_batch' ? 'warning' : 'info'">
              {{ row.purposeLabel || row.purpose }}
            </el-tag>
            <span v-else>无用途</span>
          </template>
        </el-table-column>
        <el-table-column prop="grain" label="粒度" width="80" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="适用用户" width="130">
          <template #default="{ row }">{{ row.applicableUsers || '-' }}</template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
      </el-table>

      <el-card v-if="selectedRun" class="run-detail" shadow="never">
        <template #header>
          <span>运行详情 (ID: {{ selectedRun.id }})</span>
        </template>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="指标">{{ selectedRun.metricKey }}</el-descriptions-item>
          <el-descriptions-item label="展示口径">
            {{ selectedRun.purposeLabel || '无用途' }}
          </el-descriptions-item>
          <el-descriptions-item label="适用用户">{{ selectedRun.applicableUsers || '-' }}</el-descriptions-item>
          <el-descriptions-item label="引用身份" :span="2">
            <code>{{ selectedRun.referenceIdentity || '-' }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="归属修订">
            {{ selectedRun.attributionRevision == null ? '-' : 'attrRev@' + selectedRun.attributionRevision }}
          </el-descriptions-item>
          <el-descriptions-item label="数据截止点" :span="3">
            {{ formatCutoff(selectedRun.dataCutoffJson) }}
          </el-descriptions-item>
          <el-descriptions-item label="待归属" :span="3">
            <span v-if="pendingInfo">
              金额 {{ pendingInfo.pendingAmount }}，退款 {{ pendingInfo.pendingRefunds }} 笔（不进入任何期间金额，不按同名商品/当前价格猜填）
            </span>
            <span v-else>无</span>
          </el-descriptions-item>
        </el-descriptions>

        <div style="margin: 12px 0">
          <el-button size="small" @click="replay">按原输入复演</el-button>
          <span v-if="replayResult" class="replay-result">
            复演运行 {{ replayResult.replayedRunId }}：{{ replayResult.identical ? '与原运行一致' : '不一致' }}
          </span>
        </div>

        <el-table :data="cells" stripe size="small">
          <el-table-column prop="periodKey" label="期间" width="110" />
          <el-table-column prop="dimsJson" label="维度" width="140">
            <template #default="{ row }">{{ formatDims(row.dimsJson) }}</template>
          </el-table-column>
          <el-table-column prop="valueNum" label="分子" width="110" />
          <el-table-column prop="valueDen" label="分母" width="110" />
          <el-table-column prop="value" label="值" width="110">
            <template #default="{ row }">{{ formatCellValue(row) }}</template>
          </el-table-column>
          <el-table-column prop="cellStatus" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="cellStatusType(row.cellStatus)" size="small">{{ row.cellStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" @click="drillDown(row)">下钻</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-dialog v-model="drillVisible" title="单元格下钻" width="820px">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="期间">{{ drillCell?.periodKey }}</el-descriptions-item>
          <el-descriptions-item label="维度">{{ formatDims(drillCell?.dimsJson) }}</el-descriptions-item>
          <el-descriptions-item label="值">{{ drillCell?.valueNum }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ drillCell?.cellStatus }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>参与记录</el-divider>
        <el-table :data="lineage" stripe size="small">
          <el-table-column prop="recordType" label="类型" width="120" />
          <el-table-column prop="recordId" label="记录ID" width="130" />
          <el-table-column prop="contribution" label="贡献值" width="110" />
          <el-table-column prop="included" label="是否计入" width="100">
            <template #default="{ row }">
              <el-tag :type="row.included ? 'success' : 'info'" size="small">
                {{ row.included ? '计入' : '排除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" />
        </el-table>
        <el-divider>最终算式</el-divider>
        <div class="formula">{{ drillCell?.message || '无算式信息' }}</div>
      </el-dialog>

      <!-- 运行表单：用途必须显式选择 -->
      <el-dialog v-model="runDialog" title="运行指标" width="560px">
        <el-form :model="runForm" label-width="110px">
          <el-form-item label="指标">
            <el-select v-model="runForm.metricKey" placeholder="选择指标" style="width: 100%" @change="onMetricChange">
              <el-option v-for="m in metrics" :key="m.metricKey" :label="`${m.name} (${m.metricKey})`" :value="m.metricKey" />
            </el-select>
          </el-form-item>
          <el-form-item label="固定用途">
            <div>
              <code v-if="runForm.boundPurpose">该指标已固定用途：{{ runForm.boundPurpose }}</code>
              <span v-else-if="runForm.purposeRequired">该指标为用途模板（引用退款时间口径），必须选择用途</span>
              <span v-else>该指标不含退款时间口径，可不选用途</span>
            </div>
          </el-form-item>
          <el-form-item label="用途" :required="runForm.purposeRequired || !!runForm.boundPurpose">
            <el-radio-group v-model="runForm.purpose" :disabled="!!runForm.boundPurpose">
              <el-radio value="financial">财务发生（按入账日）</el-radio>
              <el-radio value="order_batch">运营订单（按原交易日）</el-radio>
              <el-radio v-if="runForm.isReconciliation" value="reconciliation">对账复算（仅校验）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="期间">
            <el-date-picker v-model="runForm.from" type="date" value-format="YYYY-MM-DD" placeholder="从" />
            <span style="margin: 0 6px">至</span>
            <el-date-picker v-model="runForm.to" type="date" value-format="YYYY-MM-DD" placeholder="到" />
          </el-form-item>
          <el-form-item label="粒度">
            <el-select v-model="runForm.grain">
              <el-option label="日" value="day" />
              <el-option label="月" value="month" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="runDialog = false">取消</el-button>
          <el-button type="primary"
                     :disabled="(runForm.purposeRequired || !!runForm.boundPurpose) && !runForm.purpose"
                     @click="submitRun">运行</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const runs = ref([])
const metrics = ref([])
const selectedRun = ref(null)
const cells = ref([])
const drillVisible = ref(false)
const drillCell = ref(null)
const lineage = ref([])
const replayResult = ref(null)
const pendingInfo = ref(null)
const runDialog = ref(false)
const runForm = ref({
  metricKey: '', purpose: '', boundPurpose: null, purposeRequired: false, isReconciliation: false,
  from: '2026-01-01', to: '2026-01-31', grain: 'month', metricVersionId: null
})

async function loadRuns() {
  try {
    const res = await api.get('/runs')
    runs.value = res.data
  } catch (e) {
    ElMessage.error('加载运行失败: ' + (e.response?.data?.error || e.message))
  }
}

async function loadMetrics() {
  try {
    const res = await api.get('/metrics')
    metrics.value = res.data
  } catch (e) {
    console.error(e)
  }
}

async function onMetricChange(key) {
  try {
    const res = await api.get(`/metrics/${key}/published`)
    const version = res.data
    runForm.value.metricVersionId = version.id
    runForm.value.boundPurpose = version.boundPurpose || null
    runForm.value.purposeRequired = !!version.purposeRequired
    runForm.value.isReconciliation = version.boundPurpose === 'reconciliation'
    if (version.boundPurpose && version.boundPurpose !== 'reconciliation') {
      runForm.value.purpose = version.boundPurpose
    } else if (version.boundPurpose === 'reconciliation') {
      runForm.value.purpose = 'reconciliation'
    } else {
      runForm.value.purpose = ''
    }
  } catch (e) {
    ElMessage.error('加载指标版本失败: ' + (e.response?.data?.error || e.message))
  }
}

async function submitRun() {
  if ((runForm.value.purposeRequired || runForm.value.boundPurpose) && !runForm.value.purpose) {
    ElMessage.warning('必须显式选择用途（财务发生 / 运营订单）')
    return
  }
  try {
    const res = await api.post('/runs', {
      metricKey: runForm.value.metricKey,
      metricVersionId: runForm.value.metricVersionId,
      from: runForm.value.from,
      to: runForm.value.to,
      grain: runForm.value.grain,
      purpose: runForm.value.purpose || null
    })
    ElMessage.success('运行完成：' + (res.data.referenceIdentity || '无用途'))
    runDialog.value = false
    loadRuns()
    selectRun(res.data)
  } catch (e) {
    ElMessage.error('运行失败: ' + (e.response?.data?.error || e.message))
  }
}

async function selectRun(row) {
  selectedRun.value = row
  replayResult.value = null
  pendingInfo.value = null
  try {
    const res = await api.get(`/runs/${row.id}/cells`)
    cells.value = res.data
    if (row.pendingJson) {
      try { pendingInfo.value = JSON.parse(row.pendingJson) } catch { pendingInfo.value = null }
    }
  } catch (e) {
    ElMessage.error('加载单元格失败: ' + (e.response?.data?.error || e.message))
  }
}

async function replay() {
  try {
    const res = await api.post(`/runs/${selectedRun.value.id}/replay`)
    replayResult.value = res.data
    ElMessage.success(res.data.identical ? '复演一致' : '复演结果不一致，请检查')
  } catch (e) {
    ElMessage.error('复演失败: ' + (e.response?.data?.error || e.message))
  }
}

async function drillDown(cell) {
  drillCell.value = cell
  drillVisible.value = true
  try {
    const dims = typeof cell.dimsJson === 'string' ? JSON.parse(cell.dimsJson) : (cell.dimsJson || {})
    const sorted = Object.keys(dims).sort().map(k => `|${k}=${dims[k]}`).join('')
    const cellKey = cell.periodKey + sorted
    const res = await api.get(`/runs/${selectedRun.value.id}/lineage`, { params: { cellKey } })
    lineage.value = res.data
  } catch (e) {
    ElMessage.error('加载血缘失败: ' + (e.response?.data?.error || e.message))
  }
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

function formatCutoff(cutoffJson) {
  if (!cutoffJson) return '-'
  try {
    const obj = typeof cutoffJson === 'string' ? JSON.parse(cutoffJson) : cutoffJson
    return JSON.stringify(obj.cutoffBySource || obj)
  } catch {
    return cutoffJson
  }
}

function formatDims(dimsJson) {
  if (!dimsJson) return '-'
  if (typeof dimsJson === 'string') {
    try { return JSON.stringify(JSON.parse(dimsJson)) } catch { return dimsJson }
  }
  return JSON.stringify(dimsJson)
}

function formatCellValue(cell) {
  if (cell.valueNum === null || cell.valueNum === undefined) return '-'
  if (cell.valueDen !== null && cell.valueDen !== undefined && cell.valueDen !== 0) {
    return (cell.valueNum / cell.valueDen).toFixed(4)
  }
  return cell.valueNum
}

function cellStatusType(status) {
  const map = { ok: 'success', not_covered: 'warning', div_zero: 'danger', no_data: 'info', partial: 'warning', excluded: 'info' }
  return map[status] || 'info'
}

function statusType(status) {
  const map = { success: 'success', failed: 'danger', partial: 'warning' }
  return map[status] || 'info'
}

onMounted(() => {
  loadRuns()
  loadMetrics()
})
</script>

<style scoped>
.runs-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.run-detail {
  margin-top: 20px;
}
.formula {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
  white-space: pre-wrap;
}
.replay-result {
  margin-left: 12px;
  color: #67c23a;
}
</style>
