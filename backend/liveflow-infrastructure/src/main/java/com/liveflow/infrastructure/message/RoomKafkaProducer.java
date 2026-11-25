package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomEvents.Submission;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomMessagePorts.Producer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import static com.liveflow.core.resilience.RoomMessageCircuitBreaker.Dependency.KAFKA;
import static com.liveflow.core.room.message.RoomMessageFailure.Code.*;

/** 持有提交生产者，将实际 Kafka 确认交回发送业务。 */
@Component
public final class RoomKafkaProducer implements Producer {
    private final RoomKafkaSettings settings;
    private final RoomAdmissionStatus admission;
    private final String topic;
    private final JsonMapper json;
    private final Timer enqueue;
    private final Semaphore available = new Semaphore(256);
    private volatile KafkaProducer<String, String> producer;
    private volatile boolean running;

    RoomKafkaProducer(RoomKafkaSettings settings, RoomAdmissionStatus admission, RoomMessageTopology topology,
            JsonMapper json, MeterRegistry metrics) {
        this.settings = settings;
        this.admission = admission;
        this.topic = topology.topic("submissions");
        this.json = json;
        enqueue = RoomKafkaMetrics.stage(metrics, "enqueue");
    }

    /** 准备客户端，全部组件启动后才开放发送。 */
    void prepare() {
        Map<String, Object> producerConfig = new HashMap<>(settings.base);
        producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        producerConfig.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
        producerConfig.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2500);
        producer = new KafkaProducer<>(producerConfig, new StringSerializer(), new StringSerializer());
    }

    void openAdmission() {
        running = true;
    }

    void closeAdmission() {
        running = false;
    }

    void stop() {
        closeAdmission();
        var client = producer;
        producer = null;
        if (client != null) client.close(Duration.ofSeconds(3));
    }

    /**
     * 将本次预约交给生产者一次，并在实际确认结束时归还生产并发额度。
     * @param submission 已获得 Redis 单次发布许可的提交
     * @return Broker 写入确认，结果未知时异常完成
     * @throws RoomMessageFailure 本机拒绝或 Kafka 接入不可用
     */
    @Override
    public CompletableFuture<Void> publish(Submission submission) {
        KafkaProducer<String, String> current = producer;
        if (!settings.enabled || !settings.accepting) {
            throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        }
        if (!running || current == null) {
            throw RoomMessageFailure.dependency(ROOM_MESSAGES_UNAVAILABLE, KAFKA, null);
        }
        if (!available.tryAcquire()) throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        var result = new CompletableFuture<Void>();
        // 同步异常和异步回调共用一次完成边界，避免提前归还或者重复归还生产额度。
        var completion = result.whenComplete((ignored, failure) -> available.release());
        long started = System.nanoTime();
        try {
            String payload = json.writeValueAsString(submission);
            current.send(new ProducerRecord<>(topic, RoomMessageTopology.partition(submission.roomId()),
                    submission.roomId(), payload), (metadata, failure) -> {
                if (failure == null) {
                    enqueue.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    result.complete(null);
                } else {
                    result.completeExceptionally(RoomMessageFailure.dependency(MESSAGE_SUBMISSION_UNKNOWN, KAFKA, failure));
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(switch (failure) {
                case KafkaException kafkaFailure -> RoomMessageFailure.dependency(MESSAGE_SUBMISSION_UNKNOWN, KAFKA, kafkaFailure);
                case IllegalStateException closed -> RoomMessageFailure.dependency(ROOM_MESSAGES_UNAVAILABLE, KAFKA, closed);
                default -> new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
            });
        }
        return completion;
    }

    /** @param roomId 目标房间
     * @throws RoomMessageFailure 本机停写或目标分区暂不可接入
     */
    @Override
    public void requireAdmission(String roomId) {
        if (!running || !settings.enabled || !settings.accepting || producer == null) {
            throw new RoomMessageFailure(ROOM_MESSAGES_UNAVAILABLE);
        }
        admission.require(RoomMessageTopology.partition(roomId));
    }

}
