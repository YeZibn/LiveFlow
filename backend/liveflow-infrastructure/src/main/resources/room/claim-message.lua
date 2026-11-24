-- KEYS[1] 为账号冷却键，KEYS[2] 为同槽预约键。
-- ARGV 依次为候选预约、租约、摘要、显示名、冷却、租约、重试和保留毫秒。
local cooldown = tonumber(ARGV[5])
local lease = tonumber(ARGV[6])
local retry = tonumber(ARGV[7])
local retention = tonumber(ARGV[8])
if #ARGV ~= 8 or not cooldown or cooldown < 1 or not lease or lease < 1
        or not retry or retry <= lease or not retention or retention <= retry then
    return {'UNAVAILABLE'}
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local old = redis.call('HMGET', KEYS[2], 'submissionId', 'contentHash', 'senderName', 'admittedAtMs', 'phase', 'leaseUntilMs')
if old[1] then
    local admitted = tonumber(old[4])
    if not old[2] or not old[3] or not admitted then return {'UNAVAILABLE'} end
    if old[2] ~= ARGV[3] then return {'CONFLICT'} end
    if old[5] == 'QUEUED' then return {'QUEUED', old[1], old[4]} end
    local leaseUntil = tonumber(old[6])
    if old[5] ~= 'PENDING' or not leaseUntil then return {'UNAVAILABLE'} end
    if now >= admitted + retry then return {'EXPIRED'} end
    if now < leaseUntil then return {'IN_FLIGHT', tostring(leaseUntil - now)} end
    -- 老预约续租沿用首次许可、显示名与冷却截止。
    redis.call('HSET', KEYS[2], 'leaseId', ARGV[2], 'leaseUntilMs', tostring(now + lease))
    return {'GRANTED', old[1], old[4], old[3]}
end

-- GET 会拒绝错误类型。以绝对截止裁决，当前毫秒等于截止时即可写入新预约。
if redis.call('GET', KEYS[1]) then
    local expiresAt = redis.call('PEXPIRETIME', KEYS[1])
    if expiresAt < 0 then return {'UNAVAILABLE'} end
    if now < expiresAt then return {'RATE', tostring(expiresAt - now)} end
end

-- 许可和冷却使用同一 Redis 时钟，拒绝路径不会写入任何键。
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', now + cooldown)
redis.call('HSET', KEYS[2], 'submissionId', ARGV[1], 'leaseId', ARGV[2], 'contentHash', ARGV[3], 'senderName', ARGV[4],
    'admittedAtMs', tostring(now), 'phase', 'PENDING', 'leaseUntilMs', tostring(now + lease))
redis.call('PEXPIREAT', KEYS[2], now + retention)
return {'NEW', ARGV[1], tostring(now), ARGV[4]}
