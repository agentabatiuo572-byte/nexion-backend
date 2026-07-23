package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** H2 single source of truth: state, settlement, wallet/D4 and A4 all commit together. */
@Service
@RequiredArgsConstructor
public class AppTrialLifecycleService {
    private final AppTrialLifecycleMapper mapper;
    private final AdminIdempotencyService idempotency;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService audit;
    private final EventOutboxService outbox;

    public ApiResult<Map<String, Object>> state(Long userId) {
        if (userId == null || mapper.activeUser(userId) == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        TrialRow row = mapper.trial(userId);
        return ApiResult.ok(project(row, policyMap(), LocalDateTime.now()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> start(
            Long userId, Long paymentMethodId, String deviceName, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_START", userId, idempotencyKey,
                linked("paymentMethodId", paymentMethodId, "deviceName", deviceName),
                () -> startOnce(userId, paymentMethodId, deviceName, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> startOnce(
            Long userId, Long paymentMethodId, String deviceName, String idempotencyKey) {
        Map<String, String> policy = policyMap();
        if (!flag(policy, "phaseOpen", true)) return ApiResult.fail(409, "TRIAL_PHASE_CLOSED");
        if (mapper.trialCycleSignalCount(userId) > 0) return ApiResult.fail(409, "TRIAL_CYCLE_RISK_BLOCKED");
        if (paymentMethodId != null && mapper.lockUsablePaymentMethod(userId, paymentMethodId) == null) {
            return ApiResult.fail(409, "TRIAL_PAYMENT_METHOD_UNAVAILABLE");
        }
        TrialRow existing = mapper.lockTrial(userId);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null && active(existing.status())) return ApiResult.fail(409, "TRIAL_ALREADY_ACTIVE");
        if (existing != null && "REDEEMED".equals(normalize(existing.status()))) {
            return ApiResult.fail(409, "TRIAL_ALREADY_REDEEMED");
        }
        if (existing != null && existing.cooldownUntil() != null && existing.cooldownUntil().isAfter(now)) {
            return ApiResult.fail(409, "TRIAL_COOLDOWN_ACTIVE");
        }
        int days = positiveInt(policy, "trialDays", 3);
        BigDecimal dailyUsdt = decimal(policy, "shadowDailyUSD", "38.52");
        BigDecimal dailyNex = decimal(policy, "shadowDailyNEX", "65");
        BigDecimal offsetCap = decimal(policy, "trialOffsetCapUSD",
                policy.getOrDefault("discountCapUSD", "50"));
        BigDecimal price = decimal(policy, "trialPriceUSD", "1299");
        String productCode = policy.getOrDefault("trialProductId", "device-trial-standard");
        String normalizedDevice = StringUtils.hasText(deviceName) ? deviceName.trim() : "Nexion Trial Device";
        String claimNo = "TRIAL-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String snapshot = "paymentRail=NEXION_USDT_WALLET,productCode=" + productCode
                + ",trialDays=" + days + ",dailyUsdt=" + dailyUsdt + ",dailyNex=" + dailyNex
                + ",offsetCapUsdt=" + offsetCap + ",priceUsdt=" + price;
        int changed = existing == null
                ? mapper.insertTrial(userId, claimNo, idempotencyKey, paymentMethodId, normalizedDevice,
                        days, dailyUsdt, dailyNex, offsetCap, price, now, now.plusDays(days), snapshot)
                : mapper.restartTrial(existing.id(), existing.version(), claimNo, idempotencyKey,
                        paymentMethodId, normalizedDevice, days, dailyUsdt, dailyNex, offsetCap, price,
                        now, now.plusDays(days), snapshot);
        if (changed != 1) throw new BizException(409, "TRIAL_START_CONFLICT");
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", claimNo, "trial.started", userId, attr, linked(
                "trial_price_usdt", price, "trial_days", days, "payment_rail", "NEXION_USDT_WALLET"));
        record("H2_TRIAL_STARTED", claimNo, userId, linked("claimNo", claimNo, "policySnapshot", snapshot));
        return ApiResult.ok(project(mapper.trial(userId), policy, now));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId, String reason, String idempotencyKey) {
        requireUser(userId);
        String normalized = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        if (!List.of("unbind", "explicit").contains(normalized)) return ApiResult.fail(422, "TRIAL_CANCEL_REASON_INVALID");
        return once("TRIAL_CANCEL", userId, idempotencyKey, normalized,
                () -> cancelOnce(userId, normalized));
    }

    private ApiResult<Map<String, Object>> cancelOnce(Long userId, String reason) {
        TrialRow row = mapper.lockTrial(userId);
        if (row == null || !active(row.status())) return ApiResult.fail(409, "TRIAL_NOT_CANCELLABLE");
        LocalDateTime now = LocalDateTime.now();
        int cooldownDays = positiveInt(policyMap(), "cooldownDays", 30);
        if (mapper.cancelTrial(row.id(), row.version(), reason, now, now.plusDays(cooldownDays)) != 1) {
            throw new BizException(409, "TRIAL_CANCEL_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", row.claimNo(), "trial.cancelled", userId, attr,
                linked("cause", reason, "state_before", normalize(row.status())));
        record("H2_TRIAL_CANCELLED", row.claimNo(), userId,
                linked("cause", reason, "stateBefore", normalize(row.status())));
        return ApiResult.ok(project(mapper.trial(userId), policyMap(), now));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> extend(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_EXTEND", userId, idempotencyKey, "accept",
                () -> extendOnce(userId));
    }

    private ApiResult<Map<String, Object>> extendOnce(Long userId) {
        Map<String, String> policy = policyMap();
        TrialRow row = mapper.lockTrial(userId);
        if (row == null) return ApiResult.fail(409, "TRIAL_NOT_EXTENDABLE");
        LocalDateTime now = LocalDateTime.now();
        if ("ACTIVE".equals(normalize(row.status())) && !row.expiresAt().isAfter(now)) {
            if (mapper.enterGrace(row.id(), row.version(), now) != 1) throw new BizException(409, "TRIAL_GRACE_CONFLICT");
            row = mapper.lockTrial(userId);
        }
        if (!"GRACE".equals(normalize(row.status()))) return ApiResult.fail(409, "TRIAL_NOT_IN_GRACE");
        Settlement preview = settlement(row, policy, false, now);
        BigDecimal threshold = decimal(policy, "highQualityThresholdUSD", "100");
        if (preview.shadowUsdt().compareTo(threshold) < 0) return ApiResult.fail(409, "TRIAL_EXTENSION_THRESHOLD_NOT_MET");
        int days = positiveInt(policy, "extensionDays", 3);
        if (mapper.extendTrial(row.id(), row.version(), days, now) != 1) {
            throw new BizException(409, "TRIAL_EXTENSION_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", row.claimNo(), "trial.extended", userId, attr,
                linked("extension_days", days, "shadow_usdt", preview.shadowUsdt()));
        record("H2_TRIAL_EXTENDED", row.claimNo(), userId,
                linked("extensionDays", days, "shadowUsdt", preview.shadowUsdt()));
        return ApiResult.ok(project(mapper.trial(userId), policy, now));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> redeemEarly(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_REDEEM_EARLY", userId, idempotencyKey, "early",
                () -> redeemOnce(userId, true, "early"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> charge(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_CHARGE", userId, idempotencyKey, "auto",
                () -> redeemOnce(userId, false, "auto"));
    }

    private ApiResult<Map<String, Object>> redeemOnce(Long userId, boolean early, String trigger) {
        TrialRow row = mapper.lockTrial(userId);
        if (row == null || !active(row.status())) return ApiResult.fail(409, "TRIAL_NOT_CHARGEABLE");
        Map<String, String> policy = policyMap();
        LocalDateTime now = LocalDateTime.now();
        Settlement value = settlement(row, policy, early, now);
        requireCoverage(value.remainderUsdt(), value.shadowNex());
        WalletRow wallet = mapper.lockWallet(userId);
        if (wallet == null) throw new BizException(409, "TRIAL_WALLET_UNAVAILABLE");
        if (wallet.usdt().compareTo(value.chargeUsdt()) < 0) {
            publishChargeAttempt(userId, row, trigger, "FAILED", value.chargeUsdt(), "INSUFFICIENT_FUNDS");
            return ApiResult.ok(linked("ok", false, "reason", "INSUFFICIENT_FUNDS",
                    "amountUsdt", value.chargeUsdt(), "paymentRail", "NEXION_USDT_WALLET"));
        }
        if (mapper.settleWallet(userId, value.chargeUsdt(), value.remainderUsdt(), value.shadowNex()) != 1) {
            throw new BizException(409, "TRIAL_WALLET_CONFLICT");
        }
        BigDecimal usdtAfter = wallet.usdt().subtract(value.chargeUsdt()).add(value.remainderUsdt());
        BigDecimal nexAfter = wallet.nex().add(value.shadowNex());
        if (value.chargeUsdt().signum() > 0) mapper.insertLedger(userId, row.claimNo() + ":CHARGE", "TRIAL_CHARGE",
                "USDT", "OUT", value.chargeUsdt(), wallet.usdt().subtract(value.chargeUsdt()),
                "H2 purchase via Nexion USDT wallet");
        if (value.remainderUsdt().signum() > 0) mapper.insertLedger(userId, row.claimNo() + ":REMAINDER", "TRIAL_BONUS",
                "USDT", "IN", value.remainderUsdt(), usdtAfter, "H2 shadow remainder credited after purchase");
        if (value.shadowNex().signum() > 0) mapper.insertLedger(userId, row.claimNo() + ":NEX", "TRIAL_BONUS",
                "NEX", "IN", value.shadowNex(), nexAfter, "H2 shadow NEX credited after purchase");
        String productCode = policy.getOrDefault("trialProductId", "device-trial-standard");
        String instanceNo = "TRIAL-DEV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        if (mapper.insertPurchasedDevice(userId, row.claimNo(), productCode, instanceNo, row.deviceName(),
                row.priceUsdt(), row.dailyUsdt(), row.dailyNex()) != 1) {
            throw new BizException(409, "TRIAL_DEVICE_CREATE_CONFLICT");
        }
        Long deviceId = mapper.deviceIdByInstanceNo(instanceNo);
        String snapshot = value.snapshot(trigger, "NEXION_USDT_WALLET", productCode);
        if (deviceId == null || mapper.markRedeemed(row.id(), row.version(), deviceId,
                value.shadowUsdt(), value.shadowNex(), value.remainderUsdt(), value.discountUsdt(),
                value.chargeUsdt(), now, snapshot) != 1) {
            throw new BizException(409, "TRIAL_REDEEM_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        Map<String, Object> detail = linked(
                "shadow_usdt", value.shadowUsdt(), "shadow_nex", value.shadowNex(),
                "offset_usdt", value.offsetUsdt(), "remainder_usdt", value.remainderUsdt(),
                "discount_applied", value.discountUsdt(), "amount_usdt", value.chargeUsdt(),
                "early_purchase", early, "payment_rail", "NEXION_USDT_WALLET", "device_id", deviceId);
        publish("TRIAL", row.claimNo(), "trial.redeemed", userId, attr, detail);
        publishChargeAttempt(userId, row, trigger, "SUCCESS", value.chargeUsdt(), "REDEEMED");
        record("H2_TRIAL_REDEEMED", row.claimNo(), userId, detail);
        Map<String, Object> response = new LinkedHashMap<>(project(mapper.trial(userId), policy, now));
        response.putAll(detail);
        response.put("ok", true);
        return ApiResult.ok(response);
    }

    private Settlement settlement(TrialRow row, Map<String, String> policy, boolean early, LocalDateTime now) {
        long seconds = Math.max(0, Duration.between(row.claimedAt(), now).getSeconds());
        BigDecimal days = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86400), 8, RoundingMode.DOWN);
        BigDecimal maxDays = BigDecimal.valueOf(Math.max(1,
                Duration.between(row.claimedAt(), row.expiresAt()).toDays()));
        days = days.min(maxDays);
        BigDecimal shadowUsdt = row.dailyUsdt().multiply(days).setScale(6, RoundingMode.DOWN);
        BigDecimal shadowNex = row.dailyNex().multiply(days).setScale(6, RoundingMode.DOWN);
        BigDecimal offset = shadowUsdt.min(row.offsetCapUsdt()).setScale(6, RoundingMode.DOWN);
        BigDecimal remainder = shadowUsdt.subtract(offset).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        BigDecimal discount = BigDecimal.ZERO;
        if (early) {
            BigDecimal rate = decimal(policy, "discountRate", "15");
            if (rate.compareTo(BigDecimal.ONE) > 0) rate = rate.movePointLeft(2);
            discount = row.priceUsdt().multiply(rate)
                    .min(decimal(policy, "discountCapUSD", "50")).setScale(6, RoundingMode.DOWN);
        }
        BigDecimal charge = row.priceUsdt().subtract(discount).subtract(offset)
                .max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        return new Settlement(shadowUsdt, shadowNex, offset, remainder, discount, charge);
    }

    private void publishChargeAttempt(
            Long userId, TrialRow row, String trigger, String result, BigDecimal amount, String reason) {
        Attribution attr = requireAttribution(userId);
        Map<String, Object> detail = linked("trigger", trigger, "result", result,
                "amount_usdt", amount, "reason", reason, "payment_rail", "NEXION_USDT_WALLET");
        publish("TRIAL", row.claimNo(), "trial.charge_attempted", userId, attr, detail);
        record("H2_TRIAL_CHARGE_ATTEMPTED", row.claimNo(), userId, detail);
    }

    private Map<String, Object> project(TrialRow row, Map<String, String> policy, LocalDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (row == null) {
            result.put("state", "ELIGIBLE");
            result.put("canStart", flag(policy, "phaseOpen", true));
            result.put("source", "nx_trial_claim");
            result.put("paymentRail", "NEXION_USDT_WALLET");
            return result;
        }
        Settlement preview = active(row.status()) ? settlement(row, policy, false, now) : null;
        result.put("claimNo", row.claimNo());
        result.put("state", normalize(row.status()));
        result.put("canStart", !active(row.status())
                && !"REDEEMED".equals(normalize(row.status()))
                && (row.cooldownUntil() == null || !row.cooldownUntil().isAfter(now)));
        result.put("deviceName", row.deviceName());
        result.put("claimedAt", row.claimedAt());
        result.put("expiresAt", row.expiresAt());
        result.put("cooldownUntil", row.cooldownUntil());
        result.put("shadowUsdt", preview == null ? safe(row.shadowAccruedUsdt()) : preview.shadowUsdt());
        result.put("shadowNex", preview == null ? safe(row.shadowAccruedNex()) : preview.shadowNex());
        result.put("offsetUsdt", preview == null ? BigDecimal.ZERO : preview.offsetUsdt());
        result.put("remainderUsdt", preview == null ? safe(row.remainderUsdt()) : preview.remainderUsdt());
        result.put("priceUsdt", row.priceUsdt());
        result.put("paymentRail", "NEXION_USDT_WALLET");
        result.put("source", "nx_trial_claim + nx_user_wallet");
        return result;
    }

    private void requireCoverage(BigDecimal remainderUsdt, BigDecimal rewardNex) {
        if (safe(remainderUsdt).signum() <= 0 && safe(rewardNex).signum() <= 0) return;
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        if (snapshot == null || !snapshot.reliable() || snapshot.coverageRatio() == null
                || snapshot.redlinePct() == null || snapshot.coverageRatio().signum() <= 0
                || snapshot.redlinePct().signum() <= 0
                || snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0) {
            throw new BizException(422, "B1_COVERAGE_REDLINE_BLOCKED");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
    }

    private Attribution requireAttribution(Long userId) {
        Attribution attr = mapper.attribution(userId);
        if (attr == null || attr.accountAgeMonths() == null || !StringUtils.hasText(attr.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        return attr;
    }

    private void publish(
            String aggregateType, String aggregateId, String eventName, Long userId,
            Attribution attr, Map<String, Object> detail) {
        String phase = normalize(attr.phase());
        if (!phase.matches("P[1-6]")) phase = "P1";
        outbox.publishUserEvent(aggregateType, aggregateId, eventName, userId,
                phase, attr.accountAgeMonths(), attr.cohort(), detail);
    }

    private void record(String action, String claimNo, Long userId, Map<String, Object> detail) {
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType("TRIAL_CLAIM").resourceId(claimNo).bizNo(claimNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }

    private Map<String, String> policyMap() {
        List<PolicyRow> rows = mapper.policies();
        return (rows == null ? List.<PolicyRow>of() : rows).stream().collect(Collectors.toMap(
                PolicyRow::policyKey, PolicyRow::currentValue, (left, right) -> right, LinkedHashMap::new));
    }

    private int positiveInt(Map<String, String> policy, String key, int fallback) {
        try {
            return Math.max(1, new BigDecimal(policy.getOrDefault(key, String.valueOf(fallback))).intValueExact());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private BigDecimal decimal(Map<String, String> policy, String key, String fallback) {
        try {
            return new BigDecimal(policy.getOrDefault(key, fallback));
        } catch (RuntimeException ignored) {
            return new BigDecimal(fallback);
        }
    }

    private boolean flag(Map<String, String> policy, String key, boolean fallback) {
        String value = policy.get(key);
        if (!StringUtils.hasText(value)) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("1", "true", "enabled", "on", "yes", "是", "开", "开启").contains(normalized)) return true;
        if (List.of("0", "false", "disabled", "off", "no", "否", "关", "关闭").contains(normalized)) return false;
        return fallback;
    }

    private boolean active(String status) {
        return List.of("CLAIMED", "ACTIVE", "GRACE", "EXTENDED").contains(normalize(status));
    }

    private String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> once(
            String operation, Long userId, String key, Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:" + operation + ":USER:" + userId, key, sha256(String.valueOf(request)),
                ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private record Settlement(
            BigDecimal shadowUsdt, BigDecimal shadowNex, BigDecimal offsetUsdt,
            BigDecimal remainderUsdt, BigDecimal discountUsdt, BigDecimal chargeUsdt) {
        String snapshot(String trigger, String paymentRail, String productCode) {
            return "trigger=" + trigger + ",paymentRail=" + paymentRail + ",productCode=" + productCode
                    + ",shadowUsdt=" + shadowUsdt + ",shadowNex=" + shadowNex + ",offsetUsdt=" + offsetUsdt
                    + ",remainderUsdt=" + remainderUsdt + ",discountUsdt=" + discountUsdt
                    + ",chargeUsdt=" + chargeUsdt;
        }
    }
}
