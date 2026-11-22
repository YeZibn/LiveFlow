-- 已消费的摘要仍证明退出请求属于这个会话。
local key = KEYS[1]
-- 新登录可能已经替换会话，先比较目标 ID，再允许按摘要撤销。
if redis.call('HGET', key, 'sessionId') ~= ARGV[1] then return 0 end
if redis.call('HGET', key, 'refreshHash') == ARGV[2]
        or redis.call('HEXISTS', key, 'used:' .. ARGV[2]) == 1 then
    return redis.call('DEL', key)
end
return 0
