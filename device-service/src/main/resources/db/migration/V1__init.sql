-- 设备服务初始表结构（Flyway V1，由原 schema.sql 转换）。种子设备不再写在迁移脚本中：
-- 演示设备在 db/demo/R__demo_devices.sql（仅 demo profile），测试设备在测试资源 db/testdata 中。
-- 保留 IF NOT EXISTS：旧开发库已有该表时，基线 0 之后执行 V1 不会报错。
CREATE TABLE IF NOT EXISTS device (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL
);
