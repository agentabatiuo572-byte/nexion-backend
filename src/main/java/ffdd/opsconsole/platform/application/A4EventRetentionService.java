package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.bi.mapper.BehaviorAnalyticsMapper;
import ffdd.opsconsole.platform.mapper.EventGovernanceMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class A4EventRetentionService {
    static final int BATCH_SIZE = 200;
    static final int MAX_BATCHES_PER_RUN = 10;
    private final EventGovernanceMapper mapper;
    private final BehaviorAnalyticsMapper behaviorMapper;
    private final AuditLogService auditLogService;
    private final A4RuntimePolicyService policy;

    @Scheduled(cron = "${nexion.events.retention.cron:0 45 3 * * *}")
    @Transactional(rollbackFor = Exception.class)
    public void scheduledCleanup() {
        RetentionRun run = runNow();
        log.info("A4 event retention completed lockAcquired={} months={} outboxRows={} behaviorFactRows={} cutoff={}",
                run.lockAcquired(), run.retentionMonths(), run.outboxRows(), run.behaviorFactRows(), run.cutoff());
    }

    @Transactional(rollbackFor = Exception.class)
    public RetentionRun runNow() {
        LocalDateTime evaluatedAt = LocalDateTime.now();
        int months = policy.eventRetentionMonths();
        if (!Integer.valueOf(1).equals(mapper.tryAcquireRetentionLock())) {
            return new RetentionRun(months, evaluatedAt, 0, 0, false);
        }
        try {
            LocalDateTime cutoff = evaluatedAt.minusMonths(months);
            int outbox = drainOutbox(cutoff);
            int behaviorFacts = drainBehaviorFacts(cutoff);
            auditLogService.recordRequired(AuditLogWriteRequest.builder()
                    .action("A4_EVENT_RETENTION_EXECUTED")
                    .resourceType("A4_EVENT_RETENTION")
                    .resourceId(cutoff.toLocalDate().toString())
                    .actorType("SYSTEM").actorUsername("system")
                    .result("SUCCESS").riskLevel("MEDIUM")
                    .detail(Map.of("retentionMonths", months, "cutoff", cutoff.toString(),
                            "batchSize", BATCH_SIZE, "maxBatches", MAX_BATCHES_PER_RUN,
                            "outboxRows", outbox, "behaviorFactRows", behaviorFacts,
                            "terminalOutboxOnly", true, "idempotent", true))
                    .build());
            return new RetentionRun(months, cutoff, outbox, behaviorFacts, true);
        } finally {
            mapper.releaseRetentionLock();
        }
    }

    private int drainOutbox(LocalDateTime cutoff) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int affected = mapper.deleteTerminalAnalyticsEventsBefore(cutoff, BATCH_SIZE);
            total += affected;
            if (affected < BATCH_SIZE) break;
        }
        return total;
    }

    private int drainBehaviorFacts(LocalDateTime cutoff) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int affected = behaviorMapper.deleteExpiredFacts(cutoff, BATCH_SIZE);
            total += affected;
            if (affected < BATCH_SIZE) break;
        }
        return total;
    }

    public record RetentionRun(
            int retentionMonths, LocalDateTime cutoff, int outboxRows, int behaviorFactRows, boolean lockAcquired) {
        public int affectedRows() { return outboxRows + behaviorFactRows; }
    }
}
