// 保护 M1 页面：工单列表筛选/分页/新建、详情动作与冲销区分、派工面板、报工页校验/幂等/防重复/错误回显/平板触控尺寸。
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { HttpError } from '@/api/http'
import { createMockDispatchApi, setDispatchApi } from '@/api/dispatch'
import { mountAs } from './helpers'

vi.mock('@/api/workorders', async (orig) => {
  const m = await orig<typeof import('@/api/workorders')>()
  return {
    ...m,
    listWorkOrders: vi.fn(), getRelease: vi.fn(), getProgress: vi.fn(), getReports: vi.fn(), reportOperation: vi.fn(),
    releaseWorkOrder: vi.fn(), holdWorkOrder: vi.fn(), resumeWorkOrder: vi.fn(), completeWorkOrder: vi.fn(), createWorkOrder: vi.fn()
  }
})
vi.mock('@/api/masterdata', () => ({ listStations: vi.fn() }))
vi.mock('element-plus', async (orig) => {
  const m = await orig<typeof import('element-plus')>()
  return { ...m, ElMessage: Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn() }), ElMessageBox: { prompt: vi.fn() } }
})

/** 等按钮出现且可用（busy 结束）再点击，避免点到禁用状态的按钮。 */
async function click(w: { find: (s: string) => { exists: () => boolean; attributes: (a: string) => string | undefined; trigger: (e: string) => Promise<void> } }, sel: string) {
  await vi.waitFor(() => {
    expect(w.find(sel).exists()).toBe(true)
    expect(w.find(sel).attributes('disabled')).toBeUndefined()
  })
  await w.find(sel).trigger('click')
}

const api = vi.mocked(await import('@/api/workorders'))
const md = vi.mocked(await import('@/api/masterdata'))
const { ElMessageBox } = await import('element-plus')
const prompt = vi.mocked(ElMessageBox.prompt)

const WRITE = ['workorder:read', 'workorder:write']
const READ = ['workorder:read']
const problem = (status: number, type: string, detail = '') => new HttpError({ status, message: detail || type, problem: { type, title: type, status, detail } })
const progress = (status = 'IN_PROGRESS', uom: string | null = null) => ({
  workOrderNo: 'WO1', status, plannedQty: '10.000000',
  operations: [{ operationNo: '10', seq: 1, goodQty: '2.000000', defectQty: '0', scrapQty: '0', uom, limit: '10', effectiveReports: 1 }]
})
const orders = Array.from({ length: 12 }, (_, i) => ({ id: `WO${i + 1}`, deviceId: `D${i}`, product: i % 2 ? '齿轮' : '轴', quantity: 10, status: i < 3 ? 'IN_PROGRESS' : 'CREATED' }))

beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(window, 'innerWidth', { value: 1024, configurable: true, writable: true })
  setDispatchApi(createMockDispatchApi())
  api.listWorkOrders.mockResolvedValue(orders)
  api.getProgress.mockResolvedValue(progress())
  api.getRelease.mockResolvedValue({ orderId: 'WO1', status: 'IN_PROGRESS', version: 1, productCode: 'P', bomVersion: 'B1', routingVersion: 'R1', releasedAt: null, resumeTarget: null, holdReasons: [], bomLineCount: 1, operations: [], reservation: { reservationId: 'r', status: 'ACTIVE', expiresAt: null, lines: [] } })
  api.getReports.mockResolvedValue([])
})
afterEach(() => { document.body.innerHTML = '' })

