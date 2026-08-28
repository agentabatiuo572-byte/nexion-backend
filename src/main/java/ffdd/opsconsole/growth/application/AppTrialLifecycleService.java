package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** H2 single source of truth: state, settlement, wallet/D4 and A4 all commit together. */
@Service
@RequiredArgsConstructor
public class AppTrialLifecycleService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TRIAL_KILLSWITCH_KEY = "killswitch.trial";
    private static final String TRIAL_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.trial";
    private static final String TRIAL_CONVERSION_SOURCE = "nx_trial_claim + nx_order + nx_order_item";
    private static final BigDecimal MAX_EXPECTED_AMOUNT = new BigDecimal("1000000.00");
    private static final List<String> ACTIVE_STATES = List.of("CLAIMED", "ACTIVE", "GRACE", "EXTENDED");
    private static final List<String> RESTARTABLE_STATES = List.of("CANCELLED", "FAILED");
    private static final List<String> PERSISTED_STATES = List.of(
            "CLAIMED", "ACTIVE", "GRACE", "EXTENDED", "REDEEMED", "FAILED", "CANCELLED");
    private static final String LEGACY_TRIAL_PRODUCT_ID = "device-trial-standard";
    private static final String CANONICAL_TRIAL_PRODUCT_ID = "stellarbox-s1";

    private final AppTrialLifecycleMapper mapper;
    private final EarningsReleaseService earningsReleaseService;
    private final AdminIdempotencyService idempotency;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final Environment environment;
    private final Clock clock;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> state(Long userId) {
        if (userId == null || invalidUser(activeUser(userId, false))) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        LocalDateTime now = businessNow();
        Map<String, String> policy = policyMap();
        TrialRow row = mapper.lockTrial(userId);
        row = advanceExpiredActiveToGrace(userId, row, policy, now);
        return ApiResult.ok(project(userId, row, policy, now));
    }

    private TrialRow advanceExpiredActiveToGrace(
            Long userId, TrialRow row, Map<String, String> policy, LocalDateTime now) {
        if (row == null || !List.of("CLAIMED", "ACTIVE").contains(normalize(row.status()))
                || row.expiresAt() == null || row.expiresAt().isAfter(now)) {
            return row;
        }
        Settlement frozen = settlement(row, policy, false, now);
        if (mapper.enterGrace(row.id(), row.version(), now) == 1) {
            Attribution attr = requireAttribution(userId);
            publish("TRIAL", row.claimNo(), "trial.grace_entered", userId, attr,
                    linked("grace_days", nonNegativeInt(policy, "graceDays", 7),
                            "shadow_usdt", frozen.shadowUsdt(), "shadow_nex", frozen.shadowNex()));
            record("H2_TRIAL_GRACE_ENTERED", row.claimNo(), userId,
                    linked("shadowUsdt", frozen.shadowUsdt(), "shadowNex", frozen.shadowNex()));
        }
        TrialRow refreshed = mapper.lockTrial(userId);
        return refreshed == null ? row : refreshed;
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
        if (!trialGateEnabled()) return ApiResult.fail(409, "TRIAL_KILL_SWITCH_DISABLED");
        Map<String, String> policy = policyMap();
        if (!flag(policy, "phaseOpen", true)) return ApiResult.fail(409, "TRIAL_PHASE_CLOSED");
        if (mapper.trialCycleSignalCount(userId) > 0) return ApiResult.fail(409, "TRIAL_CYCLE_RISK_BLOCKED");
        if (paymentMethodId != null && mapper.lockUsablePaymentMethod(userId, paymentMethodId) == null) {
            return ApiResult.fail(409, "TRIAL_PAYMENT_METHOD_UNAVAILABLE");
        }
        TrialRow existing = mapper.lockTrial(userId);
        LocalDateTime now = businessNow();
        String existingState = existing == null ? null : normalize(existing.status());
        if (existing != null && active(existingState)) return ApiResult.fail(409, "TRIAL_ALREADY_ACTIVE");
        if (existing != null && "REDEEMED".equals(existingState)) {
            return ApiResult.fail(409, "TRIAL_ALREADY_REDEEMED");
        }
        if (existing != null && !restartable(existingState)) {
            return ApiResult.fail(409, "TRIAL_STATE_UNKNOWN");
        }
        if (existing != null && existing.cooldownUntil() != null && existing.cooldownUntil().isAfter(now)) {
            return ApiResult.fail(409, "TRIAL_COOLDOWN_ACTIVE");
        }
        int days = positiveInt(policy, "trialDays", 3);
        BigDecimal dailyUsdt = decimal(policy, "shadowDailyUSD", "38.52");
        BigDecimal dailyNex = decimal(policy, "shadowDailyNEX", "65");
        BigDecimal offsetCap = decimal(policy, "trialOffsetCapUSD",
                policy.getOrDefault("discountCapUSD", "50"));
        String productCode = trialProductCode(policy);
        if (productCode == null) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_CONFIG_INVALID");
        }
        AppTrialLifecycleMapper.ConversionProduct product = mapper.lockTrialStartProduct(productCode);
        if (!availablePhysicalTrialProduct(product)) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        }
        String normalizedDevice = product.name();
        BigDecimal price = product.priceUsdt();
        LocalDate quotaDate = businessDate();
        int seatsBeforeClaim = nonNegativeInt(policy, "seatsLeftToday", 0);
        if (seatsBeforeClaim <= 0 || mapper.consumeTrialQuota(quotaDate) != 1) {
            return ApiResult.fail(409, "TRIAL_QUOTA_EXHAUSTED");
        }
        Integer seatsAfterClaimValue = mapper.trialQuotaRemaining(quotaDate);
        if (seatsAfterClaimValue == null || seatsAfterClaimValue < 0) {
            throw new BizException(503, "TRIAL_QUOTA_STATE_UNAVAILABLE");
        }
        int seatsAfterClaim = seatsAfterClaimValue;
        String claimNo = "TRIAL-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String snapshot = "paymentRail=NEXION_USDT_WALLET,productCode=" + productCode
                + ",trialDays=" + days + ",dailyUsdt=" + dailyUsdt + ",dailyNex=" + dailyNex
                + ",offsetCapUsdt=" + offsetCap + ",priceUsdt=" + price
                + ",seatsLeftTodayBefore=" + seatsBeforeClaim + ",seatsLeftTodayAfter=" + seatsAfterClaim;
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
        Map<String, String> remainingPolicy = new LinkedHashMap<>(policy);
        remainingPolicy.put("seatsLeftToday", Integer.toString(seatsAfterClaim));
        return ApiResult.ok(project(userId, mapper.trial(userId), remainingPolicy, now));
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
        LocalDateTime now = businessNow();
        int cooldownDays = positiveInt(policyMap(), "cooldownDays", 30);
        if (mapper.cancelTrial(row.id(), row.version(), reason, now, now.plusDays(cooldownDays)) != 1) {
            throw new BizException(409, "TRIAL_CANCEL_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", row.claimNo(), "trial.cancelled", userId, attr,
                linked("cause", reason, "state_before", normalize(row.status())));
        record("H2_TRIAL_CANCELLED", row.claimNo(), userId,
                linked("cause", reason, "stateBefore", normalize(row.status())));
        return ApiResult.ok(project(userId, mapper.trial(userId), policyMap(), now));
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
        LocalDateTime now = businessNow();
        if ("ACTIVE".equals(normalize(row.status())) && !row.expiresAt().isAfter(now)) {
            if (mapper.enterGrace(row.id(), row.version(), now) != 1) throw new BizException(409, "TRIAL_GRACE_CONFLICT");
            row = mapper.lockTrial(userId);
        }
        if (!"GRACE".equals(normalize(row.status()))) return ApiResult.fail(409, "TRIAL_NOT_IN_GRACE");
        Settlement preview = settlement(row, policy, false, now);
        BigDecimal threshold = decimal(policy, "highQualityThresholdUSD", "100");
        if (preview.shadowUsdt().compareTo(threshold) < 0) return ApiResult.fail(409, "TRIAL_EXTENSION_THRESHOLD_NOT_MET");
        int days = positiveInt(policy, "extensionDays", 3);
        int graceDays = nonNegativeInt(policy, "graceDays", 7);
        LocalDateTime extendedExpiresAt = row.expiresAt().plusDays((long) graceDays + days);
        if (mapper.extendTrial(row.id(), row.version(), extendedExpiresAt, now) != 1) {
            throw new BizException(409, "TRIAL_EXTENSION_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", row.claimNo(), "trial.extended", userId, attr,
                linked("extension_days", days, "shadow_usdt", preview.shadowUsdt()));
        record("H2_TRIAL_EXTENDED", row.claimNo(), userId,
                linked("extensionDays", days, "shadowUsdt", preview.shadowUsdt()));
        return ApiResult.ok(project(userId, mapper.trial(userId), policy, now));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> redeemEarly(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_REDEEM_EARLY", userId, idempotencyKey, "early",
                () -> redeemOnce(userId, true, "early"));
    }

    /** Converts the reserved trial slot into one authoritative catalogue order. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> convert(
            Long userId, String productNo, BigDecimal expectedAmountUsdt, String idempotencyKey) {
        requireUser(userId);
        String normalized = productNo == null ? "" : productNo.trim();
        return once("TRIAL_CONVERT:PRODUCTION", userId, idempotencyKey,
                linked("productNo", normalized, "expectedAmountUsdt", expectedAmountUsdt),
                () -> convertOnce(userId, normalized, expectedAmountUsdt));
    }

    private ApiResult<Map<String, Object>> convertOnce(
            Long userId, String productNo, BigDecimal expectedAmountUsdt) {
        if (!productNo.matches("[A-Za-z0-9._-]{2,64}")) return ApiResult.fail(422, "TRIAL_PRODUCT_REQUIRED");
        if (!validExpectedAmount(expectedAmountUsdt)) {
            return ApiResult.fail(409, "TRIAL_AMOUNT_INVALID");
        }
        Map<String, String> policy = policyMap();
        TrialRow row = mapper.lockTrial(userId);
        if (row == null || !active(row.status())) return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        String configured = trialProductCode(policy, row);
        String canonicalRequested = canonicalTrialProductId(productNo);
        if (configured == null || !canonicalRequested.equals(configured)) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_ELIGIBLE");
        }
        LocalDateTime now = businessNow();
        if (row.expiresAt() == null || now.isAfter(row.expiresAt().plusDays(nonNegativeInt(policy, "graceDays", 7)))) {
            return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        }
        AppTrialLifecycleMapper.ConversionProduct product = mapper.lockConversionProduct(canonicalRequested);
        if (!availablePhysicalTrialProduct(product)) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        }
        Settlement settlement = settlement(row, policy, false, now);
        BigDecimal subtotal = row.priceUsdt();
        if (subtotal == null || subtotal.signum() <= 0) {
            throw new BizException(409, "TRIAL_PRICE_SNAPSHOT_INVALID");
        }
        BigDecimal promoDiscount = conversionPromoDiscount(subtotal, policy);
        BigDecimal discount = promoDiscount.add(settlement.offsetUsdt())
                .min(subtotal).setScale(6, RoundingMode.DOWN);
        BigDecimal amount = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        // The App confirms a two-decimal USDT quote. Reject only when the
        // canonical two-decimal amount increased; a later/larger trial offset
        // may safely reduce what the user owes.
        if (amount.setScale(2, RoundingMode.HALF_UP)
                .compareTo(expectedAmountUsdt.setScale(2, RoundingMode.UNNECESSARY)) > 0) {
            return ApiResult.fail(409, "TRIAL_AMOUNT_MISMATCH");
        }
        requireCoverage(settlement.remainderUsdt(), settlement.shadowNex());
        WalletRow wallet = mapper.lockWallet(userId);
        if (wallet == null) return ApiResult.fail(409, "TRIAL_WALLET_UNAVAILABLE");
        if (wallet.usdt().compareTo(amount) < 0) return ApiResult.fail(409, "TRIAL_WALLET_INSUFFICIENT");
        if (mapper.settleWallet(userId, amount, BigDecimal.ZERO, BigDecimal.ZERO) != 1) {
            throw new BizException(409, "TRIAL_WALLET_CONFLICT");
        }
        if (settlement.remainderUsdt().signum() > 0) {
            earningsReleaseService.creditReward(userId, "H2_TRIAL_REMAINDER", row.claimNo() + ":REMAINDER",
                    "USDT", settlement.remainderUsdt(), "H2:" + row.claimNo() + ":REMAINDER:USDT");
        }
        if (settlement.shadowNex().signum() > 0) {
            earningsReleaseService.creditReward(userId, "H2_TRIAL_BONUS", row.claimNo() + ":NEX",
                    "NEX", settlement.shadowNex(), "H2:" + row.claimNo() + ":NEX");
        }
        BigDecimal usdtAfter = wallet.usdt().subtract(amount).add(settlement.remainderUsdt());
        BigDecimal nexAfter = wallet.nex().add(settlement.shadowNex());
        if (amount.signum() > 0) mapper.insertLedger(userId, row.claimNo() + ":CHARGE", "TRIAL_CHARGE",
                "USDT", "OUT", amount, wallet.usdt().subtract(amount), "H2 conversion via Nexion USDT wallet");
        if (settlement.remainderUsdt().signum() > 0) mapper.insertLedger(
                userId, row.claimNo() + ":REMAINDER", "TRIAL_BONUS", "USDT", "IN",
                settlement.remainderUsdt(), usdtAfter, "H2 shadow remainder credited after purchase");
        if (settlement.shadowNex().signum() > 0) mapper.insertLedger(
                userId, row.claimNo() + ":NEX", "TRIAL_BONUS", "NEX", "IN",
                settlement.shadowNex(), nexAfter, "H2 shadow NEX credited after purchase");
        String orderNo = "TRC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (mapper.decrementProductStock(product.id()) != 1) throw new BizException(409, "TRIAL_PRODUCT_STOCK_CONFLICT");
        if (mapper.insertConversionOrder(userId, orderNo, product.id(), subtotal, discount, amount) != 1
                || mapper.insertConversionOrderItem(orderNo, product.id(), product.productNo(), product.name(),
                        subtotal) != 1) {
            throw new BizException(409, "TRIAL_CONVERSION_ORDER_CONFLICT");
        }
        String instanceNo = "TRIAL-DEV-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        if (mapper.insertPurchasedDevice(userId, orderNo, product.id(), product.productNo(), product.tier(),
                "SHARE".equalsIgnoreCase(product.productType()) ? "CLOUD_SHARE" : "CLOUD",
                instanceNo, row.deviceName(), subtotal, row.dailyUsdt(), row.dailyNex()) != 1) {
            throw new BizException(409, "TRIAL_DEVICE_CREATE_CONFLICT");
        }
        Long deviceId = mapper.deviceIdByInstanceNo(instanceNo);
        String snapshot = settlement.snapshot("convert", "NEXION_USDT_WALLET", product.productNo())
                + ",orderNo=" + orderNo + ",promoDiscountUsdt=" + promoDiscount
                + ",totalDiscountUsdt=" + discount + ",amountUsdt=" + amount;
        if (deviceId == null || mapper.markRedeemed(row.id(), row.version(), deviceId,
                settlement.shadowUsdt(), settlement.shadowNex(), settlement.remainderUsdt(), discount,
                amount, now, snapshot) != 1) {
            throw new BizException(409, "TRIAL_CONVERSION_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        Map<String, Object> detail = linked("orderNo", orderNo, "productNo", product.productNo(),
                "amountUsdt", amount, "discountUsdt", discount, "paymentStatus", "PAID",
                "orderStatus", "PAID", "paymentRail", "NEXION_USDT_WALLET",
                "deviceId", deviceId, "sourceEnvironment", "PRODUCTION");
        putCanonicalProvenance(detail, TRIAL_CONVERSION_SOURCE);
        publish("TRIAL", row.claimNo(), "trial.redeemed", userId, attr, detail);
        record("H2_TRIAL_CONVERTED", row.claimNo(), userId, detail);
        return ApiResult.ok(detail);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> charge(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_CHARGE", userId, idempotencyKey, "auto",
                () -> redeemOnce(userId, false, "auto"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> settleDue(
            Long userId, String expectedClaimNo, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_SETTLE_DUE", userId, idempotencyKey, expectedClaimNo,
                () -> settleDueOnce(userId, expectedClaimNo));
    }

    private ApiResult<Map<String, Object>> settleDueOnce(Long userId, String expectedClaimNo) {
        TrialRow row = mapper.lockTrial(userId);
        if (row == null || !active(row.status()) || !row.claimNo().equals(expectedClaimNo)) {
            return ApiResult.fail(409, "TRIAL_DUE_STATE_CONFLICT");
        }
        Map<String, String> policy = policyMap();
        LocalDateTime now = businessNow();
        String state = normalize(row.status());
        if (row.expiresAt() == null) return ApiResult.fail(409, "TRIAL_DUE_TIME_MISSING");
        LocalDateTime dueAt = "EXTENDED".equals(state)
                ? row.expiresAt()
                : row.expiresAt().plusDays(nonNegativeInt(policy, "graceDays", 7));
        if (dueAt.isAfter(now)) return ApiResult.fail(409, "TRIAL_NOT_DUE");
        if (!flag(policy, "autoChargeAtEnd", true)) {
            return cancelOnce(userId, "auto_end");
        }
        return redeemOnce(userId, false, "auto");
    }

    private ApiResult<Map<String, Object>> redeemOnce(Long userId, boolean early, String trigger) {
        TrialRow row = mapper.lockTrial(userId);
        if (row == null || !active(row.status())) return ApiResult.fail(409, "TRIAL_NOT_CHARGEABLE");
        Map<String, String> policy = policyMap();
        String productCode = trialProductCode(policy, row);
        if (productCode == null) return ApiResult.fail(409, "TRIAL_PRODUCT_CONFIG_INVALID");
        AppTrialLifecycleMapper.ConversionProduct product = mapper.lockConversionProduct(productCode);
        if (!availablePhysicalTrialProduct(product)) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        }
        LocalDateTime now = businessNow();
        Settlement value = settlement(row, policy, early, now);
        requireCoverage(value.remainderUsdt(), value.shadowNex());
        WalletRow wallet = mapper.lockWallet(userId);
        if (wallet == null) throw new BizException(409, "TRIAL_WALLET_UNAVAILABLE");
        if (wallet.usdt().compareTo(value.chargeUsdt()) < 0) {
            publishChargeAttempt(userId, row, trigger, "FAILED", value.chargeUsdt(), "INSUFFICIENT_FUNDS");
            int cooldownDays = positiveInt(policy, "cooldownDays", 30);
            if (mapper.failTrial(row.id(), row.version(), value.shadowUsdt(), value.shadowNex(),
                    now, now.plusDays(cooldownDays), "INSUFFICIENT_FUNDS") != 1) {
                throw new BizException(409, "TRIAL_CHARGE_FAILURE_CONFLICT");
            }
            return ApiResult.ok(linked("ok", false, "reason", "INSUFFICIENT_FUNDS",
                    "amountUsdt", value.chargeUsdt(), "paymentRail", "NEXION_USDT_WALLET"));
        }
        if (mapper.settleWallet(userId, value.chargeUsdt(), BigDecimal.ZERO, BigDecimal.ZERO) != 1) {
            throw new BizException(409, "TRIAL_WALLET_CONFLICT");
        }
        if (value.remainderUsdt().signum() > 0) {
            earningsReleaseService.creditReward(userId, "H2_TRIAL_REMAINDER", row.claimNo() + ":REMAINDER",
                    "USDT", value.remainderUsdt(), "H2:" + row.claimNo() + ":REMAINDER:USDT");
        }
        if (value.shadowNex().signum() > 0) {
            earningsReleaseService.creditReward(userId, "H2_TRIAL_BONUS", row.claimNo() + ":NEX",
                    "NEX", value.shadowNex(), "H2:" + row.claimNo() + ":NEX");
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
        if (mapper.decrementProductStock(product.id()) != 1) {
            throw new BizException(409, "TRIAL_PRODUCT_STOCK_CONFLICT");
        }
        BigDecimal orderDiscount = value.discountUsdt().add(value.offsetUsdt())
                .min(row.priceUsdt()).setScale(6, RoundingMode.DOWN);
        String orderNo = "TRC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (mapper.insertConversionOrder(userId, orderNo, product.id(), row.priceUsdt(),
                orderDiscount, value.chargeUsdt()) != 1
                || mapper.insertConversionOrderItem(orderNo, product.id(), product.productNo(), product.name(),
                        row.priceUsdt()) != 1) {
            throw new BizException(409, "TRIAL_CONVERSION_ORDER_CONFLICT");
        }
        String instanceNo = "TRIAL-DEV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        if (mapper.insertPurchasedDevice(userId, orderNo, product.id(), product.productNo(), product.tier(),
                "SHARE".equalsIgnoreCase(product.productType()) ? "CLOUD_SHARE" : "CLOUD",
                instanceNo, row.deviceName(),
                row.priceUsdt(), row.dailyUsdt(), row.dailyNex()) != 1) {
            throw new BizException(409, "TRIAL_DEVICE_CREATE_CONFLICT");
        }
        Long deviceId = mapper.deviceIdByInstanceNo(instanceNo);
        String snapshot = value.snapshot(trigger, "NEXION_USDT_WALLET", productCode)
                + ",orderNo=" + orderNo + ",totalDiscountUsdt=" + orderDiscount;
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
                "early_purchase", early, "order_no", orderNo, "payment_status", "PAID",
                "order_status", "PAID", "payment_rail", "NEXION_USDT_WALLET", "device_id", deviceId);
        publish("TRIAL", row.claimNo(), "trial.redeemed", userId, attr, detail);
        publishChargeAttempt(userId, row, trigger, "SUCCESS", value.chargeUsdt(), "REDEEMED");
        record("H2_TRIAL_REDEEMED", row.claimNo(), userId, detail);
        Map<String, Object> response = new LinkedHashMap<>(project(userId, mapper.trial(userId), policy, now));
        response.putAll(detail);
        response.put("ok", true);
        return ApiResult.ok(response);
    }

    private Settlement settlement(TrialRow row, Map<String, String> policy, boolean early, LocalDateTime now) {
        long seconds = Math.max(0, Duration.between(row.claimedAt(), now).getSeconds());
        BigDecimal days = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86400), 8, RoundingMode.DOWN);
        BigDecimal maxDays = BigDecimal.valueOf(Math.max(1, row.durationDays() == null ? 1 : row.durationDays()));
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

    private BigDecimal conversionPromoDiscount(BigDecimal catalogPrice, Map<String, String> policy) {
        BigDecimal rate = decimal(policy, "discountRate", "15");
        if (rate.compareTo(BigDecimal.ONE) > 0) rate = rate.movePointLeft(2);
        return catalogPrice.multiply(rate)
                .min(decimal(policy, "discountCapUSD", "50"))
                .setScale(6, RoundingMode.DOWN);
    }

    private boolean validExpectedAmount(BigDecimal expected) {
        return expected != null && expected.signum() >= 0 && expected.scale() <= 2
                && expected.compareTo(MAX_EXPECTED_AMOUNT) <= 0;
    }

    private void publishChargeAttempt(
            Long userId, TrialRow row, String trigger, String result, BigDecimal amount, String reason) {
        Attribution attr = requireAttribution(userId);
        Map<String, Object> detail = linked("trigger", trigger, "result", result,
                "amount_usdt", amount, "reason", reason, "payment_rail", "NEXION_USDT_WALLET");
        publish("TRIAL", row.claimNo(), "trial.charge_attempted", userId, attr, detail);
        record("H2_TRIAL_CHARGE_ATTEMPTED", row.claimNo(), userId, detail);
    }

    private Map<String, Object> project(
            Long userId, TrialRow row, Map<String, String> policy, LocalDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean trialGateEnabled = trialGateEnabled();
        boolean phaseOpen = flag(policy, "phaseOpen", true);
        boolean riskBlocked = mapper.trialCycleSignalCount(userId) > 0;
        String configuredProductCode = trialProductCode(policy, row);
        AppTrialLifecycleMapper.ConversionProduct catalogProduct = configuredProductCode == null
                ? null : mapper.catalogProduct(configuredProductCode);
        AppTrialLifecycleMapper.ConversionProduct startProduct = configuredProductCode == null
                ? null : mapper.conversionProduct(configuredProductCode);
        boolean productAvailable = availablePhysicalTrialProduct(startProduct);
        boolean quotaAvailable = nonNegativeInt(policy, "seatsLeftToday", 0) > 0;
        result.put("authoritative", true);
        result.put("serverCanonical", true);
        result.put("sourceEnvironment", "PRODUCTION");
        result.put("runId", "");
        result.put("serverNowEpochMs", now.atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        if (row == null) {
            result.put("state", "ELIGIBLE");
            boolean canStart = productAvailable && trialGateEnabled && phaseOpen && !riskBlocked && quotaAvailable;
            result.put("canStart", canStart);
            result.put("eligibilityReason", productAvailable
                    ? eligibilityReason("ELIGIBLE", canStart, trialGateEnabled, phaseOpen,
                            riskBlocked, quotaAvailable, null, now)
                    : "product-unavailable");
            result.put("trialGateEnabled", trialGateEnabled);
            result.put("version", 0L);
            putCanonicalProvenance(result, "nx_trial_claim");
            result.put("paymentRail", "NEXION_USDT_WALLET");
            result.put("config", safePolicy(policy, configuredProductCode,
                    catalogProduct == null ? null : catalogProduct.name(),
                    catalogProduct == null ? null : catalogProduct.priceUsdt()));
            return result;
        }
        String effectiveState = effectiveState(row, now);
        Settlement preview = active(effectiveState) ? settlement(row, policy, false, now) : null;
        int graceDays = nonNegativeInt(policy, "graceDays", 7);
        boolean canStart = restartable(effectiveState)
                && (row.cooldownUntil() == null || !row.cooldownUntil().isAfter(now))
                && productAvailable && trialGateEnabled && phaseOpen && !riskBlocked && quotaAvailable;
        result.put("claimNo", row.claimNo());
        result.put("state", effectiveState);
        result.put("canStart", canStart);
        result.put("eligibilityReason", !restartable(effectiveState) || productAvailable
                ? eligibilityReason(effectiveState, canStart, trialGateEnabled, phaseOpen, riskBlocked,
                        quotaAvailable, row.cooldownUntil(), now)
                : "product-unavailable");
        result.put("trialGateEnabled", trialGateEnabled);
        result.put("version", row.version());
        result.put("deviceName", row.deviceName());
        result.put("claimedAt", row.claimedAt());
        result.put("claimedAtEpochMs", epochMillis(row.claimedAt()));
        result.put("expiresAt", row.expiresAt());
        result.put("expiresAtEpochMs", epochMillis(row.expiresAt()));
        result.put("activeEndsAt", "EXTENDED".equals(effectiveState) ? null : row.expiresAt());
        LocalDateTime graceEndsAt = List.of("ACTIVE", "GRACE").contains(effectiveState)
                ? row.expiresAt().plusDays(graceDays) : null;
        LocalDateTime extendedEndsAt = "EXTENDED".equals(effectiveState) ? row.expiresAt() : null;
        result.put("graceEndsAt", graceEndsAt);
        result.put("graceEndsAtEpochMs", epochMillis(graceEndsAt));
        result.put("extendedEndsAt", extendedEndsAt);
        result.put("extendedEndsAtEpochMs", epochMillis(extendedEndsAt));
        result.put("cooldownUntil", row.cooldownUntil());
        result.put("cooldownUntilEpochMs", epochMillis(row.cooldownUntil()));
        result.put("shadowUsdt", preview == null ? safe(row.shadowAccruedUsdt()) : preview.shadowUsdt());
        result.put("shadowNex", preview == null ? safe(row.shadowAccruedNex()) : preview.shadowNex());
        result.put("offsetUsdt", preview == null ? BigDecimal.ZERO : preview.offsetUsdt());
        result.put("remainderUsdt", preview == null ? safe(row.remainderUsdt()) : preview.remainderUsdt());
        result.put("priceUsdt", row.priceUsdt());
        result.put("paymentRail", "NEXION_USDT_WALLET");
        putCanonicalProvenance(result, "nx_trial_claim + nx_user_wallet");
        boolean frozenProduct = !restartable(effectiveState);
        result.put("config", safePolicy(policy, configuredProductCode,
                frozenProduct ? row.deviceName() : catalogProduct == null ? null : catalogProduct.name(),
                frozenProduct ? row.priceUsdt() : catalogProduct == null ? null : catalogProduct.priceUsdt()));
        return result;
    }

    private Long epochMillis(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
    }

    private String eligibilityReason(
            String state, boolean canStart, boolean trialGateEnabled, boolean phaseOpen,
            boolean riskBlocked, boolean quotaAvailable, LocalDateTime cooldownUntil, LocalDateTime now) {
        if (!"ELIGIBLE".equals(state) && !PERSISTED_STATES.contains(normalize(state))) return "unknown";
        if (canStart) return null;
        if (active(state)) return "in-progress";
        if ("REDEEMED".equals(state)) return "converted";
        if (riskBlocked) return "risk";
        if (!trialGateEnabled || !phaseOpen) return "phase-closed";
        if (!quotaAvailable) return "quota-exhausted";
        if (cooldownUntil != null && cooldownUntil.isAfter(now)) return "used";
        return "unknown";
    }

    private String effectiveState(TrialRow row, LocalDateTime now) {
        String state = normalize(row.status());
        if (List.of("CLAIMED", "ACTIVE").contains(state)
                && row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            return "GRACE";
        }
        return state;
    }

    private Map<String, Object> safePolicy(
            Map<String, String> policy, String productCode, String productName, BigDecimal productPrice) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> defaults = linkedString(
                "trialDays", "3", "graceDays", "7", "extensionDays", "3",
                "discountRate", "0.15", "discountCapUSD", "20", "trialOffsetCapUSD", "50",
                "autoChargeAtEnd", "true", "highQualityThresholdUSD", "100",
                "trialProductId", CANONICAL_TRIAL_PRODUCT_ID, "trialPriceUSD", "1299",
                "shadowDailyUSD", "38.52", "shadowDailyNEX", "65", "cooldownDays", "30",
                "phaseOpen", "true", "autoPushEnabled", "true", "autoPushDelayMs", "1500",
                "autoPushCooldownHours", "24", "autoPushMaxPerSession", "1",
                "seatsLeftToday", "0");
        defaults.forEach((key, fallback) -> {
            if (List.of("phaseOpen", "autoPushEnabled", "autoChargeAtEnd").contains(key)) {
                result.put(key, flag(policy, key, Boolean.parseBoolean(fallback)));
            } else {
                result.put(key, policy.getOrDefault(key, fallback));
            }
        });
        if (StringUtils.hasText(productCode)) result.put("trialProductId", productCode);
        if (StringUtils.hasText(productName)) result.put("trialProductName", productName.trim());
        if (productPrice != null && productPrice.signum() > 0) {
            result.put("trialPriceUSD", productPrice.stripTrailingZeros().toPlainString());
        }
        return result;
    }

    private Map<String, String> linkedString(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private String trialProductCode(Map<String, String> policy) {
        String productCode = canonicalTrialProductId(policy.getOrDefault("trialProductId", CANONICAL_TRIAL_PRODUCT_ID));
        return productCode.matches("[A-Za-z0-9._-]{2,64}") ? productCode : null;
    }

    private String trialProductCode(Map<String, String> policy, TrialRow row) {
        if (row != null && !restartable(normalize(row.status()))) {
            String pinned = canonicalTrialProductId(snapshotValue(row.quotaSnapshot(), "productCode"));
            if (pinned.matches("[A-Za-z0-9._-]{2,64}")) return pinned;
        }
        return trialProductCode(policy);
    }

    private String snapshotValue(String snapshot, String key) {
        if (!StringUtils.hasText(snapshot) || !StringUtils.hasText(key)) return "";
        String prefix = key + "=";
        for (String token : snapshot.split(",")) {
            String trimmed = token.trim();
            if (trimmed.startsWith(prefix)) return trimmed.substring(prefix.length()).trim();
        }
        return "";
    }

    private String canonicalTrialProductId(String value) {
        String normalized = value == null ? "" : value.trim();
        return LEGACY_TRIAL_PRODUCT_ID.equals(normalized) ? CANONICAL_TRIAL_PRODUCT_ID : normalized;
    }

    private boolean availablePhysicalTrialProduct(AppTrialLifecycleMapper.ConversionProduct product) {
        return product != null
                && "FINITE".equalsIgnoreCase(product.inventoryMode())
                && Set.of("DEVICE", "SERVER").contains(normalize(product.productType()))
                && product.priceUsdt() != null
                && product.priceUsdt().signum() > 0
                && product.stock() != null
                && product.stock() > 0
                && StringUtils.hasText(product.name());
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
        if (userId == null || invalidUser(activeUser(userId, true))) throw new BizException(404, "USER_NOT_FOUND");
    }

    private Long activeUser(Long userId, boolean lock) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        boolean development = profiles.length == 1 && "dev".equalsIgnoreCase(profiles[0].trim());
        boolean production = profiles.length == 1 && "prod".equalsIgnoreCase(profiles[0].trim());
        if (production) return lock ? mapper.lockActiveUser(userId) : mapper.activeUser(userId);
        if (!development) return null;
        String countryCode = environment.getProperty("nexion.auth.development-passkey-account.country-code", "");
        String phone = environment.getProperty("nexion.auth.development-passkey-account.phone", "");
        countryCode = countryCode == null ? "" : countryCode.trim();
        phone = phone == null ? "" : phone.trim();
        if (countryCode.isBlank() || phone.isBlank()) return null;
        return lock ? mapper.lockDevelopmentUser(userId, countryCode, phone)
                : mapper.activeDevelopmentUser(userId, countryCode, phone);
    }

    private boolean invalidUser(Long resolvedUserId) {
        return resolvedUserId == null || resolvedUserId <= 0L;
    }

    private void putCanonicalProvenance(Map<String, Object> target, String source) {
        target.put("serverCanonical", true);
        target.put("source", source);
        target.put("sourceEnvironment", "PRODUCTION");
        target.put("runId", "");
        target.put("provenance", linked(
                "serverCanonical", true,
                "source", source,
                "sourceEnvironment", "PRODUCTION",
                "runId", ""));
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
        Map<String, String> policy = (rows == null ? List.<PolicyRow>of() : rows).stream().collect(Collectors.toMap(
                PolicyRow::policyKey, PolicyRow::currentValue, (left, right) -> right, LinkedHashMap::new));
        int dailyLimit = nonNegativeInt(policy, "seatsLeftToday", 0);
        LocalDate quotaDate = businessDate();
        mapper.ensureTrialQuotaDay(quotaDate, dailyLimit);
        Integer remaining = mapper.trialQuotaRemaining(quotaDate);
        policy.put("seatsLeftToday", Integer.toString(remaining == null ? 0 : Math.max(remaining, 0)));
        return policy;
    }

    private LocalDate businessDate() {
        return Instant.now(clock).atZone(BUSINESS_ZONE).toLocalDate();
    }

    private LocalDateTime businessNow() {
        return LocalDateTime.ofInstant(Instant.now(clock), BUSINESS_ZONE);
    }

    private boolean trialGateEnabled() {
        return KillSwitchState.enabled(
                Optional.ofNullable(mapper.emergencyValue(TRIAL_KILLSWITCH_KEY)),
                Optional.ofNullable(mapper.emergencyValue(TRIAL_LEGACY_KILLSWITCH_KEY)));
    }

    private int positiveInt(Map<String, String> policy, String key, int fallback) {
        try {
            return Math.max(1, new BigDecimal(policy.getOrDefault(key, String.valueOf(fallback))).intValueExact());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private int nonNegativeInt(Map<String, String> policy, String key, int fallback) {
        try {
            return Math.max(0, new BigDecimal(policy.getOrDefault(key, String.valueOf(fallback))).intValueExact());
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
        if (List.of("1", "true", "enabled", "on", "yes", "是", "开", "开启", "开放").contains(normalized)) return true;
        if (List.of("0", "false", "disabled", "off", "no", "否", "关", "关闭").contains(normalized)) return false;
        return false;
    }

    private boolean active(String status) {
        return ACTIVE_STATES.contains(normalize(status));
    }

    private boolean restartable(String status) {
        return RESTARTABLE_STATES.contains(normalize(status));
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
