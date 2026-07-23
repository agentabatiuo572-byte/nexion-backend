package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

class NexMarketCurveSchedulerTest {
    @Test
    void unconfiguredScheduleDoesNotBreakApplicationStartup() {
        OpsNexMarketService marketService = mock(OpsNexMarketService.class);
        when(marketService.currentSchedule()).thenReturn(NexMarketSchedule.unconfigured());

        NexMarketCurveScheduler scheduler = new NexMarketCurveScheduler(marketService, null);

        assertThat(scheduler.nextExecution(new SimpleTriggerContext())).isNull();
    }

    @Test
    void configuredScheduleStillProducesNextExecution() {
        OpsNexMarketService marketService = mock(OpsNexMarketService.class);
        when(marketService.currentSchedule()).thenReturn(NexMarketSchedule.parse("cron:0 15 4 * * *|zone=UTC"));
        SimpleTriggerContext context = new SimpleTriggerContext(Clock.fixed(
                Instant.parse("2026-06-30T00:00:00Z"),
                ZoneId.of("UTC")));

        NexMarketCurveScheduler scheduler = new NexMarketCurveScheduler(marketService, null);

        assertThat(scheduler.nextExecution(context)).isEqualTo(Instant.parse("2026-06-30T04:15:00Z"));
    }
}
