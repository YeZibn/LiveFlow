package com.liveflow.core.room.message;

import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.profile.service.UserProfileService.Profile;
import com.liveflow.core.resilience.RoomMessageCircuitBreaker;
import com.liveflow.core.room.message.RoomEvents.Queued;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomEvents.Submission;
import com.liveflow.core.room.message.RoomMessagePorts.Admission;
import com.liveflow.core.room.message.RoomMessagePorts.Producer;
import com.liveflow.core.room.message.RoomMessagePorts.Reservation;
import com.liveflow.core.room.service.RoomService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

class RoomMessageServiceTest {
    private final RoomService rooms = mock(RoomService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final Admission admission = mock(Admission.class);
    private final Producer producer = mock(Producer.class);
    private final RoomMessageCircuitBreaker circuit = mock(RoomMessageCircuitBreaker.class);
    private final RoomMessageCircuitBreaker.Permit permit = mock(RoomMessageCircuitBreaker.Permit.class);
    private final RoomMessageService service = new RoomMessageService(rooms, profiles, admission, producer, circuit);
    private final Scope scope = new Scope(42, UUID.randomUUID(), UUID.randomUUID().toString());
    private final String clientId = UUID.randomUUID().toString();
    private final String hash = RoomEvents.hash("你好");
    private final Queued reserved = new Queued(UUID.randomUUID(), Instant.parse("2026-11-24T00:00:00.123Z"));

    @BeforeEach
    void allowSend() {
        when(circuit.tryAcquire()).thenReturn(Optional.of(permit));
    }

    @Test
    void openCircuitRejectsBeforeAnySendDependency() {
        when(circuit.tryAcquire()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(scope, clientId, "你好"))
                .isInstanceOfSatisfying(RoomMessageFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(RoomMessageFailure.Code.ROOM_MESSAGES_UNAVAILABLE));
        verifyNoInteractions(admission, rooms, profiles, producer, permit);
    }

    @Test
    void brokerAcknowledgementPrecedesQueuedResultAndRetainsFirstSenderSnapshot() throws Exception {
        when(rooms.exists(scope.roomId())).thenReturn(true);
        when(profiles.read(42)).thenReturn(new Profile(42, "Viewer_42", "新名", "新名", "default", ""));
        var lease = new Reservation(reserved, UUID.randomUUID(), "首次显示名");
        when(admission.claim(scope, clientId, hash, "新名")).thenReturn(lease);
        var broker = new CompletableFuture<Void>();
        when(producer.publish(any())).thenReturn(broker);

        var result = service.send(scope, clientId, "　你好 ");
        assertThat(result.reservation()).isEqualTo(reserved);
        assertThat(result.confirmation().isDone()).isFalse();
        verify(admission, never()).markQueued(any(), any(), any());
        var command = ArgumentCaptor.forClass(Submission.class);
        verify(producer).publish(command.capture());
        assertThat(command.getValue().senderName()).isEqualTo("首次显示名");
        assertThat(command.getValue().admittedAt()).isEqualTo("2026-11-24T00:00:00.123Z");
        assertThat(command.getValue().contentHash()).isEqualTo(hash);
        broker.complete(null);
        assertThat(result.confirmation().get(2, TimeUnit.SECONDS)).isEqualTo(reserved);
        verify(permit).succeeded();
        verify(admission, timeout(1000)).markQueued(scope, clientId, lease);
    }

    @Test
    void knownBrokerAcknowledgementBypassesDatabaseAndProducer() {
        when(admission.findQueued(scope, clientId, hash)).thenReturn(Optional.of(reserved));

        assertThat(service.send(scope, clientId, "你好").confirmation().join()).isEqualTo(reserved);
        verify(permit).release();
        verifyNoInteractions(rooms, profiles, producer);
    }

    @Test
    void findsConcurrentAcknowledgementWhenProfileLookupFails() {
        when(rooms.exists(scope.roomId())).thenReturn(true);
        when(profiles.read(42)).thenThrow(new DataAccessResourceFailureException("test"));
        when(admission.findQueued(scope, clientId, hash)).thenReturn(Optional.empty(), Optional.of(reserved));

        assertThat(service.send(scope, clientId, "你好").confirmation().join()).isEqualTo(reserved);
        verify(permit).release();
        verifyNoInteractions(producer);
    }

    @Test
    void brokerConfirmedSubmissionCompletesDespiteRedisWritebackFailure() {
        when(rooms.exists(scope.roomId())).thenReturn(true);
        when(profiles.read(42)).thenReturn(new Profile(42, "Viewer_42", "原名", "原名", "default", ""));
        var reservation = new Reservation(reserved, UUID.randomUUID(), "原名");
        when(admission.claim(scope, clientId, hash, "原名")).thenReturn(reservation);
        when(producer.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
        doThrow(new DataAccessResourceFailureException("writeback")).when(admission).markQueued(scope, clientId, reservation);

        assertThat(service.send(scope, clientId, "你好").confirmation().join()).isEqualTo(reserved);
        verify(admission, timeout(1000)).markQueued(scope, clientId, reservation);
        verify(permit).succeeded();
        verify(permit, never()).failed(any(), any());
    }

    @Test
    void pendingReservationReleasesPermitAndKeepsReadOnlyReceiptMetadata() {
        when(admission.findQueued(scope, clientId, hash)).thenThrow(
                new RoomMessageFailure(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN).withReceipt(reserved));

        assertThatThrownBy(() -> service.send(scope, clientId, "你好"))
                .isInstanceOfSatisfying(RoomMessageFailure.class,
                        failure -> assertThat(failure.receipt()).isEqualTo(reserved));
        verify(permit).release();
        verifyNoInteractions(rooms, profiles, producer);
    }
}
