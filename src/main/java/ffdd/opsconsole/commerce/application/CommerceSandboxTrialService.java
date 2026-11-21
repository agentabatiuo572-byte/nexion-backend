package ffdd.opsconsole.commerce.application;

import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.device.domain.ProductInventoryMode;
import ffdd.opsconsole.commerce.mapper.CommerceSandboxTrialMapper;
import ffdd.opsconsole.commerce.mapper.CommerceSandboxTrialMapper.TrialClaim;
import ffdd.opsconsole.commerce.mapper.CommerceSandboxTrialMapper.TrialClaimWrite;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.config.StorageTime;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Explicit local-sandbox trial rail. It has no dependency on nx_trial_claim or
 * production wallet/order tables; conversion delegates the money mutation to
 * the same run-scoped commerce sandbox callback used by normal checkout.
 */
@Service
@RequiredArgsConstructor
public class CommerceSandboxTrialService {
    private static final ZoneId BUSINESS_ZONE = StorageTime.ZONE;
    private static final int TRIAL_DAYS = 3;
    private static final int GRACE_DAYS = 7;
    private static final BigDecimal OFFSET_CAP = new BigDecimal("50.000000");
    private static final BigDecimal DISCOUNT_RATE = new BigDecimal("0.15");
    private static final BigDecimal DISCOUNT_CAP = new BigDecimal("20.000000");
    private static final BigDecimal MAX_EXPECTED_AMOUNT = new BigDecimal("1000000.00");
    private static final boolean PHASE_OPEN = true;
    private static final boolean AUTO_PUSH_ENABLED = true;
    private static final int AUTO_PUSH_DELAY_MS = 1500;
    private static final int AUTO_PUSH_COOLDOWN_HOURS = 24;
    private static final int AUTO_PUSH_MAX_PER_SESSION = 1;

    private final CommerceSandboxTrialMapper trialMapper;
    private final CommerceAcceptanceSandboxMapper commerceMapper;
    private final CommerceAcceptanceSandboxService payment;
    private final CommerceAcceptanceRun acceptanceRun;
    private final FundsSandboxProfileGuard profileGuard;
    private final Clock clock;