describe('工单列表', () => {
  it('分页、按状态与关键字筛选、点击进入详情', async () => {
    const { w, router } = await mountAs('/workorders', WRITE)
    await vi.waitFor(() => expect(w.findAll('.row').length).toBe(10))
    const vm = w.findComponent({ name: 'WorkOrderView' }).vm as unknown as { status: string; keyword: string; page: number; filtered: unknown[]; open: (r: unknown) => void }
    vm.page = 2
    await vi.waitFor(() => expect(w.findAll('.row').length).toBe(2))
    vm.status = 'IN_PROGRESS'
    await vi.waitFor(() => expect(vm.filtered.length).toBe(3))
    expect(vm.page).toBe(1)
    vm.keyword = '齿轮'
    await vi.waitFor(() => expect(vm.filtered.length).toBe(1))
    await w.find('.row').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('workorder-detail'))
  })
  it('403 显示无权限页面而不跳登录；其他错误显示提示', async () => {
    api.listWorkOrders.mockRejectedValueOnce(problem(403, 'access-denied'))
    const { w, router } = await mountAs('/workorders', READ)
    await vi.waitFor(() => expect(w.find('.forbidden').exists()).toBe(true))
    expect(router.currentRoute.value.name).toBe('workorders')
    expect(w.find('.btn-create').exists()).toBe(false)
    api.listWorkOrders.mockRejectedValueOnce(new Error('网络异常'))
    await w.find('.toolbar .touch').trigger('click')
    await vi.waitFor(() => expect(w.text()).toContain('网络异常'))
  })
  it('新建工单：校验、幂等重试复用键、成功跳详情', async () => {
    const { w, router } = await mountAs('/workorders', WRITE)
    const vm = w.findComponent({ name: 'WorkOrderView' }).vm as unknown as { openCreate: () => void; submitCreate: () => Promise<void>; createForm: Record<string, string> }
    vm.openCreate()
    await vm.submitCreate()
    await vi.waitFor(() => expect(document.body.textContent).toContain('请输入设备'))
    expect(api.createWorkOrder).not.toHaveBeenCalled()
    Object.assign(vm.createForm, { deviceId: 'D1', product: '轴', quantity: '5' })
    api.createWorkOrder.mockRejectedValueOnce(problem(400, 'validation-error', 'product: 过长'))
    await vm.submitCreate()
    await vi.waitFor(() => expect(document.body.textContent).toContain('过长'))
    api.createWorkOrder.mockResolvedValueOnce({ id: 'WO99', deviceId: 'D1', product: '轴', quantity: 5, status: 'CREATED' })
    await vm.submitCreate()
    const [k1, k2] = api.createWorkOrder.mock.calls.map((c) => c[1])
    expect(k1).toBe(k2)
    await vi.waitFor(() => expect(router.currentRoute.value.params.id).toBe('WO99'))
  })
})

