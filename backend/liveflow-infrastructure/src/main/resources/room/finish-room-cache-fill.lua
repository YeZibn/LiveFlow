-- ARGV 依次是令牌、发布方式、到期参数。'1' 使用相对 TTL，'0' 使用绝对截止。
-- 被接管或被创建预热清除的持有者不能写入，也不能释放后来取得的租约。
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end

if ARGV[2] == '1' then
    redis.call('SET', KEYS[1], '1', 'PX', ARGV[3])
elseif ARGV[2] == '0' then
    local time = redis.call('TIME')
    local now = time[1] * 1000 + math.floor(time[2] / 1000)
    if now < tonumber(ARGV[3]) then
        redis.call('SET', KEYS[1], '0', 'PXAT', ARGV[3])
    end
end

-- 'release' 和已越过负结果截止的写入只释放自己的租约。
redis.call('DEL', KEYS[2])
return 1
