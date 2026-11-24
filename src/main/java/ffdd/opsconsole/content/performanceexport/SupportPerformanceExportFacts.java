package ffdd.opsconsole.content.performanceexport;

import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.application.SupportPerformanceAggregation;
import ffdd.opsconsole.content.application.SupportPerformanceCache;
import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.Event;
import ffdd.opsconsole.content.domain.SupportPerformance.EventAttribution;
import ffdd.opsconsole.content.domain.SupportPerformance.Kind;
import ffdd.opsconsole.content.domain.SupportPerformance.MonthlySubtotal;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.RootDisplayRow;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.platform.application.AdminObjectDisplayNameService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Projects the frozen B80 observations into each authorized export. */
@Component
class SupportPerformanceExportFacts implements PerformanceExportFactSource {
    private final SupportPerformanceCache cache;
    private final SupportOwnershipService ownership;
    private final SupportLeaderboardMapper leaderboard;
    private final AdminObjectDisplayNameService names;
    private final SupportAnalyticsMapper analytics;

    SupportPerformanceExportFacts(SupportPerformanceCache cache, SupportOwnershipService ownership,
            SupportLeaderboardMapper leaderboard, AdminObjectDisplayNameService names, SupportAnalyticsMapper analytics) {
        this.cache = cache;
        this.ownership = ownership;
        this.leaderboard = leaderboard;
        this.names = names;
        this.analytics = analytics;
    }

    @Override
    public List<MonthOption> months() {
        return published().map(snapshot -> snapshot.months().stream().map(month -> new MonthOption(month.month(),
                        month.coverage() == SupportPerformance.Coverage.COMPLETE ? "READY" : month.coverage().name(),
                        month.reasons().stream().map(Enum::name).toList())).toList())
                .orElseGet(List::of);
    }

    @Override
    public Window window(String month, Scope scope) {
        return published().flatMap(snapshot -> snapshot.months().stream().filter(entry -> entry.month().equals(month)).findFirst()
                .map(m -> new Window(snapshot.definitionVersion(), snapshot.sourceVersion(), month,
                        "PERIOD_EVENT", m.from().toString(), m.to().toString(), snapshot.asOf().toString(), m.businessZone())))
                .orElse(null);
    }

    @Override
    public Table read(Type type, Window window, Scope scope) {
        var snapshot = published().filter(value -> value.sourceVersion().equals(window.sourceVersion())).orElse(null);
        Long actor = ownership.actorId();
        if (snapshot == null || actor == null) return null;
        var month = usableMonth(snapshot, window.month()).orElse(null);
        if (month == null) return null;
        var coverage = new Coverage(Status.valueOf(month.coverage().name()),
                month.reasons().stream().map(Enum::name).toList());
        if (type != Type.SUMMARY) return detail(type, window, scope, actor, snapshot, month, coverage);
        var subtotals = snapshot.monthlySubtotals().stream()
                .filter(row -> window.month().equals(row.month()) && visible(row.attribution(), scope, actor)).toList();
        if (subtotals.isEmpty()) return null;

        var agentNames = names.byIds(subtotals.stream().map(row -> row.attribution().agentId())
                .filter(Objects::nonNull).distinct().toList());
        var groupNames = groupNames();
        var rows = subtotals.stream().map(row -> values(row, window, agentNames, groupNames, coverage)).toList();
        return new Table(type, Columns.forType(type), rows, coverage,
                0, List.of());
    }

    private Optional<SupportPerformance.Snapshot> published() {
        return cache.current().filter(snapshot -> snapshot.generation() == SupportPerformance.Generation.READY);
    }

    @Override
    public String newRequestBlocker(Window window) {
        if (cache.refreshFailed()) return "EXPORT_REFRESH_FAILED";
        if (window == null) return null;
        var snapshot = published().filter(value -> value.sourceVersion().equals(window.sourceVersion())).orElse(null);
        if (snapshot == null) return "EXPORT_SOURCE_UNAVAILABLE";
        if (cache.stale(snapshot)) return "EXPORT_DATA_STALE";
        return usableMonth(snapshot, window.month()).isEmpty() ? "EXPORT_ROWS_UNVERIFIED" : null;
    }

    private static Optional<SupportPerformance.Month> usableMonth(SupportPerformance.Snapshot snapshot, String month) {
        return snapshot.months().stream().filter(m -> m.month().equals(month))
                .filter(m -> m.coverage() == SupportPerformance.Coverage.COMPLETE
                        || m.coverage() == SupportPerformance.Coverage.PARTIAL)
                .findFirst();
    }

    private static boolean visible(EventAttribution attribution, Scope scope, Long actor) {
        return switch (scope.kind()) {
            case "SELF" -> actor.equals(attribution.agentId());
            case "MANAGED_GROUPS" -> attribution.state() == SupportPerformance.AttributionState.ASSIGNED
                    && scope.groupIds().contains(String.valueOf(attribution.groupId()));
            case "ALL" -> true;
            default -> false;
        };
    }

