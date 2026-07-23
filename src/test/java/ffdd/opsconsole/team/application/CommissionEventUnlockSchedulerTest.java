package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F5 CommissionEventUnlockScheduler 单元测试。
 * 覆盖:autoUnlockCoolingDue 扫描 COOLING unlock_at<=now → updateCommissionStatus UNLOCKED
 * + publish COMMISSION_UNLOCKED + postLedgerEntry;无到期/已解锁跳过。
 */
@ExtendWith(MockitoExtension.class)
class CommissionEventUnlockSchedulerTest {

    @Mock private TeamCommissionMapper teamCommissionMapper;
    @Mock private TeamCommissionRepository commissionRepository;
    @Mock private EventOutboxService eventOutboxService;
    @Mock private TreasuryLedgerPostingFacade ledgerPostingFacade;

    @InjectMocks private CommissionEventUnlockScheduler scheduler;

    private Map<String, Object> due(long id, long userId, String amount, String currency) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("userId", userId);
        m.put("amountUsdt", new BigDecimal(amount));
        m.put("currency", currency);
        return m;
    }

    @Test
    void autoUnlock_unlocksCoolingDueCommission() {
        // COOLING unlock_at 已到期 → UNLOCKED + publish COMMISSION_UNLOCKED + D4 入账
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of(
                due(1001L, 2001L, "50", "USDT")));
        when(commissionRepository.updateCommissionStatus("CM-1001", "UNLOCKED")).thenReturn(true);

        scheduler.autoUnlockCoolingDue();

        verify(commissionRepository).updateCommissionStatus("CM-1001", "UNLOCKED");
        verify(eventOutboxService).publish(eq("COMMISSION"), eq("1001"),
                eq("COMMISSION_UNLOCKED"), any());
        verify(ledgerPostingFacade).postLedgerEntry(eq("F5-AUTO-UNLOCK-1001"), eq(2001L),
                eq("TEAM_COMMISSION"), eq("USDT"), eq("IN"), eq(new BigDecimal("50")),
                eq("PENDING"), anyString());
    }

    @Test
    void autoUnlock_noDue_returnsEarly() {
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of());

        scheduler.autoUnlockCoolingDue();

        verify(commissionRepository, never()).updateCommissionStatus(anyString(), anyString());
        verify(eventOutboxService, never()).publish(anyString(), anyString(), anyString(), any());
        verify(ledgerPostingFacade, never()).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void autoUnlock_alreadyUnlocked_skipsPublishAndLedger() {
        // updateCommissionStatus 返回 false(并发已手动处置/0 行)→ 跳过 publish + ledger
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of(
                due(1002L, 2002L, "10", "USDT")));
        when(commissionRepository.updateCommissionStatus("CM-1002", "UNLOCKED")).thenReturn(false);

        scheduler.autoUnlockCoolingDue();

        verify(commissionRepository).updateCommissionStatus("CM-1002", "UNLOCKED");
        verify(eventOutboxService, never()).publish(anyString(), anyString(), anyString(), any());
        verify(ledgerPostingFacade, never()).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    void autoUnlock_amountZero_skipsLedgerOnly() {
        // amount=0 → updateStatus + publish 仍执行,但 ledger 跳过(amount<=0)
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of(
                due(1003L, 2003L, "0", "USDT")));
        when(commissionRepository.updateCommissionStatus("CM-1003", "UNLOCKED")).thenReturn(true);

        scheduler.autoUnlockCoolingDue();

        verify(eventOutboxService, times(1)).publish(eq("COMMISSION"), eq("1003"),
                eq("COMMISSION_UNLOCKED"), any());
        verify(ledgerPostingFacade, never()).postLedgerEntry(anyString(), anyLong(), anyString(),
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString());
    }
}
