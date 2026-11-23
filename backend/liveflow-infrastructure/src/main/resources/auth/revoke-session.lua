-- 已消费的摘要仍证明退出请求属于这个会话。
-- KEYS[1] 是账号的当前会话键。ARGV 依次为页面指定的会话 ID 和提交的刷新摘要。
-- 返回 1 表示已删除会话，0 表示会话 ID 或摘要未匹配。
local key = KEYS[1]
-- 新登录可能已经替换会话，先比较目标 ID，再允许按摘要撤销。
if redis.call('HGET', key, 'sessionId') ~= ARGV[1] then return 0 end
if redis.call('HGET', key, 'refreshHash') == ARGV[2]
        or redis.call('HEXISTS', key, 'used:' .. ARGV[2]) == 1 then
    return redis.call('DEL', key)
end
return 0
