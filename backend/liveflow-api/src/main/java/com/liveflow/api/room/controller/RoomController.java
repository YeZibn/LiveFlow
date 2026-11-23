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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** 处理房间创建、本人查询、统一目录和房间详情。 */
@RestController
public class RoomController {

    private static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> DIRECTORY_PARAMETERS = Set.of("q", "limit", "cursor");

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
     * 浏览全部已创建房间或按名称搜索。
     * @param jwt 安全过滤链已确认会话的 JWT
     * @param parameters 原始查询参数，保留重复值用于严格校验
     * @return 不可缓存的目录分页结果
     * @throws InvalidRoomRequestException 查询参数重复、未知或页大小格式无效
     * @throws RoomService.InvalidDirectoryQueryException 查询词、页大小或游标不符合契约
     * @throws DataAccessException 房间读取失败
     */
    @GetMapping("/api/v1/rooms")
    public ResponseEntity<DirectoryResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.entrySet().stream().anyMatch(entry ->
                !DIRECTORY_PARAMETERS.contains(entry.getKey()) || entry.getValue().size() != 1)) {
            throw new InvalidRoomRequestException();
        }
        // 本人标记始终依据可信账号。请求参数只允许提供搜索和分页条件。
        var page = rooms.list(Long.parseLong(jwt.getSubject()), parameters.getFirst("q"),
                parseLimit(parameters.getFirst("limit")), parameters.getFirst("cursor"));
        var items = page.items().stream().map(room -> new DirectoryItem(room.roomId(), room.title(),
                room.ownerName(), UTC_MILLIS.format(room.createdAt().toInstant(ZoneOffset.UTC)), room.isMine()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DirectoryResponse(items, page.nextCursor()));
    }

    /**
     * 读取目录页大小，格式校验由此处处理，取值范围由房间服务验证。
     * @param rawLimit 原始查询值，缺省时为 null
     * @return 用户提交的整数，缺省时为 12
     * @throws InvalidRoomRequestException 空值、非十进制整数或整型溢出
     */
    private static int parseLimit(String rawLimit) {
        if (rawLimit == null) {
            return 12;
        }
        if (!rawLimit.matches("[0-9]+")) {
            throw new InvalidRoomRequestException();
        }
        try {
            return Integer.parseInt(rawLimit);
        } catch (NumberFormatException failure) {
            throw new InvalidRoomRequestException();
        }
    }

    /**
     * 查询任意已登录用户可访问的房间详情。
     * @param roomId 路径中的标准小写 UUID v4
     * @return 不可缓存的房间标题、对外标识和房主显示名
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
                .body(new RoomDetailsResponse(room.roomId(), room.title(), room.ownerName()));
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

    /**
     * 本人房间查询结果。
     * @param room 已创建的房间，未创建时响应显式包含 null
     */
    public record CurrentRoomResponse(@JsonInclude(JsonInclude.Include.ALWAYS) RoomResponse room) {}

    /**
     * 创建和本人查询共用的房间对象。
     * @param roomId 对外使用的小写 UUID v4
     * @param title 保存后的房间标题
     * @param createdAt 三位小数秒的 UTC 创建时间
     */
    public record RoomResponse(String roomId, String title, String createdAt) {
        private static RoomResponse from(Room room) {
            return new RoomResponse(room.roomId(), room.title(), UTC_MILLIS.format(room.createdAt()));
        }
    }

    /**
     * 直播间详情的公开字段。
     * @param roomId 房间的对外 UUID
     * @param title 保存后的房间标题
     * @param ownerName 房主昵称，未设置时为原拼写账号名
     */
    public record RoomDetailsResponse(String roomId, String title, String ownerName) {}

    /**
     * 统一目录的一条公开房间信息。
     * @param roomId 房间的对外 UUID
     * @param title 保存后的房间标题
     * @param ownerName 房主当前显示名
     * @param createdAt 三位小数秒的 UTC 创建时间，用于展示与排序
     * @param isMine 由认证账号与房主账号比较得到的标记
     */
    public record DirectoryItem(String roomId, String title, String ownerName, String createdAt, boolean isMine) {}

    /**
     * 目录分页结果。
     * @param items 按创建时间及房间 UUID 倒序排列的本页房间
     * @param nextCursor 继续相同查询的不透明游标，终页显式返回 null
     */
    public record DirectoryResponse(List<DirectoryItem> items,
            @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {}

    /** 房间请求的字段、类型或路径参数无效。 */
    static final class InvalidRoomRequestException extends RuntimeException {}

    /** 请求中的房间 UUID 没有对应的记录。 */
    static final class RoomNotFoundException extends RuntimeException {}
}
