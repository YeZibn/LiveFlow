-- KEYS[1] 为预约键。ARGV 依次为 Broker 已确认的预约 ID 和首次发布许可 ID。
local entry = redis.call('HMGET', KEYS[1], 'submissionId', 'leaseId')
if entry[1] ~= ARGV[1] or entry[2] ~= ARGV[2] then return 0 end
-- HSET 保留原预约绝对截止。
redis.call('HSET', KEYS[1], 'phase', 'QUEUED')
return 1
