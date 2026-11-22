package com.liveflow.api.auth;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.infrastructure.auth.AuthSessionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

/** 用本地 Compose 中的 MySQL 和 Redis 验证经过真实 HTTP 过滤链的认证契约。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LIVEFLOW_AUTH_INTEGRATION", matches = "true")
class AuthIntegrationTest {

    private static final byte[] KEY = new SecureRandom().generateSeed(32);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("liveflow.auth.signing-key", () -> Base64.getEncoder().encodeToString(KEY));
        registry.add("liveflow.auth.secure-cookie", () -> false);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JsonMapper json;
    @MockitoSpyBean AuthSessionRepository sessions;
    @LocalServerPort int port;

    String username;
    String password;
    long userId;

    @BeforeEach
    void createAccount() {
        username = "auth_" + UUID.randomUUID().toString().substring(0, 12).replace("-", "");
        password = UUID.randomUUID().toString();
        String hash = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(password);
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", username, hash);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void removeAccount() {
        redis.delete("liveflow:auth:session:{" + userId + "}");
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void csrfCookieAndHttpContract() throws Exception {
        var browser = new Browser();
        failure(browser.send("GET", "me", null, null, false), 401, "AUTH_REQUIRED");
        failure(browser.send("POST", "login", credentials(password), null, false), 403, "AUTH_CSRF_INVALID");
        assertThat(browser.send("GET", "csrf", null, null, false).statusCode()).isEqualTo(204);
        failure(browser.send("POST", "login", "{}", null, true), 400, "INVALID_REQUEST");
        failure(browser.send("POST", "login", credentials("wrong"), null, true), 401, "AUTH_INVALID_CREDENTIALS");

        var response = browser.send("POST", "login", credentials(password), null, true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith("lf_refresh=").contains("HttpOnly", "SameSite=Lax", "Path=/api/v1/auth"));
        JsonNode token = json.readTree(response.body());
        assertThat(token.get("expiresIn").asInt()).isEqualTo(900);
        var me = browser.send("GET", "me", null, access(token), false);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).get("userId").asString()).isEqualTo(Long.toString(userId));

        String session = token.get("sessionId").asString();
        browser.csrf();
        failure(browser.send("GET", "me", null, expiredToken(session), false), 401, "AUTH_ACCESS_EXPIRED");
        failure(browser.send("GET", "me", null, "invalid", false), 401, "AUTH_ACCESS_INVALID");

        var refresh = browser.send("POST", "refresh", null, null, true);
        assertThat(refresh.statusCode()).isEqualTo(200);
        JsonNode renewed = json.readTree(refresh.body());
        assertThat(renewed.get("sessionId")).isEqualTo(token.get("sessionId"));
        assertThat(renewed.get("sessionExpiresAt")).isEqualTo(token.get("sessionExpiresAt"));

        var logout = browser.send("POST", "logout", logoutBody(session), null, true);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(browser.cookies).doesNotContainKey("lf_refresh");
        failure(browser.send("GET", "me", null, access(renewed), false), 401, "AUTH_SESSION_INACTIVE");
    }

    @Test
    void newLoginAndStaleExitKeepNewSession() throws Exception {
        var oldDevice = new Browser();
        JsonNode oldToken = oldDevice.login();
        var newDevice = new Browser();
        JsonNode newToken = newDevice.login();
        String oldSession = oldToken.get("sessionId").asString();
        failure(oldDevice.send("GET", "me", null, access(oldToken), false), 401, "AUTH_SESSION_REPLACED");
        failure(oldDevice.send("POST", "refresh", null, null, true), 401, "AUTH_REFRESH_INVALID");
        failure(oldDevice.send("POST", "logout", logoutBody(oldSession), null, true), 401, "AUTH_REFRESH_INVALID");
        String newCookie = newDevice.cookies.get("lf_refresh");
        failure(newDevice.send("POST", "logout", logoutBody(oldSession), null, true), 409, "AUTH_SESSION_CHANGED");
        assertThat(newDevice.cookies.get("lf_refresh")).isEqualTo(newCookie);
        assertThat(newDevice.send("GET", "me", null, access(newToken), false).statusCode()).isEqualTo(200);
    }

    @Test
    void concurrentRefreshAndConsumedTokenLogout() throws Exception {
        var browser = new Browser();
        JsonNode initial = browser.login();
        var sameCookie = new Browser();
        sameCookie.cookies.putAll(browser.cookies);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.<HttpResponse<String>>invokeAll(List.of(
                    () -> browser.send("POST", "refresh", null, null, true),
                    () -> sameCookie.send("POST", "refresh", null, null, true)));
            var first = results.get(0).get();
            var second = results.get(1).get();
            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 401);
            failure(first.statusCode() == 401 ? first : second, 401, "AUTH_REFRESH_REUSED");
            failure(browser.send("GET", "me", null, access(initial), false), 401, "AUTH_SESSION_INACTIVE");
        }
        JsonNode login = browser.login();
        var priorRefresh = new Browser();
        priorRefresh.cookies.putAll(browser.cookies);
        assertThat(browser.send("POST", "refresh", null, null, true).statusCode()).isEqualTo(200);
        var revoked = priorRefresh.send("POST", "logout", logoutBody(login.get("sessionId").asString()), null, true);
        assertThat(revoked.statusCode()).isEqualTo(204);
        failure(browser.send("GET", "me", null, access(login), false), 401, "AUTH_SESSION_INACTIVE");
    }

    @Test
    void unavailableSessionDependencyMapsTo503() throws Exception {
        var browser = new Browser();
        JsonNode login = browser.login();
        doThrow(new AuthFailure(AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE)).when(sessions)
                .requireCurrent(userId, UUID.fromString(login.get("sessionId").asString()));
        failure(browser.send("GET", "me", null, access(login), false), 503, "AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void rateLimitedLoginKeepsCurrentSession() throws Exception {
        var browser = new Browser();
        String access = access(browser.login());
        for (int attempt = 0; attempt < 9; attempt++) {
            failure(browser.send("POST", "login", credentials("wrong"), null, true), 401,
                    "AUTH_INVALID_CREDENTIALS");
        }
        failure(browser.send("POST", "login", credentials(password), null, true), 429, "AUTH_RATE_LIMITED");
        assertThat(browser.send("GET", "me", null, access, false).statusCode()).isEqualTo(200);
    }

    private String credentials(String supplied) {
        return json.writeValueAsString(Map.of("username", username.toUpperCase(java.util.Locale.ROOT), "password", supplied));
    }

    private String logoutBody(String sessionId) {
        return json.writeValueAsString(Map.of("sessionId", sessionId));
    }

    private void failure(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    private static String access(JsonNode body) {
        return body.get("accessToken").asString();
    }

    private String expiredToken(String sessionId) {
        Instant now = Instant.now();
        var encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256")).build();
        var claims = JwtClaimsSet.builder().issuer("liveflow").audience(List.of("liveflow-api"))
                .subject(Long.toString(userId)).claim("sid", sessionId)
                .issuedAt(now.minusSeconds(20)).expiresAt(now.minusSeconds(5)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private final class Browser {
        final Map<String, String> cookies = new HashMap<>();

        JsonNode login() throws Exception {
            csrf();
            var response = send("POST", "login", credentials(password), null, true);
            assertThat(response.statusCode()).isEqualTo(200);
            csrf();
            return json.readTree(response.body());
        }

        void csrf() throws Exception {
            assertThat(send("GET", "csrf", null, null, false).statusCode()).isEqualTo(204);
            assertThat(cookies).containsKey("XSRF-TOKEN");
        }

        HttpResponse<String> send(String method, String path, String body, String bearer, boolean csrf)
                throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/" + path));
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ", cookies.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).toList()));
            }
            if (csrf) {
                request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            if (bearer != null) {
                request.header("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            var response = HTTP.send(request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (String cookie : response.headers().allValues("Set-Cookie")) {
                String pair = cookie.split(";", 2)[0];
                String[] parts = pair.split("=", 2);
                if (parts[1].isEmpty() || cookie.contains("Max-Age=0")) {
                    cookies.remove(parts[0]);
                } else {
                    cookies.put(parts[0], parts[1]);
                }
            }
            return response;
        }
    }
}
