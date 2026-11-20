package ffdd.opsconsole.growth.application;

import java.util.Arrays;
import java.util.function.LongBinaryOperator;

/** A shared random plan. Index zero is the persisted balance at startAt. */
public record HomeDisplayBatch(long sequence, boolean enabled, long startAt, long[] online, long[] paidCents,
                               Long[] activeJobs) {
    public static final int WINDOW_SECONDS = 120;
    static final long MAX_ACTIVE_JOBS = 100_000_000L;

    public HomeDisplayBatch(long sequence, boolean enabled, long startAt, long[] online, long[] paidCents) {
        this(sequence, enabled, startAt, online, paidCents, new Long[online.length]);
    }

    public int index(long now) {
        return (int) Math.min(online.length - 1, Math.max(0L, (now - startAt) / 1000));
    }

    public boolean needsExtension(long now) {
        return enabled && now >= startAt + (online.length - 61L) * 1000;
    }

    /** Existing batches already published their online plan. Start jobs at the current sample. */
    HomeDisplayBatch withActiveJobs(Long base, long now) {
        Long[] jobs = new Long[online.length];
        if (base != null) {
            int current = index(now);
            Arrays.fill(jobs, 0, current + 1, base);
            for (int i = current + 1; i < jobs.length; i++) {
                jobs[i] = Math.min(MAX_ACTIVE_JOBS, jobs[i - 1] + online[i] - online[i - 1]);
            }
        }
        return new HomeDisplayBatch(sequence + 1, enabled, startAt, online, paidCents, jobs);
    }

    public HomeDisplayBatch extend(long now, long onlineMin, long onlineMax, long paidMin, long paidMax,
                                  LongBinaryOperator random) {
        int consumed = index(now);
        long nextStart = startAt + consumed * 1000L;
        // A server outage does not invent missed activity. Resume at the last durable balance.
        if (now >= startAt + online.length * 1000L) nextStart = now;
        long[] nextOnline = Arrays.copyOfRange(online, consumed, online.length);
        long[] nextPaid = Arrays.copyOfRange(paidCents, consumed, paidCents.length);
        Long[] nextJobs = Arrays.copyOfRange(activeJobs, consumed, activeJobs.length);
        int kept = nextOnline.length;
        nextOnline = Arrays.copyOf(nextOnline, WINDOW_SECONDS + 1);
        nextPaid = Arrays.copyOf(nextPaid, WINDOW_SECONDS + 1);
        nextJobs = Arrays.copyOf(nextJobs, WINDOW_SECONDS + 1);
        for (int i = kept; i <= WINDOW_SECONDS; i++) {
            long onlineStep = random.applyAsLong(onlineMin, onlineMax + 1);
            nextOnline[i] = Math.min(9_000_000_000_000L, Math.addExact(nextOnline[i - 1], onlineStep));
            nextPaid[i] = Math.min(900_000_000_000_000L, Math.addExact(nextPaid[i - 1], random.applyAsLong(paidMin, paidMax + 1)));
            if (nextJobs[i - 1] != null) {
                nextJobs[i] = Math.min(MAX_ACTIVE_JOBS, nextJobs[i - 1] + onlineStep);
            }
        }
        return new HomeDisplayBatch(sequence + 1, enabled, nextStart, nextOnline, nextPaid, nextJobs);
    }
}
