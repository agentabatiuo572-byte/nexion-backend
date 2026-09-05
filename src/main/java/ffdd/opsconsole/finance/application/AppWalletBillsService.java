package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.AppWalletBillsMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppWalletBillsService {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("dev", "prod");
    private static final Set<String> ISOLATED_PROFILES = Set.of("test");
    private static final int MAX_CURSOR_LENGTH = 128;
    private static final LocalDateTime MYSQL_DATETIME_MIN = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final LocalDateTime MYSQL_DATETIME_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000);
    private final AppWalletBillsMapper mapper;
    private final Environment environment;
    private final Clock clock;

    /** Retains the focused test and legacy construction contract. */
    public AppWalletBillsService(AppWalletBillsMapper mapper, Environment environment) {
        this(mapper, environment, Clock.system(DateTimeFormatConfig.BUSINESS_ZONE));
    }

    @Autowired
    public AppWalletBillsService(AppWalletBillsMapper mapper, Environment environment, Clock clock) {
        this.mapper = mapper;
        this.environment = environment;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> list(Long userId, int page, int pageSize) {
        return list(userId, page, pageSize, null, null, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> list(Long userId, int page, int pageSize,
                                                String asset, String direction, String category, String cursor) {
        requireProductionUser(userId);
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BizException(400, "WALLET_BILLS_PAGE_INVALID");
        }
        BillFilter filter = filter(asset, direction, category);
        if (cursor == null) return offsetPage(userId, page, pageSize, filter);
        return cursorPage(userId, page, pageSize, filter, cursor);
    }

    /** Compatibility overload for callers using the original bounded ledger contract. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> list(Long userId) {
        requireProductionUser(userId);
        List<Map<String, Object>> bills = mapper.rows(userId, 200).stream().map(this::bill).toList();
        Map<String, Object> result = base();
        result.put("bills", bills);
        result.put("nextCursor", null);
        return ApiResult.ok(result);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> summary(Long userId) {
        AppWalletBillsMapper.UserScope scope = requireProductionUser(userId);
        // The initial scoped InnoDB read establishes the REPEATABLE_READ snapshot. Its DB UTC timestamp is the
        // conservative client watermark, so a concurrent later ledger insert cannot be marked as already read.
        Instant snapshotAsOf = scope.snapshotAt() == null
                ? clock.instant() : scope.snapshotAt().toInstant(java.time.ZoneOffset.UTC);
        LocalDateTime businessNow = LocalDateTime.ofInstant(snapshotAsOf, DateTimeFormatConfig.BUSINESS_ZONE);
        LocalDateTime dayStart = businessNow.toLocalDate().atStartOfDay();
        LocalDateTime nextDay = dayStart.plusDays(1);
        LocalDateTime monthStart = YearMonth.from(businessNow).atDay(1).atStartOfDay();
        LocalDateTime nextMonth = monthStart.plusMonths(1);
        AppWalletBillsMapper.SummaryRow summary = mapper.summary(userId, dayStart, nextDay, monthStart, nextMonth);
        if (summary == null) summary = new AppWalletBillsMapper.SummaryRow(null, null, null, null, null, null);

        Map<String, Object> result = base();
        result.put("timeZone", DateTimeFormatConfig.BUSINESS_ZONE.getId());
        result.put("asOf", snapshotAsOf.toString());
        result.put("rewardsUsdt", nonNegative(summary.rewardsUsdt()));
        result.put("rewardsNex", nonNegative(summary.rewardsNex()));
        result.put("latestRewardAt", apiInstant(summary.latestRewardAt()));
        result.put("todayNexEarn", signed(summary.todayNexEarn()));
        result.put("pendingNex", nonNegative(summary.pendingNex()));
        result.put("monthBillCount", Math.max(0L, summary.monthBillCount() == null ? 0L : summary.monthBillCount()));
        result.put("recentNexBills", mapper.recentNexRows(userId, 10).stream().map(this::bill).toList());
        return ApiResult.ok(result);
    }

    private ApiResult<Map<String, Object>> offsetPage(Long userId, int page, int pageSize, BillFilter filter) {
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException exception) {
            throw new BizException(400, "WALLET_BILLS_PAGE_INVALID");
        }
        long total = filter.empty() ? mapper.count(userId) : mapper.countFiltered(userId, filter.asset(), filter.direction(), filter.category());
        List<AppWalletBillsMapper.LedgerRow> rows = filter.empty()
                ? mapper.rows(userId, pageSize, offset)
                : mapper.rowsFiltered(userId, pageSize, offset,
                        filter.asset(), filter.direction(), filter.category());
        Map<String, Object> result = base();
        result.put("bills", rows.stream().map(this::bill).toList());
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", total);
        boolean hasMore = (long) page * pageSize < total;
        if (hasMore && page == Integer.MAX_VALUE) throw new BizException(400, "WALLET_BILLS_PAGE_INVALID");
        result.put("nextPage", hasMore ? page + 1 : null);
        result.put("nextCursor", null);
        return ApiResult.ok(result);
    }

    private ApiResult<Map<String, Object>> cursorPage(Long userId, int page, int pageSize, BillFilter filter, String cursor) {
        Cursor boundary = decodeCursor(cursor);
        List<AppWalletBillsMapper.LedgerRow> fetched = mapper.rowsAfter(userId, pageSize + 1,
                filter.asset(), filter.direction(), filter.category(), boundary.createdAt(), boundary.id());
        boolean hasMore = fetched.size() > pageSize;
        List<AppWalletBillsMapper.LedgerRow> pageRows = hasMore ? fetched.subList(0, pageSize) : fetched;
        long total = filter.empty() ? mapper.count(userId) : mapper.countFiltered(userId, filter.asset(), filter.direction(), filter.category());
        Map<String, Object> result = base();
        result.put("bills", pageRows.stream().map(this::bill).toList());
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", total);
        if (hasMore && page == Integer.MAX_VALUE) throw new BizException(400, "WALLET_BILLS_PAGE_INVALID");
        result.put("nextPage", hasMore ? page + 1 : null);
        result.put("nextCursor", hasMore && !pageRows.isEmpty() ? encodeCursor(pageRows.get(pageRows.size() - 1)) : null);
        return ApiResult.ok(result);
    }

    private BillFilter filter(String asset, String direction, String category) {
        String normalizedAsset = allowed(asset, Set.of("USDT", "NEX"), "WALLET_BILLS_FILTER_INVALID");
        String normalizedDirection = allowed(direction, Set.of("IN", "OUT"), "WALLET_BILLS_FILTER_INVALID");
        String normalizedCategory = allowed(category, Set.of("REWARD"), "WALLET_BILLS_FILTER_INVALID");
        return new BillFilter(normalizedAsset, normalizedDirection, normalizedCategory);
    }

    private String allowed(String value, Set<String> allowed, String error) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || !allowed.contains(normalized)) throw new BizException(400, error);
        return normalized;
    }

    private Cursor decodeCursor(String value) {
        if ("start".equals(value)) return new Cursor(null, null);
        if (value == null || value.isBlank() || value.length() > MAX_CURSOR_LENGTH) {
            throw new BizException(400, "WALLET_BILLS_CURSOR_INVALID");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            long id = Long.parseLong(parts[2]);
            if (id <= 0) throw new IllegalArgumentException();
            LocalDateTime createdAt = LocalDateTime.parse(parts[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (createdAt.isBefore(MYSQL_DATETIME_MIN) || createdAt.isAfter(MYSQL_DATETIME_MAX)) {
                throw new IllegalArgumentException();
            }
            return new Cursor(createdAt, id);
        } catch (RuntimeException exception) {
            throw new BizException(400, "WALLET_BILLS_CURSOR_INVALID");
        }
    }

    private String encodeCursor(AppWalletBillsMapper.LedgerRow row) {
        if (row == null || row.createdAt() == null || row.id() == null || row.id() <= 0) {
            throw new BizException(500, "WALLET_LEDGER_ROW_INVALID");
        }
        String raw = "v1|" + row.createdAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private AppWalletBillsMapper.UserScope requireProductionUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(403, "USER_AUTH_REQUIRED");
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim).map(String::toLowerCase).filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        boolean production = profiles.isEmpty()
                || (profiles.size() == 1 && PRODUCTION_PROFILES.contains(profiles.iterator().next()));
        boolean development = false;
        boolean isolated = profiles.size() == 1 && ISOLATED_PROFILES.contains(profiles.iterator().next());
        if (isolated) throw new BizException(409, "WALLET_PRODUCTION_BILLS_FORBIDDEN");
        if (!production && !development) throw new BizException(503, "WALLET_PROFILE_INVALID");
        AppWalletBillsMapper.UserScope user = mapper.userScope(userId);
        if (user == null || user.sandbox() == null) throw new BizException(403, "WALLET_USER_REQUIRED");
        if (production && user.sandbox() != 0) throw new BizException(403, "WALLET_PRODUCTION_USER_REQUIRED");
        return user;
    }

    private Map<String, Object> base() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "server");
        result.put("sourceEnvironment", "PRODUCTION");
        return result;
    }

    private Map<String, Object> bill(AppWalletBillsMapper.LedgerRow row) {
        if (row == null || row.id() == null || row.createdAt() == null) {
            throw new BizException(500, "WALLET_LEDGER_ROW_INVALID");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "WL-" + row.id());
        item.put("bizNo", row.bizNo());
        item.put("bizType", row.bizType());
        item.put("category", category(row.bizType(), row.direction()));
        item.put("asset", row.asset());
        item.put("direction", row.direction());
        item.put("amount", nonNegative(row.amount()));
        item.put("balanceAfter", nonNegative(row.balanceAfter()));
        item.put("status", apiStatus(row.status()));
        item.put("remark", row.remark());
        item.put("createdAt", apiInstant(row.createdAt()));
        return item;
    }

    private String category(String bizType, String direction) {
        String value = bizType == null ? "" : bizType.toUpperCase(Locale.ROOT);
        String normalizedDirection = direction == null ? "" : direction.toUpperCase(Locale.ROOT);
        if (value.matches(".*(DEPOSIT|TOPUP|RECHARGE).*$")) return "topup";
        if (value.matches(".*(WITHDRAW|PAYOUT).*$")) return "withdraw";
        if (value.matches(".*(REFERRAL|COMMISSION|UNILEVEL|BINARY|LEADERSHIP).*$")) return "refer";
        if (value.matches(".*(STAKE|STAKING).*$")) return "IN".equals(normalizedDirection) ? "unstake" : "stake";
        if (value.matches(".*(EXCHANGE|SWAP).*$")) return "swap";
        if (value.matches(".*(ACHIEVEMENT|MILESTONE|QUEST).*$")) return "achievement";
        if (value.matches(".*(REWARD|BONUS).*$")) return "bonus";
        if (value.matches(".*(PURCHASE|ORDER|REPURCHASE|GENESIS|TRADE_IN).*$")) return "IN".equals(normalizedDirection) ? "earn" : "purchase";
        if (value.matches(".*(EARN|RELEASE|TASK|TRIAL).*$")) return "earn";
        return "other";
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal signed(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String apiInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toString();
    }

    private String apiStatus(String value) {
        if (value == null || value.isBlank()) throw new BizException(500, "WALLET_LEDGER_STATUS_INVALID");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SUCCESS", "POSTED", "COMPLETED", "CONFIRMED" -> "SUCCESS";
            case "PENDING" -> "PENDING";
            case "FAILED", "REJECTED", "CANCELLED" -> "FAILED";
            default -> throw new BizException(500, "WALLET_LEDGER_STATUS_INVALID");
        };
    }

    private record BillFilter(String asset, String direction, String category) {
        boolean empty() { return asset == null && direction == null && category == null; }
    }

    private record Cursor(LocalDateTime createdAt, Long id) { }
}
