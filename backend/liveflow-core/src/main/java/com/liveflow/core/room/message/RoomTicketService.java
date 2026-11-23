package com.liveflow.core.room.message;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.session.AuthSessionStore;
import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.service.RoomService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

@Service
public class RoomTicketService {

    private static final Pattern TICKET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final SecureRandom random = new SecureRandom();
    private final RoomTicketStore tickets;
    private final RoomService rooms;
    private final AuthSessionStore sessions;
    private final Duration lifetime;

    /**
     * @param tickets 短期票据存储
     * @param rooms 现有房间查询服务
     * @param sessions 当前会话存储
     * @param ttlSeconds 票据有效秒数
     */
    public RoomTicketService(RoomTicketStore tickets, RoomService rooms, AuthSessionStore sessions,
            @Value("${liveflow.room-messages.ticket-ttl-seconds:30}") int ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("Ticket TTL 必须为正数");
        }
        this.tickets = tickets;
        this.rooms = rooms;
        this.sessions = sessions;
        this.lifetime = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 为经过 HTTP 鉴权的账号签发一次性接收票据。
     * @param userId 可信账号 ID
     * @param sessionId 可信登录会话 ID
     * @param roomId 对外房间 UUID
     * @return 原始票据及有效秒数
     * @throws RoomMessageFailure 房间无效、不存在或无法分配票据
     * @throws DataAccessException 查询或写入失败
     */
    public Issued issue(long userId, UUID sessionId, String roomId) {
        requireRoom(roomId);
        for (int attempt = 0; attempt < 2; attempt++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (tickets.put(digest(ticket), new RoomTicketStore.Binding(userId, sessionId, roomId), lifetime)) {
                return new Issued(ticket, lifetime.toSeconds());
            }
        }
        throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
    }

    /**
     * 兑换首帧中的票据并校验它绑定的房间和登录会话。
     * @param ticket 客户端提交的原始票据
     * @return 通过校验的连接身份
     * @throws RoomMessageFailure 票据格式无效、已消费、过期或房间不可用
     * @throws AuthFailure 当前会话失效或无法确认
     * @throws DataAccessException 票据消费或房间查询失败
     */
    public RoomTicketStore.Binding redeem(String ticket) {
        if (ticket == null || !TICKET.matcher(ticket).matches()) {
            throw new RoomMessageFailure(TICKET_INVALID);
        }
        // 先原子消费，避免两个并发连接在会话复查期间同时得到入房资格。
        var binding = tickets.consume(digest(ticket)).orElseThrow(() -> new RoomMessageFailure(TICKET_INVALID));
        sessions.requireCurrent(binding.userId(), binding.sessionId());
        requireRoom(binding.roomId());
        return binding;
    }

    private void requireRoom(String roomId) {
        if (!RoomUuid.isCanonical(roomId)) {
            throw new RoomMessageFailure(INVALID_REQUEST);
        }
        if (rooms.readDetails(roomId).isEmpty()) {
            throw new RoomMessageFailure(ROOM_NOT_FOUND);
        }
    }

    private static String digest(String ticket) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    /** 签发接口使用的短期票据与有效期。 */
    public record Issued(String ticket, long expiresIn) {}
}
