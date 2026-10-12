-- 独立事件库初始化支持已有数据卷，不删除工单或认证数据。
CREATE DATABASE IF NOT EXISTS manufacturing_event CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'event_app'@'%' IDENTIFIED BY 'local-demo-password';
GRANT ALL PRIVILEGES ON manufacturing_event.* TO 'event_app'@'%';
