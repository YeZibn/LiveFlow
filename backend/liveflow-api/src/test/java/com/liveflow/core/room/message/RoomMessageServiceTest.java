package com.liveflow.core.room.message;

import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.profile.service.UserProfileService.Profile;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoomMessageServiceTest {
    private final RoomService rooms = mock(RoomService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final Admission admission = mock(Admission.class);
    private final Producer producer = mock(Producer.class);
    private final RoomMessageService service = new RoomMessageService(rooms, profiles, admission, producer);
    private final Scope scope = new Scope(42, UUID.randomUUID(), UUID.randomUUID().toString());
    private final String clientId = UUID.randomUUID().toString();
    private final String hash = RoomEvents.hash("你好");
    private final Queued reserved = new Queued(UUID.randomUUID(), Instant.parse("2026-11-24T00:00:00.123Z"));

    @Test
    void brokerAcknowledgementPrecedesQueuedResultAndRetainsFirstSenderSnapshot() throws Exception {
        when(rooms.exists(scope.roomId())).thenReturn(true);
        when(profiles.read(42)).thenReturn(new Profile(42, "Viewer_42", "新名", "新名", "default", ""));
        var lease = new Reservation(reserved, UUID.randomUUID(), "首次显示名");
        when(admission.claim(scope, clientId, hash, "新名")).thenReturn(lease);
        var broker = new CompletableFuture<Void>();
        when(producer.publish(any())).thenReturn(broker);

        var result = service.send(scope, clientId, "　你好 ");
        assertThat(result.isDone()).isFalse();
        verify(admission, never()).markQueued(any(), any(), any());
        var command = ArgumentCaptor.forClass(Submission.class);
        verify(producer).publish(command.capture());
        assertThat(command.getValue().senderName()).isEqualTo("首次显示名");
        assertThat(command.getValue().admittedAt()).isEqualTo("2026-11-24T00:00:00.123Z");
        assertThat(command.getValue().contentHash()).isEqualTo(hash);
        broker.complete(null);
        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(reserved);
        verify(admission).markQueued(scope, clientId, lease);
    }

    @Test
    void knownBrokerAcknowledgementBypassesDatabaseAndProducer() {
        when(admission.findQueued(scope, clientId, hash)).thenReturn(Optional.of(reserved));

        assertThat(service.send(scope, clientId, "你好").join()).isEqualTo(reserved);
        verifyNoInteractions(rooms, profiles, producer);
    }

    @Test
    void findsConcurrentAcknowledgementWhenProfileLookupFails() {
        when(rooms.exists(scope.roomId())).thenReturn(true);
        when(profiles.read(42)).thenThrow(new DataAccessResourceFailureException("test"));
        when(admission.findQueued(scope, clientId, hash)).thenReturn(Optional.empty(), Optional.of(reserved));

        assertThat(service.send(scope, clientId, "你好").join()).isEqualTo(reserved);
        verifyNoInteractions(producer);
    }
}
