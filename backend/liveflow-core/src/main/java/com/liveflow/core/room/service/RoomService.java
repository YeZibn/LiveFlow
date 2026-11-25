package com.liveflow.core.room.service;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.room.mapper.RoomMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理房间的读取与一次性创建。 */
@Service
public class RoomService {

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)\\A\\s+|\\s+\\z");
    private static final Pattern OWNER_KEY = Pattern.compile("'(?:[^']+\\.)?uk_rooms_owner_user_id'$");

    private static final Pattern UUID_KEY = Pattern.compile("'(?:[^']+\\.)?uk_rooms_room_uuid'$");

    private final RoomMapper rooms;
    private final RoomExistenceStore existence;
    private final TransactionTemplate transaction;

    /**
     * @param rooms 房间数据访问 Mapper
     * @param existence 房间存在性读取及提交后预热
     * @param transactionManager MySQL 事务管理器
     */
    public RoomService(RoomMapper rooms, RoomExistenceStore existence, PlatformTransactionManager transactionManager) {
        this.rooms = rooms;
        this.existence = existence;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 读取本人房间。
     * @param ownerUserId 已认证的账号 ID
     * @return 持久化房间，尚未创建时为空
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 读取失败
     */
    public Optional<Room> read(long ownerUserId) {
        RoomMapper.Row row = rooms.find(ownerUserId);
        if (row == null) {
            throw new AuthFailure(AuthFailure.Code.AUTH_SESSION_INACTIVE);
        }
        if (row.roomId() == null) {
            return Optional.empty();
        }
        // DATETIME 没有时区，插入语句明确按 UTC 存储。
        return Optional.of(new Room(row.roomId(), row.title(), row.createdAt().toInstant(ZoneOffset.UTC)));
    }

    /**
     * 读取已授权访问的房间详情。
     * @param roomId 已通过格式校验的房间 UUID
     * @return 房间详情，房间不存在时为空
     * @throws DataAccessException 存储读取失败
     */
    public Optional<RoomMapper.DetailsRow> readDetails(String roomId) {
        return Optional.ofNullable(rooms.findDetails(roomId));
    }

    /**
     * 仅检查房间是否存在，供票据和弹幕写入路径调用。
     * @param roomId 已通过格式校验的房间 UUID
     * @return 房间存在时为 true
     * @throws RoomExistenceStore.Unavailable 超过回源或等待预算，无法确认
     * @throws DataAccessException 存储读取失败
     */
    public boolean exists(String roomId) {
        return existence.exists(roomId);
    }

    /**
     * 使用相同排序读取统一目录或标题搜索结果。
     * @param viewerUserId 已认证账号 ID
     * @param rawKeyword 原始搜索词，缺省时为 null
     * @param limit 请求的页大小
     * @param token 当前搜索的游标，缺省时从首页读取
     * @return 本页数据和可选的下一页游标
     * @throws InvalidDirectoryQueryException 关键词、页大小或游标无效
     * @throws DataAccessException 存储读取失败
     */
    public DirectoryPage list(long viewerUserId, String rawKeyword, int limit, String token) {
        if (limit < 1 || limit > 24 || (rawKeyword != null && !isSingleLine(rawKeyword))) {
            throw new InvalidDirectoryQueryException();
        }
        String keyword = rawKeyword == null ? "" : trim(rawKeyword);
        if (keyword.codePointCount(0, keyword.length()) > 40) {
            throw new InvalidDirectoryQueryException();
        }
        RoomCursor cursor = RoomCursor.decode(token, keyword);
        // 先转义转义符本身，再转义 LIKE 通配符，只保留两端用于包含搜索的百分号。
        String titlePattern = keyword.isEmpty() ? null
                : "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<RoomMapper.DirectoryRow> rows = rooms.list(viewerUserId, titlePattern,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.roomId(), limit + 1);
        if (rows.size() <= limit) {
            return new DirectoryPage(rows, null);
        }
        // 多读的一条只确认有下一页。以最后一条已返回记录作为边界，避免跳过额外行。
        var boundary = rows.get(limit - 1);
        return new DirectoryPage(List.copyOf(rows.subList(0, limit)),
                new RoomCursor(boundary.createdAt(), boundary.roomId()).encode(keyword));
    }

    /**
     * 校验房间名称，在同一事务中插入并回读记录。
     * @param ownerUserId 已认证的账号 ID
     * @param title 原始房间名称
     * @return 此次事务成功提交的房间
     * @throws InvalidTitleException 名称不满足字段规则
     * @throws RoomAlreadyExistsException 账号已拥有房间
     * @throws AuthFailure 账号已不存在
     * @throws DataAccessException 房间写入或回读失败
     * @throws TransactionException 事务无法完成
     */
    public Room create(long ownerUserId, String title) {
        String normalized = normalizeTitle(title);
        Room created;
        try {
            created = insertRoom(ownerUserId, normalized);
        } catch (DuplicateKeyException failure) {
            if (!matchesConstraint(failure, UUID_KEY)) {
                throw failure;
            }
            // UUID 冲突的事务已经回滚。重试生成新 UUID，并开启一笔独立事务。
            created = insertRoom(ownerUserId, normalized);
        }
        // TransactionTemplate 已确认提交，只有最终获胜的 UUID 可以发布正缓存。
        existence.warmCreated(created.roomId());
        return created;
    }

    /**
     * 在独立事务中写入并回读房间。重试调用会生成新的 UUID。
     * @param ownerUserId 已认证的房主账号 ID
     * @param title 已校验并规范化的房间名称
     * @return 事务提交后的房间
     * @throws RoomAlreadyExistsException 房主唯一约束拒绝写入
     * @throws AuthFailure 房主账号已不存在
     * @throws DataAccessException 写入、读取或 UUID 唯一约束失败
     * @throws TransactionException 事务无法完成
     */
    private Room insertRoom(long ownerUserId, String title) {
        String roomId = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            try {
                // 让唯一索引裁决并发创建，避免先查后写的竞态。
                if (rooms.insert(ownerUserId, roomId, title) == 0) {
                    throw new AuthFailure(AuthFailure.Code.AUTH_SESSION_INACTIVE);
                }
            } catch (DuplicateKeyException failure) {
                if (matchesConstraint(failure, OWNER_KEY)) {
                    throw new RoomAlreadyExistsException(failure);
                }
                throw failure;
            }
            // 回读失败会回滚，提交成功后调用方才拿到新房间。
            return read(ownerUserId).orElseThrow(() -> new DataRetrievalFailureException("无法回读新建房间"));
        });
    }

    /**
     * 判断 MySQL 的重复键异常是否来自指定索引。
     * @param failure MyBatis 转换后的重复键异常
     * @param key 匹配底层错误末尾索引名的表达式
     * @return 底层异常明确报告该索引时为 true
     */
    private static boolean matchesConstraint(DuplicateKeyException failure, Pattern key) {
        String detail = failure.getMostSpecificCause().getMessage();
        return detail != null && key.matcher(detail).find();
    }

    /**
     * 先验证原始单行输入，再清理首尾空白并按码点检查长度。
     * @param title 创建请求的原始标题
     * @return 保留内部空白和大小写的标题
     * @throws InvalidTitleException 原始字符或规范化后的长度不符合约定
     */
    private static String normalizeTitle(String title) {
        // 先校验原始字符，避免首尾换行被去除后绕过单行校验。
        if (title == null || !isSingleLine(title)) {
            throw new InvalidTitleException();
        }
        String normalized = trim(title);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 40) {
            throw new InvalidTitleException();
        }
        return normalized;
    }

    /**
     * 验证标题与关键词共用的字符规则，拒绝控制字符、行段分隔符和未配对代理项。
     * @param value 已确认非 null 的原始字符串
     * @return 原始字符串可作为单行文本时为 true
     */
    private static boolean isSingleLine(String value) {
        return value.codePoints().noneMatch(point -> Character.isISOControl(point)
                || point == 0x2028 || point == 0x2029
                || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE));
    }

    /**
     * 按 Unicode 空白规则清理输入两端。
     * @param value 已完成字符校验的非 null 输入
     * @return 保留内部空白的字符串
     */
    private static String trim(String value) {
        return EDGE_WHITESPACE.matcher(value).replaceAll("");
    }

    /** 持久化房间的对外 UUID、名称和 UTC 创建时间。 */
    public record Room(String roomId, String title, Instant createdAt) {}

    /** 有序的房间目录结果及下一页读取边界。 */
    public record DirectoryPage(List<RoomMapper.DirectoryRow> items, String nextCursor) {}

    /** 目录关键词、页大小或游标不符合接口契约。 */
    public static final class InvalidDirectoryQueryException extends RuntimeException {}

    /** 名称无法保存为合法的单行文本。 */
    public static final class InvalidTitleException extends RuntimeException {
        private InvalidTitleException() {
            super("请输入 1–40 个字符的单行房间名称");
        }
    }

    /** 房主唯一约束拒绝创建。 */
    public static final class RoomAlreadyExistsException extends RuntimeException {
        private RoomAlreadyExistsException(DuplicateKeyException cause) {
            super("当前账号已创建直播间", cause);
        }
    }
}
