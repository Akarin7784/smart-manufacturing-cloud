#!/usr/bin/env sh
# 监控冒烟测试：只在 monitoring profile 下运行（docker compose --profile app --profile monitoring up -d）。
# 检查项：
#   1) Prometheus 的 spring-boot 抓取目标数量等于预期，且全部 health=up（服务刚就绪时首轮抓取可能未完成，轮询等待）；
#   2) 6 条告警规则已加载（ServiceDown / HighHttp5xxRatio / JvmHeapUsageHigh / OutboxBacklog / QualityDisposedReversalConflict / QualityReversalOrphan）；
#   3) 各服务指标带 application 标签；
#   4) Grafana 健康，自动配置的数据源连通、概览看板已加载（用 .env 中 init-env 生成的管理员账号）；
#   5) 指标不经网关对外暴露：宿主机访问 8080 的 /actuator/prometheus 必须被拒绝（401）。
# 依赖 curl 与 jq（与 smoke-test.sh 相同）。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
prom=${PROMETHEUS_URL:-http://localhost:9090}
grafana=${GRAFANA_URL:-http://localhost:3000}
gateway=${BASE_URL:-http://localhost:8080}
# 与 infra/observability/prometheus/prometheus.yml 中的静态目标保持一致。
expected=${EXPECTED_TARGETS:-6}
timeout=${MONITORING_WAIT_SECONDS:-120}

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

# 1) 轮询直到全部目标 up 或超时；超时时打印每个目标的状态与最后错误，便于定位。
deadline=$(( $(date +%s) + timeout ))
while :; do
  targets=$(curl -fsS -m 5 "$prom/api/v1/targets?state=active" || echo '{}')
  total=$(printf '%s' "$targets" | jq '[.data.activeTargets[]? | select(.labels.job=="spring-boot")] | length')
  up=$(printf '%s' "$targets" | jq '[.data.activeTargets[]? | select(.labels.job=="spring-boot" and .health=="up")] | length')
  [ "$total" -eq "$expected" ] && [ "$up" -eq "$expected" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    printf '%s' "$targets" | jq -r '.data.activeTargets[]? | "\(.labels.instance) \(.health) \(.lastError)"' >&2 || true
    fail "Prometheus 目标 up=$up / total=$total，预期 $expected 个全部 up"
  fi
  sleep 5
done
pass "Prometheus 抓取目标 $up/$expected 全部 up"

# 2) 告警规则已加载。
rules=$(curl -fsS -m 5 "$prom/api/v1/rules" | jq -r '[.data.groups[].rules[].name] | join(",")')
for r in ServiceDown HighHttp5xxRatio JvmHeapUsageHigh OutboxBacklog QualityDisposedReversalConflict QualityReversalOrphan; do
  case ",$rules," in *",$r,"*) ;; *) fail "告警规则 $r 未加载（已加载：$rules）" ;; esac
done
pass "告警规则已加载"

# 3) application 标签：每个服务的 process_uptime_seconds 都应带服务名标签。
apps=$(curl -fsS -m 5 --get "$prom/api/v1/query" --data-urlencode 'query=count by (application) (process_uptime_seconds{job="spring-boot"})' \
  | jq '[.data.result[] | select(.metric.application != null and .metric.application != "")] | length')
[ "$apps" -eq "$expected" ] || fail "带 application 标签的服务数为 $apps，预期 $expected"
pass "指标均带 application 标签"

# 4) Grafana：管理员账号密码只从 .env 读取（init-env 生成），不在脚本中写默认值。
env_file="$root/.env"
user=${GRAFANA_ADMIN_USER:-$(sed -n 's/^GRAFANA_ADMIN_USER=//p' "$env_file" 2>/dev/null)}
password=${GRAFANA_ADMIN_PASSWORD:-$(sed -n 's/^GRAFANA_ADMIN_PASSWORD=//p' "$env_file" 2>/dev/null)}
[ -n "$user" ] && [ -n "$password" ] || fail "缺少 GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD，请先运行 scripts/init-env.sh"
curl -fsS -m 5 "$grafana/api/health" | jq -e '.database == "ok"' >/dev/null || fail "Grafana 健康检查失败"
# 用 -K 从标准输入读取凭据，避免密码出现在进程参数列表中。
gcurl() { printf 'user = "%s:%s"\n' "$user" "$password" | curl -fsS -m 10 -K - "$@"; }
gcurl "$grafana/api/datasources/uid/prometheus/health" | jq -e '.status == "OK"' >/dev/null || fail "Grafana 数据源 prometheus 不可用"
gcurl "$grafana/api/dashboards/uid/smc-overview" | jq -e '.dashboard.uid == "smc-overview"' >/dev/null || fail "概览看板 smc-overview 未加载"
pass "Grafana 数据源连通，概览看板已加载"

# 5) 网关 8080 上没有匿名指标：cloud profile 下 actuator 在独立管理端口，8080 上的 /actuator/** 仍要求 metrics:read。
code=$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$gateway/actuator/prometheus")
[ "$code" = "401" ] || fail "网关 8080 的 /actuator/prometheus 返回 $code，预期 401（指标不应对外暴露）"
pass "网关不对外暴露指标（8080 /actuator/prometheus -> 401）"
echo "监控冒烟测试通过"
