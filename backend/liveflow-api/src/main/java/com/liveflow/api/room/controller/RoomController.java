package com.liveflow.api.room.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.service.RoomService;
import com.liveflow.core.room.service.RoomService.InvalidTitleException;
import com.liveflow.core.room.service.RoomService.Room;
import com.liveflow.core.room.service.RoomService.RoomAlreadyExistsException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** 处理房间创建、本人查询及已登录用户的房间详情请求。 */
@RestController
public class RoomController {

    private static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final RoomService rooms;
    private final ObjectReader requestReader;

    /**
     * @param rooms 房间读写服务
     * @param json 应用的 JSON 映射器
     */
    public RoomController(RoomService rooms, JsonMapper json) {
        this.rooms = rooms;
        // 严格解析仅作用于创建房间，拒绝重复键和同一正文附带的第二个 JSON 值。
        this.requestReader = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * 查询本人房间，尚未创建时返回显式空值。
     * @param jwt 已验证签名与当前会话的 JWT
     * @return 不可缓存的本人房间响应
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 房间读取失败
     */
    @GetMapping("/api/v1/users/me/room")
    public ResponseEntity<CurrentRoomResponse> read(@AuthenticationPrincipal Jwt jwt) {
        RoomResponse room = rooms.read(Long.parseLong(jwt.getSubject())).map(RoomResponse::from).orElse(null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CurrentRoomResponse(room));
    }

    /**
     * 查询任意已登录用户可访问的房间详情。
     * @param roomId 路径中的标准小写 UUID v4
     * @return 不可缓存的房间标题和对外标识
     * @throws InvalidRoomRequestException 标识格式无效
     * @throws RoomNotFoundException UUID 对应的房间不存在
     * @throws DataAccessException 房间读取失败
     */
    @GetMapping("/api/v1/rooms/{roomId}")
    public ResponseEntity<RoomDetailsResponse> readDetails(@PathVariable("roomId") String roomId) {
        if (!RoomUuid.isCanonical(roomId)) {
            throw new InvalidRoomRequestException();
        }
        // 安全过滤链先校验当前会话，详情仅暴露渲染房间页所需的字段。
        var room = rooms.readDetails(roomId).orElseThrow(RoomNotFoundException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RoomDetailsResponse(room.roomId(), room.title()));
    }

    /**
     * 校验创建请求并为认证账号创建房间。
     * @param jwt 已验证签名与当前会话的 JWT
     * @param body 客户端提交的 JSON 字节
     * @return 已提交房间的不可缓存响应
     * @throws JacksonException JSON 无法解析
     * @throws InvalidRoomRequestException 请求不是只含字符串 title 的对象
     * @throws InvalidTitleException 名称不满足业务规则
     * @throws RoomAlreadyExistsException 当前账号已拥有房间
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 读写失败
     * @throws TransactionException 事务无法完成
     */
    @PostMapping(path = "/api/v1/rooms", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RoomResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody byte[] body) {
        JsonNode input = requestReader.readValue(body);
        if (input == null || !input.isObject() || input.size() != 1 || !input.path("title").isString()) {
            throw new InvalidRoomRequestException();
        }
        // 房主只能来自已验证的 JWT，正文只提供名称。
        Room room = rooms.create(Long.parseLong(jwt.getSubject()), input.get("title").asString());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(RoomResponse.from(room));
    }

    /** 本人房间查询结果，房间未创建时仍发送 room 字段。 */
    public record CurrentRoomResponse(@JsonInclude(JsonInclude.Include.ALWAYS) RoomResponse room) {}

    /** 对外房间信息，ID 和时间均按接口约定序列化。 */
    public record RoomResponse(String roomId, String title, String createdAt) {
        private static RoomResponse from(Room room) {
            return new RoomResponse(room.roomId(), room.title(), UTC_MILLIS.format(room.createdAt()));
        }
    }

    /** 直播间详情的公开字段。 */
    public record RoomDetailsResponse(String roomId, String title) {}

    /** 房间请求的字段、类型或路径参数无效。 */
    static final class InvalidRoomRequestException extends RuntimeException {}

    /** 请求中的房间 UUID 没有对应的记录。 */
    static final class RoomNotFoundException extends RuntimeException {}
}
