-- 首次初始化开发数据卷时创建独立数据库和账号，限制每个服务的数据库访问范围。
CREATE DATABASE IF NOT EXISTS manufacturing_device CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS manufacturing_workorder CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'device_app'@'%' IDENTIFIED BY 'local-demo-password';
CREATE USER IF NOT EXISTS 'workorder_app'@'%' IDENTIFIED BY 'local-demo-password';
GRANT ALL PRIVILEGES ON manufacturing_device.* TO 'device_app'@'%';
GRANT ALL PRIVILEGES ON manufacturing_workorder.* TO 'workorder_app'@'%';
