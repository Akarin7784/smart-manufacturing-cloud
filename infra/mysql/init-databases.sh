#!/usr/bin/env sh
# 数据库初始化（db-init 一次性容器执行）：创建各服务（认证、设备、工单、主数据、库存）独立数据库和专用账号，并把密码同步为 .env 中的值。
# 设计原因：SQL 文件无法引用环境变量，改用脚本避免把密码明文写入仓库；全部语句幂等，支持已有数据卷。
set -eu
: "${MYSQL_ROOT_PASSWORD:?}" "${MYSQL_APP_PASSWORD:?}"
# 通过环境变量传递 root 密码，避免出现在进程参数中。
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
# 再做一次连接等待，防御 MySQL 刚通过健康检查但尚未接受远程连接的短暂窗口。
i=0
until mysqladmin ping -h "${MYSQL_HOST:-mysql}" -uroot --silent 2>/dev/null; do
  i=$((i+1)); [ "$i" -le 60 ] || { echo "MySQL 不可连接" >&2; exit 1; }
  sleep 2
done
# 对单引号转义，防止密码中含特殊字符时破坏 SQL。
app=$(printf '%s' "$MYSQL_APP_PASSWORD" | sed "s/'/''/g")
mysql -h "${MYSQL_HOST:-mysql}" -uroot <<SQL
CREATE DATABASE IF NOT EXISTS manufacturing_auth CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS manufacturing_device CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS manufacturing_workorder CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS manufacturing_masterdata CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS manufacturing_inventory CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'auth_app'@'%' IDENTIFIED BY '$app';
CREATE USER IF NOT EXISTS 'device_app'@'%' IDENTIFIED BY '$app';
CREATE USER IF NOT EXISTS 'workorder_app'@'%' IDENTIFIED BY '$app';
CREATE USER IF NOT EXISTS 'masterdata_app'@'%' IDENTIFIED BY '$app';
CREATE USER IF NOT EXISTS 'inventory_app'@'%' IDENTIFIED BY '$app';
ALTER USER 'auth_app'@'%' IDENTIFIED BY '$app';
ALTER USER 'device_app'@'%' IDENTIFIED BY '$app';
ALTER USER 'workorder_app'@'%' IDENTIFIED BY '$app';
ALTER USER 'masterdata_app'@'%' IDENTIFIED BY '$app';
ALTER USER 'inventory_app'@'%' IDENTIFIED BY '$app';
GRANT ALL PRIVILEGES ON manufacturing_auth.* TO 'auth_app'@'%';
GRANT ALL PRIVILEGES ON manufacturing_device.* TO 'device_app'@'%';
GRANT ALL PRIVILEGES ON manufacturing_workorder.* TO 'workorder_app'@'%';
GRANT ALL PRIVILEGES ON manufacturing_masterdata.* TO 'masterdata_app'@'%';
GRANT ALL PRIVILEGES ON manufacturing_inventory.* TO 'inventory_app'@'%';
SQL
echo "数据库与账号初始化完成"
