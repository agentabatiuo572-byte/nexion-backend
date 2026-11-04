package ffdd.opsconsole.market.application;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
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
                new TriggerTask(scheduledAdvanceService::advanceIfDue, this::nextExecution));
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
