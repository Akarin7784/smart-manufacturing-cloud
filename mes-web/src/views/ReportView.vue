<!--
  报工页（车间平板）：选工单 → 选工序 → 录入合格/不良/报废数量与批次 → 提交。
  接口：POST /api/workorders/{id}/operations/{opNo}/reports（需 execution:report，兼容期 workorder:write 亦可；带 Idempotency-Key）。
  所有可点控件高度 ≥48px；数量输入使用 inputmode="decimal" 以唤起数字键盘。
  工时 laborHours 为必填（小时，≥0，最多 2 位小数，以字符串发送）。
-->
<template>
  <div class="page">
    <el-alert
      v-if="!canWrite"
      class="no-perm"
      title="无权限：当前账号没有报工权限"
      type="warning"
      :closable="false"
    />
    <section>
      <h3>1. 选择工单</h3>
      <el-select
        v-model="woId"
        class="wo-select"
        size="large"
        filterable
        placeholder="选择可报工的工单"
        @change="onWorkOrderChange"
      >
        <el-option
          v-for="w in workOrders"
          :key="w.id"
          :label="`${w.id} · ${w.product}`"
          :value="w.id"
        />
      </el-select>
    </section>

    <el-alert
      v-if="error && !opNo"
      class="err top-err"
      :title="error"
      type="error"
      :closable="false"
    />
    <section v-if="progress">
      <h3>2. 选择工序</h3>
      <div class="ops">
        <el-button
          v-for="op in progress.operations"
          :key="op.operationNo"
          class="op-btn"
          :type="op.operationNo === opNo ? 'primary' : 'default'"
          @click="selectOp(op.operationNo)"
        >
          {{ op.seq }}. {{ op.operationNo }}<br>
          <small>已合格 {{ fmtQty(op.goodQty) }} / 上限 {{ fmtQty(op.limit) }}</small>
        </el-button>
      </div>
    </section>

    <section v-if="opNo">
      <h3>3. 录入报工</h3>
      <el-form
        class="form"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <div class="grid">
          <el-form-item
            label="合格数"
            :error="fe.goodQty"
          >
            <el-input
              v-model="form.goodQty"
              class="q-good"
              inputmode="decimal"
              placeholder="0"
            />
          </el-form-item>
          <el-form-item
            label="不良数"
            :error="fe.defectQty"
          >
            <el-input
              v-model="form.defectQty"
              class="q-defect"
              inputmode="decimal"
              placeholder="0"
            />
          </el-form-item>
          <el-form-item
            label="报废数"
            :error="fe.scrapQty"
          >
            <el-input
              v-model="form.scrapQty"
              class="q-scrap"
              inputmode="decimal"
              placeholder="0"
            />
          </el-form-item>
          <el-form-item
            label="工时（小时）"
            :error="fe.laborHours"
          >
            <el-input
              v-model="form.laborHours"
              class="f-hours"
              inputmode="decimal"
              placeholder="0.00"
            />
          </el-form-item>
          <el-form-item
            label="单位"
            :error="fe.uom"
          >
            <el-input
              v-model="form.uom"
              class="f-uom"
              :disabled="uomLocked"
              maxlength="16"
            />
          </el-form-item>
          <el-form-item
            label="批次号"
            :error="fe.lotNo"
          >
            <el-input
              v-model="form.lotNo"
              class="f-lot"
              maxlength="64"
              :placeholder="`留空则使用工单号 ${woId}`"
            />
          </el-form-item>
          <el-form-item
            label="备注"
            :error="fe.note"
          >
            <el-input
              v-model="form.note"
              class="f-note"
              maxlength="256"
            />
          </el-form-item>
        </div>
        <el-alert
          v-if="!reportable"
          class="err not-reportable"
          :title="`工单当前状态为「${statusLabel(progress?.status ?? '')}」，不能报工`"
          type="warning"
          :closable="false"
        />
        <el-alert
          v-if="error"
          class="err"
          :title="error"
          type="error"
          :closable="false"
        />
        <el-button
          class="submit"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="!canWrite || !reportable"
        >
          提交报工
        </el-button>
      </el-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProgress, listWorkOrders, MAX_PAGE_SIZE, newIdempotencyKey, reportOperation, type WorkOrder, type WorkOrderProgressView } from '@/api/workorders'
import { canReport, fmtQty, isValidHours, isValidQty, normalizeLotNo, statusLabel } from '@/utils/workorder'
import { useProblem } from '@/composables/useProblem'
import { useAuthStore } from '@/stores/auth'
import { canReportWith } from '@/utils/permissions'

