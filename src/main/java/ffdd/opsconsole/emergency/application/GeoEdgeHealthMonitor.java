package ffdd.opsconsole.emergency.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Runtime truth for edge country-resolution health; no samples means health is unknown, never healthy. */
@Component
@RequiredArgsConstructor
public class GeoEdgeHealthMonitor {
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Duration READINESS_WINDOW = Duration.ofMinutes(5);
    private static final int MIN_READINESS_SAMPLES = 20;
    private static final int MAX_SAMPLES_PER_SOURCE = 10_000;

    private final Map<String, Deque<Sample>> samples = new ConcurrentHashMap<>();
    private final Clock clock;

    public void record(String source, boolean resolved, long elapsedNanos) {
        if (!GeoEdgeSourceRegistry.supports(source)) {
            return;
        }
        Deque<Sample> queue = samples.computeIfAbsent(source, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            prune(queue);
            queue.addLast(new Sample(clock.instant(), resolved, Math.max(0L, elapsedNanos)));
            while (queue.size() > MAX_SAMPLES_PER_SOURCE) {
                queue.removeFirst();
            }
        }
    }

    public Snapshot snapshot(String source) {
        Deque<Sample> queue = samples.computeIfAbsent(source, ignored -> new ArrayDeque<>());
        List<Sample> current;
        synchronized (queue) {
            prune(queue);
            current = new ArrayList<>(queue);
        }
        Instant readinessCutoff = clock.instant().minus(READINESS_WINDOW);
        List<Sample> readinessSamples = current.stream()
                .filter(sample -> !sample.recordedAt().isBefore(readinessCutoff))
                .toList();
        long total = readinessSamples.size();
        long failures = readinessSamples.stream().filter(sample -> !sample.resolved()).count();
        List<Long> latencies = readinessSamples.stream()
                .filter(Sample::resolved)
                .map(sample -> sample.elapsedNanos() / 1_000_000L)
                .sorted(Comparator.naturalOrder())
                .toList();
        long p95Ms = latencies.isEmpty() ? 0L : latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1));
        double failureRatePct = total == 0 ? 0D : failures * 100D / total;
        Instant lastSuccessAt = current.stream()
                .filter(Sample::resolved)
                .map(Sample::recordedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        Instant lastSampleAt = current.stream()
                .map(Sample::recordedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        boolean fresh = lastSampleAt != null && !lastSampleAt.isBefore(readinessCutoff);
        boolean healthy = fresh && total >= MIN_READINESS_SAMPLES && lastSuccessAt != null && failureRatePct < 5D;
        String status = current.isEmpty()
                ? "awaiting_samples"
                : !fresh
                ? "stale"
                : total < MIN_READINESS_SAMPLES
                ? "awaiting_samples"
                : healthy ? "healthy" : "degraded";
        return new Snapshot(source, status, healthy, total, failures, failureRatePct, p95Ms, lastSuccessAt, lastSampleAt);
    }

    public boolean isSwitchReady(String source) {
        return snapshot(source).healthy();
    }

    private void prune(Deque<Sample> queue) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!queue.isEmpty() && queue.peekFirst().recordedAt().isBefore(cutoff)) {
            queue.removeFirst();
        }
    }

    private record Sample(Instant recordedAt, boolean resolved, long elapsedNanos) {
    }

    public record Snapshot(
            String source,
            String status,
            boolean healthy,
            long sampleCount,
            long failureCount,
            double failureRatePct,
            long p95Ms,
            Instant lastSuccessAt,
            Instant lastSampleAt) {
    }
}
