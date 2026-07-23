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
    void g2CumulativeTriggerCreatesTicketWithG2OwnedThresholdAndRequiredAudit() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.findOpenKycReviewTicketByUserForUpdate("usr-g2")).thenReturn(Optional.empty());
        RiskKycReviewFacadeAdapter facade = new RiskKycReviewFacadeAdapter(repository, audit);

        var result = facade.triggerCumulativeExchangeReview(
                "usr-g2", new BigDecimal("25"), new BigDecimal("105"), new BigDecimal("100"),
                "PENDING", "EX-G2-1", "system:g2", "G2 cumulative exchange threshold reached");

        assertThat(result.requiresReview()).isTrue();
        assertThat(result.created()).isTrue();
        assertThat(result.ticketId()).startsWith("KR-G2-");
        verify(repository).createCumulativeExchangeKycReviewTicket(
                org.mockito.ArgumentMatchers.eq(result.ticketId()), org.mockito.ArgumentMatchers.eq("usr-g2"),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("25")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("105")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("100")),
                org.mockito.ArgumentMatchers.eq("EX-G2-1"), org.mockito.ArgumentMatchers.eq("PENDING"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("system:g2"));
        verify(audit).recordRequired(org.mockito.ArgumentMatchers.argThat(entry ->
                "K5_KYC_REVIEW_TRIGGERED_BY_G2_CUMULATIVE".equals(entry.getAction())
                        && "EX-G2-1".equals(entry.getBizNo())));
    }

    @Test
    void distinctG2CumulativeEventsMergeIntoOneOpenTicketAndKeepEverySource() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.findOpenKycReviewTicketByUserForUpdate("usr-g2"))
                .thenReturn(Optional.of(new KycReviewTicketContext(
                        "KR-G2-OPEN", "累计过线", "usr-g2", "in-review", "[]", 2L)),
                        Optional.of(new KycReviewTicketContext(
                                "KR-G2-OPEN", "累计过线", "usr-g2", "in-review", "[]", 3L)));
        when(repository.mergeOpenKycReviewTicket(
                org.mockito.ArgumentMatchers.eq("KR-G2-OPEN"), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("system:g2")))
                .thenReturn(true);
        RiskKycReviewFacadeAdapter facade = new RiskKycReviewFacadeAdapter(repository, audit);

        var first = facade.triggerCumulativeExchangeReview(
                "usr-g2", new BigDecimal("25"), new BigDecimal("105"), new BigDecimal("100"),
                "PENDING", "EX-G2-1", "system:g2", "first threshold event");
        var second = facade.triggerCumulativeExchangeReview(
                "usr-g2", new BigDecimal("30"), new BigDecimal("135"), new BigDecimal("100"),
                "PENDING", "EX-G2-2", "system:g2", "second threshold event");

        assertThat(first.ticketId()).isEqualTo("KR-G2-OPEN");
        assertThat(second.ticketId()).isEqualTo("KR-G2-OPEN");
        verify(repository).linkKycReviewSource("KR-G2-OPEN", "G2", "EX-G2-1");
        verify(repository).linkKycReviewSource("KR-G2-OPEN", "G2", "EX-G2-2");
    }

    @Test
    void repeatedD2TriggerMergesIntoExistingOpenTicket() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.kycLargeWithdrawReviewUsdt()).thenReturn(1000);
        when(repository.findOpenKycReviewTicketByUserForUpdate("usr-1")).thenReturn(Optional.of(
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

    @Test
    void concurrentMergeReloadsLatestVersionAndRetriesOnce() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        var stale = new KycReviewTicketContext("KR-1", "人工复审", "usr-1", "in-review", "[]", 3L);
        var latest = new KycReviewTicketContext("KR-1", "人工复审", "usr-1", "in-review", "[]", 4L);
        when(repository.findOpenKycReviewTicketByUserForUpdate("usr-1"))
                .thenReturn(Optional.of(stale), Optional.of(latest));
        when(repository.mergeOpenKycReviewTicket("KR-1", 3L,
                "C4:usr-1 · concurrent compliance review", "risk-admin")).thenReturn(false);
        when(repository.mergeOpenKycReviewTicket("KR-1", 4L,
                "C4:usr-1 · concurrent compliance review", "risk-admin")).thenReturn(true);
        RiskKycReviewFacadeAdapter facade = new RiskKycReviewFacadeAdapter(repository, audit);

        var result = facade.triggerManualReview("usr-1", "risk-admin", "concurrent compliance review");

        assertThat(result.created()).isFalse();
        assertThat(result.ticketId()).isEqualTo("KR-1");
        verify(repository).mergeOpenKycReviewTicket("KR-1", 3L,
                "C4:usr-1 · concurrent compliance review", "risk-admin");
        verify(repository).mergeOpenKycReviewTicket("KR-1", 4L,
                "C4:usr-1 · concurrent compliance review", "risk-admin");
        verify(repository).linkKycReviewSource("KR-1", "C4", "usr-1");
    }
}
