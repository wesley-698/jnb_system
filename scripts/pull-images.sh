#!/usr/bin/env bash
# 拉取 docker-compose.yml 所需镜像并打回原始 tag（带重试）
# 镜像源说明：Docker Hub 被墙时使用 daocloud 加速源；
#   daocloud 可代理 library/*、nacos/*、apache/*，但拒绝 bitnami/*，故 Kafka 用官方 apache 镜像
# 用法: bash scripts/pull-images.sh
set -e
MIRROR="docker.m.daocloud.io"
MAX_ATTEMPT=8

pull_with_retry() {
  local src="$1" dst="$2"
  local attempt=1
  echo "===== [$dst] 开始拉取 ====="
  while [ $attempt -le $MAX_ATTEMPT ]; do
    echo "--- 尝试 $attempt/$MAX_ATTEMPT: $src"
    if docker pull "$src"; then
      docker tag "$src" "$dst"
      echo "✔ $dst 就绪 (尝试 $attempt 成功)"
      return 0
    fi
    echo "⚠ 拉取失败(尝试 $attempt)，3 秒后重试..."
    attempt=$((attempt + 1))
    sleep 3
  done
  echo "✘ $dst 拉取失败（已达 $MAX_ATTEMPT 次）"
  return 1
}

pull_with_retry "$MIRROR/library/redis:7.2-alpine"       "redis:7.2-alpine"
pull_with_retry "$MIRROR/library/postgres:16-alpine"      "postgres:16-alpine"
pull_with_retry "$MIRROR/apache/kafka:3.7.1"              "apache/kafka:3.7.1"
pull_with_retry "$MIRROR/nacos/nacos-server:v2.3.2"       "nacos/nacos-server:v2.3.2"

echo "========== 拉取任务结束 =========="
docker images | grep -E "nacos/nacos-server|redis:7|apache/kafka|postgres:16" || echo "(部分镜像未就绪)"
