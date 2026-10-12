-- V2（#30、#31）：坏消息记录表；去重表与报工锁表按时间清理所需的列与索引。

-- 不可恢复的坏消息（缺 eventId、JSON 解析失败、缺字段等）：直接 ACK 不重投，在此留档供人工排查。
-- 只存载荷摘要（SHA-256 + 前 512 个字符），不存完整原文，避免大消息撑大表。
CREATE TABLE invalid_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  msg_id VARCHAR(128) NOT NULL,
  tag VARCHAR(64) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  payload_excerpt VARCHAR(512) NOT NULL,
  payload_bytes INT NOT NULL,
  received_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT pk_invalid_event PRIMARY KEY (id),
  INDEX idx_invalid_event_received (received_at)
);

-- 报工锁表补创建时间（存量行取迁移时间，按此计算保留期），清理任务按时间索引分批删除。
ALTER TABLE defect_report_lock ADD COLUMN created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
CREATE INDEX idx_defect_report_lock_created ON defect_report_lock (created_at);
CREATE INDEX idx_consumed_event_consumed ON consumed_event (consumed_at);
