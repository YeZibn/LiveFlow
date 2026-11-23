package com.liveflow.api.room.message;

import com.liveflow.core.auth.session.AuthSessionStore;
import com.liveflow.core.room.message.RoomMessageBroadcast;
import com.liveflow.core.room.message.RoomTicketService;
import com.liveflow.core.room.message.RoomTicketStore.Binding;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomSocketHandlerTest {

    @Test
    void closesOnlyTheSlowConnectionWhileFastConnectionKeepsRoomOrder() throws Exception {
        var ticketService = mock(RoomTicketService.class);
        var roomId = UUID.randomUUID().toString();
        when(ticketService.redeem("slow")).thenReturn(new Binding(1, UUID.randomUUID(), roomId));
        when(ticketService.redeem("fast")).thenReturn(new Binding(2, UUID.randomUUID(), roomId));
        JsonMapper json = JsonMapper.builder().build();
        var handler = new RoomSocketHandler(ticketService, mock(AuthSessionStore.class), json, 8, 4, 2, 5, 15, 5000);
        var slow = mock(WebSocketSession.class);
        when(slow.getId()).thenReturn("slow");
        when(slow.isOpen()).thenReturn(true);
        var fast = mock(WebSocketSession.class);
        when(fast.getId()).thenReturn("fast");
        when(fast.isOpen()).thenReturn(true);
        var fastEvents = new LinkedBlockingQueue<String>();
        var slowReady = new CountDownLatch(1);
        var slowWrite = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        var closed = new LinkedBlockingQueue<CloseStatus>();
        doAnswer(call -> {
            var frame = json.readTree(((TextMessage) call.getArgument(0)).getPayload());
            if (frame.get("type").asString().equals("ready")) {
                slowReady.countDown();
            } else {
                slowWrite.countDown();
                assertThat(releaseSlow.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(slow).sendMessage(any());
        doAnswer(call -> {
            closed.add(call.getArgument(0));
            return null;
        }).when(slow).close(any(CloseStatus.class));
        doAnswer(call -> {
            var frame = json.readTree(((TextMessage) call.getArgument(0)).getPayload());
            fastEvents.add(frame.get("type").asString().equals("ready") ? "ready" : frame.get("content").asString());
            return null;
        }).when(fast).sendMessage(any());

        try {
            handler.afterConnectionEstablished(slow);
            handler.afterConnectionEstablished(fast);
            handler.handleMessage(slow, new TextMessage("{\"type\":\"auth\",\"ticket\":\"slow\"}"));
            handler.handleMessage(fast, new TextMessage("{\"type\":\"auth\",\"ticket\":\"fast\"}"));
            assertThat(slowReady.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fastEvents.poll(2, TimeUnit.SECONDS)).isEqualTo("ready");
            for (String text : new String[] {"甲", "乙", "丙", "丁"}) {
                handler.enqueue(new RoomMessageBroadcast.Message(roomId, UUID.randomUUID().toString(), "观众", text,
                        Instant.now()));
                assertThat(fastEvents.poll(2, TimeUnit.SECONDS)).isEqualTo(text);
                if (text.equals("甲")) {
                    assertThat(slowWrite.await(2, TimeUnit.SECONDS)).isTrue();
                }
            }
            releaseSlow.countDown();
            assertThat(closed.poll(2, TimeUnit.SECONDS)).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
        } finally {
            releaseSlow.countDown();
            handler.stop();
        }
    }
}
