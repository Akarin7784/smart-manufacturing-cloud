#!/usr/bin/env sh
# 生成本地演示用 .env：不存在时从 .env.example 复制，并为留空的密钥填充随机值。
# 设计原因：密钥不能写在 compose 或仓库中；已有 .env 中非空的值保持不变，避免已初始化的数据卷密码失配。
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
env_file="$root/.env"
[ -f "$env_file" ] || { cp "$root/.env.example" "$env_file"; echo "已从 .env.example 创建 .env"; }
chmod 600 "$env_file" 2>/dev/null || true

# 生成 hex 随机串；优先 openssl，缺失时回退到 /dev/urandom。
rand_hex() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex "$1"; else od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; fi
}

# 仅当键不存在或值为空时写入，保证脚本可重复执行。
ensure() {
  key=$1; value=$2
  if grep -q "^$key=." "$env_file"; then return 0; fi
  tmp="$env_file.tmp"
  grep -v "^$key=" "$env_file" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  mv "$tmp" "$env_file"
  echo "已生成 $key"
}

ensure AUTH_BOOTSTRAP_PASSWORD "$(rand_hex 16)"
ensure MYSQL_ROOT_PASSWORD "$(rand_hex 16)"
ensure MYSQL_APP_PASSWORD "$(rand_hex 16)"
# Nacos 令牌密钥要求 Base64 且解码后至少 32 字节，这里用 48 个随机 hex 字符编码。
ensure NACOS_AUTH_TOKEN "$(rand_hex 24 | tr -d '\n' | base64 | tr -d '\n')"
ensure NACOS_AUTH_IDENTITY_KEY "serverIdentity"
ensure NACOS_AUTH_IDENTITY_VALUE "$(rand_hex 16)"
ensure NACOS_USERNAME "nacos"
ensure NACOS_PASSWORD "$(rand_hex 16)"
ensure AUTH_ISSUER "http://localhost:8080"
ensure AUTH_AUDIENCE "manufacturing-api"
