-- KEYS[1] 为账号额度键，KEYS[2] 为同槽预约键。
-- ARGV 依次为候选预约、租约、摘要、显示名、窗口毫秒、额度、租约毫秒、重试和保留毫秒。
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local old = redis.call('HMGET', KEYS[2], 'submissionId', 'contentHash', 'senderName', 'admittedAtMs', 'phase', 'leaseUntilMs')
if old[1] then
    if old[2] ~= ARGV[3] then return {'CONFLICT'} end
    if old[5] == 'QUEUED' then return {'QUEUED', old[1], old[4]} end
    if now >= tonumber(old[4]) + tonumber(ARGV[8]) then return {'EXPIRED'} end
    if now < tonumber(old[6]) then return {'IN_FLIGHT', tostring(tonumber(old[6]) - now)} end
    -- 更新租约沿用已预约的期限和发送者。
    redis.call('HSET', KEYS[2], 'leaseId', ARGV[2], 'leaseUntilMs', tostring(now + tonumber(ARGV[7])))
    return {'GRANTED', old[1], old[4], old[3]}
end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - tonumber(ARGV[5]))
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[6]) then
    local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return {'RATE', tostring(tonumber(first[2]) + tonumber(ARGV[5]) - now)}
end
redis.call('ZADD', KEYS[1], now, ARGV[1])
redis.call('PEXPIRE', KEYS[1], ARGV[5])
redis.call('HSET', KEYS[2], 'submissionId', ARGV[1], 'leaseId', ARGV[2], 'contentHash', ARGV[3], 'senderName', ARGV[4],
    'admittedAtMs', tostring(now), 'phase', 'PENDING', 'leaseUntilMs', tostring(now + tonumber(ARGV[7])))
redis.call('PEXPIREAT', KEYS[2], now + tonumber(ARGV[9]))
return {'GRANTED', ARGV[1], tostring(now), ARGV[4]}
