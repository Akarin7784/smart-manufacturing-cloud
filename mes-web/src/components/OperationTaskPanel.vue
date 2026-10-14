<!--
  工序任务（派工）面板：派工 / 改派 / 取消派工（需 execution:dispatch）、开工 / 暂停 / 恢复（需 execution:report），
  兼容期 workorder:write 均可。接口见 api/dispatch.ts；后端上线前为【模拟数据】。
  工位下拉来自主数据工作中心（按工序 workCenterCode 过滤），操作工下拉来自 /auth/operators（模拟）。
-->
<template>
  <el-card>
    <template #header>
      工序任务
      <el-tag
        v-if="isMock"
        class="mock-tag"
        type="warning"
      >
        模拟数据（后端派工接口待上线）
      </el-tag>
    </template>
    <el-alert
      v-if="error"
      class="task-err"
      :title="error"
      type="error"
      :closable="false"
    />
    <div
      v-for="t in tasks"
      :key="t.operationNo"
      class="task"
    >
      <span class="op-no">工序 {{ t.operationNo }} {{ t.name }}</span>
      <el-tag>{{ TASK_LABELS[t.status] }}</el-tag>
      <span class="assign">{{ t.stationCode ?? '未派工' }} · {{ operatorName(t.operator) }}</span>
      <span class="spacer" />
      <template v-if="canDispatch">
        <el-button
          v-if="taskActions(t).dispatch"
          class="touch t-dispatch"
          type="primary"
          :disabled="busy"
          @click="openAssign(t, 'dispatch')"
        >
          派工
        </el-button>
        <el-tooltip
          v-if="isDispatched(t)"
          :disabled="taskActions(t).reassign"
          :content="reassignBlockedReason(t)"
        >
          <span>
            <el-button
              class="touch t-reassign"
              :disabled="busy || !taskActions(t).reassign"
              @click="openAssign(t, 'reassign')"
            >
              改派
            </el-button>
          </span>
        </el-tooltip>
        <span
          v-if="isDispatched(t) && !taskActions(t).reassign"
          class="hint t-reassign-hint"
        >{{ reassignBlockedReason(t) }}</span>
        <el-button
          v-if="taskActions(t).cancelDispatch"
          class="touch t-cancel"
          :disabled="busy"
          @click="cancelDispatch(t)"
        >
          取消派工
        </el-button>
      </template>
      <template v-if="canExec">
        <el-button
          v-if="taskActions(t).start"
          class="touch t-start"
          type="primary"
          :disabled="busy"
          @click="act(t, 'start')"
        >
          开工
        </el-button>
        <el-button
          v-if="taskActions(t).pause"
          class="touch t-pause"
          type="warning"
          :disabled="busy"
          @click="onPause(t)"
        >
          暂停
        </el-button>
        <el-button
          v-if="taskActions(t).resume"
          class="touch t-resume"
          type="success"
          :disabled="busy"
          @click="act(t, 'resume')"
        >
          恢复
        </el-button>
      </template>
    </div>

    <el-dialog
      v-model="dialog"
      :title="mode === 'dispatch' ? '派工' : '改派'"
      width="min(480px, 92vw)"
    >
      <el-form label-position="top">
        <el-form-item
          label="工位"
          :error="fieldErrors.stationCode"
        >
          <el-select
            v-model="assignForm.stationCode"
            class="r-station"
            size="large"
            placeholder="选择工位"
          >
            <el-option
              v-for="st in stations"
              :key="st.code"
              :label="`${st.code} ${st.name}`"
              :value="st.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="操作工（可空）">
          <el-select
            v-model="assignForm.operator"
            class="r-person"
            size="large"
            clearable
            placeholder="选择操作工"
          >
            <el-option
              v-for="p in operators"
              :key="p.id"
              :label="operatorLabel(p)"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="dialogError"
        class="dlg-err"
        :title="dialogError"
        type="error"
        :closable="false"
      />
      <template #footer>
        <el-button
          class="touch"
          @click="dialog = false"
        >
          取消
        </el-button>
        <el-button
          class="touch r-confirm"
          type="primary"
          :disabled="!assignForm.stationCode || busy"
          @click="confirmAssign"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  dispatchApi, DISPATCH_IS_MOCK, isDispatched, operatorLabel, reassignBlockedReason, taskActions,
  type Operator, type OperationTask, type OperationTaskStatus
} from '@/api/dispatch'
import { listStations, type Station } from '@/api/masterdata'
import { newIdempotencyKey } from '@/api/workorders'
import { useProblem } from '@/composables/useProblem'
import { useAuthStore } from '@/stores/auth'
import { canDispatchWith, canListOperatorsWith, canReportWith } from '@/utils/permissions'

const props = defineProps<{ workOrderNo: string }>()
const emit = defineEmits<{ changed: [] }>()

