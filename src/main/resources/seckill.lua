-- 秒杀Lua脚本
-- KEYS: (unused, keys are built from ARGV)
-- ARGV[1] = voucherId
-- ARGV[2] = userId

local voucherId = ARGV[1]
local userId = ARGV[2]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key（一人一单）
local orderKey = 'seckill:order:' .. voucherId .. ':' .. userId

-- 判断是否已经下过单
local orderExists = redis.call('get', orderKey)
if orderExists then
    return 2
end

-- 判断库存
local stock = redis.call('get', stockKey)
if not stock or tonumber(stock) <= 0 then
    return 1
end

-- 扣减库存
redis.call('decrby', stockKey, 1)
-- 标记已下单
redis.call('set', orderKey, '1')

return 0
