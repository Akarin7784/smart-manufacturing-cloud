-- 开发环境设备表及种子数据；条件插入允许服务重启，不覆盖已有设备状态。
CREATE TABLE IF NOT EXISTS device (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL
);
INSERT INTO device (id, name, status)
SELECT 'D001', '数控加工中心', 'ONLINE'
WHERE NOT EXISTS (SELECT 1 FROM device WHERE id = 'D001');
INSERT INTO device (id, name, status)
SELECT 'D002', '工业机械臂', 'OFFLINE'
WHERE NOT EXISTS (SELECT 1 FROM device WHERE id = 'D002');

-- 独立设备预留表，设备唯一键防止资源被重复占用。
CREATE TABLE IF NOT EXISTS device_reservation (reservation_id VARCHAR(36) NOT NULL, device_id VARCHAR(64) PRIMARY KEY);
