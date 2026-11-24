-- KEYS[1] 为账号冷却键，KEYS[2] 为同槽预约键。
-- ARGV 依次为候选预约 ID、单次许可 ID、正文摘要、显示名、冷却毫秒、保留毫秒。
local cooldown = tonumber(ARGV[5])
local retention = tonumber(ARGV[6])
if #ARGV ~= 6 or not cooldown or cooldown < 1 or not retention or retention < 1 then
    return {'UNAVAILABLE'}
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local old = redis.call('HMGET', KEYS[2], 'submissionId', 'contentHash', 'admittedAtMs', 'phase')
if old[1] then
    if not old[2] or not tonumber(old[3]) then return {'UNAVAILABLE'} end
    if old[2] ~= ARGV[3] then return {'CONFLICT'} end
    if old[4] == 'QUEUED' then return {'QUEUED', old[1], old[3]} end
    -- 已有绑定只读。旧预约即使带有过期的 leaseUntilMs，也不会再次取得发布许可。
    if old[4] == 'PENDING' then return {'PENDING', old[1], old[3]} end
    return {'UNAVAILABLE'}
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
    'admittedAtMs', tostring(now), 'phase', 'PENDING')
redis.call('PEXPIREAT', KEYS[2], now + retention)
return {'NEW', ARGV[1], tostring(now), ARGV[4]}
