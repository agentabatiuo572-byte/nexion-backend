package ffdd.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.exception.BizException;
import ffdd.wallet.chain.WithdrawalChainBroadcaster;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalBroadcastServiceTest {
    private final WithdrawalOrderMapper withdrawalOrderMapper = mock(WithdrawalOrderMapper.class);
    private final WithdrawalChainBroadcaster broadcaster = mock(WithdrawalChainBroadcaster.class);
    private final WithdrawalBroadcastService service =
            new WithdrawalBroadcastService(withdrawalOrderMapper, broadcaster, 3, 60);

    @Test
    void broadcastsPendingWithdrawalAndMarksSubmitted() {
        WithdrawalOrder order = withdrawal("WD-B1", "PENDING_CHAIN", 0);
        when(withdrawalOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(broadcaster.broadcast(order)).thenReturn("0xabc");

        WithdrawalBroadcastResponse response = service.broadcastPending(10);

        assertThat(response.getScanned()).isEqualTo(1);
        assertThat(response.getSubmitted()).isEqualTo(1);
        assertThat(response.getFailed()).isZero();
        assertThat(response.getDead()).isZero();
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void schedulesRetryWhenBroadcastFailsBelowMaxRetries() {
        WithdrawalOrder order = withdrawal("WD-B2", "PENDING_CHAIN", 1);
        when(withdrawalOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(broadcaster.broadcast(order)).thenThrow(new IllegalStateException("provider down"));

        WithdrawalBroadcastResponse response = service.broadcastPending(10);

        assertThat(response.getScanned()).isEqualTo(1);
        assertThat(response.getSubmitted()).isZero();
        assertThat(response.getFailed()).isEqualTo(1);
        assertThat(response.getDead()).isZero();
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void marksDeadWhenBroadcastReachesMaxRetries() {
        WithdrawalOrder order = withdrawal("WD-B3", "PENDING_CHAIN", 2);
        when(withdrawalOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(broadcaster.broadcast(order)).thenThrow(new IllegalStateException("invalid address"));

        WithdrawalBroadcastResponse response = service.broadcastPending(10);

        assertThat(response.getScanned()).isEqualTo(1);
        assertThat(response.getFailed()).isZero();
        assertThat(response.getDead()).isEqualTo(1);
        verify(withdrawalOrderMapper).updateById(any(WithdrawalOrder.class));
    }

    @Test
    void doesNotCallBroadcasterWhenNoPendingRows() {
        when(withdrawalOrderMapper.selectList(any())).thenReturn(List.of());

        WithdrawalBroadcastResponse response = service.broadcastPending(10);

        assertThat(response.getScanned()).isZero();
        verify(broadcaster, never()).broadcast(any(WithdrawalOrder.class));
    }

    @Test
    void retryDeadWithdrawalResetsBroadcastState() {
        WithdrawalOrder dead = withdrawal("WD-DEAD", "DEAD", 3);
        dead.setNextBroadcastAt(LocalDateTime.now().plusHours(1));
        dead.setBroadcastDeadAt(LocalDateTime.now());
        dead.setLastBroadcastError("provider down");
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(dead);

        WithdrawalOrder result = service.retryBroadcast("WD-DEAD");

        assertThat(result.getStatus()).isEqualTo("PENDING_CHAIN");
        assertThat(result.getChainBroadcastAttempts()).isZero();
        assertThat(result.getNextBroadcastAt()).isNull();
        assertThat(result.getBroadcastDeadAt()).isNull();
        assertThat(result.getLastBroadcastError()).isNull();
        verify(withdrawalOrderMapper).update(any(), any());
    }

    @Test
    void retryBroadcastRejectsSubmittedWithdrawal() {
        when(withdrawalOrderMapper.selectOne(any())).thenReturn(withdrawal("WD-SUB", "CHAIN_SUBMITTED", 1));

        assertThatThrownBy(() -> service.retryBroadcast("WD-SUB"))
                .isInstanceOf(BizException.class)
                .hasMessage("Withdrawal is not retryable");

        verify(withdrawalOrderMapper, never()).update(any(), any());
    }

    private WithdrawalOrder withdrawal(String withdrawalNo, String status, int attempts) {
        WithdrawalOrder order = new WithdrawalOrder();
        order.setId(1L);
        order.setUserId(10001L);
        order.setWithdrawalNo(withdrawalNo);
        order.setAsset("USDT");
        order.setAmount(new BigDecimal("1.000000"));
        order.setFee(BigDecimal.ZERO);
        order.setTargetAddress("TTargetAddress111111111111111111111111");
        order.setStatus(status);
        order.setChainBroadcastAttempts(attempts);
        order.setIsDeleted(0);
        return order;
    }
}
