package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.domain.KycReviewTicketContext;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RiskKycReviewFacadeAdapterTest {
    @Test
    void repeatedD2TriggerMergesIntoExistingOpenTicket() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.kycLargeWithdrawReviewUsdt()).thenReturn(1000);
        when(repository.findOpenKycReviewTicketByUser("usr-1")).thenReturn(Optional.of(
                new KycReviewTicketContext("KR-1", "大额提现", "usr-1", "in-review", "[]", 3L)));
        when(repository.mergeOpenKycReviewTicket("KR-1", 3L,
                "D2:WD-2 · repeated withdrawal signal", "risk-admin")).thenReturn(true);
        RiskKycReviewFacadeAdapter facade = new RiskKycReviewFacadeAdapter(repository, audit);

        var result = facade.triggerLargeWithdrawalReview("usr-1", new BigDecimal("1200"),
                "PENDING", "WD-2", "risk-admin", "repeated withdrawal signal");

        assertThat(result.requiresReview()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.ticketId()).isEqualTo("KR-1");
        verify(repository).mergeOpenKycReviewTicket("KR-1", 3L,
                "D2:WD-2 · repeated withdrawal signal", "risk-admin");
        verify(repository).linkKycReviewSource("KR-1", "D2", "WD-2");
        verify(audit).recordRequired(org.mockito.ArgumentMatchers.argThat(
                entry -> "K5_KYC_REVIEW_TRIGGER_MERGED_D2".equals(entry.getAction())));
    }
}
