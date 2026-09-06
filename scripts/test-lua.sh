#!/usr/bin/env bash
# 直接验证防超卖 Lua 脚本（需本机 Redis 已启动，默认 6379）
# 场景：扣减成功 / 超限购 / 库存尽 / 补偿回补，验证库存永不为负
set -e

REDIS_CLI="${REDIS_CLI:-redis-cli}"
DEDUCT="coin-reservation-service/src/main/resources/lua/deduct_stock.lua"
COMPENSATE="coin-reservation-service/src/main/resources/lua/compensate_stock.lua"
STOCK_KEY="stock:1:1:0"
LIMIT_KEY="resv:limit:1"

echo "========== 初始化：网点库存 = 5 =========="
$REDIS_CLI SET "$STOCK_KEY" 5 >/dev/null
$REDIS_CLI DEL "$LIMIT_KEY" >/dev/null

echo "1) 用户A 扣减（限购1）→ 期望 1（成功）"
$REDIS_CLI --eval "$DEDUCT" "$STOCK_KEY" "$LIMIT_KEY" , A 1

echo "2) 用户A 再次扣减 → 期望 -2（超限购）"
$REDIS_CLI --eval "$DEDUCT" "$STOCK_KEY" "$LIMIT_KEY" , A 1

echo "3) 用户B 扣减 → 期望 1（成功，库存 5-1-1=3... 实际为 4 再扣 1 = 3）"
$REDIS_CLI --eval "$DEDUCT" "$STOCK_KEY" "$LIMIT_KEY" , B 1

echo "4) 连续扣减 C/D/E/F → 期望 1,1,1,-1（库存尽后返回 -1）"
for u in C D E F; do
  echo -n "   用户$u: "
  $REDIS_CLI --eval "$DEDUCT" "$STOCK_KEY" "$LIMIT_KEY" , "$u" 1
done

echo "5) 库存当前值 → 期望 0（绝不小于 0，防超卖）"
$REDIS_CLI GET "$STOCK_KEY"

echo "6) 补偿用户A（库存+1，限购-1）→ 期望 1"
$REDIS_CLI --eval "$COMPENSATE" "$STOCK_KEY" "$LIMIT_KEY" , A

echo "7) 补偿后库存 → 期望 1"
$REDIS_CLI GET "$STOCK_KEY"

echo "========== 验证完成 =========="
