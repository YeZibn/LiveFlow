package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomEvents.Queued;
import com.liveflow.core.room.message.RoomEvents.Receipt;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomMessagePorts.Admission;
import com.liveflow.core.room.message.RoomMessagePorts.Reservation;
import com.liveflow.core.room.message.RoomMessagePorts.Receipts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** Redis 预约与终态索引，Kafka 保留正式回执真源。 */
@Repository
public class RedisMessageState implements Admission, Receipts {
    private static final DefaultRedisScript<List> CLAIM = script("claim-message", List.class);
    private static final DefaultRedisScript<Long> CONFIRM = script("confirm-message", Long.class);
    private static final DefaultRedisScript<Long> PROJECT = script("project-receipt", Long.class);
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String prefix;
    private final String rateWindow;
    private final String rateLimit;
    private final String lease;

    /**
     * @param redis 普通命令连接
     * @param json 事件 JSON 编解码器
     * @param environment Redis 部署命名空间
     * @param rateWindow 账号额度滚动窗口毫秒数
     * @param rateLimit 窗口内允许的初次预约数
     * @param lease 一次生产确认占用的毫秒数
     */
    public RedisMessageState(StringRedisTemplate redis, JsonMapper json,
            @Value("${liveflow.room-messages.v2.environment:local}") String environment,
            @Value("${liveflow.room-messages.v2.rate-window-millis:10000}") long rateWindow,
            @Value("${liveflow.room-messages.v2.rate-limit:5}") int rateLimit,
            @Value("${liveflow.room-messages.v2.lease-millis:5000}") long lease) {
        if (!environment.matches("[A-Za-z0-9_-]{1,32}") || rateWindow < 1 || rateLimit < 1
                || lease < 3000 || lease >= com.liveflow.core.room.message.RoomEvents.RETRY_SECONDS * 1000L) {
            throw new IllegalArgumentException("弹幕 Redis 命名或时限配置无效");
        }
        this.redis = redis;
        this.json = json;
        this.prefix = environment + ":liveflow:rooms:";
        this.rateWindow = Long.toString(rateWindow);
        this.rateLimit = Integer.toString(rateLimit);
        this.lease = Long.toString(lease);
    }

    @Override
    public Optional<Queued> findQueued(Scope scope, String clientMessageId, String hash) {
        var entries = redis.opsForHash().multiGet(submitKey(scope, clientMessageId),
                List.of("submissionId", "contentHash", "phase", "admittedAtMs"));
        if (entries.getFirst() == null) {
            return Optional.empty();
        }
        if (!hash.equals(entries.get(1))) {
            throw new RoomMessageFailure(MESSAGE_ID_CONFLICT);
        }
        return "QUEUED".equals(entries.get(2))
                ? Optional.of(queued(entries.getFirst(), entries.get(3))) : Optional.empty();
    }

    @Override
    public Reservation claim(Scope scope, String clientMessageId, String hash, String name) {
        UUID lease = UUID.randomUUID();
        var result = redis.execute(CLAIM, List.of(account(scope) + ":rate", submitKey(scope, clientMessageId)),
                UUID.randomUUID().toString(), lease.toString(), hash, name, rateWindow, rateLimit, this.lease,
                Long.toString(com.liveflow.core.room.message.RoomEvents.RETRY_SECONDS * 1000L),
                Long.toString(com.liveflow.core.room.message.RoomEvents.RECEIPT_SECONDS * 1000L));
        if (result == null || result.isEmpty()) {
            throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        }
        return switch (result.getFirst().toString()) {
            case "GRANTED" -> new Reservation(queued(result.get(1), result.get(2)), lease, result.get(3).toString());
            case "QUEUED" -> new Reservation(queued(result.get(1), result.get(2)), null, null);
            case "CONFLICT" -> throw new RoomMessageFailure(MESSAGE_ID_CONFLICT);
            case "EXPIRED" -> throw new RoomMessageFailure(MESSAGE_SUBMISSION_EXPIRED);
            case "RATE" -> throw new RoomMessageFailure(MESSAGE_RATE_LIMITED, retryAfter(result.get(1)));
            case "IN_FLIGHT" -> throw new RoomMessageFailure(MESSAGE_SUBMISSION_UNKNOWN, retryAfter(result.get(1)));
            default -> throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        };
    }

    @Override
    public void markQueued(Scope scope, String clientMessageId, Reservation reservation) {
        redis.execute(CONFIRM, List.of(submitKey(scope, clientMessageId)),
                reservation.queued().submissionId().toString(), reservation.leaseId().toString());
    }

    @Override
    public Optional<Receipt> find(Scope scope, UUID submissionId) {
        return Optional.ofNullable(redis.opsForValue().get(receiptKey(scope, submissionId)))
                .map(value -> json.readValue(value, Receipt.class));
    }

    /**
     * 以事件绝对截止时间保存终态，安全重放相同投影。
     * @param receipt Kafka 中已经提交的终态
     * @throws IllegalStateException Redis 返回无法确认的结果或终态冲突
     */
    public void project(Receipt receipt) {
        var scope = new Scope(Long.parseLong(receipt.userId()), receipt.sessionId(), receipt.roomId());
        Long outcome = redis.execute(PROJECT, List.of(receiptKey(scope, receipt.submissionId())),
                json.writeValueAsString(receipt), Long.toString(Instant.parse(receipt.expiresAt()).toEpochMilli()));
        if (outcome == null || outcome < 0) {
            throw new IllegalStateException("弹幕回执投影不可确认");
        }
    }

    String channel(String roomId) {
        return prefix + roomId + ":messages:v1";
    }

    String receiptProjectionMarker(String namespace) {
        return prefix + "message:receipt-projection:" + (namespace.isEmpty() ? "main" : namespace);
    }

    private String account(Scope scope) {
        return prefix + "message:{" + scope.userId() + "}";
    }

    private String submitKey(Scope scope, String clientMessageId) {
        return account(scope) + ":submit:" + scope.sessionId() + ":" + scope.roomId() + ":" + clientMessageId;
    }

    private String receiptKey(Scope scope, UUID id) {
        return account(scope) + ":receipt:" + scope.sessionId() + ":" + scope.roomId() + ":" + id;
    }

    private static Queued queued(Object id, Object admitted) {
        return new Queued(UUID.fromString(id.toString()), Instant.ofEpochMilli(Long.parseLong(admitted.toString())));
    }

    private static long retryAfter(Object time) {
        return Math.max(1, Math.ceilDiv(Long.parseLong(time.toString()), 1000));
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> resultType) {
        var result = new DefaultRedisScript<T>();
        result.setLocation(new ClassPathResource("room/" + name + ".lua"));
        result.setResultType(resultType);
        return result;
    }
}
