package ffdd.opsconsole.market.application;

import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.market.mapper.AppStakingMapper;
import ffdd.opsconsole.market.mapper.MarketSandboxMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Server-authoritative user boundary for the four G1 USDT staking products. */
@Service
public class AppStakingService {
    private static final String STAKING_PREFIX = "G.staking.";
    private static final String STAKING_KILLSWITCH_KEY = "killswitch.staking";
    private static final String STAKING_LEGACY_KILLSWITCH_KEY = "J.killswitch.staking";
    private final AppStakingMapper mapper;
    private final RiskDisclosureGateFacade disclosureGate;
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final EarningsReleaseService earningsReleaseService;
    private final Clock clock;
    private final Environment environment;
    private final MarketSandboxMapper sandboxMapper;

    public AppStakingService(AppStakingMapper mapper, RiskDisclosureGateFacade disclosureGate,
            PlatformConfigFacade config, AdminIdempotencyService idempotency, EventOutboxService outbox,
            AuditLogService audit, EarningsReleaseService earningsReleaseService, Clock clock,
            Environment environment) {
        this(mapper, disclosureGate, config, idempotency, outbox, audit, earningsReleaseService, clock,
                environment, null);
    }

    @Autowired
    public AppStakingService(AppStakingMapper mapper, RiskDisclosureGateFacade disclosureGate,
            PlatformConfigFacade config, AdminIdempotencyService idempotency, EventOutboxService outbox,
            AuditLogService audit, EarningsReleaseService earningsReleaseService, Clock clock,
            Environment environment, MarketSandboxMapper sandboxMapper) {
        this.mapper = mapper;
        this.disclosureGate = disclosureGate;
        this.config = config;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.earningsReleaseService = earningsReleaseService;
        this.clock = clock;
        this.environment = environment;
        this.sandboxMapper = sandboxMapper;
    }

    public ApiResult<Map<String, Object>> pools() {
        if (isSandbox() && !isDevelopmentRuntime()) return ApiResult.ok(linked("pools", sandboxPools(), "serverCanonical", true,
                "source", "mock", "sourceEnvironment", "SANDBOX", "runId", sandboxRunId()));
        requireCanonicalPoolRuntime();
        List<Map<String, Object>> rows = mapper.listCanonicalProducts().stream().map(this::poolView).toList();
        return ApiResult.ok(linked(
                "pools", rows,
                "serverCanonical", true,
                "source", "nx_staking_product + nx_config_item + nx_emergency_control_setting",
                "sourceEnvironment", "PRODUCTION",
                "runId", ""));
    }

    @Transactional
    public ApiResult<Map<String, Object>> positions(Long userId) {
        return positions(userId, 1, 100);
    }

    @Transactional
    public ApiResult<Map<String, Object>> positions(Long userId, int requestedPageNum, int requestedPageSize) {
        if (isSandbox()) return sandboxPositions(userId, requestedPageNum, requestedPageSize);
        requireCanonicalProductionRuntime();
        requireUser(userId);
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        LocalDateTime now = LocalDateTime.now(clock);
        mapper.matureDuePositions(userId, now);
        return positionsResponse(userId, null, null, null, null, null, null,
                requestedPageNum, requestedPageSize);
    }

