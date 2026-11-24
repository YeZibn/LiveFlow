-- KEYS[1]: 回执键。ARGV: 不可变终态 JSON、绝对到期毫秒。
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if now >= tonumber(ARGV[2]) then return 0 end
local existing = redis.call('GET', KEYS[1])
if existing then
    if existing ~= ARGV[1] then return -1 end
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', ARGV[2])
return 1
