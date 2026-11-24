package com.liveflow.api.room.message;

import com.liveflow.core.room.message.RoomEvents.Queued;
import com.liveflow.core.room.message.RoomEvents.Scope;
import com.liveflow.core.room.message.RoomHistoryService;
import com.liveflow.core.room.message.RoomMessageFailure;
import com.liveflow.core.room.message.RoomMessagePorts.Receipts;
import com.liveflow.core.room.message.RoomMessageService;
import com.liveflow.core.room.message.RoomMessageService.SendAttempt;
import com.liveflow.core.room.message.RoomTicketService;
import com.liveflow.infrastructure.message.RoomKafkaRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomMessageControllerTest {
    @Test
    void responseTimeoutCarriesReceiptWithoutCompletingTheBrokerFuture() {
        var messages = mock(RoomMessageService.class);
        var controller = new RoomMessageController(mock(RoomTicketService.class), messages, mock(RoomHistoryService.class),
                mock(Receipts.class), mock(RoomKafkaRuntime.class), new SimpleMeterRegistry(), JsonMapper.builder().build());
        UUID session = UUID.randomUUID();
        String room = UUID.randomUUID().toString();
        String clientId = UUID.randomUUID().toString();
        var jwt = Jwt.withTokenValue("test-token").header("alg", "HS256")
                .subject("42").claim("sid", session.toString()).build();
        var reservation = new Queued(UUID.randomUUID(), Instant.parse("2026-11-24T00:00:00.123Z"));
        var broker = new CompletableFuture<Queued>();
        when(messages.send(new Scope(42, session, room), clientId, "测试弹幕"))
                .thenReturn(new SendAttempt(reservation, broker));
        var body = ("{\"clientMessageId\":\"" + clientId + "\",\"content\":\"测试弹幕\"}")
                .getBytes(StandardCharsets.UTF_8);

        var response = controller.send(jwt, room, body);
        var timeout = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
        var failure = (RoomMessageFailure) timeout.getCause();
        assertThat(failure.code()).isEqualTo(RoomMessageFailure.Code.MESSAGE_SUBMISSION_UNKNOWN);
        var error = controller.businessError(failure);
        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(error.getBody()).containsEntry("submissionId", reservation.submissionId())
                .containsEntry("receiptExpiresAt", "2026-11-24T00:30:00.123Z");
        // 后续确认可以继续写回原预约，而当前 HTTP 响应已经结束。
        assertThat(broker).isNotDone();
        assertThat(broker.complete(reservation)).isTrue();
    }
}
