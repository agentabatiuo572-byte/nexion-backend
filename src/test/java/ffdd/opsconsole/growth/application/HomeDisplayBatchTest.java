package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HomeDisplayBatchTest {
    private static final long START = 1_790_000_000_000L;

    @Test void extendsOnlyFutureSecondsAndPreservesTheSharedOverlap() {
        HomeDisplayBatch first = new HomeDisplayBatch(1, true, START, new long[]{28432}, new long[]{13053200})
                .extend(START, 1, 3, 110, 550, (min, max) -> min);
        HomeDisplayBatch next = first.extend(START + 60_000, 1, 3, 110, 550, (min, max) -> max - 1);
        for (int second = 60; second <= 120; second++) {
            assertThat(next.online()[second - 60]).isEqualTo(first.online()[second]);
            assertThat(next.paidCents()[second - 60]).isEqualTo(first.paidCents()[second]);
        }
        assertThat(next.online()[61] - next.online()[60]).isEqualTo(3);
        assertThat(next.paidCents()[61] - next.paidCents()[60]).isEqualTo(550);
    }

    @Test void stoppedStateNeverAccumulatesAndRestartStartsFromTheLastBalance() {
        HomeDisplayBatch paused = new HomeDisplayBatch(2, false, START, new long[]{28500}, new long[]{13060000});
        assertThat(paused.needsExtension(START + 900_000)).isFalse();
        assertThat(paused.index(START + 900_000)).isZero();
        HomeDisplayBatch resumed = new HomeDisplayBatch(3, true, START + 900_000, paused.online(), paused.paidCents())
                .extend(START + 900_000, 1, 3, 110, 550, (min, max) -> min);
        assertThat(resumed.online()[0]).isEqualTo(28500);
        assertThat(resumed.online()[1]).isEqualTo(28501);
    }

    @Test void longServerOutageRetainsDurableTotalsWithoutUnboundedCatchup() {
        HomeDisplayBatch first = new HomeDisplayBatch(1, true, START, new long[]{28432}, new long[]{13053200})
                .extend(START, 1, 3, 110, 550, (min, max) -> min);
        HomeDisplayBatch restarted = first.extend(START + 86_400_000, 1, 3, 110, 550, (min, max) -> min);
        assertThat(restarted.online()[0]).isEqualTo(first.online()[120]);
        assertThat(restarted.online()).hasSize(121);
        assertThat(restarted.startAt()).isEqualTo(START + 86_400_000);
    }

    @Test void upperBoundRemainsStorableAfterGrowthAndPause() {
        HomeDisplayBatch capped = new HomeDisplayBatch(1, true, START,
                new long[]{8_999_999_999_999L}, new long[]{899_999_999_999_999L})
                .extend(START, 3, 3, 550, 550, (min, max) -> min);
        assertThat(capped.online()[120]).isEqualTo(9_000_000_000_000L);
        assertThat(capped.paidCents()[120]).isEqualTo(900_000_000_000_000L);
    }
}
