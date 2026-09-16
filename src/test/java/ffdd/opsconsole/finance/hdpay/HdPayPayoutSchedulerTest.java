package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.application.HdPayPayoutTransactions;
import ffdd.opsconsole.finance.mapper.BankWithdrawalMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;

class HdPayPayoutSchedulerTest {
    @Test void ambiguousCreateIsOnlyQueriedOnSubsequentTicksNeverCreatedAgain() {
        var bank = mock(BankWithdrawalMapper.class);
        var transport = mock(HdPayProperties.class);
        var payout = mock(HdPayPayoutProperties.class);
        var gateway = mock(HdPayPayoutGateway.class);
        var transactions = mock(HdPayPayoutTransactions.class);
        var env = new MockEnvironment(); env.setActiveProfiles("dev");
        var scheduler = new HdPayPayoutScheduler(bank, transport, payout, gateway, transactions, env, Clock.systemUTC());
        when(transport.connectionReady()).thenReturn(true);
        when(payout.ready(transport)).thenReturn(true);
        when(bank.schemaTables()).thenReturn(4);
        when(bank.ready(any())).thenReturn(List.of("WD-TEST"), List.of());
        when(bank.queryDue(any())).thenReturn(List.of(), List.of("WD-TEST"));
        var request = new HdPayPayoutGateway.Request("WD-TEST", new BigDecimal("1000000"), "VCB", "0123456789", "NGUYEN VAN A", "203.0.113.7");
        when(transactions.prepare("WD-TEST")).thenReturn(request);
        doThrow(new RuntimeException("timeout-after-acceptance")).when(gateway).create(request);
        when(gateway.query("WD-TEST")).thenThrow(new RuntimeException("still-unknown"));
        scheduler.tick(); scheduler.tick();
        verify(gateway, times(1)).create(request);
        verify(gateway).query("WD-TEST");
        verify(transactions).defer("WD-TEST");
    }
    @Test void disablingChannelDoesNotDisableExistingOrderReconciliation() {
        var bank = mock(BankWithdrawalMapper.class);
        var transport = mock(HdPayProperties.class);
        var payout = mock(HdPayPayoutProperties.class);
        var gateway = mock(HdPayPayoutGateway.class);
        var transactions = mock(HdPayPayoutTransactions.class);
        var env = new MockEnvironment(); env.setActiveProfiles("dev");
        when(transport.connectionReady()).thenReturn(true);
        when(bank.schemaTables()).thenReturn(4);
        when(bank.queryDue(any())).thenReturn(List.of("WD-TEST"));
        new HdPayPayoutScheduler(bank, transport, payout, gateway, transactions, env, Clock.systemUTC()).tick();
        verify(gateway).query("WD-TEST");
        verify(bank, never()).ready(any());
        verify(gateway, never()).create(any());
    }
}
