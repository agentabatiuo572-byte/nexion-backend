package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisDepositSwitchServiceTest {
    @Test
    void emergencyCloseTurnsOffEveryMoneySwitchAtExpectedVersion() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockProvisionGate()).thenReturn(Map.of("version", 4L));
        when(db.forceEmergencyOff("EMERGENCY:Suspected callback mismatch", 4)).thenReturn(1);

        Map<String, Object> result = service(db).emergencyOff(7, 4, "Suspected callback mismatch");

        assertThat(result).containsEntry("state", "BLOCKED")
                .containsEntry("assignEnabled", false)
                .containsEntry("creditEnabled", false)
                .containsEntry("payoutEnabled", false)
                .containsEntry("version", 5L);
        verify(db).insertRiskAlert(88, "manual-emergency-4", "P0", "MANUAL_EMERGENCY_OFF",
                "admin=7,reason=Suspected callback mismatch");
    }

    @Test
    void makerCannotApproveOwnReopening() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(proposal(7));

        assertThatThrownBy(() -> service(db).check(7, 9, "APPROVE", "Reviewed recovery evidence"))
                .hasMessage("CREGIS_SWITCH_DIFFERENT_CHECKER_REQUIRED");
        verify(db, never()).setSwitches(anyInt(), anyInt(), anyInt(), any(String.class), anyLong());
    }

    @Test
    void independentCheckerCannotReopenWithoutRecentCompleteReconciliation() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(proposal(7));
        when(db.lockProvisionGate()).thenReturn(Map.of("version", 4L, "state", "BLOCKED"));
        when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
        // No complete reconciliation: the database returns its default zero.

        assertThatThrownBy(() -> service(db).check(8, 9, "APPROVE", "Reviewed recovery evidence"))
                .hasMessage("CREGIS_SWITCH_RECOVERY_NOT_SAFE");
        verify(db, never()).setSwitches(anyInt(), anyInt(), anyInt(), any(String.class), anyLong());
    }

    private static Map<String, Object> proposal(long makerId) {
        return Map.of("projectId", 88L, "status", "MAKER_DONE", "makerId", makerId,
                "expectedVersion", 4L, "assignEnabled", 1, "creditEnabled", 0);
    }

    private static CregisDepositSwitchService service(CregisDepositMapper db) {
        CregisProperties config = new CregisProperties();
        config.setMode(CregisProperties.Mode.PROVIDER);
        config.setProjectId(88);
        config.setDepositEnabled(true);
        config.setDepositCreditEnabled(true);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new CregisDepositSwitchService(config, db, mock(AuditLogService.class), tx);
    }
}
