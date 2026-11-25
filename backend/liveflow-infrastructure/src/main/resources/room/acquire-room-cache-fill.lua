-- KEYS 是同一房间的值与租约；ARGV 是令牌、租约毫秒、负缓存时长毫秒。
local value = redis.call('GET', KEYS[1])
if value then return {'value', value} end
if not redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2]) then
    return {'waiting'}
end

-- 使用 Redis 时钟从取得租约起计算负结果截止，迟到写入不能延长其生命周期。
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
return {'owner', now + tonumber(ARGV[3])}
