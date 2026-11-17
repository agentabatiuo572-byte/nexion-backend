package ffdd.opsconsole.market.application;

import jakarta.annotation.PreDestroy;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NexMarketCurveScheduler implements SchedulingConfigurer {
    private final OpsNexMarketService marketService;
    private final G3ScheduledAdvanceService scheduledAdvanceService;
    private final AuditLogService audit;
    private final Clock clock;
    private ScheduledTaskRegistrar taskRegistrar;
    private ScheduledTask scheduledTask;

    @Override
    public synchronized void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        this.taskRegistrar = taskRegistrar;
        refreshSchedule();
    }

    @TransactionalEventListener(fallbackExecution = true)
    synchronized void scheduleChanged(NexMarketSchedule.Changed event) {
        if (taskRegistrar != null) {
            refreshSchedule();
        }
    }

    private void refreshSchedule() {
        cancelSchedule();
        scheduledTask = taskRegistrar.scheduleTriggerTask(
                new TriggerTask(this::advanceAndRecord, this::nextExecution));
    }

    private void advanceAndRecord() {
        Instant attemptedAt = clock.instant();
        Map<String, Object> result;
        try {
            // The proxied method returns only after its original transaction has committed.
            result = scheduledAdvanceService.advanceIfDue();
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            String code = message != null && Set.of("B1_COVERAGE_UNRELIABLE", "COVERAGE_BELOW_REDLINE",
                    "G3_WEEKLY_CURVE_INVALID", "AUDIT_REQUIRED_DISABLED", "AUDIT_REQUIRED_INVALID").contains(message)
                    ? message : failure instanceof org.springframework.dao.DataAccessException ? "PERSISTENCE_FAILURE"
                    : failure instanceof org.springframework.transaction.TransactionException ? "TRANSACTION_FAILURE"
                    : "G3_SCHEDULE_EXECUTION_FAILED";
            try {
                recordExecution(attemptedAt, Map.of("outcome", "FAILED", "reasonCode", code));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
        // An audit failure here must not turn a committed advance into a fabricated business failure.
        recordExecution(attemptedAt, result);
    }

    private void recordExecution(Instant attemptedAt, Map<String, Object> result) {
        Map<String, Object> detail = new LinkedHashMap<>(result);
        detail.put("sourceDomain", "G3");
        detail.put("attemptedAt", attemptedAt.toString());
        detail.put("completedAt", clock.instant().toString());
        boolean failed = "FAILED".equals(result.get("outcome"));
        audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("G3_SCHEDULE_EXECUTION").resourceType("NEX_MARKET_CURVE").resourceId("weekly")
                .actorType("SYSTEM").actorUsername("system").result(failed ? "FAILED" : "SUCCESS")
                .riskLevel(failed ? "HIGH" : "INFO").detail(detail).build());
    }

    @PreDestroy
    synchronized void cancelSchedule() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
    }

    Instant nextExecution(TriggerContext triggerContext) {
        NexMarketSchedule schedule = marketService.currentSchedule();
        if (!StringUtils.hasText(schedule.cronExpression())) {
            return null;
        }
        return new CronTrigger(schedule.cronExpression(), schedule.zoneId()).nextExecution(triggerContext);
    }
}