    private Table detail(Type type, Window window, Scope scope, Long actor, SupportPerformance.Snapshot snapshot,
            SupportPerformance.Month month, Coverage coverage) {
        var portfolio = snapshot.events().stream().filter(event -> event.effectiveAt().isBefore(month.to())
                && visible(event.attribution(), scope, actor)).toList();
        var monthEvents = portfolio.stream().filter(event -> month.contains(event.effectiveAt())).toList();
        if (monthEvents.isEmpty() && coverage.status() == Status.PARTIAL) return null;
        var ids = monthEvents.stream().filter(event -> event.attribution().agentId() != null)
                .map(Event::customerId).distinct().toList();
        Map<Long, RootDisplayRow> allowed = new LinkedHashMap<>();
        for (var readScope : scope.currentCustomerScopes(actor)) {
            for (int offset = 0; offset < ids.size(); offset += 200) {
                analytics.rootDisplayRows(readScope, ids.subList(offset, Math.min(offset + 200, ids.size())))
                        .forEach(row -> allowed.put(row.customerId(), row));
            }
        }
        var disclosed = monthEvents.stream().filter(event -> event.attribution().agentId() != null
                && allowed.containsKey(event.customerId())).toList();
        var agentNames = names.byIds(disclosed.stream().map(event -> event.attribution().agentId()).distinct().toList());
        List<Map<String, String>> rows = new ArrayList<>();
        if (type == Type.CUSTOMER_DETAIL) {
            var cumulative = portfolio.stream().filter(event -> event.attribution().agentId() != null
                    && allowed.containsKey(event.customerId())).collect(Collectors.toMap(
                            CustomerAgent::of, Event::deltaUsdt, BigDecimal::add));
            disclosed.stream().collect(Collectors.groupingBy(CustomerAgent::of, LinkedHashMap::new, Collectors.toList()))
                    .forEach((key, events) -> rows.add(customer(window, month, coverage, events, allowed.get(key.customerId()),
                            agentNames.get(key.agentId()), cumulative.get(key))));
        } else {
            var groups = groupNames();
            disclosed.forEach(event -> rows.add(transaction(event, allowed.get(event.customerId()).customerNo(),
                    agentNames.get(event.attribution().agentId()), groups, coverage)));
        }
        var restricted = monthEvents.stream().filter(event -> event.attribution().agentId() == null
                || !allowed.containsKey(event.customerId())).toList();
        if (!restricted.isEmpty()) {
            // One anonymous subtotal; none of its source identifiers enter the frozen export.
            Map<String, String> summary = new LinkedHashMap<>();
            String amount = decimal(restricted.stream().map(Event::deltaUsdt).reduce(BigDecimal.ZERO, BigDecimal::add));
            if (type == Type.CUSTOMER_DETAIL) {
                addContext(summary, window, coverage);
                summary.put("month", month.month());
                summary.put("performanceAmount", amount);
                summary.put("missingSources", String.join(",", coverage.reasons()) + ",RESTRICTED_HISTORY");
            } else {
                summary.put("status", "RESTRICTED_HISTORY");
                summary.put("performanceDeltaUsdt", amount);
                summary.put("coverageReason", "RESTRICTED_HISTORY");
            }
            rows.add(summary);
        }
        return new Table(type, Columns.forType(type), List.copyOf(rows), coverage, restricted.size(),
                disclosed.stream().map(Event::customerId).distinct().toList());
    }

    private record CustomerAgent(long customerId, Long agentId) {
        static CustomerAgent of(Event event) { return new CustomerAgent(event.customerId(), event.attribution().agentId()); }
    }

