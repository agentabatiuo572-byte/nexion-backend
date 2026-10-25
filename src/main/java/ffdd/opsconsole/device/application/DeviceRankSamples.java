package ffdd.opsconsole.device.application;

import ffdd.opsconsole.growth.application.DeviceRankConfigService.Model;
import ffdd.opsconsole.growth.dto.DeviceRankConfigRequest.Tier;
import java.util.Arrays;

/** Algorithm 1: immutable, indexed SplitMix64 draws. Keep this algorithm stable for durable seed replay. */
final class DeviceRankSamples {
    static final int MAX_POPULATION = 10_000_000;
    private Model model;
    private String scope;
    private double[] samples = new double[0];
    private double[] sorted = new double[0];

    // ponytail: one bounded model per process; shard by scope only if concurrent sandbox load warrants it.
    synchronized int rank(String nextScope, Model next, int population, double tops) {
        if (population < 1 || population > MAX_POPULATION || !Double.isFinite(tops)) throw new IllegalArgumentException();
        if (model == null || !nextScope.equals(scope) || model.seed() != next.seed() || !model.tiers().equals(next.tiers())) {
            model = next;
            scope = nextScope;
            samples = new double[0];
            sorted = new double[0];
        }
        int count = population - 1;
        if (count > samples.length) {
            int existing = samples.length;
            samples = Arrays.copyOf(samples, count);
            for (int i = existing; i < count; i++) samples[i] = sample(next, i);
        }
        if (count < sorted.length) {
            sorted = Arrays.copyOf(samples, count);
            Arrays.sort(sorted);
        } else if (count > sorted.length) {
            double[] added = Arrays.copyOfRange(samples, sorted.length, count);
            Arrays.sort(added);
            double[] merged = new double[count];
            int a = 0, b = 0, out = 0;
            while (a < sorted.length && b < added.length) merged[out++] = sorted[a] <= added[b] ? sorted[a++] : added[b++];
            while (a < sorted.length) merged[out++] = sorted[a++];
            while (b < added.length) merged[out++] = added[b++];
            sorted = merged;
        }
        return rankSorted(sorted, tops);
    }

    static int rankSorted(double[] values, double tops) {
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (values[mid] <= tops) lo = mid + 1;
            else hi = mid;
        }
        return values.length - lo + 1;
    }

    static double sample(Model model, int index) {
        double choice = uniform(model.seed() + 0x9e3779b97f4a7c15L * (2L * index + 1)) * 10000;
        double position = uniform(model.seed() + 0x9e3779b97f4a7c15L * (2L * index + 2));
        double cumulative = 0;
        for (Tier tier : model.tiers()) {
            cumulative += tier.sharePct().movePointRight(2).intValueExact();
            if (choice < cumulative) {
                double min = tier.minTops().doubleValue(), max = tier.maxTops().doubleValue();
                return Math.max(min, Math.min(max, Math.rint((min + (max - min) * position) * 1_000_000) / 1_000_000));
            }
        }
        throw new IllegalStateException("DEVICE_RANK_DISTRIBUTION_INVALID");
    }

    private static double uniform(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
