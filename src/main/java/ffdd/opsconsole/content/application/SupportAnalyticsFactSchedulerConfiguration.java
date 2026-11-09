package ffdd.opsconsole.content.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
public class SupportAnalyticsFactSchedulerConfiguration {
    @Bean(name="supportAnalyticsFactTaskScheduler")
    ThreadPoolTaskScheduler supportAnalyticsFactTaskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("support-analytics-facts-");
        // Early lifecycle stop drains the current sample before Redis/SQL; future cron runs are discarded.
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
