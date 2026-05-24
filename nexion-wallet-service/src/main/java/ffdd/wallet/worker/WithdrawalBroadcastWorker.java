package ffdd.wallet.worker;

import ffdd.wallet.service.WithdrawalBroadcastResponse;
import ffdd.wallet.service.WithdrawalBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalBroadcastWorker {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalBroadcastWorker.class);

    private final WithdrawalBroadcastService broadcastService;
    private final boolean enabled;
    private final int batchSize;

    public WithdrawalBroadcastWorker(
            WithdrawalBroadcastService broadcastService,
            @Value("${nexion.wallet.withdrawal.broadcast.enabled:false}") boolean enabled,
            @Value("${nexion.wallet.withdrawal.broadcast.batch-size:20}") int batchSize) {
        this.broadcastService = broadcastService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${nexion.wallet.withdrawal.broadcast.initial-delay-ms:10000}",
            fixedDelayString = "${nexion.wallet.withdrawal.broadcast.fixed-delay-ms:10000}")
    public void broadcastScheduled() {
        if (!enabled) {
            return;
        }
        try {
            WithdrawalBroadcastResponse response = broadcastService.broadcastPending(batchSize);
            if (response.getScanned() > 0) {
                log.info("Withdrawal broadcast scanned={}, submitted={}, failed={}, dead={}",
                        response.getScanned(), response.getSubmitted(), response.getFailed(), response.getDead());
            }
        } catch (RuntimeException ex) {
            log.warn("Withdrawal broadcast worker failed: {}", ex.getMessage());
        }
    }
}
