-- 收件箱记录与业务投影同事务写入，重复 event_id 不会重复产生业务结果。
CREATE TABLE IF NOT EXISTS processed_event (
  event_id VARCHAR(64) PRIMARY KEY,
  payload_hash VARCHAR(64) NOT NULL,
  processed_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS work_order_event (
  event_id VARCHAR(64) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  device_id VARCHAR(64) NOT NULL,
  product VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  received_at TIMESTAMP NOT NULL
);
