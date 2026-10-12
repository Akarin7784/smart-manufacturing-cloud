-- 工单服务独立管理工单表；设备编号仅作为外部标识，不建立跨服务外键。
CREATE TABLE IF NOT EXISTS work_order (
  id VARCHAR(64) PRIMARY KEY,
  device_id VARCHAR(64) NOT NULL,
  product VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(16) NOT NULL
);

-- 创建工单幂等记录：按（调用方、幂等键）唯一，保存请求摘要与首次生成的工单号。
-- 单独建表而非给 work_order 加列，原因是 CREATE TABLE IF NOT EXISTS 无法为已有开发库补列；
-- 上线改 Flyway 后可合并为正式迁移脚本。与工单写入在同一本地事务中提交，保证“有记录必有工单”。
CREATE TABLE IF NOT EXISTS work_order_request (
  principal VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  order_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_work_order_request PRIMARY KEY (principal, idempotency_key),
  -- 供按保留期批量清理使用（见下方保留策略）。
  INDEX idx_work_order_request_created_at (created_at)
);
-- 保留策略：幂等记录保留 7 天。清理语句（由后续定时任务或运维作业执行，按批次限量删除以避免长事务）：
--   DELETE FROM work_order_request WHERE created_at < NOW() - INTERVAL 7 DAY LIMIT 1000;
-- 业务语义：超过保留期后同一个键视为新请求，可再次创建工单；客户端重试窗口必须远小于 7 天。
-- 只删除幂等记录，不删除工单本身。
