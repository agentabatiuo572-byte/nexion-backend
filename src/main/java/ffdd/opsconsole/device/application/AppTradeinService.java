package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.dto.AppTradeinConfigResponse;
import ffdd.opsconsole.device.dto.AppCapacityReplaceQuoteRequest;
import ffdd.opsconsole.device.dto.AppCapacityReplaceQuoteResponse;
import ffdd.opsconsole.device.dto.AppCapacityReplaceSubmitRequest;
import ffdd.opsconsole.device.dto.AppTradeinEligibilityRequest;
import ffdd.opsconsole.device.dto.AppTradeinEligibilityResponse;
import ffdd.opsconsole.device.dto.AppTradeinQuoteRequest;
import ffdd.opsconsole.device.dto.AppTradeinQuoteResponse;
import ffdd.opsconsole.device.dto.AppTradeinSubmitRequest;
import ffdd.opsconsole.device.dto.AppTradeinSubmitResponse;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.StorefrontProductReleasePolicy;
import ffdd.opsconsole.shared.canonical.StorefrontPurchaseGatePolicy;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppTradeinService {
    private static final int MAX_ACTIVE_DEVICES = 6;
    private static final Pattern LEVEL_GATE = Pattern.compile("^L([2-6])\\+\\s*持有者$");
    private static final Set<String> REQUIRED_CONFIG = Set.of(
            "tradeinEnabled", "eligibility",
            "tradeinLadderCut1", "tradeinLadderCut2", "tradeinLadderCut3", "tradeinLadderCut4",
            "tradeinLadderCredit1", "tradeinLadderCredit2", "tradeinLadderCredit3",
            "tradeinLadderCredit4", "tradeinLadderCredit5",
            "tradeinRequireHigherPrice", "tradeinMaxDevicesPerOrder");

    private final AppTradeinMapper mapper;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService outboxService;
    private final AuditLogService auditLogService;
    private final StorefrontProductReleasePolicy productReleasePolicy;
    private final FundsSandboxProfileGuard fundsSandboxProfileGuard;
    private final StorefrontPurchaseGatePolicy purchaseGatePolicy = new StorefrontPurchaseGatePolicy();

    @Transactional(readOnly = true)
    public ApiResult<AppTradeinConfigResponse> config(Long userId) {
        requireProductionTradeinUser(userId);
        if (!StringUtils.hasText(mapper.userLevel(userId))) {
            throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        }
        TradeinPolicy policy = policy();
        return ApiResult.ok(new AppTradeinConfigResponse(
                policy.enabled(), policy.eligibility(), policy.cuts(), policy.credits(),
                policy.requireHigherPrice(), policy.maxDevicesPerOrder(), "nx_compute_e3_config"));
    }

    @Transactional(readOnly = true)
    public ApiResult<AppTradeinEligibilityResponse> eligibility(
            Long userId, AppTradeinEligibilityRequest request) {
        requireProductionTradeinUser(userId);
        String targetProductNo = requireEligibilityTargetNo(request == null ? null : request.targetProductNo());
        String userLevel = mapper.userLevel(userId);
        if (!StringUtils.hasText(userLevel)) {
            throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        }

        TradeinPolicy policy = policy();
        if (!policy.enabled()) {
            return ApiResult.ok(eligibilityResponse(policy, false, "TRADEIN_DISABLED", null,
                    targetProductNo, List.of()));
        }
        if (!isEligible(userLevel, policy.eligibility())) {
            return ApiResult.ok(eligibilityResponse(policy, false, "TRADEIN_ELIGIBILITY_NOT_MET", null,
                    targetProductNo, List.of()));
        }

        AppTradeinMapper.TargetProduct target = mapper.findTargetProduct(null, targetProductNo);
        if (target == null || target.priceUsdt() == null || target.priceUsdt().signum() <= 0) {
            return ApiResult.ok(eligibilityResponse(policy, false, "TARGET_NOT_ACTIVE", null,
                    targetProductNo, List.of()));
        }
        if (target.stock() == null || target.stock() < 1) {
            return ApiResult.ok(eligibilityResponse(policy, false, "TARGET_OUT_OF_STOCK", target,
                    targetProductNo, List.of()));
        }
        StorefrontPurchaseGatePolicy.Decision purchaseDecision = purchaseDecision(userId, target.productNo());
        if (!purchaseDecision.allowed()) {
            return ApiResult.ok(eligibilityResponse(policy, false, purchaseDecision.code(), target,
                    targetProductNo, List.of()));
        }
        StorefrontProductReleasePolicy.Decision release =
                productReleasePolicy.evaluate(target.productNo(), target.unlockPhase());
        if (release == null || !release.available()) {
            return ApiResult.ok(eligibilityResponse(policy, false, "TARGET_NOT_RELEASED", target,
                    targetProductNo, List.of()));
        }

        List<AppTradeinMapper.SourceDevice> candidates = mapper.listTradeinSourceCandidates(userId);
        List<AppTradeinEligibilityResponse.Source> sources = candidates == null ? List.of()
                : candidates.stream().map(source -> eligibilitySource(source, target, policy)).toList();
        boolean eligible = sources.stream().anyMatch(AppTradeinEligibilityResponse.Source::eligible);
        return ApiResult.ok(eligibilityResponse(policy, eligible, eligible ? "ELIGIBLE" : "NO_ELIGIBLE_SOURCE",
                target, targetProductNo, sources));
    }

    @Transactional(readOnly = true)
    public ApiResult<AppTradeinQuoteResponse> quote(Long userId, AppTradeinQuoteRequest request) {
        requireProductionTradeinUser(userId);
        requireRequest(request == null ? null : request.sourceDeviceId(),
                request == null ? null : request.targetProductId(), request == null ? null : request.targetProductNo());
        Evaluation evaluation = evaluate(userId, request.sourceDeviceId(), request.targetProductId(), request.targetProductNo(), false);
        return ApiResult.ok(evaluation.response());
    }

    @Transactional(readOnly = true)
    public ApiResult<AppCapacityReplaceQuoteResponse> capacityQuote(
            Long userId, AppCapacityReplaceQuoteRequest request) {
        requireProductionTradeinUser(userId);
        String targetProductNo = requireTargetProductNo(request == null ? null : request.targetProductNo());
        if (!StringUtils.hasText(mapper.userLevel(userId))) {
            throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        }
        return ApiResult.ok(evaluateCapacity(userId, targetProductNo, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTradeinSubmitResponse> capacityReplace(
            Long userId, String idempotencyKey, AppCapacityReplaceSubmitRequest request) {
        requireProductionTradeinAvailable();
        requireProductionTradeinUser(userId);
        if (request == null || request.sourceDeviceId() == null || request.sourceDeviceId() <= 0) {
            throw new BizException(422, "CAPACITY_REPLACEMENT_SOURCE_REQUIRED");
        }
        String targetProductNo = requireTargetProductNo(request.targetProductNo());
        if (mapper.lockActiveUser(userId) == null) {
            throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        }
        AppCapacityReplaceSubmitRequest normalized = new AppCapacityReplaceSubmitRequest(
                request.sourceDeviceId(), targetProductNo, request.expectedPayableUsdt());
        return executeCapacityOnce(userId, idempotencyKey, normalized,
                () -> capacityReplaceInternal(userId, idempotencyKey, normalized));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTradeinSubmitResponse> submit(
            Long userId, String idempotencyKey, AppTradeinSubmitRequest request) {
        requireProductionTradeinAvailable();
        requireProductionTradeinUser(userId);
        requireRequest(request == null ? null : request.sourceDeviceId(),
                request == null ? null : request.targetProductId(), request == null ? null : request.targetProductNo());
        if (mapper.lockActiveUser(userId) == null) {
            throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        }
        return executeOnce(userId, idempotencyKey, request,
                () -> submitInternal(userId, idempotencyKey, request));
    }

    private ApiResult<AppTradeinSubmitResponse> submitInternal(
            Long userId, String idempotencyKey, AppTradeinSubmitRequest request) {
        Evaluation evaluation = evaluate(userId, request.sourceDeviceId(), request.targetProductId(), request.targetProductNo(), true);
        AppTradeinQuoteResponse quote = evaluation.response();
        if ((request.expectedPayableUsdt() != null
                && money(request.expectedPayableUsdt()).compareTo(quote.payableUsdt()) != 0)
                || (request.expectedDiscountUsdt() != null
                && money(request.expectedDiscountUsdt()).compareTo(quote.discountUsdt()) != 0)) {
            throw new BizException(409, "TRADEIN_QUOTE_CHANGED");
        }
        if (!quote.sufficientFunds()) {
            throw new BizException(409, "TRADEIN_INSUFFICIENT_FUNDS");
        }
        AppTradeinMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "TRADEIN_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        reservePurchaseQuotaAtSettlement(userId, evaluation.target().productNo());

        String nonce = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String tradeinNo = "TIN-" + nonce;
        String orderNo = "TIO-" + nonce;
        String instanceNo = "DEV-TI-" + nonce;
        BigDecimal balanceAfter = quote.walletBalanceUsdt().subtract(quote.payableUsdt()).setScale(6, RoundingMode.HALF_UP);

        if (quote.payableUsdt().signum() > 0) {
            if (mapper.debitWalletUsdt(userId, quote.payableUsdt()) != 1) {
                throw new BizException(409, "TRADEIN_WALLET_CONFLICT");
            }
        }
        if (mapper.insertWalletLedger(orderNo, userId, quote.payableUsdt(), balanceAfter) != 1) {
            throw new BizException(409, "TRADEIN_D4_LEDGER_CONFLICT");
        }
        if (mapper.decrementTargetStock(evaluation.target().id()) != 1) {
            throw new BizException(409, "TRADEIN_TARGET_STOCK_CONFLICT");
        }

        AppTradeinMapper.PaidOrderWrite order = new AppTradeinMapper.PaidOrderWrite(
                userId, orderNo, evaluation.target().id(), evaluation.target().productNo(),
                evaluation.target().name(), quote.targetPriceUsdt(), quote.discountUsdt(), quote.payableUsdt());
        if (mapper.insertPaidOrder(order) != 1 || mapper.insertPaidOrderItem(order) != 1) {
            throw new BizException(409, "TRADEIN_ORDER_CREATE_CONFLICT");
        }
        if (mapper.recycleSourceDevice(userId, evaluation.source().id()) != 1) {
            throw new BizException(409, "TRADEIN_SOURCE_STATE_CONFLICT");
        }

        AppTradeinMapper.DeliveredDeviceWrite delivered = new AppTradeinMapper.DeliveredDeviceWrite(
                userId, orderNo, evaluation.target().id(), evaluation.target().productNo(),
                evaluation.target().tier(), instanceNo, evaluation.target().name(), evaluation.target().deviceType(),
                evaluation.target().generation(), evaluation.target().gpuModel(), evaluation.target().vramTotalGb(),
                nz(evaluation.target().hashrate()), nz(evaluation.target().dailyUsdt()), nz(evaluation.target().dailyNex()),
                quote.targetPriceUsdt());
        if (mapper.insertTargetDevice(delivered) != 1) {
            throw new BizException(409, "TRADEIN_TARGET_DELIVERY_CONFLICT");
        }
        Long targetDeviceId = mapper.findDeviceIdByInstanceNo(instanceNo);
        if (targetDeviceId == null || targetDeviceId <= 0) {
            throw new BizException(409, "TRADEIN_TARGET_DELIVERY_NOT_FOUND");
        }

        AppTradeinMapper.TradeinApplicationWrite application = new AppTradeinMapper.TradeinApplicationWrite(
                tradeinNo, idempotencyKey.trim(), userId, evaluation.source().id(), evaluation.source().instanceNo(),
                evaluation.source().productId(), evaluation.source().productName(), evaluation.source().productTier(),
                evaluation.target().id(), evaluation.target().name(), evaluation.target().tier(),
                quote.sourceActualPaidUsdt(), quote.targetPriceUsdt(), quote.cumulativeOutputUsdt(),
                quote.outputRatioPct(), quote.creditRatePct(), quote.discountUsdt(), quote.payableUsdt(),
                orderNo, targetDeviceId);
        if (mapper.insertTradeinApplication(application) != 1
                || mapper.insertTradeinCompatibilityOrder(application) != 1) {
            throw new BizException(409, "TRADEIN_APPLICATION_CREATE_CONFLICT");
        }

        Map<String, Object> event = linked(
                "tradeinNo", tradeinNo,
                "sourceDeviceId", evaluation.source().id(),
                "targetProductId", evaluation.target().id(),
                "targetDeviceId", targetDeviceId,
                "orderNo", orderNo,
                "outputRatioPct", quote.outputRatioPct(),
                "creditRatePct", quote.creditRatePct(),
                "discountUsdt", quote.discountUsdt(),
                "walletDebitUsdt", quote.payableUsdt());
        outboxService.publishUserEvent(
                "TRADEIN", tradeinNo, "tradein.completed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        Map<String, Object> completedOrder = linked(
                "orderId", orderNo,
                "orderNo", orderNo,
                "orderSubtotalUsdt", quote.payableUsdt());
        outboxService.publishUserEvent(
                "ORDER", orderNo, "checkout.completed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), completedOrder);
        outboxService.publishUserEvent(
                "DEVICE_ORDER", orderNo, "device.purchase_completed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), linked("orderId", orderNo));
        auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_TRADEIN_COMPLETED")
                .resourceType("TRADEIN_APPLICATION")
                .resourceId(tradeinNo)
                .bizNo(orderNo)
                .userId(userId)
                .actorId(userId)
                .actorType("USER")
                .actorUsername("user:" + userId)
                .method("POST")
                .path("/api/app/trade-in/submit")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(linked(
                        "idempotencyKey", idempotencyKey.trim(),
                        "before", linked("sourceDeviceId", evaluation.source().id(), "status", evaluation.source().status()),
                        "after", linked("targetDeviceId", targetDeviceId, "status", "ACTIVE"),
                        "pricing", event,
                        "discountCreditedToWallet", false))
                .build());

        return ApiResult.ok(new AppTradeinSubmitResponse(
                tradeinNo, orderNo, evaluation.source().id(), targetDeviceId,
                "COMPLETED", "COMPLETED", quote.discountUsdt(), quote.payableUsdt(), balanceAfter));
    }

    private ApiResult<AppTradeinSubmitResponse> capacityReplaceInternal(
            Long userId, String idempotencyKey, AppCapacityReplaceSubmitRequest request) {
        AppCapacityReplaceQuoteResponse quote = evaluateCapacity(userId, request.targetProductNo(), true);
        if (!"REPLACE_REQUIRED".equals(quote.decision()) || quote.sourceDeviceId() == null) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_NOT_REQUIRED");
        }
        if (!quote.sourceDeviceId().equals(request.sourceDeviceId())) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_SOURCE_CHANGED");
        }
        if (request.expectedPayableUsdt() == null
                || money(request.expectedPayableUsdt()).compareTo(quote.payableUsdt()) != 0) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_QUOTE_CHANGED");
        }
        if (!quote.sufficientFunds()) throw new BizException(409, "TRADEIN_INSUFFICIENT_FUNDS");

        AppTradeinMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "TRADEIN_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        AppTradeinMapper.SourceDevice source = mapper.lockCapacityReplacementSource(userId);
        if (source == null || !source.id().equals(request.sourceDeviceId())) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_SOURCE_CHANGED");
        }
        AppTradeinMapper.TargetProduct target = mapper.lockTargetProduct(null, request.targetProductNo());
        if (target == null || target.stock() == null || target.stock() < 1) {
            throw new BizException(409, "TRADEIN_TARGET_NOT_ACTIVE");
        }
        reservePurchaseQuotaAtSettlement(userId, target.productNo());

        String nonce = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String tradeinNo = "CPR-" + nonce;
        String orderNo = "CPO-" + nonce;
        String instanceNo = "DEV-CPR-" + nonce;
        BigDecimal balanceAfter = quote.walletBalanceUsdt().subtract(quote.payableUsdt())
                .setScale(6, RoundingMode.HALF_UP);
        if (quote.payableUsdt().signum() > 0 && mapper.debitWalletUsdt(userId, quote.payableUsdt()) != 1) {
            throw new BizException(409, "TRADEIN_WALLET_CONFLICT");
        }
        if (mapper.insertWalletLedger(orderNo, userId, quote.payableUsdt(), balanceAfter) != 1
                || mapper.decrementTargetStock(target.id()) != 1) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_PAYMENT_CONFLICT");
        }

        AppTradeinMapper.PaidOrderWrite order = new AppTradeinMapper.PaidOrderWrite(
                userId, orderNo, target.id(), target.productNo(), target.name(),
                quote.targetPriceUsdt(), BigDecimal.ZERO.setScale(6), quote.payableUsdt());
        if (mapper.insertPaidOrder(order) != 1 || mapper.insertPaidOrderItem(order) != 1) {
            throw new BizException(409, "TRADEIN_ORDER_CREATE_CONFLICT");
        }
        if (mapper.moveSourceDeviceToInventory(userId, source.id()) != 1) {
            throw new BizException(409, "CAPACITY_REPLACEMENT_SOURCE_CONFLICT");
        }
        AppTradeinMapper.DeliveredDeviceWrite delivered = new AppTradeinMapper.DeliveredDeviceWrite(
                userId, orderNo, target.id(), target.productNo(), target.tier(), instanceNo, target.name(),
                target.deviceType(), target.generation(), target.gpuModel(), target.vramTotalGb(),
                nz(target.hashrate()), nz(target.dailyUsdt()), nz(target.dailyNex()), quote.targetPriceUsdt());
        if (mapper.insertTargetDevice(delivered) != 1) {
            throw new BizException(409, "TRADEIN_TARGET_DELIVERY_CONFLICT");
        }
        Long targetDeviceId = mapper.findDeviceIdByInstanceNo(instanceNo);
        if (targetDeviceId == null || targetDeviceId <= 0) {
            throw new BizException(409, "TRADEIN_TARGET_DELIVERY_NOT_FOUND");
        }
        AppTradeinMapper.TradeinApplicationWrite application = new AppTradeinMapper.TradeinApplicationWrite(
                tradeinNo, idempotencyKey.trim(), userId, source.id(), source.instanceNo(), source.productId(),
                source.productName(), source.productTier(), target.id(), target.name(), target.tier(),
                money(nz(source.actualPaidUsdt())), quote.targetPriceUsdt(), BigDecimal.ZERO.setScale(6),
                BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6),
                quote.payableUsdt(), orderNo, targetDeviceId);
        if (mapper.insertTradeinApplication(application) != 1
                || mapper.insertTradeinCompatibilityOrder(application) != 1) {
            throw new BizException(409, "TRADEIN_APPLICATION_CREATE_CONFLICT");
        }

        Map<String, Object> event = linked(
                "tradeinNo", tradeinNo, "sourceDeviceId", source.id(), "targetDeviceId", targetDeviceId,
                "orderNo", orderNo, "walletDebitUsdt", quote.payableUsdt(), "operation", "CAPACITY_REPLACE");
        outboxService.publishUserEvent(
                "TRADEIN", tradeinNo, "capacity_replacement.completed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        outboxService.publishUserEvent(
                "ORDER", orderNo, "checkout.completed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), linked(
                        "orderId", orderNo, "orderNo", orderNo, "orderSubtotalUsdt", quote.payableUsdt()));
        outboxService.publishUserEvent(
                "DEVICE_ORDER", orderNo, "device.purchase_completed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), linked("orderId", orderNo));
        auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("USER_CAPACITY_REPLACEMENT_COMPLETED")
                .resourceType("TRADEIN_APPLICATION").resourceId(tradeinNo).bizNo(orderNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .method("POST").path("/api/app/trade-in/capacity-replace")
                .result("SUCCESS").riskLevel("HIGH")
                .detail(linked("idempotencyScope", "USER_SCOPED", "decisionSource", "server",
                        "before", linked("sourceDeviceId", source.id(), "status", source.status()),
                        "after", linked("targetDeviceId", targetDeviceId, "status", "ACTIVE")))
                .build());
        return ApiResult.ok(new AppTradeinSubmitResponse(
                tradeinNo, orderNo, source.id(), targetDeviceId, "COMPLETED", "COMPLETED",
                BigDecimal.ZERO.setScale(6), quote.payableUsdt(), balanceAfter));
    }

    private AppCapacityReplaceQuoteResponse evaluateCapacity(
            Long userId, String targetProductNo, boolean locked) {
        int activeDevices = Math.max(0, mapper.countActiveDevices(userId));
        AppTradeinMapper.TargetProduct target = locked
                ? mapper.lockTargetProduct(null, targetProductNo)
                : mapper.findTargetProduct(null, targetProductNo);
        if (target == null || target.priceUsdt() == null || target.priceUsdt().signum() <= 0
                || target.stock() == null || target.stock() < 1) {
            throw new BizException(409, "TRADEIN_TARGET_NOT_ACTIVE");
        }
        requireReleased(target);
        requirePurchaseEligibility(userId, target.productNo());
        BigDecimal targetPrice = money(target.priceUsdt());
        BigDecimal wallet = money(nz(locked ? mapper.lockWalletBalanceUsdt(userId) : mapper.walletBalanceUsdt(userId)));
        AppTradeinMapper.SourceDevice source = null;
        String decision = "CAPACITY_AVAILABLE";
        if (activeDevices >= MAX_ACTIVE_DEVICES) {
            source = locked ? mapper.lockCapacityReplacementSource(userId) : mapper.findCapacityReplacementSource(userId);
            decision = source == null ? "NO_ACTIVE_DEVICE" : "REPLACE_REQUIRED";
        }
        return new AppCapacityReplaceQuoteResponse(
                decision, activeDevices, MAX_ACTIVE_DEVICES,
                source == null ? null : source.id(), source == null ? null : source.productName(),
                target.id(), target.productNo(), target.name(), targetPrice, targetPrice, wallet,
                wallet.compareTo(targetPrice) >= 0, "server");
    }

    private Evaluation evaluate(
            Long userId, Long sourceDeviceId, Long targetProductId, String targetProductNo, boolean locked) {
        TradeinPolicy policy = policy();
        if (!policy.enabled()) throw new BizException(409, "TRADEIN_DISABLED");
        if (policy.maxDevicesPerOrder() < 1) throw new BizException(409, "TRADEIN_DEVICE_COUNT_EXCEEDED");
        requireEligibility(mapper.userLevel(userId), policy.eligibility());

        AppTradeinMapper.SourceDevice source = locked
                ? mapper.lockSourceDevice(userId, sourceDeviceId)
                : mapper.findSourceDevice(userId, sourceDeviceId);
        if (source == null) throw new BizException(409, "TRADEIN_SOURCE_NOT_ACTIVE_OR_NOT_OWNED");
        if (source.productId() == null || source.productId() <= 0
                || source.actualPaidUsdt() == null || source.actualPaidUsdt().signum() <= 0) {
            throw new BizException(409, "TRADEIN_SOURCE_PAID_PRICE_UNAVAILABLE");
        }
        String productNo = normalizeProductNo(targetProductNo);
        AppTradeinMapper.TargetProduct target = locked
                ? mapper.lockTargetProduct(targetProductId, productNo)
                : mapper.findTargetProduct(targetProductId, productNo);
        if (target == null || target.priceUsdt() == null || target.priceUsdt().signum() <= 0) {
            throw new BizException(409, "TRADEIN_TARGET_NOT_ACTIVE");
        }
        if (target.id().equals(source.productId())) {
            throw new BizException(409, "TRADEIN_TARGET_MUST_DIFFER");
        }
        if (target.stock() == null || target.stock() < 1) {
            throw new BizException(409, "TRADEIN_TARGET_OUT_OF_STOCK");
        }
        requireReleased(target);
        requirePurchaseEligibility(userId, target.productNo());

        BigDecimal sourcePaid = money(source.actualPaidUsdt());
        BigDecimal targetPrice = money(target.priceUsdt());
        if (policy.requireHigherPrice() && targetPrice.compareTo(sourcePaid) <= 0) {
            throw new BizException(409, "TRADEIN_HIGHER_PRICE_REQUIRED");
        }
        BigDecimal output = money(nz(mapper.cumulativeDeviceOutputUsdt(source.id())));
        BigDecimal ratio = output.multiply(BigDecimal.valueOf(100))
                .divide(sourcePaid, 6, RoundingMode.HALF_UP);
        BigDecimal creditRate = creditRate(policy, ratio);
        BigDecimal discount = sourcePaid.multiply(creditRate)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .min(targetPrice);
        BigDecimal payable = targetPrice.subtract(discount).setScale(6, RoundingMode.HALF_UP);
        BigDecimal wallet = money(nz(locked ? mapper.lockWalletBalanceUsdt(userId) : mapper.walletBalanceUsdt(userId)));
        BigDecimal shortfall = payable.subtract(wallet).max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
        AppTradeinQuoteResponse response = new AppTradeinQuoteResponse(
                source.id(), source.productName(), target.id(), target.productNo(), target.name(), sourcePaid, output, ratio,
                creditRate, discount, targetPrice, payable, wallet, shortfall,
                shortfall.signum() == 0, false, "nx_compute_receipt + source paid price + nx_compute_e3_config");
        return new Evaluation(source, target, response);
    }

    private void requireReleased(AppTradeinMapper.TargetProduct target) {
        StorefrontProductReleasePolicy.Decision release =
                productReleasePolicy.evaluate(target.productNo(), target.unlockPhase());
        if (release == null || !release.available()) {
            throw new BizException(409, "TRADEIN_TARGET_NOT_RELEASED");
        }
    }

    private void requirePurchaseEligibility(Long userId, String productNo) {
        StorefrontPurchaseGatePolicy.Decision decision = purchaseDecision(userId, productNo);
        if (!decision.allowed()) throw new BizException(403, decision.code());
    }

    private void reservePurchaseQuotaAtSettlement(Long userId, String productNo) {
        String raw = mapper.purchaseGateJson(productNo);
        if (!StringUtils.hasText(raw)) return;
        StorefrontPurchaseGatePolicy.Decision decision = purchaseDecision(userId, productNo, raw);
        if (!decision.allowed()) throw new BizException(403, decision.code());
        if (purchaseGatePolicy.hasQuota(raw) && mapper.consumePurchaseQuota(productNo, 1) != 1) {
            throw new BizException(409, "PURCHASE_GATE_SOLD_OUT");
        }
    }

    private StorefrontPurchaseGatePolicy.Decision purchaseDecision(Long userId, String productNo) {
        String raw = mapper.purchaseGateJson(productNo);
        if (!StringUtils.hasText(raw)) return StorefrontPurchaseGatePolicy.Decision.open();
        return purchaseDecision(userId, productNo, raw);
    }

    private StorefrontPurchaseGatePolicy.Decision purchaseDecision(
            Long userId, String productNo, String raw) {
        AppTradeinMapper.PurchaseGateFacts facts = mapper.purchaseGateFacts(userId);
        try {
            return purchaseGatePolicy.evaluate(raw, facts == null ? null : new StorefrontPurchaseGatePolicy.Facts(
                    facts.rank() == null ? 0 : facts.rank(),
                    facts.activeDirect() == null ? 0 : facts.activeDirect(),
                    facts.teamVolumeUsd() == null ? BigDecimal.ZERO : facts.teamVolumeUsd()));
        } catch (IllegalArgumentException ex) {
            return StorefrontPurchaseGatePolicy.Decision.closed("PURCHASE_GATE_FACTS_INVALID");
        }
    }

    private TradeinPolicy policy() {
        List<AppTradeinMapper.ConfigRow> rows = mapper.listTradeinConfig();
        Map<String, String> values = new LinkedHashMap<>();
        if (rows != null) {
            for (AppTradeinMapper.ConfigRow row : rows) {
                if (row != null && StringUtils.hasText(row.configKey()) && row.configValue() != null) {
                    values.put(row.configKey(), row.configValue().trim());
                }
            }
        }
        if (!values.keySet().containsAll(REQUIRED_CONFIG)) {
            throw new BizException(503, "E3_TRADEIN_CONFIG_INCOMPLETE");
        }
        List<BigDecimal> cuts = List.of(
                decimal(values, "tradeinLadderCut1"), decimal(values, "tradeinLadderCut2"),
                decimal(values, "tradeinLadderCut3"), decimal(values, "tradeinLadderCut4"));
        List<BigDecimal> credits = List.of(
                decimal(values, "tradeinLadderCredit1"), decimal(values, "tradeinLadderCredit2"),
                decimal(values, "tradeinLadderCredit3"), decimal(values, "tradeinLadderCredit4"),
                decimal(values, "tradeinLadderCredit5"));
        if (!strictlyIncreasing(cuts) || !strictlyDecreasing(credits)
                || cuts.get(0).signum() < 0 || cuts.get(3).compareTo(BigDecimal.valueOf(100)) > 0
                || credits.stream().anyMatch(value -> value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BizException(503, "E3_TRADEIN_LADDER_INVALID");
        }
        int maxDevices;
        try {
            maxDevices = Integer.parseInt(values.get("tradeinMaxDevicesPerOrder"));
        } catch (RuntimeException ex) {
            throw new BizException(503, "E3_TRADEIN_MAX_DEVICES_INVALID");
        }
        String eligibility = values.get("eligibility");
        if (!("全部用户".equals(eligibility) || LEVEL_GATE.matcher(eligibility).matches())) {
            throw new BizException(503, "E3_TRADEIN_ELIGIBILITY_INVALID");
        }
        return new TradeinPolicy(
                bool(values.get("tradeinEnabled"), "E3_TRADEIN_ENABLED_INVALID"), eligibility,
                cuts, credits, bool(values.get("tradeinRequireHigherPrice"), "E3_TRADEIN_PRICE_GATE_INVALID"),
                maxDevices);
    }

    private BigDecimal creditRate(TradeinPolicy policy, BigDecimal ratio) {
        for (int i = 0; i < policy.cuts().size(); i++) {
            // Admin/App wording defines [0, cut1), [cut1, cut2) ... [cut4, +inf).
            // Equality therefore belongs to the next band, not the previous one.
            if (ratio.compareTo(policy.cuts().get(i)) < 0) return policy.credits().get(i);
        }
        return policy.credits().get(policy.credits().size() - 1);
    }

    private void requireEligibility(String userLevel, String eligibility) {
        if (!isEligible(userLevel, eligibility)) {
            throw new BizException(403, "TRADEIN_ELIGIBILITY_NOT_MET");
        }
    }

    private boolean isEligible(String userLevel, String eligibility) {
        if ("全部用户".equals(eligibility)) return true;
        Matcher gate = LEVEL_GATE.matcher(eligibility);
        Matcher actual = Pattern.compile("^L([1-9][0-9]*)$").matcher(StringUtils.hasText(userLevel) ? userLevel.trim() : "");
        return gate.matches() && actual.matches()
                && Integer.parseInt(actual.group(1)) >= Integer.parseInt(gate.group(1));
    }

    private String requireEligibilityTargetNo(String targetProductNo) {
        String normalized = normalizeProductNo(targetProductNo);
        if (normalized == null || !normalized.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BizException(422, "TRADEIN_TARGET_REQUIRED");
        }
        return normalized;
    }

    private AppTradeinEligibilityResponse eligibilityResponse(
            TradeinPolicy policy,
            boolean eligible,
            String decisionCode,
            AppTradeinMapper.TargetProduct target,
            String targetProductNo,
            List<AppTradeinEligibilityResponse.Source> sources) {
        return new AppTradeinEligibilityResponse(
                policy.enabled(), eligible, decisionCode,
                target == null ? null : target.id(),
                target == null ? targetProductNo : target.productNo(),
                target == null ? null : target.name(),
                target == null ? null : money(target.priceUsdt()),
                policy.requireHigherPrice(), policy.maxDevicesPerOrder(), sources, "server");
    }

    private AppTradeinEligibilityResponse.Source eligibilitySource(
            AppTradeinMapper.SourceDevice source,
            AppTradeinMapper.TargetProduct target,
            TradeinPolicy policy) {
        String reasonCode = "OK";
        boolean eligible = true;
        if (source == null || source.productId() == null || source.productId() <= 0
                || source.actualPaidUsdt() == null || source.actualPaidUsdt().signum() <= 0) {
            eligible = false;
            reasonCode = "SOURCE_PAID_PRICE_UNAVAILABLE";
        } else if (target.id().equals(source.productId())) {
            eligible = false;
            reasonCode = "TARGET_MUST_DIFFER";
        } else if (policy.requireHigherPrice()
                && money(target.priceUsdt()).compareTo(money(source.actualPaidUsdt())) <= 0) {
            eligible = false;
            reasonCode = "HIGHER_PRICE_REQUIRED";
        }
        return new AppTradeinEligibilityResponse.Source(
                source == null ? null : source.id(), source == null ? null : source.productName(), eligible, reasonCode);
    }

    private BigDecimal decimal(Map<String, String> values, String key) {
        try {
            return new BigDecimal(values.get(key));
        } catch (RuntimeException ex) {
            throw new BizException(503, "E3_TRADEIN_CONFIG_VALUE_INVALID");
        }
    }

    private boolean bool(String value, String error) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        throw new BizException(503, error);
    }

    private boolean strictlyIncreasing(List<BigDecimal> values) {
        for (int i = 1; i < values.size(); i++) if (values.get(i - 1).compareTo(values.get(i)) >= 0) return false;
        return true;
    }

    private boolean strictlyDecreasing(List<BigDecimal> values) {
        for (int i = 1; i < values.size(); i++) if (values.get(i - 1).compareTo(values.get(i)) <= 0) return false;
        return true;
    }

    private void requireProductionTradeinUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        Integer sandbox = mapper.activeUserEnvironment(userId);
        if (sandbox == null) throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        if (sandbox != 0) throw new BizException(409, "TRADEIN_SANDBOX_UNAVAILABLE");
    }

    /**
     * Trade-in settlement still writes the canonical wallet, stock and device
     * tables. It must never be reachable while the isolated commerce wallet is
     * enabled; the local sandbox has its own order/payment rail and no trade-in
     * production fallback.
     */
    private void requireProductionTradeinAvailable() {
        if (fundsSandboxProfileGuard == null || fundsSandboxProfileGuard.isLocalSandboxEnabled()) {
            throw new BizException(409, "TRADEIN_LOCAL_SANDBOX_UNAVAILABLE");
        }
        if (!fundsSandboxProfileGuard.isStrictProductionRuntime()) {
            throw new BizException(503, "TRADEIN_RUNTIME_UNAVAILABLE");
        }
    }

    private void requireRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo) {
        boolean validId = targetProductId != null && targetProductId > 0;
        boolean validNo = StringUtils.hasText(targetProductNo)
                && targetProductNo.trim().matches("[A-Za-z0-9._:-]{1,64}");
        if (sourceDeviceId == null || sourceDeviceId <= 0 || (!validId && !validNo)
                || (targetProductId != null && !validId)
                || (StringUtils.hasText(targetProductNo) && !validNo)) {
            throw new BizException(422, "TRADEIN_SOURCE_AND_TARGET_REQUIRED");
        }
    }

    private String normalizeProductNo(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String requireTargetProductNo(String value) {
        String normalized = normalizeProductNo(value);
        if (normalized == null || !normalized.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new BizException(422, "CAPACITY_REPLACEMENT_TARGET_REQUIRED");
        }
        return normalized;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<AppTradeinSubmitResponse> executeOnce(
            Long userId, String idempotencyKey, AppTradeinSubmitRequest request,
            Supplier<ApiResult<AppTradeinSubmitResponse>> action) {
        return (ApiResult<AppTradeinSubmitResponse>) (ApiResult) idempotencyService.execute(
                "APP:E3_TRADEIN_SUBMIT:USER:" + userId, idempotencyKey,
                sha256(String.valueOf(request)), ApiResult.class, (Supplier) action);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<AppTradeinSubmitResponse> executeCapacityOnce(
            Long userId, String idempotencyKey, AppCapacityReplaceSubmitRequest request,
            Supplier<ApiResult<AppTradeinSubmitResponse>> action) {
        return (ApiResult<AppTradeinSubmitResponse>) (ApiResult) idempotencyService.execute(
                "APP:E3_CAPACITY_REPLACE:USER:" + userId, idempotencyKey,
                sha256(String.valueOf(request)), ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String normalizePhase(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "P1";
        if (normalized.matches("[1-6]")) normalized = "P" + normalized;
        return normalized.matches("P[1-6]") ? normalized : "P1";
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

    private record TradeinPolicy(
            boolean enabled, String eligibility, List<BigDecimal> cuts, List<BigDecimal> credits,
            boolean requireHigherPrice, int maxDevicesPerOrder) {
    }

    private record Evaluation(
            AppTradeinMapper.SourceDevice source,
            AppTradeinMapper.TargetProduct target,
            AppTradeinQuoteResponse response) {
    }
}
