package com.liveflow.core.room.message;

import com.liveflow.core.room.mapper.RoomMapper.DetailsRow;
import com.liveflow.core.room.service.RoomService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomMessageServiceTest {

    @Test
    void replayRetainsFirstReceiptAndExpiresAtOriginalDeadline() {
        RoomService rooms = mock(RoomService.class);
        when(rooms.readDetails(anyString())).thenReturn(Optional.of(new DetailsRow("room", "房间")));
        var published = new ArrayList<RoomMessageBroadcast.Message>();
        var time = new AtomicLong();
        var service = new RoomMessageService(rooms, published::add, time::get, Clock.systemUTC(), 10, 1, 60, 2);
        UUID session = UUID.randomUUID();
        String roomA = UUID.randomUUID().toString();
        String roomB = UUID.randomUUID().toString();
        String submission = UUID.randomUUID().toString();
        var first = service.send(42, session, roomA, submission, "　你好 ");
        var conflict = assertThrows(RoomMessageFailure.class,
                () -> service.send(42, session, roomA, submission, "修改后的正文"));
        assertThat(conflict.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_ID_CONFLICT);
        var limited = assertThrows(RoomMessageFailure.class,
                () -> service.send(42, session, roomB, UUID.randomUUID().toString(), "另一房间"));
        assertThat(limited.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isEqualTo(10);
        time.set(TimeUnit.SECONDS.toNanos(59));
        assertThat(service.send(42, session, roomA, submission, "你好")).isEqualTo(first);
        assertThat(published).hasSize(1);
        time.set(TimeUnit.SECONDS.toNanos(60));
        assertThat(service.send(42, session, roomA, submission, "你好").messageId()).isNotEqualTo(first.messageId());
        assertThat(published).hasSize(2);
    }

    @Test
    void capacityFailurePreservesPreviouslyAcceptedRequests() {
        RoomService rooms = mock(RoomService.class);
        when(rooms.readDetails(anyString())).thenReturn(Optional.of(new DetailsRow("room", "房间")));
        var published = new ArrayList<RoomMessageBroadcast.Message>();
        var service = new RoomMessageService(rooms, published::add, () -> 0, Clock.systemUTC(), 10, 5, 60, 1);
        UUID session = UUID.randomUUID();
        String room = UUID.randomUUID().toString();
        String submission = UUID.randomUUID().toString();
        var first = service.send(42, session, room, submission, "第一条");
        var failure = assertThrows(RoomMessageFailure.class,
                () -> service.send(42, session, room, UUID.randomUUID().toString(), "第二条"));
        assertThat(failure.code()).isEqualTo(RoomMessageFailure.Code.ROOM_MESSAGES_UNAVAILABLE);
        assertThat(service.send(42, session, room, submission, "第一条")).isEqualTo(first);
        assertThat(published).hasSize(1);
    }
}
