package ffdd.opsconsole.content.performanceexport;

import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.content.application.SupportPerformanceCache;
import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.MonthlySubtotal;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.platform.application.AdminObjectDisplayNameService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reads summary rows from one published B80 generation after the export service authorizes the scope. */
@Component
class SupportPerformanceSummaryExportFacts implements PerformanceExportFactSource {
    private final SupportPerformanceCache cache;
    private final SupportOwnershipService ownership;
    private final SupportLeaderboardMapper leaderboard;
    private final AdminObjectDisplayNameService names;

    SupportPerformanceSummaryExportFacts(SupportPerformanceCache cache, SupportOwnershipService ownership,
            SupportLeaderboardMapper leaderboard, AdminObjectDisplayNameService names) {
        this.cache = cache;
        this.ownership = ownership;
        this.leaderboard = leaderboard;
        this.names = names;
    }

    @Override
    public List<String> months() {
        return published().map(snapshot -> snapshot.months().stream().map(SupportPerformance.Month::month).toList())
                .orElseGet(List::of);
    }

    @Override
    public Window window(String month, Scope scope) {
        return published().flatMap(snapshot -> usableMonth(snapshot, month)
                .map(m -> new Window(snapshot.definitionVersion(), snapshot.sourceVersion(), month,
                        "PERIOD_EVENT", m.from().toString(), m.to().toString(), snapshot.asOf().toString(), m.businessZone())))
                .orElse(null);
    }

    @Override
    public Table read(Type type, Window window, Scope scope) {
        if (type != Type.SUMMARY) return null;
        var snapshot = published().filter(value -> value.sourceVersion().equals(window.sourceVersion())).orElse(null);
        Long actor = ownership.actorId();
        if (snapshot == null || actor == null) return null;
        var month = usableMonth(snapshot, window.month()).orElse(null);
        if (month == null) return null;
        var subtotals = snapshot.monthlySubtotals().stream()
                .filter(row -> window.month().equals(row.month()) && visible(row, scope, actor)).toList();
        if (subtotals.isEmpty()) return null;

        var agentNames = names.byIds(subtotals.stream().map(row -> row.attribution().agentId())
                .filter(Objects::nonNull).distinct().toList());
        var groupNames = leaderboard.groups().stream().filter(group -> group.name() != null)
                .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name));
        var reasons = new LinkedHashSet<String>();
        month.reasons().forEach(reason -> reasons.add(reason.name()));
        reasons.add("SOURCE_INCOMPLETE");
        var coverage = new Coverage(Status.PARTIAL, List.copyOf(reasons));
        var rows = subtotals.stream().map(row -> values(row, window, agentNames, groupNames, coverage)).toList();
        return new Table(type, Columns.forType(type), rows, coverage,
                0, List.of());
    }

    private Optional<SupportPerformance.Snapshot> published() {
        return cache.current().filter(snapshot -> snapshot.generation() == SupportPerformance.Generation.READY);
    }

    private static Optional<SupportPerformance.Month> usableMonth(SupportPerformance.Snapshot snapshot, String month) {
        return snapshot.months().stream().filter(m -> m.month().equals(month))
                .filter(m -> m.coverage() == SupportPerformance.Coverage.COMPLETE
                        || m.coverage() == SupportPerformance.Coverage.PARTIAL)
                .findFirst();
    }

    private static boolean visible(MonthlySubtotal subtotal, Scope scope, Long actor) {
        var attribution = subtotal.attribution();
        return switch (scope.kind()) {
            case "SELF" -> actor.equals(attribution.agentId());
            case "MANAGED_GROUPS" -> attribution.state() == SupportPerformance.AttributionState.ASSIGNED
                    && scope.groupIds().contains(attribution.groupId().toString());
            case "ALL" -> true;
            default -> false;
        };
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
        row.put("asOf", window.asOf());
        row.put("businessZone", window.businessZone());
        row.put("coverage", coverage.status().name());
        row.put("missingSources", String.join(",", coverage.reasons()));
        return row;
    }

    private static String total(Map<String, BigDecimal> amounts) {
        return amounts.isEmpty() ? null : decimal(amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String decimal(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
