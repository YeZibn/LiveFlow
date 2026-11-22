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

    static RefreshToken issue(long userId, UUID sessionId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new RefreshToken(userId, sessionId, "rt1." + userId + "." + sessionId + "." + encoded);
    }

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
