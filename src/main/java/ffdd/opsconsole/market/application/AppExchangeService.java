package ffdd.opsconsole.market.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
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
import java.time.Instant;
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

@Service
@RequiredArgsConstructor
public class AppExchangeService {
    private static final String USER_CAP = "wallet.exchange.user_daily_cap_usdt";
    private static final String PLATFORM_CAP = "wallet.exchange.platform_daily_cap_usdt";
    private static final String FEE_PCT = "wallet.exchange.fee_pct";
    private static final String FEE_MIN = "wallet.exchange.fee_min_usdt";
    private static final String QUEUE_MODE = "wallet.exchange.queue_mode";
    private static final String KYC_THRESHOLD = "wallet.exchange.kyc_threshold_usdt";
    private static final String CURVE = "wallet.nex_market.weekly_curve";
    private static final String COST_BASIS = "wallet.nex_market.cost_basis";
    private static final String EXCHANGE_KILL = "killswitch.exchange";
    private static final String EXCHANGE_KILL_LEGACY = "emergency.killswitch.exchange";

    private final AppExchangeMapper mapper;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final RiskKycReviewFacade riskKycReviewFacade;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiResult<Map<String, Object>> caps() {
        BigDecimal price = requirePrice();
        return ApiResult.ok(linked(
                "asset", "NEX", "currency", "USDT", "currentPrice", price,
                "userDailyCapUsdt", number(USER_CAP, "50"),
                "platformDailyCapUsdt", number(PLATFORM_CAP, "20000"),
                "feePct", number(FEE_PCT, "0"), "feeMinUsdt", number(FEE_MIN, "0.50"),
                "queueMode", text(QUEUE_MODE, "QUEUE"),
                "kycLifetimeThresholdUsdt", number(KYC_THRESHOLD, "100"),
                "swapEnabled", swapEnabled(), "serverCanonical", true,
                "source", "G2/G3 server configuration"));
    }

    public ApiResult<Map<String, Object>> market() {
        List<NexMarketCurveFrame> frames = curveFrames();
        BigDecimal price = requirePrice();
        List<AppExchangeMapper.MarketPoint> recentPoints = mapper.recentMarketPoints();
        List<Map<String,Object>> history24h = (recentPoints == null ? List.<AppExchangeMapper.MarketPoint>of() : recentPoints).stream()
                .map(point -> linked("price", point.priceUsdt(), "sampledAt", point.sampledAt()))
                .toList();
        return ApiResult.ok(linked(
                "asset", "NEX", "currency", "USDT", "currentPrice", price,
                "costBasis", number(COST_BASIS, "0.085"),
                "frames", frames, "sparkline", frames.stream().map(NexMarketCurveFrame::targetPrice).toList(),
                "history24h", history24h,
                "sampledAt", LocalDateTime.now(clock), "serverCanonical", true,
                "source", "G3 weekly_curve + nx_price_index 24h history"));
    }

