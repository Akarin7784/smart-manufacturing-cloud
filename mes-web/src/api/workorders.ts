import { http } from './http'

/**
 * 工单服务接口（经 Gateway /api/workorders/** 路由，StripPrefix=1 后转发到 workorder-service /workorders/**）。
 * 类型与 cloud-api 中的 record 及 docs/api/workorder-openapi.yaml 保持一致；数量字段为 6 位小数的十进制字符串。
 */

/** 统一成功响应包装。 */
interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

/** 工单状态（与后端状态机一致）。 */
export type WorkOrderStatus = 'CREATED' | 'RELEASED' | 'IN_PROGRESS' | 'ON_HOLD' | 'COMPLETED' | 'CLOSED' | 'CANCELLED'

/** 冻结原因：可多原因并存，全部解除后才恢复。 */
export type HoldReason = 'MANUAL' | 'LOT_HELD' | 'FAI_FAILED' | 'EQUIPMENT_FAULT' | 'MATERIAL_RESERVATION_EXPIRED'

export interface WorkOrder {
  id: string
  deviceId: string
  product: string
  quantity: number
  status: WorkOrderStatus | string
}

export interface OperationTaskView {
  opNo: string
  name: string
  workCenterCode: string
  status: string
  reportPoint: boolean
  firstArticleRequired: boolean
  inspectionPointCodes: string[]
}

export interface ReservationLineView {
  lineId: string
  materialCode: string
  quantity: number | string
  uom: string
}

export interface MaterialReservationView {
  reservationId: string
  status: string
  expiresAt: string | null
  lines: ReservationLineView[]
}

/** 下达快照：版本、工序任务（即派工任务）与物料预留。 */
export interface WorkOrderReleaseView {
  orderId: string
  status: string
  version: number
  productCode: string
  bomVersion: string
  routingVersion: string
  releasedAt: string | null
  resumeTarget: string | null
  holdReasons: HoldReason[]
  bomLineCount: number
  operations: OperationTaskView[]
  reservation: MaterialReservationView | null
}

export interface OperationProgressView {
  operationNo: string
  seq: number
  goodQty: string
  defectQty: string
  scrapQty: string
  uom: string | null
  limit: string
  effectiveReports: number
  /** 以下为派工契约（M1）新增的附带信息，后端实现前可能缺失。 */
  name?: string
  workCenterCode?: string
  taskStatus?: string
  stationCode?: string | null
  operator?: string | null
  plannedStart?: string | null
  plannedEnd?: string | null
  version?: number
  laborHours?: string
}

export interface WorkOrderProgressView {
  workOrderNo: string
  status: string
  plannedQty: string
  operations: OperationProgressView[]
}

export interface OperationReportView {
  reportId: string
  type: string
  operationNo: string
  goodQty: string
  defectQty: string
  scrapQty: string
  uom: string
  lotNo: string | null
  note: string | null
  reversedReportId: string | null
  /** 本次报工工时（派工契约新增，后端实现前缺失）。 */
  laborHours?: string
  operator: string
  createdAt: string
}

/** 报工请求；lotNo 为空时后端回退为工单号。 */
export interface ReportOperationRequest {
  goodQty: string
  defectQty: string
  scrapQty: string
  /** 工时（小时，2 位小数字符串）。注意：后端 ReportOperationRequest 尚未定义该字段。 */
  laborHours: string
  uom: string
  lotNo: string | null
  note: string | null
}

export interface OperationReportResult {
  report: OperationReportView
  progress: WorkOrderProgressView
}

export interface WorkOrderCompletionView {
  progress: WorkOrderProgressView
  shortfall: string
  reason: string
}

/** 生成幂等键；调用方应在同一次业务提交的重试中复用同一个键。 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

const enc = encodeURIComponent

/** 工单列表（后端当前返回全量列表，无分页与筛选参数）。 */
export async function listWorkOrders(): Promise<WorkOrder[]> {
  return (await http.get<ApiResponse<WorkOrder[]>>('/workorders')).data.data
}

/** 下达快照（工序任务、预留、冻结原因）。 */
export async function getRelease(id: string): Promise<WorkOrderReleaseView> {
  return (await http.get<ApiResponse<WorkOrderReleaseView>>(`/workorders/${enc(id)}/release`)).data.data
}

/** 报工进度。 */
export async function getProgress(id: string): Promise<WorkOrderProgressView> {
  return (await http.get<ApiResponse<WorkOrderProgressView>>(`/workorders/${enc(id)}/progress`)).data.data
}

/** 报工流水。 */
export async function getReports(id: string): Promise<OperationReportView[]> {
  return (await http.get<ApiResponse<OperationReportView[]>>(`/workorders/${enc(id)}/reports`)).data.data
}

/** 工序报工（需 workorder:write，必须带 Idempotency-Key）。 */
export async function reportOperation(id: string, opNo: string, req: ReportOperationRequest, key: string): Promise<OperationReportResult> {
  const resp = await http.post<ApiResponse<OperationReportResult>>(`/workorders/${enc(id)}/operations/${enc(opNo)}/reports`, req, {
    headers: { 'Idempotency-Key': key }
  })
  return resp.data.data
}

/** 下达工单（需 workorder:write 与 masterdata:read）。 */
export async function releaseWorkOrder(id: string, key: string): Promise<WorkOrderReleaseView> {
  return (await http.post<ApiResponse<WorkOrderReleaseView>>(`/workorders/${enc(id)}/release`, null, { headers: { 'Idempotency-Key': key } })).data.data
}

/** 冻结（暂停）工单。 */
export async function holdWorkOrder(id: string, reason: HoldReason, note?: string): Promise<WorkOrderReleaseView> {
  return (await http.post<ApiResponse<WorkOrderReleaseView>>(`/workorders/${enc(id)}/hold`, { reason, note: note || null })).data.data
}

/** 解除一个冻结原因（恢复）。 */
export async function resumeWorkOrder(id: string, reason: HoldReason, note?: string): Promise<WorkOrderReleaseView> {
  return (await http.post<ApiResponse<WorkOrderReleaseView>>(`/workorders/${enc(id)}/resume`, { reason, note: note || null })).data.data
}

/** 手工完工（允许短缺，reason 必填）。 */
export async function completeWorkOrder(id: string, reason: string, key: string): Promise<WorkOrderCompletionView> {
  return (await http.post<ApiResponse<WorkOrderCompletionView>>(`/workorders/${enc(id)}/complete`, { reason }, { headers: { 'Idempotency-Key': key } })).data.data
}

/** 创建工单请求（与 cloud-api CreateWorkOrder 一致）。 */
export interface CreateWorkOrderRequest {
  deviceId: string
  product: string
  quantity: number
}

/** 幂等创建工单（需 workorder:write）：首次 201，同键重放 200。 */
export async function createWorkOrder(req: CreateWorkOrderRequest, key: string): Promise<WorkOrder> {
  return (await http.post<ApiResponse<WorkOrder>>('/workorders', req, { headers: { 'Idempotency-Key': key } })).data.data
}
