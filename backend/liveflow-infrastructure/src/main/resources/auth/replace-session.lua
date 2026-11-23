-- 密码已校验。整条替换会话和摘要历史，最后成功提交的登录获胜。
-- KEYS[1] 是账号的当前会话键。ARGV 依次为新会话 ID、刷新摘要、创建毫秒和绝对到期毫秒。
-- 返回 PEXPIREAT 的结果，1 表示已设置到期，0 表示键不存在。
local key = KEYS[1]
-- 先清除旧散列，再写入新记录。旧会话的已用摘要不会出现在新会话中。
redis.call('DEL', key)
redis.call('HSET', key, 'sessionId', ARGV[1], 'refreshHash', ARGV[2],
           'createdAtMs', ARGV[3], 'expiresAtMs', ARGV[4])
return redis.call('PEXPIREAT', key, ARGV[4])
