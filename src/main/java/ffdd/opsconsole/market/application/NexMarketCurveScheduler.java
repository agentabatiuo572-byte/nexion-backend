package ffdd.opsconsole.market.application;


import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NexMarketCurveScheduler implements SchedulingConfigurer {
    private final OpsNexMarketService marketService;
    private final G3ScheduledAdvanceService scheduledAdvanceService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(
                scheduledAdvanceService == null ? marketService::advanceScheduledFrame : scheduledAdvanceService::advanceIfDue,
                this::nextExecution);
    }

    java.time.Instant nextExecution(TriggerContext triggerContext) {
        NexMarketSchedule schedule = marketService.currentSchedule();
        if (!StringUtils.hasText(schedule.cronExpression())) {
            return null;
        }
        return new CronTrigger(schedule.cronExpression(), schedule.zoneId()).nextExecution(triggerContext);
    }
}
