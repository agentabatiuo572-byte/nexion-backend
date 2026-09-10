package ffdd.opsconsole.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.exception.BizException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HistorySnapshotIdTest {
    @Test
    void firstPageUsesTheAccountHighWaterMarkAndLaterPagesKeepItEvenWhenRowsArrive() {
        AtomicLong latest = new AtomicLong(9001L);

        long firstBoundary = HistorySnapshotId.resolve(null, latest::get);
        latest.set(9002L);

        assertThat(firstBoundary).isEqualTo(9001L);
        assertThat(HistorySnapshotId.resolve(Long.toString(firstBoundary), latest::get)).isEqualTo(9001L);
    }

    @Test
    void rejectsNonDecimalAndOverflowingSnapshotIds() {
        for (String invalid : new String[] { "", "01", "-1", "+1", "1.5", "abc", "9223372036854775808" }) {
            assertThatThrownBy(() -> HistorySnapshotId.resolve(invalid, () -> 1L))
                    .isInstanceOf(BizException.class)
                    .hasMessage("HISTORY_SNAPSHOT_INVALID");
        }
    }

    @Test
    void acceptsZeroAndTheLargestSignedBigintOnlyWhenAlreadyIssued() {
        assertThat(HistorySnapshotId.resolve("0", () -> 1L)).isZero();
        assertThat(HistorySnapshotId.resolve("9223372036854775807", () -> Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsFutureBoundariesIncludingForAnEmptyAccount() {
        for (String future : new String[]{"2", "9223372036854775807"}) {
            assertThatThrownBy(() -> HistorySnapshotId.resolve(future, () -> 1L))
                    .isInstanceOf(BizException.class).hasMessage("HISTORY_SNAPSHOT_INVALID");
        }
        assertThat(HistorySnapshotId.resolve("0", () -> 0L)).isZero();
        assertThatThrownBy(() -> HistorySnapshotId.resolve("1", () -> 0L))
                .isInstanceOf(BizException.class).hasMessage("HISTORY_SNAPSHOT_INVALID");
    }

    @Test
    void anIssuedBoundaryRemainsValidWhenItsRowIsSoftDeleted() {
        // The mapper must supply the retained issued maximum, even if the visible maximum is now 90.
        assertThat(HistorySnapshotId.resolve("100", () -> 100L)).isEqualTo(100L);
    }
}
