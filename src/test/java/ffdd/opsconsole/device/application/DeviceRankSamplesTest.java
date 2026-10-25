package ffdd.opsconsole.device.application;

import ffdd.opsconsole.growth.application.DeviceRankConfigService.Model;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest.Tier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeviceRankSamplesTest {
    static Model model(long seed) {
        return new Model(1, true, List.of(tier("entry", "35", "0", "20"), tier("high", "65", "20", "100")), seed, 1, "2026-10-25T00:00:00Z");
    }
    static Tier tier(String name, String pct, String min, String max) {
        return new Tier(name, new BigDecimal(pct), new BigDecimal(min), new BigDecimal(max));
    }

    @Test void binarySearchMatchesBruteForceAndTiesShareRank() {
        double[] values = {0, 3, 3, 9, 9};
        for (double tops : new double[]{-1, 0, 2, 3, 4, 9, 10}) {
            assertThat(DeviceRankSamples.rankSorted(values, tops)).isEqualTo((int) Arrays.stream(values).filter(n -> n > tops).count() + 1);
        }
        assertThat(DeviceRankSamples.rankSorted(values, 9)).isEqualTo(1);
        assertThat(DeviceRankSamples.rankSorted(values, 3)).isEqualTo(3);
    }

    @Test void resizingUsesStablePrefixAndRestartOrToggleDoesNotResample() {
        Model model = model(314159);
        DeviceRankSamples cache = new DeviceRankSamples();
        int baseline = cache.rank("PRODUCTION", model, 1000, 30);
        cache.rank("PRODUCTION", model, 10000, 30);
        assertThat(cache.rank("PRODUCTION", model, 1000, 30)).isEqualTo(baseline);
        assertThat(cache.rank("PRODUCTION", new Model(3, true, model.tiers(), model.seed(), 1, model.generatedAt()), 1000, 30)).isEqualTo(baseline);
        assertThat(new DeviceRankSamples().rank("PRODUCTION", model, 1000, 30)).isEqualTo(baseline);
        for (int n : new int[]{1, 10, 10000, 10, 1000, 12000}) {
            int brute = (int) IntStream.range(0, n - 1).mapToDouble(i -> DeviceRankSamples.sample(model, i)).filter(x -> x > 30).count() + 1;
            assertThat(cache.rank("PRODUCTION", model, n, 30)).isEqualTo(brute);
        }
    }

    @Test void weightedDrawsRemainWithinConfiguredRangesAndNoAccountDataIsNeeded() {
        Model model = new Model(1, true, List.of(tier("zero-share", "0", "999", "999"),
                tier("entry", "25", "5", "5"), tier("high", "75", "20", "20")), 17, 1, "2026-10-25T00:00:00Z");
        long entry = IntStream.range(0, 100000).mapToDouble(i -> DeviceRankSamples.sample(model, i)).peek(x -> assertThat(x).isIn(5d, 20d)).filter(x -> x == 5).count();
        assertThat(entry).isBetween(24000L, 26000L);
        assertThat(new DeviceRankSamples().rank("scope", model, 100000, 20)).isEqualTo(1);
        assertThatThrownBy(() -> new DeviceRankSamples().rank("scope", model, 10000001, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentQueriesReturnWholeModelResults() throws Exception {
        DeviceRankSamples cache = new DeviceRankSamples();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 32).mapToObj(i -> pool.submit(() -> cache.rank("scope", model(19), 100000, 30))).toList();
            int expected = new DeviceRankSamples().rank("scope", model(19), 100000, 30);
            for (var future : futures) assertThat(future.get()).isEqualTo(expected);
        } finally { pool.shutdownNow(); }
    }

    @Test void benchmarkHundredThousandSamplesAndTenThousandQueries() {
        Model model = model(20261025);
        DeviceRankSamples cache = new DeviceRankSamples();
        long start = System.nanoTime();
        int first = cache.rank("scope", model, 100000, 30);
        long built = System.nanoTime();
        int checksum = 0;
        for (int i = 0; i < 10000; i++) checksum += cache.rank("scope", model, 100000, i % 100);
        long queried = System.nanoTime();
        assertThat(first).isBetween(1, 100000);
        assertThat(checksum).isPositive();
        System.out.printf("DEVICE_RANK_BENCH population=100000 samples=99999 build_ms=%.3f queries=10000 query_total_ms=%.3f query_avg_us=%.3f retained_array_bytes=%d%n",
                (built-start)/1e6, (queried-built)/1e6, (queried-built)/1e7, 99999L * 16);
    }
}
