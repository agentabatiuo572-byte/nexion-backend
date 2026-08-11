package ffdd.opsconsole.team.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class VRankSkuFulfillmentScheduler {
    private final VRankSkuFulfillmentService fulfillmentService;

    @Scheduled(fixedDelayString = "${nexion.vrank.sku-fulfillment-delay-ms:60000}")
    public void fulfillPending() {
        int fulfilled = fulfillmentService.processPending(25);
        if (fulfilled > 0) log.info("V-Rank SKU fulfillment completed: {}", fulfilled);
    }
}
