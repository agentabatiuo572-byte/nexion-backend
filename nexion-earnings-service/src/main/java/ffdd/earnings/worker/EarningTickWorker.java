package ffdd.earnings.worker;

import ffdd.earnings.dto.EarningTickBatchResult;
import ffdd.earnings.service.EarningTickSettlementService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nexion.earnings.tick.worker", name = "enabled", havingValue = "true")
public class EarningTickWorker {
    private static final Logger log = LoggerFactory.getLogger(EarningTickWorker.class);

    private final EarningTickSettlementService tickSettlementService;
    private final int batchSize;

    public EarningTickWorker(
            EarningTickSettlementService tickSettlementService,
            @Value("${nexion.earnings.tick.batch-size:100}") int batchSize) {
        this.tickSettlementService = tickSettlementService;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            initialDelayString = "${nexion.earnings.tick.worker.initial-delay-ms:60000}",
            fixedDelayString = "${nexion.earnings.tick.worker.fixed-delay-ms:3600000}")
    public void run() {
        try {
            EarningTickBatchResult result = tickSettlementService.settleDeviceTicks(LocalDateTime.now(), batchSize);
            if (result.getRequested() > 0 || result.getMilestoneRewards() > 0) {
                log.info("Earning tick worker requested={}, settled={}, skipped={}, milestoneRewards={}",
                        result.getRequested(),
                        result.getSettled(),
                        result.getSkipped(),
                        result.getMilestoneRewards());
            }
        } catch (RuntimeException ex) {
            log.warn("Earning tick worker failed: {}", ex.getMessage());
        }
    }
}
