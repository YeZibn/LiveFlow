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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** 事务化管理历史消息、最大落库序号以及时间保留边界。 */
@Service
public class RoomHistoryService {
    private static final int CLEANUP_BATCH_SIZE = 500;
    private final RoomHistoryMapper mapper;
    private final RoomService rooms;
    private final RoomHistoryProtection protection;
    private final TransactionTemplate write;
    private final TransactionTemplate snapshot;

    /**
     * @param mapper 历史记录与状态的数据库访问
     * @param rooms 鉴权后用于确认房间存在
     * @param protection 读取跨实例共享的历史分区阻断
     * @param transactions MySQL 事务管理器
     */
    public RoomHistoryService(RoomHistoryMapper mapper, RoomService rooms, RoomHistoryProtection protection,
            PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.rooms = rooms;
        this.protection = protection;
        write = new TransactionTemplate(transactions);
        // 每次尝试独立提交，发生键竞争后重读时必须开启新的数据库事务。
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * 在一个事务中处理单分区批次，事务提交后才返回给负责确认 Kafka 位点的调用方。
     * @param batch 已验证的正式事件，按同分区 Kafka offset 升序传入
     * @return 本批新增记录和确认的单条冲突，供上游记录观测信息
     * @throws org.springframework.dao.DataAccessException 数据库结果未确认，原 Kafka 位点应保留
     */
    public AppendResult append(List<HistoryRecord> batch) {
        LocalDateTime cutoff = retentionCutoff();
        List<Candidate> candidates = batch.stream()
                .map(record -> new Candidate(record, row(record.event())))
                .filter(candidate -> candidate.row().sentAt().isAfter(cutoff))
                .toList();
        if (candidates.isEmpty()) return new AppendResult(List.of(), List.of());
        try {
            return write.execute(status -> writeBatch(candidates));
        } catch (DuplicateKeyException failure) {
            // 其他分区可能刚占用同一个全局消息 ID。整批回滚后重读，只有命中原记录才能裁决。
            return write.execute(status -> writeBatch(candidates));
        }
    }

    private AppendResult writeBatch(List<Candidate> candidates) {
        List<Row> rows = candidates.stream().map(Candidate::row).toList();
        rows.stream().map(Row::roomId).distinct().sorted().forEach(mapper::lockOrInitialize);

        Map<RoomSequence, Row> bySequence = new HashMap<>();
        Map<String, Row> byMessageId = new HashMap<>();
        for (Row existing : mapper.existing(rows)) {
            bySequence.put(new RoomSequence(existing.roomId(), existing.roomSeq()), existing);
            byMessageId.put(existing.messageId(), existing);
        }

        List<Row> pending = new ArrayList<>();
        List<Accepted> inserted = new ArrayList<>();
        List<HistoryConflict> conflicts = new ArrayList<>();
        Map<String, Long> maxByRoom = new HashMap<>();
        for (Candidate candidate : candidates) {
            Row row = candidate.row();
            RoomSequence sequence = new RoomSequence(row.roomId(), row.roomSeq());
            Row sameSequence = bySequence.get(sequence);
            Row sameMessageId = byMessageId.get(row.messageId());
            if (sameSequence == null && sameMessageId == null) {
                // 同批后续事件也要看到已占用的两个键，才能保留 offset 更早的有效记录。
                bySequence.put(sequence, row);
                byMessageId.put(row.messageId(), row);
                pending.add(row);
                inserted.add(candidate.record().event());
                maxByRoom.merge(row.roomId(), row.roomSeq(), Math::max);
            } else if (!row.equals(sameSequence) || !row.equals(sameMessageId)) {
                conflicts.add(new HistoryConflict(candidate.record(),
                        sameSequence == null ? null : sameSequence.messageId(),
                        sameMessageId == null ? null : sameMessageId.messageId()));
            }
        }
        if (!pending.isEmpty()) {
            mapper.insert(pending);
            maxByRoom.forEach((roomId, latest) -> mapper.advance(new State(roomId, latest)));
        }
        return new AppendResult(List.copyOf(inserted), List.copyOf(conflicts));
    }

    /**
     * 在同一读快照中核对分区阻断、最大落库序号和当前历史页。
     * @param roomId 规范房间 UUID
     * @param size 请求的历史条数
     * @param after 可选的排他游标，省略时读取最近窗口
     * @param through 可选的请求上界，由接口验证与游标的关系
     * @return 按房间序号升序的公开历史页
     * @throws RoomMessageFailure 房间无效、缺失或历史无法读取
     */
    public Page page(String roomId, int size, Long after, Long through) {
        if (!RoomUuid.isCanonical(roomId)) throw new RoomMessageFailure(INVALID_REQUEST);
        if (!rooms.exists(roomId)) throw new RoomMessageFailure(ROOM_NOT_FOUND);
        LocalDateTime cutoff = retentionCutoff();
        return snapshot.execute(status -> {
            if (protection.blockedRooms(List.of(roomId)).contains(roomId)) {
                throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
            }
            State state = mapper.state(roomId);
            long latest = state == null ? 0 : state.latestStoredSeq();
            long readThrough = through == null ? latest : Math.min(through, latest);
            List<Row> rows;
            if (after == null) {
                rows = mapper.latest(roomId, readThrough, cutoff, size).reversed();
            } else if (after < readThrough) {
                rows = mapper.range(roomId, after, readThrough, cutoff, size + 1);
            } else {
                rows = List.of();
            }
            String next = rows.size() > size ? Long.toString(rows.get(size - 1).roomSeq()) : null;
            return new Page(rows.stream().limit(size).map(RoomHistoryService::message).toList(),
                    Long.toString(latest), Long.toString(readThrough), next);
        });
    }

    /**
     * 读取活跃房间可用于推送的历史进度。
     * @param roomIds 活跃房间 UUID 分批列表
     * @return 当前快照中未受历史阻断的房间进度
     */
    public List<State> progress(List<String> roomIds) {
        if (roomIds.isEmpty()) return List.of();
        return snapshot.execute(status -> {
            var blocked = protection.blockedRooms(roomIds);
            return mapper.states(roomIds).stream().filter(state -> !blocked.contains(state.roomId())).toList();
        });
    }

    /**
     * 分批清理当前截止之前的历史，房间最大落库序号持续保留。
     * @param stillOwner 每批开始前续租并确认本机仍持有清理权
     * @throws org.springframework.dao.DataAccessException 删除未能完成，后续调度继续检查
     */
    public void clean(BooleanSupplier stillOwner) {
        LocalDateTime cutoff = retentionCutoff();
        int deleted;
        do {
            if (!stillOwner.getAsBoolean()) return;
            deleted = mapper.deleteExpired(cutoff, CLEANUP_BATCH_SIZE);
            // 满批时可能还有积压。保持同一截止时间，并在下一批删除前重新确认租约。
        } while (deleted == CLEANUP_BATCH_SIZE);
    }

    private static LocalDateTime retentionCutoff() {
        // 正式受理时间和 MySQL DATETIME 均为毫秒精度，读写清理使用同一个闭合过期边界。
        return LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC).minusHours(24);
    }

