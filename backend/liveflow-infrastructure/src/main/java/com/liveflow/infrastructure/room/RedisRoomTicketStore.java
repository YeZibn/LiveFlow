package com.liveflow.infrastructure.room;

import com.liveflow.core.room.message.RoomTicketStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 使用 Redis 原子保存与兑换房间票据。 */
@Repository
public class RedisRoomTicketStore implements RoomTicketStore {

    private static final String PREFIX = "liveflow:rooms:ws-ticket:";
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    /**
     * @param redis 认证与票据使用的 Redis 客户端
     * @param json 应用的 JSON 映射器
     */
    public RedisRoomTicketStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public boolean put(String digest, Binding binding, Duration lifetime) {
        Boolean stored = redis.opsForValue().setIfAbsent(PREFIX + digest, json.writeValueAsString(binding), lifetime);
        if (stored == null) {
            throw new DataRetrievalFailureException("无法确认票据写入结果");
        }
        return stored;
    }

    @Override
    public Optional<Binding> consume(String digest) {
        String stored = redis.opsForValue().getAndDelete(PREFIX + digest);
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(stored, Binding.class));
        } catch (JacksonException failure) {
            throw new DataRetrievalFailureException("无法解析票据绑定", failure);
        }
    }
}
