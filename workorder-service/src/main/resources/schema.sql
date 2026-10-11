-- 工单服务独立管理工单表；设备编号仅作为外部标识，不建立跨服务外键。
CREATE TABLE IF NOT EXISTS work_order (
  id VARCHAR(64) PRIMARY KEY,
  device_id VARCHAR(64) NOT NULL,
  product VARCHAR(128) NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(16) NOT NULL
);
