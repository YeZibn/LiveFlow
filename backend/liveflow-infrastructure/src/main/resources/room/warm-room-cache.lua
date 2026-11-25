-- 房间事务已经提交。只有正值写入成功后才撤销旧回填的写入资格。
redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
redis.call('DEL', KEYS[2])
return 1
