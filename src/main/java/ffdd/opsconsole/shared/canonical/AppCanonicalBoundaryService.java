package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppCanonicalBoundaryService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CanonicalStateMapper mapper;
    private final TamperDetectionPublisher tamperPublisher;
    private final AdminIdempotencyService idempotencyService;

    @PostConstruct
    void ensureOtpChallengeTable() {
        mapper.createOtpChallengeTable();
    }

    public ApiResult<Map<String, Object>> trialEligibility(Long userId, String clientStatus) {
        String state = normalizeState(mapper.findTrialState(userId), "ELIGIBLE");
        if (StringUtils.hasText(clientStatus) && !state.equals(normalizeState(clientStatus, ""))) {
            return reject(userId, "free_trial_state",
                    "客户端试用状态与服务器领取记录不一致，服务器拒绝按客户端状态重新领取",
                    "/api/trial/eligibility", "TRIAL_STATE_CONFLICT");
        }
        return ApiResult.ok(linked("state", state, "canStart", "ELIGIBLE".equals(state), "source", "nx_trial_claim"));
    }

    public ApiResult<Map<String, Object>> kycStatus(Long userId, Boolean clientWalletPaired) {
        boolean paired = mapper.walletPaired(userId);
        if (clientWalletPaired != null && clientWalletPaired != paired) {
            return reject(userId, "wallet_pairing",
                    "客户端钱包配对状态与服务器 KYC 及钱包地址记录不一致，服务器拒绝放行",
                    "/api/kyc/status", "WALLET_PAIRING_CONFLICT");
        }
        return ApiResult.ok(linked("walletPaired", paired, "source", "nx_user+nx_user_profile"));
    }

    public ApiResult<Map<String, Object>> securityState(Long userId, Boolean clientTwoFactorEnabled) {
        boolean enabled = mapper.twoFactorEnabled(userId);
        if (clientTwoFactorEnabled != null && clientTwoFactorEnabled != enabled) {
            return reject(userId, "two_factor_state",
                    "客户端 2FA 状态与服务器安全状态不一致，服务器拒绝使用伪造状态降级风控",
                    "/api/security/state", "TWO_FACTOR_STATE_CONFLICT");
        }
        return ApiResult.ok(linked("twoFactorEnabled", enabled, "source", "nx_user_security"));
    }

    public ApiResult<Map<String, Object>> productPhase(Long userId, String clientPinned, boolean devMode) {
        String phase = normalizePhase(mapper.currentPhase());
        if (devMode || (StringUtils.hasText(clientPinned) && !phase.equals(normalizeState(clientPinned, "")))) {
            return reject(userId, "product_phase_override",
                    "客户端阶段锁定或开发模式参数与服务器当前阶段不一致，服务器拒绝覆盖阶段",
                    "/api/product/phase", "PRODUCT_PHASE_OVERRIDE_REJECTED");
        }
        return ApiResult.ok(linked("phase", phase, "devOverrideAllowed", false, "source", "nx_config_item"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> activateDevice(
            Long userId, Long deviceId, Integer clientMaxDevices, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("DEVICE_ACTIVATE", userId, idempotencyKey,
                linked("deviceId", deviceId, "clientMaxDevices", clientMaxDevices),
                () -> activateDeviceInternal(userId, deviceId, clientMaxDevices));
    }

    private ApiResult<Map<String, Object>> activateDeviceInternal(Long userId, Long deviceId, Integer clientMaxDevices) {
        if (deviceId == null || deviceId <= 0) return ApiResult.fail(422, "DEVICE_ID_REQUIRED");
        int cap = Math.max(1, mapper.deviceSlotCap());
        int active = Math.max(0, mapper.activeDeviceCount(userId));
        if ((clientMaxDevices != null && clientMaxDevices > cap) || active >= cap) {
            return reject(userId, "device_slot_cap",
                    "客户端设备槽位上限高于服务器配置或账户已达上限，服务器拒绝激活",
                    "/api/devices/activate", "DEVICE_SLOT_CAP_EXCEEDED");
        }
        if (mapper.activateOwnedDevice(userId, deviceId, cap) != 1) {
            return ApiResult.fail(409, "DEVICE_NOT_OWNED_OR_ALREADY_ACTIVE");
        }
        return ApiResult.ok(linked("deviceId", deviceId, "activeCount", active + 1, "slotCap", cap));
    }

    public ApiResult<Map<String, Object>> deviceEarnings(
            Long userId, boolean seedLegacyDevice, boolean fastForwardAll, BigDecimal bumpedEarningsTotal) {
        if (seedLegacyDevice || fastForwardAll || bumpedEarningsTotal != null) {
            return reject(userId, "dev_seed_state",
                    "请求包含仅限开发环境的设备种子或收益快进字段，服务器拒绝修改设备年龄与收益",
                    "/api/devices/earnings", "DEV_SEED_STATE_REJECTED");
        }
        CanonicalStateMapper.DeviceEarnings earnings = mapper.deviceEarnings(userId);
        BigDecimal usdt = earnings == null || earnings.dailyUsdt() == null ? BigDecimal.ZERO : earnings.dailyUsdt();
        BigDecimal nex = earnings == null || earnings.dailyNex() == null ? BigDecimal.ZERO : earnings.dailyNex();
        List<CanonicalStateMapper.OwnedDevice> devices = mapper.ownedDevices(userId);
        return ApiResult.ok(linked(
                "dailyUsdt", usdt,
                "dailyNex", nex,
                "devices", devices == null ? List.of() : devices,
                "source", "nx_user_device"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> verifyOtp(
            Long userId, String challengeNo, String code, Boolean clientRegexAccepted, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("OTP_VERIFY", userId, idempotencyKey,
                linked("challengeNo", challengeNo, "code", code, "clientRegexAccepted", clientRegexAccepted),
                () -> verifyOtpInternal(userId, challengeNo, code, clientRegexAccepted));
    }

    private ApiResult<Map<String, Object>> verifyOtpInternal(
            Long userId, String challengeNo, String code, Boolean clientRegexAccepted) {
        if (!StringUtils.hasText(challengeNo) || !StringUtils.hasText(code)) {
            return ApiResult.fail(422, "OTP_CHALLENGE_AND_CODE_REQUIRED");
        }
        String normalizedCode = code.trim();
        if (!normalizedCode.matches("\\d{6}") || mapper.consumeValidOtp(userId, challengeNo.trim(), normalizedCode) != 1) {
            mapper.incrementOtpFailure(userId, challengeNo.trim());
            if (Boolean.TRUE.equals(clientRegexAccepted) || normalizedCode.matches("\\d{6}")) {
                return reject(userId, "otp_verification",
                        "客户端正则认为验证码有效，但服务器 TTL、次数或摘要校验未通过",
                        "/api/auth/otp/verify", "OTP_VERIFICATION_REJECTED");
            }
            return ApiResult.fail(422, "OTP_FORMAT_INVALID");
        }
        return ApiResult.ok(linked("verified", true, "source", "nx_user_otp_challenge"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> pushClientBill(
            Long userId, Map<String, Object> ignoredClientBill, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("BILL_CLIENT_PUSH", userId, idempotencyKey, ignoredClientBill,
                () -> reject(userId, "bill_client_push",
                        "账单只能由服务器资金事件入账，服务器拒绝客户端推送账单",
                        "/api/wallet/bills", "CLIENT_BILL_PUSH_REJECTED"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, Integer quantity, String idempotencyKey) {
        return createOrder(userId, clientOrderId, productId, null, quantity, idempotencyKey);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("ORDER_CREATE", userId, idempotencyKey,
                linked("clientOrderId", clientOrderId, "productId", productId,
                        "productNo", productNo, "quantity", quantity),
                () -> createOrderInternal(userId, clientOrderId, productId, productNo, quantity));
    }

    private ApiResult<Map<String, Object>> createOrderInternal(
            Long userId, String clientOrderId, Long productId, String productNo, Integer quantity) {
        if (StringUtils.hasText(clientOrderId)) {
            return reject(userId, "client_minted_id",
                    "客户端提交了自铸业务 ID，服务器拒绝使用该 ID 创建订单",
                    "/api/orders", "CLIENT_MINTED_ID_REJECTED");
        }
        int qty = quantity == null ? 1 : quantity;
        String normalizedProductNo = StringUtils.hasText(productNo) ? productNo.trim() : null;
        boolean validProductId = productId != null && productId > 0;
        if ((!validProductId && normalizedProductNo == null) || qty < 1 || qty > 100) {
            return ApiResult.fail(422, "ORDER_PRODUCT_OR_QUANTITY_INVALID");
        }
        CanonicalStateMapper.ProductStock product = mapper.lockProduct(validProductId ? productId : null, normalizedProductNo);
        if (product == null || product.priceUsdt() == null || product.priceUsdt().signum() <= 0) {
            return ApiResult.fail(409, "PRODUCT_NOT_AVAILABLE");
        }
        if (product.stock() == null || product.stock() < qty) return ApiResult.fail(409, "PRODUCT_OUT_OF_STOCK");
        String orderNo = "ORD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        BigDecimal amount = product.priceUsdt().multiply(BigDecimal.valueOf(qty));
        if (mapper.decrementProductStock(product.id(), qty) != 1) {
            throw new BizException(409, "PRODUCT_STOCK_CONFLICT");
        }
        if (mapper.insertOrder(userId, orderNo, product.id(), qty, amount) != 1) {
            throw new BizException(409, "ORDER_CREATE_CONFLICT");
        }
        return ApiResult.ok(linked("orderNo", orderNo, "amountUsdt", amount,
                "paymentStatus", "PENDING", "orderStatus", "PENDING_PAYMENT", "idSource", "server"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> chargeTrial(
            Long userId, Boolean clientChargeSucceeded, BigDecimal clientChargeFailRate, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("TRIAL_CHARGE", userId, idempotencyKey,
                linked("clientChargeSucceeded", clientChargeSucceeded, "clientChargeFailRate", clientChargeFailRate),
                () -> chargeTrialInternal(userId, clientChargeSucceeded, clientChargeFailRate));
    }

    private ApiResult<Map<String, Object>> chargeTrialInternal(
            Long userId, Boolean clientChargeSucceeded, BigDecimal clientChargeFailRate) {
        if (clientChargeSucceeded != null || clientChargeFailRate != null) {
            return reject(userId, "charge_fail_rate",
                    "客户端提交了扣款结果或失败率，服务器拒绝使用客户端随机结果",
                    "/api/trial/charge", "CLIENT_CHARGE_OUTCOME_REJECTED");
        }
        CanonicalStateMapper.TrialClaim claim = mapper.lockLatestChargeableTrial(userId);
        if (claim == null) return ApiResult.fail(409, "TRIAL_NOT_CHARGEABLE");
        BigDecimal price = claim.priceUsdt() == null ? BigDecimal.ZERO : claim.priceUsdt();
        BigDecimal offset = claim.earnedOffsetUsdt() == null ? BigDecimal.ZERO : claim.earnedOffsetUsdt();
        BigDecimal chargeAmount = price.subtract(offset).max(BigDecimal.ZERO);
        BigDecimal walletBalance = mapper.lockWalletUsdt(userId);
        if (walletBalance == null || walletBalance.compareTo(chargeAmount) < 0) {
            return ApiResult.ok(linked("ok", false, "reason", "INSUFFICIENT_FUNDS",
                    "amountUsdt", chargeAmount, "decisionSource", "server"));
        }
        BigDecimal rate = mapper.trialChargeFailRate();
        double boundedRate = rate == null ? 0.01d : Math.max(0d, Math.min(1d, rate.doubleValue()));
        boolean succeeded = RANDOM.nextDouble() >= boundedRate;
        if (!succeeded) {
            return ApiResult.ok(linked("ok", false, "reason", "SERVER_CHARGE_FAILED",
                    "amountUsdt", chargeAmount, "decisionSource", "server"));
        }
        if (chargeAmount.signum() > 0 && mapper.debitWalletUsdt(userId, chargeAmount) != 1) {
            throw new BizException(409, "TRIAL_WALLET_CONFLICT");
        }
        BigDecimal balanceAfter = walletBalance.subtract(chargeAmount);
        if (mapper.insertTrialChargeLedger(userId, claim.claimNo(), chargeAmount, balanceAfter) != 1) {
            throw new BizException(409, "TRIAL_LEDGER_CONFLICT");
        }
        String outcome = "CHARGED";
        if (mapper.markTrialChargeAttempt(claim.id(), outcome) != 1) {
            throw new BizException(409, "TRIAL_CHARGE_STATE_CONFLICT");
        }
        return ApiResult.ok(linked("ok", true, "reason", "CHARGED", "amountUsdt", chargeAmount,
                "balanceAfterUsdt", balanceAfter, "decisionSource", "server"));
    }

    private ApiResult<Map<String, Object>> reject(
            Long userId, String tamperPath, String effect, String endpoint, String code) {
        tamperPublisher.publish(userId, tamperPath, effect, endpoint);
        return ApiResult.fail(409, code);
    }

    private String normalizeState(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String normalizePhase(String value) {
        String normalized = normalizeState(value, "P1");
        if (normalized.matches("[1-6]")) normalized = "P" + normalized;
        return normalized.matches("P[1-6]") ? normalized : "P1";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> executeOnce(
            String operation, Long userId, String idempotencyKey, Object request,
            java.util.function.Supplier<ApiResult<Map<String, Object>>> action) {
        String scope = "APP:" + operation + ":USER:" + userId;
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope, idempotencyKey, sha256(String.valueOf(request)), ApiResult.class, (java.util.function.Supplier) action);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
