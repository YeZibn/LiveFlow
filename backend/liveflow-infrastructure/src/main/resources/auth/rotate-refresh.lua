-- 先确认原会话，再根据摘要决定轮换或撤销，避免影响新会话。
local key = KEYS[1]
if redis.call('HGET', key, 'sessionId') ~= ARGV[1] then return 0 end
local used = 'used:' .. ARGV[2]
-- 只有该会话曾消费过的摘要才触发撤销，未知摘要返回无效。
if redis.call('HEXISTS', key, used) == 1 then
    redis.call('DEL', key)
    return -1
end
if redis.call('HGET', key, 'refreshHash') ~= ARGV[2] then return 0 end
local expiresAt = tonumber(redis.call('HGET', key, 'expiresAtMs'))
-- HSET 保留键的绝对到期，所有已消费记录随会话清理。
redis.call('HSET', key, 'refreshHash', ARGV[3], used, '1')
return expiresAt
