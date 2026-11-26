package com.liveflow.api.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 签发和验证匿名预会话及两种用途的 CSRF 凭证。 */
final class CsrfTokens {
    static final Duration PRE_SESSION_LIFETIME = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String VERSION = "v1";
    private static final String HMAC = "HmacSHA256";
    static final int MAX_TOKEN_LENGTH = 160;

    private final String signingId;
    private final Map<String, SecretKeySpec> keys;
    private final Clock clock;

    /**
     * @param signingId 当前签发密钥标识
     * @param signingKey Base64 编码的当前签发密钥
     * @param verificationKeys 逗号分隔的旧密钥，格式为 id=Base64
     * @param clock 计算匿名预会话截止时间的时钟
     */
    CsrfTokens(String signingId, String signingKey, String verificationKeys, Clock clock) {
        this.signingId = signingId;
        this.clock = clock;
        var configured = new HashMap<String, SecretKeySpec>();
        addKey(configured, signingId, signingKey);
        if (!verificationKeys.isBlank()) {
            for (String entry : verificationKeys.split(",", -1)) {
                int separator = entry.indexOf('=');
                if (separator <= 0) throw new IllegalArgumentException("CSRF 验签密钥配置格式无效");
                addKey(configured, entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        keys = Map.copyOf(configured);
    }

    /** @return 新匿名预会话及对应的 CSRF 凭证，两者共享同一截止时间 */
    AnonymousCredentials issueAnonymous() {
        var session = new PreSession(randomValue(), clock.instant().plus(PRE_SESSION_LIFETIME).getEpochSecond());
        String deadline = Long.toString(session.deadline());
        String cookie = String.join(".", VERSION, signingId, session.id(), deadline,
                URL_ENCODER.encodeToString(mac(signingId, "anonymous-session", session.id(), deadline)));
        return new AnonymousCredentials(cookie, issue("pre-auth", session.binding()), Instant.ofEpochSecond(session.deadline()));
    }

    /**
     * @param cookie 匿名预会话 Cookie
     * @param token 与该预会话对应的 CSRF 凭证
     * @return 预会话签名、有效期及 CSRF 绑定均通过
     */
    boolean validAnonymous(String cookie, String token) {
        String[] fields = fields(cookie, 5);
        if (fields == null || !validRandom(fields[2]) || !fields[3].matches("[1-9][0-9]{0,11}")) return false;
        long deadline = Long.parseLong(fields[3]);
        if (clock.instant().getEpochSecond() >= deadline
                || !matches(fields[1], fields[4], "anonymous-session", fields[2], fields[3])) return false;
        return valid(token, "pre-auth", new PreSession(fields[2], deadline).binding());
    }

    /** @param sessionId 服务端确认用于签发的会话 ID
     *  @return 与该登录会话绑定的 CSRF 凭证 */
    String issueSession(UUID sessionId) {
        return issue("session", sessionId.toString());
    }

    /** @param token 请求提交的认证态 CSRF
     *  @param sessionId 本次请求的会话上下文
     *  @return 凭证签名及会话绑定均通过 */
    boolean validSession(String token, UUID sessionId) {
        return valid(token, "session", sessionId.toString());
    }

    private String issue(String purpose, String binding) {
        String random = randomValue();
        return String.join(".", VERSION, signingId, random,
                URL_ENCODER.encodeToString(mac(signingId, purpose, binding, random)));
    }

    private boolean valid(String token, String purpose, String binding) {
        String[] fields = fields(token, 4);
        return fields != null && validRandom(fields[2])
                && matches(fields[1], fields[3], purpose, binding, fields[2]);
    }

    private boolean matches(String id, String signature, String purpose, String binding, String random) {
        return keys.containsKey(id) && validRandom(signature)
                && MessageDigest.isEqual(URL_DECODER.decode(signature), mac(id, purpose, binding, random));
    }

    private byte[] mac(String id, String purpose, String binding, String random) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(keys.get(id));
            // 各字段来自固定用途、规范 UUID、十进制时间或 Base64URL，均无法包含换行分隔符。
            return mac.doFinal(String.join("\n", VERSION, id, purpose, binding, random)
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("无法初始化 CSRF 签名", failure);
        }
    }

    private static void addKey(Map<String, SecretKeySpec> configured, String id, String encoded) {
        if (!id.matches("[A-Za-z0-9_-]{1,32}") || configured.containsKey(id)) {
            throw new IllegalArgumentException("CSRF 密钥标识无效或重复");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("CSRF 密钥必须使用 Base64 编码");
        }
        if (bytes.length < 32) throw new IllegalArgumentException("CSRF 密钥解码后至少需要 32 字节");
        configured.put(id, new SecretKeySpec(bytes, HMAC));
    }

    private static String[] fields(String token, int size) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) return null;
        String[] fields = token.split("\\.", -1);
        return fields.length == size && VERSION.equals(fields[0]) ? fields : null;
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private static boolean validRandom(String value) {
        return value.matches("[A-Za-z0-9_-]{43}")
                && URL_ENCODER.encodeToString(URL_DECODER.decode(value)).equals(value);
    }

    private record PreSession(String id, long deadline) {
        String binding() { return id + "." + deadline; }
    }

    record AnonymousCredentials(String preSession, String token, Instant expiresAt) {
        @Override
        public String toString() { return "AnonymousCredentials[redacted]"; }
    }
}
