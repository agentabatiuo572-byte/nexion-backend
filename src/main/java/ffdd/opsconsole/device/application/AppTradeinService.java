package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.dto.AppTradeinConfigResponse;
import ffdd.opsconsole.device.dto.AppTradeinQuoteRequest;
import ffdd.opsconsole.device.dto.AppTradeinQuoteResponse;
import ffdd.opsconsole.device.dto.AppTradeinSubmitRequest;
import ffdd.opsconsole.device.dto.AppTradeinSubmitResponse;
import ffdd.opsconsole.device.mapper.AppTradeinMapper;
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

    @Transactional(readOnly = true)
    public ApiResult<AppTradeinConfigResponse> config(Long userId) {
        requireUser(userId);
        if (!StringUtils.hasText(mapper.userLevel(userId))) {
            throw new BizException(404, "TRADEIN_USER_NOT_FOUND");
        }
        TradeinPolicy policy = policy();
        return ApiResult.ok(new AppTradeinConfigResponse(
                policy.enabled(), policy.eligibility(), policy.cuts(), policy.credits(),
                policy.requireHigherPrice(), policy.maxDevicesPerOrder(), "nx_compute_e3_config"));
    }

    @Transactional(readOnly = true)
    public ApiResult<AppTradeinQuoteResponse> quote(Long userId, AppTradeinQuoteRequest request) {
        requireUser(userId);
        requireRequest(request == null ? null : request.sourceDeviceId(),
                request == null ? null : request.targetProductId(), request == null ? null : request.targetProductNo());
        Evaluation evaluation = evaluate(userId, request.sourceDeviceId(), request.targetProductId(), request.targetProductNo(), false);
        return ApiResult.ok(evaluation.response());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<AppTradeinSubmitResponse> submit(
            Long userId, String idempotencyKey, AppTradeinSubmitRequest request) {
        requireUser(userId);
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
        if (!quote.sufficientFunds()) {
            throw new BizException(409, "TRADEIN_INSUFFICIENT_FUNDS");
        }
        AppTradeinMapper.UserEventAttribution attribution = mapper.userEventAttribution(userId);
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "TRADEIN_EVENT_ATTRIBUTION_UNAVAILABLE");
        }

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
            if (ratio.compareTo(policy.cuts().get(i)) <= 0) return policy.credits().get(i);
        }
        return policy.credits().get(policy.credits().size() - 1);
    }

    private void requireEligibility(String userLevel, String eligibility) {
        if ("全部用户".equals(eligibility)) return;
        Matcher gate = LEVEL_GATE.matcher(eligibility);
        Matcher actual = Pattern.compile("^L([1-9][0-9]*)$").matcher(StringUtils.hasText(userLevel) ? userLevel.trim() : "");
        if (!gate.matches() || !actual.matches() || Integer.parseInt(actual.group(1)) < Integer.parseInt(gate.group(1))) {
            throw new BizException(403, "TRADEIN_ELIGIBILITY_NOT_MET");
        }
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

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<AppTradeinSubmitResponse> executeOnce(
            Long userId, String idempotencyKey, AppTradeinSubmitRequest request,
            Supplier<ApiResult<AppTradeinSubmitResponse>> action) {
        return (ApiResult<AppTradeinSubmitResponse>) (ApiResult) idempotencyService.execute(
                "APP:E3_TRADEIN_SUBMIT:USER:" + userId, idempotencyKey,
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
