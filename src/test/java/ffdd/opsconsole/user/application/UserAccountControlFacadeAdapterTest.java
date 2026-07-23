package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserAccountControlFacadeAdapterTest {
    private final UserOpsRepository users = mock(UserOpsRepository.class);
    private final FinanceWithdrawalControlFacade withdrawals = mock(FinanceWithdrawalControlFacade.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final UserAccountControlFacadeAdapter facade =
            new UserAccountControlFacadeAdapter(users, withdrawals, audits, outbox);

    @Test
    void k1FreezePersistsSourceAndFreezesD2() {
        when(users.findUserIdByLookupKey("U00000001")).thenReturn(Optional.of(1L));
        when(users.findById(1L)).thenReturn(Optional.of(account("ACTIVE")));
        when(users.freezeUserStatusWithSource(
                1L, "ACTIVE", "cluster confirmed", "risk", "K1_MULTI_ACCOUNT_CLUSTER", "CL-1"))
                .thenReturn(true);
        when(withdrawals.freezePendingWithdrawalsForUser(1L, "cluster confirmed", "risk")).thenReturn(2);

        int changed = facade.freezeActiveUsersByUserNos(
                List.of("U00000001"), "cluster confirmed", "risk", "CL-1");

        assertThat(changed).isEqualTo(1);
        verify(users).revokeUserSessions(1L, "cluster confirmed");
        verify(withdrawals).freezePendingWithdrawalsForUser(1L, "cluster confirmed", "risk");
    }

    @Test
    void k1ReleaseOnlyRestoresMatchingSourceAndD2Rows() {
        when(users.findUserIdByLookupKey("U00000001")).thenReturn(Optional.of(1L));
        when(users.restoreUserStatusByFreezeSource(1L, "K1_MULTI_ACCOUNT_CLUSTER", "CL-1"))
                .thenReturn(true);
        when(withdrawals.restoreWithdrawalsFrozenByUserStatus(1L, "false positive", "risk")).thenReturn(2);

        int changed = facade.restoreUsersFrozenBySource(
                List.of("U00000001"), "false positive", "risk", "CL-1");

        assertThat(changed).isEqualTo(1);
        verify(withdrawals).restoreWithdrawalsFrozenByUserStatus(1L, "false positive", "risk");
        verify(audits).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "C2_USER_STATUS_RESTORED_BY_K1".equals(request.getAction())));
    }

    private UserAccountView account(String status) {
        return new UserAccountView(
                1L, "U00000001", "Alice", "138****8000", "86", status, "APPROVED", "L1", "V1", true,
                BigDecimal.TEN, BigDecimal.ONE, 20, "低风险", 1L, 1L,
                LocalDateTime.now().minusDays(1), LocalDateTime.now());
    }
}
