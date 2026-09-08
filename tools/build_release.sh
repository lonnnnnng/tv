#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TVLIVE_STORE_FILE="${TVLIVE_STORE_FILE:-$HOME/.android/tvlive-release.jks}"
export TVLIVE_KEY_ALIAS="${TVLIVE_KEY_ALIAS:-tvlive}"

if [ ! -f "$TVLIVE_STORE_FILE" ]; then
  printf 'Release keystore not found: %s\n' "$TVLIVE_STORE_FILE" >&2
  exit 1
fi

# long: 本机密码只从钥匙串进入子进程环境；其他构建环境显式提供密码，不在仓库内存储凭据。
if [ -z "${TVLIVE_STORE_PASSWORD:-}" ] && [ "$(uname -s)" = "Darwin" ]; then
  TVLIVE_STORE_PASSWORD="$(security find-generic-password -a "$(id -un)" -s tvlive-release-keystore -w)"
  export TVLIVE_STORE_PASSWORD
fi
if [ -z "${TVLIVE_STORE_PASSWORD:-}" ]; then
  printf '%s\n' 'TVLIVE_STORE_PASSWORD is required for release signing.' >&2
  exit 1
fi
export TVLIVE_KEY_PASSWORD="${TVLIVE_KEY_PASSWORD:-$TVLIVE_STORE_PASSWORD}"
trap 'unset TVLIVE_STORE_PASSWORD TVLIVE_KEY_PASSWORD' EXIT

if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = "Darwin" ]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi

# long: 先完成正式变体的校验和构建，再发布输出目录中的同一份签名 APK，避免误上传调试包。
"$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" :app:test :app:lintRelease :app:assembleRelease "$@"
