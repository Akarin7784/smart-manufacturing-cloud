-- 质检服务初始表结构（Flyway V1）。质检服务使用独立库 manufacturing_quality，工单号、物料编码、报工 ID 只作外部标识，不建跨服务外键。
-- 不使用 IF NOT EXISTS：表已存在说明库状态与迁移历史不一致，应立即失败而不是静默跳过。
-- 所有数量列统一 DECIMAL(18,6)，与契约 Quantity 的取值范围一致。

-- NCR（不合格品处置单）：一次报工一张，source_report_id 唯一（消费幂等的第二道保险）。
-- version 每次状态或处置变化 +1，即事件 aggregateVersion；CHECK 保证处置累计量不超过不良量（行锁之外的兜底）。
CREATE TABLE ncr (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ncr_no VARCHAR(32) NOT NULL,
  source_report_id VARCHAR(64) NOT NULL,
  work_order_no VARCHAR(64) NOT NULL,
  operation_no VARCHAR(64) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  lot_no VARCHAR(64),
  uom VARCHAR(16) NOT NULL,
  defect_qty DECIMAL(18,6) NOT NULL,
  disposed_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  void_reason VARCHAR(32),
  reversed_report_id VARCHAR(64),
  reversal_id VARCHAR(64),
  version BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_ncr PRIMARY KEY (id),
  CONSTRAINT uk_ncr_no UNIQUE (ncr_no),
  CONSTRAINT uk_ncr_source_report UNIQUE (source_report_id),
  CONSTRAINT ck_ncr_disposed CHECK (disposed_qty >= 0 AND disposed_qty <= defect_qty),
  INDEX idx_ncr_status_created (status, created_at),
  INDEX idx_ncr_work_order (work_order_no, created_at)
);

-- NCR 编号按日流水：NCR-yyyyMMdd-####，以（日期）行锁分配，多实例不重号。
CREATE TABLE ncr_daily_seq (
  seq_date CHAR(8) NOT NULL,
  last_no INT NOT NULL,
  CONSTRAINT pk_ncr_daily_seq PRIMARY KEY (seq_date)
);

-- 处置行：只追加不修改；line_no 为该 NCR 内的登记顺序。
CREATE TABLE ncr_disposition (
  disposition_id VARCHAR(36) NOT NULL,
  ncr_no VARCHAR(32) NOT NULL,
  line_no INT NOT NULL,
  type VARCHAR(16) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  reason VARCHAR(500),
  operator VARCHAR(128) NOT NULL,
  disposed_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_ncr_disposition PRIMARY KEY (disposition_id),
  CONSTRAINT uk_ncr_disposition_line UNIQUE (ncr_no, line_no),
  CONSTRAINT ck_ncr_disposition_qty CHECK (qty > 0)
);

-- 已消费事件：与业务写入同一事务，主键冲突即重复投递，直接 ACK。
CREATE TABLE consumed_event (
  event_id VARCHAR(64) NOT NULL,
  tag VARCHAR(64) NOT NULL,
  consumed_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_consumed_event PRIMARY KEY (event_id)
);

-- 报工级串行锁：同一报工的 Reported 与 Reversed 并发消费时，先锁这一行再查 NCR / 暂存，
-- 避免两边各自“看不到对方”而留下孤儿暂存（READ COMMITTED 下 NCR 与暂存行都可能尚未提交）。
CREATE TABLE defect_report_lock (
  report_id VARCHAR(64) NOT NULL,
  CONSTRAINT pk_defect_report_lock PRIMARY KEY (report_id)
);

-- 暂存冲销：冲销先于原报工到达时写入；WAITING → MATCHED（原报工 TTL 内到达）或 EXPIRED（超时）。
CREATE TABLE pending_reversal (
  reversed_report_id VARCHAR(64) NOT NULL,
  reversal_id VARCHAR(64) NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  received_at TIMESTAMP(3) NOT NULL,
  ncr_no VARCHAR(32),
  updated_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_pending_reversal PRIMARY KEY (reversed_report_id),
  INDEX idx_pending_reversal_status (status, received_at)
);

-- 冲销冲突：已有处置的 NCR 收到冲销，NCR 不回退，只记录供人工处理。
CREATE TABLE ncr_conflict (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL,
  ncr_no VARCHAR(32) NOT NULL,
  reversed_report_id VARCHAR(64) NOT NULL,
  reversal_id VARCHAR(64) NOT NULL,
  disposed_qty DECIMAL(18,6) NOT NULL,
  recorded_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_ncr_conflict PRIMARY KEY (id),
  CONSTRAINT uk_ncr_conflict_event UNIQUE (event_id),
  INDEX idx_ncr_conflict_recorded (recorded_at)
);

-- 幂等记录：（调用方，幂等键）唯一，保存首次响应供重放；按 created_at 分批清理（保留 7 天）。
CREATE TABLE idempotency_record (
  principal VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  operation VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_status INT NOT NULL,
  response_body MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_idempotency_record PRIMARY KEY (principal, idempotency_key),
  INDEX idx_idempotency_record_created_at (created_at)
);

-- outbox：事件与业务数据同事务写入，由发布任务异步投递到 mes-quality-event（至少一次）；aggregate_id 为 ncrNo。
CREATE TABLE outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(36) NOT NULL,
  payload MEDIUMTEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(3) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  sent_at TIMESTAMP(3),
  last_error VARCHAR(512),
  CONSTRAINT pk_outbox_event PRIMARY KEY (id),
  CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
  INDEX idx_outbox_event_pending (status, next_attempt_at)
);
