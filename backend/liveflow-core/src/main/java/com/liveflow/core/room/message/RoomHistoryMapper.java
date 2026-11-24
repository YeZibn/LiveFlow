package com.liveflow.core.room.message;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 按房间主键维护历史消息与连续水位。 */
@Mapper
public interface RoomHistoryMapper {
    String ROW = "room_id AS roomId, room_seq AS roomSeq, message_id AS messageId, "
            + "sender_user_id AS userId, sender_name AS senderName, content, sent_at AS sentAt, stored_at AS storedAt";
    String STATE = "room_id AS roomId, stored_through AS storedThrough, earliest_seq AS earliestSeq";

    /** @param roomId 房间 UUID，首次写入前创建初始水位 */
    @Insert("""
            INSERT INTO room_message_history_state (room_id, stored_through, earliest_seq, updated_at)
            VALUES (#{roomId}, 0, 1, UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE room_id = room_id
            """)
    void initialize(@Param("roomId") String roomId);

    /**

     * @param roomId 房间 UUID

     * @return 当前事务锁定的水位

     */
    @Select("SELECT " + STATE + " FROM room_message_history_state WHERE room_id = #{roomId} FOR UPDATE")
    State lock(@Param("roomId") String roomId);

    /**

     * @param roomId 房间 UUID

     * @return 当前快照的水位或 null

     */
    @Select("SELECT " + STATE + " FROM room_message_history_state WHERE room_id = #{roomId}")
    State state(@Param("roomId") String roomId);

    /**

     * @param roomIds 有界活跃房间集合

     * @return 已存在的各房间水位

     */
    @Select("<script>SELECT " + STATE + " FROM room_message_history_state WHERE room_id IN "
            + "<foreach collection='roomIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<State> states(@Param("roomIds") List<String> roomIds);

    /** @param rows 已校验连续且尚未写入的消息 */
    @Insert("""
            <script>
            INSERT INTO room_messages (room_id,room_seq,message_id,sender_user_id,sender_name,content,sent_at,stored_at)
            VALUES
            <foreach collection="rows" item="r" separator=",">
                (#{r.roomId},#{r.roomSeq},#{r.messageId},#{r.userId},#{r.senderName},#{r.content},#{r.sentAt},UTC_TIMESTAMP(3))
            </foreach>
            </script>
            """)
    void insert(@Param("rows") List<Row> rows);

    /**

     * @param roomId 房间 UUID

     * @param sequence 需验证的房间序号

     * @return 原记录或 null

     */
    @Select("SELECT " + ROW + " FROM room_messages WHERE room_id = #{roomId} AND room_seq = #{sequence}")
    Row one(@Param("roomId") String roomId, @Param("sequence") long sequence);

    /** @param state 写入或裁剪后的连续范围 */
    @Update("""
            UPDATE room_message_history_state SET stored_through = #{storedThrough}, earliest_seq = #{earliestSeq},
                updated_at = UTC_TIMESTAMP(3) WHERE room_id = #{roomId}
            """)
    void update(State state);

    /**

     * @param roomId 房间 UUID

     * @param through 快照上限

     * @param size 最大条数

     * @return 倒序历史

     */
    @Select("SELECT " + ROW + " FROM room_messages WHERE room_id = #{roomId} AND room_seq <= #{through}"
            + " ORDER BY room_seq DESC LIMIT #{size}")
    List<Row> latest(@Param("roomId") String roomId, @Param("through") long through, @Param("size") int size);

    /**

     * @param roomId 房间 UUID

     * @param after 排他游标

     * @param through 含上界

     * @param size 最大条数

     * @return 升序历史

     */
    @Select("SELECT " + ROW + " FROM room_messages WHERE room_id = #{roomId}"
            + " AND room_seq > #{after} AND room_seq <= #{through} ORDER BY room_seq LIMIT #{size}")
    List<Row> range(@Param("roomId") String roomId, @Param("after") long after,
            @Param("through") long through, @Param("size") int size);

    /**

     * @param cutoff 最迟已过期的存储时间

     * @return 有界候选房间列表

     */
    @Select("""
            SELECT DISTINCT room_id FROM
                (SELECT room_id FROM room_messages WHERE stored_at <= #{cutoff}
                 ORDER BY stored_at, room_id, room_seq LIMIT 500) AS candidates
            """)
    List<String> expiredRooms(@Param("cutoff") LocalDateTime cutoff);

    /**

     * @param roomId 房间 UUID

     * @param through 已验证的过期前缀末尾

     */
    @Delete("DELETE FROM room_messages WHERE room_id = #{roomId} AND room_seq <= #{through}")
    void trim(@Param("roomId") String roomId, @Param("through") long through);

    record State(String roomId, long storedThrough, long earliestSeq) {}
    record Row(String roomId, long roomSeq, String messageId, long userId, String senderName, String content,
            LocalDateTime sentAt, LocalDateTime storedAt) {}
}
