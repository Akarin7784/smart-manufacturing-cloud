-- 认证服务初始表结构（Flyway V1，由原 schema.sql 转换）。用户数据只存放在认证库，密码字段仅保存 BCrypt 散列。
-- 不使用 IF NOT EXISTS：表已存在说明库状态与迁移历史不一致，应立即失败而不是静默跳过（见 application.yml 的 baseline 说明）。
CREATE TABLE auth_user (
  id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP NULL
);