    public ApiResult<Map<String, Object>> state(Long userId) {
        requireUser(userId);
        AppExchangeMapper.WalletGateRow wallet = mapper.lockWalletGate(userId);
        if (wallet == null) throw new BizException(409, "EXCHANGE_WALLET_NOT_FOUND");
        return ApiResult.ok(stateMap(userId, wallet));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> swap(Long userId, String idempotencyKey, SwapRequest request) {
        requireUser(userId);
        NormalizedSwap normalized = normalize(request);
        return executeOnce("SWAP:USER:" + userId, idempotencyKey, normalized,
                () -> swapInternal(userId, idempotencyKey, normalized));
    }

    private ApiResult<Map<String, Object>> swapInternal(Long userId, String idempotencyKey, NormalizedSwap request) {
        String userNo = mapper.lockActiveUserNo(userId);
        if (!StringUtils.hasText(userNo)) throw new BizException(404, "USER_NOT_FOUND");
        AppExchangeMapper.WalletGateRow wallet = mapper.lockWalletGate(userId);
        if (wallet == null) throw new BizException(409, "EXCHANGE_WALLET_NOT_FOUND");
        if (!swapEnabled()) throw new BizException(409, "EXCHANGE_SWAP_PAUSED");

        BigDecimal price = requirePrice();
        BigDecimal grossUsdt = "USDT".equals(request.fromAsset())
                ? request.fromAmount() : request.fromAmount().multiply(price);
        grossUsdt = money(grossUsdt);
        BigDecimal lifetimeUsdt = nz(mapper.userLifetimeUsdt(userId));
        BigDecimal kycThresholdUsdt = number(KYC_THRESHOLD, "100");
        String gate = gate(userId, wallet, grossUsdt, lifetimeUsdt, kycThresholdUsdt);
        if (gate != null) return gatedOrder(userId, userNo, idempotencyKey, request, price, grossUsdt,
                lifetimeUsdt.add(grossUsdt), kycThresholdUsdt, wallet.kycStatus(), gate);

        BigDecimal feeRate = number(FEE_PCT, "0");
        BigDecimal fee = feeRate.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : money(grossUsdt.multiply(feeRate)
                .divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP))
                .max(money(number(FEE_MIN, "0.50")));
        if (fee.compareTo(grossUsdt) >= 0) throw new BizException(422, "EXCHANGE_AMOUNT_BELOW_FEE");
        BigDecimal netUsdt = money(grossUsdt.subtract(fee));
        BigDecimal toAmount = "USDT".equals(request.fromAsset())
                ? netUsdt.divide(price, 6, RoundingMode.DOWN) : netUsdt;
        String toAsset = "USDT".equals(request.fromAsset()) ? "NEX" : "USDT";
        BigDecimal usdtDelta = "USDT".equals(request.fromAsset()) ? request.fromAmount().negate() : toAmount;
        BigDecimal nexDelta = "NEX".equals(request.fromAsset()) ? request.fromAmount().negate() : toAmount;
        if (mapper.applyWalletDelta(userId, usdtDelta, nexDelta) != 1) {
            throw new BizException(409, "EXCHANGE_WALLET_INSUFFICIENT_OR_CONFLICT");
        }
        String exchangeNo = exchangeNo();
        AppExchangeMapper.ExchangeWrite write = new AppExchangeMapper.ExchangeWrite(
                userId, exchangeNo, request.fromAsset(), toAsset, request.fromAmount(), toAmount, price, "COMPLETED");
        if (mapper.insertOrder(write) != 1) throw new BizException(409, "EXCHANGE_ORDER_CONFLICT");
        BigDecimal fromAfter = "USDT".equals(request.fromAsset())
                ? wallet.usdtAvailable().subtract(request.fromAmount()) : wallet.nexAvailable().subtract(request.fromAmount());
        BigDecimal toAfter = "USDT".equals(toAsset)
                ? wallet.usdtAvailable().add(toAmount) : wallet.nexAvailable().add(toAmount);
        if (mapper.insertLedger(new AppExchangeMapper.LedgerWrite(userId, exchangeNo + "-OUT", request.fromAsset(),
                "OUT", request.fromAmount(), money(fromAfter), "G2 canonical swap debit")) != 1
                || mapper.insertLedger(new AppExchangeMapper.LedgerWrite(userId, exchangeNo + "-IN", toAsset,
                "IN", toAmount, money(toAfter), "G2 canonical swap credit; fee allocated server-side")) != 1) {
            throw new BizException(409, "EXCHANGE_LEDGER_CONFLICT");
        }
        AppExchangeMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "exchangeNo", exchangeNo, "fromAsset", request.fromAsset(), "toAsset", toAsset,
                "fromAmount", request.fromAmount(), "toAmount", toAmount, "rate", price,
                "grossUsdt", grossUsdt, "feeUsdt", fee, "status", "COMPLETED");
        String receiptId = outbox.publishUserEvent("EXCHANGE_ORDER", exchangeNo, "exchange.swapped", userId,
                normalizePhase(attribution.phase()), attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit(userId, exchangeNo, idempotencyKey, event, "/api/exchange");
        Map<String, Object> result = stateMap(userId, mapper.lockWalletGate(userId));
        result.put("order", orderMap(write));
        result.put("feeUsdt", fee);
        result.put("receiptId", receiptId);
        return ApiResult.ok(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId, String exchangeNo, String idempotencyKey) {
        requireUser(userId);
        String normalized = StringUtils.hasText(exchangeNo) ? exchangeNo.trim() : "";
        if (!normalized.matches("EX-[A-Za-z0-9-]{8,90}")) throw new BizException(422, "EXCHANGE_NO_INVALID");
        return executeOnce("CANCEL:" + normalized + ":USER:" + userId, idempotencyKey, normalized, () -> {
            if (mapper.cancelOwnQueued(userId, normalized) != 1) throw new BizException(409, "EXCHANGE_NOT_CANCELLABLE");
            Map<String, Object> event = linked("exchangeNo", normalized, "status", "CANCELLED", "cancelledBy", "USER");
            String receiptId = outbox.publish("EXCHANGE_ORDER", normalized, "exchange.queue_cancelled", event);
            recordUserAudit(userId, normalized, idempotencyKey, event, "/api/exchange/" + normalized + "/cancel");
            Map<String, Object> result = stateMap(userId, mapper.lockWalletGate(userId));
            result.put("receiptId", receiptId);
            return ApiResult.ok(result);
        });
    }

    private ApiResult<Map<String, Object>> gatedOrder(
            Long userId, String userNo, String idempotencyKey, NormalizedSwap request,
            BigDecimal price, BigDecimal grossUsdt, BigDecimal cumulativeUsdt,
            BigDecimal kycThresholdUsdt, String kycStatus, String gate) {
        boolean queueable = ("USER_CAP".equals(gate) || "PLATFORM_CAP".equals(gate))
                && "QUEUE".equalsIgnoreCase(text(QUEUE_MODE, "QUEUE")) && request.queueIfCapped();
        String status = queueable ? "QUEUED" : gate;
        BigDecimal toAmount = "USDT".equals(request.fromAsset())
                ? grossUsdt.divide(price, 6, RoundingMode.DOWN) : grossUsdt;
        String exchangeNo = exchangeNo();
        AppExchangeMapper.ExchangeWrite write = new AppExchangeMapper.ExchangeWrite(userId, exchangeNo,
                request.fromAsset(), "USDT".equals(request.fromAsset()) ? "NEX" : "USDT",
                request.fromAmount(), toAmount, price, status);
        if (mapper.insertOrder(write) != 1) throw new BizException(409, "EXCHANGE_ORDER_CONFLICT");
        KycReviewTriggerResult k5Review = null;
        if ("KYC_REQUIRED".equals(gate)) {
            k5Review = riskKycReviewFacade.triggerCumulativeExchangeReview(
                    userNo, grossUsdt, cumulativeUsdt, kycThresholdUsdt, kycStatus, exchangeNo,
                    "system:g2", "G2 cumulative exchange threshold reached");
            if (!k5Review.requiresReview() || !StringUtils.hasText(k5Review.ticketId())) {
                throw new IllegalStateException("K5_G2_CUMULATIVE_TRIGGER_FAILED");
            }
            outbox.publish("RISK_KYC_REVIEW_TICKET", k5Review.ticketId(), "risk.kyc_review_triggered", linked(
                    "triggerType", "cumulative",
                    "userId", userId,
                    "userNo", userNo,
                    "amountUsdt", grossUsdt,
                    "cumulativeUsdt", cumulativeUsdt,
                    "thresholdUsdt", kycThresholdUsdt,
                    "exchangeNo", exchangeNo,
                    "ticketId", k5Review.ticketId(),
                    "created", k5Review.created(),
                    "operator", "system:g2",
                    "source", "G2",
                    "occurredAt", Instant.now(clock).toString(),
                    "ts", Instant.now(clock).toString()));
        }
        Map<String, Object> event = linked("exchangeNo", exchangeNo, "gate", gate, "status", status,
                "grossUsdt", grossUsdt);
        String receiptId = outbox.publish("EXCHANGE_ORDER", exchangeNo, "exchange.gated", event);
        recordUserAudit(userId, exchangeNo, idempotencyKey, event, "/api/exchange");
        Map<String, Object> result = stateMap(userId, mapper.lockWalletGate(userId));
        result.put("order", orderMap(write));
        result.put("gate", gate);
        result.put("receiptId", receiptId);
        if (k5Review != null) result.put("kycReviewTicketId", k5Review.ticketId());
        return ApiResult.ok(result);
    }

    private String gate(
            Long userId, AppExchangeMapper.WalletGateRow wallet, BigDecimal grossUsdt,
            BigDecimal lifetimeUsdt, BigDecimal kycThresholdUsdt) {
        if (mapper.geoBlocked(wallet.countryCode()) > 0) return "GEO_BLOCKED";
        if (!List.of("VERIFIED", "APPROVED", "PASSED").contains(wallet.kycStatus())
                && lifetimeUsdt.add(grossUsdt).compareTo(kycThresholdUsdt) >= 0) return "KYC_REQUIRED";
        if (nz(mapper.userTodayUsdt(userId)).add(grossUsdt).compareTo(number(USER_CAP, "50")) > 0) return "USER_CAP";
        if (nz(mapper.platformTodayUsdt()).add(grossUsdt).compareTo(number(PLATFORM_CAP, "20000")) > 0) return "PLATFORM_CAP";
        return null;
    }

    private Map<String, Object> stateMap(Long userId, AppExchangeMapper.WalletGateRow wallet) {
        return linked("caps", caps().getData(), "wallet", linked("usdtAvailable", money(wallet.usdtAvailable()),
                        "nexAvailable", money(wallet.nexAvailable())),
                "todayUserUsedUsdt", money(mapper.userTodayUsdt(userId)),
                "todayPlatformUsedUsdt", money(mapper.platformTodayUsdt()),
                "lifetimeExchangedUsdt", money(mapper.userLifetimeUsdt(userId)),
                "kycStatus", wallet.kycStatus(), "orders", mapper.userOrders(userId), "serverCanonical", true);
    }

    private Map<String, Object> orderMap(AppExchangeMapper.ExchangeWrite row) {
        return linked("exchangeNo", row.exchangeNo(), "fromAsset", row.fromAsset(), "toAsset", row.toAsset(),
                "fromAmount", row.fromAmount(), "toAmount", row.toAmount(), "rate", row.rate(), "status", row.status());
    }

    private NormalizedSwap normalize(SwapRequest request) {
        if (request == null || !StringUtils.hasText(request.direction()) || request.fromAmount() == null
                || request.fromAmount().compareTo(BigDecimal.ZERO) <= 0) throw new BizException(422, "EXCHANGE_REQUEST_INVALID");
        String direction = request.direction().trim().toUpperCase(Locale.ROOT).replace("-", "_");
        String from = switch (direction) {
            case "USDT_TO_NEX", "USDT2NEX" -> "USDT";
            case "NEX_TO_USDT", "NEX2USDT" -> "NEX";
            default -> throw new BizException(422, "EXCHANGE_DIRECTION_INVALID");
        };
        BigDecimal amount = request.fromAmount().setScale(6, RoundingMode.DOWN);
        return new NormalizedSwap(from, amount, Boolean.TRUE.equals(request.queueIfCapped()));
    }

    private List<NexMarketCurveFrame> curveFrames() {
        String json = config.activeValue(CURVE).orElseThrow(() -> new BizException(503, "G3_WEEKLY_CURVE_NOT_CONFIGURED"));
        try {
            List<NexMarketCurveFrame> frames = objectMapper.readValue(json, new TypeReference<>() {});
            if (frames.size() != 7 || frames.stream().anyMatch(f -> f.dayIndex() < 0 || f.dayIndex() > 6
                    || f.targetPrice() == null || f.targetPrice().compareTo(BigDecimal.ZERO) <= 0
                    || f.pumpProbability() == null || f.volatilityPct() == null || f.volatilityPct().compareTo(BigDecimal.ZERO) <= 0)
                    || frames.stream().map(NexMarketCurveFrame::dayIndex).distinct().count() != 7) {
                throw new BizException(503, "G3_WEEKLY_CURVE_INVALID");
            }
            return frames.stream().sorted(java.util.Comparator.comparingInt(NexMarketCurveFrame::dayIndex)).toList();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(503, "G3_WEEKLY_CURVE_INVALID");
        }
    }

    private BigDecimal requirePrice() {
        BigDecimal value = mapper.currentPrice();
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) throw new BizException(503, "G3_PRICE_UNAVAILABLE");
        return value.stripTrailingZeros();
    }

