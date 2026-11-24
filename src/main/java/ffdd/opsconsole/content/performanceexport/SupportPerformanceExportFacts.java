package ffdd.opsconsole.content.performanceexport;

import ffdd.opsconsole.content.application.SupportPerformanceMetrics;
import ffdd.opsconsole.content.mapper.SupportPerformanceHistoryMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
    private final SupportPerformanceHistoryMapper history;

    SupportPerformanceExportFacts(SupportPerformanceCache cache, SupportOwnershipService ownership,
            SupportLeaderboardMapper leaderboard, AdminObjectDisplayNameService names, SupportAnalyticsMapper analytics,
            SupportPerformanceHistoryMapper history) {
        this.cache = cache;
        this.ownership = ownership;
        this.leaderboard = leaderboard;
        this.names = names;
        this.analytics = analytics;
        this.history = history;
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
                        "PERIOD_EVENT", m.from().toString(), m.to().toString(), snapshot.asOf().toString(), m.businessZone(),
                        SupportPerformance.digest(snapshot.sourceVersion() + "|" + ownership.actorId() + "|" + scope.kind()
                                + "|" + scope.groupIds()))))
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
        var buckets = new LinkedHashMap<EventAttribution, MonthlySubtotal>();
        snapshot.monthlySubtotals().stream().filter(row -> window.month().equals(row.month())
                        && visible(row.attribution(), scope, actor)).forEach(row -> buckets.put(row.attribution(), row));
        snapshot.bindings().stream().filter(binding -> binding.from().isBefore(month.to())
                        && (binding.to() == null || binding.to().isAfter(month.from()))).map(SupportPerformance.Binding::attribution)
                .filter(assignment -> visible(assignment, scope, actor)).forEach(assignment -> buckets.computeIfAbsent(assignment,
                        key -> SupportPerformanceAggregation.subtotal(month.month(), key, List.of())));
        var subtotals = List.copyOf(buckets.values());
        if (subtotals.isEmpty() && coverage.status() != Status.COMPLETE) return null;

        var agentNames = names.byIds(subtotals.stream().map(row -> row.attribution().agentId())
                .filter(Objects::nonNull).distinct().toList());
        var groupNames = groupNames();
        var monthlyEvents = snapshot.events().stream().filter(event -> month.contains(event.effectiveAt()))
                .collect(Collectors.groupingBy(Event::attribution));
        var supervisors = history.supervisorsAtCutoff(LocalDateTime.ofInstant(month.to(), ZoneOffset.UTC));
        var supervisorNames = names.byIds(supervisors.stream().map(SupportPerformanceHistoryMapper.Supervisor::adminId).distinct().toList());
        var byGroup = supervisors.stream().collect(Collectors.toMap(SupportPerformanceHistoryMapper.Supervisor::groupId,
                SupportPerformanceHistoryMapper.Supervisor::adminId));
        var rows = subtotals.stream().map(subtotal -> {
            var assignment = subtotal.attribution();
            var events = monthlyEvents.getOrDefault(assignment, List.of());
            var row = values(subtotal, window, agentNames, groupNames, coverage);
            Long supervisor = byGroup.get(assignment.groupId());
            if (supervisor != null) row.put("supervisorAtCutoff", supervisorNames.get(supervisor));
            SupportPerformanceMetrics.counts(snapshot, month.from(), month.to(), events, assignment::equals)
                    .forEach((key, count) -> {
                        if (count > 0 || coverage.status() == Status.COMPLETE) row.put("counts." + key, count.toString());
                    });
            fillReferences(row, "references.", events);
            putTotal(row, "vndReference.bankTopupReceived", events.stream().filter(event -> event.kind() == Kind.INFLOW
                    && "VN_BANK_TOPUP".equals(event.channel())).map(Event::vnd).filter(Objects::nonNull).toList());
            putTotal(row, "vndReference.bankDirectReceived", events.stream().filter(event -> event.kind() == Kind.INFLOW
                    && "VN_BANK_DIRECT_PURCHASE".equals(event.channel())).map(Event::vnd).filter(Objects::nonNull).toList());
            putTotal(row, "vndReference.bankInflowCorrection", events.stream().filter(event -> event.kind() == Kind.CORRECTION
                    && event.vnd() != null).map(event -> event.deltaUsdt().signum() < 0 ? event.vnd().negate() : event.vnd()).toList());
            return row;
        }).toList();
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
        var ids = monthEvents.stream().map(Event::customerId).distinct().toList();
        Map<Long, RootDisplayRow> allowed = new LinkedHashMap<>();
        for (var readScope : scope.currentCustomerScopes(actor)) {
            for (int offset = 0; offset < ids.size(); offset += 200) {
                analytics.rootDisplayRows(readScope, ids.subList(offset, Math.min(offset + 200, ids.size())))
                        .forEach(row -> allowed.put(row.customerId(), row));
            }
        }
        var disclosed = monthEvents.stream().filter(event -> allowed.containsKey(event.customerId())).toList();
        var agentNames = names.byIds(disclosed.stream().map(event -> event.attribution().agentId())
                .filter(Objects::nonNull).distinct().toList());
        List<Map<String, String>> rows = new ArrayList<>();
        if (type == Type.CUSTOMER_DETAIL) {
            var cumulative = portfolio.stream().filter(event -> allowed.containsKey(event.customerId())).collect(Collectors.toMap(
                            CustomerAgent::of, Event::deltaUsdt, BigDecimal::add));
            disclosed.stream().collect(Collectors.groupingBy(CustomerAgent::of, LinkedHashMap::new, Collectors.toList()))
                    .forEach((key, events) -> rows.add(customer(window, month, coverage, events, allowed.get(key.customerId()),
                            key.agentId() == null ? null : agentNames.get(key.agentId()), cumulative.get(key),
                            snapshot.bindings().stream().filter(binding -> binding.customerId() == key.customerId()
                                    && Objects.equals(key.agentId(), binding.attribution().agentId())
                                    && visible(binding.attribution(), scope, actor)).toList())));
        } else {
            var groups = groupNames();
            disclosed.forEach(event -> rows.add(transaction(event, allowed.get(event.customerId()).customerNo(),
                    event.attribution().agentId() == null ? null : agentNames.get(event.attribution().agentId()), groups, coverage)));
        }
        var restricted = monthEvents.stream().filter(event -> !allowed.containsKey(event.customerId())).toList();
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
        return new Table(type, Columns.forType(type), List.copyOf(rows), coverage, restricted.isEmpty() ? 0 : 1,
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
            List<Event> events, RootDisplayRow display, String name, BigDecimal cumulative, List<SupportPerformance.Binding> bindings) {
        Map<String, String> row = new LinkedHashMap<>();
        var subtotal = SupportPerformanceAggregation.subtotal(month.month(), events.get(0).attribution(), events);
        row.put("customerNo", display.customerNo());
        row.put("maskedNickname", SupportPerformance.maskNickname(display.nickname()));
        row.put("eventAgent", name);
        var periods = new LinkedHashSet<String>();
        java.util.stream.Stream.concat(events.stream().map(Event::bindingPeriod).filter(Objects::nonNull),
                bindings.stream().map(binding -> new SupportPerformance.BindingPeriod(binding.from(), binding.to())))
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
        fillReferences(row, "", events);
        row.put("performanceAmount", decimal(subtotal.performanceUsdt()));
        row.put("customerAgentCumulativePerformance", decimal(cumulative));
        addContext(row, window, coverage);
        return row;
    }

    private static Map<String, String> transaction(Event event, String customerNo, String agentName,
            Map<Long, String> groups, Coverage coverage) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("effectiveAt", event.effectiveAt().toString());
        row.put("occurredAt", event.occurredAt() == null ? null : event.occurredAt().toString());
        row.put("customerNo", customerNo);
        row.put("eventAgent", agentName);
        if (event.attribution().groupId() != null) row.put("eventGroup", groups.get(event.attribution().groupId()));
        row.put("kind", event.kind() == Kind.WITHDRAWAL_REFERENCE ? "WITHDRAWAL"
                : event.kind() == Kind.REFERENCE ? event.sourceStatus() : event.kind().name());
        if (event.devicePurchase()) row.put("referenceCategories", "DEVICE_PURCHASE");
        else if (event.kind() == Kind.REFERENCE) row.put("referenceCategories", event.sourceStatus());
        if (refund(event)) row.put("referenceCategories", "REFUND");
        row.put("channel", event.channel());
        row.put("network", event.network());
        row.put("businessNo", event.businessNo());
        row.put("businessAmount", decimal(event.vnd() == null ? event.amountUsdt() : event.vnd()));
        row.put("businessCurrency", event.vnd() == null ? SupportPerformance.CURRENCY : "VND");
        row.put("roundingAdjustmentUsdt", decimal(event.roundingUsdt()));
        row.put("totalPaidUsdt", decimal(event.totalPaidUsdt()));
        boolean settlementUnknown = event.kind() == Kind.WITHDRAWAL_REFERENCE && "CONFIRMED".equals(event.sourceStatus());
        row.put("performanceDeltaUsdt", settlementUnknown ? null : decimal(event.deltaUsdt()));
        if (event.kind() == Kind.WITHDRAWAL) row.put("walletDebitUsdt", decimal(event.amountUsdt()));
        row.put("withdrawalFeeUsdt", decimal(event.feeUsdt()));
        row.put("channelFeeUsdt", decimal(event.channelFeeUsdt()));
        row.put("networkFeeUsdt", decimal(event.networkFeeUsdt()));
        row.put("receivedUsdt", decimal(event.receivedUsdt()));
        row.put("vndAmount", decimal(event.vnd() == null || event.kind() != Kind.CORRECTION || event.deltaUsdt().signum() >= 0
                ? event.vnd() : event.vnd().negate()));
        row.put("sourceStatus", event.sourceStatus());
        row.put("status", event.performanceStatus());
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
        if (coverage.status() == Status.COMPLETE) {
            zeroIfMissing(row, "inflowTotal");
            zeroIfMissing(row, "correctionTotal");
            zeroIfMissing(row, "withdrawalTotal");
            SupportPerformance.INFLOW_CHANNELS.forEach(channel -> {
                zeroIfMissing(row, "inflowByChannel." + channel);
                zeroIfMissing(row, "correctionByChannel." + channel);
            });
            SupportPerformance.WITHDRAWAL_CHANNELS.forEach(channel -> zeroIfMissing(row, "withdrawalByChannel." + channel + ".walletDebited"));
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

    private static void fillReferences(Map<String, String> row, String prefix, List<Event> events) {
        putTotal(row, prefix + "devicePurchases", events.stream().filter(Event::devicePurchase).map(Event::amountUsdt).toList());
        putTotal(row, prefix + "refunds", events.stream().filter(SupportPerformanceExportFacts::refund).map(Event::amountUsdt).toList());
        putTotal(row, prefix + "manualCredits", events.stream().filter(event -> "MANUAL_CREDIT".equals(event.sourceStatus()))
                .map(Event::amountUsdt).toList());
        putTotal(row, prefix + "rewardsCredited", events.stream().filter(event -> "REWARD".equals(event.sourceStatus()))
                .map(Event::amountUsdt).toList());
    }

    private static void zeroIfMissing(Map<String, String> row, String column) {
        if (row.get(column) == null) row.put(column, "0");
    }

    private static boolean refund(Event event) {
        return "REFUND".equals(event.sourceStatus()) || "EXTERNAL_REFUND_CONFIRMED".equals(event.sourceStatus());
    }

    private static void putTotal(Map<String, String> row, String field, List<BigDecimal> amounts) {
        if (!amounts.isEmpty()) row.put(field, decimal(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    private static String total(Map<String, BigDecimal> amounts) {
        return amounts.isEmpty() ? null : decimal(amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String decimal(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
