<!--
  工单列表：后端 GET /api/workorders 返回全量列表（暂无分页/筛选参数），
  因此按状态、关键字筛选与分页在前端完成；数据量增大后需后端补分页接口。
-->
<template>
  <div class="page">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        class="kw"
        size="large"
        clearable
        placeholder="工单号 / 产品 / 设备"
      />
      <el-select
        v-model="status"
        class="st"
        size="large"
        clearable
        placeholder="全部状态"
      >
        <el-option
          v-for="(label, value) in STATUS_LABELS"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-button
        class="touch"
        size="large"
        :loading="loading"
        @click="load"
      >
        刷新
      </el-button>
      <el-button
        v-if="canWrite"
        class="touch btn-create"
        size="large"
        type="primary"
        @click="openCreate"
      >
        新建工单
      </el-button>
    </div>
    <el-dialog
      v-model="createVisible"
      title="新建工单"
      width="min(480px, 92vw)"
    >
      <el-form label-position="top">
        <el-form-item
          label="设备"
          :error="cp.fieldErrors.value.deviceId"
        >
          <el-input
            v-model="createForm.deviceId"
            class="c-device"
            size="large"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item
          label="产品"
          :error="cp.fieldErrors.value.product"
        >
          <el-input
            v-model="createForm.product"
            class="c-product"
            size="large"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item
          label="计划数量"
          :error="cp.fieldErrors.value.quantity"
        >
          <el-input
            v-model="createForm.quantity"
            class="c-qty"
            size="large"
            inputmode="numeric"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="cp.error.value"
        class="c-err"
        :title="cp.error.value"
        type="error"
        :closable="false"
      />
      <template #footer>
        <el-button
          class="touch"
          @click="createVisible = false"
        >
          取消
        </el-button>
        <el-button
          class="touch c-submit"
          type="primary"
          :loading="creating"
          @click="submitCreate"
        >
          创建
        </el-button>
      </template>
    </el-dialog>
    <el-result
      v-if="forbidden"
      class="forbidden"
      icon="warning"
      title="无权限"
      sub-title="当前账号没有查看工单的权限"
    />
    <el-alert
      v-else-if="error"
      class="err"
      :title="error"
      type="error"
      :closable="false"
    />
    <el-table
      v-loading="loading"
      :data="paged"
      row-class-name="row"
      empty-text="暂无工单"
      @row-click="open"
    >
      <el-table-column
        prop="id"
        label="工单号"
        min-width="160"
      />
      <el-table-column
        prop="product"
        label="产品"
        min-width="120"
      />
      <el-table-column
        prop="quantity"
        label="计划数"
        width="100"
      />
      <el-table-column
        prop="deviceId"
        label="设备"
        min-width="110"
      />
      <el-table-column
        label="状态"
        width="110"
      >
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      class="pager"
      layout="total, prev, pager, next"
      :page-size="pageSize"
      :total="filtered.length"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createWorkOrder, listWorkOrders, newIdempotencyKey, type WorkOrder } from '@/api/workorders'
import { useAuthStore } from '@/stores/auth'
import { PERM_WORKORDER_WRITE } from '@/utils/permissions'
import { STATUS_LABELS, statusLabel, statusTag } from '@/utils/workorder'
import { useProblem } from '@/composables/useProblem'

const router = useRouter()
const rows = ref<WorkOrder[]>([])
const loading = ref(false)
const keyword = ref('')
const status = ref('')
const page = ref(1)
const pageSize = 10
const { error, forbidden, capture, reset } = useProblem()

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((r) =>
    (!status.value || r.status === status.value) &&
    (!kw || [r.id, r.product, r.deviceId].some((f) => (f ?? '').toLowerCase().includes(kw))))
})
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
// 筛选条件变化后回到第一页，避免停留在已不存在的页码
watch([keyword, status], () => { page.value = 1 })

async function load() {
  loading.value = true
  reset()
  try {
    rows.value = await listWorkOrders()
  } catch (e) {
    capture(e)
  } finally {
    loading.value = false
  }
}

const auth = useAuthStore()
const canWrite = computed(() => auth.can(PERM_WORKORDER_WRITE))
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ deviceId: '', product: '', quantity: '' })
const cp = useProblem()
// 创建幂等键：打开对话框时生成，失败重试复用，成功后作废
let createKey = ''

function openCreate() {
  createForm.deviceId = ''; createForm.product = ''; createForm.quantity = ''
  cp.reset()
  createKey = newIdempotencyKey()
  createVisible.value = true
}

/** 校验与后端 CreateWorkOrder 一致：设备、产品必填，数量 1–1000000 的整数。 */
async function submitCreate() {
  if (creating.value) return
  cp.reset()
  const qty = Number(createForm.quantity)
  const errs: Record<string, string> = {}
  if (!createForm.deviceId.trim()) errs.deviceId = '请输入设备'
  if (!createForm.product.trim()) errs.product = '请输入产品'
  if (!/^\d+$/.test(createForm.quantity.trim()) || qty < 1 || qty > 1000000) errs.quantity = '请输入 1–1000000 的整数'
  if (Object.keys(errs).length) { cp.fieldErrors.value = errs; return }
  creating.value = true
  try {
    const wo = await createWorkOrder({ deviceId: createForm.deviceId.trim(), product: createForm.product.trim(), quantity: qty }, createKey)
    createVisible.value = false
    await load()
    open(wo)
  } catch (e) {
    cp.capture(e)
  } finally {
    creating.value = false
  }
}

function open(row: WorkOrder) {
  router.push({ name: 'workorder-detail', params: { id: row.id } })
}

onMounted(load)
defineExpose({ keyword, status, page, filtered, paged, open, openCreate, submitCreate, createForm })
</script>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.kw { flex: 1 1 240px; }
.st { width: 180px; }
.touch { min-height: 48px; min-width: 96px; }
.err { margin-bottom: 12px; }
.pager { margin-top: 12px; justify-content: flex-end; }
/* 行高加大，方便平板上点击进入详情 */
:deep(.row td) { height: 56px; cursor: pointer; }
</style>