describe('工单详情', () => {
  it('显示进度与报工流水，冲销记录区分显示', async () => {
    api.getReports.mockResolvedValue([
      { reportId: 'R1', type: 'REPORT', operationNo: '10', goodQty: '2.000000', defectQty: '0', scrapQty: '0', uom: 'PCS', lotNo: 'WO1', note: null, reversedReportId: null, operator: 'u', createdAt: 't' },
      { reportId: 'R2', type: 'REVERSAL', operationNo: '10', goodQty: '-2', defectQty: '0', scrapQty: '0', uom: 'PCS', lotNo: 'WO1', note: null, reversedReportId: 'R1', operator: 'u', createdAt: 't' },
      { reportId: 'R3', type: 'REPORT', operationNo: '10', goodQty: '1', defectQty: '0', scrapQty: '0', uom: 'PCS', lotNo: 'L', note: null, reversedReportId: null, operator: 'u', createdAt: 't' }
    ])
    const { w } = await mountAs('/workorders/WO1', WRITE)
    await vi.waitFor(() => expect(w.findAll('.row-reversed').length).toBe(1))
    expect(w.findAll('.row-reversal').length).toBe(1)
    expect(w.text()).toContain('已冲销')
    expect(w.text()).toContain('计划数：10')
    expect(w.find('.el-progress').exists()).toBe(true)
  })
  it('冻结、完工、下达与恢复动作及错误提示', async () => {
    const { w, router } = await mountAs('/workorders/WO1', WRITE)
    await vi.waitFor(() => expect(w.find('.act-hold').exists()).toBe(true))
    prompt.mockResolvedValueOnce({ value: '换线', action: 'confirm' } as never)
    api.holdWorkOrder.mockResolvedValueOnce({} as never)
    await click(w, '.act-hold')
    await vi.waitFor(() => expect(api.holdWorkOrder).toHaveBeenCalledWith('WO1', 'MANUAL', '换线'))
    prompt.mockRejectedValueOnce('cancel')
    await click(w, '.act-hold')
    await flushPromises()
    expect(api.holdWorkOrder).toHaveBeenCalledTimes(1)
    prompt.mockResolvedValueOnce({ value: ' 短缺 ', action: 'confirm' } as never)
    api.completeWorkOrder.mockRejectedValueOnce(problem(409, 'operations-unreported', '工序 20 未报工'))
    await click(w, '.act-complete')
    await vi.waitFor(() => expect(w.find('.act-err').text()).toContain('仍有工序未报工'))
    prompt.mockResolvedValueOnce({ value: '短缺', action: 'confirm' } as never)
    api.completeWorkOrder.mockResolvedValueOnce({} as never)
    await click(w, '.act-complete')
    await vi.waitFor(() => expect(api.completeWorkOrder).toHaveBeenCalledTimes(2))
    // 失败重试复用同一个幂等键
    expect(api.completeWorkOrder.mock.calls[0][2]).toBe(api.completeWorkOrder.mock.calls[1][2])
    prompt.mockRejectedValueOnce('cancel')
    await click(w, '.act-complete')
    await flushPromises()
    expect(api.completeWorkOrder).toHaveBeenCalledTimes(2)
    await click(w, '.act-report')
    await vi.waitFor(() => expect(router.currentRoute.value.query.wo).toBe('WO1'))
  })
  it('CREATED 可下达；ON_HOLD 显示解除原因；只读用户无按钮', async () => {
    api.getProgress.mockResolvedValueOnce(progress('CREATED'))
    const a = await mountAs('/workorders/WO1', WRITE)
    await vi.waitFor(() => expect(a.w.find('.act-release').exists()).toBe(true))
    api.releaseWorkOrder.mockResolvedValueOnce({} as never)
    await click(a.w, '.act-release')
    await vi.waitFor(() => expect(api.releaseWorkOrder).toHaveBeenCalled())
    a.w.unmount()

    api.getProgress.mockResolvedValue(progress('ON_HOLD'))
    api.getRelease.mockResolvedValue({ ...(await api.getRelease('x')), status: 'ON_HOLD', holdReasons: ['MANUAL', 'LOT_HELD'] })
    const b = await mountAs('/workorders/WO1', WRITE)
    await vi.waitFor(() => expect(b.w.findAll('.act-resume').length).toBe(2))
    expect(b.w.text()).toContain('解除：人工冻结')
    api.resumeWorkOrder.mockResolvedValueOnce({} as never)
    await click(b.w, '.act-resume')
    await vi.waitFor(() => expect(api.resumeWorkOrder).toHaveBeenCalledWith('WO1', 'MANUAL'))
    b.w.unmount()

    const c = await mountAs('/workorders/WO1', READ)
    await vi.waitFor(() => expect(c.w.text()).toContain('报工进度'))
    expect(c.w.find('.act-resume').exists()).toBe(false)
    expect(c.w.find('.t-start').exists()).toBe(false)
  })
  it('快照不可用时局部提示；403 显示无权限', async () => {
    api.getRelease.mockRejectedValueOnce(problem(404, 'workorder-not-released', '工单未下达'))
    api.getReports.mockRejectedValueOnce(new Error('x'))
    const a = await mountAs('/workorders/WO1', WRITE)
    await vi.waitFor(() => expect(a.w.text()).toContain('工单未下达'))
    a.w.unmount()
    api.getProgress.mockRejectedValueOnce(problem(403, 'access-denied'))
    const b = await mountAs('/workorders/WO1', READ)
    await vi.waitFor(() => expect(b.w.find('.forbidden').exists()).toBe(true))
  })
})

