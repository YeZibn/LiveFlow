package com.liveflow.core.auth.service;

import com.liveflow.core.auth.AuthFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 不透明刷新凭证。账号和会话 ID 只用于定位，摘要匹配才证明凭证归属。 */
record RefreshToken(long userId, UUID sessionId, String value) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile(
            "rt1\\.([1-9][0-9]{0,18})\\.([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\\.([A-Za-z0-9_-]{43})");

    /**
     * 为指定登录会话生成新的随机刷新凭证。
     * @param userId 已认证的账号 ID
     * @param sessionId 要写入凭证的会话 ID
     * @return 带定位字段和 32 字节随机秘密的凭证
     */
    static RefreshToken issue(long userId, UUID sessionId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new RefreshToken(userId, sessionId, "rt1." + userId + "." + sessionId + "." + encoded);
    }

    /**
     * 解析定位字段并保留完整原值供摘要计算。归属由 Redis 校验。
     * @param value 浏览器提交的 Refresh Cookie 值
     * @return 格式有效的凭证
     * @throws AuthFailure Cookie 缺失，或版本、格式和账号 ID 无效
     */
    static RefreshToken parse(String value) {
        if (value == null || value.isEmpty()) {
            throw new AuthFailure(AUTH_REQUIRED);
        }
        var matcher = FORMAT.matcher(value);
        if (matcher.matches()) {
            try {
                return new RefreshToken(Long.parseLong(matcher.group(1)), UUID.fromString(matcher.group(2)), value);
            } catch (IllegalArgumentException ex) {
                throw new AuthFailure(AUTH_REFRESH_INVALID);
            }
        }
        throw new AuthFailure(AUTH_REFRESH_INVALID);
    }

    /**
     * 计算完整凭证的 SHA-256，只将摘要交给会话存储。
     * @return 小写十六进制摘要
     * @throws IllegalStateException 当前运行时无法提供 SHA-256
     */
    String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256", ex);
        }
    }

    @Override
    public String toString() {
        return "RefreshToken[redacted]";
    }
}
