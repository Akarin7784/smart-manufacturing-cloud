/**
 * 权限常量与判断（设计稿 §5）：
 * - execution:dispatch：派工 / 改派 / 取消派工（ADMIN、新角色 PLANNER）
 * - execution:report：开工 / 暂停 / 恢复 / 报工（ADMIN、OPERATOR）
 * - 关闭工单：只需 workorder:write（#75 起工单服务用 client_credentials 服务令牌查询质量服务，不再透传用户令牌）
 * 兼容期内持有 workorder:write 也可执行上述操作，后端收紧后前端无需改动。
 */
export const PERM_WORKORDER_WRITE = 'workorder:write'
export const PERM_EXECUTION_REPORT = 'execution:report'
export const PERM_EXECUTION_DISPATCH = 'execution:dispatch'

type Can = (p: string) => boolean

/** 是否可报工 / 开工 / 暂停 / 恢复。 */
export function canReportWith(can: Can): boolean {
  return can(PERM_EXECUTION_REPORT) || can(PERM_WORKORDER_WRITE)
}

/** 是否可派工 / 改派 / 取消派工。 */
export function canDispatchWith(can: Can): boolean {
  return can(PERM_EXECUTION_DISPATCH) || can(PERM_WORKORDER_WRITE)
}

/** 是否可关闭工单：仅需 workorder:write（质量服务查询由后端服务令牌完成，用户无需 quality:read）。 */
export function canCloseWith(can: Can): boolean {
  return can(PERM_WORKORDER_WRITE)
}
