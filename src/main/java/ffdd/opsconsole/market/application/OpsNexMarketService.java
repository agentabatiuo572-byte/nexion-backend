package ffdd.opsconsole.market.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.market.domain.ExchangeOrderView;
import ffdd.opsconsole.market.domain.NexMarketRepository;
import ffdd.opsconsole.market.dto.ExchangeParamUpdateRequest;
import ffdd.opsconsole.market.dto.ExchangeQueueCancelRequest;
import ffdd.opsconsole.market.dto.ExchangeSwapStatusRequest;
import ffdd.opsconsole.market.dto.NexMarketAdvanceRequest;
import ffdd.opsconsole.market.dto.NexMarketCurveFrame;
import ffdd.opsconsole.market.dto.NexMarketCurveUpdateRequest;
import ffdd.opsconsole.market.dto.NexMarketValueUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsNexMarketService {
    private static final String EXCHANGE_USER_DAILY_CAP_KEY = "wallet.exchange.user_daily_cap_usdt";
    private static final String EXCHANGE_PLATFORM_DAILY_CAP_KEY = "wallet.exchange.platform_daily_cap_usdt";
    private static final String EXCHANGE_FEE_PCT_KEY = "wallet.exchange.fee_pct";
    private static final String EXCHANGE_FEE_MIN_KEY = "wallet.exchange.fee_min_usdt";
    private static final String EXCHANGE_QUEUE_MODE_KEY = "wallet.exchange.queue_mode";
    private static final String EXCHANGE_KYC_THRESHOLD_KEY = "wallet.exchange.kyc_threshold_usdt";
    private static final String EXCHANGE_KILLSWITCH_KEY = "killswitch.exchange";
    private static final String EXCHANGE_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.exchange";
    private static final String CURRENT_PRICE_KEY = "wallet.exchange.nex_usdt_price";
    private static final String WEEKLY_CURVE_KEY = "wallet.nex_market.weekly_curve";
    private static final String PUMP_PROBABILITY_KEY = "wallet.nex_market.pump_probability";
    private static final String VOLATILITY_KEY = "wallet.nex_market.volatility_pct";
    private static final String ORACLE_KEY = "wallet.nex_market.oracle";
    private static final String DEVIATION_KEY = "wallet.nex_market.deviation_pct";
    private static final String COST_BASIS_KEY = "wallet.nex_market.cost_basis";
    private static final String PAUSED_KEY = "wallet.nex_market.paused";
    private static final String CONTROL_PREFIX = "wallet.nex_market.control.";
    private static final String GENESIS_PREFIX = "G.genesis.";
    private static final String GENESIS_KILLSWITCH_KEY = "J.killswitch.genesis";
    private static final int GENESIS_SOLD = 847;
    private static final BigDecimal GENESIS_DAILY_VOLUME_BASE = new BigDecimal("24200000");
    private static final BigDecimal GENESIS_SECONDARY_FLOOR = new BigDecimal("12400");
    private static final BigDecimal GENESIS_SECONDARY_VOL_24H = new BigDecimal("186000");
    private static final int GENESIS_SECONDARY_LISTED = 38;
    private static final int GENESIS_SECONDARY_OWNERS = 612;
    private static final String GENESIS_TODAY_BATCH = "GD-0611";
    private static final BigDecimal GENESIS_ACCRUAL_DAYS = new BigDecimal("31.6");
    private static final String STAKING_PREFIX = "G.staking.";
    private static final String STAKING_KILLSWITCH_KEY = "J.killswitch.staking";
    private static final BigDecimal STAKING_USDT_POOL_USD = new BigDecimal("1250000");
    private static final BigDecimal STAKING_NEX_POOL_USD = new BigDecimal("250000");
    private static final BigDecimal STAKING_INTEREST_USD = new BigDecimal("312000");
    private static final int STAKING_PENDING_COUNT = 18;
    private static final int STAKING_ACTIVE_COUNT = 3180;
    private static final int STAKING_MATURE_COUNT = 232;
    private static final int STAKING_EARLY_MONTH_COUNT = 41;
    private static final String REPURCHASE_PREFIX = "G.repurchase.";
    private static final BigDecimal REPURCHASE_PRINCIPAL_USD = new BigDecimal("390000");
    private static final int REPURCHASE_LOCK_DAYS = 90;
    private static final int REPURCHASE_ORDERS_MONTH = 1840;
    private static final BigDecimal REPURCHASE_RATE = new BigDecimal("26.9");
    private static final List<String> CONTROL_KEYS = List.of("schedule", "pin", "loop");
    private static final List<String> EXCHANGE_QUEUE_STATUSES = List.of("QUEUED");
    private static final List<String> OVERRIDE_KEYS = List.of(
            "currentPrice", "volatilityPct", "oracle", "deviationPct", "costBasis", "paused");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final NexMarketRepository marketRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiResult<Map<String, Object>> exchangeOverview() {
        BigDecimal platformCap = readDecimal(EXCHANGE_PLATFORM_DAILY_CAP_KEY, new BigDecimal("20000"));
        BigDecimal todayUsd = marketRepository.todayExchangeCompletedUsdt();
        long gateKyc = marketRepository.todayExchangeCountByStatus("KYC_REQUIRED");
        long gateUser = marketRepository.todayExchangeCountByStatus("USER_CAP");
        long gatePlatform = marketRepository.todayExchangeCountByStatus("PLATFORM_CAP");
        long gateGeo = marketRepository.todayExchangeCountByStatus("GEO_BLOCKED");
        boolean swapEnabled = exchangeSwapEnabled();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G2");
        response.put("asset", "NEX");
        response.put("currency", "USDT");
        response.put("currentPrice", currentPrice());
        response.put("stats", map(
                "todayUsd", todayUsd,
                "poolPct", percent(todayUsd, platformCap),
                "queueDepth", marketRepository.queuedExchangeCount(),
                "gateKyc", gateKyc,
                "gateUser", gateUser,
                "gatePlatform", gatePlatform,
                "gateGeo", gateGeo));
        response.put("caps", exchangeCaps(todayUsd, platformCap));
        response.put("queue", marketRepository.exchangeOrdersByStatuses(EXCHANGE_QUEUE_STATUSES, 50));
        response.put("gateDetails", map(
                "kyc", gate("kyc", "需实名(kyc-required)", "累计兑换过线、还没完成实名的拦截。过实名(C4)后自动放行。", List.of("KYC_REQUIRED")),
                "user", gate("user", "单用户超限(user-cap)", "超过单用户日额度的拦截。进次日队列或拒绝。", List.of("USER_CAP")),
                "platform", gate("platform", "平台超限(platform-cap)", "全平台日总池见底的拦截。全部转次日队列。", List.of("PLATFORM_CAP")),
                "geo", gate("geo", "地域封锁(geo-blocked)", "命中 J2 地域封锁的兑换单会取消或拒绝。", List.of("GEO_BLOCKED"))));
        response.put("swap", map(
                "enabled", swapEnabled,
                "status", swapEnabled ? "enabled" : "disabled",
                "configKey", EXCHANGE_KILLSWITCH_KEY,
                "linkedDomain", "J1"));
        response.put("geoBlocked", blockedCountryViews());
        response.put("coverage", exchangeCoverage());
        response.put("serverCanonical", true);
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of(
                "nx_exchange_order",
                "nx_config_item:wallet.exchange.*",
                "nx_config_item:" + EXCHANGE_KILLSWITCH_KEY,
                "nx_config_item:emergency.geo.*"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateExchangeParam(
            String idempotencyKey,
            String paramKey,
            ExchangeParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ExchangeParamDef def = exchangeParamDef(paramKey);
        if (def == null) {
            return validation("G2_EXCHANGE_PARAM_KEY_INVALID");
        }
        String oldValue = readText(def.configKey(), def.defaultValue());
        String newValue = normalizeExchangeParamValue(def, request.value());
        if (!StringUtils.hasText(newValue)) {
            return validation("VALUE_REQUIRED");
        }
        if (exchangeParamLoosens(def, oldValue, newValue)) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(def.configKey(), newValue, def.valueType(), "wallet", def.remark());
        audit("G2_EXCHANGE_PARAM_CHANGED", "EXCHANGE_PARAM", def.configKey(), request.operator(), map(
                "paramKey", def.key(),
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = exchangeOverview().getData();
        response.put("updated", map("key", def.key(), "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateExchangeSwapStatus(
            String idempotencyKey,
            ExchangeSwapStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.enabled() == null) {
            return validation("EXCHANGE_SWAP_ENABLED_REQUIRED");
        }
        boolean before = exchangeSwapEnabled();
        boolean after = request.enabled();
        if (after && !before) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(
                EXCHANGE_KILLSWITCH_KEY,
                after ? "enabled" : "disabled",
                "STRING",
                "admin_killswitch",
                "G2 exchange surface linked to J1 kill switch");
        audit("G2_EXCHANGE_SWAP_STATUS_CHANGED", "KILL_SWITCH", "exchange", request.operator(), map(
                "before", before,
                "after", after,
                "reason", request.reason().trim(),
                "linkedDomain", "J1",
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = exchangeOverview().getData();
        response.put("updated", map("key", "exchange", "before", before, "after", after));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> cancelExchangeQueueOrder(
            String idempotencyKey,
            String exchangeNo,
            ExchangeQueueCancelRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Optional<ExchangeOrderView> before = marketRepository.findExchangeOrder(exchangeNo);
        if (before.isEmpty()) {
            return ApiResult.fail(404, "EXCHANGE_ORDER_NOT_FOUND");
        }
        if (!"QUEUED".equalsIgnoreCase(before.get().status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        marketRepository.cancelQueuedExchange(exchangeNo);
        audit("G2_EXCHANGE_QUEUE_ORDER_CANCELLED", "EXCHANGE_ORDER", exchangeNo, request.operator(), map(
                "exchangeNo", exchangeNo,
                "userId", before.get().userId(),
                "amountUsdt", before.get().amountUsdt(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = exchangeOverview().getData();
        response.put("updated", map("exchangeNo", exchangeNo, "before", "QUEUED", "after", "CANCELLED"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> exchangeOrderDetail(String exchangeNo) {
        Optional<ExchangeOrderView> order = marketRepository.findExchangeOrder(exchangeNo);
        if (order.isEmpty()) {
            return ApiResult.fail(404, "EXCHANGE_ORDER_NOT_FOUND");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("order", order.get());
        response.put("config", map(
                "userDailyCapUsdt", readDecimal(EXCHANGE_USER_DAILY_CAP_KEY, new BigDecimal("50")),
                "platformDailyCapUsdt", readDecimal(EXCHANGE_PLATFORM_DAILY_CAP_KEY, new BigDecimal("20000")),
                "feePct", readDecimal(EXCHANGE_FEE_PCT_KEY, BigDecimal.ZERO),
                "feeMinUsdt", readDecimal(EXCHANGE_FEE_MIN_KEY, new BigDecimal("0.50")),
                "queueMode", readText(EXCHANGE_QUEUE_MODE_KEY, "QUEUE"),
                "kycThresholdUsdt", readDecimal(EXCHANGE_KYC_THRESHOLD_KEY, new BigDecimal("100"))));
        response.put("coverage", exchangeCoverage());
        response.put("swap", map("enabled", exchangeSwapEnabled(), "linkedDomain", "J1"));
        response.put("sources", List.of("nx_exchange_order:" + exchangeNo, "nx_user:" + order.get().userId()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> stakingOverview() {
        List<Map<String, Object>> pools = stakingPoolDefs().stream().map(this::stakingPoolRow).toList();
        long killedCount = pools.stream()
                .filter(pool -> Boolean.TRUE.equals(pool.get("killed")))
                .count();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G1");
        response.put("product", "staking");
        response.put("stats", map(
                "lockedTotalUsd", STAKING_USDT_POOL_USD.add(STAKING_NEX_POOL_USD),
                "usdtPoolUsd", STAKING_USDT_POOL_USD,
                "nexPoolUsd", STAKING_NEX_POOL_USD,
                "interestUsd", STAKING_INTEREST_USD,
                "positionCount", STAKING_ACTIVE_COUNT + STAKING_MATURE_COUNT,
                "activeCount", STAKING_ACTIVE_COUNT,
                "matureCount", STAKING_MATURE_COUNT,
                "pendingCount", STAKING_PENDING_COUNT,
                "earlyWithdrawnMonth", STAKING_EARLY_MONTH_COUNT,
                "killedCount", killedCount,
                "stakingGateOn", stakingGateOn()));
        response.put("gate", map(
                "enabled", stakingGateOn(),
                "configKey", STAKING_KILLSWITCH_KEY,
                "linkedDomain", "J1"));
        response.put("pools", pools);
        response.put("positions", stakingPositionGroups());
        response.put("stateMachine", List.of(
                "pending_lock",
                "active",
                "mature_unclaimed",
                "claimed",
                "early_withdrawn",
                "slashed",
                "refunded"));
        response.put("coverage", stakingCoverage());
        response.put("serverCanonical", true);
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of(
                "nx_config_item:" + STAKING_PREFIX + "*",
                "nx_config_item:" + STAKING_KILLSWITCH_KEY,
                "nx_staking_position",
                "nx_wallet_ledger:staking",
                "B1 coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateStakingPoolParam(
            String idempotencyKey,
            String tierKey,
            String paramKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        StakingPoolDef pool = stakingPoolDef(tierKey);
        if (pool == null) {
            return validation("G1_STAKING_TIER_KEY_INVALID");
        }
        StakingParamDef def = stakingParamDef(pool, paramKey);
        if (def == null) {
            return validation("G1_STAKING_PARAM_KEY_INVALID");
        }
        String oldValue = readText(def.configKey(), def.defaultValue());
        String newValue = normalizeStakingParamValue(def, request.value());
        if (!StringUtils.hasText(newValue)) {
            return validation("G1_STAKING_PARAM_VALUE_INVALID");
        }
        if ("apy".equals(def.key()) && !stakingApyOrderValid(pool, newValue)) {
            return validation("G1_STAKING_APY_ORDER_INVALID");
        }
        if (stakingParamLoosens(def, oldValue, newValue)) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(def.configKey(), newValue, def.valueType(), "market", "G1 staking pool parameter");
        audit("G1_STAKING_POOL_PARAM_CHANGED", "STAKING_POOL_PARAM", def.configKey(), request.operator(), map(
                "tierKey", pool.tierKey(),
                "product", pool.product(),
                "paramKey", def.key(),
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = stakingOverview().getData();
        response.put("updated", map("tierKey", pool.tierKey(), "key", def.key(), "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateStakingPoolSaleStatus(
            String idempotencyKey,
            String tierKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        StakingPoolDef pool = stakingPoolDef(tierKey);
        if (pool == null) {
            return validation("G1_STAKING_TIER_KEY_INVALID");
        }
        Boolean enabled = parseBooleanValue(request.value());
        if (enabled == null) {
            return validation("G1_STAKING_SALE_STATUS_INVALID");
        }
        String configKey = STAKING_PREFIX + "enabled." + pool.tierKey();
        boolean before = stakingPoolEnabled(pool);
        if (enabled && !before) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(configKey, enabled.toString(), "BOOLEAN", "market", "G1 staking pool sale status");
        audit("G1_STAKING_POOL_SALE_STATUS_CHANGED", "STAKING_POOL", pool.tierKey(), request.operator(), map(
                "tierKey", pool.tierKey(),
                "product", pool.product(),
                "before", before,
                "after", enabled,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = stakingOverview().getData();
        response.put("updated", map("tierKey", pool.tierKey(), "key", "saleStatus", "before", before, "after", enabled));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateStakingPoolKillStatus(
            String idempotencyKey,
            String tierKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        StakingPoolDef pool = stakingPoolDef(tierKey);
        if (pool == null) {
            return validation("G1_STAKING_TIER_KEY_INVALID");
        }
        Boolean killed = parseBooleanValue(request.value());
        if (killed == null) {
            return validation("G1_STAKING_KILL_STATUS_INVALID");
        }
        String configKey = STAKING_PREFIX + pool.tierKey() + ".killed";
        boolean before = stakingPoolKilled(pool);
        if (killed && request.reason().trim().length() < 8) {
            return validation("G1_STAKING_KILL_PLAN_REQUIRED");
        }
        if (!killed && before) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(configKey, killed.toString(), "BOOLEAN", "market", "G1 staking pool kill status");
        audit("G1_STAKING_POOL_KILL_STATUS_CHANGED", "STAKING_POOL", pool.tierKey(), request.operator(), map(
                "tierKey", pool.tierKey(),
                "product", pool.product(),
                "before", before,
                "after", killed,
                "reason", request.reason().trim(),
                "linkedDomain", "J1",
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = stakingOverview().getData();
        response.put("updated", map("tierKey", pool.tierKey(), "key", "killStatus", "before", before, "after", killed));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> repurchaseOverview() {
        BigDecimal apy = repurchaseNumberValue(repurchaseParamDef("apy"));
        BigDecimal lotteryPerOrder = repurchaseNumberValue(repurchaseParamDef("lottery"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G7");
        response.put("product", "repurchase");
        response.put("asset", "USDT");
        response.put("currentNexPrice", currentPrice());
        response.put("stats", map(
                "ordersMonth", REPURCHASE_ORDERS_MONTH,
                "principalUsd", REPURCHASE_PRINCIPAL_USD,
                "matureUsd", repurchaseMatureUsd(REPURCHASE_PRINCIPAL_USD, apy),
                "ticketsMonth", lotteryPerOrder.multiply(BigDecimal.valueOf(REPURCHASE_ORDERS_MONTH)).setScale(0, RoundingMode.HALF_UP),
                "reinvestRate", REPURCHASE_RATE,
                "lockDays", REPURCHASE_LOCK_DAYS));
        response.put("params", repurchaseParamDefs().stream().map(this::repurchaseParamRow).toList());
        response.put("phaseGate", map(
                "key", "reinvestMultiplier",
                "label", "H1 growth phase controls limited-time multiplier",
                "value", readText("growth.reinvest.multiplier", "1x; month 5-6 can be 2x"),
                "linkedDomain", "H1",
                "readonly", true));
        response.put("stateMachine", List.of(
                "pending_lock",
                "active",
                "mature_unclaimed",
                "claimed",
                "early_withdrawn"));
        response.put("amountDistribution", readText(
                REPURCHASE_PREFIX + "distribution",
                "$100 tier 62% / $200 tier 21% / $500 tier 12% / $1,000 tier 5%"));
        response.put("coverage", repurchaseCoverage());
        response.put("serverCanonical", true);
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of(
                "nx_config_item:" + REPURCHASE_PREFIX + "*",
                "nx_wallet_ledger:wallet.reinvest",
                "nx_staking_position:repurchase",
                "B1 coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateRepurchaseParam(
            String idempotencyKey,
            String paramKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        RepurchaseParamDef def = repurchaseParamDef(paramKey);
        if (def == null) {
            return validation("G7_REPURCHASE_PARAM_KEY_INVALID");
        }
        String oldValue = readText(def.configKey(), def.defaultValue());
        String newValue = normalizeRepurchaseParamValue(def, request.value());
        if (!StringUtils.hasText(newValue)) {
            return validation("G7_REPURCHASE_PARAM_VALUE_INVALID");
        }
        if (repurchaseParamLoosens(def, oldValue, newValue)) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(def.configKey(), newValue, def.valueType(), "market", "G7 repurchase product parameter");
        audit("G7_REPURCHASE_PARAM_CHANGED", "REPURCHASE_PARAM", def.configKey(), request.operator(), map(
                "paramKey", def.key(),
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = repurchaseOverview().getData();
        response.put("updated", map("key", def.key(), "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> genesisOverview() {
        BigDecimal supply = genesisNumberValue(genesisParamDef("supply"));
        BigDecimal unitPrice = genesisNumberValue(genesisParamDef("price"));
        BigDecimal dividendPct = genesisNumberValue(genesisParamDef("dividend"));
        BigDecimal royaltyPct = genesisNumberValue(genesisParamDef("royalty"));
        BigDecimal poolToday = GENESIS_DAILY_VOLUME_BASE
                .multiply(dividendPct)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal perSlotPerDay = poolToday.divide(supply, 8, RoundingMode.HALF_UP);
        BigDecimal floorPerNodePerDay = unitPrice
                .multiply(dividendPct)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal payoutToday = perSlotPerDay.multiply(BigDecimal.valueOf(GENESIS_SOLD)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal genesisAccrual = floorPerNodePerDay
                .multiply(BigDecimal.valueOf(GENESIS_SOLD))
                .multiply(GENESIS_ACCRUAL_DAYS)
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G4");
        response.put("product", "genesis");
        response.put("asset", "GENESIS_NODE");
        response.put("stats", map(
                "totalSlots", supply.setScale(0, RoundingMode.HALF_UP),
                "sold", GENESIS_SOLD,
                "unitPrice", unitPrice,
                "unsold", supply.subtract(BigDecimal.valueOf(GENESIS_SOLD)).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP),
                "soldPct", percent(BigDecimal.valueOf(GENESIS_SOLD), supply),
                "genesisAccrualUsd", genesisAccrual,
                "marketOn", genesisMarketOn(),
                "todayBatch", GENESIS_TODAY_BATCH,
                "secondary", map(
                        "floor", GENESIS_SECONDARY_FLOOR,
                        "vol24h", GENESIS_SECONDARY_VOL_24H,
                        "listed", GENESIS_SECONDARY_LISTED,
                        "owners", GENESIS_SECONDARY_OWNERS,
                        "royaltyPct", royaltyPct)));
        response.put("params", genesisParamDefs().stream().map(this::genesisParamRow).toList());
        response.put("dividend", map(
                "dailyVolumeBase", GENESIS_DAILY_VOLUME_BASE,
                "dividendPct", dividendPct,
                "poolToday", poolToday.setScale(2, RoundingMode.HALF_UP),
                "perSlotPerDay", perSlotPerDay.setScale(2, RoundingMode.HALF_UP),
                "floorPerNodePerDay", floorPerNodePerDay.setScale(2, RoundingMode.HALF_UP),
                "payoutToday", payoutToday,
                "batchNo", GENESIS_TODAY_BATCH,
                "batchStatus", readText(GENESIS_PREFIX + "rerun." + GENESIS_TODAY_BATCH, "ready")));
        response.put("market", map(
                "enabled", genesisMarketOn(),
                "configKey", GENESIS_KILLSWITCH_KEY,
                "linkedDomain", "J1"));
        response.put("geoBlocked", blockedCountryViews());
        response.put("nodes", genesisNodes(perSlotPerDay, floorPerNodePerDay));
        response.put("stateMachine", List.of("minted", "held", "listed", "sold"));
        response.put("coverage", genesisCoverage());
        response.put("serverCanonical", true);
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of(
                "nx_config_item:" + GENESIS_PREFIX + "*",
                "nx_config_item:" + GENESIS_KILLSWITCH_KEY,
                "nx_genesis_node",
                "nx_genesis_dividend_batch",
                "B1 coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateGenesisParam(
            String idempotencyKey,
            String paramKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        GenesisParamDef def = genesisParamDef(paramKey);
        if (def == null) {
            return validation("G4_GENESIS_PARAM_KEY_INVALID");
        }
        String oldValue = readText(def.configKey(), def.defaultValue());
        String newValue = normalizeGenesisParamValue(def, request.value());
        if (!StringUtils.hasText(newValue)) {
            return validation("G4_GENESIS_PARAM_VALUE_INVALID");
        }
        if (genesisParamLoosens(def, oldValue, newValue)) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(def.configKey(), newValue, def.valueType(), "market", "G4 Genesis economy parameter");
        audit("G4_GENESIS_PARAM_CHANGED", "GENESIS_PARAM", def.configKey(), request.operator(), map(
                "paramKey", def.key(),
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = genesisOverview().getData();
        response.put("updated", map("key", def.key(), "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateGenesisMarketStatus(
            String idempotencyKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Boolean enabled = parseBooleanValue(request.value());
        if (enabled == null) {
            return validation("G4_GENESIS_MARKET_STATUS_INVALID");
        }
        boolean before = genesisMarketOn();
        if (enabled && !before) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(
                GENESIS_KILLSWITCH_KEY,
                enabled ? "on" : "off",
                "STRING",
                "admin_killswitch",
                "G4 Genesis market switch linked to J1 kill switch matrix");
        audit("G4_GENESIS_MARKET_STATUS_CHANGED", "KILL_SWITCH", "genesis", request.operator(), map(
                "before", before,
                "after", enabled,
                "reason", request.reason().trim(),
                "linkedDomain", "J1",
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = genesisOverview().getData();
        response.put("updated", map("key", "marketStatus", "before", before, "after", enabled));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> rerunGenesisDividendBatch(
            String idempotencyKey,
            String batchNo,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedBatch = batchNo == null ? "" : batchNo.trim();
        if (!normalizedBatch.matches("[A-Za-z0-9-]{3,32}")) {
            return validation("G4_GENESIS_BATCH_NO_INVALID");
        }
        String configKey = GENESIS_PREFIX + "rerun." + normalizedBatch;
        String before = readText(configKey, "ready");
        configFacade.upsertAdminValue(configKey, "done", "STRING", "market", "G4 Genesis dividend batch rerun marker");
        audit("G4_GENESIS_DIVIDEND_BATCH_RERUN", "GENESIS_DIVIDEND_BATCH", normalizedBatch, request.operator(), map(
                "batchNo", normalizedBatch,
                "before", before,
                "after", "done",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = genesisOverview().getData();
        response.put("updated", map("key", "batchRerun", "batchNo", normalizedBatch, "before", before, "after", "done"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> overview() {
        List<NexMarketCurveFrame> frames = loadCurve();
        int activeDay = activeDayIndex();
        NexMarketCurveFrame activeFrame = frameFor(frames, activeDay);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G3");
        response.put("asset", "NEX");
        response.put("currency", "USDT");
        response.put("currentPrice", currentPrice());
        response.put("activeDayIndex", activeDay);
        response.put("activeFrame", activeFrame);
        response.put("weekPeakPrice", weekPeak(frames));
        response.put("frames", frames);
        response.put("controls", controls());
        response.put("overrides", overrides(activeFrame));
        response.put("coverage", coverage());
        response.put("sunsetExclusions", List.of("Premium", "NEX v2", "Points"));
        response.put("sources", List.of("nx_config_item:" + WEEKLY_CURVE_KEY, "nx_price_index:NEX_USDT"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> curveHistory() {
        List<BigDecimal> values = marketRepository.latestNexSparkline()
                .map(this::readSparkline)
                .filter(list -> !list.isEmpty())
                .orElseGet(() -> loadCurve().stream().map(NexMarketCurveFrame::targetPrice).toList());
        BigDecimal current = currentPrice();
        List<BigDecimal> points = new ArrayList<>(values);
        if (points.isEmpty()) {
            points.add(current);
        }
        if (points.get(points.size() - 1).compareTo(current) != 0) {
            points.add(current);
        }
        if (points.size() == 1) {
            points.add(current);
        }
        if (points.size() > 48) {
            points = new ArrayList<>(points.subList(points.size() - 48, points.size()));
        }
        int intervalMinutes = Math.max(1, 1440 / Math.max(1, points.size() - 1));
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            BigDecimal value = points.get(i).setScale(8, RoundingMode.HALF_UP);
            BigDecimal previous = i == 0 ? value : points.get(i - 1);
            samples.add(map(
                    "sampledAt", now.minusMinutes((long) (points.size() - 1 - i) * intervalMinutes),
                    "price", value,
                    "deltaPct", deltaPercent(previous, value)));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "G3");
        response.put("asset", "NEX");
        response.put("currency", "USDT");
        response.put("window", "24h");
        response.put("intervalMinutes", intervalMinutes);
        response.put("points", samples);
        response.put("serverCanonical", true);
        response.put("sources", List.of("nx_price_index:NEX_USDT.sparkline", "nx_config_item:" + WEEKLY_CURVE_KEY));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateWeeklyCurve(String idempotencyKey, NexMarketCurveUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        List<NexMarketCurveFrame> before = loadCurve();
        List<NexMarketCurveFrame> frames = normalizeFrames(request.frames());
        if (curveRaisesLiability(before, frames)) {
            TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
            if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
                return ApiResult.fail(
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
        }
        String curveJson = writeCurve(frames);
        configFacade.upsertAdminValue(WEEKLY_CURVE_KEY, curveJson, "JSON", "wallet", "G3 weekly NEX market curve");
        applyFrame(frameFor(frames, activeDayIndex()), frames);
        audit("G3_WEEKLY_CURVE_CHANGED", "NEX_MARKET_CURVE", WEEKLY_CURVE_KEY, request.operator(), Map.of(
                "oldPeakPrice", weekPeak(before),
                "newPeakPrice", weekPeak(frames),
                "activeDayIndex", activeDayIndex(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    public ApiResult<Map<String, Object>> updateControl(
            String idempotencyKey,
            String controlKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!CONTROL_KEYS.contains(controlKey)) {
            return validation("G3_CONTROL_KEY_INVALID");
        }
        if (!StringUtils.hasText(request.value())) {
            return validation("VALUE_REQUIRED");
        }
        String value = request.value().trim();
        String configKey = CONTROL_PREFIX + controlKey;
        configFacade.upsertAdminValue(configKey, value, "STRING", "wallet", "G3 NEX market curve control");
        audit("G3_CONTROL_CHANGED", "NEX_MARKET_CONTROL", configKey, request.operator(), Map.of(
                "controlKey", controlKey,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    public ApiResult<Map<String, Object>> updateOverride(
            String idempotencyKey,
            String overrideKey,
            NexMarketValueUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!OVERRIDE_KEYS.contains(overrideKey)) {
            return validation("G3_OVERRIDE_KEY_INVALID");
        }
        if (!StringUtils.hasText(request.value())) {
            return validation("VALUE_REQUIRED");
        }
        String value = request.value().trim();
        ApiResult<Map<String, Object>> result = switch (overrideKey) {
            case "currentPrice" -> updateCurrentPriceOverride(idempotencyKey, value, request);
            case "volatilityPct" -> updateNumericOverride(
                    idempotencyKey, overrideKey, VOLATILITY_KEY, value, request, new BigDecimal("20"), "G3 active volatility override");
            case "deviationPct" -> updateNumericOverride(
                    idempotencyKey, overrideKey, DEVIATION_KEY, value, request, new BigDecimal("50"), "G3 oracle deviation alert override");
            case "costBasis" -> updateNumericOverride(
                    idempotencyKey, overrideKey, COST_BASIS_KEY, value, request, null, "G3 NEX cost basis override");
            case "oracle" -> updateStringOverride(
                    idempotencyKey, overrideKey, ORACLE_KEY, value, request, "G3 oracle source override");
            case "paused" -> updateBooleanOverride(idempotencyKey, value, request);
            default -> validation("G3_OVERRIDE_KEY_INVALID");
        };
        return result;
    }

    public ApiResult<Map<String, Object>> advanceCurrentFrame(String idempotencyKey, NexMarketAdvanceRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        List<NexMarketCurveFrame> frames = loadCurve();
        NexMarketCurveFrame activeFrame = frameFor(frames, activeDayIndex());
        applyFrame(activeFrame, frames);
        audit("G3_DAILY_FRAME_ADVANCED", "NEX_MARKET_CURVE", WEEKLY_CURVE_KEY, request.operator(), Map.of(
                "activeDayIndex", activeFrame.dayIndex(),
                "targetPrice", activeFrame.targetPrice(),
                "pumpProbability", activeFrame.pumpProbability(),
                "volatilityPct", activeFrame.volatilityPct(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return overview();
    }

    void advanceScheduledFrame() {
        List<NexMarketCurveFrame> frames = loadCurve();
        applyFrame(frameFor(frames, activeDayIndex()), frames);
    }

    private ApiResult<Map<String, Object>> updateCurrentPriceOverride(
            String idempotencyKey,
            String value,
            NexMarketValueUpdateRequest request) {
        BigDecimal newPrice = parseDecimal(value, null);
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return validation("PRICE_INVALID");
        }
        BigDecimal oldPrice = currentPrice();
        BigDecimal normalized = newPrice.setScale(8, RoundingMode.HALF_UP);
        if (normalized.compareTo(oldPrice) > 0) {
            ApiResult<Map<String, Object>> redline = coverageRedlineFailure();
            if (redline != null) {
                return redline;
            }
        }
        configFacade.upsertAdminValue(
                CURRENT_PRICE_KEY,
                normalized.stripTrailingZeros().toPlainString(),
                "NUMBER",
                "wallet",
                "G3 manual active NEX price override");
        marketRepository.publishNexUsdtPrice(
                normalized,
                deltaPercent(oldPrice, normalized),
                sparklineJson(loadCurve()),
                LocalDateTime.now(clock));
        auditOverride("currentPrice", CURRENT_PRICE_KEY, oldPrice, normalized, idempotencyKey, request);
        return overview();
    }

    private ApiResult<Map<String, Object>> updateNumericOverride(
            String idempotencyKey,
            String overrideKey,
            String configKey,
            String value,
            NexMarketValueUpdateRequest request,
            BigDecimal max,
            String remark) {
        BigDecimal numeric = parseDecimal(value, null);
        if (numeric == null || numeric.compareTo(BigDecimal.ZERO) <= 0 || (max != null && numeric.compareTo(max) > 0)) {
            return validation("NUMERIC_VALUE_INVALID");
        }
        String oldValue = readText(configKey, "");
        BigDecimal normalized = numeric.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        configFacade.upsertAdminValue(configKey, normalized.toPlainString(), "NUMBER", "wallet", remark);
        auditOverride(overrideKey, configKey, oldValue, normalized, idempotencyKey, request);
        return overview();
    }

    private ApiResult<Map<String, Object>> updateStringOverride(
            String idempotencyKey,
            String overrideKey,
            String configKey,
            String value,
            NexMarketValueUpdateRequest request,
            String remark) {
        if (value.length() > 80) {
            return validation("TEXT_VALUE_TOO_LONG");
        }
        String oldValue = readText(configKey, "");
        configFacade.upsertAdminValue(configKey, value, "STRING", "wallet", remark);
        auditOverride(overrideKey, configKey, oldValue, value, idempotencyKey, request);
        return overview();
    }

    private ApiResult<Map<String, Object>> updateBooleanOverride(
            String idempotencyKey,
            String value,
            NexMarketValueUpdateRequest request) {
        Boolean parsed = parseBooleanValue(value);
        if (parsed == null) {
            return validation("BOOLEAN_VALUE_INVALID");
        }
        String oldValue = readText(PAUSED_KEY, "false");
        configFacade.upsertAdminValue(PAUSED_KEY, parsed.toString(), "BOOLEAN", "wallet", "G3 NEX market engine pause override");
        auditOverride("paused", PAUSED_KEY, oldValue, parsed, idempotencyKey, request);
        return overview();
    }

    private void applyFrame(NexMarketCurveFrame frame, List<NexMarketCurveFrame> frames) {
        BigDecimal oldPrice = currentPrice();
        BigDecimal newPrice = frame.targetPrice().setScale(8, RoundingMode.HALF_UP);
        configFacade.upsertAdminValue(CURRENT_PRICE_KEY, newPrice.stripTrailingZeros().toPlainString(), "NUMBER", "wallet", "G3 active NEX price");
        configFacade.upsertAdminValue(PUMP_PROBABILITY_KEY, frame.pumpProbability().stripTrailingZeros().toPlainString(), "NUMBER", "wallet", "G3 active pump probability");
        configFacade.upsertAdminValue(VOLATILITY_KEY, frame.volatilityPct().stripTrailingZeros().toPlainString(), "NUMBER", "wallet", "G3 active volatility");
        marketRepository.publishNexUsdtPrice(
                newPrice,
                deltaPercent(oldPrice, newPrice),
                sparklineJson(frames),
                LocalDateTime.now(clock));
    }

    private List<NexMarketCurveFrame> normalizeFrames(List<NexMarketCurveFrame> frames) {
        if (frames == null || frames.size() != 7) {
            throw new IllegalArgumentException("G3 weekly curve must contain 7 frames");
        }
        List<NexMarketCurveFrame> normalized = new ArrayList<>();
        for (NexMarketCurveFrame frame : frames) {
            if (frame == null || frame.dayIndex() < 0 || frame.dayIndex() > 6) {
                throw new IllegalArgumentException("dayIndex must be 0-6");
            }
            BigDecimal price = requirePositive(frame.targetPrice(), "targetPrice").setScale(8, RoundingMode.HALF_UP);
            BigDecimal pump = normalizeProbability(frame.pumpProbability());
            BigDecimal volatility = requirePositive(frame.volatilityPct(), "volatilityPct").setScale(4, RoundingMode.HALF_UP);
            if (volatility.compareTo(BigDecimal.valueOf(20)) > 0) {
                throw new IllegalArgumentException("volatilityPct must be <= 20");
            }
            normalized.add(new NexMarketCurveFrame(frame.dayIndex(), price, pump, volatility));
        }
        List<Integer> indexes = normalized.stream().map(NexMarketCurveFrame::dayIndex).sorted().toList();
        if (!indexes.equals(List.of(0, 1, 2, 3, 4, 5, 6))) {
            throw new IllegalArgumentException("dayIndex must cover 0-6 exactly once");
        }
        return normalized.stream().sorted(Comparator.comparingInt(NexMarketCurveFrame::dayIndex)).toList();
    }

    private List<NexMarketCurveFrame> loadCurve() {
        return configFacade.activeValue(WEEKLY_CURVE_KEY)
                .filter(StringUtils::hasText)
                .map(this::readCurve)
                .orElseGet(() -> defaultCurve(currentPrice()));
    }

    private List<NexMarketCurveFrame> readCurve(String json) {
        try {
            return normalizeFrames(objectMapper.readValue(json, new TypeReference<List<NexMarketCurveFrame>>() {
            }));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return defaultCurve(currentPrice());
        }
    }

    private List<BigDecimal> readSparkline(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<BigDecimal>>() {
            }).stream()
                    .filter(value -> value != null && value.compareTo(BigDecimal.ZERO) > 0)
                    .toList();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    private String writeCurve(List<NexMarketCurveFrame> frames) {
        try {
            return objectMapper.writeValueAsString(frames);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize G3 weekly curve", ex);
        }
    }

    private String sparklineJson(List<NexMarketCurveFrame> frames) {
        try {
            return objectMapper.writeValueAsString(frames.stream().map(NexMarketCurveFrame::targetPrice).toList());
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<NexMarketCurveFrame> defaultCurve(BigDecimal currentPrice) {
        List<NexMarketCurveFrame> frames = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            BigDecimal drift = BigDecimal.ONE.add(BigDecimal.valueOf(index - 3L).multiply(new BigDecimal("0.005")));
            frames.add(new NexMarketCurveFrame(
                    index,
                    currentPrice.multiply(drift).setScale(8, RoundingMode.HALF_UP),
                    new BigDecimal("0.08"),
                    new BigDecimal("3.00")));
        }
        return frames;
    }

    private boolean curveRaisesLiability(List<NexMarketCurveFrame> before, List<NexMarketCurveFrame> after) {
        BigDecimal oldPeak = weekPeak(before);
        BigDecimal newPeak = weekPeak(after);
        boolean raisesPeak = newPeak.compareTo(oldPeak) > 0;
        boolean raisesPump = averagePump(after).compareTo(averagePump(before)) > 0;
        return raisesPeak || raisesPump;
    }

    private BigDecimal weekPeak(List<NexMarketCurveFrame> frames) {
        return frames.stream()
                .map(NexMarketCurveFrame::targetPrice)
                .max(BigDecimal::compareTo)
                .orElse(currentPrice());
    }

    private BigDecimal averagePump(List<NexMarketCurveFrame> frames) {
        return frames.stream()
                .map(NexMarketCurveFrame::pumpProbability)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(frames.size()), 6, RoundingMode.HALF_UP);
    }

    private NexMarketCurveFrame frameFor(List<NexMarketCurveFrame> frames, int dayIndex) {
        return frames.stream()
                .filter(frame -> frame.dayIndex() == dayIndex)
                .findFirst()
                .orElse(frames.get(0));
    }

    private int activeDayIndex() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).getDayOfWeek().getValue() - 1;
    }

    private BigDecimal currentPrice() {
        return configFacade.activeValue(CURRENT_PRICE_KEY)
                .flatMap(value -> Optional.ofNullable(parseDecimal(value, null)))
                .or(() -> marketRepository.latestNexUsdtPrice())
                .orElse(new BigDecimal("0.171"));
    }

    private BigDecimal deltaPercent(BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice == null || oldPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return newPrice.subtract(oldPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(oldPrice, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeProbability(BigDecimal raw) {
        BigDecimal value = requirePositive(raw, "pumpProbability");
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("pumpProbability must be 0-1 or 0-100 percent");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal requirePositive(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private BigDecimal parseDecimal(String raw, BigDecimal fallback) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private Boolean parseBooleanValue(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        return switch (value) {
            case "true", "enabled", "on", "1", "paused" -> Boolean.TRUE;
            case "false", "disabled", "off", "0", "running" -> Boolean.FALSE;
            default -> null;
        };
    }

    private String readText(String configKey, String fallback) {
        return configFacade.activeValue(configKey)
                .filter(StringUtils::hasText)
                .orElse(fallback);
    }

    private List<Map<String, Object>> controls() {
        return List.of(
                control("schedule", "自动按日推进", "每日 00:00 UTC server cron 推进到当前关键帧", "每日 00:00 自动推进"),
                control("pin", "钉住某日(pin)", "钉在指定日做演示 / 应急冻结,自动推进暂停", "未钉住"),
                control("loop", "跑完循环 / 停末值(loop)", "D7 之后回到 D1 循环,或停在末日值", "循环"));
    }

    private Map<String, Object> control(String key, String name, String description, String defaultValue) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("key", key);
        control.put("name", name);
        control.put("description", description);
        control.put("value", readText(CONTROL_PREFIX + key, defaultValue));
        return control;
    }

    private Map<String, Object> overrides(NexMarketCurveFrame activeFrame) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("currentPrice", currentPrice());
        overrides.put("volatilityPct", readText(VOLATILITY_KEY, activeFrame.volatilityPct().stripTrailingZeros().toPlainString()));
        overrides.put("oracle", readText(ORACLE_KEY, "内部做市"));
        overrides.put("deviationPct", readText(DEVIATION_KEY, "5"));
        overrides.put("costBasis", readText(COST_BASIS_KEY, "0.085"));
        overrides.put("paused", Boolean.TRUE.equals(parseBooleanValue(readText(PAUSED_KEY, "false"))));
        return overrides;
    }

    private ApiResult<Map<String, Object>> coverageRedlineFailure() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return ApiResult.fail(
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        return null;
    }

    private List<StakingPoolDef> stakingPoolDefs() {
        return List.of(
                new StakingPoolDef("USDT", "usdt30d", 30, "30d", "12", "5", "100", new BigDecimal("270000"), "USDT", false),
                new StakingPoolDef("USDT", "usdt90d", 90, "90d", "35", "15", "500", new BigDecimal("430000"), "USDT", false),
                new StakingPoolDef("USDT", "usdt180d", 180, "180d", "80", "30", "1000", new BigDecimal("350000"), "USDT", true),
                new StakingPoolDef("USDT", "usdt365d", 365, "365d", "180", "50", "5000", new BigDecimal("200000"), "USDT", true),
                new StakingPoolDef("NEX", "nex30d", 30, "30d", "5", "5", "1000", new BigDecimal("80000"), "NEX", false),
                new StakingPoolDef("NEX", "nex90d", 90, "90d", "12", "15", "5000", new BigDecimal("70000"), "NEX", false),
                new StakingPoolDef("NEX", "nex180d", 180, "180d", "20", "30", "10000", new BigDecimal("60000"), "NEX", false),
                new StakingPoolDef("NEX", "nex365d", 365, "365d", "35", "50", "20000", new BigDecimal("40000"), "NEX", false));
    }

    private StakingPoolDef stakingPoolDef(String rawTierKey) {
        String tierKey = rawTierKey == null ? "" : rawTierKey.trim();
        return stakingPoolDefs().stream()
                .filter(pool -> pool.tierKey().equals(tierKey))
                .findFirst()
                .orElse(null);
    }

    private StakingParamDef stakingParamDef(StakingPoolDef pool, String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        return switch (key) {
            case "apy" -> new StakingParamDef(
                    key,
                    STAKING_PREFIX + "apy." + pool.tierKey(),
                    "NUMBER",
                    pool.defaultApy(),
                    "Annual APY for new staking positions only",
                    StakingParamKind.PERCENT);
            case "penalty" -> new StakingParamDef(
                    key,
                    STAKING_PREFIX + "penalty." + pool.tierKey(),
                    "NUMBER",
                    pool.defaultPenalty(),
                    "Early withdrawal principal penalty percent",
                    StakingParamKind.PERCENT);
            case "min" -> new StakingParamDef(
                    key,
                    STAKING_PREFIX + "min." + pool.tierKey(),
                    "NUMBER",
                    pool.defaultMin(),
                    "Minimum stake amount for the pool asset",
                    StakingParamKind.AMOUNT);
            default -> null;
        };
    }

    private Map<String, Object> stakingPoolRow(StakingPoolDef pool) {
        StakingParamDef apyDef = stakingParamDef(pool, "apy");
        StakingParamDef penaltyDef = stakingParamDef(pool, "penalty");
        StakingParamDef minDef = stakingParamDef(pool, "min");
        String apy = readText(apyDef.configKey(), apyDef.defaultValue());
        String penalty = readText(penaltyDef.configKey(), penaltyDef.defaultValue());
        String min = readText(minDef.configKey(), minDef.defaultValue());
        boolean killed = stakingPoolKilled(pool);
        boolean enabled = stakingPoolEnabled(pool);
        return map(
                "product", pool.product(),
                "tierKey", pool.tierKey(),
                "term", pool.termLabel(),
                "termDays", pool.termDays(),
                "apy", apy,
                "apyDisplay", displayStakingParamValue(apyDef, apy, pool),
                "penalty", penalty,
                "penaltyDisplay", displayStakingParamValue(penaltyDef, penalty, pool),
                "minStake", min,
                "minDisplayValue", displayStakingParamValue(minDef, min, pool),
                "lockedUsd", pool.lockedUsd(),
                "lockedDisplay", displayStakingLocked(pool.lockedUsd()),
                "enabled", enabled,
                "killed", killed,
                "status", killed ? "killed" : enabled ? "active" : "stopped",
                "statusLabel", killed ? "已熔断" : enabled ? "营业中" : "已停售",
                "statusTone", killed ? "bad" : enabled ? "ok" : "dim",
                "highYield", pool.highYield());
    }

    private String normalizeStakingParamValue(StakingParamDef def, String rawValue) {
        BigDecimal value = parseRepurchaseNumber(rawValue, null);
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        if (def.kind() == StakingParamKind.PERCENT) {
            BigDecimal max = "penalty".equals(def.key()) ? new BigDecimal("100") : new BigDecimal("300");
            if (value.compareTo(max) > 0) {
                return null;
            }
            return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        if (value.compareTo(new BigDecimal("1000000000")) > 0) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean stakingParamLoosens(StakingParamDef def, String oldValue, String newValue) {
        BigDecimal oldNumber = parseRepurchaseNumber(oldValue, BigDecimal.ZERO);
        BigDecimal newNumber = parseRepurchaseNumber(newValue, BigDecimal.ZERO);
        if ("apy".equals(def.key())) {
            return newNumber.compareTo(oldNumber) > 0;
        }
        if ("penalty".equals(def.key())) {
            return newNumber.compareTo(oldNumber) < 0;
        }
        return false;
    }

    private boolean stakingApyOrderValid(StakingPoolDef changedPool, String changedValue) {
        List<StakingPoolDef> ordered = stakingPoolDefs().stream()
                .filter(pool -> pool.product().equals(changedPool.product()))
                .sorted(Comparator.comparingInt(StakingPoolDef::termDays))
                .toList();
        BigDecimal previous = null;
        for (StakingPoolDef pool : ordered) {
            StakingParamDef apyDef = stakingParamDef(pool, "apy");
            BigDecimal current = pool.tierKey().equals(changedPool.tierKey())
                    ? parseRepurchaseNumber(changedValue, BigDecimal.ZERO)
                    : stakingNumberValue(apyDef);
            if (previous != null && current.compareTo(previous) < 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private BigDecimal stakingNumberValue(StakingParamDef def) {
        return parseRepurchaseNumber(readText(def.configKey(), def.defaultValue()), new BigDecimal(def.defaultValue()));
    }

    private boolean stakingGateOn() {
        return configFacade.activeValue(STAKING_KILLSWITCH_KEY)
                .map(this::parseSwitchEnabled)
                .orElse(true);
    }

    private boolean stakingPoolEnabled(StakingPoolDef pool) {
        return configFacade.activeValue(STAKING_PREFIX + "enabled." + pool.tierKey())
                .map(this::parseSwitchEnabled)
                .orElse(true);
    }

    private boolean stakingPoolKilled(StakingPoolDef pool) {
        return configFacade.activeValue(STAKING_PREFIX + pool.tierKey() + ".killed")
                .map(this::parseSwitchEnabled)
                .orElse(false);
    }

    private String displayStakingParamValue(StakingParamDef def, String value, StakingPoolDef pool) {
        if ("apy".equals(def.key())) {
            return value + "%";
        }
        if ("penalty".equals(def.key())) {
            return value + "% principal + forfeit interest";
        }
        BigDecimal amount = parseRepurchaseNumber(value, BigDecimal.ZERO);
        return amount.stripTrailingZeros().toPlainString() + " " + pool.minAsset();
    }

    private String displayStakingLocked(BigDecimal value) {
        return "$" + value.divide(BigDecimal.valueOf(1_000_000), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "M";
    }

    private Map<String, Object> stakingCoverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        return map(
                "coverageRatio", snapshot.coverageRatio(),
                "redlinePct", snapshot.redlinePct(),
                "redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0,
                "precheck", "raising APY, lowering early withdrawal penalty, restoring sale, or clearing a kill switch requires B1 coverage redline check");
    }

    private List<Map<String, Object>> stakingPositionGroups() {
        return List.of(
                stakingPositionGroup(
                        "pending_lock",
                        "pending_lock 待确认",
                        "支付或链上确认中,服务器确认后进入 active。",
                        STAKING_PENDING_COUNT,
                        List.of(
                                stakingPosition("POS-9081", "U00019081", "USDT 90d", "$500", "waiting payment confirmation"),
                                stakingPosition("POS-9087", "U00019087", "NEX 30d", "1,000 NEX", "chain confirmation 2/6"))),
                stakingPositionGroup(
                        "active",
                        "active 计息中",
                        "按开仓时锁定 APY 线性计息,参数更新不追溯。",
                        STAKING_ACTIVE_COUNT,
                        List.of(
                                stakingPosition("POS-7102", "U00017102", "USDT 180d", "$1,000", "D90 / 180, accrued $197.26"),
                                stakingPosition("POS-6339", "U00016339", "NEX 365d", "20,000 NEX", "D120 / 365, accrued 230 NEX"))),
                stakingPositionGroup(
                        "mature_unclaimed",
                        "mature_unclaimed 到期未领",
                        "到期后生成待领取本息,claim 写 D4 账单。",
                        STAKING_MATURE_COUNT,
                        List.of(
                                stakingPosition("POS-5412", "U00015412", "USDT 30d", "$100", "matured 2026-06-14"),
                                stakingPosition("POS-5520", "U00015520", "NEX 90d", "5,000 NEX", "matured 2026-06-15"))),
                stakingPositionGroup(
                        "early_withdrawn",
                        "本月 early_withdrawn 提前赎回",
                        "提前赎回按本金罚款并没收未领取利息。",
                        STAKING_EARLY_MONTH_COUNT,
                        List.of(
                                stakingPosition("POS-5019", "U00015019", "USDT 365d", "$5,000", "penalty 50%, interest forfeited"),
                                stakingPosition("POS-5027", "U00015027", "NEX 180d", "10,000 NEX", "penalty 30%, interest forfeited"))));
    }

    private Map<String, Object> stakingPositionGroup(
            String status,
            String label,
            String note,
            int count,
            List<Map<String, Object>> rows) {
        return map("status", status, "label", label, "note", note, "count", count, "rows", rows);
    }

    private Map<String, Object> stakingPosition(String positionNo, String userNo, String tier, String amount, String note) {
        return map("positionNo", positionNo, "userNo", userNo, "tier", tier, "amount", amount, "note", note);
    }

    private List<RepurchaseParamDef> repurchaseParamDefs() {
        return List.of(
                repurchaseParamDef("apy"),
                repurchaseParamDef("nurture"),
                repurchaseParamDef("lottery"),
                repurchaseParamDef("penalty"),
                repurchaseParamDef("presets"));
    }

    private RepurchaseParamDef repurchaseParamDef(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        return switch (key) {
            case "apy" -> new RepurchaseParamDef(
                    key,
                    REPURCHASE_PREFIX + "apy",
                    "NUMBER",
                    "35",
                    "Annual APY",
                    "90 day lock; applies to new repurchase orders",
                    "Raising APY amplifies future outflow and triggers B1 coverage redline check",
                    true,
                    RepurchaseParamKind.PERCENT);
            case "nurture" -> new RepurchaseParamDef(
                    key,
                    REPURCHASE_PREFIX + "nurture",
                    "NUMBER",
                    "1.5",
                    "Nurture reward multiplier",
                    "Applied immediately to repurchase cultivation reward",
                    "Raising multiplier amplifies reward outflow and triggers B1 coverage redline check",
                    false,
                    RepurchaseParamKind.MULTIPLIER);
            case "lottery" -> new RepurchaseParamDef(
                    key,
                    REPURCHASE_PREFIX + "lottery",
                    "NUMBER",
                    "1",
                    "Genesis ticket per order",
                    "Per repurchase order ticket issuance; G4 prize capacity must be checked",
                    "Updates ticket issuance rule and refreshes monthly issued ticket forecast",
                    false,
                    RepurchaseParamKind.INTEGER);
            case "penalty" -> new RepurchaseParamDef(
                    key,
                    REPURCHASE_PREFIX + "penalty",
                    "NUMBER",
                    "15",
                    "Early withdrawal penalty",
                    "Principal penalty percent plus forfeited interest and ticket",
                    "Lowering penalty amplifies outflow and triggers B1 coverage redline check",
                    false,
                    RepurchaseParamKind.PERCENT);
            case "presets" -> new RepurchaseParamDef(
                    key,
                    REPURCHASE_PREFIX + "presets",
                    "STRING",
                    "$100 / 200 / 500 / 1,000",
                    "Preset amount tiers",
                    "User facing quick amount tiers",
                    "Amount tier display updates immediately; no outflow amplification by itself",
                    false,
                    RepurchaseParamKind.TEXT);
            default -> null;
        };
    }

    private Map<String, Object> repurchaseParamRow(RepurchaseParamDef def) {
        String value = readText(def.configKey(), def.defaultValue());
        return map(
                "key", def.key(),
                "configKey", def.configKey(),
                "name", def.name(),
                "sub", def.sub(),
                "value", value,
                "displayValue", displayRepurchaseValue(def, value),
                "note", def.note(),
                "newOnly", def.newOnly(),
                "b1RedlineTriggered", def.kind() == RepurchaseParamKind.PERCENT || def.kind() == RepurchaseParamKind.MULTIPLIER,
                "valueType", def.valueType());
    }

    private String normalizeRepurchaseParamValue(RepurchaseParamDef def, String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        if (def.kind() == RepurchaseParamKind.TEXT) {
            String value = rawValue.trim();
            return containsSunsetTerm(value) ? null : value;
        }
        BigDecimal value = parseRepurchaseNumber(rawValue, null);
        if (value == null) {
            return null;
        }
        if (def.kind() == RepurchaseParamKind.INTEGER) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                return null;
            }
            return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        if (def.kind() == RepurchaseParamKind.MULTIPLIER) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("10")) > 0) {
                return null;
            }
            return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("300")) > 0) {
            return null;
        }
        if ("penalty".equals(def.key()) && value.compareTo(new BigDecimal("100")) > 0) {
            return null;
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean repurchaseParamLoosens(RepurchaseParamDef def, String oldValue, String newValue) {
        BigDecimal oldNumber = parseRepurchaseNumber(oldValue, BigDecimal.ZERO);
        BigDecimal newNumber = parseRepurchaseNumber(newValue, BigDecimal.ZERO);
        if ("apy".equals(def.key()) || "nurture".equals(def.key())) {
            return newNumber.compareTo(oldNumber) > 0;
        }
        if ("penalty".equals(def.key())) {
            return newNumber.compareTo(oldNumber) < 0;
        }
        return false;
    }

    private BigDecimal repurchaseNumberValue(RepurchaseParamDef def) {
        return parseRepurchaseNumber(readText(def.configKey(), def.defaultValue()), new BigDecimal(def.defaultValue()));
    }

    private BigDecimal repurchaseMatureUsd(BigDecimal principalUsd, BigDecimal apyPct) {
        BigDecimal interest = safeBig(principalUsd)
                .multiply(safeBig(apyPct))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(REPURCHASE_LOCK_DAYS))
                .divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);
        return safeBig(principalUsd).add(interest).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> repurchaseCoverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        return map(
                "coverageRatio", snapshot.coverageRatio(),
                "redlinePct", snapshot.redlinePct(),
                "redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0,
                "precheck", "raising APY or nurture multiplier, or lowering early withdrawal penalty, requires B1 coverage redline check");
    }

    private String displayRepurchaseValue(RepurchaseParamDef def, String value) {
        return switch (def.kind()) {
            case PERCENT -> "penalty".equals(def.key())
                    ? "principal " + value + "% + forfeit"
                    : value + "%";
            case MULTIPLIER -> "x" + value;
            case INTEGER -> "+" + value + " / order";
            case TEXT -> value;
        };
    }

    private BigDecimal parseRepurchaseNumber(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String normalized = raw.trim()
                .replace(",", "")
                .replace("x", "")
                .replace("X", "")
                .replace("×", "")
                .replace("+", "")
                .replaceAll("[^0-9.\\-]", "");
        if (!StringUtils.hasText(normalized) || ".".equals(normalized) || "-".equals(normalized)) {
            return fallback;
        }
        try {
            return new BigDecimal(normalized);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private boolean containsSunsetTerm(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return normalized.contains("premium")
                || normalized.contains("nexv2")
                || normalized.contains("nex.v2")
                || normalized.contains("points")
                || normalized.contains("积分");
    }

    private BigDecimal safeBig(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<GenesisParamDef> genesisParamDefs() {
        return List.of(
                genesisParamDef("supply"),
                genesisParamDef("price"),
                genesisParamDef("dividend"),
                genesisParamDef("royalty"),
                genesisParamDef("divBase"));
    }

    private GenesisParamDef genesisParamDef(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        return switch (key) {
            case "supply" -> new GenesisParamDef(
                    key,
                    GENESIS_PREFIX + "supply",
                    "NUMBER",
                    "1000",
                    "Node supply",
                    "Cannot be lower than minted/sold nodes",
                    "Increasing future supply can amplify future dividend liability and triggers B1 coverage redline check",
                    true,
                    GenesisParamKind.INTEGER);
            case "price" -> new GenesisParamDef(
                    key,
                    GENESIS_PREFIX + "price",
                    "NUMBER",
                    "9999",
                    "Primary sale price",
                    "Applies to future primary purchases only",
                    "Raising price raises the guaranteed daily accrual base and triggers B1 coverage redline check",
                    true,
                    GenesisParamKind.MONEY);
            case "dividend" -> new GenesisParamDef(
                    key,
                    GENESIS_PREFIX + "dividend",
                    "NUMBER",
                    "0.1",
                    "Daily dividend rate",
                    "Baseline 0.1% per day; any upward deviation requires PM decision reference",
                    "Raising the daily rate directly amplifies USDT outflow and triggers B1 coverage redline check",
                    true,
                    GenesisParamKind.PERCENT);
            case "royalty" -> new GenesisParamDef(
                    key,
                    GENESIS_PREFIX + "royalty",
                    "NUMBER",
                    "2.5",
                    "Secondary royalty",
                    "Deducted from seller side on secondary trade",
                    "Applies to new secondary trades only",
                    false,
                    GenesisParamKind.PERCENT);
            case "divBase" -> new GenesisParamDef(
                    key,
                    GENESIS_PREFIX + "divBase",
                    "STRING",
                    "platform daily volume * 0.1% / 1000 slots",
                    "Dividend base formula",
                    "Controls daily pool basis and equal-split rule",
                    "Changing the formula changes daily payout and triggers B1 coverage redline check",
                    true,
                    GenesisParamKind.TEXT);
            default -> null;
        };
    }

    private Map<String, Object> genesisParamRow(GenesisParamDef def) {
        String value = readText(def.configKey(), def.defaultValue());
        return map(
                "key", def.key(),
                "configKey", def.configKey(),
                "name", def.name(),
                "sub", def.sub(),
                "value", value,
                "displayValue", displayGenesisValue(def, value),
                "note", def.note(),
                "b1RedlineTriggered", def.b1RedlineTriggered(),
                "valueType", def.valueType());
    }

    private String normalizeGenesisParamValue(GenesisParamDef def, String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        if (def.kind() == GenesisParamKind.TEXT) {
            String value = rawValue.trim();
            if (value.length() > 160 || containsSunsetTerm(value)) {
                return null;
            }
            return value;
        }
        BigDecimal value = parseRepurchaseNumber(rawValue, null);
        if (value == null) {
            return null;
        }
        if (def.kind() == GenesisParamKind.INTEGER) {
            if (value.compareTo(BigDecimal.valueOf(GENESIS_SOLD)) < 0 || value.compareTo(new BigDecimal("100000")) > 0) {
                return null;
            }
            return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        if (def.kind() == GenesisParamKind.MONEY) {
            if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
                return null;
            }
            return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        BigDecimal max = "royalty".equals(def.key()) ? new BigDecimal("20") : new BigDecimal("5");
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(max) > 0) {
            return null;
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean genesisParamLoosens(GenesisParamDef def, String oldValue, String newValue) {
        if ("divBase".equals(def.key())) {
            return !oldValue.trim().equals(newValue.trim());
        }
        BigDecimal oldNumber = parseRepurchaseNumber(oldValue, BigDecimal.ZERO);
        BigDecimal newNumber = parseRepurchaseNumber(newValue, BigDecimal.ZERO);
        if ("supply".equals(def.key()) || "price".equals(def.key()) || "dividend".equals(def.key())) {
            return newNumber.compareTo(oldNumber) > 0;
        }
        return false;
    }

    private BigDecimal genesisNumberValue(GenesisParamDef def) {
        return parseRepurchaseNumber(readText(def.configKey(), def.defaultValue()), new BigDecimal(def.defaultValue()));
    }

    private String displayGenesisValue(GenesisParamDef def, String value) {
        return switch (def.kind()) {
            case INTEGER -> value + " slots";
            case MONEY -> displayUsd(parseRepurchaseNumber(value, BigDecimal.ZERO));
            case PERCENT -> "dividend".equals(def.key()) ? value + "% / day" : value + "%";
            case TEXT -> value;
        };
    }

    private boolean genesisMarketOn() {
        return configFacade.activeValue(GENESIS_KILLSWITCH_KEY)
                .map(this::parseSwitchEnabled)
                .orElse(true);
    }

    private Map<String, Object> genesisCoverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        return map(
                "coverageRatio", snapshot.coverageRatio(),
                "redlinePct", snapshot.redlinePct(),
                "redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0,
                "precheck", "raising supply, sale price, daily dividend rate, restoring the market, or changing payout basis requires B1 coverage redline check");
    }

    private List<Map<String, Object>> genesisNodes(BigDecimal perSlotPerDay, BigDecimal floorPerNodePerDay) {
        String perSlot = displayUsd(perSlotPerDay.setScale(2, RoundingMode.HALF_UP)) + " / day";
        String floor = displayUsd(floorPerNodePerDay.setScale(2, RoundingMode.HALF_UP)) + " / day";
        return List.of(
                genesisNode(
                        "#0042",
                        "usr_31E8",
                        "primary",
                        displayUsd(new BigDecimal("3840")),
                        "held",
                        "持有计分红",
                        "ok",
                        "2026-01 primary " + displayUsd(new BigDecimal("9999")),
                        List.of(
                                map("label", "Lifetime dividend", "value", displayUsd(new BigDecimal("3840")) + " (160 days)"),
                                map("label", "Current daily dividend", "value", perSlot),
                                map("label", "Guaranteed accrual basis", "value", floor),
                                map("label", "Last payout", "value", "today 00:00 batch")),
                        List.of(map("time", "-", "event", "Primary holder, no secondary transfer", "royalty", "-"))),
                genesisNode(
                        "#0117",
                        "usr_19C7",
                        "secondary transfer 05-12",
                        displayUsd(new BigDecimal("696")),
                        "held",
                        "持有计分红",
                        "ok",
                        "2026-01 primary, transferred on 2026-05-12",
                        List.of(
                                map("label", "Lifetime dividend after transfer", "value", displayUsd(new BigDecimal("696")) + " (29 days)"),
                                map("label", "Current daily dividend", "value", perSlot),
                                map("label", "Transfer rule", "value", "Dividend follows NFT holder")),
                        List.of(map("time", "2026-05-12", "event", "usr_3990 -> usr_19C7 secondary trade " + displayUsd(new BigDecimal("11200")), "royalty", "2.5% deducted"))),
                genesisNode(
                        "#0233",
                        "usr_84F2",
                        "primary",
                        displayUsd(new BigDecimal("3000")),
                        "listed",
                        "二级挂单中",
                        "dim",
                        "2026-02 primary " + displayUsd(new BigDecimal("9999")),
                        List.of(
                                map("label", "Lifetime dividend", "value", displayUsd(new BigDecimal("3000")) + " (125 days)"),
                                map("label", "Current daily dividend", "value", perSlot),
                                map("label", "Listing price", "value", displayUsd(new BigDecimal("12800")))),
                        List.of(map("time", "2026-05-28", "event", "usr_84F2 listed on secondary market " + displayUsd(new BigDecimal("12800")), "royalty", "pending"))));
    }

    private Map<String, Object> genesisNode(
            String id,
            String owner,
            String source,
            String lifetimeDividend,
            String status,
            String statusLabel,
            String statusTone,
            String buy,
            List<Map<String, Object>> dividends,
            List<Map<String, Object>> transfers) {
        return map(
                "id", id,
                "owner", owner,
                "source", source,
                "lifetimeDividend", lifetimeDividend,
                "status", status,
                "statusLabel", statusLabel,
                "statusTone", statusTone,
                "buy", buy,
                "dividends", dividends,
                "transfers", transfers);
    }

    private List<Map<String, Object>> exchangeCaps(BigDecimal todayUsd, BigDecimal platformCap) {
        BigDecimal userCap = readDecimal(EXCHANGE_USER_DAILY_CAP_KEY, new BigDecimal("50"));
        BigDecimal feePct = readDecimal(EXCHANGE_FEE_PCT_KEY, BigDecimal.ZERO);
        BigDecimal feeMin = readDecimal(EXCHANGE_FEE_MIN_KEY, new BigDecimal("0.50"));
        String queueMode = normalizeQueueMode(readText(EXCHANGE_QUEUE_MODE_KEY, "QUEUE"));
        return List.of(
                cap("userDailyCap", "单用户日额度", "每人每天最多换出多少 USDT", userCap, displayUsd(userCap),
                        "范围 $0-10,000 · 放宽过红线", true, null),
                cap("platformDailyCap", "平台日额度", "全平台每天兑换总池", platformCap, displayUsd(platformCap),
                        "范围 $0-10,000,000 · 放宽过红线", true, percent(todayUsd, platformCap)),
                cap("fee", "兑换手续费率", "每笔 NEX->USDT 抽成 · 当前可为免费推广期", feePct, displayPct(feePct),
                        "范围 0%-10% · 降费=放大流出过红线", true, null),
                cap("feeMin", "最低手续费", "开费后小额兑换的保底费", feeMin, displayUsd(feeMin),
                        "范围 $0-5 · 随费率启用生效", true, null),
                cap("queueMode", "超 cap 处置策略", "用户超 cap 时进次日队列还是直接拒绝", queueMode, displayQueueMode(queueMode),
                        "枚举: 排队 / 拒绝", "QUEUE".equals(queueMode), null));
    }

    private Map<String, Object> cap(
            String key,
            String name,
            String sub,
            Object rawValue,
            String displayValue,
            String note,
            boolean loosen,
            BigDecimal meterPct) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("name", name);
        row.put("sub", sub);
        row.put("value", rawValue);
        row.put("displayValue", displayValue);
        row.put("note", note);
        row.put("loosen", loosen);
        if (meterPct != null) {
            row.put("meterPct", meterPct);
        }
        return row;
    }

    private Map<String, Object> gate(String key, String title, String note, List<String> statuses) {
        List<ExchangeOrderView> rows = marketRepository.exchangeOrdersByStatuses(statuses, 50);
        return map(
                "key", key,
                "title", title,
                "note", note,
                "count", rows.size(),
                "rows", rows);
    }

    private Map<String, Object> exchangeCoverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        return map(
                "coverageRatio", snapshot.coverageRatio(),
                "redlinePct", snapshot.redlinePct(),
                "redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0,
                "precheck", "loosening exchange outflow requires B1 coverage redline check");
    }

    private ExchangeParamDef exchangeParamDef(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        return switch (key) {
            case "userDailyCap" -> new ExchangeParamDef(
                    key, EXCHANGE_USER_DAILY_CAP_KEY, "NUMBER", "50", "G2 user daily exchange cap", ExchangeParamKind.DECIMAL);
            case "platformDailyCap" -> new ExchangeParamDef(
                    key, EXCHANGE_PLATFORM_DAILY_CAP_KEY, "NUMBER", "20000", "G2 platform daily exchange cap", ExchangeParamKind.DECIMAL);
            case "fee" -> new ExchangeParamDef(
                    key, EXCHANGE_FEE_PCT_KEY, "NUMBER", "0", "G2 exchange fee percent", ExchangeParamKind.PERCENT);
            case "feeMin" -> new ExchangeParamDef(
                    key, EXCHANGE_FEE_MIN_KEY, "NUMBER", "0.50", "G2 minimum exchange fee", ExchangeParamKind.DECIMAL);
            case "queueMode" -> new ExchangeParamDef(
                    key, EXCHANGE_QUEUE_MODE_KEY, "STRING", "QUEUE", "G2 over-cap queue strategy", ExchangeParamKind.QUEUE_MODE);
            default -> null;
        };
    }

    private String normalizeExchangeParamValue(ExchangeParamDef def, String rawValue) {
        if (def.kind() == ExchangeParamKind.QUEUE_MODE) {
            return normalizeQueueMode(rawValue);
        }
        BigDecimal value = parseDecimalLoose(rawValue, null);
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        if (def.kind() == ExchangeParamKind.PERCENT && value.compareTo(BigDecimal.valueOf(10)) > 0) {
            return null;
        }
        if ("userDailyCap".equals(def.key()) && value.compareTo(new BigDecimal("10000")) > 0) {
            return null;
        }
        if ("platformDailyCap".equals(def.key()) && value.compareTo(new BigDecimal("10000000")) > 0) {
            return null;
        }
        if ("feeMin".equals(def.key()) && value.compareTo(new BigDecimal("5")) > 0) {
            return null;
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean exchangeParamLoosens(ExchangeParamDef def, String oldValue, String newValue) {
        if (def.kind() == ExchangeParamKind.QUEUE_MODE) {
            return "REJECT".equals(normalizeQueueMode(oldValue)) && "QUEUE".equals(normalizeQueueMode(newValue));
        }
        BigDecimal oldNumber = parseDecimalLoose(oldValue, BigDecimal.ZERO);
        BigDecimal newNumber = parseDecimalLoose(newValue, BigDecimal.ZERO);
        if ("userDailyCap".equals(def.key()) || "platformDailyCap".equals(def.key())) {
            return newNumber.compareTo(oldNumber) > 0;
        }
        if ("fee".equals(def.key()) || "feeMin".equals(def.key())) {
            return newNumber.compareTo(oldNumber) < 0;
        }
        return false;
    }

    private boolean exchangeSwapEnabled() {
        return configFacade.activeValue(EXCHANGE_KILLSWITCH_KEY)
                .or(() -> configFacade.activeValue(EXCHANGE_LEGACY_KILLSWITCH_KEY))
                .map(this::parseSwitchEnabled)
                .orElse(true);
    }

    private boolean parseSwitchEnabled(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        return switch (value) {
            case "enabled", "enable", "on", "true", "1" -> true;
            case "disabled", "disable", "off", "false", "0" -> false;
            default -> true;
        };
    }

    private List<Map<String, Object>> blockedCountryViews() {
        return countrySeeds().stream()
                .map(seed -> {
                    String status = configFacade.activeValue("emergency.geo.country." + seed.code())
                            .or(() -> configFacade.activeValue("emergency.geo." + seed.code()))
                            .orElse(seed.status());
                    return map("cc", seed.code(), "name", seed.name(), "status", status, "reason", seed.reason());
                })
                .filter(row -> "blocked".equals(row.get("status")))
                .toList();
    }

    private List<CountrySeed> countrySeeds() {
        return List.of(
                new CountrySeed("KP", "朝鲜", "blocked", "OFAC 制裁名单"),
                new CountrySeed("IR", "伊朗", "blocked", "OFAC + FATF 制裁名单"),
                new CountrySeed("SY", "叙利亚", "blocked", "OFAC 制裁名单"),
                new CountrySeed("CN", "中国", "allowed", "运营灰度国家"),
                new CountrySeed("US", "美国", "allowed", "主市场"),
                new CountrySeed("IN", "印度", "allowed", "增长市场"),
                new CountrySeed("BR", "巴西", "allowed", "增长市场"));
    }

    private BigDecimal readDecimal(String configKey, BigDecimal fallback) {
        return configFacade.activeValue(configKey)
                .map(value -> parseDecimalLoose(value, fallback))
                .orElse(fallback);
    }

    private BigDecimal parseDecimalLoose(String raw, BigDecimal fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String normalized = raw.trim()
                .replace("$", "")
                .replace("%", "")
                .replace(",", "")
                .replace("免费", "")
                .replace("(", "")
                .replace(")", "");
        try {
            return new BigDecimal(normalized.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private String displayUsd(BigDecimal value) {
        return "$" + value.stripTrailingZeros().toPlainString();
    }

    private String displayPct(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return "0%(免费)";
        }
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    private String normalizeQueueMode(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        return switch (value) {
            case "QUEUE", "QUEUED", "排队" -> "QUEUE";
            case "REJECT", "REJECTED", "拒绝" -> "REJECT";
            default -> "";
        };
    }

    private String displayQueueMode(String value) {
        return "REJECT".equals(value) ? "拒绝" : "排队";
    }

    private Map<String, Object> coverage() {
        TreasuryCoverageSnapshot snapshot = coverageFacade.snapshot();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("coverageRatio", snapshot.coverageRatio());
        coverage.put("redlinePct", snapshot.redlinePct());
        coverage.put("redlineBreached", snapshot.coverageRatio().compareTo(snapshot.redlinePct()) < 0);
        coverage.put("precheck", "week peak NEX liability is checked before raising price or pump probability");
        return coverage;
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<Map<String, Object>> validation(String message) {
        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message);
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void auditOverride(
            String overrideKey,
            String configKey,
            Object oldValue,
            Object newValue,
            String idempotencyKey,
            NexMarketValueUpdateRequest request) {
        audit("G3_OVERRIDE_CHANGED", "NEX_MARKET_OVERRIDE", configKey, request.operator(), Map.of(
                "overrideKey", overrideKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private enum ExchangeParamKind {
        DECIMAL,
        PERCENT,
        QUEUE_MODE
    }

    private enum StakingParamKind {
        PERCENT,
        AMOUNT
    }

    private enum RepurchaseParamKind {
        PERCENT,
        MULTIPLIER,
        INTEGER,
        TEXT
    }

    private enum GenesisParamKind {
        INTEGER,
        MONEY,
        PERCENT,
        TEXT
    }

    private record ExchangeParamDef(
            String key,
            String configKey,
            String valueType,
            String defaultValue,
            String remark,
            ExchangeParamKind kind) {
    }

    private record StakingPoolDef(
            String product,
            String tierKey,
            int termDays,
            String termLabel,
            String defaultApy,
            String defaultPenalty,
            String defaultMin,
            BigDecimal lockedUsd,
            String minAsset,
            boolean highYield) {
    }

    private record StakingParamDef(
            String key,
            String configKey,
            String valueType,
            String defaultValue,
            String remark,
            StakingParamKind kind) {
    }

    private record RepurchaseParamDef(
            String key,
            String configKey,
            String valueType,
            String defaultValue,
            String name,
            String sub,
            String note,
            boolean newOnly,
            RepurchaseParamKind kind) {
    }

    private record GenesisParamDef(
            String key,
            String configKey,
            String valueType,
            String defaultValue,
            String name,
            String sub,
            String note,
            boolean b1RedlineTriggered,
            GenesisParamKind kind) {
    }

    private record CountrySeed(String code, String name, String status, String reason) {
    }
}
