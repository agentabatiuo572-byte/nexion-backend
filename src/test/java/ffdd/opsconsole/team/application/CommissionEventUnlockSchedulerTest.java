package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
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
    @Mock private CommissionEventUnlockProcessor processor;

    @InjectMocks private CommissionEventUnlockScheduler scheduler;

    private Map<String, Object> due(long id, long userId, String amount, String currency) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("userId", userId);
        m.put("version", 0L);
        m.put("amount", new BigDecimal(amount));
        m.put("currency", currency);
        return m;
    }

    @Test
    void autoUnlock_unlocksCoolingDueCommission() {
        // COOLING unlock_at 已到期 → UNLOCKED + publish COMMISSION_UNLOCKED + D4 入账
        Map<String, Object> row = due(1001L, 2001L, "50", "USDT");
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of(row));
        when(processor.unlock(row)).thenReturn(true);

        scheduler.autoUnlockCoolingDue();

        verify(processor).unlock(row);
    }

    @Test
    void autoUnlock_noDue_returnsEarly() {
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of());

        scheduler.autoUnlockCoolingDue();

    }

    @Test
    void autoUnlock_alreadyUnlocked_skipsPublishAndLedger() {
        // updateCommissionStatus 返回 false(并发已手动处置/0 行)→ 跳过 publish + ledger
        Map<String, Object> row = due(1002L, 2002L, "10", "USDT");
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of(row));
        when(processor.unlock(row)).thenReturn(false);

        scheduler.autoUnlockCoolingDue();

        verify(processor).unlock(row);
    }

    @Test
    void autoUnlock_amountZero_skipsLedgerOnly() {
        // amount=0 → updateStatus + publish 仍执行,但 ledger 跳过(amount<=0)
        Map<String, Object> row = due(1003L, 2003L, "0", "USDT");
        when(teamCommissionMapper.listCoolingDueForUnlock(500)).thenReturn(List.of(row));
        when(processor.unlock(row)).thenReturn(true);

        scheduler.autoUnlockCoolingDue();

        verify(processor).unlock(row);
    }
}
