#!/usr/bin/env bash
# 端到端验证脚本（普通纪念币：先到先得、约满为止）
#   实名预填 → 额度预热 → 查网点余量 → 提交预约(当场返回预约号) → 落库 → 查询 → 兑换
# 前置：基础设施已启动（docker-compose up -d），6 个服务已启动，网关监听 :8080
# 依赖：curl、jq
set -e

BASE="${BASE:-http://localhost:8080}"
AUTH="Authorization: Bearer test-token"

echo "========== 1. 实名预填 =========="
curl -s -X POST "$BASE/api/user/qualify" -H 'Content-Type: application/json' -d '{
  "userId": 1001, "idCard": "110101199001011234", "name": "张三", "phone": "13800000001"
}' | jq .
curl -s -X POST "$BASE/api/user/qualify" -H 'Content-Type: application/json' -d '{
  "userId": 1002, "idCard": "110101199001015678", "name": "李四", "phone": "13800000002"
}' | jq .

echo "========== 2. 额度预热（网点额度拆 10 分片写入 Redis + 登记网点清单） =========="
curl -s -X POST "$BASE/api/product/1/init-stock" | jq .

echo "========== 3. 各网点剩余额度（提交前选网点用） =========="
curl -s "$BASE/api/reservation/stock/1" -H "$AUTH" | jq .

echo "========== 4. 提交预约（先到先得：当场返回预约号，即预约成功） =========="
curl -s -X POST "$BASE/api/reservation/submit" -H 'Content-Type: application/json' -H "$AUTH" -d '{
  "productId": 1, "branchId": 1, "userId": 1001, "idCard": "110101199001011234", "token": "token-1001"
}' | jq .
# 期望 code:0，data.orderNo 非空，data.branchRemain 为扣减后余量

echo "========== 4b. 重复提交同一 token（应返回 1003 请勿重复提交） =========="
curl -s -X POST "$BASE/api/reservation/submit" -H 'Content-Type: application/json' -H "$AUTH" -d '{
  "productId": 1, "branchId": 1, "userId": 1001, "idCard": "110101199001011234", "token": "token-1001"
}' | jq .

echo "========== 4c. 换 token 用同一身份证再约（重复预约无效，应返回 1002） =========="
curl -s -X POST "$BASE/api/reservation/submit" -H 'Content-Type: application/json' -H "$AUTH" -d '{
  "productId": 1, "branchId": 1, "userId": 1001, "idCard": "110101199001011234", "token": "token-1001-b"
}' | jq .

echo "========== 4d. 另一用户提交（应 code:0，验证多人先到先得） =========="
curl -s -X POST "$BASE/api/reservation/submit" -H 'Content-Type: application/json' -H "$AUTH" -d '{
  "productId": 1, "branchId": 1, "userId": 1002, "idCard": "110101199001015678", "token": "token-1002"
}' | jq .

echo "========== 5. 再查网点余量（应比步骤 3 少 2） =========="
curl -s "$BASE/api/reservation/stock/1" -H "$AUTH" | jq .

echo "========== 等待 Kafka 异步落库 =========="
sleep 5

echo "========== 6. 查询预约明细（应含 orderNo 与 sequenceNo） =========="
ORDER_NO=$(curl -s "$BASE/api/order/list?idCard=110101199001011234" -H "$AUTH" | jq -r '.data[0].orderNo // empty')
echo "orderNo=$ORDER_NO"
curl -s "$BASE/api/order/list?idCard=110101199001011234" -H "$AUTH" | jq .

if [ -n "$ORDER_NO" ]; then
  echo "========== 7. 兑换登记 =========="
  curl -s -X POST "$BASE/api/exchange/register" -H 'Content-Type: application/json' -H "$AUTH" -d "{
    \"orderNo\": \"$ORDER_NO\", \"idCard\": \"110101199001011234\", \"branchId\": 1, \"operatorId\": 9001
  }" | jq .

  echo "========== 7b. 重复兑换（应返回已兑换） =========="
  curl -s -X POST "$BASE/api/exchange/register" -H 'Content-Type: application/json' -H "$AUTH" -d "{
    \"orderNo\": \"$ORDER_NO\", \"idCard\": \"110101199001011234\", \"branchId\": 1, \"operatorId\": 9001
  }" | jq .
fi

echo "========== 完成 =========="
