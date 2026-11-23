package com.liveflow.core.auth.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.springframework.dao.DataAccessException;

/** 认证模块的账号写入和读取，公开查询仅返回账号摘要。 */
@Mapper
public interface UserMapper {

    /**
     * 插入账号并回填数据库生成的 ID。账号名由唯一索引裁决。
     * @param account 待写入的原样账号名和密码哈希
     * @return 写入的行数
     * @throws DataAccessException 用户名重复或账号写入失败
     */
    @Insert("""
            INSERT INTO users (username, password_hash, created_at, updated_at)
            VALUES (#{username}, #{passwordHash}, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewAccount account);

    /**
     * 查询用于密码验证的账号数据。
     * @param username 已校验并保留大小写的用户名
     * @return 账号凭证数据，账号不存在时返回 null
     */
    @Select("SELECT id, password_hash AS passwordHash FROM users WHERE username = #{username}")
    LoginAccount findForLogin(String username);

    /**
     * 按可信认证身份读取当前用户的公开信息。
     * @param userId 通过鉴权的用户 ID
     * @return 公开账号数据，账号不存在时返回 null
     */
    @Select("SELECT id, username FROM users WHERE id = #{userId}")
    Account findById(long userId);

    /** 注册写入参数，MyBatis 在写入后回填 id。 */
    final class NewAccount {
        private Long id;
        private final String username;
        private final String passwordHash;

        /**
         * @param username 已校验的原样账号名
         * @param passwordHash Argon2id 编码结果
         */
        public NewAccount(String username, String passwordHash) {
            this.username = username;
            this.passwordHash = passwordHash;
        }

        /** @return 数据库生成的用户 ID，未回填时为 null */
        public Long id() {
            return id;
        }
    }

    /** 登录所需的用户 ID 和密码哈希。 */
    record LoginAccount(long id, String passwordHash) {
        @Override
        public String toString() {
            return "LoginAccount[id=" + id + "]";
        }
    }

    /** 可用于生成当前用户响应的账号摘要。 */
    record Account(long id, String username) {}
}
