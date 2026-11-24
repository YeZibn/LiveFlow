-- KEYS[1]: 预约键。ARGV: Broker 已确认的预约 ID 和当前发送租约 ID。
local entry = redis.call('HMGET', KEYS[1], 'submissionId', 'leaseId')
if entry[1] ~= ARGV[1] or entry[2] ~= ARGV[2] then return 0 end
-- HSET 保留原预约绝对截止。
redis.call('HSET', KEYS[1], 'phase', 'QUEUED')
return 1
