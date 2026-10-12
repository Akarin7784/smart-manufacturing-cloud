-- 工单下达与物料预留（设计文档 3.1 状态机、2.3 事件）。workorder 服务独立 Flyway 历史：V1__init（运维维护）+ 本 V2。
-- 团队约定：迁移不使用 IF NOT EXISTS（重复执行应失败而不是静默跳过），不对 V1 的表做 ALTER。
-- 由于不 ALTER V1，生命周期字段放在独立的 work_order_lifecycle 表，work_order.status 仍同步维护以兼容现有列表接口。

-- 工单生命周期：乐观锁版本、冻结后的恢复目标、冻结的 BOM / 工艺版本、下达幂等键。
-- 主键即工单号：并发下达同一工单时只有一个事务能插入成功（唯一约束裁决）。
CREATE TABLE work_order_lifecycle (
  order_id VARCHAR(64) PRIMARY KEY,
  version BIGINT NOT NULL,
  resume_target VARCHAR(16) NULL,
  product_code VARCHAR(64) NOT NULL,
  bom_version VARCHAR(8) NOT NULL,
  routing_version VARCHAR(8) NOT NULL,
  release_key VARCHAR(128) NOT NULL,
  released_by VARCHAR(128) NOT NULL,
  released_at TIMESTAMP NOT NULL
);

-- 冻结原因集合：可多原因并存，主键去重使同一原因重复冻结幂等。
CREATE TABLE work_order_hold_reason (
  order_id VARCHAR(64) NOT NULL,
  reason VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_work_order_hold_reason PRIMARY KEY (order_id, reason)
);

-- BOM 展开快照：下达时冻结，之后不再回查主数据；数量列 DECIMAL(18,6) 与库存契约精度一致；source_bom_version 记录每行来源版本。
CREATE TABLE wo_bom_snapshot (
  order_id VARCHAR(64) NOT NULL,
  seq INT NOT NULL,
  level INT NOT NULL,
  path VARCHAR(512) NOT NULL,
  parent_code VARCHAR(64) NOT NULL,
  component_code VARCHAR(64) NOT NULL,
  qty_per DECIMAL(18,6) NOT NULL,
  required_qty DECIMAL(18,6) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  issue_mode VARCHAR(16) NOT NULL,
  consume_op_no VARCHAR(8) NULL,
  leaf BOOLEAN NOT NULL,
  source_bom_version VARCHAR(8) NOT NULL,
  CONSTRAINT pk_wo_bom_snapshot PRIMARY KEY (order_id, seq)
);

-- 工序任务：由工艺路线快照生成；version 供后续报工乐观锁使用。
CREATE TABLE operation_task (
  order_id VARCHAR(64) NOT NULL,
  op_no VARCHAR(8) NOT NULL,
  seq INT NOT NULL,
  name VARCHAR(128) NOT NULL,
  work_center_code VARCHAR(64) NOT NULL,
  setup_minutes INT NOT NULL,
  run_seconds_per_unit INT NOT NULL,
  report_point BOOLEAN NOT NULL,
  first_article_required BOOLEAN NOT NULL,
  inspection_point_codes VARCHAR(512) NOT NULL,
  status VARCHAR(24) NOT NULL,
  good_qty INT NOT NULL DEFAULT 0,
  scrap_qty INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_operation_task PRIMARY KEY (order_id, op_no)
);

-- 工单级物料预留镜像：预留归库存服务所有，此处只记录库存返回的预留单号与到期时间，不自行判定过期。
-- 一张工单同一时刻至多一条 ACTIVE 预留；重新预留时旧记录转为 EXPIRED/RELEASED 并以 seq 递增保留历史。
CREATE TABLE wo_material_reservation (
  order_id VARCHAR(64) NOT NULL,
  seq INT NOT NULL,
  reservation_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  last_renewed_at TIMESTAMP NULL,
  CONSTRAINT pk_wo_material_reservation PRIMARY KEY (order_id, seq),
  CONSTRAINT uk_wo_material_reservation_id UNIQUE (reservation_id),
  -- 续约作业按状态批量扫描。
  INDEX idx_wo_material_reservation_status (status, reservation_id)
);

-- 预留行：按物料汇总的叶子件需求；数量与库存契约一致为 DECIMAL(18,6)（十进制字符串，最多 6 位小数）；line_id 为库存返回的预留行号，后续按行、按批次消耗时引用。
CREATE TABLE wo_material_reservation_line (
  reservation_id VARCHAR(64) NOT NULL,
  line_id VARCHAR(64) NULL,
  material_code VARCHAR(64) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  uom VARCHAR(16) NOT NULL,
  CONSTRAINT pk_wo_material_reservation_line PRIMARY KEY (reservation_id, material_code)
);

-- 状态转换审计日志：只追加；记录前后状态、原因、操作人与请求号。
CREATE TABLE work_order_status_log (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  reason VARCHAR(64) NULL,
  note VARCHAR(256) NULL,
  operator VARCHAR(128) NOT NULL,
  request_id VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_work_order_status_log_order (order_id, created_at)
);

-- 事务性发件箱：与业务变更同事务写入；中继（RocketMQ，运维分支 ops/rocketmq）落地前事件只入库不发布，
-- published_at 为空表示待发布。消费方按 event_id 幂等。
CREATE TABLE outbox_event (
  event_id VARCHAR(36) PRIMARY KEY,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  published_at TIMESTAMP NULL,
  INDEX idx_outbox_event_pending (published_at, occurred_at)
);

-- 已消费事件：主键保证重复投递只处理一次。库存 ReservationExpired 按预留单号去重，键为 ReservationExpired:{reservationId}。
CREATE TABLE consumed_event (
  event_id VARCHAR(64) PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