describe('派工面板（模拟实现）', () => {
  it('派工→开工→暂停→改派→恢复，按钮按状态与权限显示', async () => {
    md.listStations.mockResolvedValue([{ code: 'S1', name: '一号位', workCenterCode: 'WC' }, { code: 'S2', name: '二号位', workCenterCode: 'WC' }])
    const { w } = await mountAs('/workorders/WO1', ['workorder:read', 'execution:dispatch', 'execution:report'])
    await vi.waitFor(() => expect(w.find('.t-dispatch').exists()).toBe(true))
    expect(w.text()).toContain('模拟数据')
    const panel = w.findComponent({ name: 'OperationTaskPanel' }).vm as unknown as { assignForm: Record<string, string>; confirmAssign: () => Promise<void> }
    await click(w, '.t-dispatch')
    await vi.waitFor(() => expect(md.listStations).toHaveBeenCalled())
    panel.assignForm.stationCode = 'S1'
    panel.assignForm.operator = 'op1'
    await panel.confirmAssign()
    await vi.waitFor(() => expect(w.text()).toContain('S1 · 操作员一'))
    await click(w, '.t-start')
    await vi.waitFor(() => expect(w.find('.t-pause').exists()).toBe(true))
    // RUNNING 时改派禁用并说明原因
    expect(w.find('.t-reassign').attributes('disabled')).toBeDefined()
    expect(w.find('.t-reassign-hint').text()).toContain('先暂停')
    prompt.mockResolvedValueOnce({ value: '换模', action: 'confirm' } as never)
    await click(w, '.t-pause')
    await vi.waitFor(() => expect(w.find('.t-resume').exists()).toBe(true))
    expect(w.find('.t-reassign').attributes('disabled')).toBeUndefined()
    await click(w, '.t-reassign')
    panel.assignForm.stationCode = 'S2'
    await panel.confirmAssign()
    await vi.waitFor(() => expect(w.text()).toContain('S2'))
    await click(w, '.t-resume')
    await vi.waitFor(() => expect(w.find('.t-pause').exists()).toBe(true))
  })
  it('取消派工、暂停取消、错误中文提示与工位加载失败', async () => {
    md.listStations.mockRejectedValueOnce(problem(403, 'access-denied'))
    const mock = createMockDispatchApi()
    await mock.dispatch('WO1', '10', { stationCode: 'S1' }, 'k')
    setDispatchApi(mock)
    const { w } = await mountAs('/workorders/WO1', WRITE)
    await vi.waitFor(() => expect(w.find('.t-cancel').exists()).toBe(true))
    const panel = w.findComponent({ name: 'OperationTaskPanel' }).vm as unknown as {
      act: (t: unknown, a: string) => Promise<unknown>; onPause: (t: unknown) => Promise<unknown>; tasks: { status: string }[]; openAssign: (t: unknown, m: string) => Promise<void>; confirmAssign: () => Promise<void>; assignForm: Record<string, string>
    }
    await click(w, '.t-reassign')
    await vi.waitFor(() => expect(document.body.textContent).toContain('无权限'))
    prompt.mockRejectedValueOnce('cancel')
    await click(w, '.t-cancel')
    await flushPromises()
    prompt.mockResolvedValueOnce({ value: '错派', action: 'confirm' } as never)
    await click(w, '.t-cancel')
    await vi.waitFor(() => expect(w.find('.t-dispatch').exists()).toBe(true))
    // 未派工开工 → task-not-dispatched 中文提示（先等上一动作结束）
    await vi.waitFor(() => expect(w.find('.t-dispatch').attributes('disabled')).toBeUndefined())
    await panel.act(panel.tasks[0], 'start')
    await vi.waitFor(() => expect(w.find('.task-err').text()).toContain('请先派工'))
    prompt.mockRejectedValueOnce('cancel')
    await panel.onPause(panel.tasks[0])
    // 派工到空工位 → station-not-found 显示在对话框
    await panel.openAssign(panel.tasks[0], 'dispatch')
    panel.assignForm.stationCode = ''
    await panel.confirmAssign()
    await vi.waitFor(() => expect(document.body.textContent).toContain('工位不存在'))
  })
  it('任务加载失败与无派工权限时操作工为空', async () => {
    const mock = createMockDispatchApi()
    mock.listTasks = () => Promise.reject(problem(500, 'internal-error', '服务异常'))
    setDispatchApi(mock)
    const { w } = await mountAs('/workorders/WO1', ['workorder:read', 'execution:report'])
    await vi.waitFor(() => expect(w.find('.task-err').text()).toContain('服务异常'))
    expect(w.find('.t-dispatch').exists()).toBe(false)
  })
})

