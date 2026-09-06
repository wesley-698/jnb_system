-- 防超卖核心：原子扣减网点库存 + 限购计数
-- KEYS[1] = 网点库存key   KEYS[2] = 限购Hash key
-- ARGV[1] = idCard(限购维度)   ARGV[2] = 限购上限
-- 返回: 1成功  -1库存不足  -2超过限购
local stock = tonumber(redis.call('get', KEYS[1]) or '0')
if stock <= 0 then
  return -1
end
local bought = tonumber(redis.call('hget', KEYS[2], ARGV[1]) or '0')
if bought >= tonumber(ARGV[2]) then
  return -2
end
redis.call('decr', KEYS[1])
redis.call('hincrby', KEYS[2], ARGV[1], 1)
return 1
