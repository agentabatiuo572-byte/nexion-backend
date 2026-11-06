package ffdd.opsconsole.shared.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** Provides bounded, independent lanes for high-volume canonical compute facts. */
@Component
public class ComputeOutboxDispatchScheduler {
    public static final List<String> EVENT_TYPES = List.of("task.completed", "earnings.credited");
    private static final int LANES_PER_TYPE = 4;
    public static final int MAX_WORKERS = EVENT_TYPES.size() * LANES_PER_TYPE;

    private final TaskScheduler scheduler;
    private final EventOutboxDispatcher dispatcher;
    private final Environment environment;

    public ComputeOutboxDispatchScheduler(@Qualifier("computeOutboxTaskScheduler") TaskScheduler scheduler,
                                          EventOutboxDispatcher dispatcher, Environment environment) {
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        var delay = DurationStyle.detectAndParse(environment.getProperty(
                "nexion.developer.webhooks.canonical-dispatch-delay-ms", "1000"));
        var initialDelay = DurationStyle.detectAndParse(environment.getProperty(
                "nexion.developer.webhooks.canonical-dispatch-initial-delay-ms", "1000"));
        Instant startAt = scheduler.getClock().instant().plus(initialDelay);
        for (int lane = 0; lane < LANES_PER_TYPE; lane++) {
            int stripe = lane;
            for (String eventType : EVENT_TYPES) {
                scheduler.scheduleWithFixedDelay(
                        () -> dispatcher.dispatchComputeLane(eventType, LANES_PER_TYPE, stripe), startAt, delay);
            }
        }
    }
}
