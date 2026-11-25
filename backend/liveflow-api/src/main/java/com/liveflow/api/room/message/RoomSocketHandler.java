package com.liveflow.api.room.message;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.session.AuthSessionStore;
import com.liveflow.core.room.message.RoomEvents.Message;
import com.liveflow.core.room.message.RoomHistoryService;
import com.liveflow.core.room.message.RoomMessageBroadcast;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.service.RoomExistenceStore;
import com.liveflow.core.room.message.RoomTicketService;
import com.liveflow.core.room.message.RoomTicketStore.Binding;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.apache.tomcat.websocket.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在单房间锁中为订阅确认、ready 与广播排序。连接锁独立管理发送预算；
 * Redis、MySQL 和容器网络调用均在这两层锁之外。
 */
@Component
public class RoomSocketHandler extends AbstractWebSocketHandler implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(RoomSocketHandler.class);
    private static final long HEARTBEAT_INTERVAL = Duration.ofSeconds(20).toNanos();
    private static final long HEARTBEAT_TIMEOUT = Duration.ofSeconds(10).toNanos();
    private static final long SUBSCRIPTION_GRACE = Duration.ofSeconds(5).toNanos();
    private static final long QUEUE_AGE = Duration.ofSeconds(2).toNanos();
    private static final int QUEUE_BYTES = 128 * 1024;
    private static final int AUTH_FRAME_LIMIT = 1024;
    private final RoomTicketService tickets;
    private final AuthSessionStore sessions;
    private final RoomMessageBroadcast broadcast;
    private final RoomHistoryService history;
    private final JsonMapper json;
    private final ObjectReader reader;
    private final int queueCapacity;
    private final long authTimeout;
    private final long recheckInterval;
    private final long writeTimeoutMillis;
    private final Semaphore capacity;
    private final Semaphore unauthenticated;
    private final Counter slowConnections;
    private final Counter sentBytes;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    /**
     * @param tickets 一次性票据兑换
     * @param sessions WS 绑定的登录会话复查
     * @param broadcast Redis 房间订阅
     * @param history 房间历史水位查询
     * @param metrics 出站资源观测
     * @param json 协议 JSON 编解码器
     * @param connectionLimit 实例总连接上限
     * @param pendingLimit 待完成首帧鉴权的上限
     * @param queueCapacity 每连接待发送帧上限
     * @param authSeconds 从握手到订阅就绪的秒数
     * @param recheckSeconds 登录会话复查间隔
     * @param writeMillis 容器阻塞发送的毫秒上限
     */
    public RoomSocketHandler(RoomTicketService tickets, AuthSessionStore sessions, RoomMessageBroadcast broadcast,
            RoomHistoryService history, MeterRegistry metrics, JsonMapper json,
            @Value("${liveflow.room-messages.connection-limit:512}") int connectionLimit,
            @Value("${liveflow.room-messages.pending-limit:64}") int pendingLimit,
            @Value("${liveflow.room-messages.queue-capacity:64}") int queueCapacity,
            @Value("${liveflow.room-messages.auth-seconds:5}") long authSeconds,
            @Value("${liveflow.room-messages.recheck-seconds:15}") long recheckSeconds,
            @Value("${liveflow.room-messages.write-millis:5000}") long writeMillis) {
        if (connectionLimit < 1 || pendingLimit < 1 || queueCapacity < 1
                || authSeconds < 1 || recheckSeconds < 1 || writeMillis < 1) {
            throw new IllegalArgumentException("房间连接上限、队列容量和时间参数必须为正数");
        }
        this.tickets = tickets;
        this.sessions = sessions;
        this.broadcast = broadcast;
        this.history = history;
        this.json = json;
        this.reader = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.capacity = new Semaphore(connectionLimit);
        this.unauthenticated = new Semaphore(pendingLimit);
        this.slowConnections = metrics.counter("liveflow.room.messages.websocket.slow");
        this.sentBytes = metrics.counter("liveflow.room.messages.websocket.sent.bytes");
        this.queueCapacity = queueCapacity;
        this.authTimeout = Duration.ofSeconds(authSeconds).toNanos();
        this.recheckInterval = Duration.ofSeconds(recheckSeconds).toNanos();
        this.writeTimeoutMillis = writeMillis;
    }

    /**

     * @param socket 完成协议升级的连接

     * @throws IOException 过载连接关闭失败

     */
    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws IOException {
        if (stopped || !capacity.tryAcquire()) {
            socket.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        if (!unauthenticated.tryAcquire()) {
            capacity.release();
            socket.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        socket.setTextMessageSizeLimit(AUTH_FRAME_LIMIT);
        if (socket instanceof NativeWebSocketSession nativeSocket) {
            Session tomcat = nativeSocket.getNativeSession(Session.class);
            if (tomcat != null) tomcat.getUserProperties().put(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, writeTimeoutMillis);
        }
        var connection = new Connection(socket, queueCapacity);
        connections.put(socket.getId(), connection);
        if (stopped) close(connection, CloseStatus.GOING_AWAY, null);
        Thread.startVirtualThread(() -> writeLoop(connection));
    }

    /**

     * @param socket 发来首帧的连接

     * @param frame 唯一允许的应用层上行帧

     */
    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage frame) {
        var connection = connections.get(socket.getId());
        if (connection == null) return;
        synchronized (connection) {
            if (connection.state != State.PENDING || timedOut(connection)) {
                close(connection, CloseStatus.POLICY_VIOLATION, null);
                return;
            }
            connection.state = State.AUTHENTICATING;
        }
        try {
            JsonNode input = reader.readValue(frame.getPayload());
            if (input == null || !input.isObject() || input.size() != 2 || !input.path("type").isString()
                    || !"auth".equals(input.get("type").asString()) || !input.path("ticket").isString()) {
                throw new RoomMessageFailure(RoomMessageFailure.Code.INVALID_REQUEST);
            }
            Binding binding = tickets.redeem(input.get("ticket").asString());
            attach(connection, binding);
        } catch (RoomMessageFailure failure) {
            fail(connection, failure.code().name(), failure.getMessage(), CloseStatus.POLICY_VIOLATION);
        } catch (AuthFailure failure) {
            failAuth(connection, failure);
        } catch (JacksonException failure) {
            fail(connection, "INVALID_REQUEST", "连接消息格式无效", CloseStatus.POLICY_VIOLATION);
        } catch (RoomExistenceStore.Unavailable | DataAccessException | TransactionException failure) {
            fail(connection, "ROOM_MESSAGES_UNAVAILABLE", "弹幕服务暂不可用", CloseStatus.SERVER_ERROR);
        }
    }

    private void attach(Connection connection, Binding binding) {
        String id = binding.roomId();
        while (!stopped) {
            Room room = rooms.computeIfAbsent(id, Room::new);
            boolean start = false;
            synchronized (room) {
                if (rooms.get(id) != room) continue;
                if (room.retiring) {
                    // 同频道只有一个代次在 Redis 注册。先完成旧退订，再为新代次等待确认。
                    room.retired.thenRunAsync(() -> attach(connection, binding), Thread::startVirtualThread);
                    return;
                }
                synchronized (connection) {
                    if (connection.state != State.AUTHENTICATING || timedOut(connection)) {
                        close(connection, CloseStatus.POLICY_VIOLATION, null);
                        return;
                    }
                    connection.binding = binding;
                    room.members.add(connection);
                    room.emptySince = 0;
                    if (room.ready) ready(connection, id);
                    if (!room.subscribing) {
                        room.subscribing = true;
                        start = true;
                    }
                }
            }
            if (start) Thread.startVirtualThread(() -> subscribe(room));
            return;
        }
        close(connection, CloseStatus.GOING_AWAY, null);
    }

    private void subscribe(Room room) {
        try {
            Runnable cancel = broadcast.subscribe(room.id, message -> deliver(room, message),
                    () -> subscribed(room), () -> disconnected(room));
            synchronized (room) {
                if (rooms.get(room.id) == room && !stopped) {
                    room.cancel = cancel;
                    return;
                }
            }
            cancel.run();
        } catch (RuntimeException failure) {
            synchronized (room) {
                room.ready = false;
                room.subscribing = false;
                room.members.forEach(member -> fail(member, "ROOM_MESSAGES_UNAVAILABLE",
                        "弹幕订阅暂不可用", CloseStatus.SERVER_ERROR));
            }
        }
    }

    private void disconnected(Room room) {
        synchronized (room) {
            if (rooms.get(room.id) == room && !room.retiring) room.ready = false;
        }
    }

    private void subscribed(Room room) {
        synchronized (room) {
            if (rooms.get(room.id) != room || room.retiring || stopped) return;
            room.ready = true;
            // 已就绪连接收到恢复提示；首次确认时，ready 一定排在同房间的消息帧前。
            TextMessage resync = text(new RoomFrame("resync_required", room.id));
            for (var member : room.members) {
                synchronized (member) {
                    if (member.state == State.READY) offer(member, resync);
                    else ready(member, room.id);
                }
            }
        }
    }

    private void ready(Connection connection, String roomId) {
        if (connection.state != State.AUTHENTICATING) return;
        if (timedOut(connection)) {
            close(connection, CloseStatus.POLICY_VIOLATION, null);
            return;
        }
        if (offer(connection, text(new RoomFrame("ready", roomId)))) {
            connection.state = State.READY;
            connection.lastPing = connection.lastCheck = System.nanoTime();
            unauthenticated.release();
        }
    }

    private void deliver(Room room, Message message) {
        var frame = text(new MessageFrame("message", message.roomSeq(), message.messageId(),
                message.senderName(), message.content(), message.sentAt()));
        synchronized (room) {
            if (rooms.get(room.id) != room || room.retiring || !room.ready || stopped) return;
            for (var member : room.members) {
                synchronized (member) {
                    if (member.state == State.READY) offer(member, frame);
                }
            }
        }
    }

    /**

     * @param socket 当前连接

     * @param frame 客户端的协议心跳回复

     */
    @Override
    protected void handlePongMessage(WebSocketSession socket, PongMessage frame) {
        var connection = connections.get(socket.getId());
        if (connection == null) return;
        synchronized (connection) {
            connection.awaitingPong = false;
        }
    }

    /**

     * @param socket 违规连接

     * @param frame 无需解析的二进制帧

     */
    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage frame) {
        disconnect(socket, CloseStatus.BAD_DATA);
    }

    /**

     * @param socket 传输失败的连接

     * @param failure 容器异常

     */
    @Override
    public void handleTransportError(WebSocketSession socket, Throwable failure) {
        disconnect(socket, CloseStatus.SERVER_ERROR);
    }

    /**

     * @param socket 已关闭的连接

     * @param status 容器关闭状态

     */
    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        disconnect(socket, status);
    }

    /** 驱动首帧期限、心跳、会话复查及空房间退订。 */
    @Scheduled(fixedDelay = 250)
    public void maintain() {
        long now = System.nanoTime();
        for (var connection : connections.values()) {
            synchronized (connection) {
                if (connection.state == State.READY) maintainReady(connection, now);
                else if (timedOut(connection)) close(connection, CloseStatus.POLICY_VIOLATION, null);
            }
        }
        for (Room room : rooms.values()) {
            Runnable cancel;
            synchronized (room) {
                room.members.removeIf(member -> member.state == State.CLOSED);
                if (room.retiring || !room.members.isEmpty()) continue;
                if (room.emptySince == 0) room.emptySince = now;
                if (now - room.emptySince < SUBSCRIPTION_GRACE || room.subscribing && room.cancel == null) continue;
                room.retiring = true;
                cancel = room.cancel;
            }
            if (cancel != null) cancelSubscription(cancel);
            rooms.remove(room.id, room);
            room.retired.complete(null);
        }
    }

    /** 批读活跃房间的最大落库序号，帮助连接发现漏收的新消息。 */
    @Scheduled(fixedDelay = 3000)
    public void notifyHistoryProgress() {
        var active = new ArrayList<Room>();
        for (Room room : rooms.values()) {
            synchronized (room) {
                if (room.ready && !room.members.isEmpty()) active.add(room);
            }
        }
        for (int start = 0; start < active.size(); start += 100) {
            List<String> ids = active.subList(start, Math.min(start + 100, active.size())).stream().map(r -> r.id).toList();
            try {
                for (var progress : history.progress(ids)) {
                    var room = rooms.get(progress.roomId());
                    if (room == null) continue;
                    synchronized (room) {
                        long latest = progress.latestStoredSeq();
                        var frame = text(new HistoryFrame("history_progress", Long.toString(latest)));
                        for (var member : room.members) {
                            synchronized (member) {
                                // 仅在成功入队后更新该连接的通知进度，迟到补写不会重复通知。
                                if (member.state == State.READY && latest > member.lastHistoryProgress
                                        && offer(member, frame)) member.lastHistoryProgress = latest;
                            }
                        }
                    }
                }
            } catch (RuntimeException failure) {
                LOG.debug("弹幕历史进度将在下一轮重查 failure={}", failure.getClass().getSimpleName());
            }
        }
    }

    /** @return 先等待容器排空 HTTP，再关闭连接，最后停止 Kafka。 */
    @Override
    public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1;
    }

    /** 允许接收房间连接。 */
    @Override
    public void start() {
        stopped = false;
    }

    /** @return 当前实例尚未进入连接关闭阶段 */
    @Override
    public boolean isRunning() {
        return !stopped;
    }

    /** 停止接受连接，撤销订阅并逐条关闭已有连接。 */
    @Override
    public void stop() {
        stopped = true;
        connections.values().forEach(connection -> close(connection, CloseStatus.GOING_AWAY, null));
        for (Room room : rooms.values()) {
            Runnable cancel;
            synchronized (room) {
                rooms.remove(room.id, room);
                room.retired.complete(null);
                cancel = room.cancel;
            }
            if (cancel != null) cancelSubscription(cancel);
        }
    }

    private void maintainReady(Connection connection, long now) {
        if (connection.awaitingPong && now - connection.lastPing >= HEARTBEAT_TIMEOUT) {
            close(connection, CloseStatus.SESSION_NOT_RELIABLE, null);
        } else if (!connection.awaitingPong && now - connection.lastPing >= HEARTBEAT_INTERVAL) {
            connection.lastPing = now;
            connection.awaitingPong = true;
            if (!offer(connection, new PingMessage())) return;
        }
        if (connection.state == State.READY && !connection.checking && now - connection.lastCheck >= recheckInterval) {
            connection.checking = true;
            Thread.startVirtualThread(() -> recheck(connection));
        }
    }

    private void recheck(Connection connection) {
        Binding binding = connection.binding;
        try {
            sessions.requireCurrent(binding.userId(), binding.sessionId());
        } catch (AuthFailure failure) {
            failAuth(connection, failure);
        } catch (RuntimeException failure) {
            fail(connection, "AUTH_SERVICE_UNAVAILABLE", "认证服务暂不可用", CloseStatus.SERVER_ERROR);
        } finally {
            synchronized (connection) {
                connection.lastCheck = System.nanoTime();
                connection.checking = false;
            }
        }
    }

    // 调用者持有连接锁。入队失败只关闭该连接，不等待网络。
    private boolean offer(Connection connection, WebSocketMessage<?> frame) {
        var output = new Output(frame, null, System.nanoTime(), frame.getPayloadLength());
        if (connection.state == State.CLOSED) return false;
        if (connection.bytes + output.bytes() > QUEUE_BYTES || !connection.outgoing.offer(output)) {
            slowConnections.increment();
            close(connection, CloseStatus.SERVICE_OVERLOAD, null);
            return false;
        }
        connection.bytes += output.bytes();
        return true;
    }

    private void failAuth(Connection connection, AuthFailure failure) {
        CloseStatus status = failure.code() == AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE
                ? CloseStatus.SERVER_ERROR : CloseStatus.POLICY_VIOLATION;
        fail(connection, failure.code().name(), failure.getMessage(), status);
    }

    private void fail(Connection connection, String code, String message, CloseStatus status) {
        close(connection, status, text(new ErrorFrame("error", code, message)));
    }

    private void disconnect(WebSocketSession socket, CloseStatus status) {
        var connection = connections.get(socket.getId());
        if (connection != null) close(connection, status, null);
    }

    private void close(Connection connection, CloseStatus status, TextMessage frame) {
        synchronized (connection) {
            if (connection.state == State.CLOSED) return;
            if (connection.state != State.READY) unauthenticated.release();
            connection.state = State.CLOSED;
            connection.outgoing.clear();
            connection.bytes = 0;
            connection.outgoing.add(new Output(frame, status, System.nanoTime(), 0));
        }
    }

    private void writeLoop(Connection connection) {
        CloseStatus status = CloseStatus.SERVER_ERROR;
        try {
            while (true) {
                var output = connection.outgoing.take();
                synchronized (connection) {
                    if (connection.state == State.CLOSED && output.closeStatus() == null) continue;
                    connection.bytes -= output.bytes();
                }
                if (output.closeStatus() == null && System.nanoTime() - output.queuedAt() > QUEUE_AGE) {
                    slowConnections.increment();
                    status = CloseStatus.SERVICE_OVERLOAD;
                    break;
                }
                if (output.frame() != null && connection.socket.isOpen()) {
                    connection.socket.sendMessage(output.frame());
                    if (output.bytes() > 0) sentBytes.increment(output.bytes());
                }
                if (output.closeStatus() != null) {
                    status = output.closeStatus();
                    break;
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.debug("房间连接写入结束 id={}", connection.socket.getId());
        } finally {
            close(connection, status, null);
            try {
                if (connection.socket.isOpen()) connection.socket.close(status);
            } catch (IOException | RuntimeException failure) {
                LOG.debug("房间连接关闭失败 id={}", connection.socket.getId());
            } finally {
                connections.remove(connection.socket.getId(), connection);
                capacity.release();
            }
        }
    }

    private boolean timedOut(Connection connection) {
        return System.nanoTime() - connection.openedAt >= authTimeout;
    }

    private TextMessage text(Object body) {
        return new TextMessage(json.writeValueAsBytes(body));
    }

    private static void cancelSubscription(Runnable cancel) {
        try {
            cancel.run();
        } catch (RuntimeException failure) {
            LOG.debug("房间退订未确认 failure={}", failure.getClass().getSimpleName());
        }
    }

    private enum State { PENDING, AUTHENTICATING, READY, CLOSED }

    /** Room 实例本身作为订阅代次。旧订阅的延迟回调会被实例身份校验过滤。 */
    private static final class Room {
        final String id;
        final Set<Connection> members = new HashSet<>();
        final CompletableFuture<Void> retired = new CompletableFuture<>();
        boolean ready;
        boolean subscribing;
        boolean retiring;
        long emptySince;
        Runnable cancel;

        Room(String id) { this.id = id; }
    }

    private static final class Connection {
        final WebSocketSession socket;
        final ArrayBlockingQueue<Output> outgoing;
        final long openedAt = System.nanoTime();
        volatile State state = State.PENDING;
        Binding binding;
        long lastCheck;
        long lastPing;
        long lastHistoryProgress;
        boolean awaitingPong;
        boolean checking;
        int bytes;

        Connection(WebSocketSession socket, int capacity) {
            this.socket = socket;
            this.outgoing = new ArrayBlockingQueue<>(capacity);
        }
    }

    private record Output(WebSocketMessage<?> frame, CloseStatus closeStatus, long queuedAt, int bytes) {}
    private record RoomFrame(String type, String roomId) {}
    private record HistoryFrame(String type, String latestStoredSeq) {}
    private record MessageFrame(String type, String roomSeq, java.util.UUID messageId, String senderName,
            String content, String sentAt) {}
    private record ErrorFrame(String type, String code, String message) {}
}
