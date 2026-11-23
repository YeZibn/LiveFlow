package com.liveflow.core.auth.service;

import com.liveflow.core.auth.AuthFailure;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import static com.liveflow.core.auth.AuthFailure.Code.*;

/** 按认证契约签发、验证 HS256 Access Token。 */
@Service
public class AccessTokenService implements JwtDecoder {

    private static final String ISSUER = "liveflow";
    private static final String AUDIENCE = "liveflow-api";
    private final NimbusJwtDecoder decoder;
    private final NimbusJwtEncoder encoder;

    /**
     * 用相同的 HS256 密钥初始化签发器和解码器，并安装项目的声明校验。
     * @param signingKey 由部署注入的 Base64 密钥，解码后至少 32 字节
     * @throws IllegalArgumentException 密钥编码无效或长度不足
     */
    public AccessTokenService(@Value("${liveflow.auth.signing-key}") String signingKey) {
        byte[] key = Base64.getDecoder().decode(signingKey);
        if (key.length < 32) {
            throw new IllegalArgumentException("LIVEFLOW_JWT_SIGNING_KEY 必须为至少 32 字节的 Base64 随机密钥");
        }
        var secretKey = new SecretKeySpec(key, "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(secretKey).build();
        decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(AccessTokenService::validateClaims);
    }

    /**
     * 签发凭证，有效期以会话截止和 15 分钟中较早者为准。
     * @param userId 认证后的账号 ID
     * @param sessionId 当前会话 ID
     * @param sessionExpiresAt 会话的绝对截止时间
     * @return 签发后的 JWT，包含有效期和签发时刻
     * @throws AuthFailure 会话剩余有效期不足以签发有效凭证
     * @throws org.springframework.security.oauth2.jwt.JwtEncodingException JWT 签发失败
     */
    public Jwt issue(long userId, UUID sessionId, Instant sessionExpiresAt) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // JWT 使用秒级时间，向下取整确保凭证不会超过 Redis 会话的截止时间。
        Instant end = sessionExpiresAt.truncatedTo(ChronoUnit.SECONDS);
        Instant limit = now.plusSeconds(900);
        Instant expiresAt = end.isBefore(limit) ? end : limit;
        if (!expiresAt.isAfter(now)) {
            throw new AuthFailure(AUTH_REFRESH_INVALID);
        }
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(AUDIENCE))
                .subject(Long.toString(userId)).claim("sid", sessionId.toString())
                .issuedAt(now).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
    }

    /**
     * 校验签名和认证声明。会话校验由安全过滤链完成。
     * @param token HTTP 请求所带的 Access Token
     * @return 已校验的 JWT
     * @throws org.springframework.security.oauth2.jwt.JwtException 凭证无法解析或校验失败
     */
    @Override
    public Jwt decode(String token) {
        return decoder.decode(token);
    }

    /**
     * 检查 JWT 身份声明和时效。签名已由解码器校验，Redis 会话校验随后执行。
     * @param jwt 已解码并通过签名确认的 JWT
     * @return 校验成功，或区分声明无效和单纯到期的结果
     */
    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        // 先校验身份声明和时效范围，只有其余声明有效的过期凭证才触发前端续期。
        try {
            if (!ISSUER.equals(jwt.getClaimAsString("iss")) || !List.of(AUDIENCE).equals(jwt.getAudience())
                    || Long.parseLong(jwt.getSubject()) <= 0
                    || !UUID.fromString(jwt.getClaimAsString("sid")).toString().equals(jwt.getClaimAsString("sid"))
                    || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(900))
                    || jwt.getIssuedAt().isAfter(Instant.now())) {
                return invalid(AUTH_ACCESS_INVALID);
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            return invalid(AUTH_ACCESS_INVALID);
        }
        return jwt.getExpiresAt().isAfter(Instant.now())
                ? OAuth2TokenValidatorResult.success() : invalid(AUTH_ACCESS_EXPIRED);
    }

    private static OAuth2TokenValidatorResult invalid(AuthFailure.Code code) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code.name()));
    }
}
