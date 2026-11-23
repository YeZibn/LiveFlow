package com.liveflow.core.room.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.dao.DataAccessException;

/** 按账号归属读取和创建房间。 */
@Mapper
public interface RoomMapper {

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
    @Select("SELECT room_uuid AS roomId, title FROM rooms WHERE room_uuid = #{roomId}")
    DetailsRow findDetails(@Param("roomId") String roomId);

    /** 进入房间页面所需的基础信息。 */
    record DetailsRow(String roomId, String title) {}

    /** 联合查询结果，账号 ID 用于区分不存在的账号与尚无房间的账号。 */
    record Row(long ownerUserId, String roomId, String title, LocalDateTime createdAt) {}
}
