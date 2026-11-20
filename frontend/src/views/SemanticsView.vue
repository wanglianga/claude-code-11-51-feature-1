<template>
  <div class="semantics-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>退款时间口径与用途</span>
          <el-button size="small" @click="loadAll">刷新</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          同一笔跨月部分退款，两种用途都正确但不可互换：财务发生（financial）按实际入账日进入退款发生期间；
          运营订单（order_batch）按原交易日追溯、只修订对应订单批次。无法可靠关联原订单行的退款列为待归属，
          不按同名商品或当前价格猜填。
        </template>
      </el-alert>

      <el-descriptions :column="4" border size="small" style="margin-top: 16px">
        <el-descriptions-item label="口径编码">{{ semantics.code }}</el-descriptions-item>
        <el-descriptions-item label="发布版本">{{ publishedRef || '-' }}</el-descriptions-item>
        <el-descriptions-item label="归属修订">{{ revision == null ? '-' : 'attrRev@' + revision }}</el-descriptions-item>
        <el-descriptions-item label="守恒校验">
          <el-tag :type="conservation && conservation.allBalanced ? 'success' : 'danger'" size="small">
            {{ conservation && conservation.allBalanced ? '订单=订单行、退款=财务口径=运营+待归属' : '未平衡' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <!-- 用途档案 -->
      <el-table :data="purposes" stripe size="small" style="margin-top: 16px">
        <el-table-column prop="purpose" label="用途" width="140" />
        <el-table-column prop="label" label="展示口径" width="180" />
        <el-table-column prop="applicableUsers" label="适用用户" width="200" />
        <el-table-column label="引用身份" min-width="280">
          <template #default="{ row }">
            <code>{{ row.purpose }}/{{ publishedRef }}/profile@v{{ row.profileVersion }}/attrRev@{{ revision }}</code>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button v-if="row.purpose !== 'reconciliation'" size="small" @click="openEdit(row)">修改适用用户</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 期间归属对照 -->
      <el-divider content-position="left">期间归属对照（跨月部分退款：R101 O008 原交易日 1/28，实际入账 2/3）</el-divider>
      <el-form :inline="true" :model="periodForm">
        <el-form-item label="从">
          <el-date-picker v-model="periodForm.from" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="到">
          <el-date-picker v-model="periodForm.to" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" @click="loadComparison">对照</el-button>
        </el-form-item>
      </el-form>

      <el-row :gutter="20" v-if="comparison">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <span>财务发生（financial） · 按入账日</span>
            </template>
            <div class="period-amount">{{ amountOf('financial') }}</div>
            <el-table :data="comparison.financial.rows" size="small" stripe>
              <el-table-column prop="refundId" label="退款" width="90" />
              <el-table-column prop="orderLineId" label="原订单行" width="110" />
              <el-table-column prop="postingDate" label="入账日" width="110" />
              <el-table-column prop="originalBusinessDate" label="原交易日" width="110" />
              <el-table-column prop="amountSigned" label="金额" width="90" />
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <span>运营订单（order_batch） · 按原交易日</span>
            </template>
            <div class="period-amount">{{ amountOf('order_batch') }}</div>
            <el-table :data="comparison.order_batch.rows" size="small" stripe>
              <el-table-column prop="refundId" label="退款" width="90" />
              <el-table-column prop="orderLineId" label="原订单行" width="110" />
              <el-table-column prop="postingDate" label="入账日" width="110" />
              <el-table-column prop="originalBusinessDate" label="原交易日" width="110" />
              <el-table-column prop="amountSigned" label="金额" width="90" />
            </el-table>
          </el-card>
        </el-col>
      </el-row>

      <!-- 待归属 -->
      <el-divider content-position="left">待归属退款（不进入任何期间金额）</el-divider>
      <el-table :data="pendingRows" stripe size="small">
        <el-table-column prop="refundId" label="退款" width="100" />
        <el-table-column prop="orderId" label="订单" width="100" />
        <el-table-column prop="sku" label="商品" width="100" />
        <el-table-column prop="postingDate" label="入账日" width="120" />
        <el-table-column prop="amountSigned" label="金额" width="100" />
        <el-table-column prop="reason" label="原因" />
      </el-table>

      <!-- 守恒 -->
      <el-divider content-position="left">订单与退款事实守恒</el-divider>
      <el-descriptions :column="4" border size="small" v-if="conservation">
        <el-descriptions-item label="退款事实合计">{{ conservation.refundFactsTotal }}</el-descriptions-item>
        <el-descriptions-item label="财务口径（含待归属）">{{ conservation.financialTotal }}</el-descriptions-item>
        <el-descriptions-item label="运营归属">{{ conservation.orderBatchAttributed }}</el-descriptions-item>
        <el-descriptions-item label="待归属">{{ conservation.pendingTotal }}</el-descriptions-item>
        <el-descriptions-item label="订单=订单行" :span="2">
          {{ conservation.orderAmountMismatches && conservation.orderAmountMismatches.length ? '存在不一致' : '一致' }}
        </el-descriptions-item>
        <el-descriptions-item label="同商品不同价（不据此回填）" :span="2">
          <el-tag v-for="s in conservation.sameSkuDifferentPrice || []" :key="s.sku" size="small" style="margin-right: 6px">
            {{ s.sku }}: {{ s.unitPrices.join(' / ') }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-dialog v-model="editVisible" title="修改用途适用用户" width="480px">
      <el-form label-width="100px">
        <el-form-item label="用途">{{ editForm.purpose }}</el-form-item>
        <el-form-item label="适用用户">
          <el-input v-model="editForm.applicableUsers" />
        </el-form-item>
        <el-form-item label="profile 版本">
          <el-input v-model="editForm.profileVersion" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存（乐观并发）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const semantics = ref({ code: 'REFUND_TIME' })
const publishedRef = ref('')
const revision = ref(null)
const purposes = ref([])
const pendingRows = ref([])
const conservation = ref(null)
const comparison = ref(null)
const periodForm = ref({ from: '2026-01-01', to: '2026-01-31' })
const editVisible = ref(false)
const editForm = ref({ purpose: '', applicableUsers: '', profileVersion: 1 })

async function loadAll() {
  try {
    const semRes = await api.get('/refund-semantics')
    semantics.value = semRes.data
    if (semRes.data.published) {
      publishedRef.value = semRes.data.published.ref
      revision.value = semRes.data.published.revision
    }
    const [p, pending, cons] = await Promise.all([
      api.get('/refund-semantics/purposes'),
      api.get('/refund-semantics/pending'),
      api.get('/refund-semantics/conservation')
    ])
    purposes.value = p.data
    pendingRows.value = pending.data.rows || []
    conservation.value = cons.data
  } catch (e) {
    ElMessage.error('加载口径失败: ' + (e.response?.data?.error || e.message))
  }
}

async function loadComparison() {
  try {
    const res = await api.get('/refund-semantics/period-comparison', {
      params: { from: periodForm.value.from, to: periodForm.value.to }
    })
    comparison.value = res.data
  } catch (e) {
    ElMessage.error('对照失败: ' + (e.response?.data?.error || e.message))
  }
}

function amountOf(purpose) {
  if (!comparison.value) return '-'
  return comparison.value[purpose]?.amount ?? '-'
}

function openEdit(row) {
  editForm.value = {
    purpose: row.purpose,
    applicableUsers: row.applicableUsers,
    profileVersion: row.profileVersion
  }
  editVisible.value = true
}

async function saveEdit() {
  try {
    await api.put(`/refund-semantics/purposes/${editForm.value.purpose}`, {
      expectedProfileVersion: editForm.value.profileVersion,
      applicableUsers: editForm.value.applicableUsers
    })
    ElMessage.success('已更新（profile 版本递增）')
    editVisible.value = false
    loadAll()
  } catch (e) {
    ElMessage.error('更新失败: ' + (e.response?.data?.error || e.message))
  }
}

onMounted(() => {
  loadAll()
  loadComparison()
})
</script>

<style scoped>
.semantics-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.period-amount {
  font-size: 24px;
  font-weight: bold;
  color: #409eff;
  margin-bottom: 8px;
}
</style>