    private Map<Long, String> groupNames() {
        return leaderboard.groups().stream().filter(group -> group.name() != null)
                .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name));
    }

    private static Map<String, String> customer(Window window, SupportPerformance.Month month, Coverage coverage,
            List<Event> events, RootDisplayRow display, String name, BigDecimal cumulative) {
        Map<String, String> row = new LinkedHashMap<>();
        var subtotal = SupportPerformanceAggregation.subtotal(month.month(), events.get(0).attribution(), events);
        row.put("customerNo", display.customerNo());
        row.put("maskedNickname", SupportPerformance.maskNickname(display.nickname()));
        row.put("eventAgent", name);
        var periods = new LinkedHashSet<String>();
        events.stream().map(Event::bindingPeriod).filter(Objects::nonNull)
                .sorted(Comparator.comparing(SupportPerformance.BindingPeriod::from)).forEach(period -> {
                    Instant start = period.from().isBefore(month.from()) ? month.from() : period.from();
                    Instant end = period.to() == null || period.to().isAfter(month.to()) ? month.to() : period.to();
                    if (start.isBefore(end)) periods.add(start + "/" + end);
                });
        if (!periods.isEmpty()) row.put("bindingPeriods", String.join("; ", periods));
        row.put("month", month.month());
        subtotal.inflows().forEach((channel, amount) -> row.put("inflowByChannel." + channel, decimal(amount)));
        row.put("correctionTotal", total(subtotal.corrections()));
        subtotal.withdrawals().forEach((channel, withdrawal) ->
                row.put("withdrawalByChannel." + channel + ".walletDebited", decimal(withdrawal.walletDebitedUsdt())));
        var devices = events.stream().filter(Event::devicePurchase).map(Event::amountUsdt).toList();
        if (!devices.isEmpty()) row.put("devicePurchases", decimal(devices.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        row.put("performanceAmount", decimal(subtotal.performanceUsdt()));
        row.put("customerAgentCumulativePerformance", decimal(cumulative));
        addContext(row, window, coverage);
        return row;
    }

    private static Map<String, String> transaction(Event event, String customerNo, String agentName,
            Map<Long, String> groups, Coverage coverage) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("effectiveAt", event.effectiveAt().toString());
        row.put("occurredAt", event.occurredAt().toString());
        row.put("customerNo", customerNo);
        row.put("eventAgent", agentName);
        if (event.attribution().groupId() != null) row.put("eventGroup", groups.get(event.attribution().groupId()));
        row.put("kind", event.kind().name());
        if (event.devicePurchase()) row.put("referenceCategories", "DEVICE_PURCHASE");
        row.put("channel", event.channel());
        row.put("network", event.network());
        row.put("businessNo", event.businessNo());
        row.put("businessAmount", decimal(event.vnd() == null ? event.amountUsdt() : event.vnd()));
        row.put("businessCurrency", event.vnd() == null ? SupportPerformance.CURRENCY : "VND");
        row.put("roundingAdjustmentUsdt", decimal(event.roundingUsdt()));
        row.put("totalPaidUsdt", decimal(event.totalPaidUsdt()));
        row.put("performanceDeltaUsdt", decimal(event.deltaUsdt()));
        if (event.kind() == Kind.WITHDRAWAL) row.put("walletDebitUsdt", decimal(event.amountUsdt()));
        row.put("withdrawalFeeUsdt", decimal(event.feeUsdt()));
        row.put("channelFeeUsdt", decimal(event.channelFeeUsdt()));
        row.put("networkFeeUsdt", decimal(event.networkFeeUsdt()));
        row.put("receivedUsdt", decimal(event.receivedUsdt()));
        row.put("vndAmount", decimal(event.vnd()));
        row.put("sourceStatus", event.sourceStatus());
        row.put("status", "EFFECTIVE");
        row.put("originalBusinessNo", event.originalBusinessNo());
        row.put("coverageReason", String.join(",", coverage.reasons()));
        return row;
    }

    private static Map<String, String> values(MonthlySubtotal subtotal, Window window,
            Map<Long, String> agentNames, Map<Long, String> groupNames, Coverage coverage) {
        Map<String, String> row = new LinkedHashMap<>();
        var attribution = subtotal.attribution();
        if (attribution.agentId() != null) {
            row.put("agent", agentNames.get(attribution.agentId()));
        } else {
            row.put("agent", attribution.state() == SupportPerformance.AttributionState.UNASSIGNED ? "未分配" : "归属未知");
        }
        if (attribution.groupId() != null) row.put("group", groupNames.get(attribution.groupId()));
        row.put("month", subtotal.month());
        row.put("performanceAmount", decimal(subtotal.performanceUsdt()));
        row.put("inflowTotal", total(subtotal.inflows()));
        row.put("correctionTotal", total(subtotal.corrections()));
        subtotal.inflows().forEach((channel, amount) -> row.put("inflowByChannel." + channel, decimal(amount)));
        subtotal.corrections().forEach((channel, amount) -> row.put("correctionByChannel." + channel, decimal(amount)));
        if (!subtotal.withdrawals().isEmpty()) {
            var debit = subtotal.withdrawals().values().stream().map(SupportPerformance.WithdrawalSubtotal::walletDebitedUsdt)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            row.put("withdrawalTotal", decimal(debit));
            subtotal.withdrawals().forEach((channel, amount) -> {
                String prefix = "withdrawalByChannel." + channel + ".";
                row.put(prefix + "walletDebited", decimal(amount.walletDebitedUsdt()));
                row.put(prefix + "fee", decimal(amount.feeUsdt()));
                row.put(prefix + "netReceived", decimal(amount.receivedUsdt()));
                if ("VN_BANK".equals(channel)) row.put("vndReference.bankPayoutReceived", decimal(amount.receivedVnd()));
            });
        }
        addContext(row, window, coverage);
        return row;
    }

    private static void addContext(Map<String, String> row, Window window, Coverage coverage) {
        row.put("asOf", window.asOf());
        row.put("businessZone", window.businessZone());
        row.put("coverage", coverage.status().name());
        row.put("missingSources", String.join(",", coverage.reasons()));
    }

    private static String total(Map<String, BigDecimal> amounts) {
        return amounts.isEmpty() ? null : decimal(amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String decimal(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
