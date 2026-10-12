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
# 工单下达：为 GR-60 成品建单后下达，校验冻结的 BOM A01 / 工艺 R01 快照、8 道工序（首道 READY）与 ACTIVE 物料预留；
# 同键重放返回同一预留单（Idempotent-Replayed: true），换键再次下达返回 409。物料预留当前由本地替身适配器提供。
gr_body='{"deviceId":"D001","product":"FG-GR60-20","quantity":10}'
gr=$(curl -fsS -m 5 -H "$auth" -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-gr-$(od -An -N8 -tx8 /dev/urandom | tr -d " ")" -d "$gr_body" "$base/api/workorders" | jq -r '.data.id')
[ -n "$gr" ] && [ "$gr" != null ] || fail "创建 GR-60 工单失败"
rel_key="smoke-rel-$(od -An -N8 -tx8 /dev/urandom | tr -d " ")"
release() { curl -sS -m 15 -D "$1" -X POST -H "$auth" -H "Idempotency-Key: $2" "$base/api/workorders/$gr/release"; }
rel=$(release "$hdr" "$rel_key")
echo "$rel" | jq -e '.code=="OK" and .data.status=="RELEASED" and .data.bomVersion=="A01" and .data.routingVersion=="R01"
  and (.data.operations|length)==8 and .data.operations[0].status=="READY" and .data.reservation.status=="ACTIVE"
  and ([.data.reservation.lines[]|select(.materialCode=="RM-20CRMNTI-D30")|.quantity][0]*10000|round)==105000' >/dev/null \
  || fail "工单下达结果不符: $rel"
rel_id=$(echo "$rel" | jq -r '.data.reservation.reservationId')
replay_rel=$(release "$hdr" "$rel_key")
grep -qi '^Idempotent-Replayed: true' "$hdr" || fail "同键重放下达缺少 Idempotent-Replayed: true"
[ "$(echo "$replay_rel" | jq -r '.data.reservation.reservationId')" = "$rel_id" ] || fail "同键重放下达返回了不同的预留单"
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 15 -X POST -H "$auth" -H "Idempotency-Key: $rel_key-other" "$base/api/workorders/$gr/release")" = 409 ] || fail "换键重复下达应返回 409"
# 未带令牌访问必须被网关拒绝。
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$base/api/devices")" = 401 ] || fail "未认证请求应返回 401"

# 服务身份（OAuth2 client_credentials）：经网关用工单客户端密钥换取服务令牌，解码 JWT 载荷断言 scope/aud/有效期；
# 错误密钥必须返回 401 invalid_client。明文密钥取自环境变量或 .env 的 WORKORDER_CLIENT_SECRET。
cs=${WORKORDER_CLIENT_SECRET:-$(sed -n 's/^WORKORDER_CLIENT_SECRET=//p' "$root/.env" 2>/dev/null || true)}
[ -n "$cs" ] || fail "缺少 WORKORDER_CLIENT_SECRET"
tok=$(curl -fsS -m 10 -u "workorder-service:$cs" -d grant_type=client_credentials -d scope=inventory:reserve "$base/api/auth/oauth2/token") \
  || fail "client_credentials 换取服务令牌失败"
printf '%s' "$tok" | jq -e '.token_type=="Bearer" and .scope=="inventory:reserve" and .expires_in==300' >/dev/null || fail "服务令牌响应不符：$tok"
# JWT 载荷为 base64url，无填充：转换字符集并补齐 '=' 后再解码。
payload=$(printf '%s' "$tok" | jq -r '.access_token' | cut -d. -f2 | tr '_-' '/+')
while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="$payload="; done
printf '%s' "$payload" | base64 -d | jq -e '.sub=="workorder-service" and .client_id=="workorder-service" and .scope=="inventory:reserve"
  and (.aud|if type=="array" then . else [.] end)==["inventory-service"] and .token_type=="service" and (.exp-.iat)==300 and (has("permissions")|not)' >/dev/null \
  || fail "服务令牌声明不符（scope/aud/有效期/无用户权限）"
