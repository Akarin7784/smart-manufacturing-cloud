-- V3：质检回写库存 QC-HOLD 的任务表（设计见 docs/quality-inventory-writeback-design.md §5）。
-- 任务与 NCR 状态变更、处置行在同一本地事务写入，由调度器在事务外调用库存服务后回写结果（事务外调用，避免持锁等待网络）。
-- 时间列统一 TIMESTAMP(3)，Java 写入前截断到毫秒（与 OutboxWriter 一致，#46），避免数据库四舍五入后 next_attempt_at 晚于写入时刻。
CREATE TABLE inventory_writeback (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ncr_no VARCHAR(32) NOT NULL,
  -- HOLD 入隔离；SCRAP 报废出库；RELEASE 返工/让步放行；VOID 作废扣回。
  action VARCHAR(16) NOT NULL,
  -- 放行原因：RELEASE 为 REWORK/CONCESSION，VOID 为 NCR_VOIDED，其余为 NULL。
  reason VARCHAR(16),
  -- 处置行编号（ncr_disposition.line_no，NCR 内只追加、不复用）；HOLD 与 VOID 为 NULL。
  disposition_id BIGINT,
  -- 调用库存的固定幂等键：qc:{ncrNo}:hold / qc:{ncrNo}:disp:{dispositionId}:scrap|release / qc:{ncrNo}:void。
  idempotency_key VARCHAR(128) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  lot_no VARCHAR(64),
  uom VARCHAR(16) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  -- PENDING / RUNNING（已领取、租约内执行中）/ DONE / FAILED / CANCELLED / RESOLVED_MANUALLY；DONE、CANCELLED、RESOLVED_MANUALLY 为终态。
  status VARCHAR(20) NOT NULL,
  -- 本轮尝试次数：调度器领取时先 +1 再调用库存（领取即视为“已尝试”）；人工重试清零。
  attempts INT NOT NULL DEFAULT 0,
  -- 首次被领取执行的时间，人工重试也不清空：作废时据此判断 HOLD 是否“从未尝试过”（attempts 会被人工重试清零，不能单独作依据）。
  first_attempted_at TIMESTAMP(3) NULL,
  -- PENDING 时为下次可执行时间；RUNNING 时为租约到期时间（进程崩溃后到期可被重新领取）；终态与 FAILED 为 NULL。
  next_attempt_at TIMESTAMP(3) NULL,
  -- FAILED 的性质：REJECTED 库存明确拒绝（确定未记账）；UNCERTAIN 超时、5xx 等重试耗尽（是否记账不确定）。
  failure_kind VARCHAR(16),
  last_error VARCHAR(512),
  inventory_txn_id BIGINT,
  -- 人工结案审计：操作人（令牌 sub）、原因、时间。
  resolved_by VARCHAR(128),
  resolved_reason VARCHAR(512),
  resolved_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_inventory_writeback PRIMARY KEY (id),
  CONSTRAINT uk_inventory_writeback_key UNIQUE (idempotency_key),
  CONSTRAINT ck_inventory_writeback_qty CHECK (qty > 0),
  -- 调度领取：按状态与到期时间扫描。
  INDEX idx_inventory_writeback_due (status, next_attempt_at),
  -- 同一 NCR 按 id 串行、关闭校验与查询接口。
  INDEX idx_inventory_writeback_ncr (ncr_no, id),
  -- 终态任务按更新时间清理（保留 30 天）。
  INDEX idx_inventory_writeback_updated (status, updated_at)
);
