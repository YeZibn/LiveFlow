package com.liveflow.core.room;

import com.liveflow.core.room.mapper.RoomMapper;
import com.liveflow.core.room.service.RoomExistenceStore;
import com.liveflow.core.room.service.RoomService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RoomCreationCacheTest {
    private final RoomMapper mapper = mock(RoomMapper.class);
    private final RoomExistenceStore existence = mock(RoomExistenceStore.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final RoomService rooms = new RoomService(mapper, existence, transactions);

    @Test
    void publishesTheFinalUuidAfterTheTransactionCommits() {
        prepareCreate();
        var created = rooms.create(42, "直播间");
        assertThat(RoomUuid.isCanonical(created.roomId())).isTrue();
        var order = inOrder(transactions, existence);
        order.verify(transactions).commit(transaction);
        order.verify(existence).warmCreated(created.roomId());
    }

    @Test
    void failedReadBackRollsBackWithoutPublishingExistence() {
        prepareCreate();
        when(mapper.find(42)).thenReturn(new RoomMapper.Row(42, null, null, null));
        assertThatThrownBy(() -> rooms.create(42, "直播间")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        inOrder(transactions).verify(transactions).rollback(transaction);
        verifyNoInteractions(existence);
    }

    @Test
    void unknownCommitDoesNotPublishExistence() {
        prepareCreate();
        doThrow(new TransactionSystemException("test")).when(transactions).commit(transaction);
        assertThatThrownBy(() -> rooms.create(42, "直播间")).isInstanceOf(TransactionSystemException.class);
        verifyNoInteractions(existence);
    }

    private void prepareCreate() {
        var inserted = new AtomicReference<String>();
        when(transactions.getTransaction(any())).thenReturn(transaction);
        when(mapper.insert(eq(42L), any(), eq("直播间"))).thenAnswer(call -> {
            inserted.set(call.getArgument(1));
            return 1;
        });
        when(mapper.find(42)).thenAnswer(call ->
                new RoomMapper.Row(42, inserted.get(), "直播间", LocalDateTime.of(2026, 11, 25, 12, 0)));
    }
}