    private boolean swapEnabled() {
        String current = mapper.emergencyValue(EXCHANGE_KILL);
        String legacy = mapper.emergencyValue(EXCHANGE_KILL_LEGACY);
        return KillSwitchState.enabled(java.util.Optional.ofNullable(current), java.util.Optional.ofNullable(legacy));
    }

    private BigDecimal number(String key, String fallback) {
        try { return new BigDecimal(config.activeValue(key).orElse(fallback).trim()); }
        catch (RuntimeException ex) { throw new BizException(503, "EXCHANGE_CONFIG_INVALID:" + key); }
    }

    private String text(String key, String fallback) { return config.activeValue(key).orElse(fallback).trim(); }
    private BigDecimal money(BigDecimal value) { return nz(value).setScale(6, RoundingMode.HALF_UP); }
    private BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private String exchangeNo() { return "EX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT); }
    private void requireUser(Long userId) { if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED"); }

    private AppExchangeMapper.UserAttribution requireAttribution(Long userId) {
        AppExchangeMapper.UserAttribution row = mapper.userAttribution(userId);
        if (row == null || row.accountAgeMonths() == null || !StringUtils.hasText(row.cohort()))
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        return row;
    }
    private String normalizePhase(String phase) {
        String value = StringUtils.hasText(phase) ? phase.trim().toUpperCase(Locale.ROOT) : "P1";
        if (value.matches("[1-6]")) value = "P" + value;
        return value.matches("P[1-6]") ? value : "P1";
    }

    private void recordUserAudit(Long userId,String exchangeNo,String key,Map<String,Object> detail,String path) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("USER_EXCHANGE_MUTATION")
                .resourceType("EXCHANGE_ORDER").resourceId(exchangeNo).bizNo(exchangeNo).userId(userId)
                .actorId(userId).actorType("USER").actorUsername("user:" + userId).method("POST").path(path)
                .result("SUCCESS").riskLevel("HIGH").detail(linked("idempotencyKey", key.trim(), "state", detail)).build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> executeOnce(String scope,String key,Object request,Supplier<ApiResult<Map<String,Object>>> action) {
        return (ApiResult<Map<String,Object>>) (ApiResult) idempotency.execute("APP:G2_" + scope,key,sha256(String.valueOf(request)),
                ApiResult.class,(Supplier) action);
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private Map<String,Object> linked(Object... values) {
        Map<String,Object> map = new LinkedHashMap<>();
        for (int i=0;i<values.length;i+=2) map.put(String.valueOf(values[i]),values[i+1]);
        return map;
    }

    public record SwapRequest(String direction,BigDecimal fromAmount,Boolean queueIfCapped) {}
    private record NormalizedSwap(String fromAsset,BigDecimal fromAmount,boolean queueIfCapped) {}
}
