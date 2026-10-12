-- 工单服务独立管理工单表；设备编号仅作为外部标识，不建立跨服务外键。
CREATE TABLE IF NOT EXISTS work_order (
  id VARCHAR(64) PRIMARY KEY,
  device_id VARCHAR(64) NOT NULL,
  product VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(16) NOT NULL
);

-- 发件箱与工单同库；claim_token 防止过期发送者覆盖新租约，SENT 表示 Broker 已确认。
CREATE TABLE IF NOT EXISTS outbox_event (
  event_id VARCHAR(64) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  available_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  lease_until TIMESTAMP NULL,
  claim_token VARCHAR(64) NULL,
  last_error VARCHAR(512) NULL,
  sent_at TIMESTAMP NULL
);
