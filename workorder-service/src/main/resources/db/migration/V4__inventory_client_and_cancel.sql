-- 库存预留客户端接入与工单取消（设计已在群内评审通过）。workorder 独立 Flyway 历史：V1–V3 不得修改，本 V4 只追加。
-- 团队约定：不使用 IF NOT EXISTS；数量一律 DECIMAL(18,6)。

-- 预留镜像记录已处理的库存事件版本：库存事件带 aggregateVersion（按预留单单调递增、可能不连续），
-- 消费端丢弃不大于本列的事件。为空表示尚未处理过任何事件。
ALTER TABLE wo_material_reservation ADD COLUMN aggregate_version BIGINT NULL;

-- 预留释放任务（取消工单时与状态变更同一本地事务写入，相当于面向库存 HTTP 接口的 outbox）：
-- 后台作业 ReservationReleaseWorker 领取 PENDING 任务调用库存释放接口；
--   DONE：库存返回 2xx 且预留为 RELEASED / EXPIRED；STOPPED：库存答复不可释放（409 reservation-consumed 或 404），不再重试，已写审计并告警；
--   失败（5xx、网络、401/403）只累加 attempts 并按指数退避写 next_attempt_at。
-- idempotency_key 唯一：同一张预留单只会生成一个取消释放任务（重复取消不会再次释放），键同时作为库存侧 Idempotency-Key。
CREATE TABLE wo_reservation_release_task (
  task_id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  reservation_id VARCHAR(64) NOT NULL,
  reason VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NULL,
  last_error VARCHAR(512) NULL,
  result_status VARCHAR(16) NULL,
  created_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  CONSTRAINT uk_wo_reservation_release_task_key UNIQUE (idempotency_key),
  CONSTRAINT ck_wo_reservation_release_task_status CHECK (status IN ('PENDING', 'DONE', 'STOPPED')),
  INDEX idx_wo_reservation_release_task_due (status, next_attempt_at, created_at)
);

-- 库存交互审计：只追加。记录释放完成、释放被拒（预留已扣减等需人工处理的情况）与认证失败，供排查与对账。
CREATE TABLE wo_inventory_audit (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  reservation_id VARCHAR(64) NOT NULL,
  event VARCHAR(48) NOT NULL,
  detail VARCHAR(512) NULL,
  created_at TIMESTAMP NOT NULL,
  INDEX idx_wo_inventory_audit_order (order_id, created_at)
);
