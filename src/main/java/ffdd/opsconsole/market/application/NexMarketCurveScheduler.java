package ffdd.opsconsole.market.application;


import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NexMarketCurveScheduler implements SchedulingConfigurer {
    private final OpsNexMarketService marketService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(marketService::advanceScheduledFrame, this::nextExecution);
    }

    java.time.Instant nextExecution(TriggerContext triggerContext) {
        NexMarketSchedule schedule = marketService.currentSchedule();
        return new CronTrigger(schedule.cronExpression(), schedule.zoneId()).nextExecution(triggerContext);
    }
}
