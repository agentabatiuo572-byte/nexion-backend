package ffdd.opsconsole.content.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Keeps stream polling on its own worker and stops it before the Redis connection factory. */
@Configuration(proxyBeanMethods = false)
class ConversationRedisSchedulerConfiguration {
    @Bean(name = "conversationRedisTaskScheduler")
    ThreadPoolTaskScheduler conversationRedisTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("conversation-redis-reader-");
        scheduler.setDaemon(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
