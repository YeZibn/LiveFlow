package com.liveflow.api.auth;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.room.mapper.RoomMapper;
import com.liveflow.infrastructure.auth.AuthSessionRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** 用本地 Compose 中的 MySQL 和 Redis 验证经过真实 HTTP 过滤链的认证契约。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LIVEFLOW_AUTH_INTEGRATION", matches = "true")
class AuthIntegrationTest {

    private static final String REGISTRATION_SOURCE = "liveflow:auth:registration:source:127.0.0.1";
    private static final String REGISTRATION_PASSWORD = "Abcdefg1!_";
    private static final String PROFILE_PATH = "/api/v1/users/me/profile";
    private static final String ROOM_CREATE_PATH = "/api/v1/rooms";
    private static final String ROOM_READ_PATH = "/api/v1/users/me/room";
    private static final byte[] KEY = new SecureRandom().generateSeed(32);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("liveflow.auth.signing-key", () -> Base64.getEncoder().encodeToString(KEY));
        registry.add("liveflow.auth.secure-cookie", () -> false);
        registry.add("spring.data.redis.database", () -> 15);
        registry.add("liveflow.auth.registration-limit.max-attempts", () -> 10);
        registry.add("liveflow.auth.registration-limit.window-seconds", () -> 600);
        registry.add("liveflow.room-messages.auth-seconds", () -> 2);
        registry.add("liveflow.room-messages.recheck-seconds", () -> 1);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired JsonMapper json;
    @MockitoSpyBean AuthSessionRepository sessions;
    @MockitoSpyBean UserProfileService profiles;
    @MockitoSpyBean RoomMapper rooms;
    @LocalServerPort int port;

    String username;
    String password;
    long userId;
    final List<String> registeredNames = new ArrayList<>();

    @BeforeEach
    void createAccount() {
        username = "auth_" + UUID.randomUUID().toString().substring(0, 12).replace("-", "");
        password = UUID.randomUUID().toString();
        String hash = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(password);
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", username, hash);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        redis.delete(REGISTRATION_SOURCE);
    }

    @AfterEach
    void removeAccount() {
        redis.delete("liveflow:auth:session:{" + userId + "}");
        jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        for (String name : registeredNames) {
            for (Long id : jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, name)) {
                redis.delete("liveflow:auth:session:{" + id + "}");
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }
        redis.delete(REGISTRATION_SOURCE);
    }

    @Test
    void registerValidPasswordsAndLoginWithExactUsername() throws Exception {
        String mixed = registrationName("New_");
        String lower = mixed.toLowerCase(Locale.ROOT);
        registeredNames.add(lower);
        String longestPassword = "Aa1!" + "x".repeat(21);
        var upper = new Browser();
        upper.csrf();
        var first = registered(upper.send("POST", "register", credentials(mixed, REGISTRATION_PASSWORD), null, true));
        assertThat(first.get("username").asString()).isEqualTo(mixed);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, mixed);
        assertThat(hash).startsWith("$argon2id$");

