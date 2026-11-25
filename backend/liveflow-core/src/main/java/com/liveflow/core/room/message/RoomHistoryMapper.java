package com.liveflow.core.room.message;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 管理按房间排序的历史记录与最大落库序号。 */
@Mapper
public interface RoomHistoryMapper {
    String ROW = "room_id AS roomId, room_seq AS roomSeq, message_id AS messageId, "
            + "sender_user_id AS userId, sender_name AS senderName, content, sent_at AS sentAt";
    String STATE = "room_id AS roomId, latest_stored_seq AS latestStoredSeq";

    /**
     * 在写事务里创建房间状态。已有状态时，通过主键更新持有该行的排他锁。
     * @param roomId 房间 UUID
     */
    @Insert("""
            INSERT INTO room_message_history_state (room_id, latest_stored_seq, updated_at)
            VALUES (#{roomId}, 0, UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE room_id = room_id
            """)
    void lockOrInitialize(@Param("roomId") String roomId);

    /**
     * @param roomId 房间 UUID
     * @return 当前快照的最大落库序号，未写入时返回 null
     */
    @Select("SELECT " + STATE + " FROM room_message_history_state WHERE room_id = #{roomId}")
    State state(@Param("roomId") String roomId);

    /**
     * @param roomIds 有界的活跃房间列表
     * @return 已建立状态的房间及其最大落库序号
     */
    @Select("<script>SELECT " + STATE + " FROM room_message_history_state WHERE room_id IN "
            + "<foreach collection='roomIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<State> states(@Param("roomIds") List<String> roomIds);

    /**
     * 分别沿房间序号主键与全局消息 ID 唯一键核对，允许两条查询命中同一行。
     * @param candidates 待裁决的非空批次
     * @return 命中任一键的历史记录
     */
    @Select("""
            <script>
            SELECT
            """ + ROW + """
             FROM room_messages WHERE (room_id, room_seq) IN
            <foreach collection="candidates" item="r" open="(" separator="," close=")">
                (#{r.roomId}, #{r.roomSeq})
            </foreach>
            UNION ALL
            SELECT
            """ + ROW + """
             FROM room_messages WHERE message_id IN
            <foreach collection="candidates" item="r" open="(" separator="," close=")">#{r.messageId}</foreach>
            </script>
            """)
    List<Row> existing(@Param("candidates") List<Row> candidates);

    /**
     * 批量插入，唯一键或其他写入错误交给事务调用方处理。
     * @param rows 已通过双键裁决的非空消息列表
     */
    @Insert("""
            <script>
            INSERT INTO room_messages (room_id, room_seq, message_id, sender_user_id, sender_name, content, sent_at, stored_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.roomId}, #{r.roomSeq}, #{r.messageId}, #{r.userId}, #{r.senderName}, #{r.content}, #{r.sentAt}, UTC_TIMESTAMP(3))
            </foreach>
            </script>
            """)
    void insert(@Param("rows") List<Row> rows);

    /**
     * @param state 房间和本批实际插入的最大序号，只有超过已保存值时才推进
     */
    @Update("""
            UPDATE room_message_history_state SET latest_stored_seq = #{latestStoredSeq},
                updated_at = UTC_TIMESTAMP(3) WHERE room_id = #{roomId} AND latest_stored_seq < #{latestStoredSeq}
            """)
    void advance(State state);

    /**
     * @param roomId 房间 UUID
     * @param through 快照的最大落库序号
     * @param cutoff 受理时间小于或等于该值的记录已过期
     * @param size 最大返回条数
     * @return 序号倒序的最近历史
     */
    @Select("SELECT " + ROW + " FROM room_messages WHERE room_id = #{roomId} AND room_seq <= #{through}"
            + " AND sent_at > #{cutoff} ORDER BY room_seq DESC LIMIT #{size}")
    List<Row> latest(@Param("roomId") String roomId, @Param("through") long through,
            @Param("cutoff") LocalDateTime cutoff, @Param("size") int size);

    /**
     * @param roomId 房间 UUID
     * @param after 排他的下界
     * @param through 包含的上界
     * @param cutoff 受理时间小于或等于该值的记录已过期
     * @param size 最大返回条数，分页可多读一条确定是否还有下一页
     * @return 序号升序的历史
     */
    @Select("SELECT " + ROW + " FROM room_messages WHERE room_id = #{roomId}"
            + " AND room_seq > #{after} AND room_seq <= #{through} AND sent_at > #{cutoff}"
            + " ORDER BY room_seq LIMIT #{size}")
    List<Row> range(@Param("roomId") String roomId, @Param("after") long after,
            @Param("through") long through, @Param("cutoff") LocalDateTime cutoff, @Param("size") int size);

    /**
     * 按保留索引删除一批过期记录，房间的历史进度不参与清理。
     * @param cutoff 最晚已到期的受理时间
     * @param size 本批允许删除的最大条数
     * @return 实际删除的行数
     */
    @Delete("""
            DELETE FROM room_messages WHERE sent_at <= #{cutoff}
            ORDER BY sent_at, room_id, room_seq LIMIT #{size}
            """)
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff, @Param("size") int size);

    /** @param roomId 房间 UUID
     * @param latestStoredSeq 该房间曾成功落库的最大序号
     */
    record State(String roomId, long latestStoredSeq) {}

    /** @param roomId 房间 UUID
     * @param roomSeq 房间内的正式序号
     * @param messageId 全局消息 ID
     * @param userId 发送者账号 ID
     * @param senderName 正式受理时保留的显示名
     * @param content 规范化的弹幕纯文本
     * @param sentAt UTC 正式受理时间
     */
    record Row(String roomId, long roomSeq, String messageId, long userId, String senderName, String content,
            LocalDateTime sentAt) {}
}
