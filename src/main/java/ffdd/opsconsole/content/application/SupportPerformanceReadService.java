package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Basis;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats;
import ffdd.opsconsole.content.domain.SupportLeaderboard.Board;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final SupportAnalyticsPrivateQueryService baseline;

    public SupportPerformanceReadService(SupportPerformanceCache cache, SupportOwnershipService ownership,
            SupportAnalyticsMapper analytics, AdminObjectDisplayNameService names, SupportLeaderboardMapper leaderboard,
            SupportAnalyticsPrivateQueryService baseline) {
        this.cache = cache;
        this.ownership = ownership;
        this.analytics = analytics;
        this.names = names;
        this.leaderboard = leaderboard;
        this.baseline = baseline;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> analytics(Map<String, List<String>> raw) {
        var parameters = parameters(raw);
        boolean eventsView = List.of("PERFORMANCE_EVENTS").equals(parameters.get("view"));
        boolean amountSort = List.of("PERFORMANCE_AMOUNT").equals(parameters.get("sortKey"));
        if (eventsView) parameters.put("view", List.of("FINANCE"));
        if (amountSort) parameters.remove("sortKey");
        var query = SupportAnalyticsQueryRequest.fromParameters(parameters)
                .normalize(SupportAnalyticsPrivateQueryService.CURRENCIES, SupportAnalyticsPrivateQueryService.SORTS);
        boolean period = query.basis() == Basis.PERIOD_EVENT;
        boolean firstView = query.view() == View.FIRST_DEPOSITS || query.view() == View.FIRST_PURCHASES;
        boolean firstFilter = query.firstState() != SupportAnalyticsQueryRequest.First.ALL;
        boolean firstDependent = firstView || firstFilter || Set.of(SupportAnalyticsQueryRequest.Sort.FIRST_SUCCEEDED_AT,
                SupportAnalyticsQueryRequest.Sort.FIRST_CONFIRMED_CUSTOMER_COUNT).contains(query.sortKey());
        if (eventsView && (!period || !SupportPerformance.CURRENCY.equals(query.currency()) || query.keyword() != null
                || query.category() != SupportAnalyticsQueryRequest.Category.ALL
                || query.filter() != SupportAnalyticsQueryRequest.ServiceFilter.ALL || firstFilter
                || !Set.of(SupportAnalyticsQueryRequest.Sort.SUCCEEDED_AT, SupportAnalyticsQueryRequest.Sort.AMOUNT).contains(query.sortKey()))) {
            throw new BizException(422, "SUPPORT_ANALYTICS_DETAIL_QUERY_INVALID");
        }
        if (firstView && (!period || query.currency() == null
                || !Set.of(SupportAnalyticsQueryRequest.Sort.SUCCEEDED_AT, SupportAnalyticsQueryRequest.Sort.AMOUNT).contains(query.sortKey()))) {
            throw new BizException(422, "SUPPORT_ANALYTICS_DETAIL_QUERY_INVALID");
        }
        if (!firstView) SupportAnalyticsPrivateQueryService.validateView(query);
        if (amountSort && (!SupportPerformance.CURRENCY.equals(query.currency())
                || query.basis() != Basis.CURRENT_CUSTOMER_HISTORY || query.view() != View.CUSTOMERS)) {
            throw new BizException(422, "SUPPORT_ANALYTICS_SORT_INVALID");
        }
        if (query.basis() == Basis.CURRENT_ASSET) {
            if (firstDependent) throw new BizException(422, "SUPPORT_ANALYTICS_SORT_INVALID");
            var independent = new LinkedHashMap<>(baseline.query(parameters));
            independent.putAll(fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation",
                    published().isPresent() ? "READY" : "UPGRADING", "performanceWindow", null));
            return independent;
        }
        ReadScope scope = ownership.defaultQueryScope(query.groupId(), query.agentId());
        var grants = grants(scope.actorId());
        var groups = Set.copyOf(analytics.currentGrantedGroupIds(scope));
        var current = published();
        if (current.isEmpty()) {
            if (query.expectedVersion() != null) throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
            if (eventsView || firstView) return fields("metricVersion", SupportPerformance.DEFINITION_VERSION,
                    "generation", "UPGRADING", "view", eventsView ? "PERFORMANCE_EVENTS" : query.view().name());
            return upgradingAnalytics(parameters, query, scope, firstDependent, firstFilter);
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
        var currentOwners = analytics.currentCustomers(scope).stream().collect(Collectors.toMap(
                SupportAnalyticsMapper.CurrentCustomer::customerId, customer -> customer));
        var ids = currentOwners.keySet().stream().sorted().toList();
        var visibleCustomers = new TreeMap<Long, RootDisplayRow>();
        for (int offset = 0; offset < ids.size(); offset += 200) {
            analytics.rootDisplayRows(scope, ids.subList(offset, Math.min(offset + 200, ids.size())))
                    .forEach(row -> visibleCustomers.put(row.customerId(), row));
        }
        var currentHistory = snapshot.events().stream().filter(e -> visibleCustomers.containsKey(e.customerId())).toList();
        boolean fullHistory = !snapshot.months().isEmpty() && snapshot.months().stream().allMatch(entry -> entry.coverage() == Coverage.COMPLETE);
        var historyByCustomer = currentHistory.stream().collect(Collectors.groupingBy(Event::customerId));
        var firstByCustomer = snapshot.firstPayments().stream().collect(Collectors.toMap(FirstPayment::customerId, f -> f));
        var baselineParameters = new LinkedHashMap<>(parameters);
        if (firstView) {
            baselineParameters.put("view", List.of(View.CUSTOMERS.name()));
            baselineParameters.remove("sortKey");
        }
        var originalPage = baseline.performanceBaseline(baselineParameters,
                id -> firstSelection(snapshot, firstByCustomer.get(id), id),
                amountSort ? id -> {
                    var sum = sumPerformance(historyByCustomer.getOrDefault(id, List.of()));
                    return sum == null && fullHistory ? BigDecimal.ZERO : sum;
                } : null);
        Map<String, Object> original = originalPage.body();
        var selectedIds = originalPage.selectedCustomers();
        boolean scopedFirstComplete = snapshot.firstHistoryReady().containsAll(originalPage.eligibleCustomers())
                && (!firstView || months.get(0).firstHistoryComplete());
        boolean hasConfirmedFirst = originalPage.eligibleCustomers().stream().map(firstByCustomer::get)
                .anyMatch(first -> first != null && first.confirmed());
        var firstCoverage = scopedFirstComplete ? completeFirst() : firstCoverage(hasConfirmedFirst);
        boolean firstUnavailable = firstDependent && !scopedFirstComplete;
        List<Event> selected = period ? inWindow.stream().filter(e -> historicalScope(e, scope, groups)).toList() : currentHistory;
        String scopeVersion = scopeVersion(snapshot, scope, groups, grants, query.basis());
        String version = version("saq-v1:", snapshot, scopeVersion, from.toString(), to.toString(),
                query.view().name(), query.sortKey().name(), query.direction().name(),
                query.firstState().name(), query.category().name(), query.filter().name(), String.valueOf(query.currency()),
                String.valueOf(query.keyword()), Boolean.toString(eventsView), Boolean.toString(amountSort),
                String.valueOf(original.get("queryVersion")), visibleCustomers.keySet().toString(), new TreeSet<>(selectedIds).toString());
        if (firstUnavailable && query.expectedVersion() != null) throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        boolean versionReady = eventsView || firstView || "READY".equals(original.get("versionState"));
        requireVersion(query.expectedVersion(), versionReady ? version : null, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        Map<String, Object> window = fields("definitionVersion", SupportPerformance.DEFINITION_VERSION,
                "sourceVersion", snapshot.sourceVersion(), "sourceScopeVersion", scopeVersion, "month", month, "basis", query.basis().name(),
                "from", from.toString(), "to", to.toString(), "asOf", snapshot.asOf().toString(), "businessZone", zone);
        boolean complete = !months.isEmpty() && months.stream().allMatch(entry -> entry.coverage() == Coverage.COMPLETE);
        var currentWindow = new LinkedHashMap<String, Object>(window);
        currentWindow.putAll(fields("sourceScopeVersion", scopeVersion(snapshot, scope, groups, grants, Basis.CURRENT_CUSTOMER_HISTORY),
                "month", null, "basis", Basis.CURRENT_CUSTOMER_HISTORY.name(), "from", snapshot.historyStart().toString(),
                "to", snapshot.asOf().toString(), "businessZone", BusinessCalendar.legacy().zoneAt(snapshot.asOf()).getId()));
        var performance = period ? measuredCounts(privateAmounts(selected, window, complete), snapshot, from, to, selected,
                assignment -> historicalScope(assignment, scope, groups), complete) : privateAmounts(selected, window, complete);
        var cumulative = privateAmounts(currentHistory, currentWindow, fullHistory);
        var summary = mapAt(original, "scopeSummary");
        var financial = mapAt(summary, "financial");
        financial.put("performance", period ? performance : null);
        var currentSummary = mapAt(summary, "current");
        currentSummary.put("performance", cumulative);
        summary.putAll(fields("financial", financial, "current", currentSummary,
                "groups", groups(summary.get("groups"), selected, window, snapshot, currentHistory, currentWindow,
                        currentOwners, complete, fullHistory, period, scope, groups, grants)));
        if (scope.mode() == ReadMode.ALL && query.view() == View.OVERVIEW && query.groupId() == null && query.agentId() == null) {
            summary.put("performanceAttribution", period
                    ? fields("unassigned", privateAmounts(bucket(selected, AttributionState.UNASSIGNED), window, complete),
                            "unknown", privateAmounts(bucket(selected, AttributionState.UNKNOWN), window, complete))
                    : null);
        }
        var disclosed = selected.stream().filter(e -> e.attribution().state() != AttributionState.UNKNOWN
                && visibleCustomers.containsKey(e.customerId())).toList();
        List<Map<String, Object>> records;
        List<FirstPayment> firstFacts = List.of(), visibleFirst = List.of();
        if (eventsView) {
            boolean descending = query.direction() == SupportAnalyticsQueryRequest.Direction.DESC;
            Comparator<BigDecimal> amounts = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
            Comparator<Event> order = query.sortKey() == SupportAnalyticsQueryRequest.Sort.AMOUNT
                    ? Comparator.comparing(e -> e.settlementUnknown() ? null : e.deltaUsdt(), Comparator.nullsLast(amounts))
                    : descending ? Comparator.comparing(Event::effectiveAt).reversed() : Comparator.comparing(Event::effectiveAt);
            var agentNames = names.byIds(disclosed.stream().map(e -> e.attribution().agentId()).filter(Objects::nonNull).distinct().toList());
            var groupNames = leaderboard.groups().stream().filter(g -> g.name() != null)
                    .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name));
            records = disclosed.stream().sorted(order.thenComparing(e -> publicEventId(e.id())))
                    .map(e -> eventRow(e, visibleCustomers.get(e.customerId()), agentNames, groupNames)).toList();
        } else if (firstView) {
            var kind = query.view() == View.FIRST_DEPOSITS ? "DEPOSIT" : "DEVICE_PURCHASE";
            Comparator<FirstPayment> order = query.sortKey() == SupportAnalyticsQueryRequest.Sort.AMOUNT
                    ? Comparator.comparing(FirstPayment::amount) : Comparator.comparing(FirstPayment::at);
            if (query.direction() == SupportAnalyticsQueryRequest.Direction.DESC) order = order.reversed();
            boolean filtered = firstFilter || query.category() != SupportAnalyticsQueryRequest.Category.ALL
                    || query.filter() != SupportAnalyticsQueryRequest.ServiceFilter.ALL || query.keyword() != null;
            var permittedIds = firstUnavailable ? originalPage.eligibleCustomers() : selectedIds;
            firstFacts = snapshot.firstPayments().stream().filter(first -> first.confirmed() && kind.equals(first.kind())
                            && query.currency().equals(first.currency()) && SupportPerformance.contains(from, to, first.at())
                            && historicalScope(first.attribution(), scope, groups)
                            && (!filtered || permittedIds.contains(first.customerId()))).toList();
            visibleFirst = firstFacts.stream().filter(first -> visibleCustomers.containsKey(first.customerId())).toList();
            var firstNames = names.byIds(visibleFirst.stream().map(FirstPayment::attribution).map(EventAttribution::agentId)
                    .filter(Objects::nonNull).distinct().toList());
            records = visibleFirst.stream().sorted(order.thenComparingLong(FirstPayment::customerId).thenComparing(FirstPayment::factId))
                    .map(first -> firstRow(first, visibleCustomers.get(first.customerId()), firstNames))
                    .toList();
        } else {
            records = maps(original.get("records")).stream().map(row -> {
                Long customerId = id(row.get("customerId"));
                Long agentId = id(row.get("accountId"));
                if (customerId != null && Set.of(View.OVERVIEW, View.CUSTOMERS, View.ACTIVITY).contains(query.view())) {
                    row.put("performance", privateAmounts(historyByCustomer.getOrDefault(customerId, List.of()), currentWindow, fullHistory));
                    var first = firstByCustomer.get(customerId);
                    if (first != null && first.confirmed() && first.channel() != null) {
                        var firstViewData = mapAt(row, "first");
                        firstViewData.put("channel", first.channel());
                        row.put("first", firstViewData);
                    }
                }
                if (agentId != null && query.view() == View.AGENTS) {
                    var selectedAgent = selected.stream().filter(event -> event.attribution().state() != AttributionState.UNKNOWN
                            && agentId.equals(event.attribution().agentId())).toList();
                    var currentAgent = currentHistory.stream().filter(event -> agentId.equals(currentOwners.get(event.customerId()).ownerAgentId())).toList();
                    var currentAgentRow = mapAt(row, "current");
                    var periodAgentRow = mapAt(row, "period");
                    var agentScope = new ReadScope(scope.actorId(), scope.mode(), scope.requestedGroupId(), agentId);
                    currentAgentRow.put("performance", privateAmounts(currentAgent,
                            withScopeVersion(currentWindow, scopeVersion(snapshot, agentScope, groups, grants, Basis.CURRENT_CUSTOMER_HISTORY)), fullHistory));
                    if (period) periodAgentRow.put("performance", measuredCounts(privateAmounts(selectedAgent,
                            withScopeVersion(window, scopeVersion(snapshot, agentScope, groups, grants, Basis.PERIOD_EVENT)), complete),
                            snapshot, from, to, selectedAgent, a -> agentId.equals(a.agentId()) && historicalScope(a, scope, groups), complete));
                    row.putAll(fields("current", currentAgentRow, "period", period ? periodAgentRow : null));
                }
                return row;
            }).toList();
        }
        boolean explicitDetails = eventsView || firstView;
        var page = explicitDetails ? page(records, query.pageNum(), query.pageSize()) : records;
        var disclosedIds = disclosed.stream().map(Event::id).collect(Collectors.toSet());

        if (!scope.equals(ownership.defaultQueryScope(query.groupId(), query.agentId()))
                || !groups.equals(Set.copyOf(analytics.currentGrantedGroupIds(scope))) || !grants.equals(grants(scope.actorId()))) {
            throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        }
        var selectedCurrent = mapAt(original, "selectedCurrent");
        selectedCurrent.put("performance", privateAmounts(currentHistory.stream()
                .filter(event -> selectedIds.contains(event.customerId())).toList(), currentWindow, fullHistory));
        var firstSummary = firstView ? firstSummary(firstFacts, visibleFirst, query.currency(), scopedFirstComplete) : null;
        var result = fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "READY",
                "view", eventsView ? "PERFORMANCE_EVENTS" : query.view().name(), "basis", query.basis().name(),
                "asOf", snapshot.asOf().toString(), "businessZone", SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId(),
                "queryVersion", versionReady ? version : null, "sourceVersion", snapshot.sourceVersion(), "performanceWindow", window,
                "versionState", versionReady ? "READY" : "UNKNOWN", "recordsStatus", explicitDetails ? (firstView ? scopedFirstComplete : complete) ? "AVAILABLE" : "PARTIAL" : original.get("recordsStatus"),
                "total", explicitDetails ? (firstView ? scopedFirstComplete : complete) ? (long) records.size() : null : original.get("total"),
                "observedTotal", explicitDetails ? (long) records.size() : original.get("observedTotal"),
                "pageNum", query.pageNum(), "pageSize", query.pageSize(), "canContinue", explicitDetails
                    ? query.pageNum() * query.pageSize() < records.size() : original.get("canContinue"), "records", page, "scopeSummary", summary,
                "selectedCustomerCount", original.getOrDefault("selectedCustomerCount", (long) visibleCustomers.size()),
                "selectedCurrent", selectedCurrent,
                "firstPaymentCoverage", firstCoverage, "funds", original.get("funds"),
                "detailSummary", eventsView ? details(selected, disclosed, disclosedIds, window, complete)
                        : firstView ? firstSummary : original.get("detailSummary"),
                "exportReadiness", period ? exportReadiness(snapshot) : fields("state", "NOT_APPLICABLE", "reasonCodes", List.of()));
        if (firstUnavailable) {
            result.putAll(fields("pageNum", 1L, "records", List.of(), "recordsStatus", "UNKNOWN",
                    "total", null, "versionState", "UNKNOWN", "queryVersion", null, "canContinue", false,
                    "observedTotal", firstView ? mapAt(firstSummary, "visibleCount").get("observed") : null));
            if (firstFilter) result.putAll(fields("selectedCustomerCount", null, "selectedCurrent", null, "funds", null));
        }
        return result;
    }

    private Map<String, Object> upgradingAnalytics(Map<String, List<String>> parameters,
            SupportAnalyticsQueryRequest.Normalized query, ReadScope scope, boolean firstDependent, boolean firstFilter) {
        var independent = new LinkedHashMap<>(parameters);
        if (firstDependent) {
            independent.remove("firstState");
            independent.remove("sortKey");
        }
        var body = new LinkedHashMap<>(baseline.performanceBaseline(independent, id -> new SupportAnalyticsStats.FirstSelection(
                null, SupportAnalyticsStats.Status.UNKNOWN, List.of("GENERATION_UPGRADING")), null).body());
        boolean period = query.basis() == Basis.PERIOD_EVENT;
        var window = fields("definitionVersion", SupportPerformance.DEFINITION_VERSION, "sourceVersion", null,
                "sourceScopeVersion", SupportPerformance.digest(scope.toString()), "month", period
                        ? BusinessCalendar.legacy().month(query.from()).toString() : null, "basis", query.basis().name(),
                "from", period ? query.from().toString() : SupportPerformance.HISTORY_START.toString(),
                "to", period ? query.to().toString() : null, "asOf", null, "businessZone", SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId());
        var cumulative = new LinkedHashMap<>(window);
        cumulative.putAll(fields("basis", Basis.CURRENT_CUSTOMER_HISTORY.name(), "month", null,
                "from", SupportPerformance.HISTORY_START.toString(), "to", null));
        var current = privateAmounts(List.of(), cumulative);
        var periodAmounts = period ? privateAmounts(List.of(), window) : null;
        var summary = mapAt(body, "scopeSummary");
        putPerformance(summary, "current", current);
        putPerformance(summary, "financial", periodAmounts);
        var groups = maps(summary.get("groups"));
        groups.forEach(group -> {
            putPerformance(group, "current", current);
            putPerformance(group, "period", periodAmounts);
        });
        summary.put("groups", groups);
        if (scope.mode() == ReadMode.ALL && query.view() == View.OVERVIEW && query.groupId() == null && query.agentId() == null) {
            summary.put("performanceAttribution", period ? fields("unassigned", periodAmounts, "unknown", periodAmounts) : null);
        }
        var rows = maps(body.get("records"));
        rows.forEach(row -> {
            if (query.view() == View.AGENTS) {
                putPerformance(row, "current", current);
                row.put("period", period ? fields("performance", periodAmounts) : null);
            } else if (Set.of(View.OVERVIEW, View.CUSTOMERS, View.ACTIVITY).contains(query.view())) {
                row.put("performance", current);
            }
        });
        putPerformance(body, "selectedCurrent", current);
        body.putAll(fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "UPGRADING",
                "performanceWindow", window, "firstPaymentCoverage", fields("status", "UNKNOWN",
                        "reasonCodes", List.of("GENERATION_UPGRADING")), "scopeSummary", summary,
                "records", firstDependent ? List.of() : rows, "observedTotal", firstDependent ? null : body.get("observedTotal"),
                "canContinue", false, "versionState", "UNKNOWN", "queryVersion", null, "total", null,
                "recordsStatus", firstDependent ? "UNKNOWN" : body.get("recordsStatus"),
                "exportReadiness", period ? fields("state", "BLOCKED", "reasonCodes", List.of("EXPORT_GENERATION_UPGRADING"))
                        : fields("state", "NOT_APPLICABLE", "reasonCodes", List.of())));
        if (firstFilter) body.putAll(fields("selectedCustomerCount", null, "selectedCurrent", null, "funds", null));
        return body;
    }

    private static void putPerformance(Map<String, Object> parent, String key, Map<String, Object> performance) {
        var projection = mapAt(parent, key);
        projection.put("performance", performance);
        parent.put(key, projection);
    }

    static Map<String, Object> firstCoverage(boolean confirmed) {
        return fields("status", confirmed ? "PARTIAL" : "UNKNOWN", "reasonCodes", List.of("HISTORY_INCOMPLETE"));
    }

    private static Map<String, Object> completeFirst() {
        return fields("status", "COMPLETE", "reasonCodes", List.of());
    }

    private static SupportAnalyticsStats.FirstSelection firstSelection(Snapshot snapshot, FirstPayment first, long customer) {
        if (first != null && first.confirmed()) {
            return new SupportAnalyticsStats.FirstSelection(new SupportAnalyticsStats.FirstCandidate(first.kind(),
                    first.channel(), LocalDateTime.ofInstant(first.at(), SupportAnalyticsQueryRequest.BUSINESS_ZONE),
                    6, first.amount(), first.currency(), null), SupportAnalyticsStats.Status.AVAILABLE,
                    List.of(), SupportAnalyticsStats.FirstState.CONFIRMED);
        }
        return snapshot.firstHistoryReady().contains(customer) && first == null
                ? new SupportAnalyticsStats.FirstSelection(null, SupportAnalyticsStats.Status.AVAILABLE,
                        List.of(), SupportAnalyticsStats.FirstState.NONE)
                : new SupportAnalyticsStats.FirstSelection(null, SupportAnalyticsStats.Status.UNKNOWN,
                        List.of("HISTORY_INCOMPLETE"), SupportAnalyticsStats.FirstState.UNKNOWN);
    }

    private static Map<String, Object> firstRow(FirstPayment first, RootDisplayRow customer, Map<Long, String> agents) {
        var attribution = first.attribution();
        var row = fields("id", publicEventId(first.factId()), "customerId", Long.toString(first.customerId()),
                "customerNo", customer.customerNo(), "nickname", customer.nickname(),
                "kind", first.kind(), "currency", first.currency(), "amount", text(first.amount()),
                "succeededAt", first.at().toString(), "agentId", text(attribution.agentId()),
                "groupId", text(attribution.groupId()), "agentDisplayName",
                attribution.agentId() == null ? null : agents.get(attribution.agentId()));
        if (first.channel() != null) row.put("channel", first.channel());
        return row;
    }

    private static Map<String, Object> firstSummary(List<FirstPayment> facts, List<FirstPayment> visible,
            String currency, boolean complete) {
        var visibleIds = visible.stream().map(FirstPayment::customerId).collect(Collectors.toSet());
        var restricted = facts.stream().filter(first -> !visibleIds.contains(first.customerId())).toList();
        if (complete) {
            var fullAmount = zeroIfMissing(total(facts.stream().map(FirstPayment::amount).toList()));
            return fields("currency", currency, "status", "AVAILABLE", "observedAmount", fullAmount, "totalAmount", fullAmount,
                    "visibleAmount", zeroIfMissing(total(visible.stream().map(FirstPayment::amount).toList())),
                    "restrictedAmount", zeroIfMissing(total(restricted.stream().map(FirstPayment::amount).toList())),
                    "totalCount", facts.size(), "visibleCount", visible.size(), "restrictedCount", restricted.size(),
                    "customerCount", facts.size(), "visibleCustomerCount", visible.size(), "restrictedCustomerCount", restricted.size());
        }
        return fields("currency", currency,
                "totalAmount", firstTotal(facts, complete), "visibleAmount", firstTotal(visible, complete),
                "restrictedAmount", firstTotal(restricted, complete), "totalCount", firstCount(facts.size(), complete),
                "visibleCount", firstCount(visible.size(), complete), "restrictedCount", firstCount(restricted.size(), complete),
                "customerCount", firstCount(facts.size(), complete), "visibleCustomerCount", firstCount(visible.size(), complete),
                "restrictedCustomerCount", firstCount(restricted.size(), complete));
    }

    private static String zeroIfMissing(BigDecimal value) {
        return value == null ? "0" : text(value);
    }

    private static Map<String, Object> firstTotal(List<FirstPayment> facts, boolean complete) {
        var sum = total(facts.stream().map(FirstPayment::amount).toList());
        if (complete) return amount(sum, true);
        return fields("value", null, "observed", text(sum), "status", sum == null ? "UNKNOWN" : "PARTIAL",
                "reasons", List.of("HISTORY_INCOMPLETE"));
    }

    private static Map<String, Object> firstCount(long observed, boolean complete) {
        return fields("value", complete ? observed : null, "observed", complete || observed > 0 ? observed : null,
                "status", complete ? "COMPLETE" : observed > 0 ? "PARTIAL" : "UNKNOWN",
                "reasons", complete ? List.of() : List.of("HISTORY_INCOMPLETE"));
    }

    public Map<String, Object> customer(long customer, Map<String, List<String>> raw,
                                        java.util.function.Supplier<Map<String, Object>> legacy) {
        if (!parameters(raw).isEmpty()) throw new BizException(422, "SUPPORT_ANALYTICS_QUERY_INVALID");
        var scope = ownership.customerQueryScope(customer);
        var body = new LinkedHashMap<>(legacy.get());
        var current = published();
        var window = current.<Map<String, Object>>map(snapshot -> fields(
                "definitionVersion", SupportPerformance.DEFINITION_VERSION, "sourceVersion", snapshot.sourceVersion(),
                "sourceScopeVersion", version("", snapshot, scope.toString(), Long.toString(customer)),
                "month", null, "basis", Basis.CURRENT_CUSTOMER_HISTORY.name(), "from", snapshot.historyStart().toString(),
                "to", snapshot.asOf().toString(), "asOf", snapshot.asOf().toString(),
                "businessZone", BusinessCalendar.legacy().zoneAt(snapshot.asOf()).getId())).orElseGet(() -> fields(
                "definitionVersion", SupportPerformance.DEFINITION_VERSION, "sourceVersion", null,
                "sourceScopeVersion", "customer:" + customer, "month", null, "basis", Basis.CURRENT_CUSTOMER_HISTORY.name(),
                "from", SupportPerformance.HISTORY_START.toString(), "to", null, "asOf", null,
                "businessZone", BusinessCalendar.legacy().zoneAt(SupportPerformance.HISTORY_START).getId()));
        var events = current.map(snapshot -> snapshot.events().stream().filter(event -> event.customerId() == customer).toList())
                .orElseGet(List::of);
        boolean amountComplete = current.filter(s -> !s.months().isEmpty()
                && s.months().stream().allMatch(month -> month.coverage() == Coverage.COMPLETE)).isPresent();
        var projection = privateAmounts(events, window, amountComplete);
        var first = current.flatMap(snapshot -> snapshot.firstPayments().stream()
                .filter(candidate -> candidate.customerId() == customer).findFirst());
        if (body.get("profile") instanceof Map<?, ?> legacyProfile) {
            var profile = copyFields(legacyProfile);
            for (String key : List.of("statistics", "finance")) {
                if (!(profile.get(key) instanceof Map<?, ?> group) || !"READY".equals(group.get("status"))
                        || !(group.get("data") instanceof Map<?, ?> data)) continue;
                var view = copyFields(data);
                view.put("performance", projection);
                if ("statistics".equals(key)) {
                    var selection = current.map(snapshot -> firstSelection(snapshot, first.orElse(null), customer)).orElse(null);
                    boolean confirmed = selection != null && selection.state() == SupportAnalyticsStats.FirstState.CONFIRMED;
                    boolean none = selection != null && selection.state() == SupportAnalyticsStats.FirstState.NONE;
                    view.put("firstPaymentCoverage", current.isEmpty()
                            ? fields("status", "UNKNOWN", "reasonCodes", List.of("GENERATION_UPGRADING"))
                            : confirmed || none ? completeFirst() : firstCoverage(first.isPresent()));
                    var payment = confirmed ? first.orElseThrow() : null;
                    var firstView = fields("state", confirmed ? "CONFIRMED" : none ? "NONE" : "UNKNOWN",
                            "status", selection == null ? "UNKNOWN" : selection.status().name(), "kind", payment == null ? null : payment.kind(),
                            "currency", payment == null ? null : payment.currency(), "amount", payment == null ? null : text(payment.amount()),
                            "succeededAt", payment == null ? null : payment.at().toString());
                    if (payment != null && payment.channel() != null) firstView.put("channel", payment.channel());
                    view.put("first", firstView);
                }
                var projectedGroup = copyFields(group);
                projectedGroup.put("data", view);
                profile.put(key, projectedGroup);
            }
            body.put("profile", profile);
        }
        if (!scope.equals(ownership.customerQueryScope(customer))) throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        body.put("metricVersion", SupportPerformance.DEFINITION_VERSION);
        body.put("generation", current.isPresent() ? "READY" : "UPGRADING");
        return body;
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
        var current = cache.currentPublication().filter(publication -> publication.snapshot().generation() == Generation.READY);
        if (current.isEmpty()) {
            if (query.expectedVersion() != null) throw new BizException(409, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
            return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "UPGRADING", "board", query.board().name());
        }
        var publication = current.get();
        var snapshot = publication.snapshot();
        var month = snapshot.months().stream().filter(m -> m.month().equals(query.referenceMonth().toString())).findFirst()
                .orElseThrow(() -> new BizException(422, "SUPPORT_LEADERBOARD_MONTH_UNAVAILABLE"));
        var board = boardRows(snapshot, month, query, groups, actor, publication.evidence(), true);
        var observed = board.rows();
        boolean complete = board.complete(), candidatesComplete = board.candidatesComplete();
        String version = board.version();
        requireVersion(query.expectedVersion(), version, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
        if (complete) rank(observed, snapshot, month, query, groups, publication.evidence());
        var matches = observed.stream().filter(r -> query.keyword() == null
                || r.name().toLowerCase(Locale.ROOT).contains(query.keyword().toLowerCase(Locale.ROOT))).toList();
        Map<String, Object> body = fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "READY",
                "viewVersion", version, "queryVersion", version, "sourceVersion", snapshot.sourceVersion(),
                "definitionVersion", SupportPerformance.DEFINITION_VERSION, "board", query.board().name(),
                "rankMonth", query.rankMonth() == null ? null : month.month(), "referenceMonth", month.month(), "scope", query.scope().name(),
                "currency", SupportPerformance.CURRENCY, "businessZone", month.businessZone(), "asOf", snapshot.asOf().toString(),
                "publishedAt", snapshot.publishedAt().toString(), "state", complete ? "COMPLETE" : "PROVISIONAL",
                "candidateCoverage", candidatesComplete ? "COMPLETE" : observed.isEmpty() ? "UNKNOWN" : "PARTIAL",
                "stale", cache.stale(snapshot), "refreshFailed", cache.refreshFailed(),
                "selectableMonths", snapshot.months().stream().map(Month::month).toList(),
                "currencies", List.of(SupportPerformance.CURRENCY), "scopeOptions", scopeOptions,
                "exportReadiness", exportReadiness(snapshot));
        if (requestedAgent != null) {
            body.put("row", observed.stream().filter(r -> requestedAgent == r.id()).map(Ranked::row).findFirst()
                    .orElseThrow(() -> new BizException(404, "SUPPORT_LEADERBOARD_AGENT_NOT_FOUND")));
            return body;
        }
        var self = observed.stream().filter(r -> r.id() == actor).findFirst().orElse(null);
        var podium = complete ? observed.stream().filter(r -> (Integer) r.row().get("rank") <= 3).toList() : List.<Ranked>of();
        body.putAll(fields("total", observed.size(), "ranked", complete ? observed.size() : 0,
                "unranked", complete ? 0 : observed.size(), "matched", matches.size(),
                "pageNum", query.pageNum(), "pageSize", query.pageSize(),
                "rows", page(matches, query.pageNum(), query.pageSize()).stream().map(Ranked::row).toList(),
                "podium", fields("total", podium.size(), "rows", podium.stream().limit(4).map(Ranked::row).toList()),
                "self", fields("row", self == null ? null : self.row(), "gap", complete && self != null
                                && (Integer) self.row().get("rank") > 1 ? text(observed.get(0).amount().subtract(self.amount())) : null,
                        "reason", self == null ? "NOT_A_CANDIDATE" : !complete ? "METRIC_INCOMPLETE"
                                : (Integer) self.row().get("rank") == 1 ? "LEADER" : "NONE",
                        "pageNum", self == null ? null : observed.indexOf(self) / query.pageSize() + 1)));
        return body;
    }

    private record BoardRows(List<Ranked> rows, String version, boolean complete, boolean candidatesComplete) { }

    private BoardRows boardRows(Snapshot snapshot, Month month, SupportLeaderboardQuery.Normalized query,
            Set<Long> groups, long actor, SupportPerformanceCache.RankEvidence evidence, boolean withCapabilities) {
        var cutoff = LocalDateTime.ofInstant(month.to().minusNanos(1_000), ZoneOffset.UTC);
        var memberships = evidence.memberships().stream()
                .filter(member -> activeAt(member.startsAt(), member.endsAt(), cutoff))
                .collect(Collectors.groupingBy(SupportLeaderboardMapper.MemberInterval::agentId, TreeMap::new, Collectors.toList()));
        boolean customersBoard = query.board() == Board.customers;
        var groupNames = evidence.groups().stream().filter(group -> group.name() != null)
                .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name,
                        (a, b) -> a, TreeMap::new));
        var qualifications = evidence.qualifications();
        var qualified = qualifications.stream().filter(q -> activeAt(q.startsAt(), q.endsAt(), cutoff))
                .collect(Collectors.groupingBy(SupportLeaderboardMapper.QualificationInterval::agentId, TreeMap::new, Collectors.toList()));
        var monthAgents = qualifications.stream().filter(q -> "ENABLED".equals(q.state())
                && !q.startsAt().isAfter(cutoff)
                && (q.endsAt() == null || q.endsAt().isAfter(q.startsAt())
                    && q.endsAt().toInstant(ZoneOffset.UTC).isAfter(month.from())))
                .map(SupportLeaderboardMapper.QualificationInterval::agentId).collect(Collectors.toSet());
        var currentBindings = evidence.bindings().stream().filter(binding ->
                activeAt(binding.startsAt(), binding.endsAt(), cutoff)).toList();
        var boundByAgent = currentBindings.stream().collect(Collectors.groupingBy(
                SupportLeaderboardMapper.Binding::agentId, TreeMap::new, Collectors.mapping(
                        SupportLeaderboardMapper.Binding::customerId, Collectors.toCollection(TreeSet::new))));
        var duplicated = currentBindings.stream().map(SupportLeaderboardMapper.Binding::customerId).distinct().count() != currentBindings.size();
        var byAgent = snapshot.monthlySubtotals().stream().filter(row -> month.month().equals(row.month())
                && row.attribution().agentId() != null && (groups.isEmpty()
                || row.attribution().groupId() != null && groups.contains(row.attribution().groupId())))
                .collect(Collectors.groupingBy(row -> row.attribution().agentId()));
        var firstByAgent = snapshot.firstPayments().stream().filter(first -> first.confirmed()
                && SupportPerformance.CURRENCY.equals(first.currency()) && month.contains(first.at())
                && first.attribution().state() != AttributionState.UNKNOWN && first.attribution().agentId() != null
                && (groups.isEmpty() || first.attribution().groupId() != null && groups.contains(first.attribution().groupId())))
                .collect(Collectors.groupingBy(first -> first.attribution().agentId(), Collectors.counting()));
        Set<Long> attributed = query.board() == Board.netContribution ? byAgent.keySet() : firstByAgent.keySet();
        var accounts = evidence.accounts().stream().filter(account ->
                Integer.valueOf(0).equals(account.isDeleted()) && Integer.valueOf(1).equals(account.compatibleRole())
                || customersBoard && boundByAgent.containsKey(account.id())
                || !customersBoard && (monthAgents.contains(account.id()) || attributed.contains(account.id()))).toList();
        var accountIds = accounts.stream().map(SupportLeaderboardMapper.Account::id).collect(Collectors.toSet());
        var monthStart = LocalDateTime.ofInstant(month.from(), ZoneOffset.UTC);
        var qualificationHistory = qualifications.stream().collect(Collectors.groupingBy(SupportLeaderboardMapper.QualificationInterval::agentId));
        var memberHistory = evidence.memberships().stream().collect(Collectors.groupingBy(SupportLeaderboardMapper.MemberInterval::agentId));
        var provedGroups = evidence.groups().stream().map(SupportLeaderboardMapper.Group::id).collect(Collectors.toSet());
        var firstOwnership = new HashMap<Long, LocalDateTime>();
        evidence.bindings().forEach(binding -> firstOwnership.merge(binding.agentId(), binding.startsAt(),
                (a, b) -> a.isBefore(b) ? a : b));
        snapshot.events().stream().filter(event -> month.contains(event.effectiveAt()) && event.attribution().agentId() != null
                && event.attribution().state() != AttributionState.UNKNOWN).forEach(event ->
                firstOwnership.merge(event.attribution().agentId(), LocalDateTime.ofInstant(event.effectiveAt(), ZoneOffset.UTC),
                        (a, b) -> a.isBefore(b) ? a : b));
        boolean currentMonth = month.month().equals(BusinessCalendar.legacy().month(snapshot.asOf()).toString());
        boolean knownCurrentBounds = currentMonth && !duplicated && accountIds.containsAll(boundByAgent.keySet())
                && boundByAgent.keySet().stream().allMatch(id -> qualificationHistory.getOrDefault(id, List.of()).stream()
                        .anyMatch(interval -> "ENABLED".equals(interval.state()) && !interval.startsAt().isAfter(cutoff)))
                && accounts.stream().filter(account -> Integer.valueOf(1).equals(account.serviceProfile())
                        && (groups.isEmpty() || memberships.getOrDefault(account.id(), List.of()).stream()
                            .anyMatch(member -> member.groupId() != null && groups.contains(member.groupId()))))
                    .allMatch(account -> qualified.getOrDefault(account.id(), List.of()).size() == 1);
        var historyAgents = new HashSet<>(monthAgents);
        historyAgents.addAll(firstOwnership.keySet());
        historyAgents.addAll(memberHistory.keySet());
        historyAgents.addAll(attributed);
        boolean historicalCandidates = evidence.cutover() != null && !monthStart.isBefore(evidence.cutover())
                && accountIds.containsAll(monthAgents) && accountIds.containsAll(attributed)
                && accounts.stream().filter(account -> historyAgents.contains(account.id())
                        || Integer.valueOf(1).equals(account.serviceProfile())).allMatch(account -> {
                    var member = memberships.getOrDefault(account.id(), List.of());
                    boolean mayBeInScope = groups.isEmpty() || attributed.contains(account.id()) || member.isEmpty() || member.stream()
                            .anyMatch(row -> row.groupId() != null && groups.contains(row.groupId()));
                    return !mayBeInScope || SupportLeaderboardSourceService.completeHistory(account,
                            qualificationHistory.getOrDefault(account.id(), List.of()), memberHistory.getOrDefault(account.id(), List.of()),
                            provedGroups, monthStart, cutoff, evidence.cutover(), firstOwnership.get(account.id()));
                });
        boolean candidatesComplete = (customersBoard ? knownCurrentBounds : historicalCandidates)
                && memberships.values().stream().allMatch(rows -> rows.size() == 1
                        && (rows.get(0).groupId() == null || provedGroups.contains(rows.get(0).groupId())))
                && qualified.values().stream().allMatch(rows -> rows.size() == 1) && !duplicated;
        boolean amountComplete = month.coverage() == Coverage.COMPLETE;
        boolean complete = candidatesComplete && (query.board() == Board.customers
                || query.board() == Board.firstPayment && month.firstHistoryComplete()
                || query.board() == Board.netContribution && amountComplete);
        String version = version("slb-v1:", snapshot, SupportPerformance.DEFINITION_VERSION, query.board().name(),
                query.currency(), query.scope().name(), new TreeSet<>(groups).toString(), month.month(),
                accounts.toString(), memberships.toString(), qualifications.toString(), groupNames.toString(), boundByAgent.toString());
        var observed = accounts.stream().filter(account -> (query.board() == Board.customers
                        ? boundByAgent.containsKey(account.id()) || qualified.getOrDefault(account.id(), List.of()).stream()
                                .anyMatch(q -> "ENABLED".equals(q.state()) && Integer.valueOf(1).equals(account.status())
                                        && Integer.valueOf(1).equals(account.profileEnabled())
                                        && Integer.valueOf(1).equals(account.compatibleRole())
                                        && Integer.valueOf(0).equals(account.isDeleted()))
                        : monthAgents.contains(account.id()) || attributed.contains(account.id())) && (groups.isEmpty()
                        || memberships.getOrDefault(account.id(), List.of()).stream()
                                .anyMatch(member -> member.groupId() != null && groups.contains(member.groupId()))
                        || query.board() == Board.netContribution && byAgent.containsKey(account.id())
                        || query.board() == Board.firstPayment && firstByAgent.containsKey(account.id())))
                .map(account -> {
            long id = account.id();
            var parts = byAgent.getOrDefault(id, List.of());
            BigDecimal inflows = total(parts.stream().flatMap(row -> row.inflows().values().stream()).toList());
            BigDecimal debits = total(parts.stream().flatMap(row -> row.withdrawals().values().stream())
                    .map(WithdrawalSubtotal::walletDebitedUsdt).toList());
            BigDecimal value = inflows == null && debits == null ? null : parts.stream()
                    .map(MonthlySubtotal::performanceUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            var membership = memberships.getOrDefault(id, List.of());
            String group = membership.size() == 1 && membership.get(0).groupId() != null
                    ? groupNames.getOrDefault(membership.get(0).groupId(), "") : "";
            var qualificationRows = qualified.getOrDefault(id, List.of());
            boolean wasQualified = qualificationHistory.getOrDefault(id, List.of()).stream()
                    .anyMatch(q -> "ENABLED".equals(q.state()) && !q.startsAt().isAfter(cutoff));
            String qualification = boundByAgent.containsKey(id) && wasQualified
                    && (qualificationRows.size() != 1 || !"ENABLED".equals(qualificationRows.get(0).state())
                        || !Integer.valueOf(1).equals(account.status()) || !Integer.valueOf(1).equals(account.profileEnabled()))
                    ? "HANDOVER_REQUIRED" : qualificationRows.size() != 1 ? "UNKNOWN"
                    : switch (qualificationRows.get(0).state()) {
                case "ENABLED" -> Integer.valueOf(1).equals(account.status()) && Integer.valueOf(1).equals(account.profileEnabled()) ? "ACTIVE" : "DISABLED";
                case "REMOVED" -> "REMOVED";
                default -> "DISABLED";
            };
            var name = account.nickname() == null || account.nickname().isBlank() ? Long.toString(id) : account.nickname();
            long first = firstByAgent.getOrDefault(id, 0L);
            long customers = boundByAgent.getOrDefault(id, new TreeSet<>()).size();
            boolean currentCountsComplete = knownCurrentBounds;
            BigDecimal metric = query.board() == Board.firstPayment
                    ? month.firstHistoryComplete() ? BigDecimal.valueOf(first) : observedCount(first)
                    : query.board() == Board.customers ? BigDecimal.valueOf(customers)
                    : amountComplete && value == null ? BigDecimal.ZERO : value;
            boolean metricComplete = query.board() == Board.customers ? currentCountsComplete
                    : query.board() == Board.firstPayment ? month.firstHistoryComplete() : amountComplete;
            String avatarUrl = "ATTACHED".equals(account.avatarState()) && Long.valueOf(id).equals(account.avatarAdminId())
                    && account.avatarAssetId() != null && account.avatarVersion() != null
                    ? SupportLeaderboardService.performanceAvatarUrl(id, query, version) : null;
            return new Ranked(id, name, metric, fields("agentId", Long.toString(id), "name", name, "avatarUrl", avatarUrl,
                    "groupName", group, "qualification", qualification, "rank", null, "isTied", false,
                    "rankMetricCoverage", metricComplete ? "COMPLETE" : metric == null ? "UNKNOWN" : "PARTIAL",
                    "firstPayment", boardCount(first, month.firstHistoryComplete()), "customers", boardCount(customers, currentCountsComplete),
                    "performance", fields("currency", SupportPerformance.CURRENCY, "month", month.month(),
                            "performanceAmount", amount(value, amountComplete), "inflowTotal", amount(inflows, amountComplete),
                            "withdrawalTotal", amount(debits, amountComplete)),
                    "movement", movementUnavailable(!candidatesComplete ? "CANDIDATES_INCOMPLETE" : "METRIC_INCOMPLETE"),
                    "canViewCustomers", withCapabilities && ownership.canReadAgent(actor, id)));
        }).sorted(Comparator.comparing(Ranked::amount, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingLong(Ranked::id)).toList();
        return new BoardRows(observed, version, complete, candidatesComplete);
    }

    private void rank(List<Ranked> rows, Snapshot snapshot, Month month, SupportLeaderboardQuery.Normalized query,
                      Set<Long> groups, SupportPerformanceCache.RankEvidence evidence) {
        var ranks = ranks(rows, true);
        var calendar = BusinessCalendar.legacy();
        var day = calendar.dateAt(snapshot.asOf());
        String unavailable = null;
        BoardRows priorBoard = null;
        SupportPerformanceCache.DaySnapshot prior = null;
        if (!YearMonth.from(day).toString().equals(month.month())) {
            unavailable = "HISTORICAL_MONTH";
        } else if (day.getDayOfMonth() == 1 && query.board() != Board.customers) {
            unavailable = "MONTH_START";
        } else {
            prior = cache.priorDay(day, query.board());
            if (prior == null || prior.evidence() == null || prior.snapshot().months().isEmpty()) {
                unavailable = "NO_BASELINE";
            } else {
                var priorMonth = prior.snapshot().months().get(prior.snapshot().months().size() - 1);
                priorBoard = boardRows(prior.snapshot(), priorMonth, query, groups, 0, prior.evidence(), false);
                if (!priorBoard.complete()) unavailable = "NO_BASELINE";
            }
        }
        var priorRanks = unavailable == null ? ranks(priorBoard.rows(), false) : Map.<Long, Integer>of();
        if (unavailable == null && !priorRanks.keySet().equals(ranks.keySet())) {
            var baselineAt = LocalDateTime.ofInstant(prior.snapshot().asOf(), ZoneOffset.UTC);
            var evaluatedAt = LocalDateTime.ofInstant(snapshot.asOf(), ZoneOffset.UTC);
            var births = evidence.qualifications().stream().filter(interval -> interval.version() == 1
                    && "ENABLED".equals(interval.state()) && evidence.cutover() != null
                    && !interval.startsAt().isBefore(evidence.cutover()) && interval.startsAt().isAfter(baselineAt)
                    && !interval.startsAt().isAfter(evaluatedAt)).map(SupportLeaderboardMapper.QualificationInterval::agentId)
                    .collect(Collectors.toSet());
            if (!groups.isEmpty() || !ranks.keySet().containsAll(priorRanks.keySet()) || ranks.keySet().stream()
                    .anyMatch(id -> !priorRanks.containsKey(id) && !births.contains(id))) unavailable = "MEMBERS_CHANGED";
        }
        for (var row : rows) {
            if (unavailable != null) {
                row.row().put("movement", movementUnavailable(unavailable));
                continue;
            }
            Integer priorRank = priorRanks.get(row.id());
            if (priorRank == null) {
                row.row().put("movement", fields("kind", "NEW", "places", null, "previousRank", null,
                        "baselineAt", prior.snapshot().asOf().toString(), "baselineVersion", priorBoard.version(), "reason", "NONE"));
                continue;
            }
            int change = priorRank - ranks.get(row.id());
            row.row().put("movement", fields("kind", change > 0 ? "UP" : change < 0 ? "DOWN" : "SAME",
                    "places", Math.abs(change), "previousRank", priorRank, "baselineAt", prior.snapshot().asOf().toString(),
                    "baselineVersion", priorBoard.version(), "reason", "NONE"));
        }
    }

    private static Map<String, Object> movementUnavailable(String reason) {
        return fields("kind", "UNAVAILABLE", "places", null, "previousRank", null,
                "baselineAt", null, "baselineVersion", null, "reason", reason);
    }

    private static Map<Long, Integer> ranks(List<Ranked> rows, boolean project) {
        var frequencies = rows.stream().collect(Collectors.groupingBy(Ranked::amount, TreeMap::new, Collectors.counting()));
        var ranks = new TreeMap<Long, Integer>();
        BigDecimal previous = null;
        int rank = 0;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (previous == null || previous.compareTo(row.amount()) != 0) rank = index + 1;
            previous = row.amount();
            ranks.put(row.id(), rank);
            if (project) {
                row.row().put("rank", rank);
                row.row().put("isTied", frequencies.get(row.amount()) > 1);
            }
        }
        return ranks;
    }

    private static boolean activeAt(LocalDateTime from, LocalDateTime to, LocalDateTime at) {
        return from != null && !from.isAfter(at) && (to == null || at.isBefore(to));
    }

    private static BigDecimal observedCount(long count) { return count == 0 ? null : BigDecimal.valueOf(count); }

    private static Map<String, Object> boardCount(long value, boolean complete) {
        return fields("value", complete ? value : null, "coverage", complete ? "COMPLETE" : "UNKNOWN",
                "reason", complete ? "NONE" : "METRIC_INCOMPLETE");
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

    private Map<String, Object> exportReadiness(SupportPerformance.Snapshot snapshot) {
        String reason = cache.refreshFailed() ? "EXPORT_REFRESH_FAILED" : cache.stale(snapshot)
                ? "EXPORT_DATA_STALE" : null;
        return fields("state", reason == null ? "ELIGIBLE" : "BLOCKED", "reasonCodes", reason == null ? List.of() : List.of(reason));
    }

    private static boolean historicalScope(Event event, ReadScope scope, Set<Long> groups) {
        return historicalScope(event.attribution(), scope, groups);
    }

    private static boolean historicalScope(EventAttribution assignment, ReadScope scope, Set<Long> groups) {
        if (assignment.state() == AttributionState.UNKNOWN) {
            return scope.mode() == ReadMode.ALL && scope.requestedAgentId() == null && scope.requestedGroupId() == null;
        }
        if (scope.requestedAgentId() != null && !scope.requestedAgentId().equals(assignment.agentId())) return false;
        if (scope.requestedGroupId() != null && !scope.requestedGroupId().equals(assignment.groupId())) return false;
        return switch (scope.mode()) {
            case ALL -> true;
            case MANAGED -> assignment.groupId() != null && groups.contains(assignment.groupId());
            case PERSONAL -> scope.actorId().equals(assignment.agentId());
        };
    }

    private static Map<String, Object> measuredCounts(Map<String, Object> performance, SupportPerformance.Snapshot snapshot,
            Instant from, Instant to, List<Event> events, java.util.function.Predicate<EventAttribution> scope, boolean complete) {
        Map<String, Object> counts = new LinkedHashMap<>();
        SupportPerformanceMetrics.counts(snapshot, from, to, events, scope)
                .forEach((key, value) -> counts.put(key, (key.equals("firstPayment")
                        ? snapshot.months().stream().anyMatch(month -> month.firstHistoryComplete() && month.from().equals(from) && month.to().equals(to))
                        : complete)
                        ? fields("value", value, "observed", value, "status", "COMPLETE", "reasons", List.of())
                        : value > 0 ? count(value) : unknownCount()));
        performance.put("counts", counts);
        return performance;
    }

    private static List<Event> bucket(List<Event> events, AttributionState state) {
        return events.stream().filter(e -> e.attribution().state() == state).toList();
    }

    private static List<Map<String, Object>> groups(Object original, List<Event> events, Map<String, Object> window,
            SupportPerformance.Snapshot snapshot, List<Event> current, Map<String, Object> currentWindow,
            Map<Long, SupportAnalyticsMapper.CurrentCustomer> owners, boolean complete, boolean fullHistory, boolean period,
            ReadScope scope, Set<Long> grantedGroups, List<String> grants) {
        var byGroup = events.stream().filter(e -> e.attribution().state() == AttributionState.ASSIGNED
                        && e.attribution().groupId() != null)
                .collect(Collectors.groupingBy(e -> e.attribution().groupId()));
        var groups = new TreeMap<Long, Map<String, Object>>();
        maps(original).forEach(group -> groups.put(id(group.get("groupId")), group));
        if (period) byGroup.keySet().forEach(id -> groups.putIfAbsent(id, fields("groupId", id.toString())));
        groups.forEach((id, group) -> {
            var groupScope = new ReadScope(scope.actorId(), scope.mode(), id, scope.requestedAgentId());
            var currentRows = current.stream().filter(event -> id.equals(owners.get(event.customerId()).currentGroupId())).toList();
            var currentGroup = mapAt(group, "current");
            currentGroup.put("performance", privateAmounts(currentRows, withScopeVersion(currentWindow,
                    scopeVersion(snapshot, groupScope, grantedGroups, grants, Basis.CURRENT_CUSTOMER_HISTORY)), fullHistory));
            var periodGroup = mapAt(group, "period");
            if (period) {
                var rows = byGroup.getOrDefault(id, List.of());
                var groupWindow = withScopeVersion(window, scopeVersion(snapshot, groupScope, grantedGroups, grants, Basis.PERIOD_EVENT));
                periodGroup.put("performance", measuredCounts(privateAmounts(rows, groupWindow, complete), snapshot,
                        Instant.parse((String) window.get("from")), Instant.parse((String) window.get("to")),
                        rows, assignment -> assignment.state() == AttributionState.ASSIGNED && id.equals(assignment.groupId()), complete));
            }
            group.putAll(fields("current", currentGroup, "period", periodGroup));
        });
        return List.copyOf(groups.values());
    }

    private static String scopeVersion(Snapshot snapshot, ReadScope scope, Set<Long> groups, List<String> grants, Basis basis) {
        String effectiveGroups = scope.requestedGroupId() == null ? new TreeSet<>(groups).toString() : scope.requestedGroupId().toString();
        return version("", snapshot, scope.toString(), effectiveGroups, grants.toString(), basis.name());
    }

    private static Map<String, Object> withScopeVersion(Map<String, Object> source, String version) {
        var window = new LinkedHashMap<>(source);
        window.put("sourceScopeVersion", version);
        return window;
    }

    private static Map<String, Object> privateAmounts(List<Event> rows, Map<String, Object> window) {
        return privateAmounts(rows, window, false);
    }

    private static Map<String, Object> privateAmounts(List<Event> rows, Map<String, Object> window, boolean complete) {
        var totals = SupportPerformanceAggregation.subtotal(null, null, rows);
        Map<String, Object> inflows = new LinkedHashMap<>(), corrections = new LinkedHashMap<>(), withdrawals = new LinkedHashMap<>();
        for (String channel : List.of("CHAIN_TOPUP", "VN_BANK_TOPUP", "CHAIN_DIRECT_PURCHASE", "VN_BANK_DIRECT_PURCHASE")) {
            inflows.put(channel, amount(totals.inflows().get(channel), complete));
            corrections.put(channel, amount(totals.corrections().get(channel), complete));
        }
        for (String channel : List.of("TRC20", "BEP20", "ERC20", "VN_BANK")) {
            var debit = totals.withdrawals().get(channel);
            withdrawals.put(channel, fields("walletDebited", amount(debit == null ? null : debit.walletDebitedUsdt(), complete),
                    "fee", amount(debit == null ? null : debit.feeUsdt()), "netReceived", amount(debit == null ? null : debit.receivedUsdt())));
        }
        var applicable = "PERIOD_EVENT".equals(window.get("basis")) ? unknownCount() : notApplicable();
        var projection = fields("window", window, "currency", SupportPerformance.CURRENCY,
                "performanceAmount", amount(totals.inflows().isEmpty() && totals.withdrawals().isEmpty() ? null : totals.performanceUsdt(), complete),
                "inflowTotal", amount(total(totals.inflows().values()), complete),
                "withdrawalTotal", amount(total(totals.withdrawals().values().stream().map(WithdrawalSubtotal::walletDebitedUsdt).toList()), complete),
                "inflowByChannel", inflows, "withdrawalByChannel", withdrawals,
                "correctionTotal", amount(total(totals.corrections().values()), complete), "correctionByChannel", corrections,
                "vndReference", fields("bankTopupReceived", notApplicable(), "bankDirectReceived", notApplicable(),
                        "bankPayoutReceived", notApplicable(), "bankInflowCorrection", notApplicable()),
                "references", fields("devicePurchases", amount(total(rows.stream().filter(Event::devicePurchase).map(Event::amountUsdt).toList())),
                        "refunds", amount(total(rows.stream().filter(SupportPerformanceReadService::refund)
                                .map(Event::amountUsdt).toList())),
                        "manualCredits", amount(total(rows.stream().filter(event -> "MANUAL_CREDIT".equals(event.sourceStatus()))
                                .map(Event::amountUsdt).toList())),
                        "rewardsCredited", amount(total(rows.stream().filter(event -> "REWARD".equals(event.sourceStatus()))
                                .map(Event::amountUsdt).toList()))),
                "counts", fields("boundAtCutoff", applicable, "newlyBound", applicable, "withInflow", applicable,
                        "withWithdrawal", applicable, "firstPayment", applicable, "negativePerformance", applicable));
        if (window.get("sourceVersion") == null) markUpgrading(projection);
        return projection;
    }

    private static void markUpgrading(Map<String, Object> projection) {
        if ("UNKNOWN".equals(projection.get("status"))
                && !List.of("NOT_APPLICABLE").equals(projection.get("reasons"))) {
            projection.put("reasons", List.of("GENERATION_UPGRADING"));
            return;
        }
        projection.forEach((key, value) -> {
            if (!"window".equals(key) && value instanceof Map<?, ?> child) {
                @SuppressWarnings("unchecked")
                var typed = (Map<String, Object>) child;
                markUpgrading(typed);
            }
        });
    }

    private static boolean refund(Event event) {
        return "REFUND".equals(event.sourceStatus()) || "EXTERNAL_REFUND_CONFIRMED".equals(event.sourceStatus());
    }

    private static BigDecimal sumPerformance(List<Event> rows) {
        return total(rows.stream().filter(event -> event.kind() == Kind.INFLOW || event.kind() == Kind.CORRECTION
                || event.kind() == Kind.WITHDRAWAL).map(Event::deltaUsdt).toList());
    }

    private static Map<String, Object> eventRow(Event e, RootDisplayRow customer, Map<Long, String> agents, Map<Long, String> groups) {
        var amounts = e.missingAmounts(false);
        var reasons = e.factReasons();
        reasons.addAll(amounts.values());
        return fields("eventId", publicEventId(e.id()), "customerId", Long.toString(e.customerId()), "customerNo", customer.customerNo(),
                "maskedNickname", SupportPerformance.maskNickname(customer.nickname()),
                "attributionState", e.attribution().state().name(), "agentId", text(e.attribution().agentId()),
                "agentName", e.attribution().agentId() == null ? null : agents.get(e.attribution().agentId()), "groupId", text(e.attribution().groupId()),
                "groupName", e.attribution().groupId() == null ? null : groups.get(e.attribution().groupId()),
                "kind", e.kind() == Kind.WITHDRAWAL_REFERENCE ? "WITHDRAWAL"
                        : e.kind() == Kind.REFERENCE ? e.sourceStatus() : e.kind().name(), "channel", e.channel(),
                "reasonCodes", List.copyOf(reasons),
                "references", e.devicePurchase() ? List.of("DEVICE_PURCHASE") : refund(e) ? List.of("REFUND")
                        : e.kind() == Kind.REFERENCE ? List.of(e.sourceStatus()) : List.of(),
                "network", e.network(), "businessNo", e.businessNo(), "originalBusinessNo", e.originalBusinessNo(),
                "originalEventId", e.originalEventId() == null ? null : publicEventId(e.originalEventId()),
                "occurredAt", text(e.occurredAt()), "effectiveAt", e.effectiveAt().toString(), "sourceStatus", e.sourceStatus(),
                "status", e.performanceStatus(),
                "businessAmount", e.vndBusiness() ? null : text(e.amountUsdt()),
                "businessCurrency", e.vndBusiness() || e.amountUsdt() == null ? null : SupportPerformance.CURRENCY,
                "performanceDeltaUsdt", e.settlementUnknown() ? null : text(e.deltaUsdt()),
                "walletDebitUsdt", text(e.kind() == Kind.WITHDRAWAL ? e.amountUsdt() : null),
                "withdrawalFeeUsdt", text(e.feeUsdt()), "channelFeeUsdt", text(e.channelFeeUsdt()),
                "networkFeeUsdt", text(e.networkFeeUsdt()), "receivedUsdt", text(e.receivedUsdt()),
                "roundingAdjustmentUsdt", text(e.roundingUsdt()), "totalPaidUsdt", text(e.totalPaidUsdt()),
                "amountReasons", amounts, "vndAmount", null);
    }

    private static String publicEventId(String id) {
        return UUID.nameUUIDFromBytes((SupportPerformance.DEFINITION_VERSION + ":" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> details(List<Event> selected, List<Event> visible, Set<String> disclosed,
            Map<String, Object> window, boolean complete) {
        var restricted = selected.stream().filter(event -> !disclosed.contains(event.id())).toList();
        var visibleCustomers = visible.stream().map(Event::customerId).collect(Collectors.toSet());
        return fields("performance", privateAmounts(selected, window, complete),
                "visiblePerformance", privateAmounts(visible, window, complete),
                "restrictedPerformance", privateAmounts(restricted, window, complete),
                "totalCount", count(selected.size(), complete), "visibleCount", count(visible.size(), complete),
                "restrictedCount", count(restricted.size(), complete),
                "customerCount", count(selected.stream().map(Event::customerId).distinct().count(), complete),
                "visibleCustomerCount", count(visible.stream().map(Event::customerId).distinct().count(), complete),
                "restrictedCustomerCount", count(restricted.stream().map(Event::customerId)
                        .filter(id -> !visibleCustomers.contains(id)).distinct().count(), complete));
    }

    private static Map<String, Object> count(long observed, boolean complete) {
        return complete ? fields("value", observed, "observed", observed, "status", "COMPLETE", "reasons", List.of())
                : observed > 0 ? count(observed) : unknownCount();
    }

    private static Map<String, Object> count(long observed) {
        return fields("value", null, "observed", observed, "status", "PARTIAL", "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static Map<String, Object> amount(BigDecimal value) {
        return amount(value, false);
    }

    private static Map<String, Object> amount(BigDecimal value, boolean complete) {
        if (complete) {
            var verified = text(value == null ? BigDecimal.ZERO : value);
            return fields("value", verified, "observed", verified, "status", "COMPLETE", "reasons", List.of());
        }
        return fields("value", null, "observed", text(value), "status", value == null ? "UNKNOWN" : "PARTIAL",
                "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static Map<String, Object> unknownCount() {
        return fields("value", null, "observed", null, "status", "UNKNOWN", "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static Map<String, Object> notApplicable() {
        return fields("value", null, "observed", null, "status", "UNKNOWN", "reasons", List.of("NOT_APPLICABLE"));
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

    private static Map<String, Object> copyFields(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put((String) key, value));
        return copy;
    }

    private static Map<String, Object> mapAt(Map<String, Object> source, String key) {
        return source.get(key) instanceof Map<?, ?> map ? copyFields(map) : new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> copyFields((Map<?, ?>) item)).toList();
    }

    private static Long id(Object value) { return value == null ? null : Long.valueOf(value.toString()); }

    private record Ranked(long id, String name, BigDecimal amount, Map<String, Object> row) { }
}
