package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Basis;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.content.domain.SupportPerformance.*;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.View;
import ffdd.opsconsole.content.dto.SupportLeaderboardQuery;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.RootDisplayRow;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.platform.application.AdminObjectDisplayNameService;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in reads of one B80 generation. Legacy queries continue through the legacy readers. */
@Service
public class SupportPerformanceReadService {
    private final SupportPerformanceCache cache;
    private final SupportOwnershipService ownership;
    private final SupportAnalyticsMapper analytics;
    private final AdminObjectDisplayNameService names;
    private final SupportLeaderboardMapper leaderboard;

    public SupportPerformanceReadService(SupportPerformanceCache cache, SupportOwnershipService ownership,
            SupportAnalyticsMapper analytics, AdminObjectDisplayNameService names, SupportLeaderboardMapper leaderboard) {
        this.cache = cache;
        this.ownership = ownership;
        this.analytics = analytics;
        this.names = names;
        this.leaderboard = leaderboard;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> analytics(Map<String, List<String>> raw) {
        var parameters = parameters(raw);
        boolean eventsView = List.of("PERFORMANCE_EVENTS").equals(parameters.get("view"));
        boolean amountSort = List.of("PERFORMANCE_AMOUNT").equals(parameters.get("sortKey"));
        if (eventsView) parameters.put("view", List.of("FINANCE"));
        if (amountSort) parameters.remove("sortKey");
        var query = SupportAnalyticsQueryRequest.fromParameters(parameters)
                .normalize(Set.of(SupportPerformance.CURRENCY), SupportAnalyticsPrivateQueryService.SORTS);
        boolean period = query.basis() == Basis.PERIOD_EVENT;
        if (eventsView && (!period || query.currency() == null || query.keyword() != null
                || !Set.of(SupportAnalyticsQueryRequest.Sort.SUCCEEDED_AT, SupportAnalyticsQueryRequest.Sort.AMOUNT).contains(query.sortKey()))) {
            throw new BizException(422, "SUPPORT_ANALYTICS_DETAIL_QUERY_INVALID");
        }
        if (amountSort && (query.currency() == null || query.basis() != Basis.CURRENT_CUSTOMER_HISTORY || query.view() != View.CUSTOMERS)) {
            throw new BizException(422, "SUPPORT_ANALYTICS_SORT_INVALID");
        }
        if (query.basis() == Basis.CURRENT_ASSET) throw new BizException(422, "SUPPORT_METRIC_VIEW_UNAVAILABLE");
        if (query.category() != SupportAnalyticsQueryRequest.Category.ALL
                || query.firstState() != SupportAnalyticsQueryRequest.First.ALL
                || query.filter() != SupportAnalyticsQueryRequest.ServiceFilter.ALL) {
            throw new BizException(422, "SUPPORT_METRIC_FILTER_UNAVAILABLE");
        }
        ReadScope scope = ownership.defaultQueryScope(query.groupId(), query.agentId());
        var grants = grants(scope.actorId());
        var groups = Set.copyOf(analytics.currentGrantedGroupIds(scope));
        var current = published();
        if (current.isEmpty()) {
            if (query.expectedVersion() != null) throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
            return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "UPGRADING");
        }

        var snapshot = current.get();
        Instant from = period && query.from().isAfter(snapshot.historyStart()) ? query.from() : snapshot.historyStart();
        Instant to = period && query.to().isBefore(snapshot.asOf()) ? query.to() : snapshot.asOf();
        if (!from.isBefore(to)) throw new BizException(422, "SUPPORT_PERFORMANCE_RANGE_INVALID");
        var months = snapshot.months().stream().filter(m -> m.from().isBefore(to) && from.isBefore(m.to())).toList();
        String month = months.size() == 1 && period ? months.get(0).month() : null;
        if (period && (month == null || !query.from().equals(months.get(0).from())
                || (!query.to().equals(months.get(0).to()) && !query.to().equals(BusinessCalendar.legacy()
                        .startOf(YearMonth.parse(month).plusMonths(1).atDay(1)))))) {
            throw new BizException(422, "SUPPORT_PERFORMANCE_RANGE_INVALID");
        }
        String zone = months.isEmpty() ? "Asia/Ho_Chi_Minh" : months.get(months.size() - 1).businessZone();
        List<Event> inWindow = snapshot.events().stream()
                .filter(e -> SupportPerformance.contains(from, to, e.effectiveAt())).toList();
        var ids = analytics.currentCustomers(scope).stream().map(SupportAnalyticsMapper.CurrentCustomer::customerId).toList();
        var visibleCustomers = new TreeMap<Long, RootDisplayRow>();
        for (int offset = 0; offset < ids.size(); offset += 200) {
            analytics.rootDisplayRows(scope, ids.subList(offset, Math.min(offset + 200, ids.size())))
                    .forEach(row -> visibleCustomers.put(row.customerId(), row));
        }
        var currentHistory = snapshot.events().stream().filter(e -> visibleCustomers.containsKey(e.customerId())).toList();
        List<Event> selected = period ? inWindow.stream().filter(e -> historicalScope(e, scope, groups)).toList() : currentHistory;
        String scopeVersion = version("", snapshot, scope.toString(), new TreeSet<>(groups).toString(), grants.toString(),
                visibleCustomers.keySet().toString(), query.basis().name());
        String version = version("saq-v1:", snapshot, scopeVersion, from.toString(), to.toString(),
                query.view().name(), query.sortKey().name(), query.direction().name(),
                String.valueOf(query.keyword()), Boolean.toString(eventsView), Boolean.toString(amountSort));
        requireVersion(query.expectedVersion(), version, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        Map<String, Object> window = fields("definitionVersion", SupportPerformance.DEFINITION_VERSION,
                "sourceVersion", snapshot.sourceVersion(), "sourceScopeVersion", scopeVersion, "month", month, "basis", query.basis().name(),
                "from", from.toString(), "to", to.toString(), "asOf", snapshot.asOf().toString(), "businessZone", zone);
        var currentWindow = new LinkedHashMap<String, Object>(window);
        currentWindow.putAll(fields("sourceScopeVersion", version("", snapshot, scope.toString(), new TreeSet<>(groups).toString(),
                        grants.toString(), visibleCustomers.keySet().toString(), Basis.CURRENT_CUSTOMER_HISTORY.name()),
                "month", null, "basis", Basis.CURRENT_CUSTOMER_HISTORY.name(), "from", snapshot.historyStart().toString(),
                "to", snapshot.asOf().toString(), "businessZone", BusinessCalendar.legacy().zoneAt(snapshot.asOf()).getId()));
        var performance = privateAmounts(selected, window);
        var cumulative = privateAmounts(currentHistory, currentWindow);
        var financial = fields("performance", performance);
        var summary = fields("financial", financial, "current", fields("performance", cumulative),
                "groups", period ? groups(selected, window) : List.of());
        if (scope.mode() == ReadMode.ALL && query.groupId() == null && query.agentId() == null && period) {
            summary.put("performanceAttribution", fields("unassigned", privateAmounts(bucket(selected, AttributionState.UNASSIGNED), window),
                    "unknown", privateAmounts(bucket(selected, AttributionState.UNKNOWN), window)));
        }
        var disclosed = selected.stream().filter(e -> visibleCustomers.containsKey(e.customerId())
                && (!period || e.attribution().agentId() != null)).toList();
        List<Map<String, Object>> records;
        if (eventsView) {
            Comparator<Event> order = query.sortKey() == SupportAnalyticsQueryRequest.Sort.AMOUNT
                    ? Comparator.comparing(Event::deltaUsdt) : Comparator.comparing(Event::effectiveAt);
            if (query.direction() == SupportAnalyticsQueryRequest.Direction.DESC) order = order.reversed();
            var agentNames = names.byIds(disclosed.stream().map(e -> e.attribution().agentId()).distinct().toList());
            var groupNames = leaderboard.groups().stream().filter(g -> g.name() != null)
                    .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name));
            records = disclosed.stream().sorted(order.thenComparing(Event::id))
                    .map(e -> eventRow(e, visibleCustomers.get(e.customerId()), agentNames, groupNames)).toList();
        } else {
            records = records(query, amountSort, query.view() == View.AGENTS
                    ? selected.stream().filter(e -> historicalScope(e, scope, groups)).toList() : disclosed,
                    currentHistory, visibleCustomers, window, currentWindow);
        }
        var page = page(records, query.pageNum(), query.pageSize());
        var disclosedIds = disclosed.stream().map(Event::id).collect(Collectors.toSet());

