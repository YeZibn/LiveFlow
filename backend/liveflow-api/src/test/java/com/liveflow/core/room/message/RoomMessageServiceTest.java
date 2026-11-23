package com.liveflow.core.room.message;

import com.liveflow.core.profile.service.UserProfileService;
import com.liveflow.core.profile.service.UserProfileService.Profile;
import com.liveflow.core.room.service.RoomService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomMessageServiceTest {

    @Test
    void replayRetainsFirstReceiptAndExpiresAtOriginalDeadline() {
        RoomService rooms = existingRooms();
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.read(42)).thenReturn(profile(""), profile("新昵称"), profile("新昵称"));
        var published = new ArrayList<RoomMessageBroadcast.Message>();
        var time = new AtomicLong();
        var service = new RoomMessageService(rooms, profiles, published::add, time::get, Clock.systemUTC(), 10, 1, 60, 2);
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
        assertThat(published).extracting(RoomMessageBroadcast.Message::senderName).containsExactly("Viewer_42", "新昵称");
    }

    @Test
    void capacityFailurePreservesPreviouslyAcceptedRequests() {
        RoomService rooms = existingRooms();
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.read(42)).thenReturn(profile("测试昵称"));
        var published = new ArrayList<RoomMessageBroadcast.Message>();
        var service = new RoomMessageService(rooms, profiles, published::add, () -> 0, Clock.systemUTC(), 10, 5, 60, 1);
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

    @Test
    void profileFailureDoesNotUseQuotaAndReplayDoesNotReadProfile() {
        var outage = new DataAccessResourceFailureException("test");
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.read(42)).thenThrow(outage).thenReturn(profile("新昵称")).thenThrow(outage);
        var published = new ArrayList<RoomMessageBroadcast.Message>();
        var service = new RoomMessageService(existingRooms(), profiles, published::add,
                () -> 0, Clock.systemUTC(), 10, 1, 60, 2);
        UUID session = UUID.randomUUID();
        String room = UUID.randomUUID().toString();
        String submission = UUID.randomUUID().toString();

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> service.send(42, session, room, submission, "弹幕"))).isSameAs(outage);
        assertThat(published).isEmpty();
        var receipt = service.send(42, session, room, submission, "弹幕");
        assertThat(service.send(42, session, room, submission, "弹幕")).isEqualTo(receipt);
        verify(profiles, times(2)).read(42);
        assertThat(published).singleElement().extracting(RoomMessageBroadcast.Message::senderName).isEqualTo("新昵称");
    }

    @Test
    void rechecksReceiptAfterConcurrentProfileLookupFails() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.read(42)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            throw new DataAccessResourceFailureException("test");
        }).thenReturn(profile("房间观众"));
        var published = new ArrayList<RoomMessageBroadcast.Message>();
        var service = new RoomMessageService(existingRooms(), profiles, published::add,
                () -> 0, Clock.systemUTC(), 10, 1, 60, 2);
        UUID session = UUID.randomUUID();
        String room = UUID.randomUUID().toString();
        String submission = UUID.randomUUID().toString();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retry = executor.submit(() -> service.send(42, session, room, submission, "弹幕"));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var accepted = executor.submit(() -> service.send(42, session, room, submission, "弹幕"))
                        .get(3, TimeUnit.SECONDS);
                release.countDown();
                assertThat(retry.get(3, TimeUnit.SECONDS)).isEqualTo(accepted);
                assertThat(published).hasSize(1);
            } finally {
                release.countDown();
            }
        }
    }

    private static RoomService existingRooms() {
        RoomService rooms = mock(RoomService.class);
        when(rooms.exists(anyString())).thenReturn(true);
        return rooms;
    }

    private static Profile profile(String nickname) {
        String username = "Viewer_42";
        return new Profile(42, username, nickname, nickname.isEmpty() ? username : nickname, "default", "");
    }
}
