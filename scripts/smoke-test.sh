#!/usr/bin/env sh
# 冒烟测试（sh 版，对应 smoke-test.ps1）：经网关登录、查询、创建工单并校验业务错误码。会保留一条示例工单。
# 依赖 curl、jq；管理员密码从环境变量或 .env 的 AUTH_BOOTSTRAP_PASSWORD 读取。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
base=${BASE_URL:-http://localhost:8080}
pw=${AUTH_BOOTSTRAP_PASSWORD:-$(sed -n 's/^AUTH_BOOTSTRAP_PASSWORD=//p' "$root/.env" 2>/dev/null || true)}
[ -n "$pw" ] || { echo "缺少 AUTH_BOOTSTRAP_PASSWORD" >&2; exit 1; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# 等待网关与下游全部可用：登录成功且设备、工单列表都返回 OK 才继续（最多约 2 分钟）。
token=""; i=0
while [ "$i" -lt 60 ]; do
  i=$((i+1))
  body=$(jq -nc --arg p "$pw" '{username:"admin",password:$p}')
  token=$(curl -fsS -m 5 -H 'Content-Type: application/json' -d "$body" "$base/api/auth/login" 2>/dev/null | jq -r '.data.accessToken // empty' || true)
  if [ -n "$token" ] \
    && curl -fsS -m 5 -H "Authorization: Bearer $token" "$base/api/devices" 2>/dev/null | jq -e '.code=="OK"' >/dev/null \
    && curl -fsS -m 5 -H "Authorization: Bearer $token" "$base/api/workorders" 2>/dev/null | jq -e '.code=="OK"' >/dev/null; then
    break
  fi
  token=""; sleep 2
done
[ -n "$token" ] || fail "网关或业务服务未就绪"
auth="Authorization: Bearer $token"

# 返回 HTTP 状态码，用于断言业务错误映射。
status() { curl -s -o /dev/null -w '%{http_code}' -m 5 -H "$auth" -H 'Content-Type: application/json' "$@"; }

order=$(curl -fsS -m 5 -H "$auth" -H 'Content-Type: application/json' -d '{"deviceId":"D001","product":"bearing","quantity":10}' "$base/api/workorders")
id=$(printf '%s' "$order" | jq -r 'select(.code=="OK" and .data.status=="CREATED") | .data.id')
[ -n "$id" ] || fail "创建工单失败：$order"
curl -fsS -m 5 -H "$auth" "$base/api/workorders" | jq -e --arg id "$id" '[.data[].id|tostring] | index($id) != null' >/dev/null || fail "新工单未持久化"
[ "$(status "$base/api/devices/missing")" = 404 ] || fail "不存在设备应返回 404"
[ "$(status -d '{"deviceId":"missing","product":"bearing","quantity":10}' "$base/api/workorders")" = 404 ] || fail "不存在设备下单应返回 404"
[ "$(status -d '{"deviceId":"D002","product":"bearing","quantity":10}' "$base/api/workorders")" = 409 ] || fail "非可用设备下单应返回 409"
[ "$(status -d '{"deviceId":"D001","product":"bearing","quantity":0}' "$base/api/workorders")" = 400 ] || fail "非法数量应返回 400"
# 未带令牌访问必须被网关拒绝。
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$base/api/devices")" = 401 ] || fail "未认证请求应返回 401"

# RocketMQ 收发回环：用 Broker 镜像自带的 tools.sh 发一条带唯一标记的消息，再用 PushConsumer 收回，确认 NameServer→Broker→消费链路可用。
# 只用镜像自带工具，不给业务镜像增加依赖；工具 JVM 堆压到 128M。SKIP_MQ_SMOKE=1 可跳过（如未启动 app profile）。
if [ "${SKIP_MQ_SMOKE:-0}" != 1 ]; then
  # 在 broker 容器内执行 mqadmin；参数通过环境变量传入，避免 shell 转义问题。
  mqadmin() {
    docker compose --profile app exec -T -e "JAVA_OPT_EXT=-Xms64m -Xmx128m -Xmn32m" -e "MARKER=$marker" -e "GROUP=$group" \
      broker sh -c "\"\$ROCKETMQ_HOME/bin/mqadmin\" $1"
  }
  marker="smoke-$(date +%s)-$$"; group="CG_SMOKE_$$"
  # 发送：返回 SEND_OK 才算成功。
  mqadmin 'sendMessage -n namesrv:9876 -t mes-smoke-test -p "$MARKER" -k "$MARKER"' 2>/dev/null | grep -q SEND_OK \
    || fail "RocketMQ 发送消息失败"
  # 接收：以新消费组从队列拉取消息，找到刚发送的唯一标记即视为收发回环成功（最多重试约 30 秒）。
  got=""; j=0
  while [ "$j" -lt 15 ]; do
    j=$((j+1))
    if mqadmin 'consumeMessage -n namesrv:9876 -t mes-smoke-test -g "$GROUP" -c 1000' 2>/dev/null | grep -q "$marker"; then got=1; break; fi
    sleep 2
  done
  [ -n "$got" ] || fail "RocketMQ 未收回刚发送的消息"
  echo "PASS: RocketMQ 收发回环（Topic mes-smoke-test）"
fi
echo "PASS: 登录、JWT 校验、路由、Feign 令牌传递、持久化与业务错误码"
