-- 工序报工（设计已在群内评审通过）。workorder 独立 Flyway 历史：V1__init、V2__workorder_release（均不得修改）+ 本 V3。
-- 团队约定：不使用 IF NOT EXISTS。数量一律 DECIMAL(18,6)，与库存契约一致。

-- 报工流水：只追加，不更新、不删除。冲销不改原记录，而是追加一条 type=REVERSAL、数量取负的记录，
-- reversed_report_id 指向被冲销的原报工；唯一约束保证同一报工最多被冲销一次（不支持部分冲销）。
CREATE TABLE wo_operation_report (
  report_id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  op_no VARCHAR(8) NOT NULL,
  type VARCHAR(16) NOT NULL,
  good_qty DECIMAL(18,6) NOT NULL,
  defect_qty DECIMAL(18,6) NOT NULL,
  scrap_qty DECIMAL(18,6) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  lot_no VARCHAR(64) NULL,
  note VARCHAR(256) NULL,
  reversed_report_id VARCHAR(36) NULL,
  operator VARCHAR(128) NOT NULL,
  request_id VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT ck_wo_operation_report_type CHECK (type IN ('REPORT', 'REVERSAL')),
  CONSTRAINT uk_wo_operation_report_reversed UNIQUE (reversed_report_id),
  INDEX idx_wo_operation_report_order (order_id, op_no, created_at)
);

-- 工序累计进度：按（工单, 工序号）汇总有效报工（REPORT 与 REVERSAL 相加）。报工事务内先对工单行加锁再更新本表，
-- 因此累计值与流水始终一致；effective_reports 为未被冲销的 REPORT 条数，供手工完工校验“每道工序至少一次有效报工”。
CREATE TABLE wo_operation_progress (
  order_id VARCHAR(64) NOT NULL,
  op_no VARCHAR(8) NOT NULL,
  seq INT NOT NULL,
  good_qty DECIMAL(18,6) NOT NULL,
  defect_qty DECIMAL(18,6) NOT NULL,
  scrap_qty DECIMAL(18,6) NOT NULL,
  uom VARCHAR(16) NULL,
  effective_reports INT NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT pk_wo_operation_progress PRIMARY KEY (order_id, op_no)
);

-- 写命令幂等记录（报工、冲销、手工完工）：保存首次成功处理的 HTTP 状态与响应体，同键重放原样返回；
-- 同键不同请求（摘要不同）返回 422 idempotency-key-conflict。与业务写入同一事务提交。保留期与 work_order_request 相同（7 天）。
CREATE TABLE wo_command_request (
  principal VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  command VARCHAR(32) NOT NULL,
  order_id VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_status INT NOT NULL,
  response_body TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_wo_command_request PRIMARY KEY (principal, idempotency_key),
  INDEX idx_wo_command_request_created_at (created_at)
);

-- 发件箱增加投递目标：topic / tag 取自 Nacos manufacturing.mq.*，写入时落库，中继按行投递；
-- V2 写入的旧事件两列为空，表示尚无投递目标。
ALTER TABLE outbox_event ADD COLUMN topic VARCHAR(128) NULL;
ALTER TABLE outbox_event ADD COLUMN tag VARCHAR(64) NULL;

-- 工单聚合版本：每次状态变更（下达、冻结、恢复、开工、完工、重开）递增 1，随全部工单状态事件以 aggregateVersion 发布；
-- 消费端丢弃版本不高于本地已处理版本的事件（Topic 为普通消息，不保证顺序），occurredAt 仅用于展示与审计。
ALTER TABLE work_order ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
