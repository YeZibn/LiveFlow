package com.liveflow.core.room.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.dao.DataAccessException;

/** 按账号归属读取和创建房间。 */
@Mapper
public interface RoomMapper {
    String OWNER_NAME = """
            CASE WHEN p.nickname IS NULL OR CHAR_LENGTH(p.nickname) = 0
                 THEN u.username ELSE p.nickname END AS ownerName
            """;
    String OWNER_JOINS = """
            JOIN users AS u ON u.id = r.owner_user_id
            LEFT JOIN user_profiles AS p ON p.user_id = u.id
            """;

    /**
     * 查询账号及其可选房间。
     * @param ownerUserId 已认证的账号 ID
     * @return 账号及房间数据，账号不存在时返回 null，账号尚无房间时房间列为空
     * @throws DataAccessException 查询失败
     */
    @Select("""
            SELECT u.id AS ownerUserId, r.room_uuid AS roomId, r.title, r.created_at AS createdAt
            FROM users AS u
            LEFT JOIN rooms AS r ON r.owner_user_id = u.id
            WHERE u.id = #{ownerUserId}
            """)
    Row find(@Param("ownerUserId") long ownerUserId);

    /**
     * 为仍存在的账号插入新房间，创建时间由数据库写入 UTC 值。
     * @param ownerUserId 已认证的账号 ID
     * @param roomId 新房间的对外 UUID
     * @param title 已校验并规范化的房间名称
     * @return 插入行数，账号不存在时为零
     * @throws DataAccessException 写入失败，包括房主或房间 UUID 唯一约束冲突
     */
    @Insert("""
            INSERT INTO rooms (room_uuid, owner_user_id, title, created_at)
            SELECT #{roomId}, id, #{title}, UTC_TIMESTAMP(3)
            FROM users
            WHERE id = #{ownerUserId}
            """)
    int insert(@Param("ownerUserId") long ownerUserId, @Param("roomId") String roomId, @Param("title") String title);

    /**
     * 按对外 UUID 查询房间详情。
     * @param roomId 已通过格式校验的房间 UUID
     * @return 房间详情，未找到时返回 null
     * @throws DataAccessException 查询失败
     */
    @Select("SELECT r.room_uuid AS roomId, r.title, " + OWNER_NAME
            + "FROM rooms AS r\n" + OWNER_JOINS + "WHERE r.room_uuid = #{roomId}")
    DetailsRow findDetails(@Param("roomId") String roomId);

    /**
     * 沿索引选出当页房间，再仅为当页记录查询房主显示名。
     * @param viewerUserId 已认证的查看者，用于计算本人标记
     * @param titlePattern 已转义的 LIKE 模式，无关键词时为空
     * @param cursorCreatedAt 上一页最后一条的 UTC 创建时间，首页为空
     * @param cursorRoomId 与创建时间配对的房间 UUID，首页为空
     * @param fetchSize 最大读取条数，包含用于判断下一页的额外一条
     * @return 按创建时间和房间 UUID 倒序排列的房间
     * @throws DataAccessException 房间或房主资料读取失败
     */
    @Select("""
            <script>
            SELECT r.room_uuid AS roomId, r.title, r.created_at AS createdAt,
                   (r.owner_user_id = #{viewerUserId}) AS isMine,
            """ + OWNER_NAME + """
            FROM (
                SELECT room_uuid, title, created_at, owner_user_id
                FROM rooms FORCE INDEX (idx_rooms_created_at_room_uuid)
                <where>
                    <if test="titlePattern != null">
                        title COLLATE utf8mb4_0900_as_ci LIKE #{titlePattern} ESCAPE '!'
                    </if>
                    <if test="cursorCreatedAt != null">
                        AND (created_at &lt; #{cursorCreatedAt}
                            OR (created_at = #{cursorCreatedAt} AND room_uuid &lt; #{cursorRoomId}))
                    </if>
                </where>
                ORDER BY created_at DESC, room_uuid DESC
                LIMIT #{fetchSize}
            ) AS r
            """ + OWNER_JOINS + """
            ORDER BY r.created_at DESC, r.room_uuid DESC
            </script>
            """)
    List<DirectoryRow> list(@Param("viewerUserId") long viewerUserId, @Param("titlePattern") String titlePattern,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt, @Param("cursorRoomId") String cursorRoomId,
            @Param("fetchSize") int fetchSize);

    /**
     * 仅检查 UUID 对应的房间是否存在。
     * @param roomId 已通过格式校验的房间 UUID
     * @return 房间存在时为 true
     * @throws DataAccessException 房间读取失败
     */
    @Select("SELECT EXISTS (SELECT 1 FROM rooms WHERE room_uuid = #{roomId})")
    boolean exists(@Param("roomId") String roomId);

    /** 进入房间页面所需的基础信息。 */
    record DetailsRow(String roomId, String title, String ownerName) {}

    /** 目录查询结果，创建时间按数据库中的 UTC 值读取。 */
    record DirectoryRow(String roomId, String title, LocalDateTime createdAt, boolean isMine, String ownerName) {}

    /** 联合查询结果，账号 ID 用于区分不存在的账号与尚无房间的账号。 */
    record Row(long ownerUserId, String roomId, String title, LocalDateTime createdAt) {}
}
