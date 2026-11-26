package com.liveflow.api.room.message;

import com.liveflow.api.auth.AuthHttpError;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.message.RoomEvents;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomHistoryService;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.service.RoomExistenceStore;
import com.liveflow.core.room.message.RoomMessagePorts.Receipts;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import com.liveflow.core.room.message.RoomMessageService;
import com.liveflow.core.room.message.RoomTicketService;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final RoomTicketService tickets;
    private final RoomMessageService messages;
    private final ObjectReader reader;
    private final RoomHistoryService history;
    private final Receipts receipts;
    private final io.micrometer.core.instrument.Counter unconfirmed;
    private final io.micrometer.core.instrument.Counter catchups;
    private final io.micrometer.core.instrument.Counter sendUnknown;

    /**
     * @param tickets 房间票据服务
     * @param messages 弹幕提交与生产确认服务
     * @param history 以一致性快照提供历史的服务
     * @param receipts 当前登录范围内的回执投影
     * @param metrics 无房间或账号标签的请求指标
     * @param json 应用 JSON 映射器
     */
    public RoomMessageController(RoomTicketService tickets, RoomMessageService messages, RoomHistoryService history,
            Receipts receipts, MeterRegistry metrics, JsonMapper json) {
        this.tickets = tickets;
        this.messages = messages;
        this.history = history;
        this.receipts = receipts;
        this.unconfirmed = metrics.counter("liveflow.room.messages.receipt.unconfirmed");
        this.catchups = metrics.counter("liveflow.room.messages.history.catchup");
        this.sendUnknown = metrics.counter("liveflow.room.messages.send.unknown");
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
     * 提交一次弹幕，在 Broker 确认后提供正式回执的查询地址。
     * @param jwt 安全链已验证的当前登录身份
     * @param roomId 路径中的房间 UUID
     * @param body 仅允许提交标识和正文的 UTF-8 JSON
     * @return Broker 确认后的 202，附同房间下的回执地址
     * @throws RoomMessageFailure 参数、预约或生产不满足接入条件
     */
    @PostMapping(path = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<QueuedResponse>> send(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("roomId") String roomId, @RequestBody byte[] body) {
        JsonNode input = reader.readValue(body);
        if (input == null || !input.isObject() || input.size() != 2
                || !input.path("clientMessageId").isString() || !input.path("content").isString()) {
            throw invalid();
        }

        var attempt = messages.send(scope(jwt, roomId), input.get("clientMessageId").asString(),
                input.get("content").asString());
        // 只约束 HTTP 等待，实际生产、熔断回报与原预约写回继续完成。
        return attempt.confirmation().copy().orTimeout(3, TimeUnit.SECONDS).handle((queued, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new RoomMessageFailure(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN)
                        .withReceipt(attempt.reservation());
            }
            String location = "/api/v1/rooms/" + roomId + "/messages/submissions/" + queued.submissionId();
            var response = new QueuedResponse(queued.submissionId(), "queued",
                    RoomEvents.UTC_MILLIS.format(queued.receiptExpiresAt()));
            return ResponseEntity.accepted().location(URI.create(location)).cacheControl(CacheControl.noStore()).body(response);
        });
    }

    /**
     * @param jwt 当前登录会话
     * @param roomId 作用域中的房间
     * @param submissionId 公开预约标识
     * @param query 应为空的查询参数
     * @param request 用于核对请求未带正文
     * @return 当前作用域的公开终态或 unconfirmed
     */
    @GetMapping("/messages/submissions/{submissionId}")
    public ResponseEntity<Map<String, Object>> receipt(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("roomId") String roomId, @PathVariable("submissionId") String submissionId,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        if (!RoomUuid.isCanonical(roomId) || !RoomUuid.isCanonical(submissionId) || !query.isEmpty()
                || request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw invalid();
        }
        var receipt = receipts.find(scope(jwt, roomId), UUID.fromString(submissionId));
        Map<String, Object> response;
        if (receipt.isEmpty()) {
            unconfirmed.increment();
            response = Map.of("submissionId", submissionId, "status", "unconfirmed");
        } else {
            var value = receipt.get();
            response = "accepted".equals(value.status())
                    ? Map.of("submissionId", submissionId, "status", "accepted", "messageId", value.messageId(),
                            "roomSeq", value.roomSeq(), "sentAt", value.sentAt())
                    : Map.of("submissionId", submissionId, "status", "rejected", "code", value.code());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * 读取最近一页、向上翻页或按序号范围补拉仍在保留期的消息。
     * @param roomId 已认证客户端读取的房间
     * @param query 分页参数，每个名称只允许出现一次
     * @return 包含最大落库序号、实际读取上限及分页游标的不可缓存历史页
     * @throws RoomMessageFailure 参数不合法或历史不可用
     */
    @GetMapping("/messages")
    public ResponseEntity<RoomHistoryService.Page> history(@PathVariable("roomId") String roomId,
            @RequestParam MultiValueMap<String, String> query) {
        if (!Set.of("limit", "beforeSeq", "afterSeq", "throughSeq").containsAll(query.keySet())
                || query.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
        if (!RoomUuid.isCanonical(roomId)) throw invalid();
        Long limit = number(query.getFirst("limit"), 100);
        Long before = number(query.getFirst("beforeSeq"), RoomEvents.MAX_SEQUENCE);
        Long after = number(query.getFirst("afterSeq"), RoomEvents.MAX_SEQUENCE);
        Long through = number(query.getFirst("throughSeq"), RoomEvents.MAX_SEQUENCE);
        if (limit != null && limit == 0) throw invalid();
        if (before != null && (before == 0 || after != null || through != null)) throw invalid();
        if (through != null && (after == null || through < after)) throw invalid();
        if (after != null) catchups.increment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(history.page(roomId, limit == null ? 100 : limit.intValue(), before, after, through));
    }

    private static RoomMessageFailure invalid() {
        return new RoomMessageFailure(RoomMessageFailure.Code.INVALID_REQUEST);
    }

    private static Long number(String value, long max) {
        if (value == null) return null;
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            long result = Long.parseLong(value);
            if (result > max) throw invalid();
            return result;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static Scope scope(Jwt jwt, String roomId) {
        return new Scope(Long.parseLong(jwt.getSubject()), sessionId(jwt), roomId);
    }

    @ExceptionHandler(RoomMessageFailure.class)
    ResponseEntity<Map<String, Object>> businessError(RoomMessageFailure failure) {
        HttpStatus status = switch (failure.code()) {
            case INVALID_REQUEST, TICKET_INVALID -> HttpStatus.BAD_REQUEST;
            case ROOM_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MESSAGE_ID_CONFLICT -> HttpStatus.CONFLICT;
            case MESSAGE_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case ROOM_MESSAGES_UNAVAILABLE, MESSAGE_SUBMISSION_UNKNOWN -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (failure.code() == RoomMessageFailure.Code.MESSAGE_RATE_LIMITED && failure.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(failure.retryAfterSeconds()));
        }
        if (failure.code() == RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN) {
            sendUnknown.increment();
            var receipt = failure.receipt();
            if (receipt != null) {
                return response.body(Map.of("code", failure.code().name(), "message", failure.getMessage(),
                        "submissionId", receipt.submissionId(),
                        "receiptExpiresAt", RoomEvents.UTC_MILLIS.format(receipt.receiptExpiresAt())));
            }
        }
        return response.body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
    }

    @ExceptionHandler({JacksonException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalidBody() {
        return businessError(new RoomMessageFailure(RoomMessageFailure.Code.INVALID_REQUEST));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, RoomExistenceStore.Unavailable.class})
    ResponseEntity<Map<String, Object>> dependencyUnavailable() {
        return businessError(new RoomMessageFailure(RoomMessageFailure.Code.ROOM_MESSAGES_UNAVAILABLE));
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthHttpError> authError(AuthFailure failure) {
        return ResponseEntity.status(AuthHttpError.status(failure.code())).cacheControl(CacheControl.noStore())
                .body(AuthHttpError.from(failure.code()));
    }

    /**
     * 从已验证的身份提取会话标识，供票据绑定及弹幕判重使用。
     * @param jwt 通过安全过滤链的 Access Token
     * @return 登录会话 UUID
     */
    private static UUID sessionId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }

    /**
     * @param submissionId 本次预约的公开回执标识
     * @param status 固定为 queued
     * @param receiptExpiresAt 查询截止，UTC 毫秒
     */
    public record QueuedResponse(UUID submissionId, String status, String receiptExpiresAt) {}
}
