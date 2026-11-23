-- 两个窗口一起检查，超过上限时无需进行昂贵的密码校验。
-- KEYS 依次是账号和来源计数键。ARGV 依次是两项上限和固定窗口秒数。
-- 返回 1 表示两个窗口都已计数，0 表示至少一个窗口已满，本次不计数。
if tonumber(redis.call('GET', KEYS[1]) or '0') >= tonumber(ARGV[1])
        or tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[2]) then
    return 0
end
-- 只在计数由 0 变为 1 时设定 TTL，后续尝试不会延长窗口。
for _, key in ipairs(KEYS) do
    if redis.call('INCR', key) == 1 then redis.call('EXPIRE', key, ARGV[3]) end
end
return 1
