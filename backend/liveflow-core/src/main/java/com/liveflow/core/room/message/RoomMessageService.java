package com.liveflow.core.room.message;

import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.resilience.RoomMessageCircuitBreaker;
import com.liveflow.core.resilience.RoomMessageCircuitBreaker.Permit;
import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.service.RoomService;
import com.liveflow.core.room.message.RoomEvents.Queued;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomEvents.Submission;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;
import static com.liveflow.core.resilience.RoomMessageCircuitBreaker.Dependency.MYSQL;

/** 校验已授权的弹幕提交，并以 Broker 确认为入队边界。 */
@Service
public class RoomMessageService {
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)\\A\\s+|\\s+\\z");
    private final RoomService rooms;
    private final UserProfileService profiles;
    private final RoomMessagePorts.Admission admission;
    private final RoomMessagePorts.Producer producer;
    private final RoomMessageCircuitBreaker circuit;

    /**
     * @param rooms 房间存在性查询
     * @param profiles 认证用户的资料查询
     * @param admission 账号冷却与单次发布许可
     * @param producer Kafka 提交生产者
     * @param circuit 保护发送依赖的统一入口
     */
    public RoomMessageService(RoomService rooms, UserProfileService profiles, RoomMessagePorts.Admission admission,
            RoomMessagePorts.Producer producer, RoomMessageCircuitBreaker circuit) {
        this.rooms = rooms;
        this.profiles = profiles;
        this.admission = admission;
        this.producer = producer;
        this.circuit = circuit;
    }

    /**
     * 校验输入并在发送链路获得许可后进入预约与生产流程。
     * @param scope 来自安全过滤链和路径的提交作用域
     * @param clientMessageId 客户端为本次正文生成的 UUID
     * @param rawContent 原始弹幕正文
     * @return HTTP 可使用的回执查询信息，以及独立完成的 Broker 确认
     * @throws RoomMessageFailure 提交无效、超额、冲突、不可用或确认未知
     */
    public SendAttempt send(Scope scope, String clientMessageId, String rawContent) {
        if (!RoomUuid.isCanonical(scope.roomId()) || !RoomUuid.isCanonical(clientMessageId)) {
            throw new RoomMessageFailure(INVALID_REQUEST);
        }
        String content = normalize(rawContent);
        // 准入拒绝发生在读取发送绑定、房间和资料之前，因此不会消耗账号冷却。
        var permit = circuit.tryAcquire().orElseThrow(() -> new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE));
        try {
            return submit(scope, clientMessageId, content, permit);
        } catch (RuntimeException failure) {
            settleFailure(permit, failure);
            throw failure;
        }
    }

    private SendAttempt submit(Scope scope, String clientMessageId, String content, Permit permit) {
        String hash = RoomEvents.hash(content);
        var original = admission.findQueued(scope, clientMessageId, hash);
        if (original.isPresent()) {
            return existing(original.get(), permit);
        }
        String sender;
        try {
            if (!rooms.exists(scope.roomId())) {
                throw new RoomMessageFailure(ROOM_NOT_FOUND);
            }
            sender = profiles.read(scope.userId()).displayName();
        } catch (DataAccessException | TransactionException unavailable) {
            // 资料查询期间，另一个相同请求可能已经取得 Broker 确认。
            var concurrent = admission.findQueued(scope, clientMessageId, hash);
            if (concurrent.isPresent()) return existing(concurrent.get(), permit);
            throw RoomMessageFailure.dependency(ROOM_MESSAGES_UNAVAILABLE, MYSQL, unavailable);
        }
        // 分区预检在新预约之前。拒绝在外层归还断路器许可，不占用发送冷却。
        producer.requireAdmission(scope.roomId());
        var reservation = admission.claim(scope, clientMessageId, hash, sender);
        var queued = reservation.queued();
        if (reservation.leaseId() == null) {
            return existing(queued, permit);
        }
        var event = new Submission(1, queued.submissionId(), scope.roomId(), Long.toString(scope.userId()),
                scope.sessionId(), clientMessageId, content, hash, reservation.senderName(),
                RoomEvents.UTC_MILLIS.format(queued.admittedAt()));
        try {
            var confirmation = producer.publish(event).handle((ignored, failure) -> {
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                    settleFailure(permit, cause);
                    if (cause instanceof RoomMessageFailure result) throw withKnownReceipt(result, queued);
                    throw new CompletionException(cause);
                }
                permit.succeeded();
                // Broker 确认是 202 的依据。Redis 状态写回独立执行，失败由存储适配器记录。
                Thread.startVirtualThread(() -> {
                    try {
                        admission.markQueued(scope, clientMessageId, reservation);
                    } catch (RuntimeException failureToMark) {
                        // 原提交仍可通过 Kafka 生成的回执查到正式结果。
                    }
                });
                return queued;
            });
            return new SendAttempt(queued, confirmation);
        } catch (RoomMessageFailure failure) {
            throw withKnownReceipt(failure, queued);
        }
    }

    private static SendAttempt existing(Queued queued, Permit permit) {
        permit.release();
        return new SendAttempt(queued, CompletableFuture.completedFuture(queued));
    }

    private static void settleFailure(Permit permit, Throwable failure) {
        if (failure instanceof RoomMessageFailure roomFailure && roomFailure.dependency() != null) {
            permit.failed(roomFailure.dependency(), roomFailure);
        } else {
            permit.release();
        }
    }

    private static RoomMessageFailure withKnownReceipt(RoomMessageFailure failure, Queued reservation) {
        return failure.code() == MESSAGE_SUBMISSION_UNKNOWN ? failure.withReceipt(reservation) : failure;
    }

    /**
     * @param reservation 已知的预约元数据，即使 HTTP 提前结束也可用于只读回执查询
     * @param confirmation Broker 确认的独立生命周期，HTTP 层应在其副本上施加等待上限
     */
    public record SendAttempt(Queued reservation, CompletableFuture<Queued> confirmation) {}

    /**
     * @param raw 输入原文
     * @return 保留内部空白的规范化单行正文
     * @throws RoomMessageFailure 原始字符或长度不合法
     */
    public static String normalize(String raw) {
        if (raw == null || raw.codePoints().anyMatch(point -> Character.isISOControl(point)
                || point == 0x2028 || point == 0x2029
                || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE))) {
            throw new RoomMessageFailure(INVALID_REQUEST);
        }
        String content = EDGE_WHITESPACE.matcher(raw).replaceAll("");
        int length = content.codePointCount(0, content.length());
        if (length < 1 || length > 100) {
            throw new RoomMessageFailure(INVALID_REQUEST);
        }
        return content;
    }
}