        var lowerBrowser = new Browser();
        lowerBrowser.csrf();
        var second = registered(lowerBrowser.send("POST", "register", credentials(lower, longestPassword), null, true));
        assertThat(second.get("userId")).isNotEqualTo(first.get("userId"));
        failure(upper.send("POST", "login", credentials(lower, REGISTRATION_PASSWORD), null, true),
                401, "AUTH_INVALID_CREDENTIALS");
        String firstAccess = access(upper.login(mixed, REGISTRATION_PASSWORD));
        String secondAccess = access(lowerBrowser.login(lower, longestPassword));
        assertThat(json.readTree(upper.send("GET", "me", null, firstAccess, false).body())).isEqualTo(first);
        assertThat(json.readTree(lowerBrowser.send("GET", "me", null, secondAccess, false).body())).isEqualTo(second);
    }

    @Test
    void invalidRegistrationAndMissingCsrfCannotCreateAccount() throws Exception {
        var browser = new Browser();
        String name = registrationName("Invalid_");
        String valid = credentials(name, REGISTRATION_PASSWORD);
        failure(browser.send("POST", "register", valid, null, false), 403, "AUTH_CSRF_INVALID");
        browser.csrf();
        for (String invalid : List.of("Abcdef1!_", "Aa1!" + "x".repeat(22), "abcdefg1!_", "ABCDEFG1!_",
                "Abcdefgh!_", "Abcdefg123", "Abcdefg1! ", "Abcdefg1!中")) {
            failure(browser.send("POST", "register", credentials(name, invalid), null, true), 400, "INVALID_REQUEST");
        }
        for (String body : List.of("{}", "null", "[]", "{",
                json.writeValueAsString(Map.of("username", name, "password", 12345)),
                json.writeValueAsString(Map.of("username", name, "password", REGISTRATION_PASSWORD,
                        "confirmation", REGISTRATION_PASSWORD)))) {
            failure(browser.send("POST", "register", body, null, true), 400, "INVALID_REQUEST");
        }
        failure(browser.send("POST", "register", credentials(" " + name, REGISTRATION_PASSWORD), null, true),
                400, "INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, name)).isZero();
        assertThat(redis.hasKey(REGISTRATION_SOURCE)).isFalse();
    }

    @Test
    void concurrentDuplicateRegistrationCreatesOneAccount() throws Exception {
        String name = registrationName("Race_");
        String body = credentials(name, REGISTRATION_PASSWORD);
        var first = new Browser();
        var second = new Browser();
        first.csrf();
        second.csrf();
        var start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = executor.<HttpResponse<String>>invokeAll(List.of(
                    () -> {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return first.send("POST", "register", body, null, true);
                    },
                    () -> {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return second.send("POST", "register", body, null, true);
                    }));
            var results = List.of(attempts.get(0).get(), attempts.get(1).get());
            assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
            failure(results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                    409, "AUTH_USERNAME_ALREADY_EXISTS");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, name)).isOne();
    }

    @Test
    void registrationRateLimitReturnsRetryAfterAndDoesNotLimitLogin() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String body = credentials(username, REGISTRATION_PASSWORD);
        for (int attempt = 0; attempt < 10; attempt++) {
            failure(browser.send("POST", "register", body, null, true), 409, "AUTH_USERNAME_ALREADY_EXISTS");
        }
        var denied = browser.send("POST", "register", body, null, true);
        failure(denied, 429, "AUTH_RATE_LIMITED");
        assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 600L);
        assertThat(access(browser.login())).isNotEmpty();
    }

    @Test
    void unavailableRegistrationLimiterCannotCreateAccount() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String name = registrationName("Unavailable_");
        doThrow(new AuthFailure(AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE)).when(sessions)
                .checkRegistrationLimit("127.0.0.1");
        failure(browser.send("POST", "register", credentials(name, REGISTRATION_PASSWORD), null, true),
                503, "AUTH_SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, name)).isZero();
    }

    private String registrationName(String prefix) {
        String name = prefix + username;
        registeredNames.add(name);
        return name;
    }

    private JsonNode registered(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("lf_refresh="));
        return json.readTree(response.body());
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

    @Test
    void profileDefaultsSaveAndReset() throws Exception {
        var browser = new Browser();
        failure(browser.sendPath("GET", PROFILE_PATH, null, null, false), 401, "AUTH_REQUIRED");
        String bearer = access(browser.login());

        var defaults = profile(browser.sendPath("GET", PROFILE_PATH, null, bearer, false));
        assertThat(defaults.get("userId").asString()).isEqualTo(Long.toString(userId));
        assertThat(defaults.get("username").asString()).isEqualTo(username);
        assertThat(defaults.get("displayName").asString()).isEqualTo(username);
        assertThat(defaults.get("avatarKey").asString()).isEqualTo("default");
        assertThat(profileCount(userId)).isZero();

        String nickname = "叶".repeat(23) + "🌊";
        var saved = profile(browser.sendPath("PUT", PROFILE_PATH, profileBody(" " + nickname + " ", "blue",
                "  第一行\r\n第二行  "), bearer, true));
        assertThat(saved.get("displayName").asString()).isEqualTo(nickname);
        assertThat(saved.get("bio").asString()).isEqualTo("第一行\n第二行");
        assertThat(profile(browser.sendPath("GET", PROFILE_PATH, null, bearer, false))).isEqualTo(saved);

        var created = jdbc.queryForObject("SELECT created_at FROM user_profiles WHERE user_id = ?", String.class, userId);
        var reset = profile(browser.sendPath("PUT", PROFILE_PATH, profileBody("", "default", ""), bearer, true));
        assertThat(reset).isEqualTo(defaults);
        assertThat(profileCount(userId)).isOne();
        assertThat(jdbc.queryForObject("SELECT created_at FROM user_profiles WHERE user_id = ?", String.class, userId))
                .isEqualTo(created);
    }

    @Test
    void invalidProfileBodyCsrfAndReplacedSessionCannotWrite() throws Exception {
        var oldBrowser = new Browser();
        String oldBearer = access(oldBrowser.login());
        String valid = profileBody("有效昵称", "blue", "");
        failure(oldBrowser.sendPath("PUT", PROFILE_PATH, valid, oldBearer, false), 403, "AUTH_CSRF_INVALID");
        for (String body : List.of("{", "null", "[]", "{}", """
                {"nickname":null,"avatarKey":"default","bio":""}
                """, """
                {"nickname":"改别人","avatarKey":"default","bio":"","userId":"1"}
                """, """
                {"nickname":123,"avatarKey":"default","bio":""}
                """, profileBody("一", "unknown", "字".repeat(161)))) {
            failure(oldBrowser.sendPath("PUT", PROFILE_PATH, body, oldBearer, true), 400, "INVALID_REQUEST");
        }
        assertThat(profileCount(userId)).isZero();

        var currentBrowser = new Browser();
        String currentBearer = access(currentBrowser.login());
        var saved = profile(currentBrowser.sendPath("PUT", PROFILE_PATH, valid, currentBearer, true));
        failure(oldBrowser.sendPath("PUT", PROFILE_PATH, profileBody("旧会话", "green", ""), oldBearer, true),
                401, "AUTH_SESSION_REPLACED");
        assertThat(profile(currentBrowser.sendPath("GET", PROFILE_PATH, null, currentBearer, false))).isEqualTo(saved);
    }

    @Test
    void concurrentFirstProfileSavesStayCompleteAndIsolated() throws Exception {
        var firstBrowser = new Browser();
        String bearer = access(firstBrowser.login());
        var secondBrowser = new Browser();
        secondBrowser.csrf();
        String otherName = username + "_other";
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?",
                otherName, userId);
        long otherId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, otherName);
        try {
            var otherBrowser = new Browser();
            String otherBearer = access(otherBrowser.login(otherName));
            var otherSaved = profile(otherBrowser.sendPath("PUT", PROFILE_PATH, profileBody("另一账号", "orange", ""),
                    otherBearer, true));
            var start = new CyclicBarrier(2);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var writes = executor.<HttpResponse<String>>invokeAll(List.of(
                        () -> {
                            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            return firstBrowser.sendPath("PUT", PROFILE_PATH, profileBody("第一份", "blue", "甲"),
                                    bearer, true);
                        },
                        () -> {
                            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            return secondBrowser.sendPath("PUT", PROFILE_PATH, profileBody("第二份", "green", "乙"),
                                    bearer, true);
                        }));
                var first = profile(writes.get(0).get());
                var second = profile(writes.get(1).get());
                assertThat(first.get("nickname").asString()).isEqualTo("第一份");
                assertThat(second.get("nickname").asString()).isEqualTo("第二份");
                assertThat(profile(firstBrowser.sendPath("GET", PROFILE_PATH, null, bearer, false))).isIn(first, second);
                assertThat(profileCount(userId)).isOne();
            }
            assertThat(profile(otherBrowser.sendPath("GET", PROFILE_PATH, null, otherBearer, false))).isEqualTo(otherSaved);
        } finally {
            redis.delete("liveflow:auth:session:{" + otherId + "}");
            jdbc.update("DELETE FROM users WHERE id = ?", otherId);
        }
    }

    @Test
    void unavailableProfileStorageMapsTo503() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        doThrow(new DataAccessResourceFailureException("test")).when(profiles).read(userId);
        failure(browser.sendPath("GET", PROFILE_PATH, null, bearer, false), 503, "PROFILE_SERVICE_UNAVAILABLE");
    }

    @Test
    void roomValidatesInputAndPersistsNormalizedTitle() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        assertThat(roomResponse(browser.sendPath("GET", ROOM_READ_PATH, null, bearer, false), 200)
                .get("room").isNull()).isTrue();
        for (String body : List.of("{}", "null", "[]", "{", "{\"title\":null}", "{\"title\":42}",
                "{\"title\":\"room\",\"ownerUserId\":42}", "{\"title\":\"a\",\"title\":\"b\"}",
                "{\"title\":\"a\"}{}", roomBody("　 "), roomBody("\n房间"), roomBody("房间\u0080"),
                roomBody("房\u2028间"), roomBody("🎥".repeat(41)))) {
            failure(browser.sendPath("POST", ROOM_CREATE_PATH, body, bearer, true), 400, "INVALID_REQUEST");
        }
        assertThat(roomCount(userId)).isZero();
        String title = "🎥".repeat(40);
        JsonNode created = roomResponse(browser.sendPath("POST", ROOM_CREATE_PATH,
                roomBody("　" + title + " "), bearer, true), 201);
        assertThat(created.get("title").asString()).isEqualTo(title);
        assertThat(created.get("roomId").isString()).isTrue();
        String roomId = created.get("roomId").asString();
        assertThat(UUID.fromString(roomId).version()).isEqualTo(4);
        assertThat(roomId).isEqualTo(UUID.fromString(roomId).toString());
        var details = roomResponse(browser.sendPath("GET", ROOM_CREATE_PATH + "/" + roomId, null, bearer, false), 200);
        assertThat(details).isEqualTo(json.readTree(json.writeValueAsString(
                Map.of("roomId", roomId, "title", title, "ownerName", username))));
        assertThat(created.get("createdAt").asString()).matches(".+\\.\\d{3}Z");
        assertThat(roomResponse(browser.sendPath("GET", ROOM_READ_PATH, null, bearer, false), 200).get("room"))
                .isEqualTo(created);
        assertThat(roomCount(userId)).isOne();
    }

    @Test
    void roomRequiresCurrentSessionAndCsrf() throws Exception {
        var browser = new Browser();
        String oldBearer = access(browser.login());
        failure(browser.sendPath("GET", ROOM_READ_PATH, null, null, false), 401, "AUTH_REQUIRED");
        failure(browser.sendPath("POST", ROOM_CREATE_PATH, roomBody("间"), null, true), 401, "AUTH_REQUIRED");
        failure(browser.sendPath("POST", ROOM_CREATE_PATH, roomBody("间"), oldBearer, false), 403, "AUTH_CSRF_INVALID");
        var current = new Browser();
        String currentBearer = access(current.login());
        failure(browser.sendPath("POST", ROOM_CREATE_PATH, roomBody("间"), oldBearer, true),
                401, "AUTH_SESSION_REPLACED");
        assertThat(roomCount(userId)).isZero();
        assertThat(roomResponse(current.sendPath("POST", ROOM_CREATE_PATH, roomBody("间"), currentBearer, true), 201)
                .get("title").asString()).isEqualTo("间");
    }

    @Test
    void roomConcurrentCreatesKeepOneWinnerAndAllowSameTitleForOtherAccount() throws Exception {
        var first = new Browser();
        String bearer = access(first.login());
        var second = new Browser();
        second.csrf();
        String otherName = username + "_room";
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?",
                otherName, userId);
        long otherId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, otherName);
        try {
            var start = new CyclicBarrier(2);
            JsonNode winner;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var attempts = executor.<HttpResponse<String>>invokeAll(List.of(
                        () -> {
                            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            return first.sendPath("POST", ROOM_CREATE_PATH, roomBody("第一个名称"), bearer, true);
                        },
                        () -> {
                            start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            return second.sendPath("POST", ROOM_CREATE_PATH, roomBody("第二个名称"), bearer, true);
                        }));
                var left = attempts.get(0).get();
                var right = attempts.get(1).get();
                assertThat(List.of(left.statusCode(), right.statusCode())).containsExactlyInAnyOrder(201, 409);
                boolean leftWon = left.statusCode() == 201;
                winner = roomResponse(leftWon ? left : right, 201);
                assertThat(winner.get("title").asString()).isEqualTo(leftWon ? "第一个名称" : "第二个名称");
                failure(leftWon ? right : left, 409, "ROOM_ALREADY_EXISTS");
            }
            assertThat(roomCount(userId)).isOne();
            var other = new Browser();
            String otherBearer = access(other.login(otherName));
            assertThat(roomResponse(other.sendPath("GET", ROOM_READ_PATH, null, otherBearer, false), 200)
                    .get("room").isNull()).isTrue();
            // 另一个账号可读取分享的房间，本人查询仍按各自归属。
            var shared = roomResponse(other.sendPath("GET", ROOM_CREATE_PATH + "/" + winner.get("roomId").asString(),
                    null, otherBearer, false), 200);
            assertThat(shared.get("roomId")).isEqualTo(winner.get("roomId"));
            JsonNode sameTitle = roomResponse(other.sendPath("POST", ROOM_CREATE_PATH,
                    roomBody(winner.get("title").asString()), otherBearer, true), 201);
            assertThat(sameTitle.get("roomId")).isNotEqualTo(winner.get("roomId"));
            assertThat(roomResponse(first.sendPath("GET", ROOM_READ_PATH, null, bearer, false), 200).get("room"))
                    .isEqualTo(winner);
            assertThat(roomResponse(other.sendPath("GET", ROOM_READ_PATH, null, otherBearer, false), 200).get("room"))
                    .isEqualTo(sameTitle);
        } finally {
            redis.delete("liveflow:auth:session:{" + otherId + "}");
            jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", otherId);
            jdbc.update("DELETE FROM users WHERE id = ?", otherId);
        }
    }

    @Test
    void roomStorageFailureMapsTo503AndFailedReadBackRollsBackInsert() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        doThrow(new DataAccessResourceFailureException("test")).when(rooms).find(userId);
        failure(browser.sendPath("GET", ROOM_READ_PATH, null, bearer, false), 503, "ROOM_SERVICE_UNAVAILABLE");
        failure(browser.sendPath("POST", ROOM_CREATE_PATH, roomBody("房间"), bearer, true),
                503, "ROOM_SERVICE_UNAVAILABLE");
        assertThat(roomCount(userId)).isZero();
    }

    @Test
    void roomDetailsValidateUuidAndMapMissingAndStorageFailure() throws Exception {
        var browser = new Browser();
        String roomId = "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c";
        failure(browser.sendPath("GET", ROOM_CREATE_PATH + "/" + roomId, null, null, false),
                401, "AUTH_REQUIRED");
        String bearer = access(browser.login());
        for (String invalid : List.of("73", roomId.toUpperCase(Locale.ROOT), roomId.replace("-4f2a-", "-1f2a-"))) {
            failure(browser.sendPath("GET", ROOM_CREATE_PATH + "/" + invalid, null, bearer, false),
                    400, "INVALID_REQUEST");
        }
        failure(browser.sendPath("GET", ROOM_CREATE_PATH + "/" + roomId, null, bearer, false),
                404, "ROOM_NOT_FOUND");
        doThrow(new DataAccessResourceFailureException("test")).when(rooms).findDetails(roomId);
        failure(browser.sendPath("GET", ROOM_CREATE_PATH + "/" + roomId, null, bearer, false),
                503, "ROOM_SERVICE_UNAVAILABLE");
    }

    @Test
    void roomDirectoryUsesLiteralSearchAndStablePageBoundaries() throws Exception {
        var browser = new Browser();
        failure(browser.sendPath("GET", ROOM_CREATE_PATH, null, null, false), 401, "AUTH_REQUIRED");
        String bearer = access(browser.login());
        var others = new ArrayList<Long>();
        String prefix = username + " Live 字";
        String first = "7ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c";
        String second = "6ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c";
        String newer = "8ea8af2a-721f-4f2a-8ff5-03e2fd31ee3c";
        String timestamp = "2026-11-23 08:30:00.123";
        String insert = "INSERT INTO rooms (room_uuid, owner_user_id, title, created_at) VALUES (?, ?, ?, ?)";
        try {
            String otherName = registrationName("Viewer_");
            long otherId = directoryAccount(otherName, others);
            roomResponse(browser.sendPath("PUT", PROFILE_PATH, profileBody("目录房主", "default", ""),
                    bearer, true), 200);
            jdbc.update(insert, first, userId, prefix + " 50%_!", timestamp);
            jdbc.update(insert, second, otherId, prefix + " 50abc", timestamp);

            String query = ROOM_CREATE_PATH + "?limit=1&q="
                    + URLEncoder.encode("　" + prefix.toLowerCase(Locale.ROOT) + " ", StandardCharsets.UTF_8);
            JsonNode firstPage = roomResponse(browser.sendPath("GET", query, null, bearer, false), 200);
            JsonNode item = firstPage.get("items").get(0);
            assertThat(item.get("roomId").asString()).isEqualTo(first);
            assertThat(item.get("ownerName").asString()).isEqualTo("目录房主");
            assertThat(item.get("isMine").asBoolean()).isTrue();
            assertThat(item.get("createdAt").asString()).isEqualTo("2026-11-23T08:30:00.123Z");
            assertThat(item.has("userId")).isFalse();
            String cursor = firstPage.get("nextCursor").asString();
            assertThat(cursor).isNotBlank();

            long newerId = directoryAccount(registrationName("New_"), others);
            jdbc.update(insert, newer, newerId, prefix + " 新房间", "2026-11-23 08:30:00.124");
            JsonNode secondPage = roomResponse(browser.sendPath("GET", query + "&cursor=" + cursor,
                    null, bearer, false), 200);
            JsonNode secondItem = secondPage.get("items").get(0);
            assertThat(secondItem.get("roomId").asString()).isEqualTo(second);
            assertThat(secondItem.get("isMine").asBoolean()).isFalse();
            assertThat(secondItem.get("ownerName").asString()).isEqualTo(otherName);
            assertThat(secondPage.get("nextCursor").isNull()).isTrue();
            assertThat(roomResponse(browser.sendPath("GET", query, null, bearer, false), 200)
                    .get("items").get(0).get("roomId").asString()).isEqualTo(newer);

            JsonNode literal = roomResponse(browser.sendPath("GET", ROOM_CREATE_PATH + "?q=50%25_%21",
                    null, bearer, false), 200).get("items");
            assertThat(literal).anyMatch(row -> first.equals(row.get("roomId").asString()));
            assertThat(literal).noneMatch(row -> second.equals(row.get("roomId").asString()));
            JsonNode single = roomResponse(browser.sendPath("GET", ROOM_CREATE_PATH + "?q=%E5%AD%97",
                    null, bearer, false), 200).get("items");
            assertThat(single).anyMatch(row -> first.equals(row.get("roomId").asString()));
            roomResponse(browser.sendPath("GET", ROOM_CREATE_PATH, null, bearer, false), 200);

            for (String invalid : List.of("q=a&q=b", "unused=1", "q=%0A", "q=" + "a".repeat(41),
                    "limit=", "limit=0", "limit=25", "cursor=", "cursor=bad", "q=other&cursor=" + cursor)) {
                failure(browser.sendPath("GET", ROOM_CREATE_PATH + "?" + invalid, null, bearer, false),
                        400, "INVALID_REQUEST");
            }
            doThrow(new DataAccessResourceFailureException("test")).when(rooms).list(userId, null, null, null, 13);
            failure(browser.sendPath("GET", ROOM_CREATE_PATH, null, bearer, false), 503, "ROOM_SERVICE_UNAVAILABLE");
        } finally {
            for (long id : others) {
                jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", id);
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }
    }

    private long directoryAccount(String name, List<Long> accounts) {
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?", name, userId);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
        accounts.add(id);
        return id;
    }

    @Test
    void uuidConflictRetriesOnceWithANewId() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String occupied = UUID.randomUUID().toString();
        String otherName = registrationName("Uuid_");
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?",
                otherName, userId);
        long otherId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, otherName);
        String insert = "INSERT INTO rooms (room_uuid, owner_user_id, title, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3))";
        jdbc.update(insert, occupied, otherId, "已有房间");
        try {
            var generated = new ArrayList<String>();
            doAnswer(invocation -> {
                String newId = invocation.getArgument(1);
                generated.add(newId);
                // 第一次让真实唯一索引拒绝写入，第二次使用服务生成的新值。
                return jdbc.update(insert, generated.size() == 1 ? occupied : newId, userId, invocation.getArgument(2));
            }).when(rooms).insert(eq(userId), anyString(), anyString());
            JsonNode created = roomResponse(browser.sendPath("POST", ROOM_CREATE_PATH,
                    roomBody("新房间"), bearer, true), 201);
            assertThat(generated).hasSize(2).doesNotHaveDuplicates();
            assertThat(created.get("roomId").asString()).isEqualTo(generated.getLast());
            assertThat(roomCount(userId)).isOne();
        } finally {
            jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", otherId);
        }
    }

    @Test
    void roomMessagesRejectUnauthorizedAndInvalidHttpRequests() throws Exception {
        var browser = new Browser();
        browser.csrf();
        String missing = UUID.randomUUID().toString();
        failure(browser.sendPath("POST", messagePath(missing, "ws-ticket"), null, null, true), 401, "AUTH_REQUIRED");
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        failure(browser.sendPath("POST", messagePath(room, "ws-ticket"), null, bearer, false), 403, "AUTH_CSRF_INVALID");
        failure(browser.sendPath("POST", messagePath(missing, "ws-ticket"), null, bearer, true), 404, "ROOM_NOT_FOUND");
        failure(browser.sendPath("POST", messagePath("73", "ws-ticket"), null, bearer, true), 400, "INVALID_REQUEST");
        String valid = messageBody(UUID.randomUUID().toString(), "你好");
        failure(browser.sendPath("POST", messagePath(room, "messages"), valid, bearer, false), 403, "AUTH_CSRF_INVALID");
        failure(browser.sendPath("POST", messagePath(missing, "messages"), valid, bearer, true), 404, "ROOM_NOT_FOUND");
        for (String invalid : List.of("{}", "{", "{\"clientMessageId\":\"bad\",\"content\":\"a\"}",
                messageBody(UUID.randomUUID().toString(), "\n你好"), messageBody(UUID.randomUUID().toString(), "🎥".repeat(101)))) {
            failure(browser.sendPath("POST", messagePath(room, "messages"), invalid, bearer, true), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void roomTicketIsSingleUseAndExpiredAndWrongOriginConnectionsCannotJoin() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        assertThrows(java.util.concurrent.ExecutionException.class, () ->
                HTTP.newWebSocketBuilder().header("Origin", "https://example.invalid")
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/rooms"), new SocketClient())
                        .get(3, TimeUnit.SECONDS));
        try (var idle = connectRoom()) {
            assertThat(idle.closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        }
        try (var malformed = connectRoom()) {
            malformed.socket.sendText("{\"type\":\"send\"}", true).join();
            assertThat(malformed.next().get("code").asString()).isEqualTo("INVALID_REQUEST");
        }

        String expired = ticket(browser, bearer, room);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(expired.getBytes(StandardCharsets.US_ASCII)));
        String key = "liveflow:rooms:ws-ticket:" + digest;
        assertThat(redis.getExpire(key)).isBetween(1L, 30L);
        redis.expire(key, Duration.ZERO);
        try (var socket = connectRoom()) {
            socket.authenticate(expired);
            assertThat(socket.next().get("code").asString()).isEqualTo("TICKET_INVALID");
        }

        String shared = ticket(browser, bearer, room);
        try (var first = connectRoom(); var second = connectRoom()) {
            var a = first.authenticate(shared);
            var b = second.authenticate(shared);
            CompletableFuture.allOf(a, b).get(3, TimeUnit.SECONDS);
            assertThat(List.of(first.next().get("type").asString(), second.next().get("type").asString()))
                    .containsExactlyInAnyOrder("ready", "error");
        }
    }

    @Test
    void concurrentRoomMessageRetriesBroadcastOnceAndStayInTheRoom() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        String otherName = registrationName("Msg_");
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?", otherName, userId);
        long otherId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, otherName);
        var viewer = new Browser();
        String viewerBearer = access(viewer.login(otherName));
        String otherRoom = createRoomFor(viewer, viewerBearer);
        try (var sender = readyRoom(browser, bearer, room);
                var receiver = readyRoom(viewer, viewerBearer, room);
                var isolated = readyRoom(viewer, viewerBearer, otherRoom);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var secondTab = new Browser();
            secondTab.csrf();
            String id = UUID.randomUUID().toString();
            String path = messagePath(room, "messages");
            var start = new CyclicBarrier(2);
            var submissions = executor.<HttpResponse<String>>invokeAll(List.of(
                    () -> {
                        start.await(3, TimeUnit.SECONDS);
                        return browser.sendPath("POST", path, messageBody(id, "　你好 "), bearer, true);
                    },
                    () -> {
                        start.await(3, TimeUnit.SECONDS);
                        return secondTab.sendPath("POST", path, messageBody(id, "你好"), bearer, true);
                    }));
            JsonNode receipt = roomResponse(submissions.getFirst().get(), 200);
            assertThat(roomResponse(submissions.getLast().get(), 200)).isEqualTo(receipt);
            JsonNode event = sender.next();
            assertThat(event.get("type").asString()).isEqualTo("message");
            assertThat(event.get("messageId")).isEqualTo(receipt.get("messageId"));
            assertThat(event.get("sentAt")).isEqualTo(receipt.get("sentAt"));
            assertThat(event.get("senderName").asString()).isEqualTo(username);
            assertThat(event.has("userId")).isFalse();
            assertThat(event.get("content").asString()).isEqualTo("你好");
            assertThat(receiver.next()).isEqualTo(event);
            failure(browser.sendPath("POST", path, messageBody(id, "已修改"), bearer, true), 409, "MESSAGE_ID_CONFLICT");
            assertThat(sender.frames.poll(250, TimeUnit.MILLISECONDS)).isNull();
            assertThat(receiver.frames.poll(250, TimeUnit.MILLISECONDS)).isNull();
            assertThat(isolated.frames.poll(250, TimeUnit.MILLISECONDS)).isNull();

            for (int i = 0; i < 4; i++) {
                roomResponse(secondTab.sendPath("POST", path, messageBody(UUID.randomUUID().toString(), "下一条"),
                        bearer, true), 200);
            }
            var limited = browser.sendPath("POST", messagePath(otherRoom, "messages"),
                    messageBody(UUID.randomUUID().toString(), "不同房间"), bearer, true);
            failure(limited, 429, "MESSAGE_RATE_LIMITED");
            assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(
                    value -> assertThat(Integer.parseInt(value)).isBetween(1, 10));
            assertThat(isolated.frames.poll(250, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", otherId);
        }
    }

    @Test
    void roomSocketClosesOnSessionReplacementAndFailedRecheck() throws Exception {
        var browser = new Browser();
        JsonNode login = browser.login();
        String bearer = access(login);
        String room = createRoomFor(browser, bearer);
        try (var old = readyRoom(browser, bearer, room)) {
            var next = new Browser();
            JsonNode replacement = next.login();
            String current = access(replacement);
            failure(browser.sendPath("POST", messagePath(room, "messages"),
                    messageBody(UUID.randomUUID().toString(), "旧会话"), bearer, true), 401, "AUTH_SESSION_REPLACED");
            assertThat(old.next().get("code").asString()).isEqualTo("AUTH_SESSION_REPLACED");
            assertThat(old.closeCode.get(4, TimeUnit.SECONDS)).isEqualTo(1008);
            try (var unavailable = readyRoom(next, current, room)) {
                UUID sessionId = UUID.fromString(replacement.get("sessionId").asString());
                doThrow(new AuthFailure(AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE))
                        .when(sessions).requireCurrent(userId, sessionId);
                assertThat(unavailable.next().get("code").asString()).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
                assertThat(unavailable.closeCode.get(4, TimeUnit.SECONDS)).isEqualTo(1011);
            }
        }
    }

    private static String messagePath(String roomId, String action) {
        return ROOM_CREATE_PATH + "/" + roomId + "/" + action;
    }

    private String messageBody(String id, String content) {
        return json.writeValueAsString(Map.of("clientMessageId", id, "content", content));
    }

    private String createRoomFor(Browser browser, String bearer) throws Exception {
        return roomResponse(browser.sendPath("POST", ROOM_CREATE_PATH, roomBody("测试房间"), bearer, true), 201)
                .get("roomId").asString();
    }

    private String ticket(Browser browser, String bearer, String roomId) throws Exception {
        return roomResponse(browser.sendPath("POST", messagePath(roomId, "ws-ticket"), null, bearer, true), 200)
                .get("ticket").asString();
    }

    private SocketClient readyRoom(Browser browser, String bearer, String roomId) throws Exception {
        String issued = ticket(browser, bearer, roomId);
        SocketClient socket = connectRoom();
        socket.authenticate(issued).get(3, TimeUnit.SECONDS);
        var ready = socket.next();
        assertThat(ready.get("type").asString()).isEqualTo("ready");
        assertThat(ready.get("roomId").asString()).isEqualTo(roomId);
        return socket;
    }

    private SocketClient connectRoom() throws Exception {
        var client = new SocketClient();
        client.socket = HTTP.newWebSocketBuilder().header("Origin", "http://127.0.0.1:" + port)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/rooms"), client).get(3, TimeUnit.SECONDS);
        return client;
    }

    private final class SocketClient implements WebSocket.Listener, AutoCloseable {
        final LinkedBlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;

        CompletableFuture<WebSocket> authenticate(String ticket) {
            return socket.sendText(json.writeValueAsString(Map.of("type", "auth", "ticket", ticket)), true);
        }

        JsonNode next() throws InterruptedException {
            var frame = frames.poll(4, TimeUnit.SECONDS);
            assertThat(frame).as("服务端 WebSocket 帧").isNotNull();
            return frame;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket source, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                frames.add(json.readTree(fragments.toString()));
                fragments.setLength(0);
            }
            source.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket source, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private String roomBody(String title) {
        return json.writeValueAsString(Map.of("title", title));
    }

    private int roomCount(long ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM rooms WHERE owner_user_id = ?", Integer.class, ownerId);
    }

    private JsonNode roomResponse(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return json.readTree(response.body());
    }

    private String profileBody(String nickname, String avatarKey, String bio) {
        return json.writeValueAsString(Map.of("nickname", nickname, "avatarKey", avatarKey, "bio", bio));
    }

    private int profileCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles WHERE user_id = ?", Integer.class, id);
    }

    private JsonNode profile(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return json.readTree(response.body());
    }

    private String credentials(String supplied) {
        return credentials(username, supplied);
    }

    private String credentials(String name, String supplied) {
        return json.writeValueAsString(Map.of("username", name, "password", supplied));
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
            return login(username);
        }

        JsonNode login(String name) throws Exception {
            return login(name, password);
        }

        JsonNode login(String name, String suppliedPassword) throws Exception {
            csrf();
            String body = credentials(name, suppliedPassword);
            var response = send("POST", "login", body, null, true);
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
            return sendPath(method, "/api/v1/auth/" + path, body, bearer, csrf);
        }

        HttpResponse<String> sendPath(String method, String path, String body, String bearer, boolean csrf)
                throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
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
