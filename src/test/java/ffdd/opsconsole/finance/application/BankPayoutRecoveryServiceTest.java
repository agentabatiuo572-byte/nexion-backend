package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.function.Supplier;

class BankPayoutRecoveryServiceTest {
    final HdPayPayoutGateway gateway=mock(HdPayPayoutGateway.class);
    final HdPayPayoutTransactions tx=mock(HdPayPayoutTransactions.class);
    final AdminIdempotencyService idem=mock(AdminIdempotencyService.class);
    final BankWithdrawalMapper bank=mock(BankWithdrawalMapper.class);
    final BankPayoutRecoveryService service=new BankPayoutRecoveryService(gateway,tx,idem,bank);
    @BeforeEach void setup() {
        when(idem.recoveryResult(anyString(),anyString(),anyString(),eq(String.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.NOT_FOUND,null));
        when(bank.order("WD-TEST")).thenReturn(new BankWithdrawalMapper.Order("WD-TEST","BQ-test",1L,"MANUAL_REVIEW",123L,2,null));
        when(bank.version("WD-TEST")).thenReturn(5L);
        when(idem.executeRetained(anyString(),anyString(),anyString(),eq(String.class),any()))
                .thenAnswer(i -> ((Supplier<?>)i.getArgument(4)).get());
    }
    @Test void durableReceiptReplayDoesNotQueryOrTouchMoneyAgain() {
        when(idem.recoveryResult(anyString(),anyString(),anyString(),eq(String.class)))
                .thenReturn(new AdminIdempotencyService.RecoveryResult<>(AdminIdempotencyService.RecoveryStatus.SUCCEEDED,"PAID"));
        assertEquals("PAID",service.recover("WD-TEST","key",5L,"verify original order"));
        verifyNoInteractions(gateway,tx,bank);
    }
    @Test void staleMissingAndNonHeldOrdersNeverReachProvider() {
        assertThrows(RuntimeException.class,()->service.recover("WD-TEST","key",4L,"verify original order"));
        when(bank.order("WD-TEST")).thenReturn(null);
        assertThrows(RuntimeException.class,()->service.recover("WD-TEST","key",5L,"verify original order"));
        when(bank.order("WD-TEST")).thenReturn(new BankWithdrawalMapper.Order("WD-TEST","BQ-test",1L,"PAID",123L,3,null));
        assertThrows(RuntimeException.class,()->service.recover("WD-TEST","key",5L,"verify original order"));
        verifyNoInteractions(gateway,tx);
    }
    @Test void queryTimeoutDoesNotCreateOrUnfreezeAnything() {
        when(gateway.query("WD-TEST")).thenThrow(new HdPayGatewayException("FIXTURE_TIMEOUT",false));
        assertThrows(RuntimeException.class,()->service.recover("WD-TEST","key",5L,"verify original order"));
        verify(gateway,never()).create(any()); verifyNoInteractions(tx);
    }
}
