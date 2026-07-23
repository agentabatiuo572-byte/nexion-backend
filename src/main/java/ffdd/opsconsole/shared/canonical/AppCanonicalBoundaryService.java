package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.UserAttribution;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.VoucherRedemption;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.capacity.E3CapacityCurve;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppCanonicalBoundaryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> E3_CAPACITY_KEYS = Set.of(
            "capacityBand1DeltaPct", "capacityBand2DeltaPct", "capacityBand3DeltaPct",
            "stageEarlyEnd", "stageMidEnd", "cycleMonths", "capacityFloorPct", "capacitySubsidyDays",
            "capacityApplyToPhone", "capacityApplyToCloudShare", "capacityApplyToPcGpu",
            "capacityApplyToS1", "capacityApplyToPro", "capacityApplyToProV2",
            "capacityApplyToRackP1", "capacityApplyToRackP2",
            "taskLockS1", "taskLockPro", "taskLockRack");

    private final CanonicalStateMapper mapper;
    private final TamperDetectionPublisher tamperPublisher;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService outboxService;
    private final AppGrowthLifecyclePublisher growthLifecyclePublisher;
    private final GrowthRhythmFacade growthRhythmFacade;

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
        String status = normalizeState(mapper.kycStatus(userId), "NONE");
        boolean paired = mapper.walletPaired(userId);
        if (clientWalletPaired != null && clientWalletPaired != paired) {
            return reject(userId, "wallet_pairing",
                    "客户端钱包配对状态与服务器 KYC 及钱包地址记录不一致，服务器拒绝放行",
                    "/api/kyc/status", "WALLET_PAIRING_CONFLICT");
        }
        return ApiResult.ok(linked("status", status, "walletPaired", paired, "source", "KYC_AUTHORITY_LEDGER"));
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
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (!completeRhythm(rhythm)) {
            return ApiResult.fail(503, "H1_RHYTHM_UNAVAILABLE");
        }
        String phase = rhythm.currentPhase().trim().toUpperCase(Locale.ROOT);
        if (devMode || (StringUtils.hasText(clientPinned) && !phase.equals(normalizeState(clientPinned, "")))) {
            return reject(userId, "product_phase_override",
                    "客户端阶段锁定或开发模式参数与服务器当前阶段不一致，服务器拒绝覆盖阶段",
                    "/api/product/phase", "PRODUCT_PHASE_OVERRIDE_REJECTED");
        }
        return ApiResult.ok(linked(
                "phase", phase,
                "rhythm", rhythm.summary(),
                "dials", rhythm.dials(),
                "devOverrideAllowed", false,
                "source", "H1_GROWTH_RHYTHM"));
    }

    private boolean completeRhythm(GrowthRhythmSnapshot rhythm) {
        return rhythm != null
                && rhythm.totalMonths() > 0
                && rhythm.currentMonth() > 0
                && rhythm.currentMonth() <= rhythm.totalMonths()
                && StringUtils.hasText(rhythm.currentPhase())
                && rhythm.currentPhase().trim().toUpperCase(Locale.ROOT).matches("P[1-6]")
                && rhythm.phaseProgressPct() >= 0
                && rhythm.phaseProgressPct() <= 100
                && positive(rhythm.newUserBonusMultiplier())
                && positive(rhythm.inviteRewardMultiplier())
                && positive(rhythm.reinvestMultiplier())
                && bounded(rhythm.withdrawPenaltyFeeRate(), BigDecimal.ZERO, BigDecimal.valueOf(100))
                && rhythm.withdrawCooldownDays() > 0
                && bounded(rhythm.binaryDailyCap(), BigDecimal.ZERO, BigDecimal.valueOf(50_000))
                && positive(rhythm.questBonusMultiplier())
                && rhythm.sourceKeys() != null
                && !rhythm.sourceKeys().isEmpty();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean bounded(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
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
        List<CanonicalStateMapper.E3CapacityConfig> configRows = mapper.e3CapacityConfig();
        Map<String, String> capacityConfig = configRows == null ? Map.of() : configRows.stream()
                .collect(Collectors.toMap(
                        CanonicalStateMapper.E3CapacityConfig::configKey,
                        CanonicalStateMapper.E3CapacityConfig::configValue,
                        (left, right) -> right,
                        LinkedHashMap::new));
        if (!validE3CapacityConfig(capacityConfig)) {
            return ApiResult.fail(409, "E3_CAPACITY_CONFIG_INCOMPLETE");
        }
        CanonicalStateMapper.UserCanonicalProfile profile = mapper.userCanonicalProfile(userId);
        if (profile == null || profile.joinedAt() == null) {
            return ApiResult.fail(409, "CANONICAL_USER_PROFILE_UNAVAILABLE");
        }
        List<CanonicalStateMapper.OwnedDevice> rawDevices = mapper.ownedDevices(userId);
        List<Map<String, Object>> devices = (rawDevices == null ? List.<CanonicalStateMapper.OwnedDevice>of() : rawDevices)
                .stream().map(device -> projectE3Capacity(device, capacityConfig)).toList();
        BigDecimal usdt = devices.stream()
                .filter(device -> "ACTIVE".equalsIgnoreCase(String.valueOf(device.get("status"))))
                .map(device -> (BigDecimal) device.get("dailyUsdt"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
        BigDecimal nex = devices.stream()
                .filter(device -> "ACTIVE".equalsIgnoreCase(String.valueOf(device.get("status"))))
                .map(device -> (BigDecimal) device.get("dailyNex"))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
        return ApiResult.ok(linked(
                "dailyUsdt", usdt,
                "dailyNex", nex,
                "walletUsdt", zero(profile.usdtAvailable()).setScale(6, RoundingMode.HALF_UP),
                "walletNex", zero(profile.nexAvailable()).setScale(6, RoundingMode.HALF_UP),
                "userJoinedAt", profile.joinedAt().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                "serverNow", java.time.Instant.now().toEpochMilli(),
                "devices", devices,
                "capacitySchedule", capacityConfig,
                "source", "nx_user_device + nx_compute_e3_config"));
    }

    private boolean validE3CapacityConfig(Map<String, String> config) {
        if (!config.keySet().containsAll(E3_CAPACITY_KEYS)) return false;
        try {
            BigDecimal band1 = decimal(config, "capacityBand1DeltaPct");
            BigDecimal band2 = decimal(config, "capacityBand2DeltaPct");
            BigDecimal band3 = decimal(config, "capacityBand3DeltaPct");
            int early = integer(config, "stageEarlyEnd");
            int mid = integer(config, "stageMidEnd");
            int cycle = integer(config, "cycleMonths");
            BigDecimal floor = decimal(config, "capacityFloorPct");
            int subsidyDays = integer(config, "capacitySubsidyDays");
            int lockS1 = integer(config, "taskLockS1");
            int lockPro = integer(config, "taskLockPro");
            int lockRack = integer(config, "taskLockRack");
            if (List.of(band1, band2, band3).stream().anyMatch(value ->
                    value.compareTo(BigDecimal.valueOf(-100)) < 0
                            || value.compareTo(BigDecimal.valueOf(100)) > 0)) return false;
            if (early <= 0 || early >= mid || mid >= cycle || floor.signum() < 0
                    || floor.compareTo(BigDecimal.valueOf(100)) > 0 || subsidyDays < 0
                    || lockS1 < 0 || lockPro < 0 || lockRack < 0) return false;
            return E3_CAPACITY_KEYS.stream().filter(key -> key.startsWith("capacityApplyTo"))
                    .allMatch(key -> "true".equalsIgnoreCase(config.get(key))
                            || "false".equalsIgnoreCase(config.get(key)));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private Map<String, Object> projectE3Capacity(
            CanonicalStateMapper.OwnedDevice device, Map<String, String> config) {
        int ageMonths = device.purchasedAt() == null ? 0 : Math.max(0, Math.toIntExact(ChronoUnit.MONTHS.between(
                device.purchasedAt(), LocalDateTime.now(ZoneId.of("Asia/Shanghai")))));
        String switchKey = e3CapacitySwitch(device);
        if (switchKey == null) throw new BizException(409, "E3_DEVICE_CAPACITY_CLASSIFICATION_MISSING");
        BigDecimal capacityPct = Boolean.parseBoolean(config.get(switchKey))
                ? E3CapacityCurve.capacityPct(ageMonths, config)
                : BigDecimal.valueOf(100).setScale(6, RoundingMode.HALF_UP);
        int subsidyDays = integer(config, "capacitySubsidyDays");
        boolean capacitySubsidized = device.purchasedAt() != null
                && !device.purchasedAt().plusDays(subsidyDays)
                        .isBefore(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        BigDecimal multiplier = capacityPct.movePointLeft(2);
        BigDecimal dailyUsdt = zero(device.dailyUsdt()).multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
        BigDecimal dailyNex = zero(device.dailyNex()).multiply(multiplier).setScale(6, RoundingMode.HALF_UP);
        return linked(
                "id", device.id(), "instanceNo", device.instanceNo(), "name", device.name(),
                "deviceType", device.deviceType(), "productCode", device.productCode(), "status", device.status(),
                "activatedAt", device.activatedAt(), "purchasedAt", device.purchasedAt(),
                "dailyUsdt", dailyUsdt, "dailyNex", dailyNex,
                "gpuModel", device.gpuModel(), "vramTotalGb", device.vramTotalGb(),
                "basePowerW", device.basePowerW(), "location", device.location(),
                "capacityPct", capacityPct, "capacityAgeMonths", ageMonths,
                "capacityConfigKey", switchKey,
                "capacitySubsidized", capacitySubsidized,
                "capacitySubsidyDays", subsidyDays);
    }

    private String e3CapacitySwitch(CanonicalStateMapper.OwnedDevice device) {
        String identity = (String.valueOf(device.productCode()) + " " + String.valueOf(device.deviceType()))
                .toLowerCase(Locale.ROOT).replace("_", "-");
        if (identity.contains("phone") || identity.contains("mobile")) return "capacityApplyToPhone";
        if (identity.contains("cloud")) return "capacityApplyToCloudShare";
        if (identity.contains("pc") || identity.contains("gpu")) return "capacityApplyToPcGpu";
        if (identity.contains("rack-p2") || identity.contains("rackp2")) return "capacityApplyToRackP2";
        if (identity.contains("rack-p1") || identity.contains("rackp1") || identity.contains("rack")) return "capacityApplyToRackP1";
        if (identity.contains("pro-v2") || identity.contains("prov2")) return "capacityApplyToProV2";
        if (identity.contains("pro")) return "capacityApplyToPro";
        if (identity.contains("s1") || identity.contains("box")) return "capacityApplyToS1";
        return null;
    }

    private BigDecimal decimal(Map<String, String> config, String key) {
        return new BigDecimal(config.get(key));
    }

    private int integer(Map<String, String> config, String key) {
        return new BigDecimal(config.get(key)).intValueExact();
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
        return createOrder(userId, clientOrderId, productId, null, quantity, null, idempotencyKey);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String idempotencyKey) {
        return createOrder(userId, clientOrderId, productId, productNo, quantity, null, idempotencyKey);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createOrder(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String voucherId, String idempotencyKey) {
        if (mapper.lockUser(userId) == null) return ApiResult.fail(404, "USER_NOT_FOUND");
        return executeOnce("ORDER_CREATE", userId, idempotencyKey,
                linked("clientOrderId", clientOrderId, "productId", productId,
                        "productNo", productNo, "quantity", quantity, "voucherId", voucherId),
                () -> createOrderInternal(userId, clientOrderId, productId, productNo, quantity, voucherId));
    }

    public ApiResult<Map<String, Object>> orders(Long userId) {
        List<CanonicalStateMapper.UserOrder> rows = mapper.userOrders(userId);
        List<Map<String, Object>> orders = (rows == null ? List.<CanonicalStateMapper.UserOrder>of() : rows)
                .stream().map(this::projectUserOrder).toList();
        return ApiResult.ok(linked("orders", orders,
                "source", "nx_order + nx_order_item + nx_tradein_application + nx_user_device"));
    }

    private Map<String, Object> projectUserOrder(CanonicalStateMapper.UserOrder order) {
        return linked(
                "orderNo", order.orderNo(),
                "productId", order.productId(),
                "productNo", order.productNo(),
                "productName", order.productName(),
                "quantity", order.quantity(),
                "unitPriceUsdt", zero(order.unitPriceUsdt()),
                "discountUsdt", zero(order.discountUsdt()),
                "amountUsdt", zero(order.amountUsdt()),
                "paymentMethod", order.paymentMethod(),
                "paymentStatus", normalizeState(order.paymentStatus(), "PENDING"),
                "orderStatus", normalizeState(order.orderStatus(), "PENDING_PAYMENT"),
                "activationStatus", normalizeState(order.activationStatus(), "WAITING_PAYMENT"),
                "canonicalStatus", canonicalOrderStatus(order),
                "orderType", normalizeState(order.orderType(), "SINGLE"),
                "placedAt", epochMillis(order.placedAt()),
                "paidAt", epochMillis(order.paidAt()),
                "activatedAt", epochMillis(order.activatedAt()),
                "dataCenter", order.dataCenter(),
                "tradeinNo", order.tradeinNo(),
                "sourceDeviceId", order.sourceDeviceId(),
                "targetDeviceId", order.targetDeviceId(),
                "targetDeviceInstanceNo", order.targetDeviceInstanceNo());
    }

    private String canonicalOrderStatus(CanonicalStateMapper.UserOrder order) {
        String orderStatus = normalizeState(order.orderStatus(), "PENDING_PAYMENT");
        String paymentStatus = normalizeState(order.paymentStatus(), "PENDING");
        String activationStatus = normalizeState(order.activationStatus(), "WAITING_PAYMENT");
        if ("COMPLETED".equals(orderStatus) || "ACTIVATED".equals(activationStatus)) return "activated";
        if (orderStatus.contains("CANCEL") || orderStatus.contains("FAIL") || orderStatus.contains("EXPIRE")
                || orderStatus.contains("REFUND") || paymentStatus.contains("FAIL")
                || paymentStatus.contains("EXPIRE") || paymentStatus.contains("REFUND")) return "cancelled";
        if (activationStatus.contains("PROVISION") || orderStatus.contains("PROVISION")) return "provisioning";
        if ("PAID".equals(paymentStatus) || order.paidAt() != null) return "paid";
        return "placed";
    }

    private Long epochMillis(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    private ApiResult<Map<String, Object>> createOrderInternal(
            Long userId, String clientOrderId, Long productId, String productNo,
            Integer quantity, String voucherId) {
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
        CanonicalStateMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        String orderNo = "ORD-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        BigDecimal subtotal = product.priceUsdt().multiply(BigDecimal.valueOf(qty)).setScale(6, RoundingMode.DOWN);
        VoucherRedemption voucher = growthLifecyclePublisher.prepareVoucher(
                userId, voucherId, product.productNo(), subtotal);
        BigDecimal discount = voucher.discountUsdt();
        BigDecimal amount = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(6, RoundingMode.DOWN);
        if (mapper.decrementProductStock(product.id(), qty) != 1) {
            throw new BizException(409, "PRODUCT_STOCK_CONFLICT");
        }
        if (mapper.insertOrder(userId, orderNo, product.id(), qty, subtotal, discount, amount) != 1) {
            throw new BizException(409, "ORDER_CREATE_CONFLICT");
        }
        growthLifecyclePublisher.redeemVoucher(
                userId, voucher, orderNo, product.productNo(), attribution(attribution));
        outboxService.publishUserEvent(
                "ORDER", orderNo, "checkout.started", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), linked(
                "userId", userId,
                "orderId", orderNo,
                "productId", product.id(),
                "quantity", qty,
                "amountUsdt", amount));
        return ApiResult.ok(linked("orderNo", orderNo, "subtotalUsdt", subtotal,
                "discountUsdt", discount, "amountUsdt", amount,
                "voucherId", voucher.voucherId(),
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
            growthLifecyclePublisher.trialChargeAttempted(
                    userId, claim.claimNo(), "FAILED", chargeAmount, "INSUFFICIENT_FUNDS",
                    attribution(mapper.userEventAttribution(userId)));
            return ApiResult.ok(linked("ok", false, "reason", "INSUFFICIENT_FUNDS",
                    "amountUsdt", chargeAmount, "decisionSource", "server"));
        }
        BigDecimal rate = mapper.trialChargeFailRate();
        double boundedRate = rate == null ? 0.01d : Math.max(0d, Math.min(1d, rate.doubleValue()));
        boolean succeeded = RANDOM.nextDouble() >= boundedRate;
        if (!succeeded) {
            growthLifecyclePublisher.trialChargeAttempted(
                    userId, claim.claimNo(), "FAILED", chargeAmount, "SERVER_CHARGE_FAILED",
                    attribution(mapper.userEventAttribution(userId)));
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
        growthLifecyclePublisher.trialChargeAttempted(
                userId, claim.claimNo(), "SUCCEEDED", chargeAmount, "CHARGED",
                attribution(mapper.userEventAttribution(userId)));
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

    private UserAttribution attribution(CanonicalStateMapper.UserEventAttribution value) {
        if (value == null) return null;
        return new UserAttribution(normalizePhase(value.phase()), value.accountAgeMonths(), value.cohort());
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
