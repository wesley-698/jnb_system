-- 补偿回滚：库存 +1，限购 -1（与扣减脚本对称）
-- KEYS[1] = 网点库存key   KEYS[2] = 限购Hash key
-- ARGV[1] = idCard
redis.call('incr', KEYS[1])
redis.call('hincrby', KEYS[2], ARGV[1], -1)
return 1
