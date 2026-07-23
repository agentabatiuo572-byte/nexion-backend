package ffdd.opsconsole.market.application;

import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.market.mapper.AppRepurchaseMapper;
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
import java.time.LocalDateTime;
import java.util.Arrays;
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

/** Atomic, idempotent and server-authoritative G7 repurchase aggregate. */
@Service
@RequiredArgsConstructor
public class AppRepurchaseService {
    private static final String PRODUCT_CODE = "REPURCHASE_90D";
    private static final String G4_CAPACITY_KEY = "G.genesis.lottery.monthlyCapacity";
    private static final String G3_NEX_PRICE_KEY = "wallet.exchange.nex_usdt_price";
    private static final String STAKING_KILLSWITCH_KEY = "killswitch.staking";
    private static final String STAKING_LEGACY_KILLSWITCH_KEY = "J.killswitch.staking";
    private static final String STAKING_DISCLOSURE_GATE_KEY = "disclosure.gate.staking";

    private final AppRepurchaseMapper mapper;
    private final RiskDisclosureGateFacade disclosureGate;
    private final PlatformConfigFacade config;
    private final GrowthRhythmFacade growthRhythmFacade;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final Clock clock;

    public ApiResult<Map<String, Object>> config() {
        AppRepurchaseMapper.ProductRow product = requireProduct(mapper.product());
        return ApiResult.ok(configView(product));
    }

