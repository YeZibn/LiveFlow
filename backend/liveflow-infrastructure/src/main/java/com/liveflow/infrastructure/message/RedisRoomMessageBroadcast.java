package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomEvents;
import com.liveflow.core.room.message.RoomMessageBroadcast;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 使用独立 Redis 订阅连接接收本实例有观众的房间事件。 */
@Component
public final class RedisRoomMessageBroadcast implements RoomMessageBroadcast {
    private static final Logger LOG = LoggerFactory.getLogger(RedisRoomMessageBroadcast.class);
    private final RedisMessageState state;
    private final RedisMessageListenerContainer container;
    private final RoomEventCodec codec;
    private final Object registration = new Object();
    private final RedisConnectionFactory factory;
    private final Set<Runnable> activeDisconnects = ConcurrentHashMap.newKeySet();
    private AbstractRedisClient nativeClient;
    private final RedisConnectionStateListener transport = new RedisConnectionStateListener() {
        @Override
        public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
            // 普通命令连接也经过同一客户端。只有订阅连接断开才撤销房间的实时就绪标志。
            if (connection instanceof StatefulRedisPubSubConnection<?, ?>
                    || connection instanceof StatefulRedisClusterPubSubConnection<?, ?>) {
                activeDisconnects.forEach(Runnable::run);
            }
        }
    };

    /**

     * @param state 房间频道命名

     * @param container 独立订阅容器

     * @param json Kafka/Redis 事件编解码器

     */
    public RedisRoomMessageBroadcast(RedisMessageState state,
            @Qualifier("roomMessageListeners") RedisMessageListenerContainer container, RedisConnectionFactory factory,
            JsonMapper json) {
        this.state = state;
        this.container = container;
        this.factory = factory;
        this.codec = new RoomEventCodec(json);
    }

    /** 监听驱动层连接状态，订阅 ACK 仍由频道监听器处理。 */
    @PostConstruct
    void watchConnection() {
        if (factory instanceof LettuceConnectionFactory lettuce) {
            nativeClient = lettuce.getRequiredNativeClient();
            nativeClient.addListener(transport);
        }
    }

    /** 应用关闭时释放驱动回调，避免留下订阅代次的引用。 */
    @PreDestroy
    void unwatchConnection() {
        if (nativeClient != null) nativeClient.removeListener(transport);
        activeDisconnects.clear();
    }

    @Override
    public Runnable subscribe(String roomId, Consumer<RoomEvents.Message> messages, Runnable subscribed,
            Runnable disconnected) {
        var topic = new ChannelTopic(state.channel(roomId));
        class Listener implements MessageListener, SubscriptionListener {
            @Override
            public void onMessage(Message message, byte[] pattern) {
                try {
                    messages.accept(codec.decodeLive(new String(message.getBody(), StandardCharsets.UTF_8)));
                } catch (RuntimeException failure) {
                    LOG.warn("实时弹幕解析或调度失败 failure={}", failure.getClass().getSimpleName());
                }
            }

            @Override
            public void onChannelSubscribed(byte[] channel, long subscriptionCount) {
                if (matches(channel)) subscribed.run();
            }

            @Override
            public void onChannelUnsubscribed(byte[] channel, long subscriptionCount) {
                if (matches(channel)) disconnected.run();
            }

            private boolean matches(byte[] channel) {
                return topic.getTopic().equals(new String(channel, StandardCharsets.UTF_8));
            }
        }
        var listener = new Listener();
        synchronized (registration) {
            // 容器启动首个订阅时也会建立连接，动态频道注册与撤销按同一顺序执行。
            activeDisconnects.add(disconnected);
            try {
                container.addMessageListener(listener, topic);
            } catch (RuntimeException failure) {
                activeDisconnects.remove(disconnected);
                container.removeMessageListener(listener, topic);
                throw failure;
            }
        }
        return () -> {
            synchronized (registration) {
                activeDisconnects.remove(disconnected);
                container.removeMessageListener(listener, topic);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class SubscriptionConfiguration {
        /**
         * @param factory 应用 Redis 连接工厂
         * @return 弹幕专用订阅容器
         */
        @Bean
        RedisMessageListenerContainer roomMessageListeners(RedisConnectionFactory factory) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            // 订阅回调只做本地入队。内联执行维持同一 Redis 连接上的帧顺序。
            container.setTaskExecutor(Runnable::run);
            container.setSubscriptionExecutor(task -> Thread.ofPlatform().daemon().name("room-redis-subscription").start(task));
            return container;
        }
    }
}
