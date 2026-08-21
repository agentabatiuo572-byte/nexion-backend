package ffdd.opsconsole.developer.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Isolates webhook outbox work from the shared scheduler. The canonical dispatcher can spend time on a
 * large/slow event backlog without preventing the delivery worker from reclaiming and claiming rows.
 */
@Configuration
public class DeveloperWebhookSchedulerConfiguration {
    private final Environment environment;

    public DeveloperWebhookSchedulerConfiguration(Environment environment) {
        this.environment = environment;
    }

    /**
     * Spring's default @Scheduled resolution looks for the conventional taskScheduler bean. Defining a
     * dedicated scheduler without restoring this bean makes every unqualified scheduled task fall back to
     * the only available executor, which would incorrectly place unrelated jobs on developer-webhook-*.
     */
    @Bean(name = "taskScheduler")
    @Primary
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, environment.getProperty(
                "spring.task.scheduling.pool.size", Integer.class, 1)));
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Bean(name = "developerWebhookTaskScheduler")
    ThreadPoolTaskScheduler developerWebhookTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("developer-webhook-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