    @Transactional
    public ApiResult<Map<String, Object>> orders(Long userId) {
        requireUser(userId);
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        mapper.matureDue(userId, LocalDateTime.now(clock));
        return response(userId, null, null, null, null, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> open(Long userId, String key, OpenRequest request) {
        requireUser(userId);
        BigDecimal amount = request == null ? null : request.amountUsdt();
        if (amount == null || amount.signum() <= 0) throw new BizException(422, "REPURCHASE_AMOUNT_INVALID");
        OpenRequest normalized = new OpenRequest(money(amount));
        if (!globalGateEnabled()) throw new BizException(409, "REPURCHASE_GLOBAL_GATE_DISABLED");
        ApiResult<Void> userDisclosureGate = disclosureGate.checkUserGate(userId, "staking", key);
        if (userDisclosureGate.getCode() != 0) {
            return ApiResult.fail(userDisclosureGate.getCode(), userDisclosureGate.getMessage());
        }
        return once("OPEN", userId, key, normalized, () -> openInternal(userId, key, normalized));
    }

    private ApiResult<Map<String, Object>> openInternal(Long userId, String key, OpenRequest request) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppRepurchaseMapper.ProductRow product = requireProduct(mapper.lockProduct());
        if (!"ACTIVE".equals(product.status())) throw new BizException(409, "REPURCHASE_PRODUCT_STOPPED");
        if (!globalGateEnabled()) throw new BizException(409, "REPURCHASE_GLOBAL_GATE_DISABLED");
        if (request.amountUsdt().compareTo(money(product.minAmount())) < 0) {
            throw new BizException(422, "REPURCHASE_MIN_AMOUNT_NOT_MET");
        }
        long issued = mapper.issuedTicketsThisMonth();
        long tickets = Math.max(0, product.ticketPerOrder() == null ? 0 : product.ticketPerOrder());
        long capacity = configLong(G4_CAPACITY_KEY, 100_000L, 0L, 100_000_000L);
        if (issued + tickets > capacity) throw new BizException(422, "G4_LOTTERY_CAPACITY_EXCEEDED");
        BigDecimal before = mapper.lockWallet(userId);
        if (before == null || before.compareTo(request.amountUsdt()) < 0) {
            throw new BizException(409, "REPURCHASE_WALLET_INSUFFICIENT");
        }
        if (mapper.debitWallet(userId, request.amountUsdt()) != 1) {
            throw new BizException(409, "REPURCHASE_WALLET_CONFLICT");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String orderNo = "RPS-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        BigDecimal h1ReinvestMultiplier = phaseReinvestMultiplier();
        BigDecimal effectiveNurtureMultiplier = decimal(product.rewardMultiplier()).multiply(h1ReinvestMultiplier)
                .stripTrailingZeros();
        BigDecimal interest = request.amountUsdt().multiply(pct(product.apyBps()))
                .multiply(BigDecimal.valueOf(product.termDays()))
                .divide(BigDecimal.valueOf(36_500), 6, RoundingMode.HALF_UP);
        AppRepurchaseMapper.PositionWrite write = new AppRepurchaseMapper.PositionWrite(
                userId, orderNo, product.id(), PRODUCT_CODE, product.productName(), request.amountUsdt(),
                product.apyBps(), product.earlyPenaltyBps(), product.termDays(), now,
                now.plusDays(product.termDays()), interest);
        if (mapper.insertPosition(write) != 1) throw new BizException(409, "REPURCHASE_ORDER_CONFLICT");
        BigDecimal after = money(before.subtract(request.amountUsdt()));
        String billNo = orderNo + "-OPEN";
        requireLedger(new AppRepurchaseMapper.LedgerWrite(
                userId, billNo, "WALLET_REINVEST", "OUT", request.amountUsdt(), after,
                "G7 repurchase principal lock"));
        if (tickets > 0 && mapper.insertTicket(new AppRepurchaseMapper.TicketWrite(
                "GT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),
                userId, orderNo, Math.toIntExact(tickets), now)) != 1) {
            throw new BizException(409, "G4_LOTTERY_TICKET_CONFLICT");
        }
        AppRepurchaseMapper.UserAttribution at = requireAttribution(userId);
        Map<String, Object> payload = linked(
                "positionNo", orderNo, "product", "repurchase", "amountUsdt", request.amountUsdt(),
                "apyPct", pct(product.apyBps()), "termDays", product.termDays(),
                "unlockAt", write.unlockAt(), "ticketCount", tickets,
                "nurtureMultiplier", product.rewardMultiplier(),
                "h1ReinvestMultiplier", h1ReinvestMultiplier,
                "effectiveNurtureMultiplier", effectiveNurtureMultiplier,
                "walletBalanceUsdt", after);
        String reinvestReceipt = publish(at, userId, orderNo, "wallet.reinvest", payload);
        publish(at, userId, orderNo, "staking.opened", linked(
                "positionNo", orderNo, "tierKey", "repurchase", "productCode", PRODUCT_CODE,
                "amountUsdt", request.amountUsdt(), "apyPct", pct(product.apyBps()),
                "termDays", product.termDays(), "unlockAt", write.unlockAt(),
                "h1ReinvestMultiplier", h1ReinvestMultiplier,
                "effectiveNurtureMultiplier", effectiveNurtureMultiplier,
                "walletBalanceUsdt", after));
        recordAudit("USER_WALLET_REINVESTED", orderNo, billNo, userId, key, payload, "/api/repurchase/orders");
        return response(userId, orderNo, billNo, reinvestReceipt, null, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> claim(Long userId, String orderNo, String key) {
        requireUser(userId);
        String order = normalizeOrder(orderNo);
        return once("CLAIM:" + order, userId, key, order, () -> claimInternal(userId, order, key));
    }

    private ApiResult<Map<String, Object>> claimInternal(Long userId, String orderNo, String key) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppRepurchaseMapper.PositionRow row = requirePosition(userId, orderNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!("ACTIVE".equals(row.status()) || "MATURE_UNCLAIMED".equals(row.status()))
                || now.isBefore(row.unlockAt())) throw new BizException(409, "REPURCHASE_NOT_CLAIMABLE");
        BigDecimal before = requireWallet(userId);
        if (mapper.markClaimed(row.id(), userId, now) != 1) throw new BizException(409, "REPURCHASE_STATE_CONFLICT");
        BigDecimal credited = money(row.amountUsdt().add(row.interestUsdt()));
        if (mapper.creditWallet(userId, credited) != 1) throw new BizException(409, "REPURCHASE_WALLET_CONFLICT");
        BigDecimal after = money(before.add(credited));
        String billNo = orderNo + "-CLAIM";
        requireLedger(new AppRepurchaseMapper.LedgerWrite(userId, billNo, "REPURCHASE_CLAIM", "IN", credited,
                after, "G7 matured principal and interest"));
        AppRepurchaseMapper.UserAttribution at = requireAttribution(userId);
        Map<String, Object> payload = linked("positionNo", orderNo,
                "principalUsdt", row.amountUsdt(), "interestUsdt", row.interestUsdt(),
                "creditedUsdt", credited, "walletBalanceUsdt", after);
        String receipt = publish(at, userId, orderNo, "staking.claimed", payload);
        recordAudit("USER_REPURCHASE_CLAIMED", orderNo, billNo, userId, key, payload,
                "/api/repurchase/orders/" + orderNo + "/claim");
        return response(userId, orderNo, billNo, receipt, credited, null);
    }

    @Transactional
    public ApiResult<Map<String, Object>> earlyWithdraw(Long userId, String orderNo, String key) {
        requireUser(userId);
        String order = normalizeOrder(orderNo);
        return once("EARLY:" + order, userId, key, order, () -> earlyInternal(userId, order, key));
    }

    private ApiResult<Map<String, Object>> earlyInternal(Long userId, String orderNo, String key) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppRepurchaseMapper.PositionRow row = requirePosition(userId, orderNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!"ACTIVE".equals(row.status()) || !now.isBefore(row.unlockAt())) {
            throw new BizException(409, "REPURCHASE_NOT_EARLY_WITHDRAWABLE");
        }
        BigDecimal before = requireWallet(userId);
        if (mapper.markEarly(row.id(), userId, now) != 1) throw new BizException(409, "REPURCHASE_STATE_CONFLICT");
        mapper.forfeitTickets(userId, orderNo, now);
        BigDecimal penalty = money(row.amountUsdt().multiply(pct(row.earlyPenaltyBps()))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        BigDecimal credited = money(row.amountUsdt().subtract(penalty));
        if (mapper.creditWallet(userId, credited) != 1) throw new BizException(409, "REPURCHASE_WALLET_CONFLICT");
        BigDecimal after = money(before.add(credited));
        String billNo = orderNo + "-EARLY";
        requireLedger(new AppRepurchaseMapper.LedgerWrite(userId, billNo, "REPURCHASE_EARLY_WITHDRAW", "IN",
                credited, after, "G7 early withdrawal net principal; interest and tickets forfeited"));
        AppRepurchaseMapper.UserAttribution at = requireAttribution(userId);
        Map<String, Object> payload = linked("positionNo", orderNo,
                "principalUsdt", row.amountUsdt(), "penaltyUsdt", penalty, "creditedUsdt", credited,
                "forfeitedInterestUsdt", row.interestUsdt(),
                "walletBalanceUsdt", after);
        String receipt = publish(at, userId, orderNo, "staking.early_withdrawn", payload);
        recordAudit("USER_REPURCHASE_EARLY_WITHDRAWN", orderNo, billNo, userId, key, payload,
                "/api/repurchase/orders/" + orderNo + "/early-withdraw");
        return response(userId, orderNo, billNo, receipt, credited, penalty);
    }

    private Map<String, Object> configView(AppRepurchaseMapper.ProductRow p) {
        return linked("product", "repurchase", "asset", p.asset(), "apyPct", pct(p.apyBps()),
                "lockDays", p.termDays(), "nurtureMultiplier", decimal(p.rewardMultiplier()),
                "h1ReinvestMultiplier", phaseReinvestMultiplier(),
                "effectiveNurtureMultiplier", decimal(p.rewardMultiplier()).multiply(phaseReinvestMultiplier()).stripTrailingZeros(),
                "ticketPerOrder", Math.max(0, p.ticketPerOrder() == null ? 0 : p.ticketPerOrder()),
                "presets", presets(p.presetAmounts()), "earlyPenaltyPct", pct(p.earlyPenaltyBps()),
                "minAmountUsdt", money(p.minAmount()), "enabled", "ACTIVE".equals(p.status()) && globalGateEnabled(),
                "disclosureRequired", disclosureRequired(),
                "currentNexPriceUsdt", currentNexPrice(),
                "g4LotteryCapacity", configLong(G4_CAPACITY_KEY, 100_000L, 0, 100_000_000L),
                "g4TicketsIssuedThisMonth", mapper.issuedTicketsThisMonth(), "serverCanonical", true,
                "pointsReward", false);
    }

    private BigDecimal currentNexPrice() {
        BigDecimal price = mapper.latestNexUsdtPrice();
        if (price != null && price.signum() > 0) return price.stripTrailingZeros();
        BigDecimal fallback = configDecimal(G3_NEX_PRICE_KEY, BigDecimal.ZERO);
        if (fallback.signum() <= 0) throw new BizException(503, "G3_NEX_PRICE_UNAVAILABLE");
        return fallback.stripTrailingZeros();
    }

    private BigDecimal phaseReinvestMultiplier() {
        GrowthRhythmSnapshot rhythm = growthRhythmFacade.snapshot();
        if (rhythm == null || rhythm.currentMonth() < 1 || rhythm.currentMonth() > rhythm.totalMonths()) {
            throw new BizException(503, "H1_REINVEST_MONTH_UNAVAILABLE");
        }
        BigDecimal value = rhythm.reinvestMultiplier();
        if (value == null || value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.TEN) > 0) {
            throw new BizException(503, "H1_REINVEST_MULTIPLIER_UNAVAILABLE");
        }
        return value.stripTrailingZeros();
    }

    private boolean globalGateEnabled() {
        return KillSwitchState.enabled(java.util.Optional.ofNullable(mapper.controlValue(STAKING_KILLSWITCH_KEY)),
                java.util.Optional.ofNullable(mapper.controlValue(STAKING_LEGACY_KILLSWITCH_KEY)));
    }

    private boolean disclosureRequired() {
        return config.activeValue(STAKING_DISCLOSURE_GATE_KEY)
                .map(raw -> List.of("enabled", "enable", "on", "true", "1", "blocked", "required")
                        .contains(raw.trim().toLowerCase(Locale.ROOT)))
                .orElse(false);
    }

    private ApiResult<Map<String, Object>> response(Long userId, String focus, String billNo, String receipt,
                                                     BigDecimal credited, BigDecimal penalty) {
        mapper.matureDue(userId, LocalDateTime.now(clock));
        Map<String, Object> data = linked("orders", mapper.positions(userId).stream().map(this::positionView).toList(),
                "walletBalanceUsdt", money(mapper.wallet(userId)), "serverTime", LocalDateTime.now(clock),
                "serverCanonical", true);
        if (focus != null) data.put("focusOrderNo", focus);
        if (billNo != null) data.put("billNo", billNo);
        if (receipt != null) data.put("receiptId", receipt);
        if (credited != null) data.put("creditedUsdt", money(credited));
        if (penalty != null) data.put("penaltyUsdt", money(penalty));
        return ApiResult.ok(data);
    }

    private Map<String, Object> positionView(AppRepurchaseMapper.PositionRow p) {
        return linked("orderNo", p.positionNo(), "amountUsdt", money(p.amountUsdt()), "apyPct", pct(p.apyBps()),
                "earlyPenaltyPct", pct(p.earlyPenaltyBps()), "lockDays", p.termDays(), "lockedAt", p.lockedAt(),
                "unlockAt", p.unlockAt(), "estimatedInterestUsdt", money(p.interestUsdt()), "status", p.status());
    }

    private AppRepurchaseMapper.ProductRow requireProduct(AppRepurchaseMapper.ProductRow p) {
        if (p == null || !"USDT".equalsIgnoreCase(p.asset()) || p.termDays() == null || p.termDays() < 1
                || pct(p.apyBps()).compareTo(BigDecimal.valueOf(300)) > 0
                || pct(p.earlyPenaltyBps()).compareTo(BigDecimal.valueOf(100)) > 0
                || decimal(p.rewardMultiplier()).compareTo(BigDecimal.ONE) < 0) {
            throw new BizException(503, "REPURCHASE_CONFIG_INVALID");
        }
        presets(p.presetAmounts());
        return p;
    }

    private List<BigDecimal> presets(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException(503, "REPURCHASE_PRESETS_INVALID");
        try {
            List<BigDecimal> values = Arrays.stream(raw.replace("$", "").replace(",", "")
                            .split("[/;|]"))
                    .map(String::trim).filter(StringUtils::hasText).map(BigDecimal::new)
                    .map(this::money).distinct().sorted().toList();
            if (values.isEmpty() || values.stream().anyMatch(v -> v.signum() <= 0)) throw new IllegalArgumentException();
            return values;
        } catch (RuntimeException ex) {
            throw new BizException(503, "REPURCHASE_PRESETS_INVALID");
        }
    }

    private void requireLedger(AppRepurchaseMapper.LedgerWrite row) {
        if (mapper.insertLedger(row) != 1) throw new BizException(409, "REPURCHASE_LEDGER_CONFLICT");
    }

    private AppRepurchaseMapper.PositionRow requirePosition(Long userId, String orderNo) {
        AppRepurchaseMapper.PositionRow row = mapper.lockPosition(userId, orderNo);
        if (row == null) throw new BizException(404, "REPURCHASE_ORDER_NOT_FOUND");
        return row;
    }

    private BigDecimal requireWallet(Long userId) {
        BigDecimal value = mapper.lockWallet(userId);
        if (value == null) throw new BizException(409, "REPURCHASE_WALLET_NOT_FOUND");
        return money(value);
    }

    private AppRepurchaseMapper.UserAttribution requireAttribution(Long userId) {
        AppRepurchaseMapper.UserAttribution at = mapper.attribution(userId);
        if (at == null || at.accountAgeMonths() == null || !StringUtils.hasText(at.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        return at;
    }

    private String publish(AppRepurchaseMapper.UserAttribution at, Long userId, String orderNo,
                           String event, Map<String, Object> payload) {
        return outbox.publishUserEvent("REPURCHASE_ORDER", orderNo, event, userId, phase(at.phase()),
                at.accountAgeMonths(), at.cohort(), payload);
    }

    private void recordAudit(String action, String orderNo, String billNo, Long userId, String key,
                             Map<String, Object> detail, String path) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action)
                .resourceType("REPURCHASE_ORDER").resourceId(orderNo).bizNo(billNo).userId(userId).actorId(userId)
                .actorType("USER").actorUsername("user:" + userId).method("POST").path(path).result("SUCCESS")
                .riskLevel("HIGH").detail(linked("idempotencyKey", key == null ? "" : key.trim(), "state", detail))
                .build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> once(String op, Long userId, String key, Object request,
                                                Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:G7_REPURCHASE_" + op + ":USER:" + userId, key, sha256(String.valueOf(request)),
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

    private long configLong(String key, long fallback, long min, long max) {
        BigDecimal value = configDecimal(key, BigDecimal.valueOf(fallback));
        try {
            long result = value.longValueExact();
            if (result < min || result > max) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException ex) {
            throw new BizException(503, "REPURCHASE_CONFIG_INVALID:" + key);
        }
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        String raw = mapper.configValue(key);
        if (!StringUtils.hasText(raw)) return fallback;
        try { return new BigDecimal(raw.trim()); }
        catch (RuntimeException ex) { throw new BizException(503, "REPURCHASE_CONFIG_INVALID:" + key); }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
    }

    private String normalizeOrder(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("RPS-[A-Za-z0-9]{8,80}")) {
            throw new BizException(422, "REPURCHASE_ORDER_NO_INVALID");
        }
        return value.trim();
    }

    private String phase(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "P1";
        if (value.matches("[1-6]")) value = "P" + value;
        return value.matches("P[1-6]") ? value : "P1";
    }

    private BigDecimal pct(BigDecimal bps) {
        return decimal(bps).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }
    private BigDecimal decimal(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private BigDecimal money(BigDecimal v) { return decimal(v).setScale(6, RoundingMode.HALF_UP); }
    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    public record OpenRequest(BigDecimal amountUsdt) {}
}
