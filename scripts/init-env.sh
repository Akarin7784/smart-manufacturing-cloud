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

# 服务间调用（OAuth2 client_credentials）的工单客户端密钥：明文只给调用方 workorder-service，
# 认证服务只拿到 BCrypt 散列（WORKORDER_CLIENT_SECRET_HASH），从不持有明文。
# BCrypt 生成顺序：htpasswd（apache2-utils）→ python3 bcrypt 模块 → 一次性 httpd 容器内的 htpasswd（--rm，不残留容器）。
bcrypt_hash() {
  if command -v htpasswd >/dev/null 2>&1; then
    printf '%s' "$1" | htpasswd -niBC 10 "" | tr -d ':\n'
  elif command -v python3 >/dev/null 2>&1 && python3 -c 'import bcrypt' 2>/dev/null; then
    printf '%s' "$1" | python3 -c 'import bcrypt,sys; print(bcrypt.hashpw(sys.stdin.buffer.read(), bcrypt.gensalt(10)).decode(), end="")'
  elif command -v docker >/dev/null 2>&1; then
    printf '%s' "$1" | docker run --rm -i httpd:2.4-alpine htpasswd -niBC 10 "" | tr -d ':\n'
  else
    echo "无法生成 BCrypt 散列：请安装 apache2-utils（htpasswd）、python3-bcrypt 或 Docker" >&2; return 1
  fi
}
secret_created=0
if ! grep -q "^WORKORDER_CLIENT_SECRET=." "$env_file"; then
  ensure WORKORDER_CLIENT_SECRET "$(rand_hex 32)"; secret_created=1
fi
# 明文是新生成的、或散列缺失时重新计算散列，保证二者始终配对；已有配对不变，可重复执行。
if [ "$secret_created" = 1 ] || ! grep -q "^WORKORDER_CLIENT_SECRET_HASH=." "$env_file"; then
  secret=$(sed -n 's/^WORKORDER_CLIENT_SECRET=//p' "$env_file")
  hash=$(bcrypt_hash "$secret")
  case "$hash" in \$2*) ;; *) echo "BCrypt 散列生成失败" >&2; exit 1 ;; esac
  tmp="$env_file.tmp"
  grep -v "^WORKORDER_CLIENT_SECRET_HASH=" "$env_file" > "$tmp" || true
  # 散列含 $，用单引号写入，避免 docker compose 对 .env 值做变量插值。
  printf "WORKORDER_CLIENT_SECRET_HASH='%s'\n" "$hash" >> "$tmp"
  mv "$tmp" "$env_file"
  echo "已生成 WORKORDER_CLIENT_SECRET_HASH"
fi
