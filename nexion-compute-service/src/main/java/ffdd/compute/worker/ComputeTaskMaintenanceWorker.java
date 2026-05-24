package ffdd.compute.worker;

import ffdd.compute.dto.TaskMaintenanceResult;
import ffdd.compute.service.ComputeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ComputeTaskMaintenanceWorker {
    private static final Logger log = LoggerFactory.getLogger(ComputeTaskMaintenanceWorker.class);

    private final ComputeService computeService;
    private final boolean enabled;
    private final int batchSize;

    public ComputeTaskMaintenanceWorker(
            ComputeService computeService,
            @Value("${nexion.compute.task.maintenance.enabled:false}") boolean enabled,
            @Value("${nexion.compute.task.maintenance.batch-size:20}") int batchSize) {
        this.computeService = computeService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString = "${nexion.compute.task.maintenance.initial-delay-ms:10000}",
            fixedDelayString = "${nexion.compute.task.maintenance.fixed-delay-ms:10000}")
    public void maintainTasks() {
        if (!enabled) {
            return;
        }
        try {
            TaskMaintenanceResult timeoutResult = computeService.processTaskTimeouts(batchSize);
            TaskMaintenanceResult retryResult = computeService.retryDueTasks(batchSize);
            if (timeoutResult.getScanned() > 0 || retryResult.getScanned() > 0) {
                log.info("Compute task maintenance timeouts scanned={}, retryScheduled={}, failed={}, skipped={}; "
                                + "retries scanned={}, retried={}, skipped={}",
                        timeoutResult.getScanned(),
                        timeoutResult.getRetryScheduled(),
                        timeoutResult.getFailed(),
                        timeoutResult.getSkipped(),
                        retryResult.getScanned(),
                        retryResult.getRetried(),
                        retryResult.getSkipped());
            }
        } catch (RuntimeException ex) {
            log.warn("Compute task maintenance worker failed: {}", ex.getMessage());
        }
    }
}
