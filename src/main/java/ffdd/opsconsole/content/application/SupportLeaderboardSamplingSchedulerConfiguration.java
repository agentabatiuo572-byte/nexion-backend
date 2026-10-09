package ffdd.opsconsole.content.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Dedicated bounded executor; existing taskScheduler/Primary routing is unchanged. */
@Configuration
public class SupportLeaderboardSamplingSchedulerConfiguration {
    @Bean(name="supportLeaderboardTaskScheduler")
    ThreadPoolTaskScheduler supportLeaderboardTaskScheduler(){
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("support-leaderboard-");scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);return scheduler;
    }
}
