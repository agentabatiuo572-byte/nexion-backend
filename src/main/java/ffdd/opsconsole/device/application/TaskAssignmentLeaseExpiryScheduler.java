package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, cursor-based cleanup for expired App-task leases. */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskAssignmentLeaseExpiryScheduler {
    private final AppTaskAssignmentMapper mapper;
    private final AppTaskAssignmentService service;
    @Value("${nexion.tasks.assignment-expiry-batch-size:100}")
    private final int batchSize;
    private final Clock clock;
    private final AtomicLong afterTaskId = new AtomicLong();

    @Scheduled(fixedDelayString = "${nexion.tasks.assignment-expiry-delay-ms:30000}")
    public synchronized void expireLeases() {
        int limit = Math.max(1, Math.min(batchSize, 500));
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        long cursor = afterTaskId.get();
        List<AppTaskAssignmentMapper.LeaseExpiryCandidate> candidates =
                mapper.leaseExpiryCandidates(cursor, limit, now);
        if ((candidates == null || candidates.isEmpty()) && cursor > 0) {
            cursor = 0;
            afterTaskId.set(0);
            candidates = mapper.leaseExpiryCandidates(cursor, limit, now);
        }
        if (candidates == null || candidates.isEmpty()) return;
        afterTaskId.set(candidates.stream().filter(candidate -> candidate != null && candidate.taskId() != null
                        && candidate.taskId() > 0).mapToLong(AppTaskAssignmentMapper.LeaseExpiryCandidate::taskId)
                .max().orElse(cursor));
        for (AppTaskAssignmentMapper.LeaseExpiryCandidate candidate : candidates) {
            if (candidate == null || candidate.userId() == null || candidate.userId() <= 0
                    || candidate.deviceId() == null || candidate.deviceId() <= 0
                    || candidate.taskNo() == null || candidate.taskNo().isBlank()) continue;
            try {
                service.expirePendingLease(candidate.userId(), candidate.deviceId(), candidate.taskNo());
            } catch (BizException expectedStateChange) {
                if (expectedStateChange.getCode() == 404 || expectedStateChange.getCode() == 409) {
                    log.debug("Task expiry candidate became ineligible for user {} device {} task {}: {}",
                            candidate.userId(), candidate.deviceId(), candidate.taskNo(), expectedStateChange.getMessage());
                } else {
                    log.warn("Task expiry cleanup rejected for user {} device {} task {}: {}",
                            candidate.userId(), candidate.deviceId(), candidate.taskNo(), expectedStateChange.getMessage());
                }
            } catch (RuntimeException failure) {
                log.error("Task expiry cleanup failed for user {} device {} task {}",
                        candidate.userId(), candidate.deviceId(), candidate.taskNo(), failure);
            }
        }
    }
}
