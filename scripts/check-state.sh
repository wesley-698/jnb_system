#!/usr/bin/env bash
# 压测状态速查：定位链路卡在哪一环
#
#   resv:limit 人数 ≈ resv:pending + PG 落库  → 全链路一致
#   resv:limit 人数 > resv:pending + PG 落库  → 有额度被扣但既没在途单也没落库（异常，查日志）
#   resv:pending >> PG 落库笔数              → 卡在「Kafka → order-service 消费」
#   stock 合计 < PG remain 合计              → 存在「已扣未落库」的在途单（正常，会由对账收敛）
#   stock 合计 > PG remain 合计              → 存在「已落库但 Redis 被回补」的超发风险（查补偿日志）
#
# 用法：wsl -- bash /mnt/d/IdeaProjects/jnb_system/scripts/check-state.sh [productId]
set -u

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_USER="${PG_USER:-wesley}"
PG_PASSWORD="${PG_PASSWORD:-wesley}"
PG_DB="${PG_DB:-coin_reservation}"
PRODUCT="${1:-1}"
export PGPASSWORD="$PG_PASSWORD"

r() { redis-cli -h "$REDIS_HOST" "$@"; }
q() { psql -h "$PG_HOST" -U "$PG_USER" -d "$PG_DB" -At -c "$1" 2>&1; }

echo "[1] Redis 网点额度分片 stock:$PRODUCT:*   (submit 直接扣的就是它)"
keys=$(r keys "stock:$PRODUCT:*" | tr -d '\r' | sort)
if [ -z "$keys" ]; then
  echo "    (空！需先 POST /api/product/$PRODUCT/init-stock 或 /api/reservation/rebuild/$PRODUCT)"
else
  stock_sum=0
  while read -r k v; do
    stock_sum=$((stock_sum + v))
    echo "    $k = $v"
  done < <(paste -d' ' <(echo "$keys") <(r mget $keys))
  echo "    >>> 网点额度合计 = $stock_sum"
fi

echo "[2] Redis 在途单 resv:pending:$PRODUCT   (已扣额度、已投递 Kafka、待落库的量)"
echo "    >>> 在途 = $(r zcard "resv:pending:$PRODUCT")"

echo "[3] Redis 限购 resv:limit:$PRODUCT   (已约人数，每人限约一次)"
echo "    >>> 已约人数 = $(r hlen "resv:limit:$PRODUCT")"

echo "[4] Redis 预约顺序号 resv:seq:$PRODUCT   (发出多少号 => 有多少次成功扣额)"
echo "    >>> 当前序号 = $(r get "resv:seq:$PRODUCT")"

echo "[5] Redis 可预约网点清单 resv:branches:$PRODUCT"
echo "    >>> 网点 = $(r smembers "resv:branches:$PRODUCT" | tr '\n' ' ')"

echo "[6] PostgreSQL t_reservation   (最终落库量)"
q "SELECT 'rows_total=' || count(*) FROM t_reservation;" | sed 's/^/    /'
q "SELECT 'product ' || product_id || ' => ' || count(*) FROM t_reservation GROUP BY product_id;" | sed 's/^/    /'

echo "[7] PostgreSQL t_branch_stock   (PG 权威额度)"
q "SELECT 'product ' || product_id || ' => sum(total)=' || sum(total) || ' sum(remain)=' || sum(remain) FROM t_branch_stock GROUP BY product_id;" | sed 's/^/    /'
