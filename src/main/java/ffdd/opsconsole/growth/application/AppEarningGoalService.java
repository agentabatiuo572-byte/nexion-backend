package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.growth.mapper.AppEarningGoalMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.AppProductCatalogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import java.util.HexFormat;
import java.util.function.Supplier;

@ApplicationService
@RequiredArgsConstructor
public class AppEarningGoalService {
    private static final BigDecimal MIN_TARGET = new BigDecimal("100");
    private static final int MAX_DEADLINE_DAYS = 3650;

    private final AppEarningGoalMapper mapper;
    private final AppProductCatalogService productCatalogService;
    private final AdminIdempotencyService idempotency;
    private final Clock clock;

    public ApiResult<GoalListView> list(Long userId) {
        if (!validUser(userId) || mapper.activeUser(userId) == null) {
            return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        }
        BigDecimal lifetime = nonNegative(mapper.lifetimeEarnings(userId));
        List<AppEarningGoalMapper.GoalRow> rows = mapper.list(userId);
        if (rows == null) return ApiResult.fail(503, "GOALS_UNAVAILABLE");
        return ApiResult.ok(new GoalListView(true, "nx_earning_goal", lifetime,
                rows.stream().map(row -> view(row, lifetime)).toList()));
    }

    public ApiResult<GoalView> create(Long userId, BigDecimal targetUsdt, LocalDateTime deadlineAt, String idempotencyKey) {
        ApiResult<Void> validation = validate(userId, targetUsdt, deadlineAt);
        if (validation.getCode() != 0) return ApiResult.fail(validation.getCode(), validation.getMessage());
        return once(userId, idempotencyKey, targetUsdt, deadlineAt,
                () -> createOnce(userId, targetUsdt, deadlineAt));
    }

    private ApiResult<GoalView> createOnce(Long userId, BigDecimal targetUsdt, LocalDateTime deadlineAt) {
        AppEarningGoalMapper.GoalInsert inserted = new AppEarningGoalMapper.GoalInsert(
                userId, targetUsdt.setScale(6, RoundingMode.DOWN), deadlineAt);
        if (mapper.insert(inserted) != 1 || inserted.getId() == null) {
            return ApiResult.fail(503, "GOAL_SAVE_FAILED");
        }
        AppEarningGoalMapper.GoalRow row = mapper.findById(userId, inserted.getId());
        if (row == null) return ApiResult.fail(503, "GOAL_SAVE_UNCONFIRMED");
        return ApiResult.ok(view(row, nonNegative(mapper.lifetimeEarnings(userId))));
    }

    public ApiResult<GoalView> updateStatus(Long userId, Long goalId, boolean achieved) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (goalId == null || goalId <= 0) return ApiResult.fail(422, "GOAL_ID_REQUIRED");
        if (mapper.activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (mapper.updateStatus(userId, goalId, achieved) != 1) return ApiResult.fail(404, "GOAL_NOT_FOUND");
        List<AppEarningGoalMapper.GoalRow> rows = mapper.list(userId);
        AppEarningGoalMapper.GoalRow row = rows == null ? null : rows.stream()
                .filter(item -> goalId.equals(item.id())).findFirst().orElse(null);
        return row == null ? ApiResult.fail(404, "GOAL_NOT_FOUND")
                : ApiResult.ok(view(row, nonNegative(mapper.lifetimeEarnings(userId))));
    }

