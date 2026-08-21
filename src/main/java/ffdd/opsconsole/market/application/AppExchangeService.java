package ffdd.opsconsole.market.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.core.env.Environment;

@Service
@RequiredArgsConstructor
public class AppExchangeService {
    private static final long MARKET_MAX_AGE_MINUTES = 5;
    private static final long MARKET_MAX_FUTURE_SKEW_MINUTES = 1;
    private static final String USER_CAP = "wallet.exchange.user_daily_cap_usdt";
    private static final String PLATFORM_CAP = "wallet.exchange.platform_daily_cap_usdt";
    private static final String FEE_PCT = "wallet.exchange.fee_pct";
    private static final String FEE_MIN = "wallet.exchange.fee_min_usdt";
    private static final String MIN_USDT = "wallet.exchange.min_usdt";
    private static final String MIN_NEX = "wallet.exchange.min_nex";
    private static final String QUEUE_MODE = "wallet.exchange.queue_mode";
    private static final String CURRENT_PRICE = "wallet.exchange.nex_usdt_price";
    private static final String CURVE = "wallet.nex_market.weekly_curve";
    private static final String COST_BASIS = "wallet.nex_market.cost_basis";
    private static final String EXCHANGE_KILL = "killswitch.exchange";
    private static final String EXCHANGE_KILL_LEGACY = "emergency.killswitch.exchange";
    private static final String EXCHANGE_EXECUTION_MUTEX = "G2_EXCHANGE_EXECUTION";
    private static final Map<String, ExternalAsset> EXTERNAL_ASSETS = Map.of(
            "EXT_RNDR_USDT", new ExternalAsset("RNDR", "Render", "ai"),
            "EXT_TAO_USDT", new ExternalAsset("TAO", "Bittensor", "ai"),
            "EXT_AKT_USDT", new ExternalAsset("AKT", "Akash Network", "depin"),
            "EXT_FIL_USDT", new ExternalAsset("FIL", "Filecoin", "depin"),
            "EXT_GRT_USDT", new ExternalAsset("GRT", "The Graph", "infra"));
    private static final Set<String> EXTERNAL_SYMBOLS = Set.of("RNDR", "TAO", "AKT", "FIL", "GRT");

    private final AppExchangeMapper mapper;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final G2ExchangeFeeAllocationService feeAllocationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Environment environment;

    /** The sandbox ledger is absent from production-only deployments. */
    private final Optional<AppMarketSandboxService> sandbox;

    public ApiResult<Map<String, Object>> caps() {
        if (isStrictIsolatedRuntime() && !isDevelopmentRuntime()) return ApiResult.ok(sandboxCaps());
        requireCanonicalReadRuntime();
        BigDecimal price = requirePublicPrice();
        return ApiResult.ok(linked(
                "asset", "NEX", "currency", "USDT", "currentPrice", price,
                "userDailyCapUsdt", number(USER_CAP, "50"),
                "platformDailyCapUsdt", number(PLATFORM_CAP, "20000"),
                "feePct", number(FEE_PCT, "0"), "feeMinUsdt", number(FEE_MIN, "0.50"),
                "minUsdt", number(MIN_USDT, "1"), "minNex", number(MIN_NEX, "10"),
                "queueMode", text(QUEUE_MODE, "QUEUE"),
                "swapEnabled", swapEnabled(), "serverCanonical", true,
                "source", "G2/G3 server configuration", "sourceEnvironment", "PRODUCTION", "runId", ""));
    }

