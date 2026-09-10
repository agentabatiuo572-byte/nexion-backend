package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class TaskAssignmentLeaseExpirySchedulerSpringConstructionTest {
    @Test
    void springBindsTheExplicitExpiryBatchPropertyIntoTheLombokConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("expiry-test", Map.of("nexion.tasks.assignment-expiry-batch-size", "7")));
            context.registerBean(AppTaskAssignmentMapper.class, () -> mock(AppTaskAssignmentMapper.class));
            context.registerBean(AppTaskAssignmentService.class, () -> mock(AppTaskAssignmentService.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(TaskAssignmentLeaseExpiryScheduler.class);
            context.refresh();

            assertThat(context.getBean(TaskAssignmentLeaseExpiryScheduler.class)).isNotNull();
        }
    }
}
