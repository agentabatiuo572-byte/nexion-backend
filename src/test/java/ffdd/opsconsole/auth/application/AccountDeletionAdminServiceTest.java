package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AdminAccountDeletionCommandRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AccountDeletionAdminServiceTest {
    private final AppUserSecurityMapper mapper = mock(AppUserSecurityMapper.class);
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AccountDeletionAdminService service = new AccountDeletionAdminService(mapper, sessions, audit, idempotency);

    @Test
    void completionFailsClosedAndLeavesAccountAndSessionsUntouchedWhenLedgerIsOpen() {
        Map<String, Object> row = row("IN_REVIEW", 3L);
        Map<String, Object> blocked = row("BLOCKED", 4L);
        when(mapper.accountDeletionByRequestNoForUpdate("ADR-0123456789abcdef0123456789abcdef"))
                .thenReturn(row).thenReturn(blocked);
        when(mapper.hasUnsettledFundsOrOrders(42L)).thenReturn(1);
        when(mapper.transitionAccountDeletion("ADR-0123456789abcdef0123456789abcdef", "IN_REVIEW", "BLOCKED",
                3L, "UNSETTLED_FUNDS_OR_ORDERS", null)).thenReturn(1);
        runIdempotently();

        var result = service.complete("ADR-0123456789abcdef0123456789abcdef", "key",
                new AdminAccountDeletionCommandRequest(3L, "operator review"));

        assertThat(result.status()).isEqualTo("BLOCKED");
        verify(mapper, never()).disableAndAnonymizeUser(anyLong());
        verify(sessions, never()).revokeAllUserSessions(anyLong());
    }

    private void runIdempotently() {
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    private Map<String, Object> row(String status, long version) {
        return Map.of(
                "requestNo", "ADR-0123456789abcdef0123456789abcdef", "userId", 42L,
                "status", status, "version", version, "requestedAt", LocalDateTime.now());
    }
}