    public ApiResult<Map<String, Object>> market() {
        if (isStrictIsolatedRuntime() && !isDevelopmentRuntime()) return ApiResult.ok(sandboxMarket());
        requireCanonicalReadRuntime();
        List<NexMarketCurveFrame> frames = curveFrames();
        BigDecimal price = requirePublicPrice();
        List<AppExchangeMapper.MarketPoint> recentPoints = mapper.recentMarketPoints();
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String,Object>> history24h = (recentPoints == null ? List.<AppExchangeMapper.MarketPoint>of() : recentPoints).stream()
                .filter(point -> point != null && point.priceUsdt() != null
                        && point.priceUsdt().compareTo(BigDecimal.ZERO) > 0 && point.sampledAt() != null
                        && !point.sampledAt().isBefore(now.minusHours(24))
                        && !point.sampledAt().isAfter(now.plusMinutes(MARKET_MAX_FUTURE_SKEW_MINUTES)))
                .map(point -> linked("price", point.priceUsdt(), "sampledAt", point.sampledAt()))
                .toList();
        return ApiResult.ok(linked(
                "asset", "NEX", "currency", "USDT", "currentPrice", price,
                "costBasis", number(COST_BASIS, "0.085"),
                "frames", frames, "sparkline", frames.stream().map(NexMarketCurveFrame::targetPrice).toList(),
                "history24h", history24h,
                "sampledAt", LocalDateTime.now(clock), "serverCanonical", true,
                "source", "G3 weekly_curve + nx_price_index 24h history",
                "sourceEnvironment", "PRODUCTION", "runId", ""));
    }

