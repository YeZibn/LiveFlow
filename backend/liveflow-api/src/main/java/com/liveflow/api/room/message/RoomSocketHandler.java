package com.liveflow.api.room.message;

import com.liveflow.core.auth.AuthFailure;
import com.liveflow.core.auth.session.AuthSessionStore;
import com.liveflow.core.room.message.RoomMessageBroadcast;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomTicketService;
import com.liveflow.core.room.message.RoomTicketStore.Binding;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import org.apache.tomcat.websocket.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
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

/** 管理本机房间接收连接。房间入队与每条连接的网络写入分离。 */
@Component
public class RoomSocketHandler extends AbstractWebSocketHandler implements RoomMessageBroadcast {

    private static final Logger LOG = LoggerFactory.getLogger(RoomSocketHandler.class);
    private static final long HEARTBEAT_INTERVAL = Duration.ofSeconds(20).toNanos();
    private static final long HEARTBEAT_TIMEOUT = Duration.ofSeconds(10).toNanos();
    private static final int AUTH_FRAME_LIMIT = 1024;

    private final RoomTicketService tickets;
    private final AuthSessionStore sessions;
    private final JsonMapper json;
    private final ObjectReader reader;
    private final int connectionLimit;
    private final int pendingLimit;
    private final int queueCapacity;
    private final long authTimeout;
    private final long recheckInterval;
    private final long writeTimeoutMillis;

    // 同一把锁为 ready、入房、广播和注销排序。网络调用均在锁外。
    private final Object monitor = new Object();
    private final Map<String, Connection> connections = new HashMap<>();
    private final Map<String, Set<Connection>> rooms = new HashMap<>();
    private int pending;
    private boolean stopped;

    /**
     * @param tickets 连接票据兑换服务
     * @param sessions 当前会话复查服务
     * @param json 应用 JSON 映射器
     * @param connectionLimit 本实例的连接数上限
     * @param pendingLimit 尚未完成首帧鉴权的连接数上限
     * @param queueCapacity 每条连接的待发送帧数上限
     * @param authSeconds 首帧及鉴权允许的秒数
     * @param recheckSeconds 当前会话复查间隔
     * @param writeMillis Tomcat 单次阻塞写入的毫秒上限
     */
    public RoomSocketHandler(RoomTicketService tickets, AuthSessionStore sessions, JsonMapper json,
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
        this.json = json;
        this.reader = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.connectionLimit = connectionLimit;
        this.pendingLimit = pendingLimit;
        this.queueCapacity = queueCapacity;
        this.authTimeout = Duration.ofSeconds(authSeconds).toNanos();
        this.recheckInterval = Duration.ofSeconds(recheckSeconds).toNanos();
        this.writeTimeoutMillis = writeMillis;
    }

