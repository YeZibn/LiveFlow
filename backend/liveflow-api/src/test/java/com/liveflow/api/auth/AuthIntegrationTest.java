package com.liveflow.api.auth;

import com.liveflow.api.room.message.RoomSocketHandler;
import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.room.mapper.RoomMapper;
import com.liveflow.core.room.message.RoomEvents;
import com.liveflow.core.room.message.RoomEvents.Accepted;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomEvents.Submission;
import com.liveflow.core.room.message.RoomHistoryService;
import com.liveflow.core.room.message.RoomHistoryService.HistoryRecord;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomMessagePorts.Admission;
import com.liveflow.infrastructure.message.RoomKafkaConsumerGroupRuntime;
import com.liveflow.infrastructure.message.RoomKafkaMaintenanceScheduler;
import com.liveflow.infrastructure.message.RoomKafkaProducer;
import com.liveflow.infrastructure.message.RoomKafkaStreamsRuntime;
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
import java.util.stream.IntStream;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
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
import org.springframework.kafka.core.KafkaAdmin;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** 用本地 Compose 中的 MySQL 和 Redis 验证经过真实 HTTP 过滤链的认证契约。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LIVEFLOW_AUTH_INTEGRATION", matches = "true")
class AuthIntegrationTest {

    private static final String LOGIN_SOURCE = "liveflow:auth:{login}:source:127.0.0.1";
    private static final String REGISTRATION_SOURCE = "liveflow:auth:registration:source:127.0.0.1";
    private static final String REGISTRATION_PASSWORD = "Abcdefg1!_";
    private static final String PROFILE_PATH = "/api/v1/users/me/profile";
    private static final String ROOM_CREATE_PATH = "/api/v1/rooms";
    private static final String ROOM_READ_PATH = "/api/v1/users/me/room";
    private static final byte[] KEY = new SecureRandom().generateSeed(32);
    private static final byte[] CSRF_KEY = new SecureRandom().generateSeed(32);
    private static final String PRE_SESSION = "__Host-lf_pre";
    private static final String PRE_CSRF = "__Host-lf_csrf_pre";
    private static final String SESSION_CSRF = "__Host-lf_csrf";
    private static final String PUBLIC_ORIGIN = "https://liveflow.example";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String MESSAGE_TEST_NAMESPACE = "v2-it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String STREAMS_TEST_DIRECTORY = java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"), "liveflow-room-it-" + UUID.randomUUID()).toString();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("liveflow.auth.signing-key", () -> Base64.getEncoder().encodeToString(KEY));
        registry.add("liveflow.auth.csrf.signing-key", () -> Base64.getEncoder().encodeToString(CSRF_KEY));
        registry.add("liveflow.auth.csrf.allowed-origins", () -> PUBLIC_ORIGIN);
        registry.add("spring.data.redis.database", () -> 15);
        registry.add("liveflow.resilience.rate-limit.registration.max-attempts", () -> 10);
        registry.add("liveflow.resilience.rate-limit.registration.window-seconds", () -> 600);
        registry.add("liveflow.room-messages.auth-seconds", () -> 2);
        registry.add("liveflow.room-messages.recheck-seconds", () -> 1);
        registry.add("liveflow.room-messages.v2.kafka-namespace", () -> MESSAGE_TEST_NAMESPACE);
        registry.add("liveflow.room-messages.v2.environment", () -> MESSAGE_TEST_NAMESPACE);
        registry.add("liveflow.room-messages.v2.state-directory", () -> STREAMS_TEST_DIRECTORY);
    }

    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean StringRedisTemplate redis;
    @Autowired JsonMapper json;
    @Autowired Admission admissions;
    @MockitoSpyBean RoomHistoryService history;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired RoomSocketHandler sockets;
    @MockitoSpyBean RoomKafkaProducer producer;
    @Autowired RoomKafkaStreamsRuntime streams;
    @Autowired RoomKafkaConsumerGroupRuntime consumerGroups;
    @Autowired RoomKafkaMaintenanceScheduler maintenance;
    @MockitoSpyBean AuthSessionRepository sessions;
    @MockitoSpyBean UserProfileService profiles;
    @MockitoSpyBean RoomMapper rooms;
    @LocalServerPort int port;

    String username;
    String password;
    long userId;
    final List<String> registeredNames = new ArrayList<>();

    @BeforeEach
    void createAccount() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while ((!streams.admissionReady() || !consumerGroups.receiptsReady()) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(streams.admissionReady()).as("Kafka Streams 分区已恢复").isTrue();
        assertThat(consumerGroups.receiptsReady()).as("回执消费组已取得分区").isTrue();

        username = "auth_" + UUID.randomUUID().toString().substring(0, 12).replace("-", "");
        password = UUID.randomUUID().toString();
        String hash = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(password);
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", username, hash);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        redis.delete(List.of(REGISTRATION_SOURCE, LOGIN_SOURCE));
    }

    @AfterEach
    void removeAccount() {
        redis.delete("liveflow:auth:session:{" + userId + "}");
        jdbc.update("DELETE FROM room_messages WHERE sender_user_id = ?", userId);
        jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        for (String name : registeredNames) {
            for (Long id : jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, name)) {
                redis.delete("liveflow:auth:session:{" + id + "}");
                jdbc.update("DELETE FROM rooms WHERE owner_user_id = ?", id);
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }
        redis.delete(List.of(REGISTRATION_SOURCE, LOGIN_SOURCE));
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
        // 已存在但没有到期时间的计数无法可靠判断窗口。
        redis.opsForValue().set(REGISTRATION_SOURCE, "1");
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
        failure(browser.send("GET", "csrf", null, null, false), 400, "INVALID_REQUEST");
        var bootstrap = browser.send("GET", "csrf?scope=pre-auth", null, null, false);
        assertThat(bootstrap.statusCode()).isEqualTo(204);
        assertThat(bootstrap.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(bootstrap.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith(PRE_SESSION + "=").contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
                        .doesNotContain("Domain="));
        assertThat(bootstrap.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith(PRE_CSRF + "=").contains("Secure", "SameSite=Lax", "Path=/")
                        .doesNotContain("HttpOnly", "Domain="));
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
        assertThat(browser.cookies).containsKey(SESSION_CSRF).doesNotContainKeys(PRE_SESSION, PRE_CSRF);
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).startsWith(SESSION_CSRF + "=").contains("Secure", "SameSite=Lax", "Path=/")
                        .doesNotContain("HttpOnly", "Domain="));
        String originalCsrf = browser.cookies.get(SESSION_CSRF);
        failure(browser.send("GET", "me", null, expiredToken(session), false), 401, "AUTH_ACCESS_EXPIRED");
        failure(browser.send("GET", "me", null, "invalid", false), 401, "AUTH_ACCESS_INVALID");

        var refresh = browser.send("POST", "refresh", null, null, true);
        assertThat(refresh.statusCode()).isEqualTo(200);
        JsonNode renewed = json.readTree(refresh.body());
        assertThat(renewed.get("sessionId")).isEqualTo(token.get("sessionId"));
        assertThat(renewed.get("sessionExpiresAt")).isEqualTo(token.get("sessionExpiresAt"));
        assertThat(browser.cookies.get(SESSION_CSRF)).isEqualTo(originalCsrf);

        var logout = browser.send("POST", "logout", logoutBody(session), null, true);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(browser.cookies).doesNotContainKeys("lf_refresh", SESSION_CSRF);
        failure(browser.send("GET", "me", null, access(renewed), false), 401, "AUTH_SESSION_INACTIVE");
    }

    @Test
    void csrfScopeOriginAndSessionBindingRejectBeforeSideEffects() throws Exception {
        var browser = new Browser();
        for (String query : List.of("csrf?scope=other", "csrf?scope=session&scope=pre-auth", "csrf?scope=pre-auth&extra=1")) {
            failure(browser.send("GET", query, null, null, false), 400, "INVALID_REQUEST");
        }
        var rejectedOrigin = browser.sendPath("GET", "/api/v1/auth/csrf?scope=pre-auth", null, null, false,
                Map.of("Origin", "https://other.example"));
        failure(rejectedOrigin, 403, "AUTH_CSRF_INVALID");
        assertThat(rejectedOrigin.headers().allValues("Set-Cookie")).isEmpty();
        browser.csrf();
        String anonymous = browser.cookies.get(PRE_CSRF);
        browser.csrf();
        assertThat(browser.cookies.get(PRE_CSRF)).isEqualTo(anonymous);
        JsonNode old = browser.login();
        String oldCsrf = browser.cookies.get(SESSION_CSRF);
        var oldDevice = new Browser();
        oldDevice.cookies.putAll(browser.cookies);
        JsonNode current = browser.login();
        String bearer = access(current);
        String sessionCsrf = browser.cookies.get(SESSION_CSRF);
        String body = profileBody("会话边界", "blue", "");

        for (String invalid : List.of(anonymous, oldCsrf, "invalid")) {
            browser.cookies.put(SESSION_CSRF, invalid);
            failure(browser.sendPath("PUT", PROFILE_PATH, body, bearer, true), 403, "AUTH_CSRF_INVALID");
        }
        browser.cookies.put(SESSION_CSRF, sessionCsrf);
        failure(browser.sendPath("PUT", PROFILE_PATH, body, bearer, false,
                Map.of("X-XSRF-TOKEN", "mismatch")), 403, "AUTH_CSRF_INVALID");
        failure(browser.sendPath("PUT", PROFILE_PATH, body, bearer, true,
                Map.of("Origin", "null", "Referer", PUBLIC_ORIGIN + "/profile")), 403, "AUTH_CSRF_INVALID");
        failure(browser.sendPath("PUT", PROFILE_PATH, body, bearer, true,
                Map.of("Referer", "https://other.example/profile")), 403, "AUTH_CSRF_INVALID");
        assertThat(profileCount(userId)).isZero();
        failure(oldDevice.send("POST", "refresh", null, null, true), 401, "AUTH_REFRESH_INVALID");
        failure(oldDevice.send("GET", "me", null, access(old), false), 401, "AUTH_SESSION_REPLACED");
        assertThat(browser.sendPath("PUT", PROFILE_PATH, body, bearer, true,
                Map.of("Referer", PUBLIC_ORIGIN + "/profile")).statusCode()).isEqualTo(200);
    }

    @Test
    void csrfReissueIsReadOnlyAndPreservesReplayRevocation() throws Exception {
        var browser = new Browser();
        JsonNode login = browser.login();
        String bearer = access(login);
        String refresh = browser.cookies.get("lf_refresh");
        String originalCsrf = browser.cookies.remove(SESSION_CSRF);
        failure(browser.send("GET", "csrf?scope=session", null, "invalid", false), 401, "AUTH_ACCESS_INVALID");
        assertThat(browser.cookies).doesNotContainKey(SESSION_CSRF);
        browser.sessionCsrf(null);
        assertThat(browser.cookies.get("lf_refresh")).isEqualTo(refresh);
        var accepted = browser.send("POST", "refresh", null, null, true);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(browser.cookies.get("lf_refresh")).isNotEqualTo(refresh);

        var replay = new Browser();
        replay.cookies.put("lf_refresh", refresh);
        var reissue = replay.send("GET", "csrf?scope=session", null, null, false);
        failure(reissue, 401, "AUTH_REFRESH_INVALID");
        assertThat(reissue.headers().allValues("Set-Cookie")).isEmpty();
        failure(replay.send("POST", "refresh", null, null, true), 403, "AUTH_CSRF_INVALID");
        assertThat(browser.send("GET", "me", null, bearer, false).statusCode()).isEqualTo(200);
        replay.cookies.put(SESSION_CSRF, originalCsrf);
        failure(replay.send("POST", "refresh", null, null, true), 401, "AUTH_REFRESH_REUSED");
        failure(browser.send("GET", "me", null, bearer, false), 401, "AUTH_SESSION_INACTIVE");
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
        doThrow(new AuthFailure(AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE)).when(sessions)
                .currentExpiry(org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
        failure(browser.send("GET", "csrf?scope=session", null, null, false), 503, "AUTH_SERVICE_UNAVAILABLE");
    }

    @Test
    void loginChecksBothFixedWindowsAndReturnsTheLongestWait() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        browser.csrf();
        String account = "liveflow:auth:{login}:account:" + username;
        String otherCase = "liveflow:auth:{login}:account:" + username.toUpperCase(Locale.ROOT);
        redis.opsForValue().set(account, "10", Duration.ofSeconds(8));
        redis.opsForValue().set(LOGIN_SOURCE, "60", Duration.ofSeconds(37));
        var bothLimited = browser.send("POST", "login", credentials(password), null, true);
        failure(bothLimited, 429, "AUTH_RATE_LIMITED");
        assertThat(Integer.parseInt(bothLimited.headers().firstValue("Retry-After").orElseThrow())).isBetween(36, 37);
        assertThat(redis.opsForValue().get(account)).isEqualTo("10");
        assertThat(redis.opsForValue().get(LOGIN_SOURCE)).isEqualTo("60");
        assertThat(redis.getExpire(account, TimeUnit.MILLISECONDS)).isBetween(1L, 8000L);
        assertThat(redis.getExpire(LOGIN_SOURCE, TimeUnit.MILLISECONDS)).isBetween(1L, 37000L);

        var sourceLimited = browser.send("POST", "login", credentials(username.toUpperCase(Locale.ROOT), password), null, true);
        failure(sourceLimited, 429, "AUTH_RATE_LIMITED");
        assertThat(redis.hasKey(otherCase)).isFalse();
        redis.opsForValue().set(LOGIN_SOURCE, "1", Duration.ofSeconds(30));
        var accountLimited = browser.send("POST", "login", credentials(password), null, true);
        failure(accountLimited, 429, "AUTH_RATE_LIMITED");
        assertThat(redis.opsForValue().get(LOGIN_SOURCE)).isEqualTo("1");
        failure(browser.send("POST", "login", credentials(username.toUpperCase(Locale.ROOT), password), null, true),
                401, "AUTH_INVALID_CREDENTIALS");
        assertThat(redis.opsForValue().get(otherCase)).isEqualTo("1");
        assertThat(redis.opsForValue().get(LOGIN_SOURCE)).isEqualTo("2");
        assertThat(redis.getExpire(LOGIN_SOURCE, TimeUnit.MILLISECONDS)).isBetween(1L, 30000L);

        redis.opsForValue().set(account, "1");
        failure(browser.send("POST", "login", credentials(password), null, true), 503, "AUTH_SERVICE_UNAVAILABLE");
        assertThat(redis.opsForValue().get(LOGIN_SOURCE)).isEqualTo("2");
        assertThat(browser.send("GET", "me", null, bearer, false).statusCode()).isEqualTo(200);
        redis.delete(List.of(account, otherCase));
    }

    @Test
    void rateLimitedLoginKeepsCurrentSession() throws Exception {
        var browser = new Browser();
        String access = access(browser.login());
        browser.csrf();
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
        secondBrowser.sessionCsrf(bearer);
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
        second.sessionCsrf(bearer);
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
    void concurrentRoomMessageDuplicatesBroadcastOnceAndStayInTheRoom() throws Exception {
        var browser = new Browser();
        var login = browser.login();
        String bearer = access(login);
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
            secondTab.sessionCsrf(bearer);
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
            var responses = List.of(submissions.getFirst().get(), submissions.getLast().get());
            assertThat(responses).anySatisfy(response -> assertThat(response.statusCode()).isEqualTo(202));
            for (var response : responses) {
                if (response.statusCode() != 202) failure(response, 503, "MESSAGE_SUBMISSION_UNKNOWN");
            }
            var queuedResponse = responses.stream().filter(response -> response.statusCode() == 202).findFirst().orElseThrow();
            var queued = roomResponse(queuedResponse, 202);
            assertThat(queued.get("status").asString()).isEqualTo("queued");
            assertThat(queued.has("retryUntil")).isFalse();
            assertThat(queued.size()).isEqualTo(3);
            for (var response : responses) {
                if (response.statusCode() == 503) {
                    assertThat(json.readTree(response.body()).get("submissionId")).isEqualTo(queued.get("submissionId"));
                }
            }
            String key = MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:message:{" + userId + "}:submit:"
                    + login.get("sessionId").asString() + ":" + room + ":" + id;
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(redis.opsForHash().get(key, "phase")).isEqualTo("QUEUED"));
            assertThat(roomResponse(browser.sendPath("POST", path, messageBody(id, "你好"), bearer, true), 202))
                    .isEqualTo(queued);
            String receiptPath = queuedResponse.headers().firstValue("Location").orElseThrow();
            JsonNode receipt = awaitJson(browser, bearer, receiptPath, result -> "accepted".equals(result.get("status").asString()));
            JsonNode event = nextMessage(sender);
            assertThat(event.get("roomSeq").asString()).isEqualTo("1");
            assertThat(event.get("messageId")).isEqualTo(receipt.get("messageId"));
            assertThat(event.get("sentAt")).isEqualTo(receipt.get("sentAt"));
            assertThat(event.get("senderName").asString()).isEqualTo(username);
            assertThat(event.has("userId")).isFalse();
            assertThat(event.get("content").asString()).isEqualTo("你好");
            assertThat(nextMessage(receiver)).isEqualTo(event);
            failure(browser.sendPath("POST", path, messageBody(id, "已修改"), bearer, true), 409, "MESSAGE_ID_CONFLICT");
            var otherReceipt = roomResponse(viewer.sendPath("GET", receiptPath, null, viewerBearer, false), 200);
            assertThat(otherReceipt.get("status").asString()).isEqualTo("unconfirmed");
            var history = awaitJson(browser, bearer, path + "?afterSeq=0&throughSeq=2",
                    result -> "1".equals(result.get("latestStoredSeq").asString()));
            assertThat(history.get("throughSeq").asString()).isEqualTo("1");
            assertThat(history.get("items").size()).isEqualTo(1);
            assertThat(history.get("items").get(0).get("messageId")).isEqualTo(event.get("messageId"));

            String deniedId = UUID.randomUUID().toString();
            var limited = browser.sendPath("POST", messagePath(otherRoom, "messages"),
                    messageBody(deniedId, "不同房间"), bearer, true);
            failure(limited, 429, "MESSAGE_RATE_LIMITED");
            assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(
                    value -> assertThat(Integer.parseInt(value)).isBetween(1, 10));

            for (int i = 0; i < 4; i++) {
                // 该用例关注广播、历史和分页。独立用例验证真实的 10 秒冷却。
                redis.expire(cooldownKey(), Duration.ZERO);
                roomResponse(secondTab.sendPath("POST", path, messageBody(UUID.randomUUID().toString(), "下一条"),
                        bearer, true), 202);
                assertThat(nextMessage(sender).get("roomSeq").asString()).isEqualTo(Integer.toString(i + 2));
            }
            var recent = awaitJson(browser, bearer, path + "?limit=2",
                    result -> "5".equals(result.get("latestStoredSeq").asString()));
            assertThat(recent.get("items").get(0).get("roomSeq").asString()).isEqualTo("4");
            var first = roomResponse(browser.sendPath("GET", path + "?limit=2&afterSeq=0&throughSeq=5", null, bearer, false), 200);
            assertThat(first.get("nextAfterSeq").asString()).isEqualTo("2");
            var second = roomResponse(browser.sendPath("GET", path + "?limit=2&afterSeq=2&throughSeq=5", null, bearer, false), 200);
            assertThat(second.get("nextAfterSeq").asString()).isEqualTo("4");
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

    @Test
    void singlePublicationPermitSurvivesTheOldLeaseDeadline() {
        var scope = new Scope(userId, UUID.randomUUID(), UUID.randomUUID().toString());
        String clientId = UUID.randomUUID().toString();
        String hash = RoomEvents.hash("你好");
        String key = MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:message:{" + userId + "}:submit:" + scope.sessionId() + ":"
                + scope.roomId() + ":" + clientId;
        var old = admissions.claim(scope, clientId, hash, "首次昵称");
        try {
            assertThat(redis.getExpire(key)).isBetween(1790L, 1800L);
            var inFlight = assertThrows(RoomMessageFailure.class, () -> admissions.claim(scope, clientId, hash, "昵称"));
            assertThat(inFlight.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN);
            assertThat(inFlight.receipt()).isEqualTo(old.queued());
            assertThat(inFlight.retryAfterSeconds()).isZero();
            var conflict = assertThrows(RoomMessageFailure.class,
                    () -> admissions.claim(scope, clientId, RoomEvents.hash("异文"), "昵称"));
            assertThat(conflict.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_ID_CONFLICT);

            long deadline = cooldownDeadline();
            // 模拟切换前的旧绑定。这个字段只能随原绑定到期，不能再次授予许可。
            redis.opsForHash().put(key, "leaseUntilMs", "0");
            var pending = assertThrows(RoomMessageFailure.class, () -> admissions.claim(scope, clientId, hash, "后来昵称"));
            assertThat(pending.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN);
            assertThat(pending.receipt()).isEqualTo(old.queued());
            assertThat(redis.opsForHash().get(key, "leaseId")).isEqualTo(old.leaseId().toString());
            assertThat(redis.opsForHash().get(key, "senderName")).isEqualTo("首次昵称");
            admissions.markQueued(scope, clientId, new com.liveflow.core.room.message.RoomMessagePorts.Reservation(
                    old.queued(), UUID.randomUUID(), "其他许可"));
            assertThat(redis.opsForHash().get(key, "phase")).isEqualTo("PENDING");
            admissions.markQueued(scope, clientId, old);
            assertThat(admissions.findQueued(scope, clientId, hash)).contains(old.queued());
            assertThat(cooldownDeadline()).isEqualTo(deadline);
        } finally {
            redis.delete(key);
            redis.delete(cooldownKey());
        }
    }

    private String cooldownKey() {
        return MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:message:{" + userId + "}:cooldown";
    }

    /** Redis 绝对截止用于验证重试和拒绝是否延长原冷却。 */
    private long cooldownDeadline() {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME', KEYS[1])", Long.class);
        return redis.execute(script, List.of(cooldownKey()));
    }

    @Test
    void accountCooldownIsAtomicAcrossRoomsAndSessionsAndExpiresAtOriginalDeadline() throws Exception {
        var firstScope = new Scope(userId, UUID.randomUUID(), UUID.randomUUID().toString());
        var secondScope = new Scope(userId, UUID.randomUUID(), UUID.randomUUID().toString());
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        String contentHash = RoomEvents.hash("并发消息");
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> attemptRoomReservation(start, firstScope, firstId, contentHash));
            var second = executor.submit(() -> attemptRoomReservation(start, secondScope, secondId, contentHash));
            UUID firstWinner = first.get(4, TimeUnit.SECONDS);
            UUID secondWinner = second.get(4, TimeUnit.SECONDS);
            assertThat(firstWinner == null).isNotEqualTo(secondWinner == null);
            var winningScope = firstWinner != null ? firstScope : secondScope;
            var winningId = firstWinner != null ? firstId : secondId;
            var losingScope = firstWinner != null ? secondScope : firstScope;
            var losingId = firstWinner != null ? secondId : firstId;
            var deadline = cooldownDeadline();
            assertThat(redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS)).isBetween(9000L, 10000L);
            assertThat(assertThrows(RoomMessageFailure.class, () ->
                    admissions.claim(winningScope, winningId, contentHash, username)).code())
                    .isEqualTo(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN);
            assertThat(assertThrows(RoomMessageFailure.class, () ->
                    admissions.claim(losingScope, losingId, contentHash, username)).code())
                    .isEqualTo(RoomMessageFailure.Code.MESSAGE_RATE_LIMITED);
            assertThat(cooldownDeadline()).isEqualTo(deadline);

            // 让 Redis 自己按原始截止过期。到期后，相同的被拒标识仍可创建首个预约。
            int ttl = redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS).intValue();
            if (ttl > 0) Thread.sleep(ttl + 10L);
            var next = admissions.claim(losingScope, losingId, contentHash, username);
            assertThat(next.queued().admittedAt().toEpochMilli()).isGreaterThanOrEqualTo(deadline);
            assertThat(cooldownDeadline()).isEqualTo(next.queued().admittedAt().toEpochMilli() + 10000);
        } finally {
            redis.delete(cooldownKey());
        }
    }

    @Test
    void luaOpensTheNextReservationExactlyAtTheCooldownDeadline() throws Exception {
        long deadline = Instant.now().plusSeconds(60).toEpochMilli();
        String reservation = cooldownKey() + ":test-reservation";
        var expireAt = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PEXPIREAT', KEYS[1], ARGV[1])", Long.class);
        redis.opsForValue().set(cooldownKey(), UUID.randomUUID().toString());
        redis.execute(expireAt, List.of(cooldownKey()), Long.toString(deadline));

        try {
            assertThat(claimAtMillis(reservation, deadline - 1)).containsExactly("RATE", "1");
            assertThat(redis.hasKey(reservation)).isFalse();
            assertThat(cooldownDeadline()).isEqualTo(deadline);
            assertThat(claimAtMillis(reservation, deadline)).startsWith("NEW");
            assertThat(redis.opsForHash().get(reservation, "admittedAtMs")).isEqualTo(Long.toString(deadline));
            assertThat(cooldownDeadline()).isEqualTo(deadline + 10000);
        } finally {
            redis.delete(List.of(cooldownKey(), reservation));
        }
    }

    /**
     * 仅替换 Redis 脚本的 TIME 返回值，实际读写和 TTL 命令由真实 Redis 执行。
     * @param reservation 测试预约键
     * @param nowMillis 用于裁决的 Redis 毫秒时刻
     * @return 间隔裁决与预约字段
     */
    private List<String> claimAtMillis(String reservation, long nowMillis) throws Exception {
        var resource = new org.springframework.core.io.ClassPathResource("room/claim-message.lua");
        String script = """
                local native = redis
                local redis = {call = function(command, ...)
                    if command == 'TIME' then return {'%s', '%s'} end
                    return native.call(command, ...)
                end}
                """.formatted(nowMillis / 1000, (nowMillis % 1000) * 1000)
                + resource.getContentAsString(StandardCharsets.UTF_8);
        var claim = new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, List.class);
        List<?> result = redis.execute(claim, List.of(cooldownKey(), reservation), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), RoomEvents.hash("边界"), username, "10000", "1800000");
        return result.stream().map(Object::toString).toList();
    }

    private UUID attemptRoomReservation(CyclicBarrier start, Scope scope, String id, String hash) throws Exception {
        start.await(3, TimeUnit.SECONDS);
        try {
            return admissions.claim(scope, id, hash, username).queued().submissionId();
        } catch (RoomMessageFailure failure) {
            assertThat(failure.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_RATE_LIMITED);
            assertThat(failure.retryAfterSeconds()).isBetween(1L, 10L);
            return null;
        }
    }

    @Test
    void uncertainKafkaAcknowledgementKeepsCooldownAndCanBeReadWithoutRepublishing() throws Exception {
        var browser = new Browser();
        JsonNode login = browser.login();
        String bearer = access(login);
        String room = createRoomFor(browser, bearer);
        String endpoint = messagePath(room, "messages");
        String id = UUID.randomUUID().toString();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> sent = (CompletableFuture<Void>) invocation.callRealMethod();
            if (calls.getAndIncrement() == 0) {
                return sent.thenCompose(ignored -> CompletableFuture.failedFuture(
                        new RoomMessageFailure(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN)));
            }
            return sent;
        }).when(producer).publish(org.mockito.ArgumentMatchers.argThat(
                event -> event != null && id.equals(event.clientMessageId())));

        var unknown = browser.sendPath("POST", endpoint, messageBody(id, "原弹幕"), bearer, true);
        failure(unknown, 503, "MESSAGE_SUBMISSION_UNKNOWN");
        assertThat(unknown.headers().firstValue("Retry-After")).isEmpty();
        var reference = json.readTree(unknown.body());
        assertThat(reference.get("receiptExpiresAt").asString()).endsWith("Z");
        long deadline = cooldownDeadline();
        String prefix = MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:message:{" + userId + "}:submit:"
                + login.get("sessionId").asString() + ":" + room + ":";
        String deniedId = UUID.randomUUID().toString();
        failure(browser.sendPath("POST", endpoint, messageBody(deniedId, "新弹幕"), bearer, true),
                429, "MESSAGE_RATE_LIMITED");
        assertThat(redis.hasKey(prefix + deniedId)).isFalse();
        failure(browser.sendPath("POST", endpoint, messageBody(id, "改正文"), bearer, true), 409, "MESSAGE_ID_CONFLICT");
        redis.opsForHash().put(prefix + id, "leaseUntilMs", "0");
        var duplicate = browser.sendPath("POST", endpoint, messageBody(id, "原弹幕"), bearer, true);
        failure(duplicate, 503, "MESSAGE_SUBMISSION_UNKNOWN");
        assertThat(json.readTree(duplicate.body())).isEqualTo(reference);
        var receipt = awaitJson(browser, bearer, endpoint + "/submissions/" + reference.get("submissionId").asString(),
                value -> "accepted".equals(value.get("status").asString()));
        var history = awaitJson(browser, bearer, endpoint,
                value -> "1".equals(value.get("latestStoredSeq").asString()));
        assertThat(history.get("items").size()).isOne();
        assertThat(history.get("items").get(0).get("messageId")).isEqualTo(receipt.get("messageId"));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(cooldownDeadline()).isEqualTo(deadline);
    }

    @Test
    void transactionalKafkaReplayReturnsCanonicalMessageAndRebuildsReceipts() throws Exception {
        var browser = new Browser();
        JsonNode login = browser.login();
        String bearer = access(login);
        String room = createRoomFor(browser, bearer);
        UUID session = UUID.fromString(login.get("sessionId").asString());
        String user = Long.toString(userId);
        String client = UUID.randomUUID().toString();
        String recent = RoomEvents.UTC_MILLIS.format(Instant.now());
        String overdue = RoomEvents.UTC_MILLIS.format(Instant.now().minusSeconds(721));
        String messages = messagePath(room, "messages");
        var first = new Submission(1, UUID.randomUUID(), room, user, session, client, "首条",
                RoomEvents.hash("首条"), username, recent);
        var repeat = new Submission(1, UUID.randomUUID(), room, user, session, client, "首条",
                first.contentHash(), "其他昵称", recent);
        var changed = new Submission(1, UUID.randomUUID(), room, user, session, client, "异文",
                RoomEvents.hash("异文"), username, recent);
        var expired = new Submission(1, UUID.randomUUID(), room, user, session, UUID.randomUUID().toString(),
                "超期", RoomEvents.hash("超期"), username, overdue);
        for (var record : List.of(first, first, repeat, changed, expired)) {
            producer.publish(record).get(5, TimeUnit.SECONDS);
        }

        String prefix = messages + "/submissions/";
        var accepted = awaitJson(browser, bearer, prefix + first.submissionId(),
                value -> value.get("status").asString().equals("accepted"));
        var replay = awaitJson(browser, bearer, prefix + repeat.submissionId(),
                value -> value.get("status").asString().equals("accepted"));
        assertThat(replay.get("messageId")).isEqualTo(accepted.get("messageId"));
        assertThat(replay.get("roomSeq").asString()).isEqualTo("1");
        assertThat(replay.get("sentAt")).isEqualTo(accepted.get("sentAt"));
        var conflict = awaitJson(browser, bearer, prefix + changed.submissionId(),
                value -> "rejected".equals(value.get("status").asString()));
        assertThat(conflict.get("code").asString()).isEqualTo("MESSAGE_ID_CONFLICT");
        var rejection = awaitJson(browser, bearer, prefix + expired.submissionId(),
                value -> "rejected".equals(value.get("status").asString()));
        assertThat(rejection.get("code").asString()).isEqualTo("MESSAGE_SUBMISSION_EXPIRED");

        var latest = awaitJson(browser, bearer, messages, value -> value.get("latestStoredSeq").asString().equals("1"));
        assertThat(latest.get("items").size()).isOne();
        assertThat(latest.get("items").get(0).get("senderName").asString()).isEqualTo(username);

        String receiptKey = MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:message:{" + userId + "}:receipt:" + session + ":" + room + ":"
                + first.submissionId();
        redis.delete(receiptKey);
        redis.delete(MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:message:receipt-projection:" + MESSAGE_TEST_NAMESPACE);
        maintenance.restoreReceipts();
        assertThat(awaitJson(browser, bearer, prefix + first.submissionId(),
                value -> value.get("status").asString().equals("accepted"))).isEqualTo(accepted);
        assertThat(redis.getExpire(receiptKey)).isBetween(1740L, 1800L);

        String wrongRoom = messagePath(UUID.randomUUID().toString(), "messages/submissions/" + first.submissionId());
        assertThat(roomResponse(browser.sendPath("GET", wrongRoom, null, bearer, false), 200)
                .get("status").asString()).isEqualTo("unconfirmed");
        var newBrowser = new Browser();
        String replacement = access(newBrowser.login());
        assertThat(roomResponse(newBrowser.sendPath("GET", prefix + first.submissionId(), null, replacement, false), 200)
                .get("status").asString()).isEqualTo("unconfirmed");
    }

    @Test
    void historyAllowsSparsePagesAndCleanUpByAcceptanceTime() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        String path = messagePath(room, "messages");
        var empty = roomResponse(browser.sendPath("GET", path, null, bearer, false), 200);
        assertThat(empty.get("latestStoredSeq").asString()).isEqualTo("0");
        assertThat(empty.get("throughSeq").asString()).isEqualTo("0");
        assertThat(empty.get("items").size()).isZero();
        for (String query : List.of("?limit=0", "?afterSeq=01", "?throughSeq=1", "?limit=1&limit=2", "?other=a",
                "?afterSeq=9223372036854775807", "?afterSeq=5&throughSeq=2")) {
            failure(browser.sendPath("GET", path + query, null, bearer, false), 400, "INVALID_REQUEST");
        }

        var high = historyEvent(room, 103, UUID.randomUUID(), "先到", Instant.now());
        var late = historyEvent(room, 101, UUID.randomUUID(), "晚到", Instant.now());
        var middle = historyEvent(room, 102, UUID.randomUUID(), "稍晚", Instant.now());
        history.append(List.of(new HistoryRecord(high, 0)));
        String range = path + "?afterSeq=100&throughSeq=103";
        var sparse = roomResponse(browser.sendPath("GET", range, null, bearer, false), 200);
        assertThat(sparse.get("items").get(0).get("roomSeq").asString()).isEqualTo("103");
        assertThat(sparse.get("items").size()).isOne();
        assertThat(sparse.get("nextAfterSeq").isNull()).isTrue();
        assertThat(history.append(List.of(new HistoryRecord(late, 1), new HistoryRecord(high, 2),
                new HistoryRecord(middle, 3))).inserted()).containsExactly(late, middle);
        var first = roomResponse(browser.sendPath("GET", range + "&limit=1", null, bearer, false), 200);
        assertThat(first.get("nextAfterSeq").asString()).isEqualTo("101");
        assertThat(first.get("throughSeq").asString()).isEqualTo("103");
        assertThat(first.get("latestStoredSeq").asString()).isEqualTo("103");
        var bounded = roomResponse(browser.sendPath("GET", path + "?afterSeq=101&throughSeq=102", null, bearer, false), 200);
        assertThat(bounded.get("items").get(0).get("roomSeq").asString()).isEqualTo("102");
        assertThat(bounded.get("throughSeq").asString()).isEqualTo("102");
        assertThat(bounded.get("latestStoredSeq").asString()).isEqualTo("103");
        var pastEnd = roomResponse(browser.sendPath("GET", path + "?afterSeq=104", null, bearer, false), 200);
        assertThat(pastEnd.get("items").size()).isZero();
        assertThat(pastEnd.get("throughSeq").asString()).isEqualTo("103");

        // 过期的是更高的序号，清理不得连带删除较小的仍有效记录，也不得回退进度。
        var expired = historyEvent(room, 103, high.messageId(), high.content(), Instant.now().minus(Duration.ofHours(24)));
        jdbc.update("UPDATE room_messages SET sent_at = ? WHERE room_id = ? AND room_seq = 103",
                java.time.LocalDateTime.ofInstant(Instant.parse(expired.sentAt()), java.time.ZoneOffset.UTC), room);
        var beforeCleanup = roomResponse(browser.sendPath("GET", path, null, bearer, false), 200);
        assertThat(beforeCleanup.get("items").size()).isEqualTo(2);
        history.clean(() -> false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_messages WHERE room_id = ?", Integer.class, room))
                .isEqualTo(3);
        history.clean(() -> true);
        assertThat(history.append(List.of(new HistoryRecord(expired, 4))).inserted()).isEmpty();
        var oldCursor = roomResponse(browser.sendPath("GET", path + "?afterSeq=0", null, bearer, false), 200);
        assertThat(oldCursor.get("items").size()).isEqualTo(2);
        assertThat(oldCursor.get("latestStoredSeq").asString()).isEqualTo("103");
        jdbc.update("UPDATE room_messages SET sent_at = UTC_TIMESTAMP(3) - INTERVAL 25 HOUR WHERE room_id = ?", room);
        history.clean(() -> true);
        var fullyExpired = roomResponse(browser.sendPath("GET", path, null, bearer, false), 200);
        assertThat(fullyExpired.get("items").size()).isZero();
        assertThat(fullyExpired.get("latestStoredSeq").asString()).isEqualTo("103");
    }

    @Test
    void historyPagesBackwardAcrossMissingAndExpiredSequences() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        var otherBrowser = new Browser();
        String otherName = registrationName("History_");
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?", otherName, userId);
        String otherRoom = createRoomFor(otherBrowser, access(otherBrowser.login(otherName)));
        String path = messagePath(room, "messages");
        Instant now = Instant.now();
        var records = IntStream.rangeClosed(1, 203).filter(seq -> seq != 102)
                .mapToObj(seq -> new HistoryRecord(historyEvent(room, seq, UUID.randomUUID(), "本房间", now), seq))
                .toList();
        history.append(records);
        history.append(List.of(new HistoryRecord(historyEvent(otherRoom, 1, UUID.randomUUID(), "其他房间", now), 1)));
        jdbc.update("UPDATE room_messages SET sent_at = UTC_TIMESTAMP(3) - INTERVAL 24 HOUR"
                + " WHERE room_id = ? AND room_seq = 1", room);

        var recent = roomResponse(browser.sendPath("GET", path, null, bearer, false), 200);
        assertThat(recent.get("items").size()).isEqualTo(100);
        assertThat(recent.get("items").get(0).get("roomSeq").asString()).isEqualTo("104");
        assertThat(recent.get("items").get(99).get("roomSeq").asString()).isEqualTo("203");
        assertThat(recent.get("nextBeforeSeq").asString()).isEqualTo("104");
        assertThat(recent.get("nextAfterSeq").isNull()).isTrue();

        var older = roomResponse(browser.sendPath("GET", path + "?beforeSeq=104", null, bearer, false), 200);
        assertThat(older.get("items").size()).isEqualTo(100);
        var actual = IntStream.range(0, 100)
                .mapToObj(i -> older.get("items").get(i).get("roomSeq").asString()).toList();
        var expected = IntStream.rangeClosed(3, 103).filter(seq -> seq != 102).mapToObj(Integer::toString).toList();
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(older.get("nextBeforeSeq").asString()).isEqualTo("3");
        assertThat(older.get("throughSeq").asString()).isEqualTo("203");
        assertThat(older.get("latestStoredSeq").asString()).isEqualTo("203");
        assertThat(older.get("nextAfterSeq").isNull()).isTrue();

        var last = roomResponse(browser.sendPath("GET", path + "?beforeSeq=3&limit=1", null, bearer, false), 200);
        assertThat(last.get("items").size()).isOne();
        assertThat(last.get("items").get(0).get("roomSeq").asString()).isEqualTo("2");
        assertThat(last.get("nextBeforeSeq").isNull()).isTrue();
        for (String boundary : List.of("1", "2")) {
            var empty = roomResponse(browser.sendPath("GET", path + "?beforeSeq=" + boundary, null, bearer, false), 200);
            assertThat(empty.get("items").size()).isZero();
            assertThat(empty.get("nextBeforeSeq").isNull()).isTrue();
            assertThat(empty.get("nextAfterSeq").isNull()).isTrue();
        }
        var gap = roomResponse(browser.sendPath("GET", path + "?beforeSeq=102&limit=1", null, bearer, false), 200);
        assertThat(gap.get("items").get(0).get("roomSeq").asString()).isEqualTo("101");
        for (String query : List.of("?beforeSeq=0", "?beforeSeq=-1", "?beforeSeq=01", "?beforeSeq=abc",
                "?beforeSeq=9223372036854775807", "?beforeSeq=3&beforeSeq=4",
                "?beforeSeq=3&afterSeq=0", "?beforeSeq=3&throughSeq=10", "?beforeSeq=3&limit=101")) {
            failure(browser.sendPath("GET", path + query, null, bearer, false), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void historyProgressOnlyNotifiesIncreasesAndRespectsPartitionBlocks() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        String path = messagePath(room, "messages");
        String topic = MESSAGE_TEST_NAMESPACE + ".liveflow.room-message.accepted.v1";
        try (var socket = readyRoom(browser, bearer, room)) {
            var high = historyEvent(room, 103, UUID.randomUUID(), "先到", Instant.now());
            var late = historyEvent(room, 101, UUID.randomUUID(), "晚到", Instant.now());
            history.append(List.of(new HistoryRecord(high, 0)));
            sockets.notifyHistoryProgress();
            var frame = socket.next();
            assertThat(frame.get("type").asString()).isEqualTo("history_progress");
            assertThat(frame.get("latestStoredSeq").asString()).isEqualTo("103");
            history.append(List.of(new HistoryRecord(late, 1)));
            sockets.notifyHistoryProgress();
            assertThat(socket.frames.poll(200, TimeUnit.MILLISECONDS)).isNull();

            jdbc.update("""
                    INSERT INTO room_message_processing_blocks
                        (consumer_group, topic_name, partition_no, reason, observed_offset, reported_by, updated_at)
                    VALUES (?, ?, ?, 'OFFSET_OUT_OF_RANGE', 0, 'fixture', UTC_TIMESTAMP(3))
                    """, historyGroup(), topic, partition(room));
            try {
                failure(browser.sendPath("GET", path, null, bearer, false), 503, "ROOM_MESSAGES_UNAVAILABLE");
                failure(browser.sendPath("GET", path + "?beforeSeq=103", null, bearer, false), 503,
                        "ROOM_MESSAGES_UNAVAILABLE");
                assertThat(history.progress(List.of(room))).isEmpty();
            } finally {
                jdbc.update("DELETE FROM room_message_processing_blocks WHERE consumer_group = ? AND topic_name = ?"
                        + " AND partition_no = ? AND room_id = ''", historyGroup(), topic, partition(room));
            }
        }
    }

    @Test
    void historySkipsSingleConflictsWithoutPausingEitherRoom() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        String secondRoom = matchingRoom(room, true);
        String account = registrationName("Hist_");
        jdbc.update("INSERT INTO users (username, password_hash, created_at, updated_at)"
                + " SELECT ?, password_hash, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) FROM users WHERE id = ?", account, userId);
        long owner = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, account);
        jdbc.update("INSERT INTO rooms (room_uuid, owner_user_id, title, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP(3))",
                secondRoom, owner, "同分区房间");

        Instant now = Instant.now();
        var original = historyEvent(room, 103, UUID.randomUUID(), "原值", now);
        var bySequence = historyEvent(room, 103, UUID.randomUUID(), "序号冲突", now);
        var byId = historyEvent(room, 104, original.messageId(), "消息 ID 冲突", now);
        var differentContent = historyEvent(room, 103, original.messageId(), "同键异文", now);
        var valid = historyEvent(room, 105, UUID.randomUUID(), "A 继续", now);
        var anotherRoom = historyEvent(secondRoom, 1, UUID.randomUUID(), "B 继续", now);
        var batchConflict = historyEvent(room, 107, UUID.randomUUID(), "批内先到", now);
        var batchLate = historyEvent(room, 107, UUID.randomUUID(), "批内后到", now);
        var batch = history.append(List.of(new HistoryRecord(batchConflict, 2), new HistoryRecord(batchLate, 5)));
        assertThat(batch.inserted()).containsExactly(batchConflict);
        assertThat(batch.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.record().offset()).isEqualTo(5);
            assertThat(conflict.sequenceMatch()).isEqualTo(batchConflict.messageId().toString());
        });
        try (var producer = historyProducer()) {
            for (var event : List.of(original, original, bySequence, byId, differentContent, valid, anotherRoom)) {
                publishHistory(producer, event);
            }
        }
        var storedB = awaitJson(browser, bearer, messagePath(secondRoom, "messages"),
                page -> "1".equals(page.get("latestStoredSeq").asString()));
        assertThat(storedB.get("items").get(0).get("content").asString()).isEqualTo("B 继续");
        var storedA = roomResponse(browser.sendPath("GET", messagePath(room, "messages"), null, bearer, false), 200);
        assertThat(storedA.get("items").size()).isEqualTo(3);
        assertThat(storedA.get("items").get(0).get("messageId").asString()).isEqualTo(original.messageId().toString());
        assertThat(storedA.get("items").get(1).get("content").asString()).isEqualTo("A 继续");
        assertThat(storedA.get("items").get(2).get("content").asString()).isEqualTo("批内先到");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_message_processing_blocks WHERE consumer_group = ?"
                + " AND partition_no = ?", Integer.class, historyGroup(), partition(room))).isZero();
    }

    @Test
    void concurrentHistoryBatchesRespectTheGlobalMessageId() throws Exception {
        String room = UUID.randomUUID().toString();
        String otherPartition = matchingRoom(room, false);
        UUID messageId = UUID.randomUUID();
        var first = historyEvent(room, 1, messageId, "甲", Instant.now());
        var second = historyEvent(otherPartition, 2, messageId, "乙", Instant.now());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                barrier.await(3, TimeUnit.SECONDS);
                return history.append(List.of(new HistoryRecord(first, 0)));
            });
            var b = executor.submit(() -> {
                barrier.await(3, TimeUnit.SECONDS);
                return history.append(List.of(new HistoryRecord(second, 0)));
            });
            var results = List.of(a.get(6, TimeUnit.SECONDS), b.get(6, TimeUnit.SECONDS));
            assertThat(results.stream().flatMap(result -> result.inserted().stream())).hasSize(1);
            assertThat(results.stream().flatMap(result -> result.conflicts().stream())).singleElement()
                    .satisfies(conflict -> assertThat(conflict.messageIdMatch()).isEqualTo(messageId.toString()));
        }
    }

    @Test
    void historyResumesAfterFailedWriteAndLostCommitConfirmation() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        var event = historyEvent(room, 1, UUID.randomUUID(), "只落库一次", Instant.now());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var firstFailure = new java.util.concurrent.CountDownLatch(1);
        var secondFailure = new java.util.concurrent.CountDownLatch(1);
        var allowWrite = new java.util.concurrent.CountDownLatch(1);
        var allowReplay = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            switch (attempts.incrementAndGet()) {
                case 1 -> {
                    firstFailure.countDown();
                    throw new DataAccessResourceFailureException("模拟写入失败");
                }
                case 2 -> {
                    assertThat(allowWrite.await(10, TimeUnit.SECONDS)).isTrue();
                    call.callRealMethod();
                    secondFailure.countDown();
                    throw new DataAccessResourceFailureException("模拟 SQL 已提交但 Kafka 未确认");
                }
                default -> {
                    assertThat(allowReplay.await(10, TimeUnit.SECONDS)).isTrue();
                    return call.callRealMethod();
                }
            }
        }).when(history).append(argThat(batch -> batch != null
                && batch.stream().anyMatch(record -> record.event().messageId().equals(event.messageId()))));
        try (var producer = historyProducer(); var admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            var position = publishHistory(producer, event);
            var topicPartition = new TopicPartition(position.topic(), position.partition());
            assertThat(firstFailure.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(committedHistoryOffset(admin, topicPartition)).isLessThanOrEqualTo(position.offset());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_messages WHERE room_id = ?", Integer.class, room))
                    .isZero();
            allowWrite.countDown();
            assertThat(secondFailure.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(committedHistoryOffset(admin, topicPartition)).isLessThanOrEqualTo(position.offset());
            allowReplay.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (committedHistoryOffset(admin, topicPartition) <= position.offset() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(committedHistoryOffset(admin, topicPartition)).isGreaterThan(position.offset());
            assertThat(attempts.get()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_messages WHERE room_id = ?", Integer.class, room))
                    .isOne();
        } finally {
            allowWrite.countDown();
            allowReplay.countDown();
        }
    }

    private Accepted historyEvent(String room, long sequence, UUID id, String content, Instant sentAt) {
        return new Accepted(1, room, Long.toString(sequence), id, Long.toString(userId), username, content,
                RoomEvents.UTC_MILLIS.format(sentAt));
    }

    private KafkaProducer<String, String> historyProducer() {
        return new KafkaProducer<>(kafkaAdmin.getConfigurationProperties(),
                new StringSerializer(), new StringSerializer());
    }

    private org.apache.kafka.clients.producer.RecordMetadata publishHistory(
            KafkaProducer<String, String> producer, Accepted event) throws Exception {
        return producer.send(new ProducerRecord<>(MESSAGE_TEST_NAMESPACE + ".liveflow.room-message.accepted.v1",
                event.roomId(), json.writeValueAsString(event))).get(5, TimeUnit.SECONDS);
    }

    private static String historyGroup() {
        return MESSAGE_TEST_NAMESPACE + ".liveflow-room-message-history-v1";
    }

    private static long committedHistoryOffset(Admin admin, TopicPartition partition) throws Exception {
        var offset = admin.listConsumerGroupOffsets(historyGroup()).partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS).get(partition);
        return offset == null ? -1 : offset.offset();
    }

    private static String matchingRoom(String reference, boolean samePartition) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while ((partition(id) == partition(reference)) != samePartition);
        return id;
    }

    private static int partition(String room) {
        return Utils.toPositive(Utils.murmur2(room.getBytes(StandardCharsets.UTF_8))) % 6;
    }

    @Test
    void retriesUnconfirmedRedisPublishWithTheSameMessageAndKeepsOtherRoomsIndependent() throws Exception {
        var browser = new Browser();
        String bearer = access(browser.login());
        String room = createRoomFor(browser, bearer);
        String channel = MESSAGE_TEST_NAMESPACE + ":liveflow:rooms:" + room + ":messages:v1";
        var publishCount = new java.util.concurrent.atomic.AtomicInteger();

        try (var socket = readyRoom(browser, bearer, room)) {
            doAnswer(invocation -> {
                // Redis 已实际发布。本地只丢失调用结果，消费者不能提交该批进度。
                Object receivers = invocation.callRealMethod();
                if (publishCount.incrementAndGet() == 1) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("模拟发布结果丢失");
                }
                return receivers;
            }).when(redis).convertAndSend(eq(channel), anyString());

            var submitted = browser.sendPath("POST", messagePath(room, "messages"),
                    messageBody(UUID.randomUUID().toString(), "同一正式弹幕"), bearer, true);
            assertThat(submitted.statusCode()).isEqualTo(202);
            var first = nextMessage(socket);
            assertThat(nextMessage(socket)).isEqualTo(first);
            assertThat(publishCount.get()).isEqualTo(2);
            var stored = awaitJson(browser, bearer, messagePath(room, "messages"),
                    page -> "1".equals(page.get("latestStoredSeq").asString()));
            assertThat(stored.get("items").size()).isOne();
        }
    }

    private JsonNode awaitJson(Browser browser, String access, String path,
            java.util.function.Predicate<JsonNode> condition) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        do {
            var value = roomResponse(browser.sendPath("GET", path, null, access, false), 200);
            if (condition.test(value)) return value;
            Thread.sleep(75);
        } while (System.nanoTime() < until);
        throw new AssertionError("投影在等待期限内未到达");
    }

    private JsonNode nextMessage(SocketClient client) throws Exception {
        for (int i = 0; i < 8; i++) {
            JsonNode frame = client.next();
            if ("message".equals(frame.get("type").asString())) return frame;
        }
        throw new AssertionError("没有收到消息帧");
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
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(cookies).containsKey(SESSION_CSRF);
            return json.readTree(response.body());
        }

        void csrf() throws Exception {
            assertThat(send("GET", "csrf?scope=pre-auth", null, null, false).statusCode()).isEqualTo(204);
            assertThat(cookies).containsKeys(PRE_SESSION, PRE_CSRF);
        }

        void sessionCsrf(String bearer) throws Exception {
            assertThat(send("GET", "csrf?scope=session", null, bearer, false).statusCode()).isEqualTo(204);
            assertThat(cookies).containsKey(SESSION_CSRF);
        }

        HttpResponse<String> send(String method, String path, String body, String bearer, boolean csrf)
                throws Exception {
            return sendPath(method, "/api/v1/auth/" + path, body, bearer, csrf);
        }

        HttpResponse<String> sendPath(String method, String path, String body, String bearer, boolean csrf)
                throws Exception {
            return sendPath(method, path, body, bearer, csrf, Map.of());
        }

        HttpResponse<String> sendPath(String method, String path, String body, String bearer, boolean csrf,
                Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            headers.forEach(request::header);
            if (!cookies.isEmpty()) {
                request.header("Cookie", String.join("; ", cookies.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).toList()));
            }
            if (csrf) {
                boolean anonymousWrite = path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register");
                String value = cookies.get(anonymousWrite ? PRE_CSRF : SESSION_CSRF);
                if (value != null) request.header("X-XSRF-TOKEN", value);
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
