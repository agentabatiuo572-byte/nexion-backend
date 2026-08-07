package ffdd.opsconsole.market.application;

import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
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
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** G4 user boundary. Every displayed entitlement and every mutation is server authoritative. */
@Service
@RequiredArgsConstructor
public class AppGenesisService {
    private static final String KILL_KEY = "killswitch.genesis";
    private static final String LEGACY_KILL_KEY = "J.killswitch.genesis";
    private static final String DISCLOSURE_KEY = "disclosure.gate.genesis";
    private static final String EMISSION_GATE_KEY = "growth.phase.genesis_emissions_open";
    private static final String SALE_PREFIX = "market.genesis.ops.";

    private final AppGenesisMapper mapper;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final Clock clock;

    public ApiResult<Map<String, Object>> state() {
        AppGenesisMapper.SeriesRow series = requireSeries();
        long sold = mapper.holdingCount(series.seriesCode());
        boolean marketEnabled = marketEnabled();
        SalePolicy salePolicy = salePolicy(series);
        return ApiResult.ok(linked(
                "series", seriesView(series, sold),
                "market", linked("enabled", marketEnabled, "restoreOwner", "J1", "internalP2POnly", true),
                "emission", linked("open", emissionOpen(), "owner", "H1", "dailyRatePct", nz(series.dailyEmissionRatePct())),
                "sale", salePolicy.publicView(clock.instant()),
                "listings", mapper.listings(), "transactions", mapper.transactions(),
                "serverCanonical", true,
                "sources", List.of("nx_genesis_series", "nx_genesis_holding", "nx_genesis_order",
                        "nx_genesis_emission_batch", "nx_genesis_emission_item", "nx_wallet_ledger",
                        "nx_config_item:market.genesis.ops.*")));
    }

    public ApiResult<Map<String, Object>> account(Long userId) {
        requireUser(userId);
        AppGenesisMapper.SeriesRow series = requireSeries();
        return ApiResult.ok(accountView(userId, series));
    }

