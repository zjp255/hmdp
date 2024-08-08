---
--- Created by 233.
--- DateTime: 2024/8/7 下午8:32
---
-- 参数列表
-- 优惠券ID
local voucherId = ARGV[1]
-- 用户ID
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

-- 库存key
local stockKey = 'seckill:stock:' .. voucherId;

-- 订单key
local orderKey = 'seckill:order:' .. voucherId;

-- 脚本业务
-- 判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足返回1
    return 1
end

-- 判断用户是否下单
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已经购买过 返回2
    return 2
end

-- 扣减库存
redis.call('incrby', stockKey, -1)
-- 将用户id 添加到 set中
redis.call('sadd', orderKey, userId)
-- 发消息到stream队列中
redis.call('xadd', 'stream.orders','*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
