-- 先确认原会话，再根据摘要决定轮换或撤销，避免影响新会话。
-- KEYS[1] 是账号的当前会话键。ARGV 依次为目标会话 ID、提交摘要和新摘要。
-- 返回原会话绝对到期毫秒表示轮换成功，0 表示无效，-1 表示发现重放并删除会话。
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
