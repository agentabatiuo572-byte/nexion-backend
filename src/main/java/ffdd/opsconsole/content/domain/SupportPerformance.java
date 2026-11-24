package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.finance.facade.SupportDirectPurchaseFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** B80 performance definition, month directory, and observations within the sampled interval. */
public final class SupportPerformance {
    public static final String DEFINITION_VERSION = "NET_INFLOW_V1";
    public static final String CURRENCY = "USDT";
    public static final List<String> INFLOW_CHANNELS =
            List.of("CHAIN_TOPUP", "VN_BANK_TOPUP", "CHAIN_DIRECT_PURCHASE", "VN_BANK_DIRECT_PURCHASE");
    public static final List<String> WITHDRAWAL_CHANNELS = List.of("TRC20", "BEP20", "ERC20", "VN_BANK");
    /** First instant of the new performance history, inclusive. */
    public static final Instant HISTORY_START = Instant.parse("2026-11-20T14:57:06.829277Z");

    public enum Generation { UPGRADING, READY }
    public enum Coverage { COMPLETE, PARTIAL, UNKNOWN, FAILED }
    public enum AttributionState { ASSIGNED, UNASSIGNED, AGENT_WITHOUT_GROUP, UNKNOWN }

    public enum Reason {
        GENERATION_UPGRADING, SOURCE_INCOMPLETE, SOURCE_READ_FAILED, HISTORY_INCOMPLETE,
        ATTRIBUTION_UNKNOWN, PAYMENT_TIME_UNVERIFIED, CHANNEL_UNVERIFIED,
        EXTERNAL_REFUND_UNVERIFIED, WITHDRAWAL_SETTLEMENT_UNVERIFIED, WITHDRAWAL_TIME_UNVERIFIED,
        NOT_APPLICABLE
    }

    public record ObservedDeposit(String factId,long customerId,long ledgerId,SupportPaymentFacts.Source source,
            String sourceBusinessId,Instant creditedAt,BigDecimal creditedUsdt,
            SupportPaymentFacts.Status historicalEnvironmentStatus, BigDecimal receivedVnd) {
        public ObservedDeposit(String factId,long customerId,long ledgerId,SupportPaymentFacts.Source source,
                String sourceBusinessId,Instant creditedAt,BigDecimal creditedUsdt,
                SupportPaymentFacts.Status historicalEnvironmentStatus) {
            this(factId, customerId, ledgerId, source, sourceBusinessId, creditedAt, creditedUsdt, historicalEnvironmentStatus, null);
        }
    }

    public record EventAttribution(AttributionState state,Long agentId,Long groupId) { }

    public enum Kind { INFLOW, WITHDRAWAL, CORRECTION, WITHDRAWAL_REFERENCE, REFERENCE }

    public record BindingPeriod(Instant from, Instant to) { }

    public record Binding(long customerId, EventAttribution attribution, Instant from, Instant to) { }

    public record FirstPayment(long customerId, String factId, String orderNo, String kind, String channel,
                               String currency, BigDecimal amount, Instant at, EventAttribution attribution,
                               boolean confirmed) { }

    /** Internal money event. Customer and business identifiers are disclosed only by authorized projections. */
    public record Event(String id, long customerId, EventAttribution attribution, BindingPeriod bindingPeriod,
                        Kind kind, String channel, String network, boolean devicePurchase, String businessNo,
                        String originalBusinessNo, String originalEventId, Instant occurredAt, Instant effectiveAt, String sourceStatus,
                        BigDecimal amountUsdt, BigDecimal deltaUsdt, BigDecimal roundingUsdt, BigDecimal totalPaidUsdt,
                        BigDecimal feeUsdt, BigDecimal channelFeeUsdt, BigDecimal networkFeeUsdt,
                        BigDecimal receivedUsdt, BigDecimal vnd) {
        public String performanceStatus() {
            if (kind == Kind.REFERENCE) return "EXCLUDED";
            if (kind != Kind.WITHDRAWAL_REFERENCE) return "EFFECTIVE";
            return switch (sourceStatus) {
                case "REVIEW_REJECTED", "ADDRESS_INVALID", "TX_FAILED", "TX_ORPHANED", "REFUNDED" -> "EXCLUDED";
                default -> "PENDING_REVIEW";
            };
        }
    }

    /** Amounts established by the captured evidence. A missing channel has no verified subtotal. */
    public record MonthlySubtotal(String month, EventAttribution attribution,
                                  Map<String, BigDecimal> inflows, Map<String, BigDecimal> corrections,
                                  Map<String, WithdrawalSubtotal> withdrawals, BigDecimal performanceUsdt) { }