    private static Row row(Accepted event) {
        return new Row(event.roomId(), Long.parseLong(event.roomSeq()), event.messageId().toString(),
                Long.parseLong(event.userId()), event.senderName(), event.content(),
                LocalDateTime.ofInstant(Instant.parse(event.sentAt()), ZoneOffset.UTC));
    }

    private static Message message(Row row) {
        return new Message(Long.toString(row.roomSeq()), UUID.fromString(row.messageId()), row.senderName(),
                row.content(), RoomEvents.UTC_MILLIS.format(row.sentAt().toInstant(ZoneOffset.UTC)));
    }

    private record RoomSequence(String roomId, long number) {}

    private record Candidate(HistoryRecord record, Row row) {}

    /**
     * @param event 已验证的正式弹幕
     * @param offset 该事件自身的 Kafka offset
     */
    public record HistoryRecord(Accepted event, long offset) {}

    /**
     * @param record 已跳过的冲突事件及实际消费位置
     * @param sequenceMatch 同房间序号对应的已有消息 ID，可空
     * @param messageIdMatch 同全局消息 ID 对应的已有消息 ID，可空
     */
    public record HistoryConflict(HistoryRecord record, String sequenceMatch, String messageIdMatch) {}

    /**
     * @param inserted 事务中实际插入的事件，供落库延迟观测
     * @param conflicts 已裁决的冲突，精确重放和过期事件不在其中
     */
    public record AppendResult(List<Accepted> inserted, List<HistoryConflict> conflicts) {}

    /**
     * @param items 同一快照内按序号升序排列的消息
     * @param latestStoredSeq 该房间曾成功写入的最大序号
     * @param throughSeq 本页的实际读取上限
     * @param nextAfterSeq 范围模式仍有下一页时的排他游标，否则为 null
     */
    public record Page(List<Message> items, String latestStoredSeq, String throughSeq, String nextAfterSeq) {}
}
