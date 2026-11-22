-- 两个窗口一起检查，超过上限时无需进行昂贵的密码校验。
if tonumber(redis.call('GET', KEYS[1]) or '0') >= tonumber(ARGV[1])
        or tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[2]) then
    return 0
end
for _, key in ipairs(KEYS) do
    if redis.call('INCR', key) == 1 then redis.call('EXPIRE', key, ARGV[3]) end
end
return 1