    @Transactional
    public ApiResult<Map<String, Object>> open(Long userId, String idempotencyKey, OpenRequest request) {
        if (isSandbox()) return sandboxOpen(userId, idempotencyKey, request);
        requireCanonicalProductionRuntime();
        requireUser(userId);
        String tierKey = normalizeTier(request == null ? null : request.tierKey());
        BigDecimal requestedAmount = request == null ? null : request.amountUsdt();
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new BizException(422, "STAKING_AMOUNT_INVALID");
        }
        BigDecimal amount = money(requestedAmount);
        OpenRequest normalized = new OpenRequest(tierKey, amount);
        ApiResult<Void> disclosure = disclosureGate.checkUserGate(userId, "staking", idempotencyKey);
        if (disclosure.getCode() != 0) {
            return ApiResult.fail(disclosure.getCode(), disclosure.getMessage());
        }
        return executeOnce("OPEN", userId, idempotencyKey, normalized,
                () -> openInternal(userId, idempotencyKey, normalized));
    }

    private ApiResult<Map<String, Object>> openInternal(Long userId, String idempotencyKey, OpenRequest request) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppStakingMapper.ProductRow product = mapper.lockProductByTier(request.tierKey());
        if (product == null || !"USDT".equalsIgnoreCase(product.asset())
                || !"ACTIVE".equalsIgnoreCase(product.status())) {
            throw new BizException(409, "STAKING_POOL_NOT_ACTIVE");
        }
        PoolPolicy policy = policy(product);
        if (policy.killed()) throw new BizException(409, "STAKING_POOL_KILLED");
        if (!policy.enabled()) throw new BizException(409, "STAKING_POOL_STOPPED");
        if (!globalGateEnabled()) throw new BizException(409, "STAKING_GLOBAL_GATE_DISABLED");
        if (request.amountUsdt().compareTo(policy.minAmount()) < 0) {
            throw new BizException(422, "STAKING_MIN_AMOUNT_NOT_MET");
        }
        BigDecimal balance = mapper.lockWalletBalance(userId);
        if (balance == null || balance.compareTo(request.amountUsdt()) < 0) {
            throw new BizException(409, "STAKING_WALLET_INSUFFICIENT");
        }
        if (mapper.debitWallet(userId, request.amountUsdt()) != 1) {
            throw new BizException(409, "STAKING_WALLET_CONFLICT");
        }

        LocalDateTime lockedAt = LocalDateTime.now(clock);
        LocalDateTime unlockAt = lockedAt.plusDays(product.termDays());
        String positionNo = "STK-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        BigDecimal interest = request.amountUsdt().multiply(policy.apyPct())
                .multiply(BigDecimal.valueOf(product.termDays()))
                .divide(BigDecimal.valueOf(36_500), 6, RoundingMode.HALF_UP);
        AppStakingMapper.PositionWrite write = new AppStakingMapper.PositionWrite(
                userId, positionNo, product.id(), product.productCode(), product.productName(), request.amountUsdt(),
                policy.apyPct().multiply(BigDecimal.valueOf(100)),
                policy.penaltyPct().multiply(BigDecimal.valueOf(100)), product.termDays(), lockedAt, unlockAt, interest);
        if (mapper.insertPosition(write) != 1) throw new BizException(409, "STAKING_POSITION_CONFLICT");
        AppStakingMapper.PositionRow created = new AppStakingMapper.PositionRow(
                null, userId, positionNo, product.id(), product.productCode(), product.productName(),
                request.amountUsdt(), write.apyBps(), write.earlyPenaltyBps(), product.termDays(),
                lockedAt, unlockAt, interest, "ACTIVE", null, null);
        BigDecimal balanceAfter = money(balance.subtract(request.amountUsdt()));
        String billNo = positionNo + "-OPEN";
        if (mapper.insertLedger(new AppStakingMapper.LedgerWrite(
                userId, billNo, "STAKING_OPEN", "OUT", request.amountUsdt(), balanceAfter,
                "G1 staking principal lock")) != 1) {
            throw new BizException(409, "STAKING_LEDGER_CONFLICT");
        }
        AppStakingMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "positionNo", positionNo, "tierKey", request.tierKey(), "productCode", product.productCode(),
                "amountUsdt", request.amountUsdt(), "apyPct", policy.apyPct(),
                "termDays", product.termDays(), "unlockAt", unlockAt, "walletBalanceUsdt", balanceAfter);
        String receiptId = outbox.publishUserEvent(
                "STAKING_POSITION", positionNo, "staking.opened", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit("USER_STAKING_OPENED", positionNo, billNo, userId, idempotencyKey, event, "HIGH",
                "/api/stakes");
        return positionsResponse(userId, created, request.amountUsdt(), null, null,
                billNo, receiptId);
    }

    @Transactional
    public ApiResult<Map<String, Object>> claim(Long userId, String positionNo, String idempotencyKey) {
        if (isSandbox()) return sandboxClaim(userId, positionNo, idempotencyKey);
        requireCanonicalProductionRuntime();
        requireUser(userId);
        String normalizedPosition = normalizePosition(positionNo);
        return executeOnce("CLAIM:" + normalizedPosition, userId, idempotencyKey, normalizedPosition,
                () -> claimInternal(userId, normalizedPosition, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> claimInternal(Long userId, String positionNo, String idempotencyKey) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppStakingMapper.PositionRow position = requirePosition(userId, positionNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!("ACTIVE".equals(position.status()) || "MATURE_UNCLAIMED".equals(position.status()))
                || position.unlockAt() == null || now.isBefore(position.unlockAt())) {
            throw new BizException(409, "STAKING_POSITION_NOT_CLAIMABLE");
        }
        BigDecimal balance = requireWallet(userId);
        if (mapper.markClaimed(position.id(), userId, now) != 1) {
            throw new BizException(409, "STAKING_POSITION_STATE_CONFLICT");
        }
        BigDecimal interest = money(position.estimatedInterestUsdt());
        BigDecimal principal = money(position.amountUsdt());
        BigDecimal credited = money(principal.add(interest));
        if (mapper.creditWallet(userId, principal) != 1) throw new BizException(409, "STAKING_WALLET_CONFLICT");
        if (interest.signum() > 0) {
            earningsReleaseService.creditReward(userId, "staking_interest", positionNo, "USDT", interest,
                    "G1-STAKING-INTEREST-" + position.id());
        }
        BigDecimal balanceAfter = money(balance.add(credited));
        String billNo = positionNo + "-CLAIM";
        if (mapper.insertLedger(new AppStakingMapper.LedgerWrite(
                userId, billNo, "STAKING_CLAIM", "IN", credited, balanceAfter,
                "G1 staking matured principal and interest")) != 1) {
            throw new BizException(409, "STAKING_LEDGER_CONFLICT");
        }
        AppStakingMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "positionNo", positionNo, "principalUsdt", money(position.amountUsdt()),
                "interestUsdt", interest, "creditedUsdt", credited, "walletBalanceUsdt", balanceAfter);
        String receiptId = outbox.publishUserEvent(
                "STAKING_POSITION", positionNo, "staking.claimed", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit("USER_STAKING_CLAIMED", positionNo, billNo, userId, idempotencyKey, event, "HIGH",
                "/api/stakes/" + positionNo + "/claim");
        return positionsResponse(userId, findPosition(userId, positionNo), position.amountUsdt(), interest, null,
                billNo, receiptId);
    }

    @Transactional
    public ApiResult<Map<String, Object>> earlyWithdraw(Long userId, String positionNo, String idempotencyKey) {
        if (isSandbox()) return sandboxEarlyWithdraw(userId, positionNo, idempotencyKey);
        requireCanonicalProductionRuntime();
        requireUser(userId);
        String normalizedPosition = normalizePosition(positionNo);
        return executeOnce("EARLY_WITHDRAW:" + normalizedPosition, userId, idempotencyKey, normalizedPosition,
                () -> earlyWithdrawInternal(userId, normalizedPosition, idempotencyKey));
    }

    private ApiResult<Map<String, Object>> earlyWithdrawInternal(
            Long userId, String positionNo, String idempotencyKey) {
        if (mapper.lockActiveUser(userId) == null) throw new BizException(404, "USER_NOT_FOUND");
        AppStakingMapper.PositionRow position = requirePosition(userId, positionNo);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!"ACTIVE".equals(position.status()) || position.unlockAt() == null || !now.isBefore(position.unlockAt())) {
            throw new BizException(409, "STAKING_POSITION_NOT_EARLY_WITHDRAWABLE");
        }
        BigDecimal balance = requireWallet(userId);
        if (mapper.markEarlyWithdrawn(position.id(), userId, now) != 1) {
            throw new BizException(409, "STAKING_POSITION_STATE_CONFLICT");
        }
        BigDecimal penaltyPct = nz(position.earlyPenaltyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal penalty = position.amountUsdt().multiply(penaltyPct)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        BigDecimal credited = money(position.amountUsdt().subtract(penalty));
        if (mapper.creditWallet(userId, credited) != 1) throw new BizException(409, "STAKING_WALLET_CONFLICT");
        BigDecimal balanceAfter = money(balance.add(credited));
        String billNo = positionNo + "-EARLY";
        if (mapper.insertLedger(new AppStakingMapper.LedgerWrite(
                userId, billNo, "STAKING_EARLY_WITHDRAW", "IN", credited, balanceAfter,
                "G1 early withdrawal net principal; unearned interest forfeited")) != 1) {
            throw new BizException(409, "STAKING_LEDGER_CONFLICT");
        }
        AppStakingMapper.UserAttribution attribution = requireAttribution(userId);
        Map<String, Object> event = linked(
                "positionNo", positionNo, "principalUsdt", money(position.amountUsdt()),
                "penaltyUsdt", penalty, "creditedUsdt", credited, "forfeitedInterestUsdt", money(position.estimatedInterestUsdt()),
                "walletBalanceUsdt", balanceAfter);
        String receiptId = outbox.publishUserEvent(
                "STAKING_POSITION", positionNo, "staking.early_withdrawn", userId, normalizePhase(attribution.phase()),
                attribution.accountAgeMonths(), attribution.cohort(), event);
        recordUserAudit("USER_STAKING_EARLY_WITHDRAWN", positionNo, billNo, userId, idempotencyKey, event, "HIGH",
                "/api/stakes/" + positionNo + "/early-withdraw");
        return positionsResponse(userId, findPosition(userId, positionNo), position.amountUsdt(), null, penalty,
                billNo, receiptId);
    }

    private Map<String, Object> poolView(AppStakingMapper.ProductRow product) {
        PoolPolicy policy = policy(product);
        return linked(
                "poolId", product.id(), "tierKey", tierKey(product.productCode()), "currency", "USDT",
                "termDays", product.termDays(), "apyPct", policy.apyPct(), "penaltyPct", policy.penaltyPct(),
                "minAmountUsdt", policy.minAmount(), "enabled", policy.enabled(), "killed", policy.killed(),
                "status", policy.killed() ? "KILLED" : policy.enabled() ? "ACTIVE" : "STOPPED");
    }

    private PoolPolicy policy(AppStakingMapper.ProductRow product) {
        String tier = tierKey(product.productCode());
        BigDecimal apyPct = configNumber(STAKING_PREFIX + "apy." + tier,
                nz(product.apyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.valueOf(300));
        BigDecimal penaltyPct = configNumber(STAKING_PREFIX + "penalty." + tier,
                nz(product.earlyPenaltyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP), BigDecimal.ZERO, BigDecimal.valueOf(100));
        BigDecimal minAmount = configNumber(STAKING_PREFIX + "min." + tier,
                money(product.minAmount()), BigDecimal.ZERO, BigDecimal.valueOf(1_000_000_000));
        boolean killed = config.activeValue(STAKING_PREFIX + tier + ".killed").map(this::switchEnabled).orElse(false);
        boolean enabled = config.activeValue(STAKING_PREFIX + "enabled." + tier)
                .map(this::switchEnabled).orElse("ACTIVE".equalsIgnoreCase(product.status()));
        return new PoolPolicy(apyPct, penaltyPct, minAmount, enabled && !killed && globalGateEnabled(), killed);
    }

    private boolean isSandbox() {
        return UserAuthEnvironment.resolve(environment).orElse(null) == UserAuthEnvironment.SANDBOX;
    }

    private boolean isDevelopmentRuntime() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 1 && "dev".equalsIgnoreCase(profiles[0]);
    }

    private String sandboxRunId() {
        String runId = environment.getProperty("nexion.commerce.acceptance-run-id",
                environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", ""));
        if (!StringUtils.hasText(runId) || !runId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) {
            throw new BizException(503, "STAKING_SANDBOX_RUN_ID_REQUIRED");
        }
        return runId.trim();
    }

    private ApiResult<Map<String, Object>> sandboxPositions(Long userId, int requestedPageNum, int requestedPageSize) {
        requireUser(userId);
        String runId = sandboxRunId();
        requireSandboxMapper();
        sandboxMapper.insertAccountIfAbsent("staking", runId, userId);
        sandboxMapper.maturePositions("staking", runId, userId, LocalDateTime.now(clock));
        return ApiResult.ok(sandboxResponse(runId, userId, null, null, null, null,
                requestedPageNum, requestedPageSize));
    }

    private ApiResult<Map<String, Object>> sandboxOpen(Long userId, String key, OpenRequest request) {
        requireUser(userId);
        String runId = sandboxRunId();
        if (request == null || request.amountUsdt() == null || request.amountUsdt().signum() <= 0) {
            throw new BizException(422, "STAKING_AMOUNT_INVALID");
        }
        String tier = normalizeTier(request.tierKey());
        BigDecimal amount = money(request.amountUsdt());
        String hash = sha256("OPEN|" + tier + "|" + amount.toPlainString());
        String positionNo = "STK-SBX-" + sha256(runId + "|" + userId + "|" + key).substring(0, 24).toUpperCase(Locale.ROOT);
        MarketSandboxMapper.IdempotencyRow replay = beginSandboxCommand(runId, userId, "OPEN", key, hash, positionNo);
        if (replay != null) return ApiResult.ok(sandboxResponse(runId, userId, replay.resourceNo(), null, null, null));
        sandboxMapper.insertAccountIfAbsent("staking", runId, userId);
        MarketSandboxMapper.AccountRow account = sandboxMapper.lockAccount("staking", runId, userId);
        if (account == null) throw new BizException(503, "STAKING_SANDBOX_ACCOUNT_UNAVAILABLE");
        SandboxProduct product = sandboxProduct(tier);
        if (!product.enabled()) throw new BizException(409, "STAKING_POOL_STOPPED");
        if (amount.compareTo(product.minAmount()) < 0) throw new BizException(422, "STAKING_MIN_AMOUNT_NOT_MET");
        if (account.walletUsdt().compareTo(amount) < 0) throw new BizException(409, "STAKING_WALLET_INSUFFICIENT");
        LocalDateTime lockedAt = LocalDateTime.now(clock);
        LocalDateTime unlockAt = lockedAt.plusDays(product.termDays());
        BigDecimal interest = amount.multiply(product.apyPct()).multiply(BigDecimal.valueOf(product.termDays()))
                .divide(BigDecimal.valueOf(36_500), 6, RoundingMode.HALF_UP);
        if (sandboxMapper.updateWallet("staking", runId, userId, account.version(), amount.negate()) != 1) {
            throw new BizException(409, "STAKING_SANDBOX_WALLET_CONFLICT");
        }
        if (sandboxMapper.insertPosition(new MarketSandboxMapper.PositionWrite("staking", runId, userId,
                positionNo, product.code(), product.name(), amount, product.apyPct(), product.penaltyPct(),
                product.termDays(), lockedAt, unlockAt, money(interest))) != 1) {
            throw new BizException(409, "STAKING_SANDBOX_POSITION_CONFLICT");
        }
        Map<String, Object> data = sandboxResponse(runId, userId, positionNo, amount, null, null);
        data.put("principalUsdt", amount);
        return ApiResult.ok(data);
    }

    private ApiResult<Map<String, Object>> sandboxClaim(Long userId, String positionNo, String key) {
        requireUser(userId);
        String runId = sandboxRunId();
        String normalized = normalizePosition(positionNo);
        String hash = sha256("CLAIM|" + normalized);
        MarketSandboxMapper.IdempotencyRow replay = beginSandboxCommand(runId, userId, "CLAIM", key, hash, normalized);
        if (replay != null) return ApiResult.ok(sandboxResponse(runId, userId, replay.resourceNo(), null, null, null));
        sandboxMapper.insertAccountIfAbsent("staking", runId, userId);
        MarketSandboxMapper.AccountRow account = sandboxMapper.lockAccount("staking", runId, userId);
        sandboxMapper.maturePositions("staking", runId, userId, LocalDateTime.now(clock));
        MarketSandboxMapper.PositionRow position = sandboxMapper.lockPosition("staking", runId, userId, normalized);
            if (position == null) throw new BizException(404, "STAKING_POSITION_NOT_FOUND");
            if (!"MATURE_UNCLAIMED".equals(position.status())) throw new BizException(409, "STAKING_POSITION_NOT_CLAIMABLE");
            BigDecimal credited = money(position.amountUsdt().add(position.interestUsdt()));
        if (sandboxMapper.transitionPosition(position.id(), "staking", runId, userId, position.version(),
                "MATURE_UNCLAIMED", "CLAIMED") != 1) throw new BizException(409, "STAKING_SANDBOX_STATE_CONFLICT");
        if (account == null || sandboxMapper.updateWallet("staking", runId, userId, account.version(), credited) != 1) {
            throw new BizException(409, "STAKING_SANDBOX_WALLET_CONFLICT");
        }
        Map<String, Object> data = sandboxResponse(runId, userId, normalized, null, credited, position.interestUsdt());
        return ApiResult.ok(data);
    }

    private ApiResult<Map<String, Object>> sandboxEarlyWithdraw(Long userId, String positionNo, String key) {
        requireUser(userId);
        String runId = sandboxRunId();
        String normalized = normalizePosition(positionNo);
        String hash = sha256("EARLY|" + normalized);
        MarketSandboxMapper.IdempotencyRow replay = beginSandboxCommand(runId, userId, "EARLY_WITHDRAW", key, hash, normalized);
        if (replay != null) return ApiResult.ok(sandboxResponse(runId, userId, replay.resourceNo(), null, null, null));
        sandboxMapper.insertAccountIfAbsent("staking", runId, userId);
        MarketSandboxMapper.AccountRow account = sandboxMapper.lockAccount("staking", runId, userId);
        MarketSandboxMapper.PositionRow position = sandboxMapper.lockPosition("staking", runId, userId, normalized);
            if (position == null) throw new BizException(404, "STAKING_POSITION_NOT_FOUND");
            if (!"ACTIVE".equals(position.status()) || !LocalDateTime.now(clock).isBefore(position.unlockAt())) {
                throw new BizException(409, "STAKING_POSITION_NOT_EARLY_WITHDRAWABLE");
            }
            BigDecimal penalty = money(position.amountUsdt().multiply(position.penaltyPct())
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            BigDecimal credited = money(position.amountUsdt().subtract(penalty));
        if (sandboxMapper.transitionPosition(position.id(), "staking", runId, userId, position.version(),
                "ACTIVE", "EARLY_WITHDRAWN") != 1) throw new BizException(409, "STAKING_SANDBOX_STATE_CONFLICT");
        if (account == null || sandboxMapper.updateWallet("staking", runId, userId, account.version(), credited) != 1) {
            throw new BizException(409, "STAKING_SANDBOX_WALLET_CONFLICT");
        }
        Map<String, Object> data = sandboxResponse(runId, userId, normalized, null, credited, penalty);
        return ApiResult.ok(data);
    }
    private Map<String, Object> sandboxResponse(String runId, Long userId, String focus, BigDecimal principal,
            BigDecimal credited, BigDecimal detail) {
        return sandboxResponse(runId, userId, focus, principal, credited, detail, 1, 50);
    }

    private Map<String, Object> sandboxResponse(String runId, Long userId, String focus, BigDecimal principal,
            BigDecimal credited, BigDecimal detail, int requestedPageNum, int requestedPageSize) {
        MarketSandboxMapper.AccountRow account = sandboxMapper.account("staking", runId, userId);
        List<MarketSandboxMapper.PositionRow> rows = sandboxMapper.listPositions("staking", runId, userId);
        int pageNum = Math.max(1, requestedPageNum);
        int pageSize = Math.max(1, Math.min(requestedPageSize, 100));
        int from = (int) Math.min((long) (pageNum - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        Map<String, Object> result = linked("positions", rows.subList(from, to).stream().map(this::sandboxPositionView).toList(),
                "positionsPage", linked("total", rows.size(), "pageNum", pageNum, "pageSize", pageSize),
                "walletBalanceUsdt", money(account == null ? null : account.walletUsdt()),
                "serverTime", LocalDateTime.now(clock), "serverCanonical", true, "source", "mock",
                "sourceEnvironment", "SANDBOX", "runId", runId);
        if (focus != null) result.put("positionNo", focus);
        if (focus != null) rows.stream().filter(row -> focus.equals(row.positionNo())).findFirst()
                .ifPresent(row -> result.put("position", sandboxPositionView(row)));
        if (principal != null) result.put("principalUsdt", money(principal));
        if (credited != null) result.put("creditedUsdt", money(credited));
        if (detail != null) result.put("interestUsdt", money(detail));
        return result;
    }

    private Map<String, Object> sandboxPositionView(MarketSandboxMapper.PositionRow row) {
        return linked("positionNo", row.positionNo(), "tierKey", tierKey(row.productCode()),
                "productCode", row.productCode(), "productName", row.productName(), "amountUsdt", money(row.amountUsdt()),
                "apyPct", row.apyPct(), "penaltyPct", row.penaltyPct(), "termDays", row.termDays(),
                "lockedAt", row.lockedAt(), "unlockAt", row.unlockAt(), "estimatedInterestUsdt", money(row.interestUsdt()),
                "status", row.status());
    }

    private MarketSandboxMapper.IdempotencyRow beginSandboxCommand(String runId, Long userId, String operation,
            String key, String hash, String resourceNo) {
        requireSandboxMapper();
        if (!StringUtils.hasText(key)) throw new BizException(422, "MARKET_SANDBOX_IDEMPOTENCY_KEY_REQUIRED");
        MarketSandboxMapper.IdempotencyRow existing = sandboxMapper.findIdempotency("staking", runId, userId, operation, key.trim());
        if (existing != null) {
            if (!hash.equals(existing.requestHash())) throw new BizException(409, "MARKET_SANDBOX_IDEMPOTENCY_CONFLICT");
            return existing;
        }
        if (sandboxMapper.insertIdempotency(new MarketSandboxMapper.IdempotencyWrite("staking", runId, userId,
                operation, key.trim(), hash, resourceNo)) == 1) return null;
        existing = sandboxMapper.findIdempotency("staking", runId, userId, operation, key.trim());
        if (existing == null || !hash.equals(existing.requestHash())) throw new BizException(409, "MARKET_SANDBOX_IDEMPOTENCY_CONFLICT");
        return existing;
    }

    private void requireSandboxMapper() {
        if (sandboxMapper == null) throw new BizException(503, "STAKING_SANDBOX_SCHEMA_UNAVAILABLE");
    }

    private SandboxProduct sandboxProduct(String tier) {
        return switch (tier) {
            case "usdt30d" -> new SandboxProduct("USDT_30D", "USDT Staking 30D", 30, new BigDecimal("12"), new BigDecimal("10"), new BigDecimal("100"), true);
            case "usdt90d" -> new SandboxProduct("USDT_90D", "USDT Staking 90D", 90, new BigDecimal("18"), new BigDecimal("12"), new BigDecimal("100"), true);
            case "usdt180d" -> new SandboxProduct("USDT_180D", "USDT Staking 180D", 180, new BigDecimal("24"), new BigDecimal("15"), new BigDecimal("100"), true);
            case "usdt365d" -> new SandboxProduct("USDT_365D", "USDT Staking 365D", 365, new BigDecimal("30"), new BigDecimal("20"), new BigDecimal("100"), true);
            default -> throw new BizException(422, "STAKING_TIER_INVALID");
        };
    }

    private List<Map<String, Object>> sandboxPools() {
        return List.of("usdt30d", "usdt90d", "usdt180d", "usdt365d").stream().map(tier -> {
            SandboxProduct p = sandboxProduct(tier);
            return linked("poolId", List.of("usdt30d", "usdt90d", "usdt180d", "usdt365d").indexOf(tier) + 1,
                    "tierKey", tier, "currency", "USDT", "termDays", p.termDays(),
                    "apyPct", p.apyPct(), "penaltyPct", p.penaltyPct(), "minAmountUsdt", p.minAmount(),
                    "enabled", true, "killed", false, "status", "ACTIVE");
        }).toList();
    }

    private boolean globalGateEnabled() {
        return KillSwitchState.enabledFailClosed(
                java.util.Optional.ofNullable(mapper.controlValue(STAKING_KILLSWITCH_KEY)),
                java.util.Optional.ofNullable(mapper.controlValue(STAKING_LEGACY_KILLSWITCH_KEY)));
    }

    private BigDecimal configNumber(String key, BigDecimal fallback, BigDecimal min, BigDecimal max) {
        BigDecimal value = config.activeValue(key).map(raw -> decimal(raw, key)).orElse(fallback);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new BizException(503, "STAKING_CONFIG_INVALID:" + key);
        return value.stripTrailingZeros();
    }

    private BigDecimal decimal(String raw, String key) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException ex) {
            throw new BizException(503, "STAKING_CONFIG_INVALID:" + key);
        }
    }

    private ApiResult<Map<String, Object>> positionsResponse(
            Long userId, AppStakingMapper.PositionRow focus, BigDecimal principal, BigDecimal interest,
            BigDecimal penalty, String billNo, String receiptId) {
        return positionsResponse(userId, focus, principal, interest, penalty, billNo, receiptId, 1, 100);
    }

    private ApiResult<Map<String, Object>> positionsResponse(
            Long userId, AppStakingMapper.PositionRow focus, BigDecimal principal, BigDecimal interest,
            BigDecimal penalty, String billNo, String receiptId, int requestedPageNum, int requestedPageSize) {
        int pageNum = Math.max(1, requestedPageNum);
        int pageSize = Math.max(1, Math.min(requestedPageSize, 100));
        long total = Math.max(0L, mapper.countUserPositions(userId));
        long offset = (long) (pageNum - 1) * pageSize;
        Map<String, Object> response = linked(
                "positions", mapper.listUserPositions(userId, offset, pageSize).stream().map(this::positionView).toList(),
                "positionsPage", linked("total", total, "pageNum", pageNum, "pageSize", pageSize),
                "walletBalanceUsdt", money(mapper.walletBalance(userId)),
                "serverTime", LocalDateTime.now(clock),
                "serverCanonical", true,
                "source", "nx_staking_product + nx_config_item + nx_emergency_control_setting",
                "sourceEnvironment", "PRODUCTION",
                "runId", "");
        if (focus != null) response.put("position", positionView(focus));
        if (principal != null) response.put("principalUsdt", money(principal));
        if (interest != null) response.put("interestUsdt", money(interest));
        if (penalty != null) response.put("penaltyUsdt", money(penalty));
        if (focus != null && ("CLAIMED".equals(focus.status()) || "EARLY_WITHDRAWN".equals(focus.status()))) {
            BigDecimal credited = "CLAIMED".equals(focus.status())
                    ? focus.amountUsdt().add(nz(focus.estimatedInterestUsdt()))
                    : focus.amountUsdt().subtract(nz(penalty));
            response.put("creditedUsdt", money(credited));
        }
        if (billNo != null) response.put("billNo", billNo);
        if (receiptId != null) response.put("receiptId", receiptId);
        return ApiResult.ok(response);
    }

    private Map<String, Object> positionView(AppStakingMapper.PositionRow row) {
        return linked(
                "positionNo", row.positionNo(), "tierKey", tierKey(row.productCode()),
                "productCode", row.productCode(), "productName", row.productName(), "amountUsdt", money(row.amountUsdt()),
                "apyPct", nz(row.apyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros(),
                "penaltyPct", nz(row.earlyPenaltyBps()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros(),
                "termDays", row.termDays(), "lockedAt", row.lockedAt(), "unlockAt", row.unlockAt(),
                "estimatedInterestUsdt", money(row.estimatedInterestUsdt()), "status", row.status());
    }

    private AppStakingMapper.PositionRow findPosition(Long userId, String positionNo) {
        AppStakingMapper.PositionRow row = mapper.lockUserPosition(userId, positionNo);
        if (row == null) throw new BizException(409, "STAKING_POSITION_PROJECTION_MISSING");
        return row;
    }

    private AppStakingMapper.PositionRow requirePosition(Long userId, String positionNo) {
        AppStakingMapper.PositionRow row = mapper.lockUserPosition(userId, positionNo);
        if (row == null) throw new BizException(404, "STAKING_POSITION_NOT_FOUND");
        return row;
    }

    private BigDecimal requireWallet(Long userId) {
        BigDecimal value = mapper.lockWalletBalance(userId);
        if (value == null) throw new BizException(409, "STAKING_WALLET_NOT_FOUND");
        return money(value);
    }

    private AppStakingMapper.UserAttribution requireAttribution(Long userId) {
        AppStakingMapper.UserAttribution value = mapper.userAttribution(userId);
        if (value == null || value.accountAgeMonths() == null || !StringUtils.hasText(value.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        return value;
    }

    private void recordUserAudit(
            String action, String positionNo, String billNo, Long userId, String idempotencyKey,
            Map<String, Object> detail, String risk, String path) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action(action).resourceType("STAKING_POSITION").resourceId(positionNo).bizNo(billNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .method("POST").path(path).result("SUCCESS").riskLevel(risk)
                .detail(linked("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim(), "state", detail))
                .build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ApiResult<Map<String, Object>> executeOnce(
            String operation, Long userId, String idempotencyKey, Object request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "APP:G1_STAKING_" + operation + ":USER:" + userId,
                idempotencyKey, sha256(String.valueOf(request)), ApiResult.class, (Supplier) action);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
    }

    private void requireCanonicalProductionRuntime() {
        if (UserAuthEnvironment.resolve(environment).orElse(null) != UserAuthEnvironment.PRODUCTION) {
            throw new BizException(503, "STAKING_PROFILE_INVALID");
        }
    }

    private void requireCanonicalPoolRuntime() {
        if (isDevelopmentRuntime()) return;
        requireCanonicalProductionRuntime();
    }

    private String normalizeTier(String value) {
        String tier = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT).replace("_", "") : "";
        if (!List.of("usdt30d", "usdt90d", "usdt180d", "usdt365d").contains(tier)) {
            throw new BizException(422, "STAKING_TIER_INVALID");
        }
        return tier;
    }

    private String normalizePosition(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("STK-[A-Za-z0-9-]{1,80}")) {
            throw new BizException(422, "STAKING_POSITION_NO_INVALID");
        }
        return value.trim();
    }

    private String tierKey(String productCode) {
        return productCode == null ? "" : productCode.trim().toLowerCase(Locale.ROOT).replace("_", "");
    }

    private String normalizePhase(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "P1";
        if (normalized.matches("[1-6]")) normalized = "P" + normalized;
        return normalized.matches("P[1-6]") ? normalized : "P1";
    }

    private boolean switchEnabled(String raw) {
        return raw != null && List.of("enabled", "enable", "on", "true", "1")
                .contains(raw.trim().toLowerCase(Locale.ROOT));
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

    public record OpenRequest(String tierKey, BigDecimal amountUsdt) {
    }

    private record PoolPolicy(
            BigDecimal apyPct, BigDecimal penaltyPct, BigDecimal minAmount, boolean enabled, boolean killed) {
    }

    private record SandboxProduct(String code, String name, int termDays, BigDecimal apyPct,
                                  BigDecimal penaltyPct, BigDecimal minAmount, boolean enabled) {
    }
}
