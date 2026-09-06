#!/usr/bin/env bash
# 端到端验证脚本：跑通 实名预填 → 库存预热 → 提交预约 → 抽签 → 落库 → 查询 → 兑换
# 前置：基础设施已启动（docker-compose up -d），6 个服务已启动，网关监听 :8080
# 依赖：curl、jq
set -e

BASE="${BASE:-http://localhost:8080}"
AUTH="Authorization: Bearer test-token"

echo "========== 1. 实名预填 =========="
curl -s -X POST "$BASE/api/user/qualify" -H 'Content-Type: application/json' -d '{
  "userId": 1001, "idCard": "110101199001011234", "name": "张三", "phone": "13800000001"
}' | jq .

echo "========== 2. 库存预热（网点额度拆 10 分片写入 Redis） =========="
curl -s -X POST "$BASE/api/product/1/init-stock" | jq .

echo "========== 3. 提交预约（携带所选网点 branchId=1） =========="
curl -s -X POST "$BASE/api/reservation/submit" -H 'Content-Type: application/json' -H "$AUTH" -d '{
  "productId": 1, "branchId": 1, "userId": 1001, "idCard": "110101199001011234", "token": "token-1001"
}' | jq .

echo "========== 3b. 重复提交（应返回重复提交） =========="
curl -s -X POST "$BASE/api/reservation/submit" -H 'Content-Type: application/json' -H "$AUTH" -d '{
  "productId": 1, "branchId": 1, "userId": 1001, "idCard": "110101199001011234", "token": "token-1001"
}' | jq .

echo "========== 4. 触发抽签/分配（Lua 原子扣库存 + 发 Kafka） =========="
curl -s -X POST "$BASE/api/reservation/draw/1?drawMode=1" -H "$AUTH" | jq .

echo "========== 等待 Kafka 异步落库 =========="
sleep 5

echo "========== 5. 查询预约明细 =========="
ORDER_NO=$(curl -s "$BASE/api/order/list?idCard=110101199001011234" -H "$AUTH" | jq -r '.data[0].orderNo // empty')
echo "orderNo=$ORDER_NO"
curl -s "$BASE/api/order/list?idCard=110101199001011234" -H "$AUTH" | jq .

if [ -n "$ORDER_NO" ]; then
  echo "========== 6. 兑换登记 =========="
  curl -s -X POST "$BASE/api/exchange/register" -H 'Content-Type: application/json' -H "$AUTH" -d "{
    \"orderNo\": \"$ORDER_NO\", \"idCard\": \"110101199001011234\", \"branchId\": 1, \"operatorId\": 9001
  }" | jq .

  echo "========== 6b. 重复兑换（应返回已兑换） =========="
  curl -s -X POST "$BASE/api/exchange/register" -H 'Content-Type: application/json' -H "$AUTH" -d "{
    \"orderNo\": \"$ORDER_NO\", \"idCard\": \"110101199001011234\", \"branchId\": 1, \"operatorId\": 9001
  }" | jq .
fi

echo "========== 完成 =========="
