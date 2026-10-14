<!--
  工单详情：GET /release（下达快照：工序任务=派工任务、预留、冻结原因）、/progress（报工进度）、/reports（报工流水）。
  未下达的工单可能没有快照，此时只展示进度区的错误提示，不影响其他区块。
  下达 / 冻结 / 恢复 / 完工 / 撤回下达按钮仅在拥有 workorder:write 且状态允许时显示；关闭另需 quality:read（#70 关闭前查询质量服务）。
  最终以后端校验为准。
-->
<template>
  <el-result
    v-if="forbidden"
    class="forbidden"
    icon="warning"
    title="无权限"
    sub-title="当前账号没有查看该工单的权限"
  />
  <div
    v-else
    class="page"
  >
    <div class="head">
      <el-button
        class="touch"
        @click="router.push({ name: 'workorders' })"
      >
        返回列表
      </el-button>
      <h2 class="no">
        {{ id }}
      </h2>
      <el-tag
        v-if="currentStatus"
        size="large"
        :type="statusTag(currentStatus)"
      >
        {{ statusLabel(currentStatus) }}
      </el-tag>
      <span class="spacer" />
      <template v-if="canWrite">
        <el-button
          v-if="actions.release"
          class="touch act-release"
          type="primary"
          :loading="busy"
          @click="doRelease"
        >
          下达
        </el-button>
        <el-button
          v-if="actions.hold"
          class="touch act-hold"
          type="warning"
          :loading="busy"
          @click="doHold"
        >
          冻结
        </el-button>
        <el-button
          v-for="r in holdReasons"
          :key="r"
          class="touch act-resume"
          type="success"
          :loading="busy"
          @click="doResume(r)"
        >
          解除：{{ HOLD_REASON_LABELS[r] ?? r }}
        </el-button>
        <el-button
          v-if="actions.complete"
          class="touch act-complete"
          type="success"
          :loading="busy"
          @click="doComplete"
        >
          完工
        </el-button>
        <el-button
          v-if="actions.withdraw"
          class="touch act-withdraw"
          type="warning"
          :loading="busy"
          @click="doWithdraw"
        >
          撤回下达
        </el-button>
        <el-button
          v-if="actions.close && canClose"
          class="touch act-close"
          type="info"
          :loading="busy"
          @click="doClose"
        >
          关闭工单
        </el-button>
      </template>
      <el-button
        v-if="canExec && canReport(currentStatus)"
        class="touch act-report"
        type="primary"
        @click="router.push({ name: 'reports', query: { wo: id } })"
      >
        去报工
      </el-button>
    </div>
    <el-alert
      v-if="actionError"
      class="err act-err"
      :title="actionError"
      type="error"
      :closable="false"
    />

    <el-card header="报工进度">
      <el-alert
        v-if="progressError"
        :title="progressError"
        type="warning"
        :closable="false"
      />
      <template v-if="progress">
        <p>计划数：{{ fmtQty(progress.plannedQty) }}</p>
        <div
          v-for="op in progress.operations"
          :key="op.operationNo"
          class="op-progress"
        >
          <span class="op-no">{{ op.seq }}. {{ op.operationNo }}</span>
          <el-progress
            class="bar"
            :stroke-width="16"
            :percentage="percent(op.goodQty, progress.plannedQty)"
          />
          <span class="qty">合格 {{ fmtQty(op.goodQty) }} / 不良 {{ fmtQty(op.defectQty) }} / 报废 {{ fmtQty(op.scrapQty) }} {{ op.uom ?? '' }}（可报上限 {{ fmtQty(op.limit) }}）</span>
        </div>
      </template>
    </el-card>

    <el-card header="下达快照">
      <el-alert
        v-if="releaseError"
        :title="releaseError"
        type="info"
        :closable="false"
      />
      <template v-if="release">
        <p class="meta">
          产品 {{ release.productCode }} · BOM {{ release.bomVersion }} · 工艺 {{ release.routingVersion }} · 版本 {{ release.version }}
        </p>
        <el-table
          :data="release.operations"
          empty-text="无工序任务"
        >
          <el-table-column
            prop="opNo"
            label="工序号"
            width="100"
          />
          <el-table-column
            prop="name"
            label="工序"
          />
          <el-table-column
            prop="workCenterCode"
            label="工作中心"
          />
          <el-table-column
            prop="status"
            label="任务状态"
            width="110"
          />
          <el-table-column
            label="报工点"
            width="90"
          >
            <template #default="{ row }">
              {{ row.reportPoint ? '是' : '否' }}
            </template>
          </el-table-column>
        </el-table>
        <p
          v-if="release.reservation"
          class="meta"
        >
          物料预留：{{ release.reservation.status }}（{{ release.reservation.lines.length }} 行）
        </p>
      </template>
    </el-card>

    <OperationTaskPanel
      :key="id"
      :work-order-no="id"
      @changed="load"
    />

    <el-card header="报工流水">
      <el-table
        :data="reports"
        empty-text="暂无报工"
        :row-class-name="reportRowClass"
      >
        <el-table-column
          prop="operationNo"
          label="工序"
          width="90"
        />
        <el-table-column
          label="类型"
          width="120"
        >
          <template #default="{ row }">
            <el-tag
              v-if="row.reversedReportId"
              type="danger"
            >
              冲销
            </el-tag>
            <el-tag
              v-else-if="reversedIds.has(row.reportId)"
              type="info"
            >
              已冲销
            </el-tag>
            <span v-else>{{ row.type }}</span>
          </template>
        </el-table-column>
        <el-table-column label="合格/不良/报废">
          <template #default="{ row }">
            {{ fmtQty(row.goodQty) }} / {{ fmtQty(row.defectQty) }} / {{ fmtQty(row.scrapQty) }} {{ row.uom }}
          </template>
        </el-table-column>
        <el-table-column
          label="工时"
          width="80"
        >
          <template #default="{ row }">
            {{ fmtQty(row.laborHours) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="lotNo"
          label="批次"
        />
        <el-table-column
          prop="operator"
          label="操作人"
        />
        <el-table-column
          prop="createdAt"
          label="时间"
          min-width="160"
        />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  closeWorkOrder, completeWorkOrder, getProgress, getRelease, getReports, holdWorkOrder, newIdempotencyKey, releaseWorkOrder, resumeWorkOrder, withdrawRelease,
  type HoldReason, type OperationReportView, type WorkOrderProgressView, type WorkOrderReleaseView
} from '@/api/workorders'
import { allowedActions, canReport, fmtQty, HOLD_REASON_LABELS, percent, statusLabel, statusTag } from '@/utils/workorder'
import { useProblem } from '@/composables/useProblem'
import { useAuthStore } from '@/stores/auth'
import { canCloseWith, canReportWith, PERM_WORKORDER_WRITE } from '@/utils/permissions'
import OperationTaskPanel from '@/components/OperationTaskPanel.vue'
import { isBusinessRejection } from '@/api/http'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = computed(() => String(route.params.id))
const release = ref<WorkOrderReleaseView | null>(null)
const progress = ref<WorkOrderProgressView | null>(null)
const reports = ref<OperationReportView[]>([])
const busy = ref(false)
const rel = useProblem()
const prog = useProblem()
const act = useProblem()
const releaseError = rel.error
const progressError = prog.error
const actionError = act.error
// 任一查询返回 403 即视为无权查看该工单
const forbidden = computed(() => prog.forbidden.value || rel.forbidden.value)
// 同一次完工操作重试时复用幂等键，成功后才更换
let completeKey = newIdempotencyKey()
let releaseKey = newIdempotencyKey()
let withdrawKey = newIdempotencyKey()
let closeKey = newIdempotencyKey()

const canWrite = computed(() => auth.can(PERM_WORKORDER_WRITE))
const canExec = computed(() => canReportWith(auth.can))
const canClose = computed(() => canCloseWith(auth.can))
// 被冲销的原报工 id 集合：冲销记录通过 reversedReportId 指向原记录
const reversedIds = computed(() => new Set(reports.value.map((r) => r.reversedReportId).filter((x): x is string => !!x)))
/** 冲销记录与已被冲销的原记录分别加样式，避免车间误读数量。 */
function reportRowClass({ row }: { row: OperationReportView }) {
  if (row.reversedReportId) return 'row-reversal'
  return reversedIds.value.has(row.reportId) ? 'row-reversed' : ''
}

// 进度接口的状态最实时；未下达时退回快照状态
const currentStatus = computed(() => progress.value?.status ?? release.value?.status ?? '')
const actions = computed(() => allowedActions(currentStatus.value))
const holdReasons = computed<HoldReason[]>(() => (currentStatus.value === 'ON_HOLD' ? release.value?.holdReasons ?? [] : []))

/** 三个区块独立加载，任一失败不影响其他区块展示。 */
async function load() {
  rel.reset(); prog.reset()
  await Promise.all([
    getRelease(id.value).then((v) => { release.value = v }, rel.capture),
    getProgress(id.value).then((v) => { progress.value = v }, prog.capture),
    getReports(id.value).then((v) => { reports.value = v }, () => { reports.value = [] })
  ])
}

/**
 * 执行工单动作并刷新；busy 防重复点击，失败时显示 problem+json 解析后的原因。
 * renewKey 用于作废该动作的幂等键：成功后作废；409 / 422 业务拒绝时也作废并刷新数据（状态已变化，旧键重试无意义）；
 * 网络错误、503 等保留原键，重试时由后端幂等去重。
 */
async function run(fn: () => Promise<unknown>, renewKey?: () => void) {
  if (busy.value) return
  busy.value = true
  act.reset()
  try {
    await fn()
    renewKey?.()
    await load()
  } catch (e) {
    if (isBusinessRejection(e)) {
      renewKey?.()
      // 刷新失败不影响展示原冲突原因
      await load().catch(() => {})
    }
    act.capture(e)
  } finally {
    busy.value = false
  }
}

function doRelease() {
  return run(() => releaseWorkOrder(id.value, releaseKey), () => { releaseKey = newIdempotencyKey() })
}

async function doHold() {
  const note = await ElMessageBox.prompt('冻结说明（可选）', '冻结工单', { confirmButtonText: '冻结', cancelButtonText: '取消' })
    .then((r) => r.value, () => null)
  if (note === null) return
  return run(() => holdWorkOrder(id.value, 'MANUAL', note))
}

function doResume(reason: HoldReason) {
  return run(() => resumeWorkOrder(id.value, reason))
}

async function doComplete() {
  const reason = await ElMessageBox.prompt('请输入完工原因（允许短缺完工）', '手工完工', {
    confirmButtonText: '完工', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '完工原因不能为空'
  }).then((r) => r.value.trim(), () => null)
  if (!reason) return
  return run(() => completeWorkOrder(id.value, reason, completeKey), () => { completeKey = newIdempotencyKey() })
}

/** 撤回下达：二次确认并可填写说明；幂等键规则同其他动作（成功 / 409 / 422 换新键，网络错误与 503 复用）。 */
async function doWithdraw() {
  const note = await ElMessageBox.prompt('撤回后工单回到“已创建”，工序任务与派工将被清除，库存预留由后台释放。撤回说明（可选）', '撤回下达', {
    confirmButtonText: '撤回', cancelButtonText: '取消'
  }).then((r) => r.value ?? '', () => null)
  if (note === null) return
  return run(() => withdrawRelease(id.value, note.trim() || undefined, withdrawKey), () => { withdrawKey = newIdempotencyKey() })
}

/** 关闭工单：质量服务不可用（503）时后端拒绝，提示文案见 problemHint('quality-service-unavailable')；503 时保留幂等键便于重试。 */
async function doClose() {
  const note = await ElMessageBox.prompt('关闭后工单不能再报工或变更。关闭说明（可选）', '关闭工单', {
    confirmButtonText: '关闭', cancelButtonText: '取消'
  }).then((r) => r.value ?? '', () => null)
  if (note === null) return
  return run(() => closeWorkOrder(id.value, note.trim() || undefined, closeKey), () => { closeKey = newIdempotencyKey() })
}

onMounted(load)
defineExpose({ doRelease, doHold, doResume, doComplete, doWithdraw, doClose, load })
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.head { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.no { margin: 0; font-size: 20px; }
.spacer { flex: 1; }
.touch { min-height: 48px; min-width: 96px; font-size: 16px; }
.op-progress { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 8px 0; }
.op-no { width: 120px; font-weight: 600; }
.bar { flex: 1 1 200px; }
.qty { color: #606266; }
.meta { color: #606266; }
:deep(.row-reversed) { color: #a8abb2; text-decoration: line-through; }
:deep(.row-reversal) { background: #fef0f0; }
</style>