    public ApiResult<Void> delete(Long userId, Long goalId) {
        if (!validUser(userId)) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (goalId == null || goalId <= 0) return ApiResult.fail(422, "GOAL_ID_REQUIRED");
        if (mapper.activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        return mapper.softDelete(userId, goalId) == 1 ? ApiResult.ok() : ApiResult.fail(404, "GOAL_NOT_FOUND");
    }

    public ApiResult<RecommendationView> recommendation(Long userId, BigDecimal targetUsdt, LocalDateTime deadlineAt) {
        ApiResult<Void> validation = validate(userId, targetUsdt, deadlineAt);
        if (validation.getCode() != 0) return ApiResult.fail(validation.getCode(), validation.getMessage());
        ApiResult<Map<String, Object>> catalog = productCatalogService.catalog(userId);
        if (catalog == null || catalog.getCode() != 0 || catalog.getData() == null) {
            return ApiResult.fail(503, "GOAL_CATALOG_UNAVAILABLE");
        }
        Map<String, Object> data = catalog.getData();
        List<Map<String, Object>> products = productRows(data.get("products"));
        BigDecimal lifetime = nonNegative(mapper.lifetimeEarnings(userId));
        long days = remainingUtcDays(deadlineAt);
        BigDecimal requiredDaily = targetUsdt.subtract(lifetime).max(BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(days), 6, RoundingMode.CEILING);
        Map<String, Object> selected = products.stream()
                .filter(item -> truthy(item.get("available")))
                .filter(item -> positive(item.get("dailyEarn")).signum() > 0)
                .filter(item -> positive(item.get("dailyEarn")).compareTo(requiredDaily) >= 0)
                .min(Comparator.comparing(item -> positive(item.get("dailyEarn"))))
                .orElseGet(() -> products.stream().filter(item -> truthy(item.get("available")))
                        .filter(item -> positive(item.get("dailyEarn")).signum() > 0)
                        .max(Comparator.comparing(item -> positive(item.get("dailyEarn")))).orElse(null));
        if (selected == null) return ApiResult.fail(409, "GOAL_NO_ELIGIBLE_PRODUCT");
        String sourceEnvironment = text(data.get("sourceEnvironment"), "PRODUCTION");
        String runId = text(data.get("runId"), "");
        return ApiResult.ok(new RecommendationView(true, text(data.get("source"), "nx_product"),
                sourceEnvironment, runId, text(selected.get("id"), ""), text(selected.get("name"), ""),
                positive(selected.get("dailyEarn")), positive(selected.get("price")), requiredDaily,
                targetUsdt, days));
    }

    private ApiResult<Void> validate(Long userId, BigDecimal target, LocalDateTime deadline) {
        if (!validUser(userId) || mapper.activeUser(userId) == null) return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        if (target == null || target.compareTo(MIN_TARGET) < 0 || target.scale() > 6) {
            return ApiResult.fail(422, "GOAL_TARGET_INVALID");
        }
        LocalDateTime now = utcNow();
        if (deadline == null || !deadline.isAfter(now)
                || deadline.isAfter(now.plusDays(MAX_DEADLINE_DAYS))) {
            return ApiResult.fail(422, "GOAL_DEADLINE_INVALID");
        }
        return ApiResult.ok();
    }

    private GoalView view(AppEarningGoalMapper.GoalRow row, BigDecimal lifetime) {
        BigDecimal progress = row.targetUsdt() == null || row.targetUsdt().signum() <= 0 ? BigDecimal.ZERO
                : lifetime.divide(row.targetUsdt(), 6, RoundingMode.DOWN).multiply(BigDecimal.valueOf(100))
                        .min(BigDecimal.valueOf(100));
        return new GoalView(row.id(), row.targetUsdt(), epoch(row.deadlineAt()), epoch(row.createdAt()), row.achieved(),
                epoch(row.achievedAt()), progress, lifetime);
    }

    private List<Map<String, Object>> productRows(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            raw.forEach((key, val) -> row.put(String.valueOf(key), val));
            rows.add(row);
        }
        return rows;
    }

    private BigDecimal positive(Object value) {
        if (value instanceof BigDecimal number) return number.max(BigDecimal.ZERO);
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue()).max(BigDecimal.ZERO);
        try { return new BigDecimal(String.valueOf(value)).max(BigDecimal.ZERO); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    private BigDecimal nonNegative(BigDecimal value) { return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO); }
    private LocalDateTime utcNow() { return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)); }
    private long remainingUtcDays(LocalDateTime deadlineAt) {
        long milliseconds = Duration.between(utcNow(), deadlineAt).toMillis();
        return Math.max(1, Math.floorDiv(milliseconds + 86_400_000L - 1, 86_400_000L));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<GoalView> once(Long userId, String idempotencyKey, BigDecimal targetUsdt,
                                     LocalDateTime deadlineAt, Supplier<ApiResult<GoalView>> action) {
        return (ApiResult<GoalView>) (ApiResult) idempotency.execute(
                "APP:GOAL_CREATE:USER:" + userId,
                idempotencyKey,
                sha256(targetUsdt.stripTrailingZeros().toPlainString() + "|" + deadlineAt),
                ApiResult.class,
                (Supplier) action);
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
    private boolean validUser(Long userId) { return userId != null && userId > 0; }
    private boolean truthy(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0 || "true".equalsIgnoreCase(String.valueOf(value)); }
    private String text(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value); }
    private Long epoch(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC).toEpochMilli(); }

    public record GoalListView(boolean serverCanonical, String source, BigDecimal lifetimeEarningsUsdt,
                               List<GoalView> goals) { }
    public record GoalView(Long id, BigDecimal targetUsdt, Long deadlineAt, Long createdAt,
                           boolean achieved, Long achievedAt, BigDecimal progressPct,
                           BigDecimal lifetimeEarningsUsdt) { }
    public record RecommendationView(boolean serverCanonical, String source, String sourceEnvironment,
                                     String runId, String productNo, String productName, BigDecimal dailyEarn,
                                     BigDecimal price, BigDecimal requiredDaily, BigDecimal targetUsdt, long days) { }
}
