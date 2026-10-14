-- M1 工序派工与任务状态（设计见 docs/workorder-dispatch-design.md）。只新增迁移，不修改 V1–V5。
-- 团队约定：不使用 IF NOT EXISTS。新增列全部可空或带默认值，已下达工单的数据无需迁移（未派工 = station_code 为空）。

-- 派工信息：工位、指派操作工（auth 用户 id）、计划起止、最近派工人/时间、首次开工与最近暂停时间。
ALTER TABLE operation_task ADD COLUMN station_code VARCHAR(64) NULL;
ALTER TABLE operation_task ADD COLUMN operator VARCHAR(64) NULL;
ALTER TABLE operation_task ADD COLUMN planned_start TIMESTAMP(3) NULL;
ALTER TABLE operation_task ADD COLUMN planned_end TIMESTAMP(3) NULL;
ALTER TABLE operation_task ADD COLUMN dispatched_at TIMESTAMP(3) NULL;
ALTER TABLE operation_task ADD COLUMN dispatched_by VARCHAR(128) NULL;
ALTER TABLE operation_task ADD COLUMN started_at TIMESTAMP(3) NULL;
ALTER TABLE operation_task ADD COLUMN paused_at TIMESTAMP(3) NULL;
-- 工位工作台按工位、状态查询任务。
CREATE INDEX idx_operation_task_station ON operation_task (station_code, status);

-- 任务动作日志：只追加，用于审计与展示改派历史；action 取值见设计 §2。
CREATE TABLE operation_task_log (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  op_no VARCHAR(8) NOT NULL,
  action VARCHAR(24) NOT NULL,
  from_status VARCHAR(24) NOT NULL,
  to_status VARCHAR(24) NOT NULL,
  station_code VARCHAR(64) NULL,
  operator VARCHAR(64) NULL,
  reason VARCHAR(256) NULL,
  actor VARCHAR(128) NOT NULL,
  request_id VARCHAR(64) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  INDEX idx_operation_task_log_order (order_id, op_no, created_at)
);

-- 报工流水记录报工当时的工位（之后改派不改历史记录）与本次总工时（小时，2 位小数，冲销记录为负数）。
ALTER TABLE wo_operation_report ADD COLUMN station_code VARCHAR(64) NULL;
ALTER TABLE wo_operation_report ADD COLUMN labor_hours DECIMAL(10,2) NOT NULL DEFAULT 0;
-- 工序累计工时，与数量一起在锁住工单行的事务内增减。
ALTER TABLE wo_operation_progress ADD COLUMN labor_hours DECIMAL(12,2) NOT NULL DEFAULT 0;
