package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AdminAccountDeletionCommandRequest;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AccountDeletionAdminServiceTest {
    private final AppUserSecurityMapper mapper = mock(AppUserSecurityMapper.class);
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AccountDeletionAdminService service = new AccountDeletionAdminService(mapper, sessions, audit, idempotency, org.mockito.Mockito.mock(ffdd.opsconsole.shared.security.UserSessionRevocationService.class));

    @Test
    void detailReadUsesANonLockingQueryAndKeepsNullableFields() throws Exception {
        String requestNo = "ADR-0123456789abcdef0123456789abcdef";
        Map<String, Object> record = row("REQUESTED", 0L);
        AppUserSecurityMapper detailMapper = mock(AppUserSecurityMapper.class, invocation -> {
            String sql = String.join(" ", invocation.getMethod().getAnnotation(Select.class).value());
            assertThat(sql).contains("request_no=#{requestNo}", "is_deleted=0", "LIMIT 1")
                    .doesNotContain("FOR UPDATE");
            assertThat(invocation.<String>getArgument(0)).isEqualTo(requestNo);
            return record;
        });
        var readService = new AccountDeletionAdminService(detailMapper, sessions, audit, idempotency, org.mockito.Mockito.mock(ffdd.opsconsole.shared.security.UserSessionRevocationService.class));

        var result = readService.find(" " + requestNo + " ");

        assertThat(AccountDeletionAdminService.class.getMethod("find", String.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(result.requestNo()).isEqualTo(requestNo);
        assertThat(result.userId()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo("REQUESTED");
        assertThat(result.version()).isZero();
        assertThat(result.requestedAt()).isEqualTo(record.get("requestedAt"));
        assertThat(result.reviewedAt()).isNull();
        assertThat(result.completedAt()).isNull();
        assertThat(result.reason()).isNull();
        assertThat(result.blockReason()).isNull();
        assertThat(result.sessionsRevoked()).isFalse();
        assertThat(result.accountDisabled()).isFalse();
        verify(detailMapper, never()).accountDeletionByRequestNoForUpdate(anyString());
        verifyNoInteractions(sessions, audit, idempotency);
    }

    @Test
    void detailLookupKeepsNotFoundAndMalformedInputErrorsWithoutSideEffects() {
        AppUserSecurityMapper notFoundMapper = mock(AppUserSecurityMapper.class, invocation -> null);
        var readService = new AccountDeletionAdminService(notFoundMapper, sessions, audit, idempotency, org.mockito.Mockito.mock(ffdd.opsconsole.shared.security.UserSessionRevocationService.class));
        for (String requestNo : new String[]{null, " ", "ADR-invalid"}) {
            assertThatThrownBy(() -> readService.find(requestNo))
                    .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(422));
        }
        verifyNoInteractions(notFoundMapper, sessions, audit, idempotency);
        assertThatThrownBy(() -> readService.find("ADR-0123456789abcdef0123456789abcdef"))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(404))
                .hasMessage("ACCOUNT_DELETION_REQUEST_NOT_FOUND");
        verifyNoInteractions(sessions, audit, idempotency);
    }

    @Test
    void detailAndCommandQueriesKeepTheSameProjectionAndActiveScopeButDifferentLocks() throws Exception {
        String read = String.join(" ", AppUserSecurityMapper.class
                .getMethod("accountDeletionByRequestNo", String.class).getAnnotation(Select.class).value());
        String locked = String.join(" ", AppUserSecurityMapper.class
                .getMethod("accountDeletionByRequestNoForUpdate", String.class).getAnnotation(Select.class).value());

        assertThat(locked).contains("FOR UPDATE");
        assertThat(read).doesNotContain("FOR UPDATE");
        assertThat(read.strip()).isEqualTo(locked.replace(" FOR UPDATE", "").strip());
    }

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

    @Test
    void pageReturnsAuthoritativeTotalAndSeparatedRecordsForFirstAndSecondPages() {
        when(mapper.countAccountDeletions("REQUESTED")).thenReturn(3L);
        when(mapper.listAccountDeletions("REQUESTED", 0, 2)).thenReturn(List.of(row("REQUESTED", 1L), row("REQUESTED", 2L)));
        when(mapper.listAccountDeletions("REQUESTED", 2, 2)).thenReturn(List.of(row("REQUESTED", 3L)));

        var first = service.page(" requested ", 1, 2);
        var second = service.page("REQUESTED", 2, 2);

        assertThat(first.total()).isEqualTo(3L);
        assertThat(first.page()).isEqualTo(1);
        assertThat(first.limit()).isEqualTo(2);
        assertThat(first.records()).hasSize(2);
        assertThat(second.total()).isEqualTo(3L);
        assertThat(second.page()).isEqualTo(2);
        assertThat(second.records()).hasSize(1);
    }

    @Test
    void pageKeepsStatusFilterForCountAndEmptyPage() {
        when(mapper.countAccountDeletions("BLOCKED")).thenReturn(1L);
        when(mapper.listAccountDeletions("BLOCKED", 10, 5)).thenReturn(List.of());

        var empty = service.page("blocked", 3, 5);

        assertThat(empty.total()).isEqualTo(1L);
        assertThat(empty.records()).isEmpty();
        verify(mapper).countAccountDeletions("BLOCKED");
        verify(mapper).listAccountDeletions("BLOCKED", 10, 5);
    }

    @Test
    void pageNormalizesRequestedBoundsBeforeCountingAndReading() {
        when(mapper.countAccountDeletions(null)).thenReturn(0L);
        when(mapper.listAccountDeletions(null, 0, 100)).thenReturn(List.of());

        var normalized = service.page(null, 0, 1_000);

        assertThat(normalized.page()).isEqualTo(1);
        assertThat(normalized.limit()).isEqualTo(100);
        verify(mapper).countAccountDeletions(null);
        verify(mapper).listAccountDeletions(null, 0, 100);
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
