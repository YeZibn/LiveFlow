package com.liveflow.api.room.message;

import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomMessageService;
import com.liveflow.core.room.message.RoomTicketService;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** 供已登录用户申请房间连接票据及提交弹幕。 */
@RestController
@RequestMapping("/api/v1/rooms/{roomId}")
public class RoomMessageController {
    static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final RoomTicketService tickets;
    private final RoomMessageService messages;
    private final ObjectReader reader;

    /**
     * @param tickets 房间票据服务
     * @param messages 本机弹幕受理服务
     * @param json 应用 JSON 映射器
     */
    public RoomMessageController(RoomTicketService tickets, RoomMessageService messages, JsonMapper json) {
        this.tickets = tickets;
        this.messages = messages;
        this.reader = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * 签发只能用于此房间的一次性接收票据。
     * @param jwt 安全链已验证的当前登录身份
     * @param roomId 路径中的房间 UUID
     * @return 不可缓存的票据及有效期
     * @throws RoomMessageFailure 房间无效、不存在或签发失败
     * @throws DataAccessException 房间或票据存储故障
     */
    @PostMapping("/ws-ticket")
    public ResponseEntity<RoomTicketService.Issued> ticket(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("roomId") String roomId) {
        var issued = tickets.issue(Long.parseLong(jwt.getSubject()), sessionId(jwt), roomId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    /**
     * 接受已认证用户的弹幕提交，并返回与广播对应的受理结果。
     * @param jwt 安全链已验证的当前登录身份
     * @param roomId 路径中的房间 UUID
     * @param body 提交的 JSON 正文
     * @return 不可缓存的消息标识和受理时间
     * @throws JacksonException 请求正文无法解析
     * @throws RoomMessageFailure 请求无效、冲突、超额或服务无法受理
     * @throws DataAccessException 房间存储故障
     */
    @PostMapping(path = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageReceipt> send(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("roomId") String roomId, @RequestBody byte[] body) {
        JsonNode input = reader.readValue(body);
        if (input == null || !input.isObject() || input.size() != 2
                || !input.path("clientMessageId").isString() || !input.path("content").isString()) {
            throw new RoomMessageFailure(RoomMessageFailure.Code.INVALID_REQUEST);
        }

        // 房间来自路径，发送者和会话只能来自安全链验证过的 JWT。
        var receipt = messages.send(Long.parseLong(jwt.getSubject()), sessionId(jwt), roomId,
                input.get("clientMessageId").asString(), input.get("content").asString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MessageReceipt(receipt.messageId(), UTC_MILLIS.format(receipt.sentAt())));
    }

    @ExceptionHandler(RoomMessageFailure.class)
    ResponseEntity<AuthHttpError> businessError(RoomMessageFailure failure) {
        HttpStatus status = switch (failure.code()) {
            case INVALID_REQUEST, TICKET_INVALID -> HttpStatus.BAD_REQUEST;
            case ROOM_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MESSAGE_ID_CONFLICT -> HttpStatus.CONFLICT;
            case MESSAGE_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ROOM_MESSAGES_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (failure.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()));
        }
        return response.body(new AuthHttpError(failure.code().name(), failure.getMessage()));
    }

    @ExceptionHandler({JacksonException.class, HttpMessageNotReadableException.class})
    ResponseEntity<AuthHttpError> invalidBody() {
        return businessError(new RoomMessageFailure(RoomMessageFailure.Code.INVALID_REQUEST));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<AuthHttpError> dependencyUnavailable() {
        return businessError(new RoomMessageFailure(RoomMessageFailure.Code.ROOM_MESSAGES_UNAVAILABLE));
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthHttpError> authError(AuthFailure failure) {
        return ResponseEntity.status(AuthHttpError.status(failure.code())).cacheControl(CacheControl.noStore())
                .body(AuthHttpError.from(failure.code()));
    }

    private static UUID sessionId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }

    /** HTTP 提交回执。 */
    public record MessageReceipt(String messageId, String sentAt) {}
}