    public ApiResult<Map<String, Object>> externalMarket() {
        if (isStrictIsolatedRuntime()) return ApiResult.ok(sandboxExternalMarket());
        requireProductionRuntime();
        List<AppExchangeMapper.ExternalMarketPoint> points = mapper.latestExternalMarketPoints();
        List<Map<String, Object>> quotes = (points == null
                ? List.<AppExchangeMapper.ExternalMarketPoint>of() : points).stream()
                .map(this::externalQuote)
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean completeSnapshot = quotes.size() == EXTERNAL_SYMBOLS.size()
                && quotes.stream().map(quote -> String.valueOf(quote.get("symbol"))).collect(java.util.stream.Collectors.toSet())
                .equals(EXTERNAL_SYMBOLS);
        List<Map<String, Object>> publishedQuotes = completeSnapshot ? quotes : List.of();
        LocalDateTime actualSampledAt = publishedQuotes.stream()
                .map(quote -> quote.get("sampledAt"))
                .filter(LocalDateTime.class::isInstance)
                .map(LocalDateTime.class::cast)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now(clock));
        return ApiResult.ok(linked(
                "serverCanonical", true,
                "source", "nx_price_index:external-market",
                "sourceEnvironment", "PRODUCTION",
                "runId", "",
                "availability", completeSnapshot ? "AVAILABLE" : "UNAVAILABLE",
                "sampledAt", actualSampledAt,
                "quotes", publishedQuotes));
    }

    private Map<String, Object> externalQuote(AppExchangeMapper.ExternalMarketPoint row) {
        ExternalAsset asset = row == null ? null : EXTERNAL_ASSETS.get(row.metricCode());
        if (asset == null || row.priceUsdt() == null || row.priceUsdt().compareTo(BigDecimal.ZERO) <= 0
                || row.deltaPercent() == null || row.volume24hUsdt() == null
                || row.volume24hUsdt().compareTo(BigDecimal.ZERO) < 0 || row.sampledAt() == null) return null;
        LocalDateTime now = LocalDateTime.now(clock);
        if (row.sampledAt().isBefore(now.minusMinutes(MARKET_MAX_AGE_MINUTES))
                || row.sampledAt().isAfter(now.plusMinutes(MARKET_MAX_FUTURE_SKEW_MINUTES))) return null;
        List<BigDecimal> sparkline = externalSparkline(row.sparkline());
        if (sparkline == null) return null;
        return externalQuote(asset, row.priceUsdt(), row.deltaPercent(), row.volume24hUsdt(), sparkline, row.sampledAt());
    }

    private List<BigDecimal> externalSparkline(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            List<BigDecimal> values = objectMapper.readValue(json, new TypeReference<>() {});
            if (values.size() < 2 || values.size() > 60
                    || values.stream().anyMatch(value -> value == null || value.compareTo(BigDecimal.ZERO) <= 0)) return null;
            return values;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> externalQuote(ExternalAsset asset, BigDecimal price, BigDecimal delta,
            BigDecimal volume, List<BigDecimal> sparkline, LocalDateTime sampledAt) {
        return linked("symbol", asset.symbol(), "name", asset.name(), "category", asset.category(),
                "priceUsd", price, "change24hPct", delta, "volume24hUsd", volume,
                "sparkline", sparkline, "sampledAt", sampledAt);
    }

    public ApiResult<Map<String, Object>> state(Long userId) {
        if (sandboxRuntime()) return sandbox.orElseThrow().exchangeState(userId);
        requireExchangeSubject(userId);
        AppExchangeMapper.WalletGateRow wallet = mapper.lockWalletGate(userId);
        if (wallet == null) throw new BizException(409, "EXCHANGE_WALLET_NOT_FOUND");
        return ApiResult.ok(stateMap(userId, wallet));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> swap(Long userId, String idempotencyKey, SwapRequest request) {
        if (sandboxRuntime()) return sandbox.orElseThrow().swap(userId, idempotencyKey, request);
        requireExchangeSubject(userId);
        NormalizedSwap normalized = normalize(request);
        return executeOnce("SWAP:USER:" + userId, idempotencyKey, normalized,
                () -> swapInternal(userId, idempotencyKey, normalized));
    }

    private ApiResult<Map<String, Object>> swapInternal(Long userId, String idempotencyKey, NormalizedSwap request) {
        if (!EXCHANGE_EXECUTION_MUTEX.equals(mapper.lockExchangeExecutionMutex())) {
            throw new BizException(503, "G2_EXECUTION_MUTEX_UNAVAILABLE");
        }
        String userNo = mapper.lockActiveUserNo(userId);
        if (!StringUtils.hasText(userNo)) throw new BizException(404, "USER_NOT_FOUND");
        AppExchangeMapper.WalletGateRow wallet = mapper.lockWalletGate(userId);
        if (wallet == null) throw new BizException(409, "EXCHANGE_WALLET_NOT_FOUND");
        if (!swapEnabled()) throw new BizException(409, "EXCHANGE_SWAP_PAUSED");

        BigDecimal price = requirePrice();
        BigDecimal minimum = "USDT".equals(request.fromAsset()) ? number(MIN_USDT, "1") : number(MIN_NEX, "10");
        if (request.fromAmount().compareTo(minimum) < 0) {
            throw new BizException(422, "EXCHANGE_AMOUNT_BELOW_MINIMUM");
        }
        BigDecimal grossUsdt = "USDT".equals(request.fromAsset())
                ? request.fromAmount() : request.fromAmount().multiply(price);
        grossUsdt = money(grossUsdt);
        String gate = gate(userId, wallet, grossUsdt);
        if (gate != null) return gatedOrder(userId, userNo, idempotencyKey, request, price, grossUsdt,
                gate);

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
        G2ExchangeFeeAllocationService.Allocation allocation =
                feeAllocationService.allocate(exchangeNo, fee, price);
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
        result.put("feeAllocation", linked(
                "burnPoolUsdt", allocation.burnPoolUsdt(),
                "feeBufferUsdt", allocation.feeBufferUsdt()));
        result.put("receiptId", receiptId);
        return ApiResult.ok(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> cancel(Long userId, String exchangeNo, String idempotencyKey) {
        if (sandboxRuntime()) return sandbox.orElseThrow().cancelExchange(userId, exchangeNo, idempotencyKey);
        requireExchangeSubject(userId);
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
            BigDecimal price, BigDecimal grossUsdt, String gate) {
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
        Map<String, Object> event = linked("exchangeNo", exchangeNo, "gate", gate, "status", status,
                "grossUsdt", grossUsdt);
        String receiptId = outbox.publish("EXCHANGE_ORDER", exchangeNo, "exchange.gated", event);
        recordUserAudit(userId, exchangeNo, idempotencyKey, event, "/api/exchange");
        Map<String, Object> result = stateMap(userId, mapper.lockWalletGate(userId));
        result.put("order", orderMap(write));
        result.put("gate", gate);
        result.put("receiptId", receiptId);
        return ApiResult.ok(result);
    }

    private String gate(
            Long userId, AppExchangeMapper.WalletGateRow wallet, BigDecimal grossUsdt) {
        if (mapper.geoBlocked(wallet.countryCode()) > 0) return "GEO_BLOCKED";
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
                "orders", mapper.userOrders(userId), "serverCanonical", true,
                "sourceEnvironment", "PRODUCTION", "runId", "");
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

    private BigDecimal requirePublicPrice() {
        BigDecimal fresh = mapper.currentPrice();
        if (fresh != null && fresh.compareTo(BigDecimal.ZERO) > 0) return fresh.stripTrailingZeros();
        try {
            BigDecimal configured = config.activeValue(CURRENT_PRICE)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(BigDecimal::new)
                    .orElse(null);
            if (configured != null && configured.compareTo(BigDecimal.ZERO) > 0) {
                return configured.stripTrailingZeros();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the stable public error contract below.
        }
        throw new BizException(503, "G3_PRICE_UNAVAILABLE");
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
    private void requireExchangeSubject(Long userId) {
        requireUser(userId);
        requireProductionRuntime();
        Integer sandbox = mapper.userSandbox(userId);
        if (sandbox == null) throw new BizException(404, "USER_NOT_FOUND");
        if (!Integer.valueOf(0).equals(sandbox)) throw new BizException(403, "EXCHANGE_PRODUCTION_USER_REQUIRED");
    }

    private void requireProductionRuntime() {
        String[] normalized = normalizedProfiles();
        if (FundsSandboxProfileGuard.isStrictIsolatedProfile(normalized)) {
            throw new BizException(503, "EXCHANGE_SANDBOX_ISOLATED_TABLE_UNAVAILABLE");
        }
        if (normalized.length != 0 && !(normalized.length == 1
                && Set.of("prod").contains(normalized[0]))) {
            throw new BizException(503, "EXCHANGE_RUNTIME_PROFILE_UNSUPPORTED");
        }
    }

    private void requireCanonicalReadRuntime() {
        if (isDevelopmentRuntime()) return;
        requireProductionRuntime();
    }

    private boolean isStrictIsolatedRuntime() {
        return FundsSandboxProfileGuard.isStrictIsolatedProfile(normalizedProfiles());
    }

    private String[] normalizedProfiles() {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        return profiles == null ? new String[0] : java.util.Arrays.stream(profiles)
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).toArray(String[]::new);
    }

    private boolean sandboxRuntime() {
        return sandbox.isPresent() && FundsSandboxProfileGuard.isStrictIsolatedProfile(normalizedProfiles());
    }

    private boolean isDevelopmentRuntime() {
        return FundsSandboxProfileGuard.isStrictDevelopmentProfile(normalizedProfiles());
    }

    /**
     * Public market configuration is a backend-owned acceptance projection.
     * It intentionally reads neither production price/config tables nor an App
     * fixture. Exchange state and every mutation remain fail-closed until a
     * run-scoped sandbox wallet/order ledger exists.
     */
    private Map<String, Object> sandboxCaps() {
        return linked(
                "asset", "NEX", "currency", "USDT",
                "currentPrice", sandboxNumber("nexion.exchange.sandbox.current-price", "0.125"),
                "userDailyCapUsdt", sandboxNumber("nexion.exchange.sandbox.user-daily-cap-usdt", "50"),
                "platformDailyCapUsdt", sandboxNumber("nexion.exchange.sandbox.platform-daily-cap-usdt", "20000"),
                "feePct", sandboxNumber("nexion.exchange.sandbox.fee-pct", "0"),
                "feeMinUsdt", sandboxNumber("nexion.exchange.sandbox.fee-min-usdt", "0.50"),
                "minUsdt", sandboxNumber("nexion.exchange.sandbox.min-usdt", "1"),
                "minNex", sandboxNumber("nexion.exchange.sandbox.min-nex", "10"),
                "queueMode", "QUEUE", "swapEnabled", true, "serverCanonical", true,
                "source", "mock", "sourceEnvironment", "SANDBOX",
                "runId", sandboxRunId());
    }

    private Map<String, Object> sandboxMarket() {
        List<BigDecimal> sparkline = sandboxSparkline();
        List<NexMarketCurveFrame> frames = java.util.stream.IntStream.range(0, sparkline.size())
                .mapToObj(index -> new NexMarketCurveFrame(index, sparkline.get(index), BigDecimal.ZERO,
                        new BigDecimal("0.01")))
                .toList();
        return linked(
                "asset", "NEX", "currency", "USDT",
                "currentPrice", sandboxNumber("nexion.exchange.sandbox.current-price", "0.125"),
                "costBasis", sandboxNumber("nexion.exchange.sandbox.cost-basis", "0.085"),
                "frames", frames, "sparkline", sparkline, "history24h", List.of(),
                "sampledAt", LocalDateTime.now(clock), "serverCanonical", true,
                "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", sandboxRunId());
    }

    private Map<String, Object> sandboxExternalMarket() {
        LocalDateTime sampledAt = LocalDateTime.now(clock);
        List<Map<String, Object>> quotes = List.of(
                externalQuote(EXTERNAL_ASSETS.get("EXT_RNDR_USDT"), new BigDecimal("7.84"), new BigDecimal("3.2"),
                        new BigDecimal("184500000"), decimals("7.50", "7.58", "7.55", "7.70", "7.84"), sampledAt),
                externalQuote(EXTERNAL_ASSETS.get("EXT_TAO_USDT"), new BigDecimal("412.5"), new BigDecimal("-2.1"),
                        new BigDecimal("92300000"), decimals("421", "418", "415", "417", "412.5"), sampledAt),
                externalQuote(EXTERNAL_ASSETS.get("EXT_AKT_USDT"), new BigDecimal("3.24"), new BigDecimal("8.1"),
                        new BigDecimal("48700000"), decimals("2.98", "3.02", "3.10", "3.18", "3.24"), sampledAt),
                externalQuote(EXTERNAL_ASSETS.get("EXT_FIL_USDT"), new BigDecimal("5.18"), new BigDecimal("2.7"),
                        new BigDecimal("187500000"), decimals("5.04", "5.08", "5.06", "5.12", "5.18"), sampledAt),
                externalQuote(EXTERNAL_ASSETS.get("EXT_GRT_USDT"), new BigDecimal("0.243"), new BigDecimal("1.1"),
                        new BigDecimal("28300000"), decimals("0.240", "0.241", "0.239", "0.242", "0.243"), sampledAt));
        return linked("serverCanonical", true, "source", "mock", "sourceEnvironment", "SANDBOX",
                "runId", sandboxRunId(), "availability", "AVAILABLE", "sampledAt", sampledAt, "quotes", quotes);
    }

    private List<BigDecimal> decimals(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    private List<BigDecimal> sandboxSparkline() {
        String raw = property("nexion.exchange.sandbox.sparkline", "0.113,0.117,0.121,0.125,0.129,0.126,0.125");
        try {
            List<BigDecimal> values = java.util.Arrays.stream(raw.split(","))
                    .map(String::trim).map(BigDecimal::new).toList();
            if (values.size() != 7 || values.stream().anyMatch(value -> value.compareTo(BigDecimal.ZERO) <= 0)) {
                throw new IllegalArgumentException("invalid sandbox sparkline");
            }
            return values;
        } catch (RuntimeException ex) {
            throw new BizException(503, "EXCHANGE_SANDBOX_MARKET_CONFIG_INVALID");
        }
    }

    private BigDecimal sandboxNumber(String key, String fallback) {
        try {
            BigDecimal value = new BigDecimal(property(key, fallback).trim());
            if (value.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException("negative");
            return value;
        } catch (RuntimeException ex) {
            throw new BizException(503, "EXCHANGE_SANDBOX_MARKET_CONFIG_INVALID");
        }
    }

    private String property(String key, String fallback) {
        if (environment == null) return fallback;
        String value = environment.getProperty(key);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String sandboxRunId() {
        String runId = property("NEXION_ACCEPTANCE_RUN_ID", "").trim();
        if (!runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) {
            throw new BizException(503, "EXCHANGE_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId;
    }

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
    private record ExternalAsset(String symbol,String name,String category) {}
}
