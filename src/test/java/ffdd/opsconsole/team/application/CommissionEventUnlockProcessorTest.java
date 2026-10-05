package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommissionEventUnlockProcessorTest {
    @Mock private F5CommissionMapper mapper;
    @Mock private EventOutboxService eventOutboxService;
    @Mock private AuditLogService auditLogService;
    @Mock private TreasuryLedgerPostingFacade ledgerPostingFacade;
    @InjectMocks private CommissionEventUnlockProcessor processor;

    @Test
    void automaticUnlockReleasesActualFundsBeforePublishingTheUnlockedEvent() {
        when(mapper.unlockCoolingEventCas(71L, 4L)).thenReturn(1);
        when(mapper.insertAutoUnlockOperation(71L, 4L)).thenReturn(1);

        assertThat(processor.unlock(Map.of("id", 71L, "userId", 42L, "version", 4L,
                        "amount", "100", "currency", "USDT")))
                .isTrue();
        verify(mapper).unlockCoolingEventCas(71L, 4L);
        verify(mapper).insertAutoUnlockOperation(71L, 4L);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(ledgerPostingFacade, eventOutboxService);
        order.verify(ledgerPostingFacade).releaseCommissionFunds(71L);
        order.verify(eventOutboxService).publish(
                "COMMISSION", "71", "COMMISSION_UNLOCKED",
                Map.of("user_id", 42L, "commission_event_id", 71L));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.any());
        verify(eventOutboxService).publish(
                "COMMISSION", "71", "COMMISSION_UNLOCKED",
                Map.of("user_id", 42L, "commission_event_id", 71L));
    }

    @Test
    void lostCasCannotReleaseWalletFunds() {
        assertThat(processor.unlock(Map.of("id", 71L, "userId", 42L, "version", 4L))).isFalse();
        verify(ledgerPostingFacade, never()).releaseCommissionFunds(71L);
    }

    @Test
    void walletFailurePropagatesWithoutPublishingSuccessfulUnlock() {
        when(mapper.unlockCoolingEventCas(71L, 4L)).thenReturn(1);
        when(mapper.insertAutoUnlockOperation(71L, 4L)).thenReturn(1);
        doThrow(new IllegalStateException("wallet unavailable")).when(ledgerPostingFacade).releaseCommissionFunds(71L);
        assertThatThrownBy(() -> processor.unlock(Map.of("id", 71L, "userId", 42L, "version", 4L)))
                .hasMessage("wallet unavailable");
        verify(eventOutboxService, never()).publish(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
