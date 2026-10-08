package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bounded DB scan; candidate absence is a wait state, never an outbox failure. */
@Component
@RequiredArgsConstructor
public class SupportBindingRetry {
    private static final Logger LOG=LoggerFactory.getLogger(SupportBindingRetry.class);
    private final SupportBindingMapper mapper;
    private final SupportBindingService service;
    private final ProductionSupportPathGuard production;

    @Scheduled(fixedDelayString="${nexion.support.binding.retry-delay-ms:60000}")
    public void retry() {
        if(!production.productionSupportAutomationAllowed()) return;
        for(Long customer:mapper.autoPending()) {
            try {service.retryAutomatic(customer);}
            catch(RuntimeException ex) {LOG.warn("Automatic support assignment retry failed: {}",ex.getClass().getSimpleName());}
        }
    }
}
