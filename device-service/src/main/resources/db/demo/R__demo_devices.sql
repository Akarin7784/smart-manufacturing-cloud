-- 演示设备（Flyway 可重复迁移，仅 demo profile 加载；冒烟测试依赖 D001 在线、D002 离线）。
-- 幂等：固定主键 + ON DUPLICATE KEY UPDATE，重复执行行数不变；不做无条件 DELETE，不影响非演示设备。
-- 名称与状态在重放时会被重置为演示初值，便于 demo 环境回到可预期状态。
-- 减速机产线设备编码以 docs/mes-product-domain-design.md 为准，后续由业务开发按工位补充。
INSERT INTO device (id, name, status) VALUES
  ('D001', '数控加工中心', 'ONLINE'),
  ('D002', '工业机械臂', 'OFFLINE')
ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status);
