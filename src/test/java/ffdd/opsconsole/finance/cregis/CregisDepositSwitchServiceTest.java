package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisDepositSwitchServiceTest {
    @ParameterizedTest
    @CsvSource({"0,0", "1,1"})
    void inactiveOrDeletedMakerCannotApproveAndTransactionRollsBack(int status, int deleted) {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(proposal(7));
        AdminMapper admins = activeAdmins();
        AdminEntity maker = new AdminEntity();
        maker.setStatus(status);
        maker.setIsDeleted(deleted);
        when(admins.selectActiveForUpdate(7L)).thenReturn(maker);
        PlatformTransactionManager tx = transactionManager();

        assertThatThrownBy(() -> service(db, admins, tx, mock(AuditLogService.class))
                .check(8, 9, "APPROVE", "Reviewed recovery evidence"))
                .hasMessage("CREGIS_SWITCH_MAKER_INACTIVE");

        verify(db, never()).lockProvisionGate();
        verify(db, never()).setSwitches(anyInt(), anyInt(), anyInt(), any(String.class), anyLong());
        verify(db, never()).checkSwitchCase(anyLong(), anyLong(), anyLong(), any(String.class));
        verify(tx).rollback(any());
        verify(tx, never()).commit(any());
    }

    @Test
    void missingMakerCannotApprove() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(proposal(7));
        AdminMapper admins = mock(AdminMapper.class);

        assertThatThrownBy(() -> service(db, admins, transactionManager(), mock(AuditLogService.class))
                .check(8, 9, "APPROVE", "Reviewed recovery evidence"))
                .hasMessage("CREGIS_SWITCH_MAKER_INACTIVE");
        verify(db, never()).setSwitches(anyInt(), anyInt(), anyInt(), any(String.class), anyLong());
    }

    @Test
    void checkerCanRejectInactiveMakerProposalWithoutTouchingGate() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(proposal(7));
        when(db.checkSwitchCase(9, 88, 8, "REJECTED")).thenReturn(1);
        AdminMapper admins = mock(AdminMapper.class);
        PlatformTransactionManager tx = transactionManager();

        assertThat(service(db, admins, tx, mock(AuditLogService.class))
                .check(8, 9, "REJECT", "Maker disabled; reject old proposal"))
                .containsEntry("status", "REJECTED");

        verifyNoInteractions(admins);
        verify(db, never()).lockProvisionGate();
        verify(db, never()).setSwitches(anyInt(), anyInt(), anyInt(), any(String.class), anyLong());
        verify(tx).commit(any());
    }

    @Test
    void successfulActiveMakerCheckStillRollsBackWhenRequiredAuditFails() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(Map.of("projectId",88L,"status","MAKER_DONE","makerId",7L,
                "expectedVersion",4L,"assignEnabled",0,"creditEnabled",0,"payoutEnabled",0));
        when(db.lockProvisionGate()).thenReturn(Map.of("version",4L,"state","IDLE",
                "assignEnabled",1,"creditEnabled",1,"payoutEnabled",1));
        when(db.setSwitches(0,0,0,"Close every switch",4)).thenReturn(1);
        when(db.checkSwitchCase(9,88,8,"RESOLVED")).thenReturn(1);
        PlatformTransactionManager tx = transactionManager();
        AuditLogService audit = mock(AuditLogService.class);
        doThrow(new IllegalStateException("AUDIT_UNAVAILABLE")).when(audit).recordRequired(any());

        assertThatThrownBy(() -> service(db, activeAdmins(), tx, audit)
                .check(8,9,"APPROVE","Close every switch"))
                .hasMessage("AUDIT_UNAVAILABLE");

        verify(db).setSwitches(0,0,0,"Close every switch",4);
        verify(tx).rollback(any());
        verify(tx, never()).commit(any());
    }

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

    @Test
    void closingOnlyPayoutRetainsDepositBitsDespiteStaleReconcileAndRiskHold() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockSwitchCase(9)).thenReturn(Map.of("projectId",88L,"status","MAKER_DONE","makerId",7L,
                "expectedVersion",4L,"assignEnabled",1,"creditEnabled",1,"payoutEnabled",0));
        when(db.lockProvisionGate()).thenReturn(Map.of("version",4L,"state","IDLE",
                "assignEnabled",1,"creditEnabled",1,"payoutEnabled",1));
        when(db.unresolvedExposure(88)).thenReturn(new BigDecimal("700"));
        when(db.setSwitches(1,1,0,"Close outbound only",4)).thenReturn(1);
        when(db.checkSwitchCase(9,88,8,"RESOLVED")).thenReturn(1);
        assertThat(service(db).check(8,9,"APPROVE","Close outbound only"))
                .containsEntry("status","RESOLVED");
        verify(db).setSwitches(1,1,0,"Close outbound only",4);
        verify(db,never()).recentCompleteReconcileCount(88);
        verify(db,never()).resolveManualEmergencyAlerts(88);
    }

    @Test
    void omittedPayoutPreservesLockedBitInsteadOfTurningItOff() {
        CregisDepositMapper db = mock(CregisDepositMapper.class);
        when(db.lockProvisionGate()).thenReturn(Map.of("version",4L,"payoutEnabled",1));
        when(db.createSwitchCase(88,4,1,0,1,"Change inbound",7)).thenReturn(1);
        when(db.lastInsertId()).thenReturn(9L);
        service(db).propose(7,4,true,false,"Change inbound");
        verify(db).createSwitchCase(88,4,1,0,1,"Change inbound",7);
    }

    private static CregisDepositSwitchService service(CregisDepositMapper db) {
        return service(db, activeAdmins(), transactionManager(), mock(AuditLogService.class));
    }

    private static AdminMapper activeAdmins() {
        AdminMapper admins = mock(AdminMapper.class);
        AdminEntity maker = new AdminEntity();
        maker.setStatus(1);
        maker.setIsDeleted(0);
        when(admins.selectActiveForUpdate(7L)).thenReturn(maker);
        return admins;
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return tx;
    }

    private static CregisDepositSwitchService service(CregisDepositMapper db, AdminMapper admins,
            PlatformTransactionManager tx, AuditLogService audit) {
        CregisProperties config = new CregisProperties();
        config.setMode(CregisProperties.Mode.PROVIDER);
        config.setProjectId(88);
        config.setDepositEnabled(true);
        config.setDepositCreditEnabled(true);
        return new CregisDepositSwitchService(config, db, audit, tx, mock(CregisPayoutControl.class), admins);
    }
}
