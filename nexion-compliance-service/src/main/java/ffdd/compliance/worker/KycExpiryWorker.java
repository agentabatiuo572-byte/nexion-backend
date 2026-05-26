package ffdd.compliance.worker;

import ffdd.compliance.dto.KycExpiryResult;
import ffdd.compliance.service.KycProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KycExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(KycExpiryWorker.class);

    private final KycProfileService kycProfileService;
    private final boolean enabled;
    private final int batchSize;
    private final String reviewer;

    public KycExpiryWorker(
            KycProfileService kycProfileService,
            @Value("${nexion.compliance.kyc-expiry.enabled:false}") boolean enabled,
            @Value("${nexion.compliance.kyc-expiry.batch-size:50}") int batchSize,
            @Value("${nexion.compliance.kyc-expiry.reviewer:system-kyc-expiry}") String reviewer) {
        this.kycProfileService = kycProfileService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.reviewer = reviewer;
    }

    @Scheduled(
            initialDelayString = "${nexion.compliance.kyc-expiry.initial-delay-ms:30000}",
            fixedDelayString = "${nexion.compliance.kyc-expiry.fixed-delay-ms:60000}")
    public void expireScheduled() {
        if (!enabled) {
            return;
        }
        try {
            KycExpiryResult result = kycProfileService.expireApprovedProfiles(batchSize, reviewer);
            if (result.getScanned() > 0) {
                log.info("KYC expiry worker scanned={}, expired={}, skipped={}",
                        result.getScanned(), result.getExpired(), result.getSkipped());
            }
        } catch (RuntimeException ex) {
            log.warn("KYC expiry worker failed: {}", ex.getMessage());
        }
    }
}
