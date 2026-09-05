package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.AppOrderCommandMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppOrderExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(AppOrderExpiryScheduler.class);
    private final AppOrderCommandMapper mapper;
    private final AppOrderCommandService service;
    @SuppressWarnings("ArchitectureConfigField")
    private final int ttlMinutes;
    @SuppressWarnings("ArchitectureConfigField")
    private final int batchSize;

    public AppOrderExpiryScheduler(
            AppOrderCommandMapper mapper,
            AppOrderCommandService service,
            @Value("${nexion.commerce.pending-order-ttl-minutes:30}") int ttlMinutes,
            @Value("${nexion.commerce.pending-order-expiry-batch-size:100}") int batchSize) {
        this.mapper = mapper;
        this.service = service;
        this.ttlMinutes = Math.max(1, ttlMinutes);
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(fixedDelayString = "${nexion.commerce.pending-order-expiry-delay-ms:60000}")
    public void expirePendingOrders() {
        List<AppOrderCommandMapper.PendingOrderExpiryCandidate> candidates =
                mapper.expiredPendingOrders(ttlMinutes, batchSize);
        if (candidates == null) return;
        for (AppOrderCommandMapper.PendingOrderExpiryCandidate candidate : candidates) {
            if (candidate == null || candidate.userId() == null || candidate.orderNo() == null) continue;
            try {
                service.expirePendingOrder(candidate.userId(), candidate.orderNo());
            } catch (RuntimeException failure) {
                log.error("Pending commerce order expiry failed for order {}", candidate.orderNo(), failure);
            }
        }
    }
}
