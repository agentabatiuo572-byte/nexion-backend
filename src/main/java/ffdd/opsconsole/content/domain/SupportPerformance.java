package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.config.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** B80 performance definition and its independent month directory. No customer or event row is stored here. */
public final class SupportPerformance {
    public static final String DEFINITION_VERSION = "NET_INFLOW_V1";
    public static final String CURRENCY = "USDT";
    /** First instant of the new performance history, inclusive. */
    public static final Instant HISTORY_START = Instant.parse("2026-11-20T14:57:06.829277Z");

    public enum Coverage { COMPLETE, PARTIAL, UNKNOWN, FAILED }

    public enum Reason {
        GENERATION_UPGRADING, SOURCE_INCOMPLETE, SOURCE_READ_FAILED, HISTORY_INCOMPLETE,
        ATTRIBUTION_UNKNOWN, PAYMENT_TIME_UNVERIFIED, CHANNEL_UNVERIFIED,
        EXTERNAL_REFUND_UNVERIFIED, WITHDRAWAL_SETTLEMENT_UNVERIFIED
    }

    public record Month(String month, Instant from, Instant to, String businessZone,
                        Coverage coverage, List<Reason> reasons) {
        public Month {
            YearMonth parsed;
            try { parsed = YearMonth.parse(month); }
            catch (DateTimeParseException | NullPointerException failure) { throw invalid(); }
            if (!parsed.toString().equals(month) || from == null || to == null || !from.isBefore(to)
                    || businessZone == null || coverage == null || reasons == null) throw invalid();
            try { ZoneId.of(businessZone); } catch (RuntimeException failure) { throw invalid(); }
            reasons = List.copyOf(reasons);
            if (coverage == Coverage.COMPLETE && !reasons.isEmpty()) throw invalid();
        }
    }

    /**
     * One immutable publication. {@code sourceVersion} is shared by all months in this
     * generation; {@code asOf} is the shared cutoff, never a per-month clock.
     */
    public record Snapshot(String definitionVersion, String sourceVersion, Instant historyStart,
                           Instant asOf, List<Month> months) {
        public Snapshot {
            if (!DEFINITION_VERSION.equals(definitionVersion) || sourceVersion == null
                    || sourceVersion.isBlank() || historyStart == null || !HISTORY_START.equals(historyStart)
                    || asOf == null || asOf.isBefore(HISTORY_START) || months == null) throw invalid();
            months = List.copyOf(months);
            if (!months.isEmpty()) {
                Month first = months.get(0), last = months.get(months.size() - 1);
                if (!first.from().equals(HISTORY_START) || !last.to().equals(asOf)) throw invalid();
                YearMonth expected = BusinessCalendar.legacy().month(HISTORY_START);
                if (!YearMonth.parse(first.month()).equals(expected)) throw invalid();
                for (int index = 0; index < months.size(); index++) {
                    Month month = months.get(index);
                    YearMonth actual = YearMonth.parse(month.month());
                    if (!actual.equals(expected) || !month.from().isBefore(month.to())) throw invalid();
                    ZoneId zone = ZoneId.of(month.businessZone());
                    Instant expectedFrom = index == 0 ? HISTORY_START
                            : actual.atDay(1).atStartOfDay(zone).toInstant();
                    if (!month.from().equals(expectedFrom)) throw invalid();
                    if (index > 0 && !month.from().equals(months.get(index - 1).to())) throw invalid();
                    if (index < months.size() - 1) {
                        Month next = months.get(index + 1);
                        Instant expectedTo = actual.plusMonths(1).atDay(1)
                                .atStartOfDay(ZoneId.of(next.businessZone())).toInstant();
                        if (!month.to().equals(expectedTo)) throw invalid();
                    } else {
                        if (!month.to().equals(asOf)) throw invalid();
                        Instant nextMonthStart = BusinessCalendar.legacy()
                                .startOf(actual.plusMonths(1).atDay(1));
                        if (last.to().isAfter(nextMonthStart)) throw invalid();
                    }
                    expected = actual.plusMonths(1);
                }
            }
        }
    }

    /** Build the explicit directory from the shared analytics cutoff; no absence is converted to a zero. */
    public static Snapshot fromAnalytics(Instant asOf, String sourceVersion) {
        if (asOf == null || asOf.isBefore(HISTORY_START)) throw invalid();
        BusinessCalendar.Track calendar = BusinessCalendar.legacy();
        YearMonth startMonth = calendar.month(HISTORY_START);
        YearMonth endMonth = calendar.month(asOf);
        List<Month> months = new ArrayList<>();
        for (YearMonth month = startMonth; !month.isAfter(endMonth); month = month.plusMonths(1)) {
            Instant from = month.equals(startMonth) ? HISTORY_START : calendar.startOf(month.atDay(1));
            Instant fullTo = calendar.startOf(month.plusMonths(1).atDay(1));
            Instant to = month.equals(endMonth) ? asOf : fullTo;
            if (!from.isBefore(to)) continue;
            String zone = calendar.zoneAt(from).getId();
            months.add(new Month(month.toString(), from, to, zone, Coverage.UNKNOWN,
                    List.of(Reason.GENERATION_UPGRADING)));
        }
        return new Snapshot(DEFINITION_VERSION, sourceVersion, HISTORY_START, asOf, months);
    }

    /** Stable source identity for one analytics cutoff and subject set; it is not an HTTP version. */
    public static String sourceVersion(Instant asOf, Collection<Long> subjects) {
        if (asOf == null || subjects == null) throw invalid();
        StringBuilder material = new StringBuilder("analytics-v1|").append(asOf.getEpochSecond())
                .append(':').append(asOf.getNano()).append('|');
        subjects.stream().filter(Objects::nonNull).sorted().forEach(id -> material.append(id).append(','));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SUPPORT_PERFORMANCE_SNAPSHOT_INVALID");
    }
}