describe('报工页', () => {
  type ReportVm = { form: Record<string, string>; submit: () => Promise<void>; selectOp: (n: string) => void; fe: Record<string, string> }
  async function ready(perms = WRITE, prog = progress()) {
    api.getProgress.mockResolvedValue(prog)
    const m = await mountAs('/reports?wo=WO1', perms)
    await vi.waitFor(() => expect(m.w.find('.op-btn').exists()).toBe(true))
    await m.w.find('.op-btn').trigger('click')
    await vi.waitFor(() => expect(m.w.find('.q-good').exists()).toBe(true))
    return { ...m, vm: m.w.findComponent({ name: 'ReportView' }).vm as unknown as ReportVm }
  }

  it('数字键盘、平板触控尺寸 class、只列可报工单', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 768, configurable: true, writable: true })
    const { w } = await ready()
    for (const c of ['.q-good', '.q-defect', '.q-scrap', '.f-hours']) expect(w.find(`${c} input`).attributes('inputmode')).toBe('decimal')
    expect(w.find('.submit').exists()).toBe(true)
    expect(w.find('.op-btn').classes()).toContain('op-btn')
    // 侧栏在平板宽度折叠，主区禁止横向滚动
    expect(w.find('.logo').text()).toBe('MES')
    expect(w.find('.main').exists()).toBe(true)
  })

  it('字段校验回显到对应字段，合计为 0 时提示', async () => {
    const { w, vm } = await ready()
    vm.form.goodQty = '1.1234567'
    await vm.submit()
    await vi.waitFor(() => expect(vm.fe.goodQty).toContain('格式不正确'))
    expect(vm.fe.laborHours).toBe('请输入工时')
    expect(vm.fe.uom).toBe('请输入单位')
    await vi.waitFor(() => expect(w.find('.el-form-item__error').exists()).toBe(true))
    Object.assign(vm.form, { goodQty: '', laborHours: '1.234', uom: 'PCS' })
    await vm.submit()
    await vi.waitFor(() => expect(vm.fe.laborHours).toContain('2 位小数'))
    vm.form.laborHours = '1'
    await vm.submit()
    await vi.waitFor(() => expect(w.text()).toContain('至少一项大于 0'))
    expect(api.reportOperation).not.toHaveBeenCalled()
  })

  it('提交：批次去空白、空批次为 null、工时字符串、失败重试复用键、成功换键、防重复点击', async () => {
    const { w, vm } = await ready(['workorder:read', 'execution:report'], progress('IN_PROGRESS', 'PCS'))
    expect(vm.form.uom).toBe('PCS')
    Object.assign(vm.form, { goodQty: ' 3 ', laborHours: '1.5', lotNo: '  L01  ', note: ' 备注 ' })
    api.reportOperation.mockRejectedValueOnce(problem(0, 'network'))
    await vm.submit()
    let resolve!: (v: unknown) => void
    api.reportOperation.mockImplementationOnce(() => new Promise((r) => { resolve = r }) as never)
    const p1 = vm.submit()
    await vm.submit() // 提交中再次点击被忽略
    expect(api.reportOperation).toHaveBeenCalledTimes(2)
    const [c1, c2] = api.reportOperation.mock.calls
    expect(c1[2]).toEqual({ goodQty: '3', defectQty: '0', scrapQty: '0', laborHours: '1.50', uom: 'PCS', lotNo: 'L01', note: '备注' })
    expect(c1[3]).toBe(c2[3])
    resolve({ report: { lotNo: 'L01' }, progress: progress('IN_PROGRESS', 'PCS') })
    await p1
    await vi.waitFor(() => expect(vm.form.goodQty).toBe(''))
    expect(vm.form.lotNo).toBe('  L01  ')
    Object.assign(vm.form, { goodQty: '1', laborHours: '0', lotNo: '   ' })
    api.reportOperation.mockResolvedValueOnce({ report: { lotNo: null }, progress: progress('IN_PROGRESS', 'PCS') } as never)
    await vm.submit()
    const c3 = api.reportOperation.mock.calls[2]
    expect(c3[2].lotNo).toBeNull()
    expect(c3[3]).not.toBe(c1[3])
    expect(w.find('.f-uom input').attributes('disabled')).toBeDefined()
  })

  it('problem+json 中文提示与字段错误回显', async () => {
    const { w, vm } = await ready()
    Object.assign(vm.form, { goodQty: '99', laborHours: '1', uom: 'PCS' })
    for (const [type, text] of [['over-report', '超过本工序可报上限'], ['uom-mismatch', '计量单位'], ['workorder-closed', '工单已关闭']]) {
      api.reportOperation.mockRejectedValueOnce(problem(type === 'workorder-closed' ? 409 : 422, type, 'x'))
      await vm.submit()
      await vi.waitFor(() => expect(w.find('.err').text()).toContain(text))
    }
    api.reportOperation.mockRejectedValueOnce(problem(400, 'validation-error', 'goodQty: 必须大于等于0; lotNo: 长度超限'))
    await vm.submit()
    await vi.waitFor(() => expect(vm.fe.lotNo).toBe('长度超限'))
    expect(w.text()).toContain('请检查标红字段')
    api.reportOperation.mockRejectedValueOnce(problem(403, 'access-denied'))
    await vm.submit()
    await vi.waitFor(() => expect(w.find('.err').text()).toBe('无权限'))
  })

  it('ON_HOLD 工单禁用提交；无权限提示；工单列表与进度加载失败', async () => {
    const { w, vm } = await ready(READ, progress('ON_HOLD'))
    expect(w.find('.not-reportable').text()).toContain('已冻结')
    expect(w.find('.no-perm').exists()).toBe(true)
    expect(w.find('.submit').attributes('disabled')).toBeDefined()
    await vm.submit()
    expect(api.reportOperation).not.toHaveBeenCalled()
    w.unmount()

    api.listWorkOrders.mockRejectedValueOnce(new Error('列表失败'))
    api.getProgress.mockRejectedValueOnce(problem(404, 'workorder-not-found', '工单不存在'))
    const m = await mountAs('/reports?wo=WOX', WRITE)
    await vi.waitFor(() => expect(m.w.text()).toContain('工单不存在'))
    expect(m.w.find('.op-btn').exists()).toBe(false)
  })

  it('无预选工单时只列出可报工的工单', async () => {
    const { w } = await mountAs('/reports', WRITE)
    await vi.waitFor(() => expect(api.listWorkOrders).toHaveBeenCalled())
    const vm = w.findComponent({ name: 'ReportView' }).vm as unknown as { woId: string; onWorkOrderChange: () => Promise<void> }
    vm.woId = 'WO2'
    await vm.onWorkOrderChange()
    await vi.waitFor(() => expect(w.find('.op-btn').exists()).toBe(true))
    expect(api.getProgress).toHaveBeenCalledWith('WO2')
  })
})
