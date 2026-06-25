package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NexMarketScheduleTest {
    @Test
    void parsesDailyTimeWithExplicitZone() {
        NexMarketSchedule schedule = NexMarketSchedule.parse("每日 08:30 Asia/Shanghai 自动推进");

        assertThat(schedule.fallback()).isFalse();
        assertThat(schedule.cronExpression()).isEqualTo("0 30 8 * * *");
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(schedule.displayValue()).isEqualTo("每日 08:30 Asia/Shanghai 自动推进");
    }

    @Test
    void parsesPlainDailyTimeAsUtc() {
        NexMarketSchedule schedule = NexMarketSchedule.parse("02:05");

        assertThat(schedule.fallback()).isFalse();
        assertThat(schedule.cronExpression()).isEqualTo("0 5 2 * * *");
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("UTC"));
        assertThat(schedule.displayValue()).isEqualTo("每日 02:05 UTC 自动推进");
    }

    @Test
    void parsesCronExpressionWithZone() {
        NexMarketSchedule schedule = NexMarketSchedule.parse("cron:0 15 4 * * *|zone=Asia/Shanghai");

        assertThat(schedule.fallback()).isFalse();
        assertThat(schedule.cronExpression()).isEqualTo("0 15 4 * * *");
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(schedule.displayValue()).isEqualTo("cron:0 15 4 * * *|zone=Asia/Shanghai");
    }

    @Test
    void invalidScheduleFallsBackToUtcMidnight() {
        NexMarketSchedule schedule = NexMarketSchedule.parse("每天凌晨");

        assertThat(schedule.fallback()).isTrue();
        assertThat(schedule.cronExpression()).isEqualTo("0 0 0 * * *");
        assertThat(schedule.zoneId()).isEqualTo(ZoneId.of("UTC"));
        assertThat(schedule.displayValue()).isEqualTo("每日 00:00 UTC 自动推进");
    }
}
