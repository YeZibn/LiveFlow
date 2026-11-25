package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomHistoryProtection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MySQL 持久阻断由接任方读取，独立于实例快照和消费者生命周期。 */
@Repository
class RoomProcessingBlocks implements RoomHistoryProtection {
    private final JdbcTemplate jdbc;
    private final RoomMessageTopology topology;
    private final RoomMessageInstance instance;

    /** @param jdbc 与历史事务共享的主库访问
     * @param topology 弹幕主题和消费组命名
     * @param instance 报告故障的当前实例身份
     */
    RoomProcessingBlocks(JdbcTemplate jdbc, RoomMessageTopology topology, RoomMessageInstance instance) {
        this.jdbc = jdbc;
        this.topology = topology;
        this.instance = instance;
    }

    /** @param group 职责组
     * @param topic 事件主题
     * @param partition 故障分区
     * @param roomId 房间 UUID，分区级阻断传空字符串
     * @param reason 可持久定位的故障原因
     * @param offset 已观察到的故障位置
     */
    void record(String group, String topic, int partition, String roomId, String reason, Long offset) {
        jdbc.update("""
                INSERT INTO room_message_processing_blocks
                    (consumer_group, topic_name, partition_no, room_id, reason,
                     observed_offset, reported_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3)) AS incoming
                ON DUPLICATE KEY UPDATE reason=incoming.reason, observed_offset=incoming.observed_offset,
                    reported_by=incoming.reported_by, updated_at=incoming.updated_at, revision=room_message_processing_blocks.revision+1
                """, group, topic, partition, roomId, reason, offset, instance.id());
    }

    /** @param group 职责组
     * @param topic 事件主题
     * @return 该职责下所有需要暂停的分区，包括含房间级冲突的分区
     */
    Set<Integer> partitions(String group, String topic) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT partition_no FROM room_message_processing_blocks
                WHERE consumer_group=? AND topic_name=?
                """, Integer.class, group, topic));
    }

    /** @param roomIds 同一历史查询批次中的房间
     * @return 当前历史快照内被阻断的房间
     */
    @Override
    public Set<String> blockedRooms(List<String> roomIds) {
        if (roomIds.isEmpty()) return Set.of();
        // 当前分区数固定且阻断只记录明确异常，批读后按房间匹配，避免逐连接查库。
        return jdbc.query("""
                SELECT partition_no, room_id FROM room_message_processing_blocks
                WHERE consumer_group=? AND topic_name=?
                """, rows -> {
            Set<String> blocked = new HashSet<>();
            Set<Integer> blockedPartitions = new HashSet<>();
            while (rows.next()) {
                String room = rows.getString("room_id");
                if (room.isEmpty()) blockedPartitions.add(rows.getInt("partition_no"));
                else blocked.add(room);
            }
            Set<String> affected = new HashSet<>();
            for (String room : roomIds) {
                if (blocked.contains(room) || blockedPartitions.contains(RoomMessageTopology.partition(room))) {
                    affected.add(room);
                }
            }
            return affected;
        }, topology.group("history"), topology.topic("accepted"));
    }

    /** @param roomId 发生历史冲突的房间
     * @param offset 未确认的批次起点
     */
    @Override
    public void recordConflict(String roomId, Long offset) {
        record(topology.group("history"), topology.topic("accepted"), RoomMessageTopology.partition(roomId),
                roomId, "HISTORY_SEQUENCE_CONFLICT", offset);
    }
}
