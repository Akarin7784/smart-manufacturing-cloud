#!/usr/bin/env sh
# 迁移幂等校验（CI 的 migrations twice job 使用，也可在本机执行）：
# 对每个有数据库的服务，在同一个空库上执行两次 Flyway 迁移（db/migration + db/demo），
# 第二次前把可重复迁移（R__）的校验和改掉以强制重放，然后断言：
#   1) 两次迁移后各业务表行数完全一致（演示数据脚本幂等，无重复插入、无误删）；
#   2) 每个 R__ 脚本确实执行了两次（防止“没重放所以行数相等”的假通过）。
# 只做 CI 双跑校验；基于 Testcontainers 的迁移集成测试归 MES测试与质量维护。
# 依赖：docker；环境变量 MYSQL_HOST（默认 127.0.0.1）、MYSQL_PORT（默认 3306）、MYSQL_ROOT_PASSWORD（必填）。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
host=${MYSQL_HOST:-127.0.0.1}
port=${MYSQL_PORT:-3306}
: "${MYSQL_ROOT_PASSWORD:?需要 MYSQL_ROOT_PASSWORD}"
# Flyway CLI 版本与 Spring Boot 3.5 管理的 flyway-core 版本保持一致。
flyway_image=${FLYWAY_IMAGE:-flyway/flyway:11.7.2-alpine}
mysql_image=${MYSQL_CLIENT_IMAGE:-mysql:8.4}

# 通过 mysql 客户端镜像执行 SQL（使用宿主网络连接 CI 的 MySQL 服务容器），密码经环境变量传递。
sql() {
  docker run --rm --network host -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$mysql_image" \
    mysql -h "$host" -P "$port" -uroot -N -B -e "$1"
}

# 执行一次 Flyway 迁移；库由调用方预先创建（URL 指向不存在的库时驱动会一直重试连接），
# locations 指向仓库内的迁移目录（只读挂载）；connectRetries 限制较小，避免异常时长时间挂起。
migrate() {
  docker run --rm --network host -v "$root:/repo:ro" "$flyway_image" \
    -url="jdbc:mysql://$host:$port/$1?allowPublicKeyRetrieval=true&useSSL=false" -user=root -password="$MYSQL_ROOT_PASSWORD" \
    -schemas="$1" -locations="$2" -connectRetries=5 migrate >/dev/null
}

# 输出某库全部业务表（排除迁移历史表）的“表名 行数”，按表名排序便于逐行比较。
counts() {
  for t in $(sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$1' AND table_name <> 'flyway_schema_history' ORDER BY table_name"); do
    printf '%s %s\n' "$t" "$(sql "SELECT COUNT(*) FROM \`$1\`.\`$t\`")"
  done
}

failed=0
for svc in auth-service device-service workorder-service masterdata-service inventory-service; do
  res="$svc/src/main/resources/db"
  [ -d "$root/$res/migration" ] || { echo "跳过 $svc：没有 db/migration"; continue; }
  db="verify_$(printf '%s' "${svc%-service}" | tr '-' '_')"
  locations="filesystem:/repo/$res/migration"
  repeatables=0
  if [ -d "$root/$res/demo" ]; then
    locations="$locations,filesystem:/repo/$res/demo"
    repeatables=$(ls "$root/$res/demo"/R__*.sql 2>/dev/null | wc -l | tr -d ' ')
  fi
  sql "DROP DATABASE IF EXISTS \`$db\`; CREATE DATABASE \`$db\` CHARACTER SET utf8mb4"
  migrate "$db" "$locations"
  first=$(counts "$db")
  # 修改已应用 R__ 记录的校验和，使 Flyway 认为脚本已变化而重放（模拟演示脚本更新后的再次执行）。
  sql "UPDATE \`$db\`.flyway_schema_history SET checksum = COALESCE(checksum, 0) + 1 WHERE version IS NULL AND success = 1"
  migrate "$db" "$locations"
  second=$(counts "$db")
  runs=$(sql "SELECT COUNT(*) FROM \`$db\`.flyway_schema_history WHERE version IS NULL AND success = 1")
  echo "== $svc（R__ 脚本 $repeatables 个，成功执行记录 $runs 条）"
  printf '%s\n' "$first"
  if [ "$first" != "$second" ]; then
    echo "FAIL: $svc 两次迁移后行数不一致" >&2
    printf '第一次:\n%s\n第二次:\n%s\n' "$first" "$second" >&2
    failed=1
  fi
  if [ "$runs" -ne $((repeatables * 2)) ]; then
    echo "FAIL: $svc 的 R__ 脚本未被重放（期望 $((repeatables * 2)) 条执行记录，实际 $runs 条）" >&2
    failed=1
  fi
  sql "DROP DATABASE IF EXISTS \`$db\`"
done
[ "$failed" -eq 0 ] && echo "PASS: 所有服务的迁移在同一库上执行两次结果一致，演示数据脚本幂等"
exit "$failed"
