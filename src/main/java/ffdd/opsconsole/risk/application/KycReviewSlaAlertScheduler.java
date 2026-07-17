package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class KycReviewSlaAlertScheduler {
    private final RiskOpsRepository riskRepository;

    @Scheduled(fixedDelayString = "${nexion.risk.k5-sla-alert-delay-ms:60000}")
    @Transactional
    public void generateOverdueAlerts() {
        riskRepository.generateOverdueKycAlerts();
        riskRepository.generateLargeWithdrawalBurstKycAlerts();
    }
}
