package com.liveflow.core.profile.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.dao.DataAccessException;

/** 从账号表定位本人资料，按账号主键写入展示字段。 */
@Mapper
public interface UserProfileMapper {

    /**
     * 查找账号及其可选资料。
     *
     * @param userId 已认证的账号 ID
     * @return 账号与资料数据，账号不存在时返回 null，资料尚未创建时资料列为空
     * @throws DataAccessException 查询失败
     */
    @Select("""
            SELECT u.id AS userId, u.username, p.nickname, p.avatar_key AS avatarKey, p.bio
            FROM users AS u
            LEFT JOIN user_profiles AS p ON p.user_id = u.id
            WHERE u.id = #{userId}
            """)
    Row find(@Param("userId") long userId);

    /**
     * 为仍存在的账号写入一份完整资料，更新时保留创建时间。
     *
     * @param userId 已认证的账号 ID
     * @param nickname 规范化后的昵称
     * @param avatarKey 已校验的预设头像标识
     * @param bio 规范化后的个人简介
     * @throws DataAccessException 写入失败
     */
    @Insert("""
            INSERT INTO user_profiles (user_id, nickname, avatar_key, bio, created_at, updated_at)
            SELECT id, #{nickname}, #{avatarKey}, #{bio}, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
            FROM users WHERE id = #{userId}
            ON DUPLICATE KEY UPDATE
                nickname = #{nickname},
                avatar_key = #{avatarKey},
                bio = #{bio},
                updated_at = UTC_TIMESTAMP(3)
            """)
    void upsert(@Param("userId") long userId, @Param("nickname") String nickname,
            @Param("avatarKey") String avatarKey, @Param("bio") String bio);

    /** 账号和资料的联合查询结果。 */
    record Row(long userId, String username, String nickname, String avatarKey, String bio) {}
}
