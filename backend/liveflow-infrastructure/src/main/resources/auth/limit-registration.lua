-- 注册使用独立固定窗口。首次写入时设置过期，后续计数沿用原窗口。
-- 返回 0 表示允许，正数表示等待的毫秒数，-1 表示窗口状态无效。
local key = KEYS[1]
local current = redis.call('GET', key)
if not current then
    redis.call('SET', key, 1, 'EX', ARGV[2])
    return 0
end

local remainingMs = redis.call('PTTL', key)
if remainingMs < 0 then return -1 end
if tonumber(current) >= tonumber(ARGV[1]) then return math.max(1, remainingMs) end
redis.call('INCR', key)
return 0
