package ffdd.opsconsole.finance.application;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Returns expired D2 extended holds to the server review queue. */
@Component
@RequiredArgsConstructor
public class D2WithdrawalHoldScheduler {

    private final OpsFinanceService opsFinanceService;

    @Scheduled(fixedDelayString = "${nexion.finance.d2-hold-release-delay-ms:30000}")
    @Transactional(rollbackFor = Exception.class)
    public void releaseExpiredHolds() {
        opsFinanceService.releaseExpiredD2Lifecycles(LocalDateTime.now());
    }
}
