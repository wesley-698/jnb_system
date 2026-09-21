#!/usr/bin/env bash
# 压测前预热：批量写入实名资格标记 qual:ok:{userId}
#
# 为什么必须做：/api/reservation/submit 会校验 qual:ok:{userId}，
# 缺失会直接返回 1004 NOT_QUALIFIED，压测结果 100% 失败。
#
# TTL 默认 7200s，务必大于压测时长。
# 注意：走 /api/user/qualify 接口写入的 key 只有 1 小时 TTL，长压测会中途失效。
#
# 用法（Redis 跑在 WSL 里，从 PowerShell 调用）：
#   wsl -- bash /mnt/d/IdeaProjects/jnb_system/scripts/preheat.sh [数量] [起始userId] [TTL]
#
# 示例：
#   wsl -- bash /mnt/d/IdeaProjects/jnb_system/scripts/preheat.sh 1000      # 先小批量验证
#   wsl -- bash /mnt/d/IdeaProjects/jnb_system/scripts/preheat.sh 200000    # 正式预热 20 万
set -e

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
COUNT="${1:-200000}"
START="${2:-100000}"
TTL="${3:-7200}"
END=$((START + COUNT - 1))

echo "预热 qual:ok:{userId}  userId ${START}~${END}  共 ${COUNT} 条  TTL=${TTL}s  Redis=${REDIS_HOST}:${REDIS_PORT}"

redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping > /dev/null || {
  echo "连不上 Redis ${REDIS_HOST}:${REDIS_PORT}" >&2
  exit 1
}

for ((i = START; i <= END; i++)); do
  echo "SET qual:ok:$i 1 EX $TTL"
done | redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" --pipe

echo "写入完成。抽样校验："
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" get "qual:ok:$START"
redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ttl "qual:ok:$START"
echo "当前 Redis key 总数：$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" dbsize)"
