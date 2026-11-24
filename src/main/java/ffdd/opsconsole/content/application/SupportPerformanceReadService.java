package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Basis;
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
        if (eventsView && (!period || query.currency() == null || query.keyword() != null
                || !Set.of(SupportAnalyticsQueryRequest.Sort.SUCCEEDED_AT, SupportAnalyticsQueryRequest.Sort.AMOUNT).contains(query.sortKey()))) {
            throw new BizException(422, "SUPPORT_ANALYTICS_DETAIL_QUERY_INVALID");
        }
        if (amountSort && (!SupportPerformance.CURRENCY.equals(query.currency())
                || query.basis() != Basis.CURRENT_CUSTOMER_HISTORY || query.view() != View.CUSTOMERS)) {
            throw new BizException(422, "SUPPORT_ANALYTICS_SORT_INVALID");
        }
        if (query.basis() == Basis.CURRENT_ASSET) {
            var independent = new LinkedHashMap<>(baseline.query(parameters));
            independent.putAll(fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation",
                    published().isPresent() ? "READY" : "UPGRADING", "performanceWindow", null, "firstPaymentCoverage", null));
            return independent;
        }
        ReadScope scope = ownership.defaultQueryScope(query.groupId(), query.agentId());
        var grants = grants(scope.actorId());
        var groups = Set.copyOf(analytics.currentGrantedGroupIds(scope));
        var current = published();
        if (current.isEmpty()) {
            if (query.expectedVersion() != null) throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
            return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "UPGRADING",
                    "view", eventsView ? "PERFORMANCE_EVENTS" : query.view().name());
        }

        var snapshot = current.get();
        Map<String, Object> original = eventsView || firstView || amountSort ? Map.of() : baseline.performanceBaseline(parameters);
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
        List<Event> selected = period ? inWindow.stream().filter(e -> historicalScope(e, scope, groups)).toList() : currentHistory;
        String scopeVersion = version("", snapshot, scope.toString(), new TreeSet<>(groups).toString(), grants.toString(),
                visibleCustomers.keySet().toString(), query.basis().name());
        String version = version("saq-v1:", snapshot, scopeVersion, from.toString(), to.toString(),
                query.view().name(), query.sortKey().name(), query.direction().name(),
                query.firstState().name(), query.category().name(), query.filter().name(), String.valueOf(query.currency()),
                String.valueOf(query.keyword()), Boolean.toString(eventsView), Boolean.toString(amountSort),
                String.valueOf(original.get("queryVersion")));
        requireVersion(query.expectedVersion(), version, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        Map<String, Object> window = fields("definitionVersion", SupportPerformance.DEFINITION_VERSION,
                "sourceVersion", snapshot.sourceVersion(), "sourceScopeVersion", scopeVersion, "month", month, "basis", query.basis().name(),
                "from", from.toString(), "to", to.toString(), "asOf", snapshot.asOf().toString(), "businessZone", zone);
        boolean complete = !months.isEmpty() && months.stream().allMatch(entry -> entry.coverage() == Coverage.COMPLETE);
        var currentWindow = new LinkedHashMap<String, Object>(window);
        currentWindow.putAll(fields("sourceScopeVersion", version("", snapshot, scope.toString(), new TreeSet<>(groups).toString(),
                        grants.toString(), visibleCustomers.keySet().toString(), Basis.CURRENT_CUSTOMER_HISTORY.name()),
                "month", null, "basis", Basis.CURRENT_CUSTOMER_HISTORY.name(), "from", snapshot.historyStart().toString(),
                "to", snapshot.asOf().toString(), "businessZone", BusinessCalendar.legacy().zoneAt(snapshot.asOf()).getId()));
        var performance = period ? measuredCounts(privateAmounts(selected, window, complete), snapshot, from, to, selected,
                assignment -> historicalScope(assignment, scope, groups), complete) : privateAmounts(selected, window, complete);
        boolean fullHistory = !snapshot.months().isEmpty() && snapshot.months().stream().allMatch(entry -> entry.coverage() == Coverage.COMPLETE);
        var cumulative = privateAmounts(currentHistory, currentWindow, fullHistory);
        var summary = mapAt(original, "scopeSummary");
        var financial = mapAt(summary, "financial");
        financial.put("performance", period ? performance : null);
        var currentSummary = mapAt(summary, "current");
        currentSummary.put("performance", cumulative);
        summary.putAll(fields("financial", financial, "current", currentSummary,
                "groups", groups(summary.get("groups"), selected, window, snapshot, currentHistory, currentWindow,
                        currentOwners, complete, fullHistory, period)));
        if (scope.mode() == ReadMode.ALL && query.view() == View.OVERVIEW && query.groupId() == null && query.agentId() == null && period) {
            summary.put("performanceAttribution", fields("unassigned", privateAmounts(bucket(selected, AttributionState.UNASSIGNED), window),
                    "unknown", privateAmounts(bucket(selected, AttributionState.UNKNOWN), window)));
        }
        var disclosed = selected.stream().filter(e -> visibleCustomers.containsKey(e.customerId())).toList();
        List<Map<String, Object>> records;
        if (eventsView) {
            Comparator<Event> order = query.sortKey() == SupportAnalyticsQueryRequest.Sort.AMOUNT
                    ? Comparator.comparing(Event::deltaUsdt) : Comparator.comparing(Event::effectiveAt);
            if (query.direction() == SupportAnalyticsQueryRequest.Direction.DESC) order = order.reversed();
            var agentNames = names.byIds(disclosed.stream().map(e -> e.attribution().agentId()).filter(Objects::nonNull).distinct().toList());
            var groupNames = leaderboard.groups().stream().filter(g -> g.name() != null)
                    .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name));
            records = disclosed.stream().sorted(order.thenComparing(Event::id))
                    .map(e -> eventRow(e, visibleCustomers.get(e.customerId()), agentNames, groupNames)).toList();
        } else if (firstView) {
            var kind = query.view() == View.FIRST_DEPOSITS ? "DEPOSIT" : "DEVICE_PURCHASE";
            Comparator<FirstPayment> order = query.sortKey() == SupportAnalyticsQueryRequest.Sort.AMOUNT
                    ? Comparator.comparing(FirstPayment::amount) : Comparator.comparing(FirstPayment::at);
            if (query.direction() == SupportAnalyticsQueryRequest.Direction.DESC) order = order.reversed();
            records = snapshot.firstPayments().stream().filter(first -> first.confirmed() && kind.equals(first.kind())
                            && (query.currency() == null || query.currency().equals(first.currency()))
                            && visibleCustomers.containsKey(first.customerId())
                            && (period ? SupportPerformance.contains(from, to, first.at())
                                && historicalScope(first.attribution(), scope, groups) : true)
                            && query.firstState() != SupportAnalyticsQueryRequest.First.NONE
                            && query.firstState() != SupportAnalyticsQueryRequest.First.UNKNOWN
                            && matches(query.keyword(), visibleCustomers.get(first.customerId())))
                    .sorted(order.thenComparing(FirstPayment::factId))
                    .map(first -> fields("customerId", Long.toString(first.customerId()),
                            "customerNo", visibleCustomers.get(first.customerId()).customerNo(),
                            "kind", first.kind(), "currency", first.currency(), "amount", text(first.amount()),
                            "succeededAt", first.at().toString(), "orderNo", first.orderNo(), "channel", first.channel()))
                    .toList();
        } else if (!original.isEmpty()) {
            records = maps(original.get("records")).stream().map(row -> {
                Long customerId = id(row.get("customerId"));
                Long agentId = id(row.get("agentId"));
                if (customerId != null) row.put("performance", privateAmounts(currentHistory.stream()
                        .filter(event -> event.customerId() == customerId).toList(), currentWindow, fullHistory));
                if (agentId != null && query.view() == View.AGENTS) {
                    var selectedAgent = selected.stream().filter(event -> agentId.equals(event.attribution().agentId())).toList();
                    var currentAgent = currentHistory.stream().filter(event -> agentId.equals(currentOwners.get(event.customerId()).ownerAgentId())).toList();
                    var currentAgentRow = mapAt(row, "current");
                    var periodAgentRow = mapAt(row, "period");
                    currentAgentRow.put("performance", privateAmounts(currentAgent, currentWindow, fullHistory));
                    if (period) periodAgentRow.put("performance", measuredCounts(privateAmounts(selectedAgent, window, complete),
                            snapshot, from, to, selectedAgent, a -> agentId.equals(a.agentId()) && historicalScope(a, scope, groups), complete));
                    row.putAll(fields("current", currentAgentRow, "period", periodAgentRow));
                }
                return row;
            }).toList();
        } else {
            records = records(query, amountSort, query.view() == View.AGENTS
                    ? selected.stream().filter(e -> historicalScope(e, scope, groups)).toList() : disclosed,
                    currentHistory, visibleCustomers, window, currentWindow);
        }
        var page = original.isEmpty() ? page(records, query.pageNum(), query.pageSize()) : records;
        var disclosedIds = disclosed.stream().map(Event::id).collect(Collectors.toSet());

        if (!scope.equals(ownership.defaultQueryScope(query.groupId(), query.agentId()))
                || !groups.equals(Set.copyOf(analytics.currentGrantedGroupIds(scope))) || !grants.equals(grants(scope.actorId()))) {
            throw new BizException(409, "SUPPORT_ANALYTICS_QUERY_CHANGED");
        }
        var selectedCurrent = mapAt(original, "selectedCurrent");
        selectedCurrent.put("performance", cumulative);
        return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "READY",
                "view", eventsView ? "PERFORMANCE_EVENTS" : query.view().name(), "basis", query.basis().name(),
                "asOf", snapshot.asOf().toString(), "businessZone", SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId(),
                "queryVersion", version, "sourceVersion", snapshot.sourceVersion(), "performanceWindow", window,
                "versionState", "READY", "recordsStatus", original.getOrDefault("recordsStatus", complete ? "AVAILABLE" : "PARTIAL"),
                "total", original.getOrDefault("total", complete ? (long) records.size() : null),
                "observedTotal", original.getOrDefault("observedTotal", (long) records.size()),
                "pageNum", query.pageNum(), "pageSize", query.pageSize(), "canContinue", original.getOrDefault("canContinue",
                query.pageNum() * query.pageSize() < records.size()), "records", page, "scopeSummary", summary,
                "selectedCustomerCount", original.getOrDefault("selectedCustomerCount", (long) visibleCustomers.size()),
                "selectedCurrent", selectedCurrent,
                "firstPaymentCoverage", period && months.get(0).firstHistoryComplete() ? completeFirst() : firstCoverage(snapshot.firstPayments().stream().anyMatch(first -> first.confirmed()
                        && visibleCustomers.containsKey(first.customerId()) && (!period
                        || historicalScope(first.attribution(), scope, groups)))), "funds", original.get("funds"),
                "detailSummary", eventsView ? details(selected, disclosed, disclosedIds, window)
                        : firstView ? fields("visibleCount", count(records.size())) : null,
                "exportReadiness", exportReadiness(snapshot, selected.isEmpty()));
    }

    static Map<String, Object> firstCoverage(boolean confirmed) {
        return fields("status", confirmed ? "PARTIAL" : "UNKNOWN", "reasonCodes", List.of("HISTORY_INCOMPLETE"));
    }

    private static Map<String, Object> completeFirst() {
        return fields("status", "COMPLETE", "reasonCodes", List.of());
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
        var projection = privateAmounts(events, window);
        var first = current.flatMap(snapshot -> snapshot.firstPayments().stream()
                .filter(candidate -> candidate.customerId() == customer && candidate.confirmed()).findFirst());
        if (body.get("profile") instanceof Map<?, ?> legacyProfile) {
            var profile = copyFields(legacyProfile);
            for (String key : List.of("statistics", "finance")) {
                if (!(profile.get(key) instanceof Map<?, ?> group) || !"READY".equals(group.get("status"))
                        || !(group.get("data") instanceof Map<?, ?> data)) continue;
                var view = copyFields(data);
                view.put("performance", projection);
                if ("statistics".equals(key)) {
                    view.put("firstPaymentCoverage", firstCoverage(first.isPresent()));
                    view.put("first", first.<Map<String, Object>>map(payment -> fields("state", "CONFIRMED", "status", "READY",
                            "kind", payment.kind(), "currency", payment.currency(), "amount", text(payment.amount()),
                            "succeededAt", payment.at().toString(), "channel", payment.channel())).orElseGet(() ->
                            fields("state", "UNKNOWN", "status", "UNKNOWN", "kind", null, "currency", null, "amount", null, "succeededAt", null)));
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
        var current = published();
        if (current.isEmpty()) {
            if (query.expectedVersion() != null) throw new BizException(409, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
            return fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "UPGRADING", "board", query.board().name());
        }
        var snapshot = current.get();
        var month = snapshot.months().stream().filter(m -> m.month().equals(query.referenceMonth().toString())).findFirst()
                .orElseThrow(() -> new BizException(422, "SUPPORT_LEADERBOARD_MONTH_UNAVAILABLE"));
        var cutoff = LocalDateTime.ofInstant(month.to().minusNanos(1_000), ZoneOffset.UTC);
        var memberships = leaderboard.memberships().stream()
                .filter(member -> activeAt(member.startsAt(), member.endsAt(), cutoff))
                .collect(Collectors.groupingBy(SupportLeaderboardMapper.MemberInterval::agentId, TreeMap::new, Collectors.toList()));
        var accounts = leaderboard.accounts().stream().filter(account -> Integer.valueOf(0).equals(account.isDeleted())
                && Integer.valueOf(1).equals(account.compatibleRole())).toList();
        var groupNames = leaderboard.groups().stream().filter(group -> group.name() != null)
                .collect(Collectors.toMap(SupportLeaderboardMapper.Group::id, SupportLeaderboardMapper.Group::name,
                        (a, b) -> a, TreeMap::new));
        var qualifications = leaderboard.qualifications();
        var qualified = qualifications.stream().filter(q -> activeAt(q.startsAt(), q.endsAt(), cutoff))
                .collect(Collectors.groupingBy(SupportLeaderboardMapper.QualificationInterval::agentId, TreeMap::new, Collectors.toList()));
        var monthAgents = qualifications.stream().filter(q -> "ENABLED".equals(q.state())
                && q.startsAt().toInstant(ZoneOffset.UTC).isBefore(month.to())
                && (q.endsAt() == null || q.endsAt().toInstant(ZoneOffset.UTC).isAfter(month.from())))
                .map(SupportLeaderboardMapper.QualificationInterval::agentId).collect(Collectors.toSet());
        var currentBindings = leaderboard.currentBindings().stream().filter(binding ->
                activeAt(binding.startsAt(), binding.endsAt(), cutoff)).toList();
        var boundByAgent = currentBindings.stream().collect(Collectors.groupingBy(
                SupportLeaderboardMapper.Binding::agentId, TreeMap::new, Collectors.mapping(
                        SupportLeaderboardMapper.Binding::customerId, Collectors.toCollection(TreeSet::new))));
        var duplicated = currentBindings.stream().map(SupportLeaderboardMapper.Binding::customerId).distinct().count() != currentBindings.size();
        var accountIds = accounts.stream().map(SupportLeaderboardMapper.Account::id).collect(Collectors.toSet());
        boolean candidatesComplete = leaderboard.qualificationCutoverUtc() != null
                && memberships.values().stream().allMatch(rows -> rows.size() == 1)
                && qualified.values().stream().allMatch(rows -> rows.size() == 1)
                && accountIds.containsAll(boundByAgent.keySet()) && !duplicated;
        boolean amountComplete = month.coverage() == Coverage.COMPLETE;
        boolean complete = candidatesComplete && (query.board() == Board.customers
                || query.board() == Board.firstPayment && month.firstHistoryComplete()
                || query.board() == Board.netContribution && amountComplete);
        String version = version("slb-v1:", snapshot, SupportPerformance.DEFINITION_VERSION, query.board().name(),
                query.currency(), query.scope().name(), new TreeSet<>(groups).toString(), month.month(),
                accounts.toString(), memberships.toString(), qualifications.toString(), groupNames.toString(), boundByAgent.toString());
        requireVersion(query.expectedVersion(), version, "SUPPORT_LEADERBOARD_VERSION_CHANGED");
        var eligible = snapshot.monthlySubtotals().stream().filter(row -> month.month().equals(row.month())
                && row.attribution().agentId() != null && (groups.isEmpty()
                || row.attribution().groupId() != null && groups.contains(row.attribution().groupId()))).toList();
        var byAgent = eligible.stream().collect(Collectors.groupingBy(row -> row.attribution().agentId()));
        var observed = accounts.stream().filter(account -> (query.board() == Board.customers
                        ? boundByAgent.containsKey(account.id()) || qualified.getOrDefault(account.id(), List.of()).stream()
                                .anyMatch(q -> "ENABLED".equals(q.state()))
                        : byAgent.containsKey(account.id()) || monthAgents.contains(account.id())) && (groups.isEmpty()
                        || memberships.getOrDefault(account.id(), List.of()).stream()
                                .anyMatch(member -> member.groupId() != null && groups.contains(member.groupId()))
                        || query.board() != Board.customers && byAgent.containsKey(account.id())))
                .map(account -> {
            long id = account.id();
            var parts = byAgent.getOrDefault(id, List.of());
            BigDecimal inflows = total(parts.stream().flatMap(row -> row.inflows().values().stream()).toList());
            BigDecimal debits = total(parts.stream().flatMap(row -> row.withdrawals().values().stream())
                    .map(WithdrawalSubtotal::walletDebitedUsdt).toList());
            BigDecimal value = inflows == null && debits == null ? null : parts.stream()
                    .map(MonthlySubtotal::performanceUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);
            java.util.function.Predicate<EventAttribution> agentScope = assignment -> Long.valueOf(id).equals(assignment.agentId())
                    && (groups.isEmpty() || assignment.groupId() != null && groups.contains(assignment.groupId()));
            var counts = SupportPerformanceMetrics.counts(snapshot, month.from(), month.to(), List.of(), agentScope);
            var membership = memberships.getOrDefault(id, List.of());
            String group = membership.size() == 1 && membership.get(0).groupId() != null
                    ? groupNames.getOrDefault(membership.get(0).groupId(), "") : "";
            var qualificationRows = qualified.getOrDefault(id, List.of());
            String qualification = qualificationRows.size() != 1 ? "UNKNOWN" : switch (qualificationRows.get(0).state()) {
                case "ENABLED" -> Integer.valueOf(1).equals(account.status()) ? "ACTIVE" : "DISABLED";
                case "REMOVED" -> "REMOVED";
                default -> "DISABLED";
            };
            var name = account.nickname() == null || account.nickname().isBlank() ? Long.toString(id) : account.nickname();
            long first = counts.get("firstPayment");
            long customers = boundByAgent.getOrDefault(id, new TreeSet<>()).size();
            boolean currentCountsComplete = !duplicated && query.referenceMonth().equals(BusinessCalendar.legacy().month(snapshot.asOf()));
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
                    "movement", fields("kind", "UNAVAILABLE", "places", null, "previousRank", null,
                            "baselineAt", null, "baselineVersion", null, "reason", "DEFINITION_CHANGED"),
                    "canViewCustomers", ownership.canReadAgent(actor, id)));
        }).sorted(Comparator.comparing(Ranked::amount, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingLong(Ranked::id)).toList();
        if (complete) rank(observed, snapshot, month, query, groups, version);
        var matches = observed.stream().filter(r -> query.keyword() == null
                || r.name().toLowerCase(Locale.ROOT).contains(query.keyword().toLowerCase(Locale.ROOT))).toList();
        Map<String, Object> body = fields("metricVersion", SupportPerformance.DEFINITION_VERSION, "generation", "READY",
                "viewVersion", version, "queryVersion", version, "sourceVersion", snapshot.sourceVersion(),
                "definitionVersion", SupportPerformance.DEFINITION_VERSION, "board", query.board().name(),
                "rankMonth", query.rankMonth() == null ? null : month.month(), "referenceMonth", month.month(), "scope", query.scope().name(),
                "currency", SupportPerformance.CURRENCY, "businessZone", month.businessZone(), "asOf", snapshot.asOf().toString(),
                "publishedAt", snapshot.asOf().toString(), "state", complete ? "COMPLETE" : "PROVISIONAL",
                "candidateCoverage", candidatesComplete ? "COMPLETE" : observed.isEmpty() ? "UNKNOWN" : "PARTIAL",
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

    private void rank(List<Ranked> rows, Snapshot snapshot, Month month, SupportLeaderboardQuery.Normalized query,
                      Set<Long> groups, String version) {
        Map<BigDecimal, Long> frequencies = rows.stream().collect(Collectors.groupingBy(
                Ranked::amount, TreeMap::new, Collectors.counting()));
        var ranks = new TreeMap<Long, Integer>();
        BigDecimal previous = null;
        int rank = 0;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (previous == null || previous.compareTo(row.amount()) != 0) rank = index + 1;
            previous = row.amount();
            ranks.put(row.id(), rank);
            row.row().put("rank", rank);
            row.row().put("isTied", frequencies.get(row.amount()) > 1);
        }
        var day = BusinessCalendar.legacy().dateAt(snapshot.asOf());
        boolean historical = !YearMonth.from(day).toString().equals(month.month());
        var prior = historical ? null : cache.compareAndSaveRanks(SupportPerformance.digest(
                query.board() + "|" + query.scope() + "|" + new TreeSet<>(groups) + "|"
                    + (query.board() == Board.customers ? "" : month.month())), day, snapshot.asOf(), version, ranks);
        String unavailable = historical ? "HISTORICAL_MONTH" : prior == null ? "NO_BASELINE"
                : !prior.ranks().keySet().equals(ranks.keySet()) ? "MEMBERS_CHANGED" : null;
        for (var row : rows) {
            Integer priorRank = unavailable == null ? prior.ranks().get(row.id()) : null;
            int change = priorRank == null ? 0 : priorRank - ranks.get(row.id());
            row.row().put("movement", fields("kind", unavailable != null ? "UNAVAILABLE" : change > 0 ? "UP" : change < 0 ? "DOWN" : "SAME",
                    "places", unavailable == null ? Math.abs(change) : null, "previousRank", priorRank,
                    "baselineAt", unavailable == null ? prior.asOf() : null,
                    "baselineVersion", unavailable == null ? prior.version() : null,
                    "reason", unavailable == null ? "NONE" : unavailable));
        }
    }

    private static boolean activeAt(LocalDateTime from, LocalDateTime to, LocalDateTime at) {
        return from != null && !from.isAfter(at) && (to == null || at.isBefore(to));
    }

    private static BigDecimal observedCount(long count) { return count == 0 ? null : BigDecimal.valueOf(count); }

    private static Map<String, Object> boardCount(long value, boolean complete) {
        return fields("value", complete ? value : null, "coverage", complete ? "COMPLETE" : value == 0 ? "UNKNOWN" : "PARTIAL",
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

    private Map<String, Object> exportReadiness(SupportPerformance.Snapshot snapshot, boolean empty) {
        String reason = cache.refreshFailed() ? "EXPORT_REFRESH_FAILED" : cache.stale(snapshot)
                ? "EXPORT_DATA_STALE" : empty ? "EXPORT_ROWS_UNVERIFIED" : null;
        return fields("state", reason == null ? "ELIGIBLE" : "BLOCKED", "reasonCodes", reason == null ? List.of() : List.of(reason));
    }

    private static boolean historicalScope(Event event, ReadScope scope, Set<Long> groups) {
        return historicalScope(event.attribution(), scope, groups);
    }

    private static boolean historicalScope(EventAttribution assignment, ReadScope scope, Set<Long> groups) {
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
                        ? fields("value", value, "observed", null, "status", "COMPLETE", "reasons", List.of())
                        : value > 0 ? count(value) : unknownCount()));
        performance.put("counts", counts);
        return performance;
    }

    private static List<Event> bucket(List<Event> events, AttributionState state) {
        return events.stream().filter(e -> e.attribution().state() == state).toList();
    }

    private static List<Map<String, Object>> groups(Object original, List<Event> events, Map<String, Object> window,
            SupportPerformance.Snapshot snapshot, List<Event> current, Map<String, Object> currentWindow,
            Map<Long, SupportAnalyticsMapper.CurrentCustomer> owners, boolean complete, boolean fullHistory, boolean period) {
        var byGroup = events.stream().filter(e -> e.attribution().groupId() != null)
                .collect(Collectors.groupingBy(e -> e.attribution().groupId()));
        var groups = new TreeMap<Long, Map<String, Object>>();
        maps(original).forEach(group -> groups.put(id(group.get("groupId")), group));
        if (period) byGroup.keySet().forEach(id -> groups.putIfAbsent(id, fields("groupId", id.toString())));
        groups.forEach((id, group) -> {
            var currentRows = current.stream().filter(event -> id.equals(owners.get(event.customerId()).currentGroupId())).toList();
            var currentGroup = mapAt(group, "current");
            currentGroup.put("performance", privateAmounts(currentRows, currentWindow, fullHistory));
            var periodGroup = mapAt(group, "period");
            if (period) {
                var rows = byGroup.getOrDefault(id, List.of());
                periodGroup.put("performance", measuredCounts(privateAmounts(rows, window, complete), snapshot,
                        Instant.parse((String) window.get("from")), Instant.parse((String) window.get("to")),
                        rows, assignment -> id.equals(assignment.groupId()), complete));
            }
            group.putAll(fields("current", currentGroup, "period", periodGroup));
        });
        return List.copyOf(groups.values());
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
        return fields("window", window, "currency", SupportPerformance.CURRENCY,
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
    }

    private static boolean refund(Event event) {
        return "REFUND".equals(event.sourceStatus()) || "EXTERNAL_REFUND_CONFIRMED".equals(event.sourceStatus());
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
                        "performance", privateAmounts(customerHistory.getOrDefault(entry.getKey(), List.of()), currentWindow), "current", fields("performance",
                                privateAmounts(customerHistory.getOrDefault(entry.getKey(), List.of()), currentWindow)))).toList();
    }

    private static boolean matches(String keyword, RootDisplayRow row) {
        return keyword == null || matches(keyword, row.customerNo()) || matches(keyword, row.nickname());
    }

    private static boolean matches(String keyword, String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> eventRow(Event e, RootDisplayRow customer, Map<Long, String> agents, Map<Long, String> groups) {
        var reasons = new LinkedHashMap<String, String>();
        if (e.amountUsdt() == null) reasons.put("businessAmount", "AMOUNT_UNVERIFIED");
        boolean settlementUnknown = e.kind() == Kind.WITHDRAWAL_REFERENCE && "CONFIRMED".equals(e.sourceStatus());
        if (settlementUnknown) reasons.put("performanceDeltaUsdt", "WITHDRAWAL_SETTLEMENT_UNVERIFIED");
        boolean vndBusiness = "VN_BANK_DIRECT_PURCHASE".equals(e.channel()) || "VN_BANK".equals(e.channel());
        return fields("eventId", publicEventId(e.id()), "customerId", Long.toString(e.customerId()), "customerNo", customer.customerNo(),
                "maskedNickname", SupportPerformance.maskNickname(customer.nickname()),
                "attributionState", e.attribution().state().name(), "agentId", text(e.attribution().agentId()),
                "agentName", e.attribution().agentId() == null ? null : agents.get(e.attribution().agentId()), "groupId", text(e.attribution().groupId()),
                "groupName", e.attribution().groupId() == null ? null : groups.get(e.attribution().groupId()),
                "kind", e.kind() == Kind.WITHDRAWAL_REFERENCE ? "WITHDRAWAL"
                        : e.kind() == Kind.REFERENCE ? e.sourceStatus() : e.kind().name(), "channel", e.channel(),
                "reasonCodes", reasons.values().stream().distinct().toList(),
                "references", e.devicePurchase() ? List.of("DEVICE_PURCHASE") : refund(e) ? List.of("REFUND")
                        : e.kind() == Kind.REFERENCE ? List.of(e.sourceStatus()) : List.of(),
                "network", e.network(), "businessNo", e.businessNo(), "originalBusinessNo", e.originalBusinessNo(),
                "originalEventId", e.originalEventId() == null ? null : publicEventId(e.originalEventId()),
                "occurredAt", text(e.occurredAt()), "effectiveAt", e.effectiveAt().toString(), "sourceStatus", e.sourceStatus(),
                "status", e.performanceStatus(),
                "businessAmount", vndBusiness ? null : text(e.amountUsdt()),
                "businessCurrency", vndBusiness ? null : SupportPerformance.CURRENCY,
                "performanceDeltaUsdt", settlementUnknown ? null : text(e.deltaUsdt()),
                "walletDebitUsdt", text(e.kind() == Kind.WITHDRAWAL ? e.amountUsdt() : null),
                "withdrawalFeeUsdt", text(e.feeUsdt()), "channelFeeUsdt", text(e.channelFeeUsdt()),
                "networkFeeUsdt", text(e.networkFeeUsdt()), "receivedUsdt", text(e.receivedUsdt()),
                "roundingAdjustmentUsdt", text(e.roundingUsdt()), "totalPaidUsdt", text(e.totalPaidUsdt()),
                "amountReasons", reasons, "vndAmount", null);
    }

    private static String publicEventId(String id) {
        return UUID.nameUUIDFromBytes((SupportPerformance.DEFINITION_VERSION + ":" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> details(List<Event> selected, List<Event> visible, Set<String> disclosed,
            Map<String, Object> window) {
        var restricted = selected.stream().filter(event -> !disclosed.contains(event.id())).toList();
        var visibleCustomers = visible.stream().map(Event::customerId).collect(Collectors.toSet());
        return fields("performance", privateAmounts(selected, window), "visiblePerformance", privateAmounts(visible, window),
                "restrictedPerformance", privateAmounts(restricted, window), "totalCount", count(selected.size()),
                "visibleCount", count(visible.size()), "restrictedCount", count(restricted.size()),
                "customerCount", count(selected.stream().map(Event::customerId).distinct().count()),
                "visibleCustomerCount", count(visible.stream().map(Event::customerId).distinct().count()),
                "restrictedCustomerCount", count(restricted.stream().map(Event::customerId)
                        .filter(id -> !visibleCustomers.contains(id)).distinct().count()));
    }

    private static Map<String, Object> count(long observed) {
        return fields("value", null, "observed", observed, "status", "PARTIAL", "reasons", List.of("SOURCE_INCOMPLETE"));
    }

    private static Map<String, Object> amount(BigDecimal value) {
        return amount(value, false);
    }

    private static Map<String, Object> amount(BigDecimal value, boolean complete) {
        if (complete) return fields("value", text(value == null ? BigDecimal.ZERO : value),
                "observed", null, "status", "COMPLETE", "reasons", List.of());
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
