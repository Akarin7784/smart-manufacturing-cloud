-- 设备服务初始表结构（Flyway V1，由原 schema.sql 转换）。种子设备不再写在迁移脚本中：
-- 演示设备在 db/demo/R__demo_devices.sql（仅 demo profile），测试设备在测试资源 db/testdata 中。
-- 不使用 IF NOT EXISTS：表已存在说明库状态与迁移历史不一致，应立即失败而不是静默跳过（见 application.yml 的 baseline 说明）。
CREATE TABLE device (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL
);