const TASK_LABELS: Record<OperationTaskStatus, string> = {
  PENDING: '待流入', READY: '待开工', RUNNING: '进行中', PAUSED: '已暂停', DONE: '已完成', CANCELLED: '已取消'
}
const isMock = DISPATCH_IS_MOCK
const auth = useAuthStore()
const canDispatch = computed(() => canDispatchWith(auth.can))
const canExec = computed(() => canReportWith(auth.can))
const tasks = ref<OperationTask[]>([])
const operators = ref<Operator[]>([])
const stations = ref<Station[]>([])
const busy = ref(false)
const { error, capture, reset } = useProblem()
const dlg = useProblem()
const dialogError = dlg.error
const fieldErrors = dlg.fieldErrors
const dialog = ref(false)
const mode = ref<'dispatch' | 'reassign'>('dispatch')
const target = ref<OperationTask | null>(null)
const assignForm = reactive({ stationCode: '', operator: '' })
// 每个动作一个幂等键：失败重试复用，成功后作废
const keys = new Map<string, string>()
const keyFor = (k: string) => keys.get(k) ?? (keys.set(k, newIdempotencyKey()), keys.get(k) as string)

function operatorName(id: string | null) {
  if (!id) return '未指派操作工'
  return operators.value.find((p) => p.id === id)?.name ?? id
}

async function load() {
  const api = dispatchApi()
  try {
    tasks.value = await api.listTasks(props.workOrderNo)
  } catch (e) {
    capture(e)
  }
  // 操作工列表只认 execution:dispatch（仅持 workorder:write 的兼容账号请求会 403 并弹「无权限」），故不满足时不请求
  operators.value = canListOperatorsWith(auth.can) ? await api.listOperators().catch(() => []) : []
}

/** 执行任务动作；busy 防止重复点击，失败显示 problem+json 中文提示。 */
async function run(actionKey: string, fn: (key: string) => Promise<unknown>) {
  if (busy.value) return false
  busy.value = true
  reset()
  try {
    await fn(keyFor(actionKey))
    keys.delete(actionKey)
    await load()
    emit('changed')
    return true
  } catch (e) {
    capture(e)
    return false
  } finally {
    busy.value = false
  }
}

function act(t: OperationTask, action: 'start' | 'resume') {
  return run(`${action}:${t.operationNo}:${t.version}`, (k) => dispatchApi()[action](props.workOrderNo, t.operationNo, k))
}

/** 暂停原因必填（契约 TaskActionRequest）。 */
async function onPause(t: OperationTask) {
  const reason = await ElMessageBox.prompt('请输入暂停原因', '暂停任务', {
    confirmButtonText: '暂停', cancelButtonText: '取消', inputValidator: (v) => !!v?.trim() || '暂停原因不能为空'
  }).then((r) => r.value.trim(), () => '')
  if (!reason) return
  return run(`pause:${t.operationNo}:${t.version}`, (k) => dispatchApi().pause(props.workOrderNo, t.operationNo, reason, k))
}

/** 取消派工原因必填。 */
async function cancelDispatch(t: OperationTask) {
  const reason = await ElMessageBox.prompt('请输入取消派工原因', '取消派工', {
    confirmButtonText: '确定', cancelButtonText: '返回', inputValidator: (v) => !!v?.trim() || '原因不能为空'
  }).then((r) => r.value.trim(), () => '')
  if (!reason) return
  return run(`cancel:${t.operationNo}:${t.version}`, (k) => dispatchApi().cancelDispatch(props.workOrderNo, t.operationNo, reason, k))
}

/** 打开派工 / 改派对话框，工位按工序工作中心过滤。 */
async function openAssign(t: OperationTask, m: 'dispatch' | 'reassign') {
  target.value = t
  mode.value = m
  assignForm.stationCode = t.stationCode ?? ''
  assignForm.operator = t.operator ?? ''
  dlg.reset()
  dialog.value = true
  try {
    stations.value = await listStations(t.workCenterCode || undefined)
  } catch (e) {
    stations.value = []
    dlg.capture(e)
  }
}

async function confirmAssign() {
  const t = target.value as OperationTask
  const req = { stationCode: assignForm.stationCode, operator: assignForm.operator || null }
  dlg.reset()
  const ok = await run(`${mode.value}:${t.operationNo}:${t.version}:${req.stationCode}:${req.operator}`, (k) =>
    mode.value === 'dispatch'
      ? dispatchApi().dispatch(props.workOrderNo, t.operationNo, req, k)
      // 改派携带读到的版本号，并发修改时后端返回 concurrent-modification
      : dispatchApi().reassign(props.workOrderNo, t.operationNo, { ...req, expectedVersion: t.version }, k))
  if (ok) dialog.value = false
  else { dlg.error.value = error.value; dlg.fieldErrors.value = {}; reset() }
}

onMounted(load)
defineExpose({ load, tasks, act, onPause, cancelDispatch, openAssign, confirmAssign, assignForm })
</script>

<style scoped>
.mock-tag { margin-left: 8px; }
.task { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 6px 0; }
.op-no { font-weight: 600; min-width: 120px; }
.assign, .hint { color: #606266; }
.spacer { flex: 1; }
.touch { min-height: 48px; min-width: 96px; font-size: 16px; }
.dlg-err { margin-top: 8px; }
</style>