const route = useRoute()
const auth = useAuthStore()
// 报工权限兼容 workorder:write 与将来的 execution:report
const canWrite = computed(() => canReportWith(auth.can))
const workOrders = ref<WorkOrder[]>([])
const woId = ref('')
const opNo = ref('')
const progress = ref<WorkOrderProgressView | null>(null)
const submitting = ref(false)
const form = reactive({ goodQty: '', defectQty: '', scrapQty: '', laborHours: '', uom: '', lotNo: '', note: '' })
const { error, fieldErrors, capture, reset } = useProblem()
// 前端校验与后端 validation-error 的字段错误合并展示，前端优先
const localErrors = ref<Record<string, string>>({})
const fe = computed(() => ({ ...fieldErrors.value, ...localErrors.value }))
// ON_HOLD / COMPLETED 等状态禁止提交，状态以最新进度为准
const reportable = computed(() => canReport(progress.value?.status ?? ''))
// 幂等键在表单内容不变的重试间复用；内容变化或提交成功后更换，避免网络重试造成重复报工
let key = newIdempotencyKey()
// flush: 'sync' 保证修改后立即换键，不会被延迟到已发出的请求之后
watch([form, opNo, woId], () => { key = newIdempotencyKey() }, { deep: true, flush: 'sync' })

const currentOp = computed(() => progress.value?.operations.find((o) => o.operationNo === opNo.value))
// 工序已有报工时单位已确定，锁定避免 uom-mismatch
const uomLocked = computed(() => !!currentOp.value?.uom)

async function loadProgress() {
  progress.value = await getProgress(woId.value)
}

async function onWorkOrderChange() {
  opNo.value = ''
  reset()
  try {
    await loadProgress()
  } catch (e) {
    progress.value = null
    capture(e)
  }
}

function selectOp(no: string) {
  opNo.value = no
  form.uom = currentOp.value?.uom ?? form.uom
  reset()
}

/** 校验：数量空值视为 0，非负最多 6 位小数且至少一项大于 0；工时必填，非负最多 2 位小数；单位必填。错误写入对应字段。 */
function validate(): string | null {
  const errs: Record<string, string> = {}
  for (const f of ['goodQty', 'defectQty', 'scrapQty'] as const) {
    if (form[f].trim() !== '' && !isValidQty(form[f])) errs[f] = '格式不正确（非负数，最多 6 位小数）'
  }
  if (!form.laborHours.trim()) errs.laborHours = '请输入工时'
  else if (!isValidHours(form.laborHours)) errs.laborHours = '格式不正确（非负数，最多 2 位小数）'
  if (!form.uom.trim()) errs.uom = '请输入单位'
  localErrors.value = errs
  if (Object.keys(errs).length) return '请检查标红字段'
  if ([form.goodQty, form.defectQty, form.scrapQty].every((v) => !(Number(v) > 0))) return '合格、不良、报废数量至少一项大于 0'
  return null
}

async function submit() {
  // 防重复点击：提交中直接忽略
  if (submitting.value || !reportable.value) return
  reset()
  const msg = validate()
  if (msg) { error.value = msg; return }
  submitting.value = true
  try {
    const result = await reportOperation(woId.value, opNo.value, {
      goodQty: form.goodQty.trim() || '0',
      defectQty: form.defectQty.trim() || '0',
      scrapQty: form.scrapQty.trim() || '0',
      // 工时以 2 位小数字符串发送，与契约 Hours 一致
      laborHours: Number(form.laborHours.trim()).toFixed(2),
      uom: form.uom.trim(),
      lotNo: normalizeLotNo(form.lotNo),
      note: form.note.trim() || null
    }, key)
    progress.value = result.progress
    ElMessage.success(`报工成功，批次 ${result.report.lotNo ?? woId.value}`)
    // 只清空数量与备注，保留批次和单位，方便连续报工
    form.goodQty = ''; form.defectQty = ''; form.scrapQty = ''; form.laborHours = ''; form.note = ''
    key = newIdempotencyKey()
    form.uom = currentOp.value?.uom ?? form.uom
  } catch (e) {
    capture(e)
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    // 服务端按状态过滤：可报工的只有 RELEASED / IN_PROGRESS，各取一页（最多 MAX_PAGE_SIZE 条）
    const pages = await Promise.all(['IN_PROGRESS', 'RELEASED'].map((status) => listWorkOrders({ status, size: MAX_PAGE_SIZE })))
    workOrders.value = pages.flatMap((p) => p.items).filter((w) => canReport(w.status))
  } catch (e) {
    capture(e)
  }
  // 从工单详情跳转时预选工单
  if (typeof route.query.wo === 'string' && route.query.wo) {
    woId.value = route.query.wo
    await onWorkOrderChange()
  }
})

defineExpose({ woId, opNo, form, submit, selectOp, onWorkOrderChange, fe })
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; max-width: 960px; }
h3 { margin: 0 0 8px; }
.wo-select { width: 100%; max-width: 480px; }
.ops { display: flex; flex-wrap: wrap; gap: 12px; }
.op-btn { min-height: 72px; min-width: 160px; font-size: 18px; line-height: 1.4; margin-left: 0 !important; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0 16px; }
:deep(.el-input__wrapper) { min-height: 48px; }
.q-good :deep(input), .q-defect :deep(input), .q-scrap :deep(input) { font-size: 22px; }
.err { margin-bottom: 12px; }
.submit { width: 100%; min-height: 56px; font-size: 20px; }
</style>
