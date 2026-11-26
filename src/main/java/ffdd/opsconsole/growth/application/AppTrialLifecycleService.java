package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.device.application.HostingCenterService;
import ffdd.opsconsole.device.application.TestCloudActivationAdmission;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.purchase.PurchasePaymentService;
import ffdd.opsconsole.finance.purchase.VerifiedPurchasePayment;
import ffdd.opsconsole.finance.purchase.PurchaseQuotaReservation;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.Attribution;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.PolicyRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialExternalPayment;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.WalletRow;
import ffdd.opsconsole.growth.mapper.TrialPurchaseOptionsMapper.Product;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.HardwareQuotaPurchaseGuard;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.capacity.MonthlyTargetProjection;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** H2 single source of truth: state, settlement, wallet/D4 and A4 all commit together. */
@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class AppTrialLifecycleService {
    @org.springframework.beans.factory.annotation.Autowired
    private HostingCenterService hostingCenters;

    @org.springframework.beans.factory.annotation.Autowired
    private ffdd.opsconsole.shared.canonical.BusinessProductImages productImages;

    @org.springframework.beans.factory.annotation.Autowired
    private TrialPurchaseOptions purchaseOptions;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<PurchasePaymentService> purchasePayments;

    private final ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade paymentAuthorization;

    // Compatibility constructor for existing isolated domain tests; runtime uses the full constructor.
    public AppTrialLifecycleService(
            SupportPaymentAttributionFacade paymentAttribution,
            AppTrialLifecycleMapper mapper,
            EarningsReleaseService earningsReleaseService,
            AdminIdempotencyService idempotency,
            TreasuryCoverageFacade coverageFacade,
            AuditLogService audit,
            EventOutboxService outbox,
            StorefrontProductReleasePolicy productReleasePolicy,
            CanonicalStateMapper canonicalStateMapper,
            Environment environment,
            Clock clock, TestCloudActivationAdmission testCloudAdmission) {
        this(null,paymentAttribution,mapper,earningsReleaseService,idempotency,coverageFacade,audit,outbox,productReleasePolicy,canonicalStateMapper,environment,clock,testCloudAdmission);
    }
    private static final ZoneId BUSINESS_ZONE = DateTimeFormatConfig.BUSINESS_ZONE;
    private static final String TRIAL_KILLSWITCH_KEY = "killswitch.trial";
    private static final String TRIAL_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.trial";
    private static final String TRIAL_CONVERSION_SOURCE = "nx_trial_claim + nx_order + nx_order_item";
    private static final BigDecimal MAX_EXPECTED_AMOUNT = new BigDecimal("1000000.00");
    private static final Duration MAX_AUTO_SETTLEMENT_DELAY = Duration.ofHours(24);
    private static final String EXTERNAL_PAYMENT_IN_PROGRESS = "TRIAL_EXTERNAL_PAYMENT_IN_PROGRESS";
    private static final String TRIAL_TERMS_CHANGED = "TRIAL_TERMS_CHANGED";
    private static final List<String> START_POLICY_KEYS = List.of(
            "trialProductId", "trialDays", "graceDays", "extensionDays", "trialOffsetCapUSD", "phaseOpen", "seatsLeftToday");
    private static final List<String> ACTIVE_STATES = List.of("CLAIMED", "ACTIVE", "GRACE", "EXTENDED");
    private static final List<String> RESTARTABLE_STATES = List.of("CANCELLED", "FAILED");
    private static final List<String> PERSISTED_STATES = List.of(
            "CLAIMED", "ACTIVE", "GRACE", "EXTENDED", "REDEEMED", "FAILED", "CANCELLED");
    private static final String LEGACY_TRIAL_PRODUCT_ID = "device-trial-standard";
    private static final String CANONICAL_TRIAL_PRODUCT_ID = "stellarbox-s1";
    /** FEAT-TRIAL05: an eligible purchase inside the original term settles the full claimed term. */
    public static final String FULL_TERM_POLICY = "FULL_TERM_ON_CONVERSION";
    public static final String ACCRUED_POLICY = "ACCRUED_ON_CONVERSION";
    public static final String ORDER_TRIAL_DEVICE_REQUIRES_CONVERSION = "ORDER_TRIAL_DEVICE_REQUIRES_CONVERSION";
    private static final String EARLY_DISCOUNT_KEY = "earlyPurchaseDiscountUSD";
    private static final String SETTLEMENT_POLICY_KEY = "settlementPolicy";

    @lombok.NonNull
    private final SupportPaymentAttributionFacade paymentAttribution;
    private final AppTrialLifecycleMapper mapper;
    private final EarningsReleaseService earningsReleaseService;
    private final AdminIdempotencyService idempotency;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final StorefrontProductReleasePolicy productReleasePolicy;
    private final CanonicalStateMapper canonicalStateMapper;
    private final Environment environment;
    private final Clock clock;
    @lombok.NonNull
    private final TestCloudActivationAdmission testCloudAdmission;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> state(Long userId) {
        if (userId == null || invalidUser(activeUser(userId, false))) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        LocalDateTime now = businessNow();
        Map<String, String> policy = policyMap(BusinessCalendar.legacy().dateAtStorage(now));
        TrialRow row = mapper.trial(userId);
        Map<String, Object> state = project(userId, row, policy, now, false);
        if (row != null && purchaseOptions != null) {
            state.put("originalPurchase", originalPurchase(row));
            if (state.get("conversionOrderNo") instanceof String orderNo) {
                var device = mapper.purchasedDevice(orderNo, userId, row.userDeviceId());
                if (device != null) device.put("imageUrl", purchaseOptions.image((String) device.get("productNo")));
                state.put("purchasedDevice", device);
            }
        }
        return withProductImage(ApiResult.ok(state));
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> purchaseOptions(Long userId) {
        if (userId == null || invalidUser(activeUser(userId, false))) return ApiResult.fail(404, "USER_NOT_FOUND");
        TrialRow row = mapper.trial(userId);
        var now = businessNow();
        if (row == null || !active(row.status()) || !withinConversionDeadline(conversionDeadline(row), now)) {
            return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        }
        var policy = policyMap();
        String original = trialProductCode(policy, row);
        if (original == null) return ApiResult.fail(409, "TRIAL_PRODUCT_REQUIRED");
        var codes = new java.util.LinkedHashSet<String>();
        codes.add(original);
        codes.addAll(purchaseOptions.selected());
        var options = new java.util.ArrayList<Map<String, Object>>();
        for (String code : codes) {
            boolean isOriginal = original.equals(code);
            boolean subcent = false;
            Product product = null;
            BigDecimal price = isOriginal ? row.priceUsdt() : null;
            Map<String, Object> quoteDetails = null;
            String quoteVersion = null;
            String reason;
            try {
                product = purchaseOptions.product(code);
                if (!isOriginal && product == null) continue;
                if (!isOriginal) price = product.priceUsdt();
                reason = isOriginal ? purchaseOptions.listingBlock(product) : purchaseOptions.otherListingBlock(product);
                if (!isOriginal && reason != null) continue;
                if (positiveOrNull(price) == null || positiveOrNull(row.dailyUsdt()) == null
                        || positiveOrNull(row.dailyNex()) == null) {
                    throw new BizException(409, "TRIAL_DAILY_YIELD_INVALID");
                }
                var quote = quote(row, policy, now, false, code, price, false, true);
                subcent = TrialPurchaseOptions.isPositiveSubcent(quote.amountUsdt());
                quoteDetails = decimalQuote(quote, price);
                quoteVersion = quote.version();
                if (reason == null) reason = purchaseOptions.stockOrConfigurationBlock(product);
                if (reason == null && !trialGateEnabled()) reason = "TRIAL_KILL_SWITCH_DISABLED";
                if (reason == null) {
                    if (!isOriginal) purchaseOptions.checkGate(userId, code, false);
                    HardwareQuotaPurchaseGuard.check(mapper, userId, code, 1, utcNow());
                }
            } catch (BizException ex) {
                reason = ex.getMessage();
            } catch (RuntimeException ex) {
                log.warn("Could not read trial purchase option for SKU {}", code, ex);
                reason = "TRIAL_PRODUCT_QUOTE_UNAVAILABLE";
            }
            var methods = isOriginal && !subcent
                    ? purchasePayments.getObject().availableTrialPaymentMethods() : List.of("WALLET");
            var option = linked("productNo", code, "name", isOriginal ? row.deviceName() : product == null ? code : product.name(),
                    "imageUrl", trialOptionImage(code), "originalTrialProduct", isOriginal,
                    "productType", product == null ? (isOriginal ? "DEVICE" : null) : product.productType(),
                    "inventoryMode", product == null ? (isOriginal ? "FINITE" : null) : product.inventoryMode(),
                    "priceUsdt", TrialPurchaseOptions.amount(price),
                    "paymentMethods", methods);
            if (isOriginal) option.put("storePriceUsdt", product == null ? null : TrialPurchaseOptions.amount(product.priceUsdt()));
            if (quoteDetails != null) {
                option.put("quote", quoteDetails);
                option.put("quoteVersion", quoteVersion);
            }
            option.put("canPurchase", reason == null);
            option.put("unavailableReason", reason);
            options.add(option);
        }
        return ApiResult.ok(linked("claimNo", row.claimNo(), "serverTime", now.atZone(BUSINESS_ZONE).toOffsetDateTime().toString(),
                "options", options));
    }

    private String trialOptionImage(String productNo) {
        try {
            return productImages.urlForSku(productNo);
        } catch (RuntimeException ex) {
            log.warn("Could not read trial purchase image for SKU {}", productNo, ex);
            return null;
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
    }

    private Map<String, Object> decimalQuote(Quote quote, BigDecimal price) {
        var view = quoteView(quote, price);
        view.replaceAll((key, value) -> value instanceof BigDecimal amount ? TrialPurchaseOptions.amount(amount) : value);
        return view;
    }

    /** Enrich the HTTP view only. A replay retains its original SKU and gets a newly signed image. */
    public ApiResult<Map<String, Object>> withProductImage(ApiResult<Map<String, Object>> receipt) {
        if (productImages == null || receipt.getCode() != 0 || receipt.getData() == null
                || !(receipt.getData().get("config") instanceof Map<?, ?> config)
                || !(config.get("trialProductId") instanceof String sku)) return receipt;
        Map<Object, Object> displayConfig = new LinkedHashMap<>(config);
        displayConfig.remove("imageUrl");
        String url = productImages.urls(List.of(sku)).get(sku);
        if (url != null) displayConfig.put("imageUrl", url);
        Map<String, Object> view = new LinkedHashMap<>(receipt.getData());
        view.put("config", displayConfig);
        return new ApiResult<>(receipt.getCode(), receipt.getMessage(), view);
    }

    @Transactional(readOnly = true)
    public TrialQuotaSnapshot dailyQuota() {
        LocalDate quotaDate = businessDate();
        try {
            Map<String, String> policy = configuredPolicyMap();
            String configuredLimit = policy.get("seatsLeftToday");
            int dailyLimit = nonNegativeInt(policy, "seatsLeftToday", -1);
            if (configuredLimit == null || !configuredLimit.matches("[0-9]+")
                    || dailyLimit < 0 || dailyLimit > 1_000_000) {
                return new TrialQuotaSnapshot(quotaDate, null);
            }
            Integer remaining = mapper.trialQuotaRemaining(quotaDate);
            // An unclaimed business day uses its configured limit without creating a counter.
            return new TrialQuotaSnapshot(quotaDate, remaining == null ? dailyLimit : Math.max(remaining, 0));
        } catch (RuntimeException ex) {
            // Do not mark a caller's configuration-write transaction rollback-only for a read failure.
            return new TrialQuotaSnapshot(quotaDate, null);
        }
    }

    public record TrialQuotaSnapshot(LocalDate businessDate, Integer remaining) {}

    public ApiResult<Map<String, Object>> command(Long userId, String kind, String key) {
        if (userId == null || invalidUser(activeUser(userId, false))) throw new BizException(404, "USER_NOT_FOUND");
        String operation = switch (kind) {
            case "start" -> "TRIAL_START";
            case "cancel" -> "TRIAL_CANCEL";
            default -> throw new BizException(422, "TRIAL_COMMAND_KIND_INVALID");
        };
        String scope = trialScope(operation, userId);
        String requestHash = idempotency.retainedRequestHash(scope, key);
        var result = linked("kind", kind, "commandKey", key.trim(), "status", "NOT_SEEN");
        if (requestHash == null) return ApiResult.ok(result);

        var recovery = idempotency.recoveryResult(scope, key, requestHash, ApiResult.class);
        if (recovery.status() == AdminIdempotencyService.RecoveryStatus.PROCESSING) {
            result.put("status", "PROCESSING");
        } else if (recovery.response() != null && recovery.response().getCode() == 0
                && recovery.response().getData() instanceof Map<?, ?> data && data.get("claimNo") instanceof String claimNo) {
            if ("cancel".equals(kind) && sha256("explicit").equals(requestHash)
                    && legacyOpsCancellation(userId, key, recovery.response())) return ApiResult.ok(result);
            result.put("status", "SUCCEEDED");
            result.put("claimNo", claimNo);
        } else if (recovery.response() != null && recovery.response().getCode() >= 400
                && recovery.response().getCode() < 500 && StringUtils.hasText(recovery.response().getMessage())) {
            result.put("status", "REJECTED");
            result.put("code", recovery.response().getCode());
            result.put("message", recovery.response().getMessage());
        } else {
            result.put("status", "INDETERMINATE");
        }
        return ApiResult.ok(result);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> start(
            Long userId, Long paymentMethodId, String deviceName, String idempotencyKey) {
        return start(userId, paymentMethodId, deviceName, null, null, idempotencyKey);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> start(
            Long userId, Long paymentMethodId, String deviceName, String trialProductId,
            String trialTermsVersion, String idempotencyKey) {
        // Preserve the exact hash used by already retained legacy commands.
        Object request = trialProductId == null && trialTermsVersion == null
                ? linked("paymentMethodId", paymentMethodId, "deviceName", deviceName)
                : List.of("confirmed-start", String.valueOf(paymentMethodId),
                        startFieldHash(deviceName), startFieldHash(trialProductId), startFieldHash(trialTermsVersion));
        return once("TRIAL_START", userId, idempotencyKey, request,
                () -> {
                    requireUser(userId);
                    return startOnce(userId, paymentMethodId, deviceName, trialProductId,
                            trialTermsVersion, idempotencyKey);
                });
    }

    private String startFieldHash(String value) {
        return value == null ? "" : sha256(value);
    }

    private ApiResult<Map<String, Object>> startOnce(
            Long userId, Long paymentMethodId, String deviceName, String trialProductId,
            String confirmedTermsVersion, String idempotencyKey) {
        if (!trialGateEnabled()) return ApiResult.fail(409, "TRIAL_KILL_SWITCH_DISABLED");
        LocalDateTime now = businessNow();
        LocalDate quotaDate = BusinessCalendar.legacy().dateAtStorage(now);
        Map<String, String> policy = startPolicyMap();
        if (!flag(policy, "phaseOpen", true)) return ApiResult.fail(409, "TRIAL_PHASE_CLOSED");
        if (mapper.trialCycleSignalCount(userId) > 0) return ApiResult.fail(409, "TRIAL_CYCLE_RISK_BLOCKED");
        if (paymentMethodId != null && mapper.lockUsablePaymentMethod(userId, paymentMethodId) == null) {
            return ApiResult.fail(409, "TRIAL_PAYMENT_METHOD_UNAVAILABLE");
        }
        TrialRow existing = mapper.lockTrial(userId);
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
        int graceDays = nonNegativeInt(policy, "graceDays", 7);
        int extensionDays = nonNegativeInt(policy, "extensionDays", 3);
        BigDecimal offsetCap = trialOffsetCap(policy);
        String productCode = trialProductCode(policy);
        if (trialProductId == null ? !StringUtils.hasText(deviceName)
                : !canonicalTrialProductId(trialProductId).equalsIgnoreCase(productCode)) {
            return ApiResult.fail(409, TRIAL_TERMS_CHANGED);
        }
        if (productCode == null) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_CONFIG_INVALID");
        }
        AppTrialLifecycleMapper.ConversionProduct product = mapper.lockTrialStartProduct(productCode);
        if (!availablePhysicalTrialProduct(product)) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        }
        if (trialProductId == null && !deviceName.trim().equals(product.name().trim())) {
            return ApiResult.fail(409, TRIAL_TERMS_CHANGED);
        }
        if (!productReleased(product)) return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_RELEASED");
        BigDecimal dailyUsdt = positiveOrNull(productDailyUsdt(product));
        BigDecimal dailyNex = positiveOrNull(product.dailyNex());
        if (confirmedTermsVersion != null && !confirmedTermsVersion.equals(
                trialTermsVersion(product, days, offsetCap, dailyUsdt, dailyNex))) {
            return ApiResult.fail(409, TRIAL_TERMS_CHANGED);
        }
        if (dailyUsdt == null || dailyNex == null) {
            return ApiResult.fail(409, "TRIAL_DAILY_YIELD_INVALID");
        }
        if (!withinPhysicalSlotCapacity(userId, product, true)) return ApiResult.fail(409, "TRIAL_CAPACITY_FULL");
        String normalizedDevice = product.name();
        BigDecimal price = product.priceUsdt();
        int dailyLimit = nonNegativeInt(policy, "seatsLeftToday", 0);
        if (dailyLimit <= 0) return ApiResult.fail(409, "TRIAL_QUOTA_EXHAUSTED");
        mapper.ensureTrialQuotaDay(quotaDate, dailyLimit);
        if (mapper.consumeTrialQuota(quotaDate) != 1) {
            return ApiResult.fail(409, "TRIAL_QUOTA_EXHAUSTED");
        }
        Integer seatsAfterClaimValue = mapper.lockedTrialQuotaRemaining(quotaDate);
        if (seatsAfterClaimValue == null || seatsAfterClaimValue < 0) {
            throw new BizException(503, "TRIAL_QUOTA_STATE_UNAVAILABLE");
        }
        int seatsAfterClaim = seatsAfterClaimValue;
        int seatsBeforeClaim = seatsAfterClaim + 1;
        String claimNo = "TRIAL-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String snapshot = "paymentRail=UVEL_USDT_WALLET,productCode=" + productCode
                + ",trialDays=" + days
                + ",graceDays=" + graceDays + ",extensionDays=" + extensionDays
                + ",dailyUsdtSource=" + dailyUsdtSource(product)
                + ",dailyNexSource=nx_product.daily_nex"
                + ",dailyUsdt=" + dailyUsdt + ",dailyNex=" + dailyNex
                + ",offsetCapUsdt=" + (offsetCap == null ? "UNLIMITED" : offsetCap) + ",priceUsdt=" + price
                + ",seatsLeftTodayBefore=" + seatsBeforeClaim + ",seatsLeftTodayAfter=" + seatsAfterClaim;
        int changed = existing == null
                ? mapper.insertTrial(userId, claimNo, idempotencyKey, paymentMethodId, normalizedDevice,
                        days, graceDays, extensionDays, dailyUsdt, dailyNex, offsetCap, price,
                        now, now.plusDays(days), snapshot)
                : mapper.restartTrial(existing.id(), existing.version(), claimNo, idempotencyKey,
                        paymentMethodId, normalizedDevice, days, graceDays, extensionDays,
                        dailyUsdt, dailyNex, offsetCap, price,
                        now, now.plusDays(days), snapshot);
        if (changed != 1) throw new BizException(409, "TRIAL_START_CONFLICT");
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", claimNo, "trial.started", userId, attr, linked(
                "trial_price_usdt", price, "trial_days", days, "payment_rail", "UVEL_USDT_WALLET"));
        record("H2_TRIAL_STARTED", claimNo, userId, linked("claimNo", claimNo, "policySnapshot", snapshot));
        Map<String, String> remainingPolicy = new LinkedHashMap<>(policy);
        remainingPolicy.put("seatsLeftToday", Integer.toString(seatsAfterClaim));
        return ApiResult.ok(project(userId, mapper.trial(userId), remainingPolicy, now, true));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId, String reason, String claimNo, String idempotencyKey) {
        String normalized = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        // Preserve the request hash for cancellation keys created before claim binding.
        Object request = claimNo == null ? normalized : linked("reason", normalized, "claimNo", claimNo);
        var result = once("TRIAL_CANCEL", userId, idempotencyKey, request,
                () -> {
                    requireUser(userId);
                    return List.of("unbind", "explicit").contains(normalized) ? cancelOnce(userId, normalized, claimNo, false)
                            : ApiResult.fail(422, "TRIAL_CANCEL_REASON_INVALID");
                });
        return claimNo == null && "explicit".equals(normalized) && legacyOpsCancellation(userId, idempotencyKey, result)
                ? ApiResult.fail(409, "IDEMPOTENCY_KEY_CONFLICT") : result;
    }

    private boolean legacyOpsCancellation(Long userId, String key, ApiResult<?> result) {
        return result.getCode() == 0 && result.getData() instanceof Map<?, ?> data
                && data.get("claimNo") instanceof String claimNo && mapper.legacyOpsCancelled(userId, claimNo, key.trim());
    }

    @Transactional(rollbackFor = Exception.class)
    ApiResult<Map<String, Object>> cancelFromOps(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_OPS_CANCEL", userId, idempotencyKey, "explicit",
                () -> cancelOnce(userId, "explicit", null, true));
    }

    private ApiResult<Map<String, Object>> cancelOnce(Long userId, String reason, String claimNo, boolean opsOverride) {
        TrialRow row = mapper.lockTrial(userId);
        if (claimNo != null && (row == null || !claimNo.equals(row.claimNo()))) {
            return ApiResult.fail(409, "TRIAL_CLAIM_MISMATCH");
        }
        if (row == null || !active(row.status())) return ApiResult.fail(409, "TRIAL_NOT_CANCELLABLE");
        if (!opsOverride) requireNoOriginalPurchase(row, false);
        return cancelClaim(userId, row, reason);
    }

    private TrialExternalPayment originalExternalPayment(TrialRow row) {
        var claim = new ExternalPaymentClaim(row.claimNo(), row.claimedAt().atZone(BUSINESS_ZONE).toInstant());
        Instant now = clock.instant();
        // Keep this a non-locking read under the caller's buyer lock, preserving delivery's intent -> buyer lock order.
        for (var payment : mapper.openTrialExternalPayments(row.userId())) {
            if (claim.matches(payment.claimNo(), payment.pricingAsOf())
                    && !purchasePayments.getObject().lateTrialPaymentCanReselect(payment.intentNo(), payment.paymentMethod(),
                            payment.deadline(), payment.providerDeadline(), payment.reason(), payment.status(), payment.paidAt(),
                            payment.confirmedAt(), now)) {
                return payment;
            }
        }
        return null;
    }

    private Map<String, Object> externalPurchase(TrialRow row, TrialExternalPayment payment) {
        String imageUrl = null;
        try {
            imageUrl = payment.imageSku() == null ? purchaseOptions.image(payment.productNo())
                    : purchaseOptions.snapshotImage(payment.imageSku(), payment.imageAssetId(), payment.imageObjectKey());
        } catch (RuntimeException unavailable) {
            log.warn("Could not read trial purchase image for intent {}", payment.intentNo(), unavailable);
        }
        return linked("kind", "EXTERNAL", "claimNo", row.claimNo(), "intentNo", payment.intentNo(),
                "productNo", payment.productNo(), "name", payment.deviceName() == null ? row.deviceName() : payment.deviceName(),
                "imageUrl", imageUrl, "paymentMethod", payment.paymentMethod(), "expectedAmountUsdt", payment.confirmedAmount(),
                "deadlineAt", PurchasePaymentService.effectivePaymentDeadline(payment.deadline(), payment.providerDeadline()).toString(),
                "status", payment.status());
    }

    private Map<String, Object> originalPurchase(TrialRow row) {
        if (row == null || !active(row.status())) return null;
        var payment = originalExternalPayment(row);
        if (payment == null) return purchaseOptions.balance(row.userId(), row.claimNo());
        return externalPurchase(row, payment);
    }

    public void requireNoOriginalPurchase(long userId, boolean externalEntry) {
        requireNoOriginalPurchase(mapper.trial(userId), externalEntry);
    }

    private void requireNoOriginalPurchase(TrialRow row, boolean externalEntry) {
        if (row == null || !active(row.status())) return;
        var payment = originalExternalPayment(row);
        if (payment != null) throw originalPurchaseConflict(externalPurchase(row, payment), externalEntry);
        if (purchaseOptions != null) {
            var pending = purchaseOptions.balance(row.userId(), row.claimNo());
            if (pending != null) throw originalPurchaseConflict(pending, externalEntry);
        }
    }

    private TrialPurchaseConflict originalPurchaseConflict(Map<String, Object> purchase, boolean externalEntry) {
        String code = "BALANCE".equals(purchase.get("kind")) ? "TRIAL_BALANCE_SUBMISSION_IN_PROGRESS"
                : externalEntry ? "PURCHASE_PAYMENT_EXISTING_INTENT_REQUIRED" : EXTERNAL_PAYMENT_IN_PROGRESS;
        return new TrialPurchaseConflict(code, purchase);
    }

    private Runnable prepareBalanceAdmission(Long userId, String productNo, BigDecimal amount, String version, String key) {
        requireUser(userId);
        TrialRow row = mapper.lockTrial(userId);
        String admittedClaim = purchaseOptions.balanceClaim(userId, key);
        if (row == null || !active(row.status()) || (admittedClaim != null && !row.claimNo().equals(admittedClaim))) {
            String claimNo = admittedClaim == null && row != null ? row.claimNo() : admittedClaim;
            throw new TrialPurchaseConflict("TRIAL_NOT_CONVERTIBLE", linked("claimNo", claimNo));
        }
        if (admittedClaim != null) return () -> {};
        requireNoOriginalPurchase(row, false);
        if (!withinConversionDeadline(conversionDeadline(row), businessNow())) {
            throw new TrialPurchaseConflict("TRIAL_NOT_CONVERTIBLE", linked("claimNo", row.claimNo()));
        }
        return () -> {
            var device = purchaseOptions.device(productNo);
            device.putAll(linked("kind", "BALANCE", "claimNo", row.claimNo(), "commandKey", key, "status", "PROCESSING",
                    "paymentMethod", "WALLET", "expectedAmountUsdt", amount == null ? null : amount.toPlainString(),
                    "quoteVersion", version));
            purchaseOptions.acceptBalance(userId, row.claimNo(), key, device);
        };
    }

    private ApiResult<Map<String, Object>> cancelClaim(Long userId, TrialRow row, String reason) {
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
        return ApiResult.ok(project(userId, mapper.trial(userId), policyMap(), now, true));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> extend(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_EXTEND", userId, idempotencyKey, "accept",
                () -> extendOnce(userId));
    }

    private ApiResult<Map<String, Object>> extendOnce(Long userId) {
        Map<String, String> policy = policyMap();
        TrialRow row = mapper.lockTrial(userId);
        if (row == null) return ApiResult.fail(409, "TRIAL_NOT_EXTENDABLE");
        requireNoOriginalPurchase(row, false);
        LocalDateTime now = businessNow();
        if ("ACTIVE".equals(normalize(row.status())) && !row.expiresAt().isAfter(now)) {
            if (mapper.enterGrace(row.id(), row.version(), now) != 1) throw new BizException(409, "TRIAL_GRACE_CONFLICT");
            row = mapper.lockTrial(userId);
        }
        if (!"GRACE".equals(normalize(row.status()))) return ApiResult.fail(409, "TRIAL_NOT_IN_GRACE");
        Settlement preview = settlement(row, policy, false, now);
        BigDecimal threshold = decimal(policy, "highQualityThresholdUSD", "100");
        if (preview.shadowUsdt().compareTo(threshold) < 0) return ApiResult.fail(409, "TRIAL_EXTENSION_THRESHOLD_NOT_MET");
        int days = Math.max(1, row.extensionDaysSnapshot());
        LocalDateTime extendedExpiresAt = row.expiresAt().plusDays((long) row.graceDaysSnapshot() + days);
        if (mapper.extendTrial(row.id(), row.version(), extendedExpiresAt, now) != 1) {
            throw new BizException(409, "TRIAL_EXTENSION_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        publish("TRIAL", row.claimNo(), "trial.extended", userId, attr,
                linked("extension_days", days, "shadow_usdt", preview.shadowUsdt()));
        record("H2_TRIAL_EXTENDED", row.claimNo(), userId,
                linked("extensionDays", days, "shadowUsdt", preview.shadowUsdt()));
        return ApiResult.ok(project(userId, mapper.trial(userId), policy, now, true));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> redeemEarly(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_REDEEM_EARLY", userId, idempotencyKey, "early",
                () -> redeemOnce(userId, true, "early"));
    }

    /** Converts the reserved trial slot into one authoritative catalogue order. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ApiResult<Map<String, Object>> convert(
            Long userId, String productNo, BigDecimal expectedAmountUsdt, String idempotencyKey) {
        return convert(userId, productNo, expectedAmountUsdt, null, idempotencyKey);
    }

    /** {@code quoteVersion} is the version the user confirmed; a different server quote needs a new confirmation. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ApiResult<Map<String, Object>> convert(
            Long userId, String productNo, BigDecimal expectedAmountUsdt, String quoteVersion, String idempotencyKey) {
        return convert(userId,productNo,expectedAmountUsdt,quoteVersion,idempotencyKey,null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ApiResult<Map<String, Object>> convert(Long userId,String productNo,BigDecimal expectedAmountUsdt,
            String quoteVersion,String idempotencyKey,
            ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade.Authorization authorization) {
        if (userId==null || userId<1) throw new BizException(404,"USER_NOT_FOUND");
        String normalized = productNo == null ? "" : productNo.trim();
        String confirmedQuote = StringUtils.hasText(quoteVersion) ? quoteVersion.trim() : null;
        Map<String, Object> request = linked("productNo", normalized, "expectedAmountUsdt", expectedAmountUsdt);
        if (confirmedQuote != null) request.put("quoteVersion", confirmedQuote);
        if (paymentAuthorization==null) return ApiResult.fail(503,"PAYMENT_PASSWORD_UNAVAILABLE");
        return paymentAuthorization.executeWithAdmission(userId, "trial-convert", idempotencyKey, request, authorization,
                () -> prepareBalanceAdmission(userId, canonicalTrialProductId(normalized), expectedAmountUsdt,
                        confirmedQuote, idempotencyKey),
                () -> {
                    testCloudAdmission.lock();
                    requireUser(userId);
                    return convertOnce(userId, normalized, expectedAmountUsdt, confirmedQuote, false, false, null,
                            purchaseOptions.balanceClaim(userId, idempotencyKey), Map.of());
                });
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> settleVerifiedExternal(Long owner,String product,BigDecimal expected,String version) {
        return settleVerifiedExternal(owner, product, expected, version, false, true, null, null, Map.of());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> settleVerifiedExternal(Long owner,String product,BigDecimal expected,String version,boolean legacyQuote,
                                                               PurchaseQuotaReservation reservation,String claimNo) {
        return settleVerifiedExternal(owner, product, expected, version, legacyQuote, true, reservation, claimNo, Map.of());
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> settleVerifiedExternal(Long owner, String product, BigDecimal expected, String version,
            boolean legacyQuote, boolean legacyVersion, PurchaseQuotaReservation reservation, String claimNo,
            Map<String, String> frozenPolicy) {
        if (!VerifiedPurchasePayment.active()) throw new BizException(403,"PURCHASE_PAYMENT_SCOPE_REQUIRED");
        testCloudAdmission.lock();
        requireUser(owner); return convertOnce(owner,product,expected,StringUtils.hasText(version)?version:null,legacyQuote,legacyVersion,reservation,claimNo,frozenPolicy);
    }
    @Transactional(readOnly=true)
    public ApiResult<Map<String,Object>> externalPaymentPricing(Long owner,Instant pricingInstant) {
        return externalPaymentPricing(owner,pricingInstant,false);
    }
    @Transactional(readOnly=true)
    public ApiResult<Map<String,Object>> externalPaymentPricing(Long owner,Instant pricingInstant,boolean legacyQuote) {
        return externalPaymentPricing(owner, pricingInstant, legacyQuote, true, Map.of());
    }
    @Transactional(readOnly=true)
    public ApiResult<Map<String,Object>> externalPaymentPricing(Long owner, Instant pricingInstant,
            boolean legacyQuote, boolean legacyVersion, Map<String, String> frozenPolicy) {
        if(owner==null||invalidUser(activeUser(owner,false)))return ApiResult.fail(404,"USER_NOT_FOUND");
        TrialRow row=mapper.trial(owner);
        if(row==null||!active(row.status()))return ApiResult.fail(409,"TRIAL_NOT_CONVERTIBLE");
        LocalDateTime pricingAt=LocalDateTime.ofInstant(pricingInstant,BUSINESS_ZONE);
        var livePolicy = policyMap();
        var policy = frozenPolicy.isEmpty() ? livePolicy : frozenPolicy;
        String productNo = trialProductCode(livePolicy, row);
        boolean fullTermWithoutDiscount = frozenPolicy.isEmpty()
                || FULL_TERM_POLICY.equals(frozenPolicy.get(SETTLEMENT_POLICY_KEY));
        var pricingQuote = quote(row, policy, pricingAt, legacyQuote, productNo, row.priceUsdt(),
                legacyVersion, fullTermWithoutDiscount);
        var frozen = quoteView(pricingQuote, row.priceUsdt());
        // Entering grace is the single automatic revision between the original term and grace.
        long pricingVersion="GRACE".equals(normalize(row.status())) && pricingAt.isBefore(row.expiresAt())
                ? row.version()-1 : row.version();
        frozen.put("sourceVersion",pricingVersion);frozen.put("claimNo",row.claimNo());
        if (!legacyVersion) {
            frozen.put("productNo", productNo);
            frozen.put("trialPricingPolicy", frozenPolicy.isEmpty() ? Map.of(
                    "discountRate", decimal(policy, "discountRate", "15").toPlainString(),
                    "discountCapUSD", decimal(policy, "discountCapUSD", "50").toPlainString(),
                    EARLY_DISCOUNT_KEY, earlyPurchaseDiscount(policy).toPlainString(),
                    SETTLEMENT_POLICY_KEY, pricingQuote.policy()) : frozenPolicy);
        }
        return ApiResult.ok(frozen);
    }

    public Map<String, Object> externalDeviceSnapshot(Long owner, String productNo, Map<String, Object> trialPricing) {
        TrialRow row = mapper.trial(owner);
        String code = externalPaymentProduct(row, productNo);
        var product = purchaseOptions.product(code);
        String name = product == null ? row.deviceName() : product.name();
        var breakdown = linked("productNo", trialPricing.get("productNo"), "productName", name,
                "priceUsdt", TrialPurchaseOptions.amount((BigDecimal) trialPricing.get("priceUsdt")),
                "promoDiscountUsdt", TrialPurchaseOptions.amount((BigDecimal) trialPricing.get("promoDiscountUsdt")),
                "offsetUsdt", TrialPurchaseOptions.amount((BigDecimal) trialPricing.get("offsetUsdt")),
                "earlyDiscountUsdt", TrialPurchaseOptions.amount((BigDecimal) trialPricing.get("earlyDiscountUsdt")),
                "amountUsdt", TrialPurchaseOptions.amount((BigDecimal) trialPricing.get("amountUsdt")));
        if (product != null && StringUtils.hasText(product.tagline())) breakdown.put("productSubtitle", product.tagline());
        var snapshot = linked("trialDeviceName", name, "trialPurchaseBreakdown", breakdown);
        var artwork = purchaseOptions.artwork(code);
        if (artwork != null) snapshot.putAll(linked("trialImageSku", artwork.sku(),
                "trialImageAssetId", artwork.assetId(), "trialImageObjectKey", artwork.objectKey()));
        return snapshot;
    }

    public ExternalPaymentClaim currentExternalPaymentClaim(Long owner) {
        TrialRow row = mapper.trial(owner);
        return row == null ? null : new ExternalPaymentClaim(row.claimNo(), row.claimedAt().atZone(BUSINESS_ZONE).toInstant());
    }

    public record ExternalPaymentClaim(String claimNo, Instant claimedAt) {
        public boolean matches(String snapshotClaimNo, String pricingAsOf) {
            if (snapshotClaimNo != null) return claimNo.equals(snapshotClaimNo);
            // Older intents carry the quote instant. A restarted claim starts after the old quote.
            return StringUtils.hasText(pricingAsOf) && !Instant.parse(pricingAsOf).isBefore(claimedAt);
        }
    }

    public Instant requireExternalPaymentConversion(Long owner) {
        if (!trialGateEnabled()) throw new BizException(409, "TRIAL_KILL_SWITCH_DISABLED");
        TrialRow row = mapper.trial(owner);
        LocalDateTime deadline = row == null || !active(row.status()) ? null : conversionDeadline(row);
        if (!withinConversionDeadline(deadline, businessNow())) {
            throw new BizException(409, "TRIAL_NOT_CONVERTIBLE");
        }
        return deadline.atZone(BUSINESS_ZONE).toInstant();
    }

    /** The ordinary purchase transaction holds the buyer lock used by trial start and close. */
    public boolean requiresOrdinaryOrderConversion(long userId, String productNo) {
        TrialRow row = mapper.trial(userId);
        if (row == null || !active(row.status()) || !withinConversionDeadline(conversionDeadline(row), businessNow())) {
            return false;
        }
        String pinned = pinnedTrialProductCode(row);
        String originalProductNo = pinned == null ? trialProductCode(configuredPolicyMap()) : pinned;
        return canonicalTrialProductId(productNo).equalsIgnoreCase(originalProductNo);
    }

    public void requireExternalPaymentProduct(Long owner, String productNo) {
        String code = externalPaymentProduct(mapper.trial(owner), productNo);
        var product = purchaseOptions.product(code);
        String reason = purchaseOptions.listingBlock(product);
        if (reason == null) reason = purchaseOptions.stockOrConfigurationBlock(product);
        if (reason != null) throw new BizException(409, reason);
    }

    public void checkExternalPaymentQuota(Long owner, String requestedProductNo, LocalDateTime occurredAtUtc) {
        HardwareQuotaPurchaseGuard.check(mapper, owner, externalPaymentProduct(mapper.trial(owner), requestedProductNo),
                1, occurredAtUtc);
    }

    public AppTrialLifecycleMapper.ConversionProduct lockExternalPaymentProduct(Long owner, String requestedProductNo) {
        String configured = externalPaymentProduct(mapper.lockTrial(owner), requestedProductNo);
        var product = mapper.lockConversionProduct(configured);
        if (!availablePhysicalTrialProduct(product)) throw new BizException(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        requireExternalPaymentProduct(owner, requestedProductNo);
        return product;
    }

    private String externalPaymentProduct(TrialRow row, String requestedProductNo) {
        if (row == null || !active(row.status())) throw new BizException(409, "TRIAL_NOT_CONVERTIBLE");
        String configured = trialProductCode(policyMap(), row);
        requireExternalPaymentProduct(requestedProductNo, configured);
        return configured;
    }

    public static void requireExternalPaymentProduct(String requestedProductNo, String originalProductNo) {
        if (requestedProductNo == null || !requestedProductNo.matches("[A-Za-z0-9._-]{2,64}")) {
            throw new BizException(422, "TRIAL_PRODUCT_REQUIRED");
        }
        if (!canonicalTrialProductId(originalProductNo).equals(canonicalTrialProductId(requestedProductNo))) {
            throw new BizException(409, "TRIAL_EXTERNAL_PAYMENT_METHOD_UNSUPPORTED");
        }
    }

    private ApiResult<Map<String, Object>> convertOnce(
            Long userId, String productNo, BigDecimal expectedAmountUsdt, String confirmedQuote, boolean legacyQuote, boolean legacyVersion,
            PurchaseQuotaReservation reservation, String paymentClaimNo, Map<String, String> frozenPolicy) {
        if (!trialGateEnabled()) return ApiResult.fail(409, "TRIAL_KILL_SWITCH_DISABLED");
        if (!TrialPurchaseOptions.validProductNo(productNo)) return ApiResult.fail(422, "TRIAL_PRODUCT_REQUIRED");
        if (!validExpectedAmount(expectedAmountUsdt)) {
            return ApiResult.fail(409, "TRIAL_AMOUNT_INVALID");
        }
        if (confirmedQuote != null && confirmedQuote.length() > 96) return ApiResult.fail(422, "TRIAL_QUOTE_VERSION_INVALID");
        Map<String, String> policy = policyMap();
        var prepared = VerifiedPurchasePayment.active() ? null : paymentAttribution.prepare(userId, Source.TRIAL_CONVERT, "USER:" + userId);
        TrialRow row = mapper.lockTrial(userId);
        if (row == null || !active(row.status())) return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        if (paymentClaimNo != null && !paymentClaimNo.equals(row.claimNo())) return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        String configured = trialProductCode(policy, row);
        String canonicalRequested = canonicalTrialProductId(productNo);
        boolean original = canonicalRequested.equals(configured);
        if (configured == null || (!original && (VerifiedPurchasePayment.active()
                || !purchaseOptions.containsForPurchase(canonicalRequested)))) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_ELIGIBLE");
        }
        LocalDateTime now = businessNow();
        LocalDateTime paidAt = VerifiedPurchasePayment.active()
                ? LocalDateTime.ofInstant(VerifiedPurchasePayment.paidInstant(), BUSINESS_ZONE) : now;
        if (!withinConversionDeadline(conversionDeadline(row), paidAt)) {
            return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        }
        AppTrialLifecycleMapper.ConversionProduct product = mapper.lockConversionProduct(canonicalRequested);
        var selected = purchaseOptions.product(canonicalRequested);
        String reason = original ? purchaseOptions.listingBlock(selected) : purchaseOptions.otherListingBlock(selected);
        if (reason == null) reason = purchaseOptions.stockOrConfigurationBlock(selected);
        if (reason != null) return ApiResult.fail(409, reason);
        if (product == null || (original && !availablePhysicalTrialProduct(product))) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        }
        var gate = original ? null : purchaseOptions.checkGate(userId, product.productNo(), true);
        boolean share = "SHARE".equalsIgnoreCase(product.productType());
        if (positiveOrNull(row.dailyUsdt()) == null || positiveOrNull(row.dailyNex()) == null) {
            return ApiResult.fail(409, "TRIAL_DAILY_YIELD_INVALID");
        }
        if (!withinPhysicalSlotCapacity(userId, product, true)) {
            return ApiResult.fail(409, "CAPACITY_REPLACEMENT_REQUIRED");
        }
        BigDecimal subtotal = original ? row.priceUsdt() : product.priceUsdt();
        if (subtotal == null || subtotal.signum() <= 0) {
            throw new BizException(409, "TRIAL_PRICE_SNAPSHOT_INVALID");
        }
        Quote quote = quote(row, frozenPolicy.isEmpty() ? policy : frozenPolicy, VerifiedPurchasePayment.active()
                ? LocalDateTime.ofInstant(VerifiedPurchasePayment.pricingInstant(),BUSINESS_ZONE) : now, legacyQuote,
                product.productNo(), subtotal, legacyVersion, !VerifiedPurchasePayment.active()
                        || FULL_TERM_POLICY.equals(frozenPolicy.get(SETTLEMENT_POLICY_KEY)));
        Settlement settlement = quote.accrued();
        BigDecimal promoDiscount = quote.promoDiscountUsdt();
        BigDecimal discount = quote.discountUsdt();
        BigDecimal amount = quote.amountUsdt();
        if ((!legacyVersion || confirmedQuote != null) && !quote.version().equals(confirmedQuote)) {
            return ApiResult.fail(409, "TRIAL_QUOTE_CHANGED", linked("claimNo", row.claimNo(),
                    "productNo", product.productNo(), "quoteVersion", quote.version(), "quote", decimalQuote(quote, subtotal)));
        }
        // The App confirms a two-decimal USDT quote. Reject only when the
        // canonical two-decimal amount increased; a later/larger trial offset
        // may safely reduce what the user owes.
        if (amount.setScale(2, RoundingMode.HALF_UP)
                .compareTo(expectedAmountUsdt.setScale(2, RoundingMode.UNNECESSARY)) > 0) {
            return ApiResult.fail(409, "TRIAL_AMOUNT_MISMATCH", linked("claimNo", row.claimNo(),
                    "productNo", product.productNo(), "quoteVersion", quote.version(), "quote", decimalQuote(quote, subtotal)));
        }
        String hostingCenter = hostingCenters.requirePool().assign("trial:" + row.claimNo());
        testCloudAdmission.requireCapacity(1);
        requireCoverage(quote.remainderUsdt(), quote.rewardNex());
        if (reservation != null) reservation.requireSingle(product.id(), product.productNo());
        var hardwareQuota = reservation == null ? HardwareQuotaPurchaseGuard.reserve(mapper, userId, product.productNo(),
                LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)) : null;
        WalletRow wallet = mapper.lockWallet(userId);
        if (wallet == null) return ApiResult.fail(409, "TRIAL_WALLET_UNAVAILABLE");
        if (VerifiedPurchasePayment.active()) VerifiedPurchasePayment.require(userId,amount);
        if (!VerifiedPurchasePayment.active() && wallet.usdt().compareTo(amount) < 0) return ApiResult.fail(409, "TRIAL_WALLET_INSUFFICIENT");
        if (!VerifiedPurchasePayment.active()) paymentAttribution.beforeCash(prepared);
        // Zero-value conversions still need the wallet journal observation used by the fund receipt.
        if (!VerifiedPurchasePayment.active() && mapper.settleWallet(userId, amount, BigDecimal.ZERO, BigDecimal.ZERO) != 1) {
            throw new BizException(409, "TRIAL_WALLET_CONFLICT");
        }
        if (quote.remainderUsdt().signum() > 0) {
            String entryNo = earningsReleaseService.creditRewardInFundOperation(userId, "H2_TRIAL_REMAINDER", row.claimNo() + ":REMAINDER",
                    "USDT", quote.remainderUsdt(), "H2:" + row.claimNo() + ":REMAINDER:USDT");
            if (!VerifiedPurchasePayment.active()) paymentAttribution.trialReward(prepared, entryNo);
        }
        if (quote.rewardNex().signum() > 0) {
            String entryNo = earningsReleaseService.creditRewardInFundOperation(userId, "H2_TRIAL_BONUS", row.claimNo() + ":NEX",
                    "UVT", quote.rewardNex(), "H2:" + row.claimNo() + ":NEX");
            if (!VerifiedPurchasePayment.active()) paymentAttribution.trialReward(prepared, entryNo);
        }
        BigDecimal usdtAfter = wallet.usdt().subtract(VerifiedPurchasePayment.active() ? BigDecimal.ZERO : amount).add(quote.remainderUsdt());
        BigDecimal nexAfter = wallet.nex().add(quote.rewardNex());
        if (!VerifiedPurchasePayment.active() && amount.signum() > 0 && paymentAttribution.insertLedger(prepared, amount,
                wallet.usdt().subtract(amount), "H2 conversion via UVEL USDT wallet") != 1) {
            throw new BizException(409, "TRIAL_CHARGE_LEDGER_CONFLICT");
        }
        if (quote.remainderUsdt().signum() > 0) mapper.insertLedger(
                userId, row.claimNo() + ":REMAINDER", "TRIAL_BONUS", "USDT", "IN",
                quote.remainderUsdt(), usdtAfter, "H2 shadow remainder credited after purchase");
        if (quote.rewardNex().signum() > 0) mapper.insertLedger(
                userId, row.claimNo() + ":NEX", "TRIAL_BONUS", "UVT", "IN",
                quote.rewardNex(), nexAfter, "H2 shadow UVT credited after purchase");
        String orderNo = "TRC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (mapper.decrementProductStock(product.id()) != 1) throw new BizException(409, "TRIAL_PRODUCT_STOCK_CONFLICT");
        if (mapper.insertConversionOrder(userId, orderNo, product.id(), subtotal, discount, amount) != 1
                || mapper.insertConversionOrderItem(orderNo, product.id(), product.productNo(), product.name(),
                        subtotal) != 1) {
            throw new BizException(409, "TRIAL_CONVERSION_ORDER_CONFLICT");
        }
        if (gate != null) purchaseOptions.consumeGate(gate, orderNo, product.id());
        String instanceNo = "TRIAL-DEV-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        if (hardwareQuota != null) HardwareQuotaPurchaseGuard.record(mapper, hardwareQuota, userId, orderNo);
        if (mapper.insertPurchasedDevice(userId, orderNo, product.id(), product.productNo(), product.tier(),
                normalize(product.productType()),
                instanceNo, product.name(), subtotal, hostingCenter) != 1) {
            throw new BizException(409, "TRIAL_DEVICE_CREATE_CONFLICT");
        }
        Long deviceId = mapper.deviceIdByInstanceNo(instanceNo);
        String paymentRail = VerifiedPurchasePayment.active() ? VerifiedPurchasePayment.method() : "UVEL_USDT_WALLET";
        String snapshot = settlement.snapshot("convert", paymentRail, product.productNo(),quote.offsetUsdt(),quote.remainderUsdt())
                + ",orderNo=" + orderNo + ",promoDiscountUsdt=" + promoDiscount
                + ",totalDiscountUsdt=" + discount + ",amountUsdt=" + amount
                + ",trialDailyUsdt=" + row.dailyUsdt() + ",trialDailyNex=" + row.dailyNex()
                + ",deviceDailyUsdt=" + product.estimatedDailyUsdt() + ",deviceDailyNex=" + product.dailyNex()
                + quote.snapshot();
        // shadow_accrued_* keep the real accrual; the credited totals live in remainder_usdt / settled_trial_nex.
        if (deviceId == null || mapper.markRedeemed(row.id(), row.version(), deviceId,
                settlement.shadowUsdt(), settlement.shadowNex(), quote.remainderUsdt(), quote.rewardNex(), discount,
                amount, now, snapshot) != 1) {
            throw new BizException(409, "TRIAL_CONVERSION_CONFLICT");
        }
        Attribution attr = requireAttribution(userId);
        Map<String, Object> detail = linked("orderNo", orderNo, "productNo", product.productNo(),
                "amountUsdt", amount, "discountUsdt", discount, "paymentStatus", "PAID",
                "orderStatus", "PAID", "paymentRail", paymentRail,
                "deviceId", deviceId, "shadowUsdt", settlement.shadowUsdt(),
                "shadowNex", settlement.shadowNex(), "offsetUsdt", quote.offsetUsdt(),
                "remainderUsdt", quote.remainderUsdt(), "discountApplied", promoDiscount,
                "earlyPurchase", quote.early(), "sourceEnvironment", "PRODUCTION");
        detail.putAll(quote.receiptFields());
        putCanonicalProvenance(detail, TRIAL_CONVERSION_SOURCE);
        Map<String, Object> eventDetail = new LinkedHashMap<>(detail);
        eventDetail.keySet().removeAll(Set.of("serverCanonical", "sourceEnvironment", "runId", "provenance"));
        // The registered trial.redeemed payload stays unchanged; the quote breakdown lives in the receipt and audit.
        eventDetail.keySet().removeAll(quote.receiptFields().keySet());
        publish("TRIAL", row.claimNo(), "trial.redeemed", userId, attr, eventDetail);
        publish("ORDER", orderNo, "checkout.completed", userId, attr,
                linked("order_id", orderNo, "order_no", orderNo, "order_subtotal_usdt", amount, "amount_usdt", amount));
        record("H2_TRIAL_CONVERTED", row.claimNo(), userId, detail);
        if (!VerifiedPurchasePayment.active()) paymentAttribution.record(prepared);
        if (!VerifiedPurchasePayment.active()) detail.put("paidTotalUsdt",amount.setScale(2,RoundingMode.HALF_UP).toPlainString());
        detail.put("claimNo", row.claimNo());
        detail.put("purchasedDevice", linked("productNo", product.productNo(), "name", product.name(),
                "productType", product.productType(), "imageUrl", purchaseOptions.image(product.productNo())));
        detail.put("settlement", decimalQuote(quote, subtotal));
        return ApiResult.ok(detail);
    }

    private LocalDateTime conversionDeadline(TrialRow row) {
        if (row.expiresAt() == null) return null;
        return "EXTENDED".equals(normalize(row.status())) ? row.expiresAt()
                : row.expiresAt().plusDays(row.graceDaysSnapshot());
    }

    private boolean withinConversionDeadline(LocalDateTime deadline, LocalDateTime now) {
        return deadline != null && now.isBefore(deadline);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> charge(Long userId, String idempotencyKey) {
        requireUser(userId);
        return once("TRIAL_CHARGE", userId, idempotencyKey, "auto",
                () -> redeemOnce(userId, false, "auto"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> settleDue(
            Long userId, String expectedClaimNo, String idempotencyKey) {
        requireUser(userId);
        TrialRow row = mapper.lockTrial(userId);
        // The scheduler reuses one key per due claim. Leave a transient payment hold outside the stored result.
        if (row != null && active(row.status()) && row.claimNo().equals(expectedClaimNo)
                && originalExternalPayment(row) != null) {
            return ApiResult.fail(409, EXTERNAL_PAYMENT_IN_PROGRESS);
        }
        return once("TRIAL_SETTLE_DUE", userId, idempotencyKey, expectedClaimNo,
                () -> settleDueOnce(userId, expectedClaimNo, row));
    }

    private ApiResult<Map<String, Object>> settleDueOnce(Long userId, String expectedClaimNo, TrialRow row) {
        if (!trialGateEnabled()) return ApiResult.fail(409, "TRIAL_KILL_SWITCH_DISABLED");
        if (row == null || !active(row.status()) || !row.claimNo().equals(expectedClaimNo)) {
            return ApiResult.fail(409, "TRIAL_DUE_STATE_CONFLICT");
        }
        Map<String, String> policy = policyMap();
        LocalDateTime now = businessNow();
        String state = normalize(row.status());
        if (row.expiresAt() == null) return ApiResult.fail(409, "TRIAL_DUE_TIME_MISSING");
        LocalDateTime dueAt = "EXTENDED".equals(state)
                ? row.expiresAt()
                : row.expiresAt().plusDays(row.graceDaysSnapshot());
        if (dueAt.isAfter(now)) return ApiResult.fail(409, "TRIAL_NOT_DUE");
        // FEAT-TRIAL02: expiry may close a trial, never authorize a purchase.
        return cancelClaim(userId, row, flag(policy, "autoChargeAtEnd", false) && now.isAfter(dueAt.plus(MAX_AUTO_SETTLEMENT_DELAY))
                ? "auto_settlement_window_expired" : "auto_end");
    }

    /** Retired card-era endpoint: only convert() may execute a user-confirmed purchase. */
    private ApiResult<Map<String, Object>> redeemOnce(Long userId, boolean early, String trigger) {
        if (!trialGateEnabled()) return ApiResult.fail(409, "TRIAL_KILL_SWITCH_DISABLED");
        return ApiResult.fail(410, "TRIAL_EXPLICIT_PURCHASE_REQUIRED");
    }

    private Settlement settlement(TrialRow row, Map<String, String> policy, boolean early, LocalDateTime now) {
        long seconds = Math.max(0, Duration.between(row.claimedAt(), now).getSeconds());
        BigDecimal days = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86400), 8, RoundingMode.DOWN);
        BigDecimal maxDays = BigDecimal.valueOf(Math.max(1, row.durationDays() == null ? 1 : row.durationDays()));
        days = days.min(maxDays);
        BigDecimal shadowUsdt = row.dailyUsdt().multiply(days).setScale(6, RoundingMode.DOWN);
        BigDecimal shadowNex = row.dailyNex().multiply(days).setScale(6, RoundingMode.DOWN);
        BigDecimal offset = row.offsetCapUsdt() == null ? shadowUsdt : shadowUsdt.min(row.offsetCapUsdt());
        BigDecimal remainder = shadowUsdt.subtract(offset).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        BigDecimal discount = early ? conversionPromoDiscount(row.priceUsdt(), policy) : BigDecimal.ZERO;
        BigDecimal charge = row.priceUsdt().subtract(discount).subtract(offset)
                .max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        return new Settlement(shadowUsdt, shadowNex, offset, remainder, discount, charge);
    }

    /**
     * FEAT-TRIAL05 conversion quote. Eligible purchases before the original expiry settle the whole
     * claimed term. The fixed discount applies to the remaining purchase price. An external payment
     * frozen under the previous rule still requires a positive discount to settle the whole term.
     */
    private Quote quote(TrialRow row, Map<String, String> policy, LocalDateTime now, boolean legacyQuote,
            String productNo, BigDecimal subtotal, boolean legacyVersion, boolean fullTermWithoutDiscount) {
        Settlement accrued = settlement(row, policy, false, now);
        BigDecimal configured = earlyPurchaseDiscount(policy);
        boolean early = fullTermSnapshot(row) && inOriginalTerm(row, now)
                && (configured.signum() > 0 || (fullTermWithoutDiscount && !legacyVersion));
        BigDecimal settledUsdt = accrued.shadowUsdt();
        BigDecimal settledNex = accrued.shadowNex();
        if (early) {
            BigDecimal days = BigDecimal.valueOf(row.durationDays());
            settledUsdt = row.dailyUsdt().multiply(days).setScale(6, RoundingMode.DOWN).max(settledUsdt);
            settledNex = row.dailyNex().multiply(days).setScale(6, RoundingMode.DOWN).max(settledNex);
        }
        BigDecimal promo = conversionPromoDiscount(subtotal, policy);
        // Previously issued external payment quotes retain the exact discount and reward they froze.
        BigDecimal offset = legacyQuote && row.offsetCapUsdt()!=null ? settledUsdt.min(row.offsetCapUsdt()) :
                accrued.offsetUsdt().min(subtotal.subtract(promo).max(BigDecimal.ZERO));
        offset = offset.setScale(6, RoundingMode.DOWN);
        BigDecimal remainder = settledUsdt.subtract(offset).setScale(6, RoundingMode.DOWN);
        BigDecimal baseDiscount = promo.add(offset).min(subtotal).setScale(6, RoundingMode.DOWN);
        BigDecimal base = subtotal.subtract(baseDiscount).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        // Bounded by what is left to pay: never a negative price, never paid out as cash.
        BigDecimal earlyDiscount = early ? configured.min(base).setScale(6, RoundingMode.DOWN)
                : BigDecimal.ZERO.setScale(6);
        BigDecimal amount = base.subtract(earlyDiscount).setScale(6, RoundingMode.DOWN);
        String policyName = early ? FULL_TERM_POLICY : ACCRUED_POLICY;
        // Confirm the claim and pricing rule. Accrual alone can only improve the confirmed price.
        String version = policyName;
        if (!legacyVersion) {
            String basis = String.join("|", row.claimNo(), productNo, TrialPurchaseOptions.amount(subtotal),
                    String.valueOf(row.offsetCapUsdt()), conversionPromoRate(policy).stripTrailingZeros().toPlainString(),
                    decimal(policy, "discountCapUSD", "50").stripTrailingZeros().toPlainString(), promo.toPlainString(), configured.toPlainString());
            version += ":" + sha256(basis).substring(0, 24);
        } else if (early) {
            String basis = legacyQuote ? String.join("|",row.claimNo(),settledUsdt.toPlainString(),settledNex.toPlainString(),
                    promo.toPlainString(),earlyDiscount.toPlainString(),amount.toPlainString())
                    : String.join("|","ACCRUED_OFFSET",row.claimNo(),settledUsdt.toPlainString(),settledNex.toPlainString(),
                    promo.toPlainString(),configured.toPlainString(),String.valueOf(row.offsetCapUsdt()));
            version += ":" + sha256(basis).substring(0,16);
        }
        return new Quote(accrued, policyName, version, early, settledUsdt, settledNex,
                settledUsdt.subtract(accrued.shadowUsdt()), settledNex.subtract(accrued.shadowNex()),
                offset, remainder, promo, configured, earlyDiscount, baseDiscount.add(earlyDiscount), amount,
                early ? row.expiresAt() : null);
    }

    private BigDecimal earlyPurchaseDiscount(Map<String, String> policy) {
        BigDecimal value = decimal(policy, EARLY_DISCOUNT_KEY, "0");
        return value.signum() > 0 ? value.setScale(2, RoundingMode.DOWN) : BigDecimal.ZERO.setScale(2);
    }

    private boolean fullTermSnapshot(TrialRow row) {
        return row.durationDays() != null && row.durationDays() > 0
                && positiveOrNull(row.dailyUsdt()) != null && positiveOrNull(row.dailyNex()) != null
                && (row.offsetCapUsdt() == null || row.offsetCapUsdt().signum() >= 0)
                && row.priceUsdt() != null && row.priceUsdt().signum() > 0
                && row.claimedAt() != null && row.expiresAt() != null;
    }

    /** Server time, not the scheduler-advanced status, decides whether the original term is still running. */
    private boolean inOriginalTerm(TrialRow row, LocalDateTime now) {
        return List.of("CLAIMED", "ACTIVE", "GRACE").contains(normalize(row.status()))
                && row.expiresAt() != null && now.isBefore(row.expiresAt());
    }

    private BigDecimal conversionPromoDiscount(BigDecimal catalogPrice, Map<String, String> policy) {
        return catalogPrice.multiply(conversionPromoRate(policy))
                .min(decimal(policy, "discountCapUSD", "50"))
                .setScale(6, RoundingMode.DOWN);
    }

    private BigDecimal conversionPromoRate(Map<String, String> policy) {
        return decimal(policy, "discountRate", "15").movePointLeft(2);
    }

    /** 商品未配收益时列为 0/NULL —— 只有正值才算「有权威收益」。 */
    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static BigDecimal productDailyUsdt(AppTrialLifecycleMapper.ConversionProduct product) {
        if (product == null) return null;
        return product.monthlyTargetUsdt() == null
                ? product.estimatedDailyUsdt() : MonthlyTargetProjection.daily(product.monthlyTargetUsdt());
    }

    private static String dailyUsdtSource(AppTrialLifecycleMapper.ConversionProduct product) {
        return product.monthlyTargetUsdt() == null ? "nx_product.estimated_daily_usdt" : "nx_product.monthly_target_usdt";
    }

    private static BigDecimal trialOffsetCap(Map<String, String> policy) {
        String configuredCap = policy.get("trialOffsetCapUSD");
        return StringUtils.hasText(configuredCap) ? new BigDecimal(configuredCap).setScale(6, RoundingMode.HALF_UP) : null;
    }

    private String trialTermsVersion(AppTrialLifecycleMapper.ConversionProduct product, int days, BigDecimal offsetCap,
                                     BigDecimal dailyUsdt, BigDecimal dailyNex) {
        return sha256(linked("trialProductId", product.productNo(), "trialDays", days,
                "dailyUsdtSource", dailyUsdtSource(product), "monthlyTargetUsdt", decimalTerm(product.monthlyTargetUsdt()),
                "dailyUsdt", decimalTerm(dailyUsdt), "dailyNex", decimalTerm(dailyNex),
                "offsetCapUsdt", decimalTerm(offsetCap), "priceUsdt", decimalTerm(product.priceUsdt())).toString());
    }

    private static String decimalTerm(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }

    private void addTrialTermsVersion(Map<String, Object> config, Map<String, String> policy,
                                      AppTrialLifecycleMapper.ConversionProduct product,
                                      BigDecimal dailyUsdt, BigDecimal dailyNex) {
        try {
            BigDecimal offsetCap = trialOffsetCap(policy);
            config.put("trialOffsetCapUSD", decimalTerm(offsetCap));
            config.put("trialTermsVersion", trialTermsVersion(product, positiveInt(policy, "trialDays", 3),
                    offsetCap, dailyUsdt, dailyNex));
        } catch (NumberFormatException ignored) {
            // Keep state readable when a stored offset cap cannot be used by start.
        }
    }

    private boolean validExpectedAmount(BigDecimal expected) {
        return expected != null && expected.signum() >= 0 && expected.scale() <= 2
                && expected.compareTo(MAX_EXPECTED_AMOUNT) <= 0;
    }

    private void publishChargeAttempt(
            Long userId, TrialRow row, String trigger, String result, BigDecimal amount, String reason) {
        Attribution attr = requireAttribution(userId);
        Map<String, Object> detail = linked("trigger", trigger, "result", result,
                "amount_usdt", amount, "reason", reason, "payment_rail", "UVEL_USDT_WALLET");
        publish("TRIAL", row.claimNo(), "trial.charge_attempted", userId, attr, detail);
        record("H2_TRIAL_CHARGE_ATTEMPTED", row.claimNo(), userId, detail);
    }

    private Map<String, Object> project(
            Long userId, TrialRow row, Map<String, String> policy, LocalDateTime now, boolean lockCapacity) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean trialGateEnabled = trialGateEnabled();
        boolean phaseOpen = flag(policy, "phaseOpen", true);
        boolean riskBlocked = mapper.trialCycleSignalCount(userId) > 0;
        String configuredProductCode = trialProductCode(policy, row);
        AppTrialLifecycleMapper.ConversionProduct catalogProduct = configuredProductCode == null
                ? null : mapper.catalogProduct(configuredProductCode);
        AppTrialLifecycleMapper.ConversionProduct startProduct = configuredProductCode == null
                ? null : mapper.conversionProduct(configuredProductCode);
        BigDecimal catalogDailyUsdt = productDailyUsdt(catalogProduct);
        BigDecimal catalogDailyNex = catalogProduct == null ? null : catalogProduct.dailyNex();
        boolean validCatalogYield = positiveOrNull(catalogDailyUsdt) != null
                && positiveOrNull(catalogDailyNex) != null;
        boolean productAvailable = availablePhysicalTrialProduct(startProduct) && productReleased(startProduct)
                && validCatalogYield;
        if (!validCatalogYield) {
            catalogDailyUsdt = null;
            catalogDailyNex = null;
        }
        boolean quotaAvailable = nonNegativeInt(policy, "seatsLeftToday", 0) > 0;
        boolean capacityAvailable = row != null && !restartable(normalize(row.status()))
                || withinPhysicalSlotCapacity(userId, startProduct, lockCapacity);
        result.put("authoritative", true);
        result.put("serverCanonical", true);
        result.put("sourceEnvironment", "PRODUCTION");
        result.put("runId", "");
        result.put("serverNowEpochMs", now.atZone(BUSINESS_ZONE).toInstant().toEpochMilli());
        result.put("endReason", row == null ? null : endReason(row));
        result.put("finishedAtEpochMs", row == null ? null : epochMillis(row.closedAt()));
        if (row == null) {
            result.put("state", "ELIGIBLE");
            boolean canStart = productAvailable && trialGateEnabled && phaseOpen && !riskBlocked && quotaAvailable && capacityAvailable;
            result.put("canStart", canStart);
            result.put("eligibilityReason", productAvailable
                    ? eligibilityReason("ELIGIBLE", canStart, trialGateEnabled, phaseOpen,
                            riskBlocked, quotaAvailable, capacityAvailable, null, now)
                    : "product-unavailable");
            result.put("trialGateEnabled", trialGateEnabled);
            result.put("version", 0L);
            putCanonicalProvenance(result, "nx_trial_claim");
            result.put("paymentRail", "UVEL_USDT_WALLET");
            Map<String, Object> config = safePolicy(policy, configuredProductCode,
                    catalogProduct == null ? mapper.configuredProductName(configuredProductCode) : catalogProduct.name(),
                    catalogProduct == null ? null : catalogProduct.priceUsdt(),
                    catalogDailyUsdt, catalogDailyNex);
            if (productAvailable) addTrialTermsVersion(config, policy, catalogProduct, catalogDailyUsdt, catalogDailyNex);
            result.put("config", config);
            return result;
        }
        String effectiveState = effectiveState(row, now);
        Settlement preview = active(effectiveState) ? settlement(row, policy, false, now) : null;
        boolean canStart = restartable(effectiveState)
                && (row.cooldownUntil() == null || !row.cooldownUntil().isAfter(now))
                && productAvailable && trialGateEnabled && phaseOpen && !riskBlocked && quotaAvailable && capacityAvailable;
        result.put("claimNo", row.claimNo());
        result.put("state", effectiveState);
        result.put("canStart", canStart);
        result.put("eligibilityReason", !restartable(effectiveState) || productAvailable
                ? eligibilityReason(effectiveState, canStart, trialGateEnabled, phaseOpen, riskBlocked,
                        quotaAvailable, capacityAvailable, row.cooldownUntil(), now)
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
                ? row.expiresAt().plusDays(row.graceDaysSnapshot()) : null;
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
        if (preview != null && row.priceUsdt() != null && row.priceUsdt().signum() > 0) {
            Quote current = quote(row, policy, now, false, configuredProductCode, row.priceUsdt(), false, true);
            Map<String,Object> purchaseQuote = quoteView(current, row.priceUsdt());
            purchaseQuote.put("trialOffsetCapUSD",row.offsetCapUsdt()==null?null:row.offsetCapUsdt().stripTrailingZeros().toPlainString());
            result.put("offsetUsdt",current.offsetUsdt());
            result.put("remainderUsdt",current.remainderUsdt());
            result.put("quote",purchaseQuote);
        }
        if ("REDEEMED".equals(effectiveState)) {
            // The purchase order may have been refunded since; the App must not keep saying "purchased".
            String snapshot = mapper.settlementSnapshot(row.id());
            String orderNo = snapshotValue(snapshot, "orderNo");
            String paymentStatus = orderNo.isEmpty() ? null : mapper.orderPaymentStatus(orderNo, userId);
            boolean refunded = "REFUNDED".equalsIgnoreCase(paymentStatus);
            result.put("conversionOrderNo", paymentStatus == null ? null : orderNo);
            result.put("conversionRefunded", refunded);
            // Only full-term conversions take their rewards back on refund (TrialConversionRewardReversal).
            result.put("conversionRewardsReversed", refunded && FULL_TERM_POLICY.equals(snapshotValue(snapshot, "settlementPolicy")));
        }
        result.put("paymentRail", "UVEL_USDT_WALLET");
        putCanonicalProvenance(result, "nx_trial_claim + nx_user_wallet");
        boolean frozenProduct = !restartable(effectiveState);
        // 已领取的试用:日收益以**该笔 claim 的快照**为准(row.dailyUsdt/dailyNex),不是当前目录价 ——
        // 用户已经按当时的数开始计息,中途改目录不该让他的 hero 数字跟着跳(zentao #78)。
        // 未领取/可重开时才回落目录商品值。
        Map<String, Object> config = safePolicy(policy, configuredProductCode,
                frozenProduct ? row.deviceName() : catalogProduct == null ? mapper.configuredProductName(configuredProductCode) : catalogProduct.name(),
                frozenProduct ? row.priceUsdt() : catalogProduct == null ? null : catalogProduct.priceUsdt(),
                frozenProduct ? row.dailyUsdt() : catalogDailyUsdt,
                frozenProduct ? row.dailyNex() : catalogDailyNex);
        if (frozenProduct) {
            config.put("trialDays", Integer.toString(Math.max(1, row.durationDays() == null ? 1 : row.durationDays())));
            config.put("graceDays", Integer.toString(row.graceDaysSnapshot()));
            config.put("extensionDays", Integer.toString(row.extensionDaysSnapshot()));
            config.put("trialOffsetCapUSD", row.offsetCapUsdt()==null?null:row.offsetCapUsdt().stripTrailingZeros().toPlainString());
        } else if (productAvailable) {
            addTrialTermsVersion(config, policy, catalogProduct, catalogDailyUsdt, catalogDailyNex);
        }
        result.put("config", config);
        return result;
    }

    /** The same numbers convert() will settle; the App only displays them. */
    private Map<String, Object> quoteView(Quote quote, BigDecimal priceUsdt) {
        Map<String, Object> view = linked(
                "settlementPolicy", quote.policy(), "quoteVersion", quote.version(),
                "earlyPurchase", quote.early(), "priceUsdt", priceUsdt,
                "accruedUsdt", quote.accrued().shadowUsdt(), "accruedNex", quote.accrued().shadowNex(),
                "settledUsdt", quote.settledUsdt(), "rewardNex", quote.rewardNex(),
                "earlyRewardUsdt", quote.earlyRewardUsdt(), "earlyRewardNex", quote.earlyRewardNex(),
                "offsetUsdt", quote.offsetUsdt(), "remainderUsdt", quote.remainderUsdt(),
                "promoDiscountUsdt", quote.promoDiscountUsdt(),
                "earlyDiscountConfiguredUsdt", quote.earlyDiscountConfiguredUsdt(),
                "earlyDiscountUsdt", quote.earlyDiscountUsdt(),
                "discountUsdt", quote.discountUsdt(), "amountUsdt", quote.amountUsdt());
        view.put("earlyEndsAtEpochMs", epochMillis(quote.earlyEndsAt()));
        return view;
    }

    private Long epochMillis(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
    }

    private String endReason(TrialRow row) {
        String state = normalize(row.status());
        if (!List.of("CANCELLED", "FAILED").contains(state)) return null;
        if ("auto_end".equals(row.cancelReason()) || "auto_settlement_window_expired".equals(row.cancelReason())) {
            return "expired";
        }
        return "CANCELLED".equals(state) ? "cancelled" : null;
    }

    private String eligibilityReason(
            String state, boolean canStart, boolean trialGateEnabled, boolean phaseOpen,
            boolean riskBlocked, boolean quotaAvailable, boolean capacityAvailable, LocalDateTime cooldownUntil, LocalDateTime now) {
        if (!"ELIGIBLE".equals(state) && !PERSISTED_STATES.contains(normalize(state))) return "unknown";
        if (canStart) return null;
        if (active(state)) return "in-progress";
        if ("REDEEMED".equals(state)) return "converted";
        if (riskBlocked) return "risk";
        if (!trialGateEnabled || !phaseOpen) return "phase-closed";
        if (!quotaAvailable) return "quota-exhausted";
        if (cooldownUntil != null && cooldownUntil.isAfter(now)) return "used";
        if (!capacityAvailable) return "capacity-full";
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
            Map<String, String> policy, String productCode, String productName, BigDecimal productPrice,
            BigDecimal dailyUsdt, BigDecimal dailyNex) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> defaults = linkedString(
                "trialDays", "3", "graceDays", "7", "extensionDays", "3",
                "discountCapUSD", "20",
                "autoChargeAtEnd", "false", "highQualityThresholdUSD", "100",
                "trialPriceUSD", "1299",
                "cooldownDays", "30",
                "phaseOpen", "true", "autoPushEnabled", "true", "autoPushDelayMs", "1500",
                "autoPushCooldownHours", "24", "autoPushMaxPerSession", "1",
                "seatsLeftToday", "0", EARLY_DISCOUNT_KEY, "0");
        defaults.forEach((key, fallback) -> {
            if (List.of("phaseOpen", "autoPushEnabled", "autoChargeAtEnd").contains(key)) {
                result.put(key, !"autoChargeAtEnd".equals(key) && flag(policy, key, Boolean.parseBoolean(fallback)));
            } else {
                result.put(key, policy.getOrDefault(key, fallback));
            }
        });
        // Installed App versions read rates at or below 1 as fractions.
        result.put("discountRate", conversionPromoRate(policy).stripTrailingZeros().toPlainString());
        String offsetCap = policy.get("trialOffsetCapUSD");
        result.put("trialOffsetCapUSD",StringUtils.hasText(offsetCap)?offsetCap:null);
        // H2 emergency auto-push stop lives outside the regular policy table.
        // It suppresses only unsolicited prompts, never the manual trial lifecycle.
        String killed = mapper.autoPushKilled();
        if (killed != null && List.of("true", "1", "yes", "on", "enabled", "active")
                .contains(killed.trim().toLowerCase(Locale.ROOT))) {
            result.put("autoPushEnabled", false);
        }
        if (StringUtils.hasText(productCode)) result.put("trialProductId", productCode);
        if (StringUtils.hasText(productName)) result.put("trialProductName", productName.trim());
        if (productPrice != null && productPrice.signum() > 0) {
            result.put("trialPriceUSD", productPrice.stripTrailingZeros().toPlainString());
        }
        result.put("shadowDailyUSD", dailyUsdt == null ? null : dailyUsdt.stripTrailingZeros().toPlainString());
        result.put("shadowDailyNEX", dailyNex == null ? null : dailyNex.stripTrailingZeros().toPlainString());
        return result;
    }

    private Map<String, String> linkedString(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private String trialProductCode(Map<String, String> policy) {
        String productCode = canonicalTrialProductId(policy.get("trialProductId"));
        return productCode.matches("[A-Za-z0-9._-]{2,64}") ? productCode : null;
    }

    private String trialProductCode(Map<String, String> policy, TrialRow row) {
        String pinned = pinnedTrialProductCode(row);
        return pinned == null ? trialProductCode(policy) : pinned;
    }

    private String pinnedTrialProductCode(TrialRow row) {
        if (row == null || restartable(row.status())) return null;
        String pinned = canonicalTrialProductId(snapshotValue(row.quotaSnapshot(), "productCode"));
        return pinned.matches("[A-Za-z0-9._-]{2,64}") ? pinned : null;
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

    private static String canonicalTrialProductId(String value) {
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

    private boolean productReleased(AppTrialLifecycleMapper.ConversionProduct product) {
        if (product == null) return false;
        StorefrontProductReleasePolicy.Decision release =
                productReleasePolicy.evaluate(product.productNo(), product.unlockPhase());
        return release != null && release.available();
    }

    /** Start reserves one slot; conversion replaces that same trial reservation with one device. */
    private boolean withinPhysicalSlotCapacity(
            Long userId, AppTrialLifecycleMapper.ConversionProduct product, boolean lockCapacity) {
        if (product == null) return true;
        // Polling projects eligibility from its read snapshot. Trial writes require current reads.
        int active = Math.max(0, lockCapacity ? canonicalStateMapper.lockActiveDeviceCount(userId, false)
                : canonicalStateMapper.activeDeviceCount(userId));
        int reserved = Math.max(0, lockCapacity ? canonicalStateMapper.lockReservedDeviceOrderCount(userId, false)
                : canonicalStateMapper.reservedDeviceOrderCount(userId));
        int cap = Math.max(1, canonicalStateMapper.deviceSlotCap());
        return (long) active + reserved + 1 <= cap;
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
        if (production || development) return lock ? mapper.lockActiveUser(userId) : mapper.activeUser(userId);
        return null;
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
        return policyMap(businessDate());
    }

    private Map<String, String> policyMap(LocalDate quotaDate) {
        Map<String, String> policy = configuredPolicyMap();
        int dailyLimit = nonNegativeInt(policy, "seatsLeftToday", 0);
        Integer remaining = mapper.trialQuotaRemaining(quotaDate);
        policy.put("seatsLeftToday", Integer.toString(remaining == null ? dailyLimit : Math.max(remaining, 0)));
        return policy;
    }

    private Map<String, String> startPolicyMap() {
        Map<String, String> policy = configuredPolicyMap();
        // Lock current policy before the claim and product so starts cannot contend on the same claim gap.
        START_POLICY_KEYS.forEach(policy::remove);
        for (PolicyRow row : mapper.lockStartPolicies(START_POLICY_KEYS)) {
            policy.put(row.policyKey(), row.currentValue());
        }
        return policy;
    }

    private Map<String, String> configuredPolicyMap() {
        List<PolicyRow> rows = mapper.policies();
        return (rows == null ? List.<PolicyRow>of() : rows).stream().collect(Collectors.toMap(
                PolicyRow::policyKey, PolicyRow::currentValue, (left, right) -> right, LinkedHashMap::new));
    }

    private LocalDate businessDate() {
        return BusinessCalendar.legacy().today(clock);
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
        String scope = trialScope(operation, userId);
        String hash = sha256(String.valueOf(request));
        if ("TRIAL_START".equals(operation) || "TRIAL_CANCEL".equals(operation)) {
            return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.executeRetained(
                    scope, key, hash, ApiResult.class, (Supplier) action);
        }
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                scope, key, hash,
                ApiResult.class, (Supplier) action);
    }

    private String trialScope(String operation, Long userId) {
        return "APP:" + operation + ":USER:" + userId;
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
        String snapshot(String trigger, String paymentRail, String productCode,BigDecimal appliedOffset,BigDecimal creditedRemainder) {
            return "trigger=" + trigger + ",paymentRail=" + paymentRail + ",productCode=" + productCode
                    + ",shadowUsdt=" + shadowUsdt + ",shadowNex=" + shadowNex + ",offsetUsdt=" + appliedOffset
                    + ",remainderUsdt=" + creditedRemainder + ",discountUsdt=" + discountUsdt
                    + ",chargeUsdt=" + chargeUsdt;
        }
    }

    private record Quote(
            Settlement accrued, String policy, String version, boolean early,
            BigDecimal settledUsdt, BigDecimal rewardNex, BigDecimal earlyRewardUsdt, BigDecimal earlyRewardNex,
            BigDecimal offsetUsdt, BigDecimal remainderUsdt, BigDecimal promoDiscountUsdt,
            BigDecimal earlyDiscountConfiguredUsdt, BigDecimal earlyDiscountUsdt, BigDecimal discountUsdt,
            BigDecimal amountUsdt, LocalDateTime earlyEndsAt) {
        /** Settlement-snapshot suffix; refunds key the reward reversal off {@code settlementPolicy}. */
        String snapshot() {
            return ",settlementPolicy=" + policy + ",quoteVersion=" + version
                    + ",settledUsdt=" + settledUsdt + ",settledNex=" + rewardNex
                    + ",earlyRewardUsdt=" + earlyRewardUsdt + ",earlyRewardNex=" + earlyRewardNex
                    + ",earlyDiscountConfiguredUsdt=" + earlyDiscountConfiguredUsdt.stripTrailingZeros().toPlainString()
                    + ",earlyDiscountUsdt=" + earlyDiscountUsdt;
        }

        Map<String, Object> receiptFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("settlementPolicy", policy);
            fields.put("quoteVersion", version);
            fields.put("rewardNex", rewardNex);
            fields.put("earlyRewardUsdt", earlyRewardUsdt);
            fields.put("earlyRewardNex", earlyRewardNex);
            fields.put("earlyDiscountUsdt", earlyDiscountUsdt);
            return fields;
        }
    }
}
