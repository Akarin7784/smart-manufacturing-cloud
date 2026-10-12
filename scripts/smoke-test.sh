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
    && curl -fsS -m 5 -H "Authorization: Bearer $token" "$base/api/workorders" 2>/dev/null | jq -e '.code=="OK"' >/dev/null \
    && curl -fsS -m 5 -H "Authorization: Bearer $token" "$base/api/materials?size=1" 2>/dev/null | jq -e '.code=="OK"' >/dev/null; then
    break
  fi
  token=""; sleep 2
done
[ -n "$token" ] || fail "网关或业务服务未就绪"
auth="Authorization: Bearer $token"

# 返回 HTTP 状态码，用于断言业务错误映射。
# 每次调用生成新的幂等键（创建工单接口必须携带 Idempotency-Key）。
status() { curl -s -o /dev/null -w '%{http_code}' -m 5 -H "$auth" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-$(od -An -N8 -tx8 /dev/urandom | tr -d " ")" "$@"; }

# 创建工单并保留键用于重放断言；键包含时间戳与进程号，重复执行冒烟不会命中旧记录。
key="smoke-$(date +%s)-$$"
create() { curl -fsS -m 5 -D "$1" -H "$auth" -H 'Content-Type: application/json' -H "Idempotency-Key: $key" \
  -d '{"deviceId":"D001","product":"bearing","quantity":10}' "$base/api/workorders"; }
hdr=$(mktemp); trap 'rm -f "$hdr"' EXIT
order=$(create "$hdr")
id=$(printf '%s' "$order" | jq -r 'select(.code=="OK" and .data.status=="CREATED") | .data.id')
[ -n "$id" ] || fail "创建工单失败：$order"
grep -q '^HTTP/[0-9.]* 201' "$hdr" || fail "首次创建应返回 201"
# 同键同内容重放：必须返回 200、Idempotent-Replayed: true 且工单号相同，不产生新工单。
replay=$(create "$hdr")
grep -q '^HTTP/[0-9.]* 200' "$hdr" || fail "同键重放应返回 200"
grep -qi '^Idempotent-Replayed: true' "$hdr" || fail "重放响应应带 Idempotent-Replayed: true"
[ "$(printf '%s' "$replay" | jq -r '.data.id')" = "$id" ] || fail "重放返回的工单号与首次不一致"
# 缺少幂等键必须在业务处理前返回 400。
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 -H "$auth" -H 'Content-Type: application/json' -d '{"deviceId":"D001","product":"bearing","quantity":10}' "$base/api/workorders")" = 400 ] || fail "缺少幂等键应返回 400"
curl -fsS -m 5 -H "$auth" "$base/api/workorders" | jq -e --arg id "$id" '[.data[].id|tostring] | index($id) != null' >/dev/null || fail "新工单未持久化"
[ "$(status "$base/api/devices/missing")" = 404 ] || fail "不存在设备应返回 404"
[ "$(status -d '{"deviceId":"missing","product":"bearing","quantity":10}' "$base/api/workorders")" = 404 ] || fail "不存在设备下单应返回 404"
[ "$(status -d '{"deviceId":"D002","product":"bearing","quantity":10}' "$base/api/workorders")" = 409 ] || fail "非可用设备下单应返回 409"
[ "$(status -d '{"deviceId":"D001","product":"bearing","quantity":0}' "$base/api/workorders")" = 400 ] || fail "非法数量应返回 400"
# 主数据（经网关）：GR-60 成品在 2026-10-12 的生效 BOM 必须是已发布的 A01，10 台展开结果须与演示数据一致：
# 数值按 4 位小数取整后比较，避免浮点累加误差。四层结构（明细最深第 3 层）、20CrMnTi 圆钢合计 10×(0.45+0.55)×1.05 = 10.5 kg、螺栓含 1% 损耗 60.6 个。
md="$base/api/boms/FG-GR60-20"
curl -fsS -m 5 -H "$auth" "$md/effective?date=2026-10-12" \
  | jq -e '.code=="OK" and .data.version=="A01" and .data.status=="RELEASED" and (.data.lines|length)==10' >/dev/null \
  || fail "演示 BOM FG-GR60-20/A01 未发布或内容不符"
curl -fsS -m 5 -H "$auth" "$md/explosion?date=2026-10-12&quantity=10" | jq -e '
  .code=="OK" and .data.bomVersion=="A01"
  and ([.data.lines[].level]|max)==3
  and ([.data.lines[]|select(.componentCode=="RM-20CRMNTI-D30")|.requiredQty]|add*10000|round)==105000
  and ([.data.lines[]|select(.componentCode=="PP-BOLT-M6")|.requiredQty][0]*10000|round)==606000' >/dev/null \
  || fail "演示 BOM 多层展开结果不符"
# 已发布版本只读：管理员重复发布 A01 必须返回 409，证明演示数据以 RELEASED 状态加载。
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 -X POST -H "$auth" "$md/A01/release")" = 409 ] || fail "已发布 BOM 重复发布应返回 409"
# 未带令牌访问必须被网关拒绝。
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$base/api/devices")" = 401 ] || fail "未认证请求应返回 401"

# RocketMQ 收发回环：用 Broker 镜像自带的 mqadmin 发一条带唯一标记的消息，再以新消费组拉回，确认 NameServer→Broker→消费链路可用。
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
echo "PASS: 登录、JWT 校验、路由、Feign 令牌传递、持久化、工单幂等重放、业务错误码与主数据 BOM 展开"
