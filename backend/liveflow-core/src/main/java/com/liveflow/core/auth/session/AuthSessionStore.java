package com.liveflow.core.auth.session;

import com.liveflow.core.auth.AuthFailure;
import java.time.Instant;
import java.util.UUID;

/** 管理每个账号唯一的当前认证会话，以及注册和登录的限流额度。 */
public interface AuthSessionStore {

    /**
     * 校验 Access Token 所属会话。
     * @param userId 已验证 JWT 中的用户 ID
     * @param sessionId 凭证对应的会话 ID
     * @throws AuthFailure 会话已撤销、已替换或会话存储不可用
     */
    void requireCurrent(long userId, UUID sessionId);

    /**
     * 替换账号的当前会话和历史摘要。
     * @param userId 用户 ID
     * @param sessionId 新会话 ID
     * @param digest 新凭证的摘要
     * @param createdAt 登录时点
     * @param expiresAt 会话绝对到期时点
     * @throws AuthFailure 无法确认原子写入
     */
    void replace(long userId, UUID sessionId, String digest, Instant createdAt, Instant expiresAt);

    /**
     * 原子消费旧凭证并登记新摘要。重放会撤销当前会话。
     * @param userId 用户 ID
     * @param sessionId 待轮换的会话 ID
     * @param digest 当前提交的摘要
     * @param nextDigest 新凭证摘要
     * @return 原会话的绝对到期时点
     * @throws AuthFailure 凭证无效、检测到重放或依赖不可用
     */
    Instant rotate(long userId, UUID sessionId, String digest, String nextDigest);

    /**
     * 定向撤销当前会话。
     * @param userId 用户 ID
     * @param sessionId 指定会话 ID
     * @param digest 可识别的凭证摘要
     * @throws AuthFailure 目标已经失效或会话存储不可用
     */
    void revoke(long userId, UUID sessionId, String digest);

    /**
     * 为登录请求保留一次限流额度。
     * @param username 已校验并保留大小写的账号
     * @param source 可信传输连接给出的来源地址
     * @throws AuthFailure 当前窗口超额或会话存储不可用
     */
    void checkLoginLimit(String username, String source);

    /**
     * 为通过字段校验的注册请求预留一次来源额度。
     * @param source 可信传输连接给出的来源地址
     * @throws AuthFailure 来源窗口超额或限流存储不可用
     */
    void checkRegistrationLimit(String source);
}
