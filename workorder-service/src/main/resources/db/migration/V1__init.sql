-- 工单服务初始表结构（Flyway V1，由原 schema.sql 转换，含 #9 的幂等记录表）。
-- 工单服务独立管理工单表；设备编号仅作为外部标识，不建立跨服务外键。
-- 不使用 IF NOT EXISTS：表已存在说明库状态与迁移历史不一致，应立即失败而不是静默跳过（见 application.yml 的 baseline 说明）。
CREATE TABLE work_order (
  id VARCHAR(64) PRIMARY KEY,
  device_id VARCHAR(64) NOT NULL,
  product VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(16) NOT NULL
);

-- 创建工单幂等记录：按（调用方、幂等键）唯一，保存请求摘要与首次生成的工单号。
-- 单独建表而非给 work_order 加列：幂等记录有独立的保留期与清理节奏，与工单生命周期解耦。
-- 与工单写入在同一本地事务中提交，保证“有记录必有工单”。
CREATE TABLE work_order_request (
  principal VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  order_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_work_order_request PRIMARY KEY (principal, idempotency_key),
  -- 供按保留期批量清理使用（见下方保留策略）。
  INDEX idx_work_order_request_created_at (created_at)
);
-- 保留策略：幂等记录保留 7 天，由 IdempotencyRecordCleaner 定时按 created_at 索引分批删除（每批限量，避免长事务）。
-- 业务语义：超过保留期后同一个键视为新请求，可再次创建工单；客户端重试窗口必须远小于 7 天。
-- 只删除幂等记录，不删除工单本身。
