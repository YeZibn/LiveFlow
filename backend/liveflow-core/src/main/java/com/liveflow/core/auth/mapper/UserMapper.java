package com.liveflow.core.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 认证模块的账号读取，密码哈希仅出现在登录查询结果中。 */
@Mapper
public interface UserMapper {

    /**
     * 查询用于密码验证的账号数据。
     * @param username 规范化后的用户名
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
