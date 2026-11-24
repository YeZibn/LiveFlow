-- KEYS 为一次请求要共同裁决的计数键。ARGV[1] 为窗口秒数，后续参数为各键上限。
-- 返回 0 表示已扣额，正数表示阻止本次请求的最长剩余毫秒，-1 表示存储状态无效。
local windowSeconds = tonumber(ARGV[1])
if not windowSeconds or windowSeconds < 1 or #ARGV ~= #KEYS + 1 then return -1 end

local longestWaitMs = 0
local existing = {}
-- 写入前检查全部状态。任一维度拒绝时，其他维度也不扣额。
for index, key in ipairs(KEYS) do
    local limit = tonumber(ARGV[index + 1])
    if not limit or limit < 1 then return -1 end
    local raw = redis.call('GET', key)
    existing[index] = raw ~= false
    if raw then
        local count = tonumber(raw)
        local ttl = redis.call('PTTL', key)
        if not count or not string.match(raw, '^%d+$') or count < 1 or ttl < 0 then return -1 end
        if count >= limit then longestWaitMs = math.max(longestWaitMs, ttl, 1) end
    end
end
if longestWaitMs > 0 then return longestWaitMs end

for index, key in ipairs(KEYS) do
    if existing[index] then
        redis.call('INCR', key)
    else
        redis.call('SET', key, 1, 'EX', windowSeconds)
    end
end
return 0
