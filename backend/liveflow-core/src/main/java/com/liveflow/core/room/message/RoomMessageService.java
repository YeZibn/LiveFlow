package com.liveflow.core.room.message;

import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.service.RoomService;
import com.liveflow.core.room.message.RoomEvents.Queued;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomEvents.Submission;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** 校验已授权的弹幕提交，并以 Broker 确认为入队边界。 */
@Service
public class RoomMessageService {
    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)\\A\\s+|\\s+\\z");
    private final RoomService rooms;
    private final UserProfileService profiles;
    private final RoomMessagePorts.Admission admission;
    private final RoomMessagePorts.Producer producer;

    /**
     * @param rooms 房间存在性查询
     * @param profiles 认证用户的资料查询
     * @param admission 账号额度和提交租约
     * @param producer Kafka 提交生产者
     */
    public RoomMessageService(RoomService rooms, UserProfileService profiles, RoomMessagePorts.Admission admission,
            RoomMessagePorts.Producer producer) {
        this.rooms = rooms;
        this.profiles = profiles;
        this.admission = admission;
        this.producer = producer;
    }

    /**
     * @param scope 来自安全过滤链和路径的提交作用域
     * @param clientMessageId 客户端为本次正文生成的 UUID
     * @param rawContent 原始弹幕正文
     * @return Broker 已确认的原预约或新预约
     * @throws RoomMessageFailure 提交无效、超额、冲突、不可用或确认未知
     * @throws DataAccessException 生产前依赖访问失败
     */
    public CompletableFuture<Queued> send(Scope scope, String clientMessageId, String rawContent) {
        if (!RoomUuid.isCanonical(scope.roomId()) || !RoomUuid.isCanonical(clientMessageId)) {
            throw new RoomMessageFailure(INVALID_REQUEST);
        }
        String content = normalize(rawContent);
        String hash = RoomEvents.hash(content);
        var original = admission.findQueued(scope, clientMessageId, hash);
        if (original.isPresent()) {
            return CompletableFuture.completedFuture(original.get());
        }
        String sender;
        try {
            if (!rooms.exists(scope.roomId())) {
                throw new RoomMessageFailure(ROOM_NOT_FOUND);
            }
            sender = profiles.read(scope.userId()).displayName();
        } catch (DataAccessException unavailable) {
            // 资料查询期间，另一个相同请求可能已经取得 Broker 确认。
            var concurrent = admission.findQueued(scope, clientMessageId, hash);
            if (concurrent.isPresent()) return CompletableFuture.completedFuture(concurrent.get());
            throw unavailable;
        }
        var reservation = admission.claim(scope, clientMessageId, hash, sender);
        var queued = reservation.queued();
        if (reservation.leaseId() == null) {
            return CompletableFuture.completedFuture(queued);
        }
        var event = new Submission(1, queued.submissionId(), scope.roomId(), Long.toString(scope.userId()),
                scope.sessionId(), clientMessageId, content, hash, reservation.senderName(),
                RoomEvents.UTC_MILLIS.format(queued.admittedAt()));
        return producer.publish(event).thenCompose(ignored -> {
            // Broker 回调线程只移交结果，Redis 写回在独立任务完成。
            var result = new CompletableFuture<Queued>();
            Thread.startVirtualThread(() -> {
                try {
                    admission.markQueued(scope, clientMessageId, reservation);
                } catch (RuntimeException failure) {
                    // 已收到 Kafka 确认。租约过期后的重放仍由正式接收的判重状态裁决。
                } finally {
                    result.complete(queued);
                }
            });
            return result;
        });
    }

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
