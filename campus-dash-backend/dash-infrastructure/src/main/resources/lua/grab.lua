-- 抢单原子判定脚本
--
-- 为什么必须用 Lua：Redis 单条命令是原子的，但多条命令之间会被其他客户端插入。
-- "GET 名额 -> 判断 > 0 -> DECR" 这个序列在并发下必然出错（两个客户端都读到 1）。
-- Lua 脚本在 Redis 中作为一个整体执行，中途不会被打断。
--
-- KEYS[1] = errand:slot:{taskId}      剩余名额（string）
-- KEYS[2] = errand:grabbed:{taskId}   已抢中用户集合（set）
-- KEYS[3] = errand:idem:{requestId}   幂等键（string）
-- ARGV[1] = runnerId
-- ARGV[2] = 幂等键过期秒数
-- 返回：1=占位成功 0=名额已满 -1=用户已抢过 -2=重复请求 -3=任务不可抢

-- 幂等：同一 requestId 重复提交直接返回上次结果，不重复扣名额
local prev = redis.call('GET', KEYS[3])
if prev then
    if tonumber(prev) == 1 then
        return -2
    else
        return 0
    end
end

local slot = redis.call('GET', KEYS[1])
if not slot then
    -- 名额键不存在：任务未发布、已结束或名额键已过期
    return -3
end

-- INV-2：同一用户不能占两个名额
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- INV-1：名额守恒
if tonumber(slot) <= 0 then
    redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), '0')
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), '1')
return 1
