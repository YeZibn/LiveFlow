package com.liveflow.core.room.message;

import com.liveflow.core.room.RoomUuid;
import com.liveflow.core.room.message.RoomEvents.Accepted;
import com.liveflow.core.room.message.RoomEvents.Message;
import com.liveflow.core.room.message.RoomHistoryMapper.Row;
import com.liveflow.core.room.message.RoomHistoryMapper.State;
import com.liveflow.core.room.service.RoomService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** 按房间事务化管理弹幕历史、连续位置和过期前缀。 */
@Service
public class RoomHistoryService {
    private static final Logger LOG = LoggerFactory.getLogger(RoomHistoryService.class);
    private final RoomHistoryMapper mapper;
    private final RoomService rooms;
    private final TransactionTemplate write;
    private final TransactionTemplate snapshot;
    private final RoomHistoryProtection protection;
    private final TransactionTemplate report;

    /**

     * @param mapper 历史 Mapper

     * @param rooms 房间验证

     * @param protection 跨实例历史阻断
     * @param transactions MySQL 事务管理器

     */
    public RoomHistoryService(RoomHistoryMapper mapper, RoomService rooms, RoomHistoryProtection protection,
            PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.rooms = rooms;
        this.protection = protection;
        report = new TransactionTemplate(transactions);
        report.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write = new TransactionTemplate(transactions);
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 将单个 Kafka 分区的一批事件在一个事务内写入。
     * @param batch 按该分区 offset 顺序提供的正式事件
     * @param offset 批次起始 Kafka 位点，手工诊断写入时可空
     * @throws IllegalStateException 遇到缺口或同一序号对应不同消息
     */
    public void append(List<Accepted> batch, Long offset) {
        Map<String, List<Row>> ordered = new TreeMap<>();
        for (Accepted event : batch) {
            ordered.computeIfAbsent(event.roomId(), ignored -> new ArrayList<>()).add(row(event));
        }
        try {
            write.executeWithoutResult(status -> ordered.forEach(this::writeRoom));
        } catch (Conflict failure) {
            // 原批次已经回滚。独立事务保留阻断，接任者可定位原位点。
            try {
                report.executeWithoutResult(status -> protection.recordConflict(failure.roomId, offset));
            } catch (RuntimeException unavailable) {
                LOG.error("弹幕历史阻断保存失败 room={} offset={}", failure.roomId, offset, unavailable);
                throw unavailable;
            }
            throw failure;
        }
    }

    private void writeRoom(String roomId, List<Row> rows) {
        mapper.initialize(roomId);
        State state = mapper.lock(roomId);
        long latest = state.storedThrough();
        var pending = new LinkedHashMap<Long, Row>();
        for (Row row : rows) {
            if (row.roomSeq() < state.earliestSeq()) {
                continue; // 已清理的旧重放不能把历史重新写回来。
            }
            if (row.roomSeq() <= latest) {
                Row original = pending.get(row.roomSeq());
                if (original == null) original = mapper.one(roomId, row.roomSeq());
                if (!same(row, original)) throw new Conflict(roomId, null);
            } else if (row.roomSeq() != latest + 1) {
                throw new Conflict(roomId, null);
            } else {
                pending.put(row.roomSeq(), row);
                latest = row.roomSeq();
            }
        }
        if (!pending.isEmpty()) {
            try {
                mapper.insert(List.copyOf(pending.values()));
            } catch (DuplicateKeyException failure) {
                throw new Conflict(roomId, failure);
            }
            mapper.update(new State(roomId, latest, state.earliestSeq()));
        }
    }

    /**
     * 从同一数据库快照读取水位及消息页。
     * @param roomId 已校验的房间 UUID
     * @param size 请求的历史条数
     * @param after 可选的排他游标
     * @param through 可选的补拉上界
     * @return 向浏览器公开的有序历史页
     * @throws RoomMessageFailure 房间无效、缺失或历史无法读取
     * @throws Gap 客户端游标指向已清理范围
     */
    public Page page(String roomId, int size, Long after, Long through) {
        if (!RoomUuid.isCanonical(roomId)) throw new RoomMessageFailure(INVALID_REQUEST);
        if (!rooms.exists(roomId)) throw new RoomMessageFailure(ROOM_NOT_FOUND);
        return snapshot.execute(status -> {
            if (protection.blockedRooms(List.of(roomId)).contains(roomId)) {
                throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
            }
            State state = mapper.state(roomId);
            long earliest = state == null ? 1 : state.earliestSeq();
            long stored = state == null ? 0 : state.storedThrough();
            if (after == null) {
                List<Message> items = mapper.latest(roomId, stored, size).reversed().stream()
                        .map(RoomHistoryService::message).toList();
                return new Page(items, Long.toString(earliest), Long.toString(stored), Long.toString(stored), null);
            }
            if (after < earliest - 1) throw new Gap(earliest, stored);
            long target = through == null ? Math.max(after, stored) : through;
            List<Row> rows = mapper.range(roomId, after, Math.min(stored, target), size + 1);
            String next = rows.size() > size ? Long.toString(rows.get(size - 1).roomSeq()) : null;
            return new Page(rows.stream().limit(size).map(RoomHistoryService::message).toList(),
                    Long.toString(earliest), Long.toString(stored), Long.toString(target), next);
        });
    }

    /**

     * @param roomIds 活跃房间 UUID 分批列表

     * @return 可用房间的连续落库水位

     */
    public List<State> watermarks(List<String> roomIds) {
        if (roomIds.isEmpty()) return List.of();
        return snapshot.execute(status -> {
            var blocked = protection.blockedRooms(roomIds);
            return mapper.states(roomIds).stream().filter(state -> !blocked.contains(state.roomId())).toList();
        });
    }

    /** @param stillOwner 开启下一房间批次前确认清理租约仍归本机 */
    public void clean(BooleanSupplier stillOwner) {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        for (String roomId : mapper.expiredRooms(cutoff)) {
            if (!stillOwner.getAsBoolean()) return;
            write.executeWithoutResult(status -> {
                State state = mapper.lock(roomId);
                if (state == null) return;
                List<Row> rows = mapper.range(roomId, state.earliestSeq() - 1, state.storedThrough(), 500);
                long through = state.earliestSeq() - 1;
                for (Row row : rows) {
                    if (row.roomSeq() != through + 1 || row.storedAt().isAfter(cutoff)) break;
                    through = row.roomSeq();
                }
                if (through >= state.earliestSeq()) {
                    mapper.trim(roomId, through);
                    mapper.update(new State(roomId, state.storedThrough(), through + 1));
                }
            });
        }
    }

    /** 历史事务已回滚且对应房间的持久阻断已记录。 */
    public static final class Conflict extends IllegalStateException {
        private final String roomId;

        private Conflict(String roomId, Throwable cause) {
            super("房间历史存在序号缺口或内容冲突", cause);
            this.roomId = roomId;
        }
    }

    private static Row row(Accepted e) {
        return new Row(e.roomId(), Long.parseLong(e.roomSeq()), e.messageId().toString(), Long.parseLong(e.userId()),
                e.senderName(), e.content(), LocalDateTime.ofInstant(Instant.parse(e.sentAt()), ZoneOffset.UTC), null);
    }

    private static Message message(Row row) {
        return new Message(Long.toString(row.roomSeq()), UUID.fromString(row.messageId()), row.senderName(),
                row.content(), RoomEvents.UTC_MILLIS.format(row.sentAt().toInstant(ZoneOffset.UTC)));
    }

    private static boolean same(Row one, Row other) {
        return other != null && one.messageId().equals(other.messageId()) && one.userId() == other.userId()
                && one.senderName().equals(other.senderName()) && one.content().equals(other.content())
                && Objects.equals(one.sentAt(), other.sentAt());
    }

    /**
     * @param items 在数据库快照内按序号升序排列的历史
     * @param earliestSeq 最早可取序号，空保留范围使用末序号加一
     * @param storedThrough 同一数据库快照的连续落库水位
     * @param targetThrough 本轮补拉固定的上界
     * @param nextAfterSeq 当前快照仍有下一页时使用的排他游标
     */
    public record Page(List<Message> items, String earliestSeq, String storedThrough,
            String targetThrough, String nextAfterSeq) {}

    /** 历史已经清理，响应主动向客户端提供当前可读范围。 */
    public static final class Gap extends RuntimeException {
        public final long earliest;
        public final long stored;

        Gap(long earliest, long stored) {
            this.earliest = earliest;
            this.stored = stored;
        }
    }
}
