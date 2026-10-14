<!--
  工单列表：服务端分页与过滤（#70 GET /api/workorders?page&size&status&keyword，page 从 0 开始，size ≤ 100）。
  筛选条件与页码同步到 URL 查询参数（?page=1&status=...&keyword=...，URL 中页码从 1 开始），刷新或分享链接可还原。
  关键字在回车、点击查询或清空时生效，避免每次按键都请求后端。
-->
<template>
  <div class="page">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        class="kw"
        size="large"
        clearable
        placeholder="工单号 / 产品"
        @keyup.enter="applyKeyword"
        @clear="applyKeyword"
      />
      <el-select
        v-model="status"
        class="st"
        size="large"
        clearable
        placeholder="全部状态"
        @change="applyStatus"
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
        @click="applyKeyword"
      >
        查询
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
      :data="rows"
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
      class="pager"
      layout="total, prev, pager, next"
      :current-page="page"
      :page-size="pageSize"
      :total="total"
      @current-change="goPage"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'
import { createWorkOrder, listWorkOrders, newIdempotencyKey, type WorkOrder } from '@/api/workorders'
import { useAuthStore } from '@/stores/auth'
import { PERM_WORKORDER_WRITE } from '@/utils/permissions'
import { STATUS_LABELS, statusLabel, statusTag } from '@/utils/workorder'
import { useProblem } from '@/composables/useProblem'

const route = useRoute()
const router = useRouter()
const rows = ref<WorkOrder[]>([])
const total = ref(0)
const loading = ref(false)
// 输入框里的关键字（未提交）；生效的筛选条件以 URL 为准
const keyword = ref('')
const status = ref('')
const page = ref(1)
const pageSize = 10
const { error, forbidden, capture, reset } = useProblem()

const str = (v: unknown) => (typeof v === 'string' ? v : '')
/** 从 URL 解析筛选条件；非法页码回退到第 1 页，未知状态忽略。 */
function parseQuery(q: LocationQuery) {
  const p = Number(str(q.page))
  const st = str(q.status)
  return {
    page: Number.isInteger(p) && p >= 1 ? p : 1,
    status: st in STATUS_LABELS ? st : '',
    keyword: str(q.keyword).trim()
  }
}

/** URL 页码存在但不是正整数（如 ?page=abc、?page=0）。 */
function hasInvalidPage(q: LocationQuery) {
  if (q.page === undefined) return false
  const p = Number(str(q.page))
  return !(Number.isInteger(p) && p >= 1)
}

/**
 * 写入 URL（replace，不产生历史堆积）；空值不出现在 URL 中。由 URL 变化触发加载。
 * 条件与当前 URL 完全相同时 router.replace 不会触发 watch，此时直接 load()，保证“查询”按钮总能刷新。
 */
function pushQuery(next: { page: number; status: string; keyword: string }) {
  const query: Record<string, string> = {}
  if (next.page > 1) query.page = String(next.page)
  if (next.status) query.status = next.status
  if (next.keyword) query.keyword = next.keyword
  const cur = route.query
  const same = Object.keys(cur).length === Object.keys(query).length && Object.entries(query).every(([k, v]) => cur[k] === v)
  return same ? load() : router.replace({ query })
}

function applyKeyword() {
  return pushQuery({ page: 1, status: status.value, keyword: keyword.value.trim() })
}
function applyStatus() {
  // 用输入框里当前的关键字（即使尚未回车提交），与用户看到的条件一致
  return pushQuery({ page: 1, status: status.value ?? '', keyword: keyword.value.trim() })
}
function goPage(p: number) {
  const q = parseQuery(route.query)
  return pushQuery({ ...q, page: p })
}

let seq = 0
async function load() {
  // 非法页码：把地址栏改写为 ?page=1（保留其他条件），改写后由 watch 重新加载
  if (hasInvalidPage(route.query)) {
    await router.replace({ query: { ...route.query, page: '1' } })
    return
  }
  const q = parseQuery(route.query)
  keyword.value = q.keyword; status.value = q.status; page.value = q.page
  const my = ++seq
  loading.value = true
  reset()
  try {
    const res = await listWorkOrders({ page: q.page - 1, size: pageSize, status: q.status || undefined, keyword: q.keyword || undefined })
    // 只采用最后一次请求的结果，避免快速翻页时旧响应覆盖新页
    if (my !== seq) return
    rows.value = res.items
    total.value = res.total
    // 页码越界（如删除后总数变少）时跳到最后一页
    const last = Math.max(1, Math.ceil(res.total / pageSize))
    if (res.items.length === 0 && q.page > last) await pushQuery({ ...q, page: last })
  } catch (e) {
    if (my === seq) capture(e)
  } finally {
    if (my === seq) loading.value = false
  }
}

// 仅在本页时响应查询变化（离开到详情页时 query 也会变，不应再请求列表）
watch(() => route.query, () => { if (route.name === 'workorders') return load() }, { immediate: true })

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

defineExpose({ keyword, status, page, rows, total, load, applyKeyword, applyStatus, goPage, open, openCreate, submitCreate, createForm })
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
