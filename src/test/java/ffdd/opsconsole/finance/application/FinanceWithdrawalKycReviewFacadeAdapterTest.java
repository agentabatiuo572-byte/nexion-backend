package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.shared.audit.AuditLogService;
import org.junit.jupiter.api.Test;

class FinanceWithdrawalKycReviewFacadeAdapterTest {
    @Test
    void passedK5ReviewReturnsWithdrawalToReviewInsteadOfChainSubmission() {
        WithdrawalOrderRepository repository = mock(WithdrawalOrderRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.transitionK5FrozenStatus("WD-100", "KR-100", "REVIEWING", null)).thenReturn(true);
        FinanceWithdrawalKycReviewFacadeAdapter facade = new FinanceWithdrawalKycReviewFacadeAdapter(repository, audit);

        assertThat(facade.releaseWithdrawalReview("WD-100", "KR-100", "K5 passed", "risk-admin")).isTrue();

        verify(repository).transitionK5FrozenStatus("WD-100", "KR-100", "REVIEWING", null);
        verify(audit).record(org.mockito.ArgumentMatchers.any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"REJECTED", "PAID", "NOT_FOUND"})
    void terminalOrMissingWithdrawalCannotBeChangedByK5(String currentState) {
        WithdrawalOrderRepository repository = mock(WithdrawalOrderRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        when(repository.transitionK5FrozenStatus("WD-LOCKED", "KR-LOCKED", "REVIEWING", null)).thenReturn(false);
        FinanceWithdrawalKycReviewFacadeAdapter facade = new FinanceWithdrawalKycReviewFacadeAdapter(repository, audit);

        assertThat(facade.releaseWithdrawalReview(
                "WD-LOCKED", "KR-LOCKED", "K5 passed from " + currentState, "risk-admin")).isFalse();

        verify(audit, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mapperTransitionsRequireTheExactK5TicketHold() throws Exception {
        var update = ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper.class.getMethod(
                        "transitionK5FrozenStatus", String.class, String.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class);
        String sql = String.join(" ", update.value());
        assertThat(sql).contains("status = 'FROZEN'", "failure_reason = CONCAT('K5_REVIEW:', #{ticketId})");

        var freeze = ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper.class.getMethod(
                        "freezeForK5Review", String.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class);
        assertThat(String.join(" ", freeze.value()))
                .contains("status = #{expectedStatus}", "failure_reason = CONCAT('K5_REVIEW:', #{ticketId})");
    }
}