    public ApiResult<Map<String, Object>> eligibility(Long userId) {
        requireUser(userId);
        AppGenesisMapper.SeriesRow series = requireSeries();
        return ApiResult.ok(eligibilityView(userId, series, salePolicy(series)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> purchase(Long userId, String idempotencyKey, PurchaseRequest request) {
        requireUser(userId);
        int quantity = request == null || request.quantity() == null ? 0 : request.quantity();
        if (quantity < 1 || quantity > 20) throw new BizException(422, "GENESIS_QUANTITY_INVALID");
        return once("PRIMARY_PURCHASE", userId, idempotencyKey, quantity,
                () -> purchaseInternal(userId, idempotencyKey, quantity));
    }

    private ApiResult<Map<String, Object>> purchaseInternal(Long userId, String idempotencyKey, int quantity) {
        if (!marketEnabled()) throw new BizException(409, "GENESIS_MARKET_PAUSED");
        AppGenesisMapper.SeriesRow series = mapper.lockActiveSeries();
        if (series == null) throw new BizException(409, "GENESIS_SERIES_UNAVAILABLE");
        SalePolicy salePolicy = salePolicy(series);
        requireEligibleUser(userId, series, salePolicy, quantity);
        long sold = mapper.lockHoldingCount(series.seriesCode());
        if (sold + quantity > series.totalSupply()) throw new BizException(409, "GENESIS_SOLD_OUT");
        if (mapper.updateSoldSupply(series.id(), sold + quantity) != 1) {
            throw new BizException(409, "GENESIS_SUPPLY_CONFLICT");
        }
        BigDecimal unitPrice = salePolicy.purchasePrice(clock.instant(), money(series.priceUsdt()));
        BigDecimal amount = money(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        BigDecimal beforeBalance = requireWallet(userId);
        if (beforeBalance.compareTo(amount) < 0 || mapper.debitWallet(userId, amount) != 1) {
            throw new BizException(409, "GENESIS_WALLET_INSUFFICIENT");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String orderNo = randomNo("G4P");
        AppGenesisMapper.OrderWrite order = new AppGenesisMapper.OrderWrite(
                orderNo, idempotencyKey.trim(), userId, series.seriesCode(), quantity,
                unitPrice, amount, "PRIMARY", null, null, BigDecimal.ZERO, now);
        if (mapper.insertOrder(order) != 1) throw new BizException(409, "GENESIS_ORDER_CONFLICT");
        for (int i = 1; i <= quantity; i++) {
            String holdingNo = orderNo + "-" + String.format(Locale.ROOT, "%02d", i);
            if (mapper.insertHolding(new AppGenesisMapper.HoldingWrite(
                    holdingNo, userId, orderNo, series.seriesCode(), unitPrice, now)) != 1) {
                throw new BizException(409, "GENESIS_HOLDING_CONFLICT");
            }
        }
        BigDecimal balanceAfter = money(beforeBalance.subtract(amount));
        if (mapper.insertLedger(new AppGenesisMapper.LedgerWrite(
                userId, orderNo, "GENESIS_PURCHASE", "OUT", amount, balanceAfter,
                "G4 Genesis primary purchase")) != 1) throw new BizException(409, "GENESIS_LEDGER_CONFLICT");
        AppGenesisMapper.UserPolicyRow policy = requirePolicy(userId);
        Map<String, Object> event = linked("orderNo", orderNo, "seriesCode", series.seriesCode(),
                "quantity", quantity, "unitPriceUsdt", unitPrice, "amountUsdt", amount,
                "walletBalanceUsdt", balanceAfter);
        String receiptId = publishUser("GENESIS_ORDER", orderNo, "genesis.purchased", policy, event);
        recordAudit("USER_GENESIS_PURCHASED", "GENESIS_ORDER", orderNo, userId, idempotencyKey,
                orderNo, "/api/genesis/purchase", event);
        Map<String, Object> response = accountView(userId, series);
        response.put("order", event);
        response.put("billNo", orderNo);
        response.put("receiptId", receiptId);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> list(Long userId, String holdingNo, String idempotencyKey, ListingRequest request) {
        requireUser(userId);
        String no = normalizeHoldingNo(holdingNo);
        BigDecimal price = request == null ? null : request.askPriceUsdt();
        if (price == null || price.signum() <= 0 || price.compareTo(new BigDecimal("100000000")) > 0) {
            throw new BizException(422, "GENESIS_LISTING_PRICE_INVALID");
        }
        BigDecimal normalizedPrice = money(price);
        return once("LIST:" + no, userId, idempotencyKey, normalizedPrice,
                () -> listInternal(userId, no, idempotencyKey, normalizedPrice));
    }

    private ApiResult<Map<String, Object>> listInternal(Long userId, String holdingNo, String key, BigDecimal price) {
        if (!marketEnabled()) throw new BizException(409, "GENESIS_MARKET_PAUSED");
        AppGenesisMapper.HoldingRow holding = requireHolding(holdingNo);
        if (!userId.equals(holding.userId())) throw new BizException(403, "GENESIS_HOLDING_OWNER_REQUIRED");
        AppGenesisMapper.SeriesRow series = requireSeries();
        requireEligibleUser(userId, series, salePolicy(series), 0);
        if (!"ACTIVE".equals(holding.status()) || mapper.listHolding(holding.id(), userId, price, LocalDateTime.now(clock)) != 1) {
            throw new BizException(409, "GENESIS_HOLDING_NOT_LISTABLE");
        }
        AppGenesisMapper.UserPolicyRow policy = requirePolicy(userId);
        Map<String, Object> event = linked("holdingNo", holdingNo, "askPriceUsdt", price);
        String receipt = publishUser("GENESIS_HOLDING", holdingNo, "genesis.listed", policy, event);
        recordAudit("USER_GENESIS_LISTED", "GENESIS_HOLDING", holdingNo, userId, key,
                holdingNo + "-LIST", "/api/genesis/holdings/" + holdingNo + "/listing", event);
        Map<String, Object> response = accountView(userId, requireSeries());
        response.put("receiptId", receipt);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> cancel(Long userId, String holdingNo, String idempotencyKey) {
        requireUser(userId);
        String no = normalizeHoldingNo(holdingNo);
        return once("CANCEL_LIST:" + no, userId, idempotencyKey, no,
                () -> cancelInternal(userId, no, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> cancelInternal(Long userId, String holdingNo, String key) {
        AppGenesisMapper.HoldingRow holding = requireHolding(holdingNo);
        if (!userId.equals(holding.userId())) throw new BizException(403, "GENESIS_HOLDING_OWNER_REQUIRED");
        if (mapper.cancelListing(holding.id(), userId) != 1) throw new BizException(409, "GENESIS_LISTING_NOT_ACTIVE");
        AppGenesisMapper.UserPolicyRow policy = requirePolicy(userId);
        Map<String, Object> event = linked("holdingNo", holdingNo, "status", "CANCELLED");
        String receipt = publishUser("GENESIS_HOLDING", holdingNo, "genesis.listing_cancelled", policy, event);
        recordAudit("USER_GENESIS_LISTING_CANCELLED", "GENESIS_HOLDING", holdingNo, userId, key,
                holdingNo + "-CANCEL", "/api/genesis/holdings/" + holdingNo + "/listing/cancel", event);
        Map<String, Object> response = accountView(userId, requireSeries());
        response.put("receiptId", receipt);
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> buyListing(Long userId, String holdingNo, String idempotencyKey) {
        requireUser(userId);
        String no = normalizeHoldingNo(holdingNo);
        return once("SECONDARY_BUY:" + no, userId, idempotencyKey, no,
                () -> buyListingInternal(userId, no, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> buyListingInternal(Long userId, String holdingNo, String key) {
        if (!marketEnabled()) throw new BizException(409, "GENESIS_MARKET_PAUSED");
        AppGenesisMapper.HoldingRow holding = requireHolding(holdingNo);
        if (!"LISTED".equals(holding.status()) || holding.listingPriceUsdt() == null) {
            throw new BizException(409, "GENESIS_LISTING_NOT_ACTIVE");
        }
        if (userId.equals(holding.userId())) throw new BizException(409, "GENESIS_SELF_TRADE_FORBIDDEN");
        AppGenesisMapper.SeriesRow series = mapper.lockActiveSeries();
        if (series == null || !series.seriesCode().equals(holding.seriesCode())) {
            throw new BizException(409, "GENESIS_SERIES_UNAVAILABLE");
        }
        requireEligibleUser(userId, series, salePolicy(series), 1);
        BigDecimal price = money(holding.listingPriceUsdt());
        BigDecimal royalty = price.multiply(BigDecimal.valueOf(series.royaltyBps()))
                .divide(BigDecimal.valueOf(10_000), 6, RoundingMode.HALF_UP);
        BigDecimal sellerNet = money(price.subtract(royalty));
        BigDecimal buyerBefore = requireWallet(userId);
        BigDecimal sellerBefore = requireWallet(holding.userId());
        if (buyerBefore.compareTo(price) < 0 || mapper.debitWallet(userId, price) != 1) {
            throw new BizException(409, "GENESIS_WALLET_INSUFFICIENT");
        }
        if (mapper.creditWallet(holding.userId(), sellerNet) != 1) throw new BizException(409, "GENESIS_SELLER_WALLET_CONFLICT");
        LocalDateTime now = LocalDateTime.now(clock);
        String orderNo = randomNo("G4S");
        if (mapper.insertOrder(new AppGenesisMapper.OrderWrite(orderNo, key.trim(), userId, series.seriesCode(),
                1, price, price, "SECONDARY", holding.userId(), holdingNo, royalty, now)) != 1) {
            throw new BizException(409, "GENESIS_ORDER_CONFLICT");
        }
        if (mapper.transferHolding(holding.id(), holding.userId(), userId, orderNo, price, now) != 1) {
            throw new BizException(409, "GENESIS_LISTING_CONFLICT");
        }
        BigDecimal buyerAfter = money(buyerBefore.subtract(price));
        BigDecimal sellerAfter = money(sellerBefore.add(sellerNet));
        if (mapper.insertLedger(new AppGenesisMapper.LedgerWrite(userId, orderNo + "-BUY", "GENESIS_SECONDARY_BUY",
                "OUT", price, buyerAfter, "G4 internal P2P purchase")) != 1
                || mapper.insertLedger(new AppGenesisMapper.LedgerWrite(holding.userId(), orderNo + "-SELL",
                "GENESIS_SECONDARY_SELL", "IN", sellerNet, sellerAfter,
                "G4 internal P2P sale net of royalty")) != 1) {
            throw new BizException(409, "GENESIS_LEDGER_CONFLICT");
        }
        AppGenesisMapper.UserPolicyRow buyerPolicy = requirePolicy(userId);
        Map<String, Object> event = linked("orderNo", orderNo, "holdingNo", holdingNo,
                "buyerUserId", userId, "sellerUserId", holding.userId(), "priceUsdt", price,
                "royaltyUsdt", royalty, "sellerNetUsdt", sellerNet);
        String receipt = publishUser("GENESIS_ORDER", orderNo, "genesis.secondary_traded", buyerPolicy, event);
        recordAudit("USER_GENESIS_SECONDARY_PURCHASED", "GENESIS_ORDER", orderNo, userId, key,
                orderNo, "/api/genesis/listings/" + holdingNo + "/buy", event);
        Map<String, Object> response = accountView(userId, series);
        response.put("trade", event);
        response.put("receiptId", receipt);
        return ApiResult.ok(response);
    }

    private Map<String, Object> accountView(Long userId, AppGenesisMapper.SeriesRow series) {
        SalePolicy policy = salePolicy(series);
        return linked("series", seriesView(series, mapper.holdingCount(series.seriesCode())),
                "holdings", mapper.holdings(userId), "emissions", mapper.emissions(userId),
                "walletBalanceUsdt", money(mapper.wallet(userId)), "marketEnabled", marketEnabled(),
                "emissionOpen", emissionOpen(), "sale", policy.publicView(clock.instant()),
                "eligibility", eligibilityView(userId, series, policy), "serverCanonical", true);
    }

    private Map<String, Object> seriesView(AppGenesisMapper.SeriesRow series, long sold) {
        long total = Math.max(0, series.totalSupply() == null ? 0 : series.totalSupply());
        return linked("seriesCode", series.seriesCode(), "name", series.name(), "totalSupply", total,
                "soldSupply", sold, "remainingSupply", Math.max(0, total - sold),
                "priceUsdt", money(series.priceUsdt()),
                "royaltyPct", BigDecimal.valueOf(series.royaltyBps() == null ? 0 : series.royaltyBps())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP),
                "dailyEmissionRatePct", nz(series.dailyEmissionRatePct()));
    }

    private void requireEligibleUser(Long userId, AppGenesisMapper.SeriesRow series, SalePolicy salePolicy,
                                     int acquiringQuantity) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppGenesisMapper.UserPolicyRow policy = requirePolicy(userId);

        int ageDays = policy.accountAgeDays() == null ? 0 : Math.max(0, policy.accountAgeDays());
        if (salePolicy.eligibilityEnabled() && ageDays < salePolicy.minAccountAgeDays()) {
            throw new BizException(403, "GENESIS_ACCOUNT_AGE_REQUIRED");
        }
        if (policy.countryCode() == null || policy.countryCode().length() != 2) {
            throw new BizException(403, "GENESIS_COUNTRY_REQUIRED");
        }
        if (mapper.geoBlocked(policy.countryCode()) > 0) throw new BizException(403, "GENESIS_GEO_BLOCKED");
        if (acquiringQuantity > 0) {
            salePolicy.requireSaleOpen(clock.instant());
            long owned = mapper.userHoldingCount(userId, series.seriesCode());
            if (owned + acquiringQuantity > salePolicy.effectiveMaxPerUser()) {
                throw new BizException(409, "GENESIS_USER_CAP_REACHED");
            }
        }
    }

    private Map<String, Object> eligibilityView(Long userId, AppGenesisMapper.SeriesRow series, SalePolicy policy) {
        AppGenesisMapper.UserPolicyRow user = requirePolicy(userId);
        long owned = mapper.userHoldingCount(userId, series.seriesCode());
        List<String> reasons = new java.util.ArrayList<>();

        int ageDays = user.accountAgeDays() == null ? 0 : Math.max(0, user.accountAgeDays());
        if (policy.eligibilityEnabled() && ageDays < policy.minAccountAgeDays()) reasons.add("ACCOUNT_AGE_REQUIRED");
        if (user.countryCode() == null || user.countryCode().length() != 2) reasons.add("COUNTRY_REQUIRED");
        else if (mapper.geoBlocked(user.countryCode()) > 0) reasons.add("GEO_BLOCKED");
        if (!policy.saleOpen(clock.instant())) reasons.add("PRESALE_NOT_OPEN");
        int max = policy.effectiveMaxPerUser();
        if (owned >= max) reasons.add("USER_CAP_REACHED");
        return linked("eligible", reasons.isEmpty(), "reasons", reasons,
                "ownedCount", owned, "maxPerUser", max,
                "remainingCap", Math.max(0L, (long) max - owned),
                "minAccountAgeDays", policy.eligibilityEnabled() ? policy.minAccountAgeDays() : 0,
                "accountAgeDays", ageDays, "serverCanonical", true);
    }

    private SalePolicy salePolicy(AppGenesisMapper.SeriesRow series) {
        try {
            boolean eligibilityEnabled = configBoolean("eligibility.enabled", true);
            int eligibilityMax = configInteger("eligibility.maxPerUser", 5);
            int minAccountAgeDays = configInteger("eligibility.minAccountAgeDays", 0);
            boolean presaleEnabled = configBoolean("presale.enabled", false);
            boolean showCountdown = configBoolean("presale.showCountdown", true);
            BigDecimal presalePrice = configDecimal("presale.unitPrice", money(series.priceUsdt()));
            int presaleMax = configInteger("presale.maxPerUser", eligibilityMax);
            Instant startAt = configInstant("presale.startAt");
            Instant endAt = configInstant("presale.endAt");
            if (eligibilityMax < 0 || minAccountAgeDays < 0 || presaleMax < 0 || presalePrice.signum() <= 0) {
                throw new IllegalArgumentException();
            }
            if (presaleEnabled && (startAt == null || endAt == null || !startAt.isBefore(endAt))) {
                throw new IllegalArgumentException();
            }
            return new SalePolicy(eligibilityEnabled, eligibilityMax, minAccountAgeDays,
                    presaleEnabled, showCountdown, money(presalePrice), presaleMax, startAt, endAt);
        } catch (RuntimeException ex) {
            if (ex instanceof BizException bizException) throw bizException;
            throw new BizException(503, "GENESIS_SALE_POLICY_INVALID");
        }
    }

    private boolean configBoolean(String key, boolean fallback) {
        return config.activeValue(SALE_PREFIX + key).map(raw -> {
            if ("true".equalsIgnoreCase(raw.trim())) return true;
            if ("false".equalsIgnoreCase(raw.trim())) return false;
            throw new IllegalArgumentException();
        }).orElse(fallback);
    }

    private int configInteger(String key, int fallback) {
        return config.activeValue(SALE_PREFIX + key).map(raw -> Integer.parseInt(raw.trim())).orElse(fallback);
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return config.activeValue(SALE_PREFIX + key).map(raw -> new BigDecimal(raw.trim())).orElse(fallback);
    }

    private Instant configInstant(String key) {
        return config.activeValue(SALE_PREFIX + key).map(raw -> Instant.parse(raw.trim())).orElse(null);
    }

    private AppGenesisMapper.UserPolicyRow requirePolicy(Long userId) {
        AppGenesisMapper.UserPolicyRow row = mapper.userPolicy(userId);
        if (row == null) throw new BizException(404, "USER_NOT_FOUND");
        return row;
    }

    private AppGenesisMapper.SeriesRow requireSeries() {
        AppGenesisMapper.SeriesRow row = mapper.activeSeries();
        if (row == null) throw new BizException(503, "GENESIS_SERIES_UNAVAILABLE");
        return row;
    }

    private AppGenesisMapper.HoldingRow requireHolding(String holdingNo) {
        AppGenesisMapper.HoldingRow row = mapper.lockHolding(holdingNo);
        if (row == null) throw new BizException(404, "GENESIS_HOLDING_NOT_FOUND");
        return row;
    }

    private BigDecimal requireWallet(Long userId) {
        BigDecimal balance = mapper.lockWallet(userId);
        if (balance == null) throw new BizException(409, "GENESIS_WALLET_NOT_FOUND");
        return money(balance);
    }

    private boolean marketEnabled() {
        boolean disclosure = config.activeValue(DISCLOSURE_KEY).map(this::switchOn).orElse(false);
        return !disclosure && KillSwitchState.enabled(
                java.util.Optional.ofNullable(mapper.controlValue(KILL_KEY)),
                java.util.Optional.ofNullable(mapper.controlValue(LEGACY_KILL_KEY)));
    }

    private boolean emissionOpen() {
        return config.activeValue(EMISSION_GATE_KEY).map(this::switchOn).orElse(false);
    }

    private boolean switchOn(String value) {
        return value != null && List.of("1", "true", "on", "enabled", "open")
                .contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private String publishUser(String aggregateType, String aggregateId, String eventType,
                               AppGenesisMapper.UserPolicyRow policy, Map<String, Object> payload) {
        return outbox.publishUserEvent(aggregateType, aggregateId, eventType, policy.userId(),
                normalizePhase(policy.phase()), policy.accountAgeMonths(), policy.cohort(), payload);
    }

    private void recordAudit(String action, String resourceType, String resourceId, Long userId,
                             String idempotencyKey, String bizNo, String path, Map<String, Object> detail) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId).bizNo(bizNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .method("POST").path(path).result("SUCCESS").riskLevel("HIGH")
                .detail(linked("idempotencyKey", idempotencyKey.trim(), "state", detail)).build());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> once(String operation, Long userId, String key, Object request,
                                                 Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:G4_GENESIS_" + operation + ":USER:" + userId, key, sha256(String.valueOf(request)),
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

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
    }

    private String normalizeHoldingNo(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("[A-Za-z0-9-]{3,128}")) {
            throw new BizException(422, "GENESIS_HOLDING_NO_INVALID");
        }
        return value.trim();
    }

    private String normalizePhase(String value) {
        String phase = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "P1";
        if (phase.matches("[1-6]")) phase = "P" + phase;
        return phase.matches("P[1-6]") ? phase : "P1";
    }

    private String randomNo(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
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

    private record SalePolicy(boolean eligibilityEnabled, int eligibilityMaxPerUser,
                              int minAccountAgeDays, boolean presaleEnabled, boolean showCountdown,
                              BigDecimal presaleUnitPrice, int presaleMaxPerUser,
                              Instant presaleStartAt, Instant presaleEndAt) {
        int effectiveMaxPerUser() {
            int eligibilityMax = eligibilityEnabled ? eligibilityMaxPerUser : Integer.MAX_VALUE;
            return presaleEnabled ? Math.min(eligibilityMax, presaleMaxPerUser) : eligibilityMax;
        }

        boolean saleOpen(Instant now) {
            return !presaleEnabled || (presaleStartAt != null && presaleEndAt != null
                    && !now.isBefore(presaleStartAt) && now.isBefore(presaleEndAt));
        }

        void requireSaleOpen(Instant now) {
            if (!saleOpen(now)) throw new BizException(409, "GENESIS_PRESALE_NOT_OPEN");
        }

        BigDecimal purchasePrice(Instant now, BigDecimal regularUnitPrice) {
            requireSaleOpen(now);
            return presaleEnabled ? presaleUnitPrice : regularUnitPrice;
        }

        Map<String, Object> publicView(Instant now) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("eligibilityEnabled", eligibilityEnabled);
            view.put("maxPerUser", effectiveMaxPerUser());
            view.put("minAccountAgeDays", eligibilityEnabled ? minAccountAgeDays : 0);
            view.put("presaleEnabled", presaleEnabled);
            view.put("showCountdown", presaleEnabled && showCountdown);
            view.put("unitPriceUsdt", presaleUnitPrice);
            view.put("startAt", presaleStartAt);
            view.put("endAt", presaleEndAt);
            view.put("open", saleOpen(now));
            view.put("serverCanonical", true);
            return view;
        }
    }

    public record PurchaseRequest(Integer quantity) {}
    public record ListingRequest(BigDecimal askPriceUsdt) {}
}
