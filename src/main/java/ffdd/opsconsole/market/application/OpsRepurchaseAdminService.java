package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.AppRepurchaseMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Direct single-operator G7 configuration command required by the current PRD decision. */
@Service
@RequiredArgsConstructor
public class OpsRepurchaseAdminService {
    private static final List<String> ORDER_STATUSES = List.of(
            "ACTIVE", "MATURE_UNCLAIMED", "CLAIMED", "EARLY_WITHDRAWN");
    private static final Map<String, String> PERMISSIONS = Map.of(
            "apy", "finprod_g7_apy_write", "lockDays", "finprod_g7_apy_write",
            "nurture", "finprod_g7_nurture_write", "lottery", "finprod_g7_write",
            "penalty", "finprod_g7_write", "presets", "finprod_g7_write");

    private final AppRepurchaseMapper mapper;
    private final TreasuryCoverageFacade coverage;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;

    public ApiResult<Map<String, Object>> orders(String status, Long cursor, Integer requestedLimit) {
        String normalizedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (normalizedStatus != null && !ORDER_STATUSES.contains(normalizedStatus)) {
            throw new BizException(422, "G7_REPURCHASE_ORDER_STATUS_INVALID");
        }
        if (cursor != null && cursor < 1) throw new BizException(422, "G7_REPURCHASE_CURSOR_INVALID");
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 50) throw new BizException(422, "G7_REPURCHASE_LIMIT_INVALID");
        List<AppRepurchaseMapper.AdminOrderRow> rows = mapper.adminOrders(normalizedStatus, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AppRepurchaseMapper.AdminOrderRow> page = hasMore ? rows.subList(0, limit) : rows;
        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).id() : null;
        return ApiResult.ok(linked("orders", page.stream().map(this::adminOrderView).toList(),
                "nextCursor", nextCursor, "hasMore", hasMore, "serverCanonical", true));
    }

    public ApiResult<Map<String, Object>> update(String key, String idempotencyKey, UpdateRequest request) {
        String param = normalizeKey(key);
        validateRequest(request);
        requirePermission(PERMISSIONS.get(param));
        String hash = sha256(param + "|" + request.value().trim() + "|" + request.reason().trim()
                + "|" + String.valueOf(request.g4Ref()));
        return once("ADMIN:G7_REPURCHASE_PARAM:" + param, idempotencyKey, hash,
                () -> updateInternal(param, idempotencyKey, request));
    }

    @Transactional
    protected ApiResult<Map<String, Object>> updateInternal(String key, String idempotencyKey, UpdateRequest request) {
        AppRepurchaseMapper.ProductRow product = mapper.lockProduct();
        if (product == null) throw new BizException(404, "G7_REPURCHASE_PRODUCT_NOT_FOUND");
        String before = before(product, key);
        String after = normalizeValue(key, request.value());
        boolean amplifies = amplifies(key, decimal(before), decimal(after));
        TreasuryCoverageSnapshot snapshot = coverage.snapshot();
        if (amplifies && snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0) {
            throw new BizException(422, "COVERAGE_BELOW_REDLINE");
        }
        long issued = mapper.issuedTicketsThisMonth();
        long g4Capacity = configLong("G.genesis.lottery.monthlyCapacity", 100_000L);
        String g4Ref = StringUtils.hasText(request.g4Ref()) ? request.g4Ref().trim() : "NOT_APPLICABLE";
        if ("lottery".equals(key)) {
            if (!StringUtils.hasText(request.g4Ref()) || request.g4Ref().trim().length() < 3) {
                throw new BizException(400, "G4_CAPACITY_REF_REQUIRED");
            }
            if (issued + Long.parseLong(after) > g4Capacity) {
                throw new BizException(422, "G4_LOTTERY_CAPACITY_EXCEEDED");
            }
        }
        int changed = switch (key) {
            case "apy" -> mapper.updateApyBps(decimal(after).multiply(BigDecimal.valueOf(100)));
            case "lockDays" -> mapper.updateLockDays(Integer.parseInt(after));
            case "nurture" -> mapper.updateNurture(decimal(after));
            case "lottery" -> mapper.updateTicketPerOrder(Integer.parseInt(after));
            case "penalty" -> mapper.updatePenaltyBps(decimal(after).multiply(BigDecimal.valueOf(100)));
            case "presets" -> mapper.updatePresets(after);
            default -> 0;
        };
        if (changed != 1) throw new BizException(409, "G7_REPURCHASE_CONFIG_CONFLICT");
        String event = switch (key) {
            case "apy", "lockDays", "nurture" -> "admin.repurchase_config_changed";
            case "lottery" -> "admin.repurchase_lottery_changed";
            default -> "admin.repurchase_params_changed";
        };
        Map<String, Object> detail = linked("paramKey", key, "before", before, "after", after,
                "coverageAtSubmit", snapshot.coverageRatio(), "g4Capacity", g4Capacity,
                "g4TicketsIssued", issued, "g4Ref", g4Ref, "reason", request.reason().trim(),
                "operator", operator(request.operator()), "idempotencyKey", idempotencyKey.trim());
        String receiptId = outbox.publish("REPURCHASE_CONFIG", key, event, detail);
        recordAudit(event.toUpperCase(Locale.ROOT).replace('.', '_'), key, request, detail);
        return ApiResult.ok(linked("updated", true, "paramKey", key, "before", before, "after", after,
                "coverageAtSubmit", snapshot.coverageRatio(), "g4Capacity", g4Capacity,
                "g4TicketsIssued", issued, "receiptId", receiptId, "serverCanonical", true));
    }

    private String before(AppRepurchaseMapper.ProductRow p, String key) {
        return switch (key) {
            case "apy" -> pct(p.apyBps());
            case "lockDays" -> String.valueOf(p.termDays());
            case "nurture" -> plain(p.rewardMultiplier());
            case "lottery" -> String.valueOf(p.ticketPerOrder());
            case "penalty" -> pct(p.earlyPenaltyBps());
            case "presets" -> p.presetAmounts();
            default -> "";
        };
    }

    private Map<String, Object> adminOrderView(AppRepurchaseMapper.AdminOrderRow row) {
        return linked("orderNo", row.positionNo(), "userId", row.userId(), "userNo", row.userNo(),
                "nickname", row.nickname(), "amountUsdt", money(row.amountUsdt()),
                "apyPct", pct(row.apyBps()), "earlyPenaltyPct", pct(row.earlyPenaltyBps()),
                "lockDays", row.termDays(), "lockedAt", row.lockedAt(), "unlockAt", row.unlockAt(),
                "estimatedInterestUsdt", money(row.interestUsdt()), "status", row.status(),
                "claimedAt", row.claimedAt(), "earlyWithdrawnAt", row.earlyWithdrawnAt(),
                "billCorrelationPrefix", row.positionNo());
    }

    private String normalizeValue(String key, String raw) {
        if ("presets".equals(key)) {
            try {
                List<BigDecimal> values = Arrays.stream(raw.replace("$", "").replace(",", "").split("[/;|]"))
                        .map(String::trim).filter(StringUtils::hasText).map(BigDecimal::new)
                        .map(v -> v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()).distinct().sorted().toList();
                if (values.isEmpty() || values.size() > 12 || values.stream().anyMatch(v -> v.signum() <= 0)) {
                    throw new IllegalArgumentException();
                }
                return String.join(" / ", values.stream().map(BigDecimal::toPlainString).toList());
            } catch (RuntimeException ex) { throw new BizException(422, "G7_PRESETS_INVALID"); }
        }
        BigDecimal value = decimal(raw);
        if ("apy".equals(key) && outside(value, BigDecimal.ZERO, BigDecimal.valueOf(300)))
            throw new BizException(422, "G7_APY_OUT_OF_RANGE");
        if ("penalty".equals(key) && outside(value, BigDecimal.ZERO, BigDecimal.valueOf(100)))
            throw new BizException(422, "G7_PENALTY_OUT_OF_RANGE");
        if ("nurture".equals(key) && outside(value, BigDecimal.ONE, BigDecimal.TEN))
            throw new BizException(422, "G7_NURTURE_OUT_OF_RANGE");
        if ("lockDays".equals(key) && (!integer(value) || outside(value, BigDecimal.ONE, BigDecimal.valueOf(3650))))
            throw new BizException(422, "G7_LOCK_DAYS_OUT_OF_RANGE");
        if ("lottery".equals(key) && (!integer(value) || outside(value, BigDecimal.ZERO, BigDecimal.valueOf(100))))
            throw new BizException(422, "G7_LOTTERY_RULE_OUT_OF_RANGE");
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean amplifies(String key, BigDecimal before, BigDecimal after) {
        return (List.of("apy", "nurture").contains(key) && after.compareTo(before) > 0)
                || ("penalty".equals(key) && after.compareTo(before) < 0);
    }

    private void validateRequest(UpdateRequest request) {
        if (request == null || !StringUtils.hasText(request.value())) throw new BizException(422, "VALUE_REQUIRED");
        int length = request.reason() == null ? 0 : request.reason().trim().length();
        if (length < 8 || length > 200) throw new BizException(400, "REASON_LENGTH_INVALID");
    }

    private String normalizeKey(String key) {
        String value = StringUtils.hasText(key) ? key.trim() : "";
        if (!PERMISSIONS.containsKey(value)) throw new BizException(422, "G7_REPURCHASE_PARAM_KEY_INVALID");
        return value;
    }

    private void requirePermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()));
        if (!allowed) throw new BizException(403, "FORBIDDEN:" + permission);
    }

    private void recordAudit(String action, String key, UpdateRequest request, Map<String, Object> detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long actorId = null;
        try { actorId = Long.valueOf(String.valueOf(auth.getPrincipal())); } catch (RuntimeException ignored) { }
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("REPURCHASE_CONFIG")
                .resourceId(key).actorId(actorId).actorType("ADMIN").actorUsername(operator(request.operator()))
                .method("PUT").path("/api/admin/repurchase/config/" + key).result("SUCCESS").riskLevel("HIGH")
                .detail(detail).build());
    }

    private String operator(String supplied) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && StringUtils.hasText(auth.getName()) ? auth.getName()
                : StringUtils.hasText(supplied) ? supplied.trim() : "admin";
    }

    private long configLong(String key, long fallback) {
        String raw = mapper.configValue(key);
        if (!StringUtils.hasText(raw)) return fallback;
        try { return new BigDecimal(raw.trim()).longValueExact(); }
        catch (RuntimeException ex) { throw new BizException(503, "G7_CROSS_DOMAIN_CONFIG_INVALID:" + key); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> once(String scope, String key, String hash,
                                                Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(scope, key, hash,
                ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private BigDecimal decimal(String raw) {
        try { return new BigDecimal(raw.trim()); }
        catch (RuntimeException ex) { throw new BizException(422, "G7_NUMBER_INVALID"); }
    }
    private BigDecimal decimal(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private boolean outside(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }
    private boolean integer(BigDecimal value) { return value.stripTrailingZeros().scale() <= 0; }
    private String pct(BigDecimal bps) { return plain(decimal(bps).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)); }
    private String plain(BigDecimal value) { return decimal(value).stripTrailingZeros().toPlainString(); }
    private BigDecimal money(BigDecimal value) { return decimal(value).setScale(2, RoundingMode.HALF_UP); }
    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record UpdateRequest(String value, String reason, String operator, String g4Ref) { }
}
