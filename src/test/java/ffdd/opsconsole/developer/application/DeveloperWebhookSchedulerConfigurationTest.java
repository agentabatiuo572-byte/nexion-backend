package ffdd.opsconsole.developer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class DeveloperWebhookSchedulerConfigurationTest {
    @Test
    void restoresBootStyleGlobalSchedulerAndKeepsDedicatedPoolSeparate() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.PropertiesPropertySource(
                "test", new java.util.Properties() {{ put("spring.task.scheduling.pool.size", "3"); }}));
        var configuration = new DeveloperWebhookSchedulerConfiguration(environment);
        ThreadPoolTaskScheduler global = configuration.taskScheduler();
        ThreadPoolTaskScheduler dedicated = configuration.developerWebhookTaskScheduler();

        assertThat(global.getPoolSize()).isEqualTo(3);
        assertThat(dedicated.getPoolSize()).isEqualTo(2);
        assertThat(global).isNotSameAs(dedicated);
        global.shutdown();
        dedicated.shutdown();
    }

    @Test
    void onlyWebhookSchedulesOptIntoDedicatedExecutor() throws Exception {
        Method worker = DeveloperWebhookDeliveryService.class.getMethod("scheduledDelivery");
        Method canonical = DeveloperWebhookCanonicalOutboxDispatchScheduler.class.getMethod("dispatchPending");
        Method broad = ffdd.opsconsole.shared.outbox.EventOutboxDispatchScheduler.class.getMethod("dispatchPending");

        assertThat(worker.getAnnotation(Scheduled.class).scheduler()).isEqualTo("developerWebhookTaskScheduler");
        assertThat(canonical.getAnnotation(Scheduled.class).scheduler()).isEqualTo("developerWebhookTaskScheduler");
        assertThat(broad.getAnnotation(Scheduled.class).scheduler()).isEmpty();
    }
}
