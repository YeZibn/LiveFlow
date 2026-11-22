-- 密码已校验。整条替换会话和摘要历史，最后成功提交的登录获胜。
local key = KEYS[1]
redis.call('DEL', key)
redis.call('HSET', key, 'sessionId', ARGV[1], 'refreshHash', ARGV[2],
           'createdAtMs', ARGV[3], 'expiresAtMs', ARGV[4])
return redis.call('PEXPIREAT', key, ARGV[4])
