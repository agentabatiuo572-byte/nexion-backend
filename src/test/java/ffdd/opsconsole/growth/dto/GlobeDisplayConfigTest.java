package ffdd.opsconsole.growth.dto;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class GlobeDisplayConfigTest {
    @Test
    void validatesPrecisionAndExactCenterSet() {
        GlobeDisplayConfig b = GlobeDisplayConfig.defaults();
        assertThat(b.valid()).isTrue();
        assertThat(new GlobeDisplayConfig(1001, new BigDecimal("0.000001"), b.activeJobsMinPct(), b.activeJobsMaxPct(),
                b.throughputMin(), b.throughputMax(), b.latencyJitterMs(), b.centers()).valid()).isTrue();
        assertThat(new GlobeDisplayConfig(1001, new BigDecimal("0.0000001"), b.activeJobsMinPct(), b.activeJobsMaxPct(),
                b.throughputMin(), b.throughputMax(), b.latencyJitterMs(), b.centers()).valid()).isFalse();
        var duplicates = new ArrayList<>(b.centers());
        duplicates.set(0, duplicates.get(1));
        assertThat(new GlobeDisplayConfig(b.intervalMs(), b.nodeJitterPct(), b.activeJobsMinPct(), b.activeJobsMaxPct(),
                b.throughputMin(), b.throughputMax(), b.latencyJitterMs(), duplicates).valid()).isFalse();
        assertThat(new GlobeDisplayConfig(b.intervalMs(), b.nodeJitterPct(), new BigDecimal("15"), b.activeJobsMaxPct(),
                b.throughputMin(), b.throughputMax(), b.latencyJitterMs(), b.centers()).valid()).isFalse();
    }
}
