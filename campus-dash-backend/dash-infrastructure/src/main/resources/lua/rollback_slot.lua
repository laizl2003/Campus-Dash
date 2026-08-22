-- 名额回滚脚本（补偿动作）
--
-- 场景：Lua 占位成功，但 DB 落库失败（CAS 冲突、唯一索引冲突、连接超时）。
-- 此时 Redis 里名额已被扣掉，必须补回去，否则名额永久泄漏——
-- 任务会一直显示"已被抢"，但数据库里没有抢中者。
--
-- 必须在事务外执行（事务已回滚），且自身要幂等：
-- 只有当该用户确实在 grabbed 集合里时才回补名额，避免重复回滚导致名额虚增。
--
-- KEYS[1] = errand:slot:{taskId}
-- KEYS[2] = errand:grabbed:{taskId}
-- KEYS[3] = errand:idem:{requestId}
-- ARGV[1] = runnerId
-- 返回：1=已回滚 0=无需回滚（幂等）

if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[3])
    return 1
end
return 0
