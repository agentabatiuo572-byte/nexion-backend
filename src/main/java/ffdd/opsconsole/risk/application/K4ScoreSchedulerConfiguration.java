package ffdd.opsconsole.risk.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps K4 refreshes off shared workers occupied by settlement and outbox tasks. */
@Configuration(proxyBeanMethods = false)
public class K4ScoreSchedulerConfiguration {
    @Bean(name = "k4ScoreTaskScheduler")
    ThreadPoolTaskScheduler k4ScoreTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("k4-score-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
