package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GeoEdgeHealthMonitorTest {
    private final GeoEdgeHealthMonitor monitor = new GeoEdgeHealthMonitor(Clock.systemUTC());

    @Test
    void sourceWithoutSamplesIsNotEligibleForSwitching() {
        GeoEdgeHealthMonitor.Snapshot snapshot = monitor.snapshot("cloudflare");

        assertThat(snapshot.status()).isEqualTo("awaiting_samples");
        assertThat(snapshot.healthy()).isFalse();
        assertThat(monitor.isSwitchReady("cloudflare")).isFalse();
    }

    @Test
    void actualSamplesDriveFailureRateAndP95() {
        for (int i = 0; i < 19; i++) {
            monitor.record("cloudflare", true, 3_000_000L);
        }
        monitor.record("cloudflare", true, 7_000_000L);

        GeoEdgeHealthMonitor.Snapshot snapshot = monitor.snapshot("cloudflare");

        assertThat(snapshot.status()).isEqualTo("healthy");
        assertThat(snapshot.sampleCount()).isEqualTo(20);
        assertThat(snapshot.failureRatePct()).isZero();
        assertThat(snapshot.p95Ms()).isEqualTo(3);
    }

    @Test
    void oneSuccessCannotOpenTheGlobalSwitchGate() {
        monitor.record("cloudflare", true, 1_000_000L);

        GeoEdgeHealthMonitor.Snapshot snapshot = monitor.snapshot("cloudflare");

        assertThat(snapshot.status()).isEqualTo("awaiting_samples");
        assertThat(snapshot.healthy()).isFalse();
        assertThat(monitor.isSwitchReady("cloudflare")).isFalse();
    }

    @Test
    void fivePercentFailureRateIsDegraded() {
        for (int i = 0; i < 19; i++) {
            monitor.record("nexion-gateway", true, 1_000_000L);
        }
        monitor.record("nexion-gateway", false, 1_000_000L);

        assertThat(monitor.snapshot("nexion-gateway").healthy()).isFalse();
        assertThat(monitor.snapshot("nexion-gateway").status()).isEqualTo("degraded");
    }

    @Test
    void readinessUsesOnlyTheRecentFiveMinuteWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-14T00:00:00Z"));
        GeoEdgeHealthMonitor timedMonitor = new GeoEdgeHealthMonitor(clock);
        for (int i = 0; i < 20; i++) {
            timedMonitor.record("cloudflare", true, 1_000_000L);
        }
        assertThat(timedMonitor.snapshot("cloudflare").healthy()).isTrue();

        clock.advance(Duration.ofMinutes(5).plusMillis(1));

        assertThat(timedMonitor.snapshot("cloudflare").status()).isEqualTo("stale");
        assertThat(timedMonitor.snapshot("cloudflare").healthy()).isFalse();
        assertThat(timedMonitor.snapshot("cloudflare").sampleCount()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
