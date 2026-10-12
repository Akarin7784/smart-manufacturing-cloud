-- 库存服务初始表结构（Flyway V1）。库存服务使用独立库 manufacturing_inventory，物料编码、工单号只作外部标识，不建跨服务外键。
-- 不使用 IF NOT EXISTS：表已存在说明库状态与迁移历史不一致，应立即失败而不是静默跳过（与 #11 口径一致）。
-- 所有数量列统一 DECIMAL(18,6)：整数部分最多 12 位、小数最多 6 位，与 OpenAPI 契约中 Quantity 的取值范围一致。

-- 库位：仓库下的存放位置。停用的库位不允许入库，但保留历史库存与流水。
CREATE TABLE warehouse_location (
  code VARCHAR(64) NOT NULL,
  warehouse_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_warehouse_location PRIMARY KEY (code)
);

-- 物料计量单位登记：首次入库时登记，之后同一物料的入库、出库与预留必须使用相同 uom，防止 KG 与 PCS 混记在同一余额上。
CREATE TABLE inventory_material (
  material_code VARCHAR(64) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_inventory_material PRIMARY KEY (material_code)
);

-- 批次：按（物料、批次号）唯一，首次入库时自动登记，用于扣减时的批次追溯。
CREATE TABLE lot (
  material_code VARCHAR(64) NOT NULL,
  lot_no VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_lot PRIMARY KEY (material_code, lot_no)
);

-- 现存量：按（物料、库位、批次）一行。写入时先 SELECT ... FOR UPDATE 锁行（按 id 升序，避免死锁），
-- version 每次变更加一，供只读方做乐观校验及排查并发问题；CHECK 兜底保证余额不为负。
CREATE TABLE stock_on_hand (
  id BIGINT NOT NULL AUTO_INCREMENT,
  material_code VARCHAR(64) NOT NULL,
  location_code VARCHAR(64) NOT NULL,
  lot_no VARCHAR(64) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_stock_on_hand PRIMARY KEY (id),
  CONSTRAINT uk_stock_on_hand UNIQUE (material_code, location_code, lot_no),
  CONSTRAINT ck_stock_on_hand_qty CHECK (qty >= 0)
);

-- 出入库流水：只追加、不更新不删除（应用层无任何 UPDATE/DELETE 语句），qty 带符号（入库为正、出库与扣减为负），
-- balance_after 为该（物料、库位、批次）变更后的余额，便于对账。ref_type/ref_no 记录来源单据（如预留单、外部单号）。
CREATE TABLE stock_txn (
  id BIGINT NOT NULL AUTO_INCREMENT,
  txn_type VARCHAR(16) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  location_code VARCHAR(64) NOT NULL,
  lot_no VARCHAR(64) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  ref_type VARCHAR(32),
  ref_no VARCHAR(128),
  operator VARCHAR(128) NOT NULL,
  occurred_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_stock_txn PRIMARY KEY (id),
  INDEX idx_stock_txn_material (material_code, occurred_at)
);

-- 预留单头：status 取 ACTIVE/RELEASED/EXPIRED/CONSUMED；expires_at 由创建与续期设置。
-- (status, expires_at) 索引供过期任务按 SKIP LOCKED 扫描到期的 ACTIVE 预留单。
CREATE TABLE reservation (
  id VARCHAR(36) NOT NULL,
  work_order_no VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at TIMESTAMP(3) NOT NULL,
  release_reason VARCHAR(32),
  principal VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_reservation PRIMARY KEY (id),
  INDEX idx_reservation_status_expires (status, expires_at)
);

-- 预留行：一条 BOM 快照需求行一行；(material_code, status) 索引用于计算“有效预留量”（可用量 = 现存量 - 有效预留未扣减量）。
CREATE TABLE reservation_line (
  id VARCHAR(36) NOT NULL,
  reservation_id VARCHAR(36) NOT NULL,
  line_no INT NOT NULL,
  source_line_id VARCHAR(128) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  qty DECIMAL(18,6) NOT NULL,
  reserved_qty DECIMAL(18,6) NOT NULL,
  consumed_qty DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  CONSTRAINT pk_reservation_line PRIMARY KEY (id),
  CONSTRAINT uk_reservation_line_source UNIQUE (reservation_id, source_line_id),
  CONSTRAINT fk_reservation_line_header FOREIGN KEY (reservation_id) REFERENCES reservation (id),
  CONSTRAINT ck_reservation_line_consumed CHECK (consumed_qty >= 0 AND consumed_qty <= reserved_qty),
  INDEX idx_reservation_line_material (material_code, status)
);

-- 幂等记录：按（调用方 client_id 或用户 sub、幂等键）唯一。保存请求摘要与首次成功响应（状态码 + 响应体原文），
-- 重放时原样返回。与业务写入同一本地事务提交，保证“有记录必有结果”。保留 7 天，由 IdempotencyRecordCleaner 分批清理。
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

-- outbox：事件与业务数据同事务写入，由发布任务异步投递到 RocketMQ（至少一次）。
-- event_id 在补发时保持不变，消费端据此幂等；(status, next_attempt_at) 索引供发布任务按 SKIP LOCKED 取待发事件。
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
