package com.liveflow.core.room.message;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.service.RoomService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** 在单实例内受理弹幕、限制账号发送频率并保存短期受理结果。 */
@Service
public class RoomMessageService {

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)\\A\\s+|\\s+\\z");
    private final RoomService rooms;
    private final UserProfileService profiles;
    private final RoomMessageBroadcast broadcast;
    private final LongSupplier ticker;
    private final Clock clock;
    private final long window;
    private final long replayTtl;
    private final int maxPerWindow;
    private final int capacity;
    private final Object admission = new Object();
    private final Map<Long, ArrayDeque<Long>> attempts = new HashMap<>();
    private final Map<RequestKey, Accepted> accepted = new LinkedHashMap<>();

    /**
     * @param rooms 房间查询服务
     * @param profiles 根据已认证账号读取显示名的资料服务
     * @param broadcast 本机房间广播
     * @param windowSeconds 账号滚动窗口秒数
     * @param maxPerWindow 窗口内允许的消息数
     * @param replaySeconds 受理结果保留时间
     * @param capacity 本实例可同时保留的受理记录上限
     */
    @Autowired
    public RoomMessageService(RoomService rooms, UserProfileService profiles, RoomMessageBroadcast broadcast,
            @Value("${liveflow.room-messages.window-seconds:10}") int windowSeconds,
            @Value("${liveflow.room-messages.max-per-window:5}") int maxPerWindow,
            @Value("${liveflow.room-messages.replay-seconds:60}") int replaySeconds,
            @Value("${liveflow.room-messages.replay-capacity:4096}") int capacity) {
        this(rooms, profiles, broadcast, System::nanoTime, Clock.systemUTC(), windowSeconds, maxPerWindow, replaySeconds,
                capacity);
    }

    RoomMessageService(RoomService rooms, UserProfileService profiles, RoomMessageBroadcast broadcast,
            LongSupplier ticker, Clock clock,
            int windowSeconds, int maxPerWindow, int replaySeconds, int capacity) {
        if (windowSeconds <= 0 || maxPerWindow <= 0 || replaySeconds < windowSeconds || capacity <= 0) {
            throw new IllegalArgumentException("弹幕额度和受理容量必须为正，去重期限必须覆盖限流窗口");
        }
        this.rooms = rooms;
        this.profiles = profiles;
        this.broadcast = broadcast;
        this.ticker = ticker;
        this.clock = clock;
        this.window = TimeUnit.SECONDS.toNanos(windowSeconds);
        this.replayTtl = TimeUnit.SECONDS.toNanos(replaySeconds);
        this.maxPerWindow = maxPerWindow;
        this.capacity = capacity;
    }

    /**
     * 接受一次弹幕提交，或返回同一提交此前的结果。
     * @param userId 已认证的账号 ID
     * @param sessionId 已认证的登录会话 ID
     * @param roomId 房间 UUID
     * @param clientMessageId 客户端为本次提交生成的 UUID
     * @param rawContent 输入的原始正文
     * @return 服务端消息标识和受理时间
     * @throws RoomMessageFailure 输入无效、房间不存在、正文冲突、超额或暂时无法受理
     * @throws AuthFailure 发送者账号已不存在
     * @throws DataAccessException 房间或发送者资料读取失败
     */
    public Receipt send(long userId, UUID sessionId, String roomId, String clientMessageId, String rawContent) {
        if (!RoomUuid.isCanonical(roomId) || !RoomUuid.isCanonical(clientMessageId)) {
            throw new RoomMessageFailure(INVALID_REQUEST);
        }
        String content = normalize(rawContent);
        if (!rooms.exists(roomId)) {
            throw new RoomMessageFailure(ROOM_NOT_FOUND);
        }

        var key = new RequestKey(sessionId, roomId, clientMessageId);
        synchronized (admission) {
            expireReceipts(ticker.getAsLong());
            Receipt replay = replay(key, content);
            if (replay != null) {
                return replay;
            }
        }

        // 资料读取可能阻塞。释放受理锁，让其他发送和已有回执的重试继续处理。
        String senderName = null;
        RuntimeException profileFailure = null;
        try {
            senderName = profiles.read(userId).displayName();
        } catch (DataAccessException | AuthFailure failure) {
            profileFailure = failure;
        }

        // 单实例的短临界区只访问内存。去重、额度和房间入队共享一次受理顺序。
        synchronized (admission) {
            long now = ticker.getAsLong();
            expireReceipts(now);
            // 若另一标签页已受理，优先返回原结果，即使这次资料查询失败。
            Receipt replay = replay(key, content);
            if (replay != null) {
                return replay;
            }
            if (profileFailure != null) {
                throw profileFailure;
            }
            var recent = attempts.get(userId);
            if (recent != null) {
                expireAttempts(recent, now);
                if (recent.size() >= maxPerWindow) {
                    long remaining = window - (now - recent.getFirst());
                    long seconds = (remaining - 1) / TimeUnit.SECONDS.toNanos(1) + 1;
                    throw new RoomMessageFailure(MESSAGE_RATE_LIMITED, seconds);
                }
            }
            if (accepted.size() >= capacity) {
                throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
            }

            var receipt = new Receipt(UUID.randomUUID().toString(), clock.instant());
            // 广播只做有界入队。成功后才记录受理结果和额度，失败时两者均不提交。
            broadcast.enqueue(new RoomMessageBroadcast.Message(roomId, receipt.messageId(), senderName, content,
                    receipt.sentAt()));
            accepted.put(key, new Accepted(content, receipt, now));
            attempts.computeIfAbsent(userId, ignored -> new ArrayDeque<>()).addLast(now);
            return receipt;
        }
    }

    /**
     * 在持有受理锁时判重。原回执命中后无需读取资料、扣减额度或再次入队。
     * @param key 会话、房间与客户端消息标识组成的去重键
     * @param content 已规范化的提交正文
     * @return 已受理的原回执，未找到记录时为 null
     * @throws RoomMessageFailure 同一标识已绑定不同的规范化正文
     */
    private Receipt replay(RequestKey key, String content) {
        Accepted prior = accepted.get(key);
        if (prior == null) {
            return null;
        }
        if (!prior.content().equals(content)) {
            throw new RoomMessageFailure(MESSAGE_ID_CONFLICT);
        }
        return prior.receipt();
    }

    /** 定期回收无请求的账号状态以及已过期的去重记录。 */
    @Scheduled(fixedDelay = 10000)
    public void cleanUp() {
        synchronized (admission) {
            long now = ticker.getAsLong();
            expireReceipts(now);
            attempts.values().removeIf(times -> {
                expireAttempts(times, now);
                return times.isEmpty();
            });
        }
    }

    /**
     * 在受理锁内清理过期回执。记录按受理顺序插入，遇到首条未过期记录即可停止。
     * @param now 本次裁决的单调时钟读数，单位为纳秒
     */
    private void expireReceipts(long now) {
        var entries = accepted.values().iterator();
        while (entries.hasNext() && now - entries.next().acceptedAt() >= replayTtl) {
            entries.remove();
        }
    }

    /**
     * 在受理锁内从队列头移除滚动窗口之外的发送时间。
     * @param recent 按受理顺序保存的账号发送时间
     * @param now 本次裁决的单调时钟读数，单位为纳秒
     */
    private void expireAttempts(ArrayDeque<Long> recent, long now) {
        while (!recent.isEmpty() && now - recent.getFirst() >= window) {
            recent.removeFirst();
        }
    }

    /**
     * 按单行文本规则校验原文，再清理首尾 Unicode 空白。
     * @param raw 从 HTTP 请求收到的原始弹幕
     * @return 保留内部空白且长度为 1–100 个码点的正文
     * @throws RoomMessageFailure 原始字符或规范化后的长度无效
     */
    private static String normalize(String raw) {
        // 检查原文后再去除首尾空白，防止首尾换行被规范化掩盖。
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

    private record RequestKey(UUID sessionId, String roomId, String clientMessageId) {}
    private record Accepted(String content, Receipt receipt, long acceptedAt) {}

    /**
     * HTTP 受理结果，重试命中时复用原标识和时间。
     * @param messageId 服务端生成的消息 UUID
     * @param sentAt 服务端确认本次受理的 UTC 时间
     */
    public record Receipt(String messageId, Instant sentAt) {}
}
