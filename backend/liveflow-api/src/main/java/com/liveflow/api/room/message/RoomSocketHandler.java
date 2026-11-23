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

/**
 * 管理本机房间的 WebSocket 连接，从首帧兑换票据到最后释放连接名额。
 * 房间成员关系和队列入队受 {@code monitor} 保护。每条连接独占一个写线程，
 * Redis 复查和阻塞网络写入均在房间锁外执行。
 */
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
    // 待鉴权名额涵盖 PENDING 与 AUTHENTICATING。总连接名额一直保留到写线程退出。
    private int pending;
    private boolean stopped;

    /**
     * 初始化连接限制和严格的首帧解析器。
     * @param tickets 连接票据兑换服务
     * @param sessions 当前会话复查服务
     * @param json 应用 JSON 映射器
     * @param connectionLimit 本实例的连接数上限
     * @param pendingLimit 尚未完成首帧鉴权的连接数上限
     * @param queueCapacity 每条连接的待发送帧数上限
     * @param authSeconds 首帧及鉴权允许的秒数
     * @param recheckSeconds 当前会话复查间隔
     * @param writeMillis Tomcat 单次阻塞写入的毫秒上限
     * @throws IllegalArgumentException 任一容量或时间参数小于 1
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
            // 在锁内认领首帧，避免另一帧在兑换票据期间也进入鉴权。
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
                // Redis 往返期间连接可能超时或断开。只有仍处于鉴权状态的连接才能入房。
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

    /**
     * 关闭发送二进制应用帧的连接。房间的应用首帧使用文本 JSON。
     * @param socket 发送该帧的连接
     * @param frame 收到的二进制帧，其内容无需解析
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage frame) {
        disconnect(socket, CloseStatus.BAD_DATA);
    }

    /**
     * 记录就绪连接的协议级心跳响应。
     * @param socket 返回 Pong 的连接
     * @param frame 协议级 Pong 帧，其载荷无需读取
     */
    @Override
    protected void handlePongMessage(WebSocketSession socket, PongMessage frame) {
        synchronized (monitor) {
            Connection connection = connections.get(socket.getId());
            if (connection != null && connection.state == State.READY) {
                connection.awaitingPong = false;
            }
        }
    }

    /**
     * 传输失败时撤销连接的接收资格，并通知写线程收尾。
     * @param socket 传输失败的连接
     * @param failure 容器报告的异常，统一映射为服务端关闭状态
     */
    @Override
    public void handleTransportError(WebSocketSession socket, Throwable failure) {
        disconnect(socket, CloseStatus.SERVER_ERROR);
    }

    /**
     * 容器关闭回调也走同一个连接清理流程。
     * @param socket 已关闭的连接
     * @param status 容器报告的关闭状态
     */
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

    /**
     * 在持有房间锁时推进就绪连接的心跳和登录会话复查。
     * @param connection 当前就绪的连接
     * @param now 本轮调度的单调时钟读数，单位为纳秒
     */
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
        // checking 将每条连接的并发复查限制为一个，慢查询不会累积新的 Redis 调用。
        if (!connection.checking && now - connection.lastCheck >= recheckInterval) {
            connection.checking = true;
            Binding binding = connection.binding;
            Thread.startVirtualThread(() -> recheck(connection, binding));
        }
    }

    /**
     * 在房间锁外复查绑定的登录会话。无法确认时关闭接收连接。
     * @param connection 正在接受复查的连接
     * @param binding 首帧鉴权时确认的账号、会话与房间
     */
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

    /**
     * 在持有房间锁时尝试立即入队。容量耗尽时将该连接移出房间。
     * @param connection 目标接收连接
     * @param frame 要入队的广播或协议级心跳帧
     * @return 帧已入队时为 true，连接因队列满而被关闭时为 false
     */
    private boolean offer(Connection connection, WebSocketMessage<?> frame) {
        if (connection.outgoing.offer(new Output(frame, null))) {
            return true;
        }
        // 慢连接失去接收资格后解除房间占用，写线程随后在自己的超时范围内关闭。
        close(connection, CloseStatus.SERVICE_OVERLOAD, null);
        return false;
    }

    /**
     * 按登录失效或认证依赖故障选择关闭状态。
     * @param connection 鉴权或复查失败的连接
     * @param cause 包含对外认证错误码的领域异常
     */
    private void failAuth(Connection connection, AuthFailure cause) {
        CloseStatus status = cause.code() == AuthFailure.Code.AUTH_SERVICE_UNAVAILABLE
                ? CloseStatus.SERVER_ERROR : CloseStatus.POLICY_VIOLATION;
        fail(connection, cause.code().name(), cause.getMessage(), status);
    }

    /**
     * 排入错误帧和关闭指令，由此连接的写线程按序发送。
     * @param connection 要关闭的连接
     * @param code 客户端可识别的错误码
     * @param message 可展示的错误说明
     * @param status WebSocket 关闭状态
     */
    private void fail(Connection connection, String code, String message, CloseStatus status) {
        TextMessage frame = text(new ErrorFrame("error", code, message));
        synchronized (monitor) {
            close(connection, status, frame);
        }
    }

    /**
     * 定位尚在管理中的连接并请求关闭。重复回调可以安全到达。
     * @param socket 容器传入的连接
     * @param status 本次关闭原因
     */
    private void disconnect(WebSocketSession socket, CloseStatus status) {
        synchronized (monitor) {
            var connection = connections.get(socket.getId());
            if (connection != null) {
                close(connection, status, null);
            }
        }
    }

    /**
     * 在持有房间锁时撤销连接的接收资格。实际写入和关闭由写线程完成。
     * @param connection 待关闭的连接
     * @param status 在写线程退出时交给容器的关闭状态
     * @param lastFrame 关闭前可选的错误帧，允许为 null
     */
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
        // 关闭指令不应排在过时的广播后面。清空队列也为终止指令腾出空间。
        connection.outgoing.clear();
        connection.outgoing.add(new Output(lastFrame, status));
    }

    /**
     * 作为此连接唯一的写入者按队列顺序发帧，退出时释放总连接名额。
     * @param connection 当前写线程独占的网络连接
     */
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
                    // 只移除当前线程管理的实例，避免旧写线程清除同名的新记录。
                    connections.remove(connection.socket.getId(), connection);
                    connection.outgoing.clear();
                }
            }
        }
    }

    /**
     * 将出站协议对象编码为文本帧。
     * @param body 应用层发送内容
     * @return 供连接队列复用的 JSON 文本帧
     * @throws JacksonException 无法序列化出站对象
     */
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
