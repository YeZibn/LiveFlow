package com.liveflow.core.room.service;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.room.mapper.RoomMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理本人房间的读取与一次性创建。 */
@Service
public class RoomService {

    private static final Pattern EDGE_WHITESPACE = Pattern.compile("(?U)\\A\\s+|\\s+\\z");
    private static final Pattern OWNER_KEY = Pattern.compile("'(?:[^']+\\.)?uk_rooms_owner_user_id'$");

    private final RoomMapper rooms;
    private final TransactionTemplate transaction;

    /**
     * @param rooms 房间数据访问 Mapper
     * @param transactionManager MySQL 事务管理器
     */
    public RoomService(RoomMapper rooms, PlatformTransactionManager transactionManager) {
        this.rooms = rooms;
        this.transaction = new TransactionTemplate(transactionManager);
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
        return transaction.execute(status -> {
            // 插入时由 MySQL 唯一索引裁决竞争，避免先查后写造成竞态。
            try {
                if (rooms.insert(ownerUserId, normalized) == 0) {
                    throw new AuthFailure(AuthFailure.Code.AUTH_SESSION_INACTIVE);
                }
            } catch (DuplicateKeyException failure) {
                String detail = failure.getMostSpecificCause().getMessage();
                if (detail != null && OWNER_KEY.matcher(detail).find()) {
                    throw new RoomAlreadyExistsException(failure);
                }
                throw failure;
            }
            // 回读失败会回滚，事务提交成功后 Controller 才能拿到结果。
            return read(ownerUserId).orElseThrow(() -> new DataRetrievalFailureException("无法回读新建房间"));
        });
    }

    private static String normalizeTitle(String title) {
        // 先校验原始字符，避免首尾换行被去除后绕过单行校验。
        if (title == null || title.codePoints().anyMatch(point -> Character.isISOControl(point)
                || point == 0x2028 || point == 0x2029
                || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE))) {
            throw new InvalidTitleException();
        }
        String normalized = EDGE_WHITESPACE.matcher(title).replaceAll("");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 40) {
            throw new InvalidTitleException();
        }
        return normalized;
    }

    /** 持久化房间的主键、名称和 UTC 创建时间。 */
    public record Room(long roomId, String title, Instant createdAt) {}

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
