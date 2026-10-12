#!/usr/bin/env sh
# 一条命令启动完整演示环境：准备 .env → 构建并启动全部容器（等待 healthy）→ 冒烟测试。
# 用法：scripts/demo.sh [up|down|reset|smoke]；Makefile 的 demo 系列目标调用本脚本。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
compose="docker compose --profile app"
case "${1:-up}" in
  up)
    sh scripts/init-env.sh
    # --wait 会等待所有服务 healthy、一次性初始化容器成功退出，任一失败即返回非零。
    $compose up -d --build --wait --wait-timeout "${DEMO_WAIT_TIMEOUT:-600}" || { $compose ps -a; $compose logs --tail=80; exit 1; }
    sh scripts/smoke-test.sh
    echo "演示环境已就绪：网关 http://localhost:8080 ，Nacos 控制台 http://localhost:8849 ；管理员 admin，密码见 .env 中 AUTH_BOOTSTRAP_PASSWORD"
    ;;
  down) $compose down ;;
  # reset 会删除数据卷（数据库、Nacos、认证私钥）和本项目构建的本地镜像（--rmi local，不删除拉取的公共镜像），
  # 用于回到干净状态并释放磁盘；.env 保留。
  reset) $compose down -v --rmi local && exec sh "$0" up ;;
  smoke) sh scripts/smoke-test.sh ;;
  *) echo "用法：$0 [up|down|reset|smoke]" >&2; exit 2 ;;
esac