[ "$(curl -s -o /dev/null -w '%{http_code}' -m 10 -u "workorder-service:wrong-$cs" -d grant_type=client_credentials "$base/api/auth/oauth2/token")" = 401 ] \
  || fail "错误客户端密钥应返回 401"
echo "PASS: 服务身份 client_credentials（scope/aud 校验、错误密钥 401）"

# 库存预留（服务令牌直连库存服务，模拟工单服务经 Nacos 直连；网关不放行预留接口）：
# 预留 → 断言可用量减少 → 释放（reason=CALLER_REQUEST）→ 断言可用量回到预留前，冒烟结束后库存状态不变、可重复执行。
# 幂等键每次运行新生成（含运行 id），预留与释放各用各的，绝不复用。
# 若脚本中途被杀、释放未执行，预留在 15 分钟后由过期任务（inventory.reservation.ttl=PT15M）自动回收。
# 物料取 GR-60 BOM 中的 RM-20CRMNTI-D30（20CrMnTi 圆钢，演示期初 16 kg），预留 1 kg，单位用主数据编码 "kg"。
inv=${INVENTORY_URL:-http://localhost:8085}
svc="Authorization: Bearer $(printf '%s' "$tok" | jq -r '.access_token')"
run_id="$(date +%s)-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
avail() { curl -fsS -m 5 -H "$auth" "$base/api/inventory/stock/availability?materialCode=RM-20CRMNTI-D30" | jq -r '.available'; }
before=$(avail) || fail "查询可用量失败"
rbody=$(jq -nc --arg wo "SMOKE-$run_id" '{workOrderNo:$wo,lines:[{materialCode:"RM-20CRMNTI-D30",qty:"1",uom:"kg",sourceLineId:"smoke-1"}]}')
res=$(curl -sS -m 10 -w '\n%{http_code}' -H "$svc" -H 'Content-Type: application/json; charset=utf-8' \
  -H "Idempotency-Key: smoke-reserve-$run_id" -d "$rbody" "$inv/inventory/reservations")
[ "$(printf '%s' "$res" | tail -n1)" = 201 ] || fail "预留应返回 201：$res"
rid=$(printf '%s' "$res" | sed '$d' | jq -r '.reservationId')
# 之后任何断言失败都先释放预留再退出，保证可重跑。
release_res() { curl -sS -o /dev/null -w '%{http_code}' -m 10 -X POST -H "$svc" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: smoke-release-$run_id" -d '{"reason":"CALLER_REQUEST"}' "$inv/inventory/reservations/$rid/release"; }
trap 'rm -f "$hdr"; [ -n "${rid:-}" ] && release_res >/dev/null 2>&1 || true' EXIT
mid=$(avail)
[ "$(jq -n --arg b "$before" --arg m "$mid" '($b|tonumber)-($m|tonumber)==1')" = true ] || fail "预留后可用量应减少 1（$before → $mid）"
[ "$(release_res)" = 200 ] || fail "释放预留应返回 200"
# 通过 GET 再确认预留单已是终态 RELEASED（服务令牌）。
curl -fsS -m 10 -H "$svc" "$inv/inventory/reservations/$rid" | jq -e '.status=="RELEASED"' >/dev/null || fail "释放后预留单状态应为 RELEASED"
rid=""
after=$(avail)
[ "$(jq -n --arg b "$before" --arg a "$after" '($b|tonumber)==($a|tonumber)')" = true ] || fail "释放后可用量应恢复（$before → $after）"
echo "PASS: 库存预留与释放（服务令牌直连、可用量往返一致）"


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
echo "PASS: 登录、JWT 校验、路由、Feign 令牌传递、持久化、工单幂等重放、业务错误码、主数据 BOM 展开与工单下达"
