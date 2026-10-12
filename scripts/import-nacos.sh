#!/usr/bin/env sh
# 等待 Nacos 就绪、初始化管理员密码并导入 infra/nacos 下的项目配置（分组 MANUFACTURING）。
# 既可在 compose 的 nacos-config-init 一次性容器中运行，也可在宿主机运行（需 curl）。
# 依赖环境变量：NACOS_URL（默认 http://localhost:8848）、NACOS_USERNAME、NACOS_PASSWORD、NACOS_CONFIG_DIR、NACOS_NAMESPACE。
set -eu
url=${NACOS_URL:-http://localhost:8848}
user=${NACOS_USERNAME:-nacos}
pass=${NACOS_PASSWORD:?NACOS_PASSWORD 未设置，请先运行 scripts/init-env.sh}
dir=${NACOS_CONFIG_DIR:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/infra/nacos}
ns=${NACOS_NAMESPACE:-public}

# 就绪检查最多等待约 3 分钟；Nacos 首次启动需要初始化内嵌存储。
i=0
until curl -fsS "$url/nacos/v3/admin/core/state/readiness" >/dev/null 2>&1; do
  i=$((i+1)); [ "$i" -le 90 ] || { echo "Nacos 未就绪" >&2; exit 1; }
  sleep 2
done

# 首次启动时设置管理员密码；已初始化时接口返回错误，属于预期情况，忽略即可（幂等）。
curl -sS -o /dev/null -X POST "$url/nacos/v3/auth/user/admin" --data-urlencode "password=$pass" || true

# 登录获取 accessToken；不打印密码和令牌。用 sed 解析是因为 curl 镜像中没有 jq。
login=$(curl -fsS -X POST "$url/nacos/v3/auth/user/login" --data-urlencode "username=$user" --data-urlencode "password=$pass") \
  || { echo "Nacos 登录失败：请确认 .env 中的 NACOS_PASSWORD 与已有数据卷一致（可执行 make demo-reset 重建）" >&2; exit 1; }
token=$(printf '%s' "$login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { echo "未能解析 Nacos accessToken" >&2; exit 1; }

# 逐个发布配置；发布接口为覆盖写，重复执行结果一致。
for f in "$dir"/*.yml; do
  name=$(basename "$f")
  result=$(curl -fsS -X POST "$url/nacos/v3/admin/cs/config" -H "accessToken: $token" \
    --data-urlencode "dataId=$name" --data-urlencode "groupName=MANUFACTURING" --data-urlencode "type=yaml" \
    --data-urlencode "namespaceId=$ns" --data-urlencode "content@$f")
  case "$result" in
    *'"data":true'*) echo "已导入 MANUFACTURING/$name" ;;
    *) echo "导入 $name 失败：$result" >&2; exit 1 ;;
  esac
done