    public boolean enabled() {
        return profileGuard != null && profileGuard.isLocalSandboxEnabled();
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> state(Long userId) {
        requireSandbox(userId);
        String runId = acceptanceRun.requireRunId();
        TrialClaim row = trialMapper.find(runId, userId);
        return ApiResult.ok(project(userId, row, LocalDateTime.now(clock)));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> start(Long userId, String idempotencyKey) {
        requireSandbox(userId);
        String runId = acceptanceRun.requireRunId();
        TrialClaim existing = trialMapper.lock(runId, userId);
        if (existing != null) return ApiResult.ok(project(userId, existing, LocalDateTime.now(clock)));
        LocalDateTime now = LocalDateTime.now(clock);
        String claimNo = "TRIAL-SBX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var product = trialMapper.configuredSku(runId);
        if (product == null) return ApiResult.fail(409,"TRIAL_PRODUCT_NOT_AVAILABLE");
        BigDecimal daily = product.effectiveDailyUsdt();
        TrialClaimWrite write = new TrialClaimWrite(runId,userId,claimNo,product.productNo(),product.name(),
                now,now.plusDays(TRIAL_DAYS),daily,product.dailyNex(),OFFSET_CAP,product.price());
        int inserted = trialMapper.insertTrialClaim(write);
        TrialClaim created = inserted == 1
                ? new TrialClaim(claimNo,userId,product.productNo(),product.name(),"ACTIVE",now,
                now.plusDays(TRIAL_DAYS),null,0L,daily,product.dailyNex(),OFFSET_CAP,product.price(),
                null,null,null,null)
                : trialMapper.lock(runId, userId);
        if (created == null) throw new BizException(409, "TRIAL_SANDBOX_START_CONFLICT");
        return ApiResult.ok(project(userId, created, now));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId) {
        requireSandbox(userId);
        String runId = acceptanceRun.requireRunId();
        TrialClaim row = trialMapper.lock(runId, userId);
        if (row == null || !"ACTIVE".equalsIgnoreCase(row.status())) {
            return ApiResult.fail(409, "TRIAL_NOT_CANCELLABLE");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (trialMapper.cancel(runId, userId, row.version(), now) != 1) {
            throw new BizException(409, "TRIAL_SANDBOX_CANCEL_CONFLICT");
        }
        return ApiResult.ok(project(userId, trialMapper.find(runId, userId), now));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> convert(Long userId, String productNo,
                                                  BigDecimal expectedAmountUsdt, String idempotencyKey) {
        requireSandbox(userId);
        String runId = acceptanceRun.requireRunId();
        TrialClaim row = trialMapper.lock(runId, userId);
        if (row == null) return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        String requested = StringUtils.hasText(productNo) ? productNo.trim() : row.productNo();
        if (!row.productNo().equals(requested)) return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_ELIGIBLE");
        if (!validExpectedAmount(expectedAmountUsdt)) {
            return ApiResult.fail(409, "TRIAL_AMOUNT_INVALID");
        }
        if ("REDEEMED".equalsIgnoreCase(row.status())) {
            if (row.amountUsdt() == null || row.amountUsdt().compareTo(expectedAmountUsdt) > 0) {
                return ApiResult.fail(409, "TRIAL_AMOUNT_MISMATCH");
            }
            return converted(row);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String state = effectiveState(row, now);
        if (!("ACTIVE".equals(state) || "GRACE".equals(state))) {
            return ApiResult.fail(409, "TRIAL_NOT_CONVERTIBLE");
        }
        CommerceAcceptanceSandboxMapper.SandboxCatalogProduct product = commerceMapper.lockSandboxCatalogProduct(
                runId, null, requested, 1);
        ProductInventoryMode inventoryMode = product == null ? null : ProductInventoryMode.parse(product.inventoryMode());
        if (product == null || product.productId() == null || product.version() == null || inventoryMode == null
                || (inventoryMode == ProductInventoryMode.UNLIMITED
                    && !"SHARE".equalsIgnoreCase(product.productType()))
                || (inventoryMode == ProductInventoryMode.FINITE
                    && (product.stock() == null || product.stock() < 1))
                || product.priceUsdt() == null || product.priceUsdt().signum() <= 0) {
            return ApiResult.fail(409, "TRIAL_PRODUCT_NOT_AVAILABLE");
        }
        BigDecimal shadowOffset = shadow(row, now).min(row.offsetCapUsdt()).min(product.priceUsdt())
                .setScale(6, RoundingMode.DOWN);
        BigDecimal promoDiscount = promoDiscount(product.priceUsdt());
        BigDecimal discount = promoDiscount.add(shadowOffset).min(product.priceUsdt()).setScale(6, RoundingMode.DOWN);
        BigDecimal amount = product.priceUsdt().subtract(discount).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        if (amount.compareTo(expectedAmountUsdt) > 0) {
            return ApiResult.fail(409, "TRIAL_AMOUNT_MISMATCH");
        }
        String orderNo = "TRC-SBX-" + sha256(runId + "|" + userId + "|" + row.claimNo() + "|" + requested)
                .substring(0, 32).toUpperCase(Locale.ROOT);
        String paymentNo = "PAY-SBX-" + sha256(runId + "|" + userId + "|" + orderNo)
                .substring(0, 32).toUpperCase(Locale.ROOT);
        if (commerceMapper.reserveSandboxCatalogStock(runId, product.productId(), product.version(), 1) != 1) {
            throw new BizException(409, "COMMERCE_SANDBOX_STOCK_CONFLICT");
        }
        if (commerceMapper.insertSandboxOrder(new CommerceAcceptanceSandboxMapper.OrderWrite(
                orderNo, userId, product.productId(), 1, amount, product.version(), runId,
                "TRIAL_CONVERT", 1)) != 1
                || commerceMapper.insertInventory(new CommerceAcceptanceSandboxMapper.InventoryWrite(
                orderNo, product.productId(), product.productNo(), product.priceUsdt(), 1, runId)) != 1) {
            throw new BizException(409, "TRIAL_SANDBOX_ORDER_CREATE_CONFLICT");
        }
        var result = payment.applyCallback(orderNo, paymentNo, "PAYMENT_SUCCEEDED", 0L,
                "trial conversion sandbox payment", String.valueOf(userId));
        if (trialMapper.markConverted(runId, userId, row.version(), now, orderNo, paymentNo, discount, amount) != 1) {
            throw new BizException(409, "TRIAL_SANDBOX_CONVERSION_CONFLICT");
        }
        return ApiResult.ok(convertedPayload(row, orderNo, paymentNo, product.productNo(), amount, discount, result.walletAfter(), runId));
    }

    private ApiResult<Map<String, Object>> converted(TrialClaim row) {
        return ApiResult.ok(convertedPayload(row, row.orderNo(), row.paymentNo(), row.productNo(), row.amountUsdt(),
                row.discountUsdt(), null, acceptanceRun.requireRunId()));
    }

    private Map<String, Object> convertedPayload(TrialClaim row, String orderNo, String paymentNo, String productNo,
                                                  BigDecimal amount, BigDecimal discount, BigDecimal walletAfter,
                                                  String runId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);
        result.put("paymentNo", paymentNo);
        result.put("productNo", productNo);
        result.put("amountUsdt", amount);
        result.put("discountUsdt", discount);
        result.put("paymentStatus", "PAID");
        result.put("orderStatus", "PAID");
        result.put("canonicalStatus", "paid");
        result.put("source", "mock");
        result.put("sourceEnvironment", "SANDBOX");
        result.put("runId", runId);
        if (walletAfter != null) result.put("walletAfter", walletAfter);
        return result;
    }

    private Map<String, Object> project(Long userId, TrialClaim row, LocalDateTime now) {
        Map<String, Object> result = new LinkedHashMap<>();
        String state = row == null ? "ELIGIBLE" : effectiveState(row, now);
        var product = row == null ? trialMapper.configuredSku(acceptanceRun.requireRunId()) : null;
        boolean canStart = row == null && product != null;
        result.put("authoritative", true);
        result.put("serverNowEpochMs", epoch(now));
        result.put("state", state);
        result.put("serverState", state);
        result.put("canStart", canStart);
        if (!canStart) result.put("eligibilityReason", row == null ? "TRIAL_PRODUCT_NOT_AVAILABLE"
                : "REDEEMED".equals(state) ? "converted" : "in-progress");
        result.put("trialGateEnabled", true);
        result.put("version", row == null ? 0L : row.version());
        result.put("source", "nx_trial_claim_sandbox");
        result.put("paymentRail", "UVEL_USDT_WALLET");
        result.put("sourceEnvironment", "SANDBOX");
        result.put("runId", acceptanceRun.requireRunId());
        if (row != null || product != null) result.put("config", Map.ofEntries(
                Map.entry("trialDays", String.valueOf(TRIAL_DAYS)), Map.entry("graceDays", String.valueOf(GRACE_DAYS)),
                Map.entry("discountRate", DISCOUNT_RATE.toPlainString()),
                Map.entry("discountCapUSD", DISCOUNT_CAP.toPlainString()),
                Map.entry("trialOffsetCapUSD", OFFSET_CAP.toPlainString()),
                Map.entry("trialProductId", row == null ? product.productNo() : row.productNo()),
                Map.entry("trialProductName", row == null ? product.name() : row.deviceName()),
                Map.entry("trialPriceUSD", (row == null ? product.price() : row.priceUsdt()).toPlainString()),
                Map.entry("shadowDailyUSD", (row == null ? product.effectiveDailyUsdt() : row.shadowDailyUsdt()).toPlainString()),
                Map.entry("shadowDailyNEX", (row == null ? product.dailyNex() : row.shadowDailyNex()).toPlainString()),
                Map.entry("phaseOpen", PHASE_OPEN), Map.entry("autoPushEnabled", AUTO_PUSH_ENABLED),
                Map.entry("autoPushDelayMs", String.valueOf(AUTO_PUSH_DELAY_MS)),
                Map.entry("autoPushCooldownHours", String.valueOf(AUTO_PUSH_COOLDOWN_HOURS)),
                Map.entry("autoPushMaxPerSession", String.valueOf(AUTO_PUSH_MAX_PER_SESSION))));
        if (row == null) return result;
        result.put("claimNo", row.claimNo());
        result.put("deviceName", row.deviceName());
        result.put("claimedAt", row.claimedAt());
        result.put("claimedAtEpochMs", epoch(row.claimedAt()));
        result.put("expiresAt", row.expiresAt());
        result.put("expiresAtEpochMs", epoch(row.expiresAt()));
        LocalDateTime graceEnd = row.expiresAt().plusDays(GRACE_DAYS);
        result.put("graceEndsAt", graceEnd);
        result.put("graceEndsAtEpochMs", epoch(graceEnd));
        result.put("finishedAt", row.finishedAt());
        result.put("finishedAtEpochMs", epoch(row.finishedAt()));
        result.put("shadowUsdt", shadow(row, "REDEEMED".equals(state) && row.finishedAt() != null ? row.finishedAt() : now));
        result.put("shadowNex", shadowNex(row, "REDEEMED".equals(state) && row.finishedAt() != null ? row.finishedAt() : now));
        result.put("offsetUsdt", shadow(row, now).min(row.offsetCapUsdt()).setScale(6, RoundingMode.DOWN));
        result.put("priceUsdt", row.priceUsdt());
        return result;
    }

    private void requireSandbox(Long userId) {
        if (!enabled()) throw new BizException(409, "TRIAL_SANDBOX_DISABLED");
        if (userId == null || userId < 1 || !commerceMapper.isSandboxUser(userId)) {
            throw new BizException(403, "COMMERCE_SANDBOX_USER_REQUIRED");
        }
    }

    private BigDecimal promoDiscount(BigDecimal catalogPrice) {
        return catalogPrice.multiply(DISCOUNT_RATE).min(DISCOUNT_CAP).setScale(6, RoundingMode.DOWN);
    }

    private boolean validExpectedAmount(BigDecimal expected) {
        return expected != null && expected.signum() >= 0 && expected.scale() <= 2
                && expected.compareTo(MAX_EXPECTED_AMOUNT) <= 0;
    }

    private String effectiveState(TrialClaim row, LocalDateTime now) {
        if ("ACTIVE".equalsIgnoreCase(row.status()) && !row.expiresAt().isAfter(now)) return "GRACE";
        return row.status().trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal shadow(TrialClaim row, LocalDateTime at) {
        long seconds = Math.max(0, Duration.between(row.claimedAt(), at).getSeconds());
        BigDecimal days = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86400), 8, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(TRIAL_DAYS));
        return row.shadowDailyUsdt().multiply(days).setScale(6, RoundingMode.DOWN);
    }

    private BigDecimal shadowNex(TrialClaim row, LocalDateTime at) {
        long seconds = Math.max(0, Duration.between(row.claimedAt(), at).getSeconds());
        BigDecimal days = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(86400), 8, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(TRIAL_DAYS));
        return row.shadowDailyNex().multiply(days).setScale(6, RoundingMode.DOWN);
    }

    private Long epoch(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