    /**
     * 接受升级后的连接，限制未经鉴权的占用并启动该连接自己的串行写入。
     * @param socket 已完成协议升级的连接
     * @throws IOException 拒绝连接时关闭失败
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws IOException {
        socket.setTextMessageSizeLimit(AUTH_FRAME_LIMIT);
        if (socket instanceof NativeWebSocketSession nativeSocket) {
            Session tomcat = nativeSocket.getNativeSession(Session.class);
            if (tomcat != null) {
                // 同步 RemoteEndpoint 写入可能阻塞。由容器超时结束单次写入。
                tomcat.getUserProperties().put(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, writeTimeoutMillis);
            }
        }

        Connection connection;
        synchronized (monitor) {
            connection = stopped || pending >= pendingLimit || connections.size() >= connectionLimit
                    ? null : new Connection(socket, queueCapacity);
            if (connection != null) {
                connections.put(socket.getId(), connection);
                pending++;
            }
        }
        if (connection == null) {
            socket.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        Thread.startVirtualThread(() -> writeLoop(connection));
    }

    /**
     * 消费第一条应用帧，验票成功后将连接加入票据指定的房间。
     * @param socket 发送帧的连接
     * @param frame 待解析的首帧
     */
    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage frame) {
        Connection connection;
        synchronized (monitor) {
            connection = connections.get(socket.getId());
            if (connection == null || connection.state == State.CLOSED) {
                return;
            }
            if (connection.state != State.PENDING || System.nanoTime() - connection.openedAt >= authTimeout) {
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
            var binding = tickets.redeem(input.get("ticket").asString());
            var ready = text(new ReadyFrame("ready", binding.roomId()));
            synchronized (monitor) {
                if (connection.state != State.AUTHENTICATING) {
                    return;
                }
                if (System.nanoTime() - connection.openedAt >= authTimeout) {
                    close(connection, CloseStatus.POLICY_VIOLATION, null);
                    return;
                }
                // 先排入 ready，再把连接开放给广播。两步在同一临界区完成。
                connection.outgoing.add(new Output(ready, null));
                connection.binding = binding;
                connection.state = State.READY;
                connection.lastPing = connection.lastCheck = System.nanoTime();
                pending--;
                rooms.computeIfAbsent(binding.roomId(), ignored -> new HashSet<>()).add(connection);
            }
        } catch (RoomMessageFailure cause) {
            fail(connection, cause.code().name(), cause.getMessage(), CloseStatus.POLICY_VIOLATION);
        } catch (AuthFailure cause) {
            failAuth(connection, cause);
        } catch (JacksonException cause) {
            fail(connection, "INVALID_REQUEST", "连接消息格式无效", CloseStatus.POLICY_VIOLATION);
        } catch (DataAccessException cause) {
            fail(connection, "ROOM_MESSAGES_UNAVAILABLE", "弹幕服务暂不可用", CloseStatus.SERVER_ERROR);
        }
    }

    /** @param socket 发送不受支持的二进制帧的连接 */
    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage frame) {
        disconnect(socket, CloseStatus.BAD_DATA);
    }

    /** @param socket 返回协议级心跳的连接 */
    @Override
    protected void handlePongMessage(WebSocketSession socket, PongMessage frame) {
        synchronized (monitor) {
            Connection connection = connections.get(socket.getId());
            if (connection != null && connection.state == State.READY) {
                connection.awaitingPong = false;
            }
        }
    }

    /** @param socket 传输失败的连接 */
    @Override
    public void handleTransportError(WebSocketSession socket, Throwable failure) {
        disconnect(socket, CloseStatus.SERVER_ERROR);
    }

    /** @param socket 已经关闭的连接 */
    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        disconnect(socket, status);
    }

    /**
     * 向当前房间所有已就绪连接入队，独立处理各连接的容量。
     * @param message 业务层已裁决的消息
     * @throws RoomMessageFailure 服务停止，消息尚未入队
     */
    @Override
    public void enqueue(Message message) {
        var frame = text(new MessageFrame("message", message.messageId(), message.senderName(), message.content(),
                RoomMessageController.UTC_MILLIS.format(message.sentAt())));
        synchronized (monitor) {
            if (stopped) {
                throw new RoomMessageFailure(RoomMessageFailure.Code.ROOM_MESSAGES_UNAVAILABLE);
            }
            var members = rooms.get(message.roomId());
            if (members != null) {
                // 关闭慢连接会从原集合移除，遍历快照保持本次的投递对象稳定。
                for (Connection connection : List.copyOf(members)) {
                    offer(connection, frame);
                }
            }
        }
    }

    /** 驱动首帧期限、心跳和会话复查，Redis 查询由连接级任务执行。 */
    @Scheduled(fixedDelay = 250)
    public void maintain() {
        synchronized (monitor) {
            long now = System.nanoTime();
            for (Connection connection : connections.values()) {
                if (connection.state == State.PENDING || connection.state == State.AUTHENTICATING) {
                    if (now - connection.openedAt >= authTimeout) {
                        close(connection, CloseStatus.POLICY_VIOLATION, null);
                    }
                } else if (connection.state == State.READY) {
                    maintainReady(connection, now);
                }
            }
        }
    }

    /** 拒绝后续受理，终止本实例的所有接收连接。 */
    @PreDestroy
    public void stop() {
        synchronized (monitor) {
            stopped = true;
            connections.values().forEach(connection -> close(connection, CloseStatus.GOING_AWAY, null));
        }
    }

    private void maintainReady(Connection connection, long now) {
        if (connection.awaitingPong && now - connection.lastPing >= HEARTBEAT_TIMEOUT) {
            close(connection, CloseStatus.SESSION_NOT_RELIABLE, null);
            return;
        }
        if (!connection.awaitingPong && now - connection.lastPing >= HEARTBEAT_INTERVAL) {
            connection.lastPing = now;
            connection.awaitingPong = true;
            if (!offer(connection, new PingMessage())) {
                return;
            }
        }
        if (!connection.checking && now - connection.lastCheck >= recheckInterval) {
            connection.checking = true;
            Binding binding = connection.binding;
            Thread.startVirtualThread(() -> recheck(connection, binding));
        }
    }

    private void recheck(Connection connection, Binding binding) {
        try {
            sessions.requireCurrent(binding.userId(), binding.sessionId());
        } catch (AuthFailure cause) {
            failAuth(connection, cause);
        } catch (RuntimeException failure) {
            fail(connection, "AUTH_SERVICE_UNAVAILABLE", "认证服务暂不可用", CloseStatus.SERVER_ERROR);
        } finally {
            synchronized (monitor) {
                connection.lastCheck = System.nanoTime();
                connection.checking = false;
            }
        }
    }

    private boolean offer(Connection connection, WebSocketMessage<?> frame) {
        if (connection.outgoing.offer(new Output(frame, null))) {
            return true;
        }
        // 慢连接失去接收资格后解除房间占用，写线程随后在自己的超时范围内关闭。
        close(connection, CloseStatus.SERVICE_OVERLOAD, null);
        return false;
    }

    private void failAuth(Connection connection, AuthFailure cause) {
        CloseStatus status = cause.code() == AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE
                ? CloseStatus.SERVER_ERROR : CloseStatus.POLICY_VIOLATION;
        fail(connection, cause.code().name(), cause.getMessage(), status);
    }

    private void fail(Connection connection, String code, String message, CloseStatus status) {
        TextMessage frame = text(new ErrorFrame("error", code, message));
        synchronized (monitor) {
            close(connection, status, frame);
        }
    }

    private void disconnect(WebSocketSession socket, CloseStatus status) {
        synchronized (monitor) {
            var connection = connections.get(socket.getId());
            if (connection != null) {
                close(connection, status, null);
            }
        }
    }

    private void close(Connection connection, CloseStatus status, TextMessage lastFrame) {
        if (connection.state == State.CLOSED) {
            return;
        }
        if (connection.state == State.READY) {
            String roomId = connection.binding.roomId();
            Set<Connection> members = rooms.get(roomId);
            members.remove(connection);
            if (members.isEmpty()) {
                rooms.remove(roomId);
            }
        } else {
            pending--;
        }
        connection.state = State.CLOSED;
        connection.outgoing.clear();
        connection.outgoing.add(new Output(lastFrame, status));
    }

    private void writeLoop(Connection connection) {
        CloseStatus status = CloseStatus.SERVER_ERROR;
        try {
            while (true) {
                var output = connection.outgoing.take();
                if (output.frame() != null && connection.socket.isOpen()) {
                    connection.socket.sendMessage(output.frame());
                }
                if (output.closeStatus() != null) {
                    status = output.closeStatus();
                    break;
                }
            }
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.debug("房间连接写入结束，连接 ID {}", connection.socket.getId());
        } finally {
            synchronized (monitor) {
                close(connection, status, null);
            }
            try {
                if (connection.socket.isOpen()) {
                    connection.socket.close(status);
                }
            } catch (IOException failure) {
                LOG.debug("房间连接关闭失败，连接 ID {}", connection.socket.getId());
            } finally {
                synchronized (monitor) {
                    connections.remove(connection.socket.getId(), connection);
                    connection.outgoing.clear();
                }
            }
        }
    }

    private TextMessage text(Object body) {
        return new TextMessage(json.writeValueAsString(body));
    }

    private enum State { PENDING, AUTHENTICATING, READY, CLOSED }

    private static final class Connection {
        final WebSocketSession socket;
        final ArrayBlockingQueue<Output> outgoing;
        final long openedAt = System.nanoTime();
        State state = State.PENDING;
        Binding binding;
        long lastCheck;
        long lastPing;
        boolean awaitingPong;
        boolean checking;

        Connection(WebSocketSession socket, int capacity) {
            this.socket = socket;
            this.outgoing = new ArrayBlockingQueue<>(capacity);
        }
    }

    private record Output(WebSocketMessage<?> frame, CloseStatus closeStatus) {}
    private record ReadyFrame(String type, String roomId) {}
    private record MessageFrame(String type, String messageId, String senderName, String content, String sentAt) {}
    private record ErrorFrame(String type, String code, String message) {}
}
