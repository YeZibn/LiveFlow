package com.liveflow.infrastructure.message;

import com.liveflow.core.room.message.RoomEvents;
import com.liveflow.core.room.message.RoomMessageFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoomKafkaRuntimeTest {
    private final RoomKafkaSettings settings = new RoomKafkaSettings(mock(KafkaAdmin.class), (short) 1, 1,
            "/tmp/example-streams", true, true, 10_000, 2_000);
    private final RoomMessageTopology topology = new RoomMessageTopology("test");
    private final RoomKafkaProducer producer = mock(RoomKafkaProducer.class);
    private final RoomKafkaStreamsRuntime streams = mock(RoomKafkaStreamsRuntime.class);
    private final RoomKafkaConsumerGroupRuntime consumers = mock(RoomKafkaConsumerGroupRuntime.class);
    private final RoomKafkaMaintenanceScheduler maintenance = mock(RoomKafkaMaintenanceScheduler.class);

    @Test
    void rollsBackPartialStartupAndKeepsTheOriginalFailure() throws Exception {
        var runtime = runtime();
        var startFailure = new IllegalStateException("Streams start failed");
        var closeFailure = new IllegalStateException("Producer close failed");
        doThrow(startFailure).when(streams).start();
        doThrow(closeFailure).when(producer).stop();

        var reported = assertThrows(IllegalStateException.class, runtime::start);
        assertThat(reported.getCause()).isSameAs(startFailure);
        assertThat(startFailure.getSuppressed()).containsExactly(closeFailure);
        assertThat(runtime.isRunning()).isFalse();
        var order = inOrder(runtime, producer, streams, consumers, maintenance);
        order.verify(runtime).configureTopics();
        order.verify(producer).prepare();
        order.verify(streams).prepare();
        order.verify(consumers).start();
        order.verify(streams).start();
        order.verify(producer).closeAdmission();
        order.verify(maintenance).stop();
        order.verify(streams).withdrawAdmission();
        order.verify(producer).stop();
        order.verify(streams).stop();
        order.verify(consumers).stop();
        verify(producer, never()).openAdmission();
    }

    @Test
    void stopsOtherComponentsWhenOneCloseFailsAndIgnoresRepeatedStop() throws Exception {
        var runtime = runtime();
        runtime.start();
        assertThat(runtime.isRunning()).isTrue();
        doThrow(new IllegalStateException("Streams close failed")).when(streams).stop();

        runtime.stop();
        runtime.stop();

        assertThat(runtime.isRunning()).isFalse();
        var order = inOrder(producer, streams, consumers, maintenance);
        order.verify(streams).start();
        order.verify(maintenance).start();
        order.verify(producer).openAdmission();
        order.verify(producer).closeAdmission();
        order.verify(maintenance).stop();
        order.verify(streams).withdrawAdmission();
        order.verify(producer).stop();
        order.verify(streams).stop();
        order.verify(consumers).stop();
        verify(consumers, times(1)).stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void holdsProducerPermitUntilActualConfirmationAndReleasesItOnSyncFailure() {
        var sender = new RoomKafkaProducer(settings, mock(RoomAdmissionStatus.class), topology,
                JsonMapper.builder().build(), new SimpleMeterRegistry());
        var callbacks = new ArrayList<Callback>();
        try (var construction = mockConstruction(KafkaProducer.class, (client, context) ->
                when(client.send(any(ProducerRecord.class), any(Callback.class)))
                        .thenThrow(new KafkaException("同步失败"))
                        .thenAnswer(call -> {
                            callbacks.add(call.getArgument(1));
                            return null;
                        }))) {
            sender.prepare();
            sender.openAdmission();
            var event = new RoomEvents.Submission(1, UUID.randomUUID(), UUID.randomUUID().toString(), "1",
                    UUID.randomUUID(), UUID.randomUUID().toString(), "测试", RoomEvents.hash("测试"),
                    "观众", RoomEvents.UTC_MILLIS.format(Instant.now()));
            assertThatThrownBy(() -> sender.publish(event).join()).isInstanceOf(CompletionException.class);
            var actual = sender.publish(event);
            var httpWait = actual.copy().orTimeout(Duration.ofMillis(1).toMillis(), TimeUnit.MILLISECONDS);
            assertThatThrownBy(httpWait::join).isInstanceOf(CompletionException.class);
            assertThat(actual).isNotDone();
            for (int i = 1; i < 256; i++) sender.publish(event);
            assertThat(callbacks).hasSize(256);
            assertThrows(RoomMessageFailure.class, () -> sender.publish(event));

            callbacks.getFirst().onCompletion(null, null);
            assertThat(actual).isCompleted();
            sender.publish(event);
            assertThat(callbacks).hasSize(257);
            sender.stop();
            verify(construction.constructed().getFirst()).close(Duration.ofSeconds(3));
        }
    }

    private RoomKafkaRuntime runtime() throws Exception {
        var runtime = spy(new RoomKafkaRuntime(settings, topology, producer, streams, consumers, maintenance));
        doNothing().when(runtime).configureTopics();
        return runtime;
    }
}
