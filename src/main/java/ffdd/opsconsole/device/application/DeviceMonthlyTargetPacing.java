package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Device-month budget allocator. All money is frozen before a task runs; no month-end payment. */
public final class DeviceMonthlyTargetPacing {
    public static final String ALGORITHM = "DMT-V3-SHA256-1";
    public static final int SCALE = 24;
    public static final int CONTRIBUTION_SCALE = 12;
    public static final int D_CAP = 7200;
    public static final BigDecimal QUANTUM = new BigDecimal("0.000001");
    private static final BigDecimal LOW = new BigDecimal("0.7"), HIGH = new BigDecimal("1.3");
    private static final BigDecimal ONE = BigDecimal.ONE, ZERO = BigDecimal.ZERO;
    private DeviceMonthlyTargetPacing() { }

    public record Cycle(int index, LocalDateTime start, LocalDateTime end) { }
    public record State(BigDecimal p, BigDecimal lo, BigDecimal hi, BigDecimal allocated) {
        public static State empty() { return new State(ZERO, ZERO, ZERO, ZERO.setScale(6)); }
        public State add(Snapshot s) {
            return new State(p.add(s.p()), lo.add(s.lo()), hi.add(s.hi()), allocated.add(s.reward()));
        }
        public State cancel(Snapshot s) {
            return new State(p.subtract(s.p()), lo.subtract(s.lo()), hi.subtract(s.hi()), allocated.subtract(s.reward()));
        }
    }
    public record Schedule(Cycle cycle, BigDecimal u, List<BigDecimal> weights) {
        public Schedule { weights = List.copyOf(weights); }
        /** Integral in business seconds. LocalDateTime here is always Asia/Shanghai. */
        public BigDecimal weightedSeconds(LocalDateTime start, LocalDateTime end) {
            BigDecimal total = ZERO;
            while (start.isBefore(end)) {
                LocalDateTime stop;
                BigDecimal weight;
                if (start.isBefore(cycle.start())) {
                    stop = cycle.start().isBefore(end) ? cycle.start() : end; weight = ONE;
                } else if (!start.isBefore(cycle.end())) {
                    stop = end; weight = ONE;
                } else {
                    stop = start.toLocalDate().plusDays(1).atStartOfDay();
                    if (stop.isAfter(cycle.end())) stop = cycle.end();
                    if (stop.isAfter(end)) stop = end;
                    int day = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(cycle.start().toLocalDate(), start.toLocalDate()));
                    weight = weights.get(day);
                }
                total = total.add(seconds(start, stop).multiply(weight)); start = stop;
            }
            return total;
        }
    }
    /** Frozen V3 evidence: inputs of the controller and clamp, as well as its six-place result. */
    public record Snapshot(String algorithm,
            @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss.SSSSSS") LocalDateTime cycleStart,
            @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss.SSSSSS") LocalDateTime cycleEnd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal beforeP,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal beforeLo,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal beforeHi,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal beforeA,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal lo,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal hi,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal poolRate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal correction,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal q,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal g,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal innerBand,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal weightedSeconds,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal baseReward, int durationSeconds,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal monthlyTargetUsdt,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal monthlyTargetBandPct,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal capacityPct,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal reward) { }

    /** Every endpoint is calculated from the original anchor, including Jan 31 and leap years. */
    public static Cycle cycleAt(LocalDateTime anchor, LocalDateTime at) {
        if (anchor == null || at.isBefore(anchor)) throw new IllegalArgumentException("MONTHLY_TARGET_ANCHOR_INVALID");
        int months = Math.toIntExact(java.time.temporal.ChronoUnit.MONTHS.between(anchor.toLocalDate().withDayOfMonth(1), at.toLocalDate().withDayOfMonth(1)));
        if (anchor.plusMonths(months).isAfter(at)) months--;
        return new Cycle(months, anchor.plusMonths(months), anchor.plusMonths(months + 1L));
    }

    public static Schedule schedule(long deviceId, Cycle cycle) {
        String identity = ALGORITHM + "|" + deviceId + "|" + cycle.start();
        BigDecimal u = BigDecimal.valueOf(uniform(identity + "|u") * 2 - 1);
        var spans = new ArrayList<BigDecimal>();
        var raw = new ArrayList<Double>();
        for (LocalDateTime t = cycle.start(); t.isBefore(cycle.end());) {
            LocalDateTime next = t.toLocalDate().plusDays(1).atStartOfDay();
            if (next.isAfter(cycle.end())) next = cycle.end();
            spans.add(seconds(t, next)); raw.add(0.7 + 0.6 * uniform(identity + "|day|" + t.toLocalDate())); t = next;
        }
        double left = -0.6, right = 0.6, duration = seconds(cycle.start(), cycle.end()).doubleValue();
        for (int n = 0; n < 64; n++) {
            double shift = (left + right) / 2, sum = 0;
            for (int d = 0; d < raw.size(); d++) sum += spans.get(d).doubleValue() * Math.max(.7, Math.min(1.3, raw.get(d) + shift));
            if (sum < duration) left = shift; else right = shift;
        }
        var weights = new ArrayList<BigDecimal>(); BigDecimal sum = ZERO;
        for (int d = 0; d < raw.size(); d++) {
            BigDecimal w = BigDecimal.valueOf(Math.max(.7, Math.min(1.3, raw.get(d) + (left + right) / 2))).setScale(SCALE, RoundingMode.HALF_UP);
            weights.add(w); sum = sum.add(w.multiply(spans.get(d)));
        }
        BigDecimal residual = seconds(cycle.start(), cycle.end()).subtract(sum);
        for (int d = 0; d < weights.size() && residual.signum() != 0; d++) {
            BigDecimal old = weights.get(d), w = old.add(divide(residual, spans.get(d))).max(LOW).min(HIGH);
            weights.set(d, w); residual = residual.subtract(w.subtract(old).multiply(spans.get(d)));
        }
        return new Schedule(cycle, u, weights);
    }

    /** Unbiased concrete-type selection, stable across a transaction rollback and catch-up batches. */
    public static int select(long deviceId, long generation, LocalDateTime logicalStart, int size) {
        long limit = (1L << 32) - (1L << 32) % size;
        for (int nonce = 0; ; nonce++) {
            byte[] h = hash("SIM-TYPE-SHA256-1|" + deviceId + "|" + generation + "|" + logicalStart + "|" + nonce);
            long n = ((h[0] & 255L) << 24) | ((h[1] & 255L) << 16) | ((h[2] & 255L) << 8) | (h[3] & 255L);
            if (n < limit) return (int)(n % size);
        }
    }

    public static boolean eligible(String asset,BigDecimal baseReward,int durationSeconds) {
        return "USDT".equals(asset) && baseReward.signum()>0 && durationSeconds>0 && durationSeconds<=D_CAP;
    }

    public static Snapshot quote(Schedule schedule, State before, LocalDateTime start, LocalDateTime due,
            BigDecimal target, BigDecimal bandPct, BigDecimal capacityPct, BigDecimal baseReward,
            int durationSeconds, BigDecimal poolRate) {
        if (durationSeconds <= 0 || durationSeconds > D_CAP || baseReward.signum() <= 0 || poolRate.signum() <= 0)
            throw new IllegalArgumentException("MONTHLY_TARGET_POOL_INELIGIBLE");
        BigDecimal duration = seconds(schedule.cycle().start(), schedule.cycle().end());
        BigDecimal q = target.multiply(capacityPct.movePointLeft(2));
        if (q.compareTo(new BigDecimal("0.01")) < 0) q = ZERO;
        BigDecimal inner = bandPct.movePointLeft(2);
        if (q.signum() > 0) inner = inner.subtract(divide(BigDecimal.valueOf(3L * D_CAP), duration)).subtract(divide(QUANTUM, q));
        BigDecimal g = ONE.add(inner.multiply(schedule.u())).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal weighted = schedule.weightedSeconds(start, due);
        BigDecimal common = divide(q.multiply(weighted), duration);
        BigDecimal p = common.multiply(g).setScale(CONTRIBUTION_SCALE, RoundingMode.HALF_UP);
        BigDecimal lo = common.multiply(ONE.subtract(inner)).setScale(CONTRIBUTION_SCALE, RoundingMode.HALF_UP);
        BigDecimal hi = common.multiply(ONE.add(inner)).setScale(CONTRIBUTION_SCALE, RoundingMode.HALF_UP);
        BigDecimal c = q.signum() == 0 ? ONE : ONE.add(divide(before.p().subtract(before.allocated()).multiply(BigDecimal.valueOf(30)), q.multiply(g).setScale(SCALE, RoundingMode.HALF_UP)))
                .max(new BigDecimal("0.5")).min(new BigDecimal("1.5"));
        BigDecimal nominal = divide(baseReward.multiply(p), BigDecimal.valueOf(durationSeconds).multiply(poolRate));
        BigDecimal reward = q.signum()==0 ? ZERO.setScale(6) : clamp(nominal.multiply(c), before.lo().add(lo).subtract(before.allocated()), before.hi().add(hi).subtract(before.allocated()));
        return new Snapshot(ALGORITHM, schedule.cycle().start(), schedule.cycle().end(), before.p(), before.lo(), before.hi(), before.allocated(),
                p, lo, hi, poolRate, c, q, g, inner, weighted, baseReward, durationSeconds, target, bandPct, capacityPct, reward);
    }
    /** Shared financial verification of step 4, using only immutable task inputs. */
    public static BigDecimal recompute(Snapshot s) {
        if (!ALGORITHM.equals(s.algorithm())) throw new IllegalArgumentException("MONTHLY_TARGET_ALGORITHM_INVALID");
        BigDecimal c = s.q().signum() == 0 ? ONE : ONE.add(divide(s.beforeP().subtract(s.beforeA()).multiply(BigDecimal.valueOf(30)), s.q().multiply(s.g()).setScale(SCALE, RoundingMode.HALF_UP)))
                .max(new BigDecimal("0.5")).min(new BigDecimal("1.5"));
        if (c.compareTo(s.correction()) != 0) throw new IllegalArgumentException("MONTHLY_TARGET_CORRECTION_INVALID");
        if(s.q().signum()==0)return ZERO.setScale(6);
        BigDecimal nominal = divide(s.baseReward().multiply(s.p()), BigDecimal.valueOf(s.durationSeconds()).multiply(s.poolRate()));
        return clamp(nominal.multiply(c), s.beforeLo().add(s.lo()).subtract(s.beforeA()), s.beforeHi().add(s.hi()).subtract(s.beforeA()));
    }
    /** Compact V3 inputs. Contributions are rounded before allocation; reward lives only on the task. */
    public record Evidence(BigDecimal p, BigDecimal lo, BigDecimal hi, BigDecimal deltaP,
            BigDecimal deltaLo, BigDecimal deltaHi, BigDecimal poolRate, BigDecimal center, int cycleSeconds) { }
    public static String compact(Snapshot s) {
        var values = List.of(s.p(), s.lo(), s.hi(), s.beforeP().subtract(s.beforeA()),
                s.beforeLo().subtract(s.beforeA()), s.beforeHi().subtract(s.beforeA()),
                s.poolRate(), s.q().multiply(s.g()).setScale(SCALE, RoundingMode.HALF_UP));
        var out = new StringBuilder("1");
        for (int i = 0; i < values.size(); i++)
            out.append('|').append(values.get(i).setScale(i < 6 ? CONTRIBUTION_SCALE : SCALE,
                    RoundingMode.UNNECESSARY).unscaledValue().toString(36));
        return out.append('|').append(Long.toString(Duration.between(s.cycleStart(),s.cycleEnd()).getSeconds(),36)).toString();
    }
    /** JSON string payload revision 1; base36 signed fixed-point integers avoid JSON DOUBLE loss. */
    public static Evidence evidence(String value) {
        var parts = value.split("\\|", -1);
        if (parts.length != 10 || !"1".equals(parts[0])) throw new IllegalArgumentException("MONTHLY_TARGET_SNAPSHOT_INVALID");
        var numbers = new BigDecimal[8];
        for (int i = 0; i < numbers.length; i++)
            numbers[i] = new BigDecimal(new java.math.BigInteger(parts[i + 1], 36), i < 6 ? CONTRIBUTION_SCALE : SCALE);
        return new Evidence(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5], numbers[6], numbers[7], Integer.parseInt(parts[9],36));
    }
    public static BigDecimal recompute(Evidence s, BigDecimal baseReward, int durationSeconds) {
        if (s.center().signum() == 0) return ZERO.setScale(6);
        var correction = ONE.add(divide(s.deltaP().multiply(BigDecimal.valueOf(30)), s.center()))
                .max(new BigDecimal("0.5")).min(new BigDecimal("1.5"));
        var nominal = divide(baseReward.multiply(s.p()), BigDecimal.valueOf(durationSeconds).multiply(s.poolRate()));
        return clamp(nominal.multiply(correction), s.deltaLo().add(s.lo()), s.deltaHi().add(s.hi()));
    }
    private static BigDecimal clamp(BigDecimal nominal, BigDecimal low, BigDecimal high) {
        BigDecimal ceil = low.max(ZERO).setScale(6, RoundingMode.CEILING), floor = high.setScale(6, RoundingMode.FLOOR).max(ZERO);
        if (ceil.compareTo(floor) > 0) return floor;
        return nominal.setScale(6, RoundingMode.HALF_UP).max(ceil).min(floor);
    }
    public static BigDecimal divide(BigDecimal a, BigDecimal b) { return a.divide(b, SCALE, RoundingMode.HALF_UP); }
    private static BigDecimal seconds(LocalDateTime a, LocalDateTime b) { return BigDecimal.valueOf(Duration.between(a, b).toNanos(), 9); }
    private static double uniform(String identity) {
        byte[] h = hash(identity); long bits = 0;
        for (int i = 0; i < 7; i++) bits = (bits << 8) | (h[i] & 255L);
        return (bits >>> 3) * 0x1.0p-53;
    }
    private static byte[] hash(String identity) {
        try { return MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
