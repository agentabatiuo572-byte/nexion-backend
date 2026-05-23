package ffdd.team.worker;

import ffdd.team.dto.TeamCommissionConsumeResult;
import ffdd.team.service.TeamCommissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TeamOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(TeamOutboxWorker.class);

    private final TeamCommissionService commissionService;
    private final boolean enabled;
    private final int batchSize;

    public TeamOutboxWorker(
            TeamCommissionService commissionService,
            @Value("${nexion.team.outbox.worker.enabled:true}") boolean enabled,
            @Value("${nexion.team.outbox.worker.batch-size:50}") int batchSize) {
        this.commissionService = commissionService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString = "${nexion.team.outbox.worker.initial-delay-ms:5000}",
            fixedDelayString = "${nexion.team.outbox.worker.fixed-delay-ms:5000}")
    public void consumeOrderPaidOutbox() {
        if (!enabled) {
            return;
        }
        try {
            TeamCommissionConsumeResult result = commissionService.consumeOrderPaid(batchSize);
            if (result.getScanned() > 0 || result.getFailed() > 0) {
                log.info("Team outbox worker scanned={}, processed={}, skipped={}, failed={}",
                        result.getScanned(), result.getProcessed(), result.getSkipped(), result.getFailed());
            }
        } catch (RuntimeException ex) {
            log.warn("Team outbox worker failed: {}", ex.getMessage());
        }
    }
}
