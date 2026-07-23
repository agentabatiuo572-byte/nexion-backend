package ffdd.opsconsole.market.application;

import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.market.mapper.AppStakingMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Server-authoritative user boundary for the four G1 USDT staking products. */
@Service
@RequiredArgsConstructor
public class AppStakingService {
    private static final String STAKING_PREFIX = "G.staking.";
    private static final String STAKING_KILLSWITCH_KEY = "killswitch.staking";
    private static final String STAKING_LEGACY_KILLSWITCH_KEY = "J.killswitch.staking";
    private final AppStakingMapper mapper;
    private final RiskDisclosureGateFacade disclosureGate;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final Clock clock;

    public ApiResult<Map<String, Object>> pools() {
        List<Map<String, Object>> rows = mapper.listCanonicalProducts().stream().map(this::poolView).toList();
        return ApiResult.ok(linked(
                "pools", rows,
                "serverCanonical", true,
                "source", "nx_staking_product + nx_config_item + nx_emergency_control_setting"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> positions(Long userId) {
        requireUser(userId);
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        LocalDateTime now = LocalDateTime.now(clock);
        mapper.matureDuePositions(userId, now);
        return positionsResponse(userId, null, null, null, null, null, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> open(Long userId, String idempotencyKey, OpenRequest request) {
        requireUser(userId);
        String tierKey = normalizeTier(request == null ? null : request.tierKey());
        BigDecimal requestedAmount = request == null ? null : request.amountUsdt();
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new BizException(422, "STAKING_AMOUNT_INVALID");
        }
        BigDecimal amount = money(requestedAmount);
        OpenRequest normalized = new OpenRequest(tierKey, amount);
        ApiResult<Void> disclosure = disclosureGate.checkUserGate(userId, "staking", idempotencyKey);
        if (disclosure.getCode() != 0) {
            return ApiResult.fail(disclosure.getCode(), disclosure.getMessage());
        }
        return executeOnce("OPEN", userId, idempotencyKey, normalized,
                () -> openInternal(userId, idempotencyKey, normalized));
    }

    private ApiResult<Map<String, Object>> openInternal(Long userId, String idempotencyKey, OpenRequest request) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppStakingMapper.ProductRow product = mapper.lockProductByTier(request.tierKey());
        if (product == null || !"USDT".equalsIgnoreCase(product.asset())
                || !"ACTIVE".equalsIgnoreCase(product.status())) {
            throw new BizException(409, "STAKING_POOL_NOT_ACTIVE");
        }
        PoolPolicy policy = policy(product);
        if (policy.killed()) throw new BizException(409, "STAKING_POOL_KILLED");
        if (!policy.enabled()) throw new BizException(409, "STAKING_POOL_STOPPED");
        if (!globalGateEnabled()) throw new BizException(409, "STAKING_GLOBAL_GATE_DISABLED");
        if (request.amountUsdt().compareTo(policy.minAmount()) < 0) {
            throw new BizException(422, "STAKING_MIN_AMOUNT_NOT_MET");
        }
        BigDecimal balance = mapper.lockWalletBalance(userId);
        if (balance == null || balance.compareTo(request.amountUsdt()) < 0) {
            throw new BizException(409, "STAKING_WALLET_INSUFFICIENT");
        }
        if (mapper.debitWallet(userId, request.amountUsdt()) != 1) {
            throw new BizException(409, "STAKING_WALLET_CONFLICT");
        }

        LocalDateTime lockedAt = LocalDateTime.now(clock);
        LocalDateTime unlockAt = lockedAt.plusDays(product.termDays());
        String positionNo = "STK-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        BigDecimal interest = request.amountUsdt().multiply(policy.apyPct())
                .multiply(BigDecimal.valueOf(product.termDays()))
                .divide(BigDecimal.valueOf(36_500), 6, RoundingMode.HALF_UP);
        AppStakingMapper.PositionWrite write = new AppStakingMapper.PositionWrite(
                userId, positionNo, product.id(), product.productCode(), product.productName(), request.amountUsdt(),
                policy.apyPct().multiply(BigDecimal.valueOf(100)),
                policy.penaltyPct().multiply(BigDecimal.valueOf(100)), product.termDays(), lockedAt, unlockAt, interest);
        if (mapper.insertPosition(write) != 1) throw new BizException(409, "STAKING_POSITION_CONFLICT");
        AppStakingMapper.PositionRow created = new AppStakingMapper.PositionRow(
                null, userId, positionNo, product.id(), product.productCode(), product.productName(),
                request.amountUsdt(), write.apyBps(), write.earlyPenaltyBps(), product.termDays(),
                lockedAt, unlockAt, interest, "ACTIVE", null, null);
        BigDecimal balanceAfter = money(balance.subtract(request.amountUsdt()));
        String billNo = positionNo + "-OPEN";
        if (mapper.insertLedger(new AppStakingMapper.LedgerWrite(
                userId, billNo, "STAKING_OPEN", "OUT", request.amountUsdt(), balanceAfter,
                "G1 staking principal lock")) != 1) {
            throw new BizException(409, "STAKING_LEDGER_CONFLICT");
        }
        AppStakingMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "positionNo", positionNo, "tierKey", request.tierKey(), "productCode", product.productCode(),
                "amountUsdt", request.amountUsdt(), "apyPct", policy.apyPct(),
                "termDays", product.termDays(), "unlockAt", unlockAt, "walletBalanceUsdt", balanceAfter);
        String receiptId = outbox.publishUserEvent(
                "STAKING_POSITION", positionNo, "staking.opened", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit("USER_STAKING_OPENED", positionNo, billNo, userId, idempotencyKey, event, "HIGH",
                "/api/stakes");
        return positionsResponse(userId, created, request.amountUsdt(), null, null,
                billNo, receiptId);
    }

    @Transactional
    public ApiResult<Map<String, Object>> claim(Long userId, String positionNo, String idempotencyKey) {
        requireUser(userId);
        String normalizedPosition = normalizePosition(positionNo);
        return executeOnce("CLAIM:" + normalizedPosition, userId, idempotencyKey, normalizedPosition,
                () -> claimInternal(userId, normalizedPosition, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> claimInternal(Long userId, String positionNo, String idempotencyKey) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppStakingMapper.PositionRow position = requirePosition(userId, positionNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!("ACTIVE".equals(position.status()) || "MATURE_UNCLAIMED".equals(position.status()))
                || position.unlockAt() == null || now.isBefore(position.unlockAt())) {
            throw new BizException(409, "STAKING_POSITION_NOT_CLAIMABLE");
        }
        BigDecimal balance = requireWallet(userId);
        if (mapper.markClaimed(position.id(), userId, now) != 1) {
            throw new BizException(409, "STAKING_POSITION_STATE_CONFLICT");
        }
        BigDecimal interest = money(position.estimatedInterestUsdt());
        BigDecimal credited = money(position.amountUsdt().add(interest));
        if (mapper.creditWallet(userId, credited) != 1) throw new BizException(409, "STAKING_WALLET_CONFLICT");
        BigDecimal balanceAfter = money(balance.add(credited));
        String billNo = positionNo + "-CLAIM";
        if (mapper.insertLedger(new AppStakingMapper.LedgerWrite(
                userId, billNo, "STAKING_CLAIM", "IN", credited, balanceAfter,
                "G1 staking matured principal and interest")) != 1) {
            throw new BizException(409, "STAKING_LEDGER_CONFLICT");
        }
        AppStakingMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "positionNo", positionNo, "principalUsdt", money(position.amountUsdt()),
                "interestUsdt", interest, "creditedUsdt", credited, "walletBalanceUsdt", balanceAfter);
        String receiptId = outbox.publishUserEvent(
                "STAKING_POSITION", positionNo, "staking.claimed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit("USER_STAKING_CLAIMED", positionNo, billNo, userId, idempotencyKey, event, "HIGH",
                "/api/stakes/" + positionNo + "/claim");
        return positionsResponse(userId, findPosition(userId, positionNo), position.amountUsdt(), interest, null,
                billNo, receiptId);
    }

    @Transactional
    public ApiResult<Map<String, Object>> earlyWithdraw(Long userId, String positionNo, String idempotencyKey) {
        requireUser(userId);
        String normalizedPosition = normalizePosition(positionNo);
        return executeOnce("EARLY_WITHDRAW:" + normalizedPosition, userId, idempotencyKey, normalizedPosition,
                () -> earlyWithdrawInternal(userId, normalizedPosition, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> earlyWithdrawInternal(
            Long userId, String positionNo, String idempotencyKey) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppStakingMapper.PositionRow position = requirePosition(userId, positionNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!"ACTIVE".equals(position.status()) || position.unlockAt() == null || !now.isBefore(position.unlockAt())) {
            throw new BizException(409, "STAKING_POSITION_NOT_EARLY_WITHDRAWABLE");
        }
        BigDecimal balance = requireWallet(userId);
        if (mapper.markEarlyWithdrawn(position.id(), userId, now) != 1) {
            throw new BizException(409, "STAKING_POSITION_STATE_CONFLICT");
        }
        BigDecimal penaltyPct = nz(position.earlyPenaltyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal penalty = position.amountUsdt().multiply(penaltyPct)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal credited = money(position.amountUsdt().subtract(penalty));
        if (mapper.creditWallet(userId, credited) != 1) throw new BizException(409, "STAKING_WALLET_CONFLICT");
        BigDecimal balanceAfter = money(balance.add(credited));
        String billNo = positionNo + "-EARLY";
        if (mapper.insertLedger(new AppStakingMapper.LedgerWrite(
                userId, billNo, "STAKING_EARLY_WITHDRAW", "IN", credited, balanceAfter,
                "G1 early withdrawal net principal; unearned interest forfeited")) != 1) {
            throw new BizException(409, "STAKING_LEDGER_CONFLICT");
        }
        AppStakingMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "positionNo", positionNo, "principalUsdt", money(position.amountUsdt()),
                "penaltyUsdt", penalty, "creditedUsdt", credited, "forfeitedInterestUsdt", money(position.estimatedInterestUsdt()),
                "walletBalanceUsdt", balanceAfter);
        String receiptId = outbox.publishUserEvent(
                "STAKING_POSITION", positionNo, "staking.early_withdrawn", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit("USER_STAKING_EARLY_WITHDRAWN", positionNo, billNo, userId, idempotencyKey, event, "HIGH",
                "/api/stakes/" + positionNo + "/early-withdraw");
        return positionsResponse(userId, findPosition(userId, positionNo), position.amountUsdt(), null, penalty,
                billNo, receiptId);
    }

    private Map<String, Object> poolView(AppStakingMapper.ProductRow product) {
        PoolPolicy policy = policy(product);
        return linked(
                "poolId", product.id(), "tierKey", tierKey(product.productCode()), "currency", "USDT",
                "termDays", product.termDays(), "apyPct", policy.apyPct(), "penaltyPct", policy.penaltyPct(),
                "minAmountUsdt", policy.minAmount(), "enabled", policy.enabled(), "killed", policy.killed(),
                "status", policy.killed() ? "KILLED" : policy.enabled() ? "ACTIVE" : "STOPPED");
    }

    private PoolPolicy policy(AppStakingMapper.ProductRow product) {
        String tier = tierKey(product.productCode());
        BigDecimal apyPct = configNumber(STAKING_PREFIX + "apy." + tier,
                nz(product.apyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.valueOf(300));
        BigDecimal penaltyPct = configNumber(STAKING_PREFIX + "penalty." + tier,
                nz(product.earlyPenaltyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.valueOf(100));
        BigDecimal minAmount = configNumber(STAKING_PREFIX + "min." + tier,
                money(product.minAmount()), BigDecimal.ZERO, BigDecimal.valueOf(1_000_000_000));
        boolean killed = config.activeValue(STAKING_PREFIX + tier + ".killed").map(this::switchEnabled).orElse(false);
        boolean enabled = config.activeValue(STAKING_PREFIX + "enabled." + tier)
                .map(this::switchEnabled).orElse("ACTIVE".equalsIgnoreCase(product.status()));
        return new PoolPolicy(apyPct, penaltyPct, minAmount, enabled && !killed && globalGateEnabled(), killed);
    }

    private boolean globalGateEnabled() {
        return KillSwitchState.enabled(
                java.util.Optional.ofNullable(mapper.controlValue(STAKING_KILLSWITCH_KEY)),
                java.util.Optional.ofNullable(mapper.controlValue(STAKING_LEGACY_KILLSWITCH_KEY)));
    }

    private BigDecimal configNumber(String key, BigDecimal fallback, BigDecimal min, BigDecimal max) {
        BigDecimal value = config.activeValue(key).map(raw -> decimal(raw, key)).orElse(fallback);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new BizException(503, "STAKING_CONFIG_INVALID:" + key);
        return value.stripTrailingZeros();
    }

    private BigDecimal decimal(String raw, String key) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException ex) {
            throw new BizException(503, "STAKING_CONFIG_INVALID:" + key);
        }
    }

    private ApiResult<Map<String, Object>> positionsResponse(
            Long userId, AppStakingMapper.PositionRow focus, BigDecimal principal, BigDecimal interest,
            BigDecimal penalty, String billNo, String receiptId) {
        Map<String, Object> response = linked(
                "positions", mapper.listUserPositions(userId).stream().map(this::positionView).toList(),
                "walletBalanceUsdt", money(mapper.walletBalance(userId)),
                "serverTime", LocalDateTime.now(clock),
                "serverCanonical", true);
        if (focus != null) response.put("position", positionView(focus));
        if (principal != null) response.put("principalUsdt", money(principal));
        if (interest != null) response.put("interestUsdt", money(interest));
        if (penalty != null) response.put("penaltyUsdt", money(penalty));
        if (focus != null && ("CLAIMED".equals(focus.status()) || "EARLY_WITHDRAWN".equals(focus.status()))) {
            BigDecimal credited = "CLAIMED".equals(focus.status())
                    ? focus.amountUsdt().add(nz(focus.estimatedInterestUsdt()))
                    : focus.amountUsdt().subtract(nz(penalty));
            response.put("creditedUsdt", money(credited));
        }
        if (billNo != null) response.put("billNo", billNo);
        if (receiptId != null) response.put("receiptId", receiptId);
        return ApiResult.ok(response);
    }

    private Map<String, Object> positionView(AppStakingMapper.PositionRow row) {
        return linked(
                "positionNo", row.positionNo(), "tierKey", tierKey(row.productCode()),
                "productCode", row.productCode(), "productName", row.productName(), "amountUsdt", money(row.amountUsdt()),
                "apyPct", nz(row.apyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros(),
                "penaltyPct", nz(row.earlyPenaltyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros(),
                "termDays", row.termDays(), "lockedAt", row.lockedAt(), "unlockAt", row.unlockAt(),
                "estimatedInterestUsdt", money(row.estimatedInterestUsdt()), "status", row.status());
    }

    private AppStakingMapper.PositionRow findPosition(Long userId, String positionNo) {
        return mapper.listUserPositions(userId).stream().filter(row -> positionNo.equals(row.positionNo())).findFirst()
                .orElseThrow(() -> new BizException(409, "STAKING_POSITION_PROJECTION_MISSING"));
    }

    private AppStakingMapper.PositionRow requirePosition(Long userId, String positionNo) {
        AppStakingMapper.PositionRow row = mapper.lockUserPosition(userId, positionNo);
        if (row == null) throw new BizException(404, "STAKING_POSITION_NOT_FOUND");
        return row;
    }

    private BigDecimal requireWallet(Long userId) {
        BigDecimal value = mapper.lockWalletBalance(userId);
        if (value == null) throw new BizException(409, "STAKING_WALLET_NOT_FOUND");
        return money(value);
    }

    private AppStakingMapper.UserAttribution requireAttribution(Long userId) {
        AppStakingMapper.UserAttribution value = mapper.userAttribution(userId);
        if (value == null || value.accountAgeMonths() == null || !StringUtils.hasText(value.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        return value;
    }

    private void recordUserAudit(
            String action, String positionNo, String billNo, Long userId, String idempotencyKey,
            Map<String, Object> detail, String risk, String path) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action(action).resourceType("STAKING_POSITION").resourceId(positionNo).bizNo(billNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .method("POST").path(path).result("SUCCESS").riskLevel(risk)
                .detail(linked("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim(), "state", detail))
                .build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> executeOnce(
            String operation, Long userId, String idempotencyKey, Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:G1_STAKING_" + operation + ":USER:" + userId,
                idempotencyKey, sha256(String.valueOf(request)), ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
    }

    private String normalizeTier(String value) {
        String tier = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT).replace("_", "") : "";
        if (!List.of("usdt30d", "usdt90d", "usdt180d", "usdt365d").contains(tier)) {
            throw new BizException(422, "STAKING_TIER_INVALID");
        }
        return tier;
    }

    private String normalizePosition(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("STK-[A-Za-z0-9-]{1,80}")) {
            throw new BizException(422, "STAKING_POSITION_NO_INVALID");
        }
        return value.trim();
    }

    private String tierKey(String productCode) {
        return productCode == null ? "" : productCode.trim().toLowerCase(Locale.ROOT).replace("_", "");
    }

    private String normalizePhase(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "P1";
        if (normalized.matches("[1-6]")) normalized = "P" + normalized;
        return normalized.matches("P[1-6]") ? normalized : "P1";
    }

    private boolean switchEnabled(String raw) {
        return raw != null && List.of("enabled", "enable", "on", "true", "1")
                .contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    private BigDecimal money(BigDecimal value) {
        return nz(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record OpenRequest(String tierKey, BigDecimal amountUsdt) {
    }

    private record PoolPolicy(
            BigDecimal apyPct, BigDecimal penaltyPct, BigDecimal minAmount, boolean enabled, boolean killed) {
    }
}
