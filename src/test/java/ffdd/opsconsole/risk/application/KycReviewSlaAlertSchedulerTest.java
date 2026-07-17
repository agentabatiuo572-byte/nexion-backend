package ffdd.opsconsole.risk.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import org.junit.jupiter.api.Test;

class KycReviewSlaAlertSchedulerTest {
    @Test
    void scheduledPassProjectsOverdueTicketsIntoIdempotentAlerts() {
        RiskOpsRepository repository = mock(RiskOpsRepository.class);

        new KycReviewSlaAlertScheduler(repository).generateOverdueAlerts();

        verify(repository).generateOverdueKycAlerts();
        verify(repository).generateLargeWithdrawalBurstKycAlerts();
    }
}
