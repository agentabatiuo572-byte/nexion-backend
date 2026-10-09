package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Basis;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.Query;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Raw HTTP query strings. Authentication, scope resolution and unknown-parameter rejection stay at the boundary. */
public record SupportAnalyticsQueryRequest(String view, String category, String firstState, String filter,
        String keyword, String basis, String from, String to, String businessZone, String groupId,
        String agentId, String currency, String sortKey, String direction, String pageNum,
        String pageSize, String expectedVersion) {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    public enum View { OVERVIEW, AGENTS, CUSTOMERS, FINANCE, DEVICES, ACTIVITY }
    public enum Category { ALL, BOUND, PENDING, ANOMALY }
    public enum First { ALL, CONFIRMED, NONE, UNKNOWN }
    public enum ServiceFilter { ALL, WINDOW_ACTIVE, ACTIVE, DORMANT, UNKNOWN, DUE, WAITING_REPLY, FIRST_CONTACT, STOPPED, TODO }
    public enum Sort { REGISTERED_AT, ASSIGNED_AT, LAST_ACTIVE_AT, FIRST_SUCCEEDED_AT,
        DIRECT_INVITATION_COUNT, TEAM_CUSTOMER_COUNT, PERSONAL_DEPOSIT, TEAM_DEPOSIT,
        BOUND_CUSTOMER_COUNT, ACTIVE_CUSTOMER_COUNT, FIRST_CONFIRMED_CUSTOMER_COUNT, DEVICE_COUNT, HASHRATE,
        NEXT_DUE_AT, WAITING_SINCE_AT, STATE_CHANGED_AT, POOL_ENTERED_AT, SUCCEEDED_AT, AMOUNT }
    public enum Direction { ASC, DESC }

    public static final Set<String> PARAMETERS = Set.of("view", "category", "firstState", "filter", "keyword",
        "basis", "from", "to", "businessZone", "groupId", "agentId", "currency", "sortKey", "direction",
        "pageNum", "pageSize", "expectedVersion");

    /** Use the raw GET parameter multimap so duplicate, unit and client scope/role parameters cannot be ignored. */
    public static SupportAnalyticsQueryRequest fromParameters(Map<String, List<String>> parameters) {
        Objects.requireNonNull(parameters);
        for (var entry : parameters.entrySet()) {
            if (!PARAMETERS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().size() != 1
                    || entry.getValue().get(0) == null) invalid("SUPPORT_ANALYTICS_QUERY_INVALID");
        }
        return new SupportAnalyticsQueryRequest(raw(parameters,"view"), raw(parameters,"category"), raw(parameters,"firstState"),
            raw(parameters,"filter"), raw(parameters,"keyword"), raw(parameters,"basis"), raw(parameters,"from"), raw(parameters,"to"),
            raw(parameters,"businessZone"), raw(parameters,"groupId"), raw(parameters,"agentId"), raw(parameters,"currency"),
            raw(parameters,"sortKey"), raw(parameters,"direction"), raw(parameters,"pageNum"), raw(parameters,"pageSize"), raw(parameters,"expectedVersion"));
    }

    public Normalized normalize(Set<String> serverAllowedCurrencies, Set<Sort> serverSupportedSorts) {
        if (serverAllowedCurrencies == null || serverSupportedSorts == null) throw new IllegalArgumentException("Server query policy required");
        View v = enumeration(view, View.class, View.CUSTOMERS);
        Category c = enumeration(category, Category.class, Category.ALL);
        First f = enumeration(firstState, First.class, First.ALL);
        ServiceFilter service = enumeration(filter, ServiceFilter.class, ServiceFilter.ALL);
        Basis b = enumeration(basis, Basis.class, Basis.CURRENT_CUSTOMER_HISTORY);
        if (businessZone != null && !BUSINESS_ZONE.getId().equals(businessZone)) invalid("SUPPORT_ANALYTICS_QUERY_INVALID");
        if (keyword != null && keyword.length() > 200) invalid("SUPPORT_KEYWORD_TOO_LONG");
        String k = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Long g = id(groupId), a = id(agentId);
        if (currency != null && (!currency.matches("[A-Z][A-Z0-9]{1,15}") || !serverAllowedCurrencies.contains(currency)))
            invalid("SUPPORT_ANALYTICS_CURRENCY_INVALID");
        Instant start = null, end = null;
        if (b == Basis.PERIOD_EVENT) {
            if (from == null || to == null) invalid("SUPPORT_PERFORMANCE_RANGE_REQUIRED");
            try { start = Instant.parse(from); end = Instant.parse(to); }
            catch (DateTimeException ex) { invalid("SUPPORT_PERFORMANCE_RANGE_INVALID"); }
            if (!start.isBefore(end) || ChronoUnit.DAYS.between(start, end) > 3660
                    || start.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || end.isAfter(Instant.parse("9999-12-31T00:00:00Z"))
                    || start.getNano() % 1000 != 0 || end.getNano() % 1000 != 0)
                invalid("SUPPORT_PERFORMANCE_RANGE_INVALID");
        } else if (from != null || to != null) invalid("SUPPORT_ANALYTICS_WINDOW_INVALID");
        Sort sort = enumeration(sortKey, Sort.class, v == View.AGENTS ? Sort.BOUND_CUSTOMER_COUNT : v == View.FINANCE ? Sort.SUCCEEDED_AT : defaultSort(c, f, service));
        Direction d = enumeration(direction, Direction.class, defaultDirection(c, f, service));
        if (!serverSupportedSorts.contains(sort)) invalid("SUPPORT_ANALYTICS_SORT_INVALID");
        if (sort == Sort.NEXT_DUE_AT && service != ServiceFilter.DUE
                || sort == Sort.WAITING_SINCE_AT && service != ServiceFilter.WAITING_REPLY
                || sort == Sort.STATE_CHANGED_AT && service != ServiceFilter.STOPPED
                || sort == Sort.POOL_ENTERED_AT && c != Category.PENDING)
            invalid("SUPPORT_ANALYTICS_SORT_INVALID");
        if ((sort == Sort.PERSONAL_DEPOSIT || sort == Sort.TEAM_DEPOSIT || sort == Sort.AMOUNT) && currency == null)
            invalid("SUPPORT_ANALYTICS_CURRENCY_REQUIRED");
        long page = number(pageNum, 1), size = number(pageSize, 20);
        if (page < 1 || page > MAX_SAFE_INTEGER || size < 1 || size > 100 || page > Long.MAX_VALUE / size)
            invalid("SUPPORT_PAGE_INVALID");
        if (expectedVersion != null && !validVersion(expectedVersion)) invalid("SUPPORT_ANALYTICS_VERSION_INVALID");
        if (page > 1 && expectedVersion == null) invalid("SUPPORT_ANALYTICS_VERSION_REQUIRED");
        return new Normalized(v, c, f, service, k, b, start, end, g, a, currency, sort, d, page, (int) size, expectedVersion);
    }

    public record Normalized(View view, Category category, First firstState, ServiceFilter filter,
            String keyword, Basis basis, Instant from, Instant to, Long groupId, Long agentId,
            String currency, Sort sortKey, Direction direction, long pageNum, int pageSize, String expectedVersion) {
        /** Called only after current server-side ownership authorization. IllegalArgument becomes HTTP-boundary 422. */
        public Query toStatsQuery(ReadScope authorizedScope) {
            if (authorizedScope == null || !java.util.Objects.equals(groupId, authorizedScope.requestedGroupId())
                    || !java.util.Objects.equals(agentId, authorizedScope.requestedAgentId()))
                throw new IllegalArgumentException("Resolved scope does not match request");
            try {
                return new Query(authorizedScope.mode(), groupId, agentId, basis,
                    from == null ? null : LocalDateTime.ofInstant(from, BUSINESS_ZONE),
                    to == null ? null : LocalDateTime.ofInstant(to, BUSINESS_ZONE), BUSINESS_ZONE.getId(), currency);
            } catch (IllegalArgumentException ex) { throw new BizException(422, "SUPPORT_ANALYTICS_QUERY_INVALID"); }
        }
    }

    public static boolean validVersion(String value) { return value != null && value.matches("saq-v1:[0-9a-f]{64}"); }
    private static String raw(Map<String,List<String>> parameters, String name) {
        return parameters.containsKey(name) ? parameters.get(name).get(0) : null;
    }
    private static Long id(String raw) {
        if (raw == null) return null;
        long value = number(raw, 0);
        if (value < 1 || value > MAX_SAFE_INTEGER) invalid("SUPPORT_ID_INVALID");
        return value;
    }
    private static long number(String raw, long fallback) {
        if (raw == null) return fallback;
        if (!raw.matches("[0-9]+")) invalid("SUPPORT_ANALYTICS_QUERY_INVALID");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException ex) { throw new BizException(422, "SUPPORT_ANALYTICS_QUERY_INVALID"); }
    }
    private static <E extends Enum<E>> E enumeration(String raw, Class<E> type, E fallback) {
        if (raw == null) return fallback;
        try { return Enum.valueOf(type, raw); }
        catch (IllegalArgumentException ex) { throw new BizException(422, "SUPPORT_ANALYTICS_QUERY_INVALID"); }
    }
    private static Sort defaultSort(Category category, First first, ServiceFilter filter) {
        if (category == Category.PENDING) return Sort.POOL_ENTERED_AT;
        if (first == First.CONFIRMED) return Sort.FIRST_SUCCEEDED_AT;
        if (first == First.NONE) return Sort.REGISTERED_AT;
        return switch (filter) {
            case WAITING_REPLY -> Sort.WAITING_SINCE_AT;
            case FIRST_CONTACT -> Sort.ASSIGNED_AT;
            case DUE -> Sort.NEXT_DUE_AT;
            case STOPPED -> Sort.STATE_CHANGED_AT;
            default -> Sort.LAST_ACTIVE_AT;
        };
    }
    private static Direction defaultDirection(Category category, First first, ServiceFilter filter) {
        if (category == Category.PENDING || first == First.NONE
                || Set.of(ServiceFilter.WAITING_REPLY, ServiceFilter.FIRST_CONTACT, ServiceFilter.DUE, ServiceFilter.DORMANT).contains(filter))
            return Direction.ASC;
        return Direction.DESC;
    }
    private static void invalid(String message) { throw new BizException(422, message); }
}