    public record WithdrawalSubtotal(BigDecimal walletDebitedUsdt, BigDecimal feeUsdt,
                                     BigDecimal receivedUsdt, BigDecimal receivedVnd) { }

    public record ChannelCoverage(Coverage status, List<Reason> reasons, BigDecimal observedUsdt,
                                  long observedEvents, String evidenceDigest) { }

    public record Month(String month, Instant from, Instant to, String businessZone,
                        Coverage coverage, List<Reason> reasons, Map<String, ChannelCoverage> channels,
                        boolean firstHistoryComplete) {
        public Month(String month, Instant from, Instant to, String businessZone, Coverage coverage, List<Reason> reasons) {
            this(month, from, to, businessZone, coverage, reasons, Map.of(), false);
        }

        public Month {
            YearMonth parsed;
            try { parsed = YearMonth.parse(month); }
            catch (DateTimeParseException | NullPointerException failure) { throw invalid(); }
            if (!parsed.toString().equals(month) || from == null || to == null || !from.isBefore(to)
                    || businessZone == null || coverage == null || reasons == null) throw invalid();
            if (!ZoneId.getAvailableZoneIds().contains(businessZone)) throw invalid();
            reasons = List.copyOf(reasons);
            channels = channels == null ? Map.of() : Map.copyOf(channels);
            if ((coverage == Coverage.COMPLETE) != reasons.isEmpty()) throw invalid();
        }

        public boolean contains(Instant at) {
            return at != null && !at.isBefore(from) && at.isBefore(to);
        }

        public Instant calendarEnd() {
            return BusinessCalendar.legacy().startOf(YearMonth.parse(month).plusMonths(1).atDay(1));
        }
    }

    /**
     * One immutable publication. {@code sourceVersion} is shared by all months in this
     * generation; {@code asOf} is the shared cutoff, never a per-month clock.
     */
    public record Snapshot(String definitionVersion, String sourceVersion, Instant historyStart,
                           Instant asOf, Generation generation, List<Month> months,
                           List<Event> events,
                           List<SupportDirectPurchaseFactsFacade.Issue> directPurchaseIssues,
                           List<MonthlySubtotal> monthlySubtotals, List<Binding> bindings,
                           List<FirstPayment> firstPayments) {
        public Snapshot(String definitionVersion, String sourceVersion, Instant historyStart, Instant asOf,
                        Generation generation, List<Month> months, List<Event> events,
                        List<SupportDirectPurchaseFactsFacade.Issue> directPurchaseIssues,
                        List<MonthlySubtotal> monthlySubtotals) {
            this(definitionVersion, sourceVersion, historyStart, asOf, generation, months, events,
                    directPurchaseIssues, monthlySubtotals, List.of(), List.of());
        }

        public Snapshot {
            if (!DEFINITION_VERSION.equals(definitionVersion) || sourceVersion == null
                    || sourceVersion.isBlank() || historyStart == null || !HISTORY_START.equals(historyStart)
                    || asOf == null || asOf.isBefore(HISTORY_START) || generation == null || months == null) throw invalid();
            months = List.copyOf(months);
            events = List.copyOf(events);
            directPurchaseIssues = directPurchaseIssues == null ? List.of() : List.copyOf(directPurchaseIssues);
            monthlySubtotals = monthlySubtotals == null ? List.of() : List.copyOf(monthlySubtotals);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            firstPayments = firstPayments == null ? List.of() : List.copyOf(firstPayments);
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
            var ids = new HashSet<String>();
            for (var event : events) {
                if (!contains(historyStart, asOf, event.effectiveAt()) || !ids.add(event.id())) throw invalid();
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
        return new Snapshot(DEFINITION_VERSION, sourceVersion, HISTORY_START, asOf, Generation.UPGRADING, months,
                List.of(), List.of(), List.of());
    }

    public static boolean contains(Instant from, Instant to, Instant at) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }

    public static String maskNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return null;
        return nickname.codePointCount(0, nickname.length()) < 2 ? "***"
                : nickname.substring(0, nickname.offsetByCodePoints(0, 1)) + "***";
    }

    /** Stable source identity for one analytics cutoff and subject set; it is not an HTTP version. */
    public static String sourceVersion(Instant asOf, Collection<Long> subjects) {
        if (asOf == null || subjects == null) throw invalid();
        StringBuilder material = new StringBuilder("analytics-v1|").append(asOf.getEpochSecond())
                .append(':').append(asOf.getNano()).append('|');
        subjects.stream().filter(Objects::nonNull).sorted().forEach(id -> material.append(id).append(','));
        return digest(material.toString());
    }

    public static String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SUPPORT_PERFORMANCE_SNAPSHOT_INVALID");
    }
}
