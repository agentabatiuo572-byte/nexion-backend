package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.AssignmentMemberRow;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BinaryAssignmentReadServiceTest {
    final BinaryCommissionSettlementMapper mapper = mock(BinaryCommissionSettlementMapper.class);
    final BinarySettlementPolicyProvider policy = mock(BinarySettlementPolicyProvider.class);
    final TreasuryLedgerPostingFacade funds = mock(TreasuryLedgerPostingFacade.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    final EventOutboxService outbox = mock(EventOutboxService.class);
    final AdminIdempotencyService keys = mock(AdminIdempotencyService.class);
    final BinaryCommissionSettlementService service =
            new BinaryCommissionSettlementService(mapper, policy, funds, audit, config, outbox, keys);

    @Test void readsExactOwnerAndMemberWithPaginationWithoutWriters() {
        var row = new AssignmentMemberRow(42L, 80L, "B", "operator",
                LocalDateTime.of(2026, 10, 16, 1, 0), "MANUAL");
        when(mapper.userSandbox(42L)).thenReturn(0);
        when(mapper.countAssignmentMembers(42L, 80L, "ALL")).thenReturn(1L);
        when(mapper.listAssignmentMembers(42L, 80L, "ALL", 20L, 20)).thenReturn(List.of(row));
        var result = service.assignments(42L, 80L, "ALL", 2, 20);
        assertThat(result.ownerUserId()).isEqualTo(42L);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).containsExactly(row);
        verify(mapper).userSandbox(42L);
        verify(mapper).countAssignmentMembers(42L, 80L, "ALL");
        verify(mapper).listAssignmentMembers(42L, 80L, "ALL", 20L, 20);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(policy, funds, audit, config, outbox, keys);
    }

    @Test void rejectsBadBoundsAndStatusesBeforeReadingAnything() {
        assertInvalid(null, null, "ALL", 1, 20);
        assertInvalid(0L, null, "ALL", 1, 20);
        assertInvalid(42L, -1L, "ALL", 1, 20);
        assertInvalid(42L, null, "UNKNOWN", 1, 20);
        assertInvalid(42L, null, null, 1, 20);
        assertInvalid(42L, null, "ALL", 0, 20);
        assertInvalid(42L, null, "ALL", 1, 0);
        assertInvalid(42L, null, "ALL", 1, 101);
        verifyNoInteractions(mapper, policy, funds, audit, config, outbox, keys);
    }

    @Test void missingOrInactiveOwnerIsNotAnEmptySuccess() {
        when(mapper.userSandbox(42L)).thenReturn(null);
        assertThatThrownBy(() -> service.assignments(42L, null, "ALL", 1, 20))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        verify(mapper).userSandbox(42L);
        verifyNoMoreInteractions(mapper);
    }

    @Test void emptyPendingIsSuccessfulAndLargePagesDoNotOverflow() {
        when(mapper.userSandbox(42L)).thenReturn(0);
        when(mapper.listAssignmentMembers(42L, null, "UNASSIGNED", 214748364600L, 100))
                .thenReturn(List.of());
        var page = service.assignments(42L, null, "UNASSIGNED", Integer.MAX_VALUE, 100);
        assertThat(page.total()).isZero();
        assertThat(page.records()).isEmpty();
        verify(mapper).listAssignmentMembers(42L, null, "UNASSIGNED", 214748364600L, 100);
    }

    private void assertInvalid(Long owner, Long member, String status, int page, int size) {
        assertThatThrownBy(() -> service.assignments(owner, member, status, page, size))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
    }
}
