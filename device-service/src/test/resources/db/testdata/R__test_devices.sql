-- 测试专用设备数据（仅 test profile 的 Flyway 位置包含本目录）：测试断言依赖 D001 在线、D002 离线。
-- 与演示数据分离，保证测试不加载 db/demo。
INSERT INTO device (id, name, status) VALUES
  ('D001', '数控加工中心', 'ONLINE'),
  ('D002', '工业机械臂', 'OFFLINE')
ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status);
