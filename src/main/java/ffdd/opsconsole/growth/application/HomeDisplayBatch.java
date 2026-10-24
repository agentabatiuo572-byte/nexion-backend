package ffdd.opsconsole.growth.application;

import java.util.Arrays;
import java.util.function.LongBinaryOperator;

/** A shared random plan. Index zero is the persisted balance at startAt. */
public record HomeDisplayBatch(long sequence, boolean enabled, long startAt, long[] online, long[] paidCents) {
    public static final int WINDOW_SECONDS = 120;

    public int index(long now) {
        return (int) Math.min(online.length - 1, Math.max(0L, (now - startAt) / 1000));
    }

    public boolean needsExtension(long now) {
        return enabled && now >= startAt + (online.length - 61L) * 1000;
    }

    public HomeDisplayBatch extend(long now, long onlineMin, long onlineMax, long paidMin, long paidMax,
                                  LongBinaryOperator random) {
        int consumed = index(now);
        long nextStart = startAt + consumed * 1000L;
        // A server outage does not invent missed activity. Resume at the last durable balance.
        if (now >= startAt + online.length * 1000L) nextStart = now;
        long[] nextOnline = Arrays.copyOfRange(online, consumed, online.length);
        long[] nextPaid = Arrays.copyOfRange(paidCents, consumed, paidCents.length);
        int kept = nextOnline.length;
        nextOnline = Arrays.copyOf(nextOnline, WINDOW_SECONDS + 1);
        nextPaid = Arrays.copyOf(nextPaid, WINDOW_SECONDS + 1);
        for (int i = kept; i <= WINDOW_SECONDS; i++) {
            nextOnline[i] = Math.min(9_000_000_000_000L, Math.addExact(nextOnline[i - 1], random.applyAsLong(onlineMin, onlineMax + 1)));
            nextPaid[i] = Math.min(900_000_000_000_000L, Math.addExact(nextPaid[i - 1], random.applyAsLong(paidMin, paidMax + 1)));
        }
        return new HomeDisplayBatch(sequence + 1, enabled, nextStart, nextOnline, nextPaid);
    }
}
