package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisFundsPreparationMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Preparation records only: deliberately has no collection/payout execution path or scheduler. */
@Service
@RequiredArgsConstructor
public class CregisFundsPreparationService {
    static final String PLATFORM = "0x0fe784237f21e7b7d74082cb265d7b4e1c91fdad";
    static final String COUNTERPARTY = "0x43e22c774baae7543b45d365050059d5fcef7e97";
    private final CregisProperties properties;
    private final CregisFundsPreparationMapper db;
    private final CregisExceptionAdminGuard admins;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final CregisPayoutControl payout;

    public Map<String,Object> status(String network) {
        admins.require(false);
        CregisNetwork n = parseNetwork(network);
        var result = new LinkedHashMap<>(configuration(n));
        result.put("preparationNetwork", n.network());
        result.put("projectId", Long.toString(project()));
        result.put("executionEnabled", false);
        result.put("sources", db.sources(project(), n.chainId()));
        result.put("mode", "PREPARATION_ONLY");
        return result;
    }

    public Map<String,Object> plans(String network, long beforeId, int limit) {
        admins.require(false);
        if (beforeId < 0 || limit < 1 || limit > 50) throw invalid();
        CregisNetwork n = parseNetwork(network);
        var rows = db.plans(project(), n.network(), beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var records = rows.subList(0, Math.min(limit, rows.size()));
        var result = new LinkedHashMap<String,Object>();
        result.put("preparationNetwork", n.network());
        result.put("records", records);
        result.put("hasMore", more);
        result.put("nextBeforeId", more ? records.get(records.size()-1).get("id").toString() : null);
        return result;
    }

    /** Vendor and RPC reads only. No query result can authorize or trigger a transfer. */
    public Map<String,Object> preflight(String network, String kind, String suppliedSource, String suppliedAmount) {
        requireWriter();
        CregisNetwork n = parseNetwork(network);
        var config = configuration(n);
        String source = source(kind, suppliedSource, config, n);
        BigDecimal amount = amount(suppliedAmount);
        var result = new LinkedHashMap<String,Object>();
        var blockers = new ArrayList<String>();
        result.put("preparationNetwork", n.network());
        result.put("executionEnabled", false);
        result.put("sourceAddress", source);
        result.put("targetAddress", target(kind, config));
        result.put("amountUsdt", amount.toPlainString());
        result.put("checkedAt", Instant.now().toString());
        result.put("walletBindingReviewRequired", true);
        result.put("gasEstimateRequired", true);
        result.put("balance", null);
        result.put("providerVerified", false);
        if (properties.getMode() != CregisProperties.Mode.PROVIDER) {
            blockers.add("PROVIDER_NOT_CONFIGURED");
        } else {
            try {
                var provider = router.provider();
                boolean supported = provider.projectCoins().stream().anyMatch(coin ->
                        n.currency().equals(coin.currency())
                        && ("COLLECTION".equals(kind) ? coin.addressEnabled() : coin.payoutEnabled()));
                if (!supported) blockers.add("PROJECT_ASSET_UNAVAILABLE");
                // address/inner validates sub-address ownership, not the bound wallet address.
                if ("COLLECTION".equals(kind) && !provider.addressBelongs(n.chainId(), source))
                    blockers.add("SOURCE_OWNERSHIP_UNVERIFIED");
                result.put("providerVerified", supported && blockers.isEmpty());
            } catch (RuntimeException unavailable) { blockers.add("PROVIDER_READ_UNAVAILABLE"); }
            try {
                var balance = chain.preparationBalance(n, source);
                result.put("balance", balanceMap(balance, n));
                if (new BigDecimal(balance.usdt()).compareTo(amount) < 0) blockers.add("USDT_BALANCE_INSUFFICIENT");
                if (new BigDecimal(balance.nativeBalance()).signum() == 0)
                    blockers.add(n.gasSymbol().toUpperCase(Locale.ROOT) + "_BALANCE_ZERO");
            } catch (RuntimeException unavailable) { blockers.add("CHAIN_BALANCE_UNAVAILABLE"); }
            try {
                var capability = payout.capability(n);
                result.put("existingPayoutCapability", capability);
                if (!Boolean.TRUE.equals(capability.get("enabled"))) blockers.add("EXISTING_PAYOUT_NOT_READY");
            } catch (RuntimeException unavailable) { blockers.add("RISK_STATUS_UNAVAILABLE"); }
        }
        result.put("blockers", blockers);
        return result;
    }

    public Map<String,Object> create(String network, String key, String kind, String suppliedSource, String suppliedAmount,
                                     String reference, String reason) {
        long actor = requireWriter();
        requireReason(reason);
        if (reference == null || reference.isBlank() || reference.length() > 100
                || reference.chars().anyMatch(Character::isISOControl)) throw invalid();
        BigDecimal amount = amount(suppliedAmount);
        CregisNetwork n = parseNetwork(network);
        String normalizedSource = suppliedSource == null ? "" : suppliedSource.trim().toLowerCase(Locale.ROOT);
        var request = new TreeMap<String,Object>();
        request.put("kind", Objects.toString(kind, ""));
        request.put("source", normalizedSource);
        request.put("amount", amount.toPlainString());
        request.put("reference", reference.trim());
        request.put("reason", reason.trim());
        if (n != CregisNetwork.BEP20) request.put("network", n.network());
        return command(actor, key, request, () -> {
            var config = configuration(n);
            String source = source(kind, normalizedSource, config, n), target = target(kind, config);
            String no = "CF-" + UUID.randomUUID();
            try {
                if (db.insert(no, project(), kind, n.network(), source, target, amount, reference.trim(), reason.trim(), actor) != 1)
                    throw conflict();
            } catch (DuplicateKeyException duplicate) { throw new BizException(409,"CREGIS_PREPARATION_REFERENCE_EXISTS"); }
            var detail = new LinkedHashMap<String,Object>(request);
            detail.put("network", n.network());
            record("CREATED", no, actor, detail);
            return planWithNetwork(required(db.plan(project(), no)), n);
        });
    }

    public Map<String,Object> decide(String key, String no, long expectedVersion, String decision, String reason) {
        long actor = requireWriter();
        requireReason(reason);
        if (no == null || !no.matches("CF-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || expectedVersion < 0 || !Set.of("REVIEW", "CANCEL").contains(Objects.toString(decision,""))) throw invalid();
        var request = Map.<String,Object>of("no",no,"version",expectedVersion,"decision",decision,"reason",reason.trim());
        return command(actor, key, request, () -> {
            var row = required(db.lock(project(), no));
            CregisNetwork n = parseNetwork(row.get("network") == null ? null : row.get("network").toString());
            if (number(row,"version") != expectedVersion) throw conflict();
            String previous = row.get("state").toString(), next;
            if ("REVIEW".equals(decision)) {
                if (!"DRAFT".equals(previous)) throw conflict();
                long maker = number(row,"makerId");
                if (maker == actor) throw new BizException(409,"CREGIS_PREPARATION_DIFFERENT_CHECKER_REQUIRED");
                admins.requireActive(maker);
                var config = configuration(n);
                String source = source(row.get("kind").toString(), row.get("sourceAddress").toString(), config, n);
                if (!n.addressEquals(source, row.get("sourceAddress").toString())
                        || !n.addressEquals(target(row.get("kind").toString(), config), row.get("targetAddress").toString()))
                    throw conflict();
                next = "REVIEWED";
            } else {
                if (!Set.of("DRAFT","REVIEWED").contains(previous)) throw conflict();
                next = "CANCELLED";
            }
            if (db.decide(project(),no,expectedVersion,previous,next,actor,reason.trim()) != 1) throw conflict();
            var detail = new LinkedHashMap<String,Object>(request);
            detail.put("network", n.network());
            record(next,no,actor,detail);
            return planWithNetwork(required(db.plan(project(), no)), n);
        });
    }

    private CregisNetwork parseNetwork(String value) {
        if (value == null || value.isBlank()) return CregisNetwork.BEP20;
        CregisNetwork network;
        try { network = CregisNetwork.fromNetwork(value); }
        catch (IllegalArgumentException unsupported) { throw invalid(); }
        if (network == CregisNetwork.TRC20) throw invalid();
        return network;
    }

    private Map<String,Object> configuration(CregisNetwork network) {
        if (network == CregisNetwork.BEP20) {
            var row = db.config(project());
            if (row == null || !PLATFORM.equals(row.get("platformAddress")) || !COUNTERPARTY.equals(row.get("counterpartyAddress"))
                    || !"BSC".equals(row.get("network")) || !"USDT".equals(row.get("asset")))
                throw new BizException(503,"CREGIS_PREPARATION_CONFIGURATION_REQUIRED");
            return row;
        }
        if (!properties.autoForwardAddressesConfigured(network))
            throw new BizException(503,"CREGIS_PREPARATION_CONFIGURATION_REQUIRED");
        var row = new LinkedHashMap<String,Object>();
        row.put("platformAddress", network.normalizeAddress(properties.autoForwardPlatform(network)));
        row.put("counterpartyAddress", network.normalizeAddress(properties.autoForwardCounterparty(network)));
        row.put("network", network.policyChainId());
        row.put("asset", "USDT");
        return row;
    }

    private String source(String kind, String supplied, Map<String,Object> config, CregisNetwork network) {
        if (!Set.of("COLLECTION","SUPPLIER_PAYMENT").contains(Objects.toString(kind,""))) throw invalid();
        String source = supplied == null ? "" : supplied.trim().toLowerCase(Locale.ROOT);
        if ("SUPPLIER_PAYMENT".equals(kind)) {
            if (!source.isEmpty() && !network.addressEquals(source, config.get("platformAddress").toString())) throw invalid();
            return network.normalizeAddress(config.get("platformAddress").toString());
        }
        if (!network.validAddress(source) || network.addressEquals(source, config.get("platformAddress").toString())
                || db.sourceExists(project(), network.chainId(), network.normalizeAddress(source)) != 1)
            throw new BizException(409,"CREGIS_PREPARATION_SOURCE_INVALID");
        return network.normalizeAddress(source);
    }

    private String target(String kind, Map<String,Object> config) {
        return config.get("COLLECTION".equals(kind) ? "platformAddress" : "counterpartyAddress").toString();
    }

    private Map<String,Object> balanceMap(BscDepositProof.PreparationBalance balance, CregisNetwork network) {
        var map = new LinkedHashMap<String,Object>();
        map.put("usdt", balance.usdt());
        if (network == CregisNetwork.BEP20) map.put("bnb", balance.bnb());
        map.put("nativeBalance", balance.nativeBalance());
        map.put("gasSymbol", network.gasSymbol());
        map.put("blockNumber", balance.blockNumber());
        map.put("blockHash", balance.blockHash());
        return map;
    }

    private Map<String,Object> planWithNetwork(Map<String,Object> plan, CregisNetwork network) {
        plan.put("preparationNetwork", network.network());
        return plan;
    }

    static BigDecimal amount(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?")) throw invalid();
        var amount = new BigDecimal(value);
        if (amount.signum() <= 0) throw invalid();
        return amount.setScale(6);
    }

    private long project() {
        if (properties.getProjectId() <= 0) throw new BizException(503,"CREGIS_PREPARATION_CONFIGURATION_REQUIRED");
        return properties.getProjectId();
    }

    private long requireWriter() {
        long actor = admins.require(true);
        if (SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .noneMatch(a -> "finance_d2_withdrawal_approve".equals(a.getAuthority())))
            throw new BizException(403,"ADMIN_FINANCE_AUTH_REQUIRED");
        return actor;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> command(long actor,String key,Map<String,Object> request,Supplier<Map<String,Object>> action) {
        return idempotency.executeRetained("CREGIS_FUNDS_PREPARATION:"+actor,key,hash(request),Map.class,action::get);
    }

    private String hash(Map<String,Object> request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(new TreeMap<>(request)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("PREPARATION_HASH_FAILED",failure); }
    }

    private void record(String action,String no,long actor,Object detail) {
        audit.recordRequired(AuditLogWriteRequest.builder().action("CREGIS_FUNDS_PREPARATION_"+action)
                .resourceType("CREGIS_FUNDS_PREPARATION").resourceId(no).bizNo(no).actorType("ADMIN").actorId(actor)
                .actorUsername("admin:"+actor).riskLevel("HIGH").result("SUCCESS").detail(detail).build());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 10 || reason.trim().length() > 300) throw invalid();
    }

    private static long number(Map<String,Object> row,String name) { return Long.parseLong(row.get(name).toString()); }
    private static Map<String,Object> required(Map<String,Object> row) { if (row == null) throw conflict(); return row; }
    private static BizException invalid() { return new BizException(400,"CREGIS_PREPARATION_REQUEST_INVALID"); }
    private static BizException conflict() { return new BizException(409,"CREGIS_PREPARATION_STATE_CONFLICT"); }
}