        if (!scope.equals(ownership.defaultQueryScope(query.groupId(), query.agentId()))
                || !groups.equals(Set.copyOf(analytics.currentGrantedGroupIds(scope))) || !grants.equals(grants(scope.actorId()))) {
            throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        }
        return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "READY",
                "view", eventsView ? "PERFORMANCE_EVENTS" : query.view().name(), "basis", query.basis().name(),
                "asOf", snapshot.asOf().toString(), "businessZone", SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId(),
                "queryVersion", version, "sourceVersion", snapshot.sourceVersion(), "performanceWindow", window,
                "versionState", "READY", "recordsStatus", "PARTIAL", "total", null, "observedTotal", (long) records.size(),
                "pageNum", query.pageNum(), "pageSize", query.pageSize(), "canContinue",
                query.pageNum() * query.pageSize() < records.size(), "records", page, "scopeSummary", summary,
                "selectedCustomerCount", (long) visibleCustomers.size(),
                "selectedCurrent", fields("performance", cumulative),
                "firstPaymentCoverage", fields("status", "UNKNOWN", "reasonCodes", List.of("HISTORY_INCOMPLETE")), "funds", null,
                "detailSummary", eventsView ? details(selected, disclosed, disclosedIds, window) : null,
                "exportReadiness", exportReadiness(snapshot, selected.isEmpty()));
    }

    private List<String> grants(Long actor) {
        var grants = analytics.currentReadGrants(actor).stream().map(SupportAnalyticsMapper.PermissionStamp::permissionCode)
                .filter(SupportOwnershipService::hasAuthority).sorted().toList();
        if (!grants.contains("service_m1_read") && !grants.contains("service_m3_read")) {
            throw new BizException(403, "SUPPORT_SCOPE_FORBIDDEN");
        }
        return grants;
    }

    Map<String, Object> leaderboard(SupportLeaderboardQuery.Normalized query, Long requestedAgent, long actor,
            Set<Long> groups, List<Map<String, Object>> scopeOptions) {
        var current = published();
        if (current.isEmpty()) {
            if (query.expectedVersion() != null) throw new BizException(409, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
            return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "UPGRADING");
        }
        var snapshot = current.get();
        var month = snapshot.months().stream().filter(m -> m.month().equals(query.referenceMonth().toString())).findFirst()
                .orElseThrow(() -> new BizException(422, "SUPPORT_LEADERBOARD_MONTH_UNAVAILABLE"));
        String version = version("slb-v1:", snapshot, query.scope().name(), new TreeSet<>(groups).toString(), month.month());
        requireVersion(query.expectedVersion(), version, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
        var eligible = snapshot.monthlySubtotals().stream().filter(row -> month.month().equals(row.month())
                && row.attribution().agentId() != null && (groups.isEmpty()
                || row.attribution().groupId() != null && groups.contains(row.attribution().groupId()))).toList();
        var byAgent = eligible.stream().collect(Collectors.groupingBy(row -> row.attribution().agentId()));
        var agentNames = names.byIds(byAgent.keySet());
        var observed = byAgent.entrySet().stream().map(entry -> {
            long id = entry.getKey();
            var parts = entry.getValue();
            BigDecimal value = parts.stream().map(MonthlySubtotal::performanceUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal inflows = total(parts.stream().flatMap(row -> row.inflows().values().stream()).toList());
            BigDecimal debits = total(parts.stream().flatMap(row -> row.withdrawals().values().stream())
                    .map(WithdrawalSubtotal::walletDebitedUsdt).toList());
            var name = agentNames.getOrDefault(id, Long.toString(id));
            return new Ranked(id, name, value, fields("agentId", Long.toString(id), "name", name, "avatarUrl", null,
                    "groupName", null, "qualification", "UNKNOWN", "rank", null, "isTied", false,
                    "rankMetricCoverage", "PARTIAL", "firstPayment", unknownCount(), "customers", unknownCount(),
                    "performance", fields("currency", SupportPerformance.CURRENCY, "month", month.month(),
                            "performanceAmount", amount(value), "inflowTotal", amount(inflows), "withdrawalTotal", amount(debits)),
                    "movement", fields("kind", "UNAVAILABLE", "places", null, "previousRank", null,
                            "baselineAt", null, "baselineVersion", null, "reason", "DEFINITION_CHANGED"),
                    "canViewCustomers", ownership.canReadAgent(actor, id)));
        }).sorted(Comparator.comparing(Ranked::amount).reversed().thenComparingLong(Ranked::id)).toList();
        var matches = observed.stream().filter(r -> query.keyword() == null
                || r.name().toLowerCase(Locale.ROOT).contains(query.keyword().toLowerCase(Locale.ROOT))).toList();
        Map<String, Object> body = fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "READY",
                "viewVersion", version, "queryVersion", version, "sourceVersion", snapshot.sourceVersion(),
                "definitionVersion", SupportPerformance.DEFINITION_VERSION, "board", query.board().name(),
                "rankMonth", month.month(), "referenceMonth", month.month(), "scope", query.scope().name(),
                "currency", SupportPerformance.CURRENCY, "businessZone", month.businessZone(), "asOf", snapshot.asOf().toString(),
                "publishedAt", snapshot.asOf().toString(), "state", "PROVISIONAL",
                "candidateCoverage", observed.isEmpty() ? "UNKNOWN" : "PARTIAL",
                "stale", cache.stale(snapshot), "refreshFailed", cache.refreshFailed(),
                "selectableMonths", snapshot.months().stream().map(Month::month).toList(),
                "currencies", List.of(SupportPerformance.CURRENCY), "scopeOptions", scopeOptions,
                "exportReadiness", exportReadiness(snapshot, observed.isEmpty()));
        if (requestedAgent != null) {
            body.put("row", observed.stream().filter(r -> requestedAgent == r.id()).map(Ranked::row).findFirst()
                    .orElseThrow(() -> new BizException(404, "SUPPORT_LEADERBOARD_AGENT_NOT_FOUND")));
            return body;
        }
        var self = observed.stream().filter(r -> r.id() == actor).findFirst().orElse(null);
        body.putAll(fields("total", observed.size(), "ranked", 0, "unranked", observed.size(), "matched", matches.size(),
                "pageNum", query.pageNum(), "pageSize", query.pageSize(),
                "rows", page(matches, query.pageNum(), query.pageSize()).stream().map(Ranked::row).toList(),
                "podium", fields("total", 0, "rows", List.of()),
                "self", fields("row", self == null ? null : self.row(), "gap", null, "reason", "METRIC_INCOMPLETE",
                        "pageNum", self == null ? null : observed.indexOf(self) / query.pageSize() + 1)));
        return body;
    }

    static Map<String, List<String>> parameters(Map<String, List<String>> raw) {
        if (!List.of(SupportPerformance.DEFINITION_VERSION).equals(raw.get("metricVersion"))) {
            throw new BizException(422, "SUPPORT_METRIC_VERSION_UNSUPPORTED");
        }
        var result = new LinkedHashMap<>(raw);
        result.remove("metricVersion");
        return result;
    }

    private Optional<SupportPerformance.Snapshot> published() {
        return cache.current().filter(s -> s.generation() == SupportPerformance.Generation.READY);
    }

    private Map<String, Object> exportReadiness(SupportPerformance.Snapshot snapshot, boolean empty) {
        String reason = cache.refreshFailed() ? "EXPORT_REFRESH_FAILED" : cache.stale(snapshot)
                ? "EXPORT_DATA_STALE" : empty ? "EXPORT_ROWS_UNVERIFIED" : null;
        return fields("state", reason == null ? "ELIGIBLE" : "BLOCKED", "reasonCodes", reason == null ? List.of() : List.of(reason));
    }

    private static boolean historicalScope(Event event, ReadScope scope, Set<Long> groups) {
        var assignment = event.attribution();
        if (scope.requestedAgentId() != null && !scope.requestedAgentId().equals(assignment.agentId())) return false;
        if (scope.requestedGroupId() != null && !scope.requestedGroupId().equals(assignment.groupId())) return false;
        return switch (scope.mode()) {
            case ALL -> true;
            case MANAGED -> assignment.groupId() != null && groups.contains(assignment.groupId());
            case PERSONAL -> scope.actorId().equals(assignment.agentId());
        };
    }

    private static List<Event> bucket(List<Event> events, AttributionState state) {
        return events.stream().filter(e -> e.attribution().state() == state).toList();
    }

    private static List<Map<String, Object>> groups(List<Event> events, Map<String, Object> window) {
        var groups = events.stream().filter(e -> e.attribution().groupId() != null)
                .collect(Collectors.groupingBy(e -> e.attribution().groupId(), TreeMap::new, Collectors.toList()));
        return groups.entrySet().stream().map(group -> fields("groupId", group.getKey().toString(), "period",
                fields("performance", privateAmounts(group.getValue(), window)))).toList();
    }

    private static Map<String, Object> privateAmounts(List<Event> rows, Map<String, Object> window) {
        var totals = SupportPerformanceAggregation.subtotal(null, null, rows);
        Map<String, Object> inflows = new LinkedHashMap<>(), corrections = new LinkedHashMap<>(), withdrawals = new LinkedHashMap<>();
        for (String channel : List.of("CHAIN_TOPUP", "VN_BANK_TOPUP", "CHAIN_DIRECT_PURCHASE", "VN_BANK_DIRECT_PURCHASE")) {
            inflows.put(channel, amount(totals.inflows().get(channel)));
            corrections.put(channel, amount(totals.corrections().get(channel)));
        }
        for (String channel : List.of("TRC20", "BEP20", "ERC20", "VN_BANK")) {
            var debit = totals.withdrawals().get(channel);
            withdrawals.put(channel, fields("walletDebited", amount(debit == null ? null : debit.walletDebitedUsdt()),
                    "fee", amount(debit == null ? null : debit.feeUsdt()), "netReceived", amount(debit == null ? null : debit.receivedUsdt())));
        }
        return fields("window", window, "currency", SupportPerformance.CURRENCY,
                "performanceAmount", amount(rows.isEmpty() ? null : totals.performanceUsdt()),
                "inflowTotal", amount(total(totals.inflows().values())),
                "withdrawalTotal", amount(total(totals.withdrawals().values().stream().map(WithdrawalSubtotal::walletDebitedUsdt).toList())),
                "inflowByChannel", inflows, "withdrawalByChannel", withdrawals,
                "correctionTotal", amount(total(totals.corrections().values())), "correctionByChannel", corrections,
                "vndReference", fields("bankTopupReceived", null, "bankDirectReceived", null,
                        "bankPayoutReceived", null, "bankInflowCorrection", null),
                "references", fields("devicePurchases", amount(total(rows.stream().filter(Event::devicePurchase).map(Event::amountUsdt).toList())),
                        "refunds", amount(null), "manualCredits", amount(null), "rewardsCredited", amount(null)),
                "counts", fields("boundAtCutoff", unknownCount(), "newlyBound", unknownCount(), "withInflow", unknownCount(),
                        "withWithdrawal", unknownCount(), "firstPayment", unknownCount(), "negativePerformance", unknownCount()));
    }

    private List<Map<String, Object>> records(SupportAnalyticsQueryRequest.Normalized query, boolean amountSort,
            List<Event> selected, List<Event> currentHistory, Map<Long, RootDisplayRow> customers,
            Map<String, Object> window, Map<String, Object> currentWindow) {
        boolean agents = query.view() == View.AGENTS;
        if (!agents && !Set.of(View.CUSTOMERS, View.OVERVIEW, View.ACTIVITY).contains(query.view())) return List.of();
        var rows = selected.stream().filter(e -> !agents || e.attribution().agentId() != null)
                .collect(Collectors.groupingBy(e -> agents ? e.attribution().agentId() : e.customerId(),
                        TreeMap::new, Collectors.toList()));
        var customerHistory = agents ? Map.<Long, List<Event>>of()
                : currentHistory.stream().collect(Collectors.groupingBy(Event::customerId));
        if (!agents) customers.forEach((id, profile) -> {
            if (matches(query.keyword(), profile)) rows.putIfAbsent(id, List.of());
            else rows.remove(id);
        });
        Map<Long, String> agentNames = agents ? names.byIds(rows.keySet()) : Map.of();
        var entries = new ArrayList<>(rows.entrySet());
        if (amountSort) {
            Comparator<BigDecimal> direction = query.direction() == SupportAnalyticsQueryRequest.Direction.DESC
                    ? Comparator.reverseOrder() : Comparator.naturalOrder();
            Comparator<Map.Entry<Long, List<Event>>> byAmount = Comparator.comparing(entry ->
                    total(entry.getValue().stream().map(Event::deltaUsdt).toList()), Comparator.nullsLast(direction));
            entries.sort(byAmount.thenComparing(Map.Entry::getKey));
        }
        return entries.stream().filter(entry -> !agents || query.keyword() == null
                || matches(query.keyword(), agentNames.get(entry.getKey())))
                .map(entry -> agents ? fields("agentId", entry.getKey().toString(), "name", agentNames.get(entry.getKey()),
                        "period", fields("performance", privateAmounts(entry.getValue(), window)))
                        : fields("customerId", entry.getKey().toString(), "customerNo", customers.get(entry.getKey()).customerNo(),
                        "performance", privateAmounts(entry.getValue(), window), "current", fields("performance",
                                privateAmounts(customerHistory.getOrDefault(entry.getKey(), List.of()), currentWindow)))).toList();
    }

    private static boolean matches(String keyword, RootDisplayRow row) {
        return keyword == null || matches(keyword, row.customerNo()) || matches(keyword, row.nickname());
    }

    private static boolean matches(String keyword, String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> eventRow(Event e, RootDisplayRow customer, Map<Long, String> agents, Map<Long, String> groups) {
        return fields("eventId", publicEventId(e.id()), "customerId", Long.toString(e.customerId()), "customerNo", customer.customerNo(),
                "maskedNickname", SupportPerformance.maskNickname(customer.nickname()),
                "attributionState", e.attribution().state().name(), "agentId", text(e.attribution().agentId()),
                "agentName", agents.get(e.attribution().agentId()), "groupId", text(e.attribution().groupId()),
                "groupName", e.attribution().groupId() == null ? null : groups.get(e.attribution().groupId()),
                "kind", e.kind().name(), "channel", e.channel(), "reasonCodes", List.of(),
                "references", e.devicePurchase() ? List.of("DEVICE_PURCHASE") : List.of(),
                "network", e.network(), "businessNo", e.businessNo(), "originalBusinessNo", e.originalBusinessNo(),
                "originalEventId", e.originalEventId() == null ? null : publicEventId(e.originalEventId()),
                "occurredAt", e.occurredAt().toString(), "effectiveAt", e.effectiveAt().toString(), "sourceStatus", e.sourceStatus(),
                "status", "EFFECTIVE", "businessAmount", e.vnd() == null ? text(e.amountUsdt()) : null,
                "businessCurrency", e.vnd() == null ? SupportPerformance.CURRENCY : null,
                "performanceDeltaUsdt", text(e.deltaUsdt()), "walletDebitUsdt", text(e.kind() == Kind.WITHDRAWAL ? e.amountUsdt() : null),
                "withdrawalFeeUsdt", text(e.feeUsdt()), "channelFeeUsdt", text(e.channelFeeUsdt()),
                "networkFeeUsdt", text(e.networkFeeUsdt()), "receivedUsdt", text(e.receivedUsdt()), "vndAmount", null);
    }

    private static String publicEventId(String id) {
        return UUID.nameUUIDFromBytes((SupportPerformance.DEFINITION_VERSION + ":" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> details(List<Event> selected, List<Event> visible, Set<String> disclosed,
            Map<String, Object> window) {
        var restricted = selected.stream().filter(event -> !disclosed.contains(event.id())).toList();
        return fields("performance", privateAmounts(selected, window), "visiblePerformance", privateAmounts(visible, window),
                "restrictedPerformance", privateAmounts(restricted, window), "totalCount", count(selected.size()),
                "visibleCount", count(visible.size()), "restrictedCount", count(restricted.size()),
                "customerCount", count(selected.stream().map(Event::customerId).distinct().count()),
                "visibleCustomerCount", count(visible.stream().map(Event::customerId).distinct().count()),
                "restrictedCustomerCount", count(restricted.stream().map(Event::customerId).distinct().count()));
    }

    private static Map<String, Object> count(long observed) {
        return fields("value", null, "observed", observed, "status", "PARTIAL", "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static Map<String, Object> amount(BigDecimal value) {
        return fields("value", null, "observed", text(value), "status", value == null ? "UNKNOWN" : "PARTIAL",
                "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static Map<String, Object> unknownCount() {
        return fields("value", null, "observed", null, "status", "UNKNOWN", "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static BigDecimal total(Collection<BigDecimal> values) {
        return values.isEmpty() ? null : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String text(Object value) {
        if (value instanceof BigDecimal amount) return amount.stripTrailingZeros().toPlainString();
        return value == null ? null : value.toString();
    }

    private static <T> List<T> page(List<T> values, long number, int size) {
        long offset = (number - 1) * size;
        return values.subList((int) Math.min(offset, values.size()), (int) Math.min(offset + size, values.size()));
    }

    private static String version(String prefix, SupportPerformance.Snapshot snapshot, String... fields) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(snapshot.sourceVersion().getBytes(StandardCharsets.UTF_8));
            for (String field : fields) {
                hash.update((byte) 0);
                hash.update(field.getBytes(StandardCharsets.UTF_8));
            }
            return prefix + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void requireVersion(String expected, String current, String reason) {
        if (expected != null && !expected.equals(current)) throw new BizException(409, reason);
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private record Ranked(long id, String name, BigDecimal amount, Map<String, Object> row) { }
}
