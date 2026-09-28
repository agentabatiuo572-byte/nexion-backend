package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CregisDepositService {
    private static final String CHAIN = CregisConstants.BSC_CHAIN_ID;
    private static final String TOKEN = CregisConstants.USDT_BEP20_TOKEN_ID;
    private static final BigDecimal EXPOSURE_TRIP = new BigDecimal("500");
    private final CregisProperties config;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final CregisSigner signer;
    private final ObjectMapper json;
    private final CregisDepositMapper db;
    private final TransactionTemplate transactions;
    private final TreasuryLedgerRepository treasury;
    private final FinanceWithdrawalControlFacade withdrawalControl;

    public CregisDepositService(CregisProperties config, CregisGatewayRouter router,
                                BscDepositProof chain, CregisSigner signer, ObjectMapper json,
                                CregisDepositMapper db, PlatformTransactionManager txManager,
                                TreasuryLedgerRepository treasury,
                                FinanceWithdrawalControlFacade withdrawalControl) {
        this.config = config;
        this.router = router;
        this.chain = chain;
        this.signer = signer;
        this.json = json;
        this.db = db;
        this.transactions = new TransactionTemplate(txManager);
        this.treasury = treasury;
        this.withdrawalControl = withdrawalControl;
    }

    public Map<String, Object> address(long userId) {
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if (!pilot(userId)) return Map.of("enabled", false, "network", "BEP20");
        tripIfNeeded();
        if (config.getDepositConfirmations() < 15) throw new BizException(503, "CREGIS_CONFIRMATIONS_INVALID");
        List<Map<String, Object>> rows = db.addressForUser(userId, CHAIN);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            if (((Number) row.get("projectId")).longValue() != config.getProjectId())
                throw new BizException(409, "CREGIS_PROJECT_ADDRESS_CONFLICT");
            Map<String, Object> gate = db.provisionGate();
            if (gate == null || !"IDLE".equals(gate.get("state")) || !on(gate, "assignEnabled"))
                return Map.of("enabled", false, "network", "BEP20", "reason", "CREGIS_DEPOSIT_PAUSED");
            if ("READY".equals(row.get("state"))) return Map.of(
                    "enabled", true, "network", "BEP20", "address", row.get("address"),
                    "confirmations", config.getDepositConfirmations(), "feeUsdt", 1,
                    "minDepositUsdt", 10);
            throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        }
        Map<String, Object> visibleGate = db.provisionGate();
        if (visibleGate == null || !"IDLE".equals(visibleGate.get("state"))
                || !on(visibleGate, "assignEnabled"))
            return Map.of("enabled", false, "network", "BEP20", "reason", "CREGIS_DEPOSIT_PAUSED");
        if (db.allocatedAddressCount(config.getProjectId(), CHAIN) == 0)
            throw new BizException(503, "CREGIS_ADDRESS_POOL_EMPTY");
        long finalized = chain.head().number() - config.getDepositConfirmations() + 1;
        if (finalized < 0) throw new BizException(503, "CREGIS_CHAIN_NOT_FINALIZED");
        Long before = db.cursor();
        if (before == null || before <= finalized) scanTrackedAddresses(finalized);
        Map<String, Object> assigned = transactions.execute(ignored -> {
            Map<String, Object> gate = db.lockProvisionGate();
            if (gate == null || !"IDLE".equals(gate.get("state")) || !on(gate, "assignEnabled"))
                throw new BizException(503, "CREGIS_ADDRESS_POOL_PAUSED");
            if (tripLocked()) return null;
            List<Map<String, Object>> current = db.addressForUser(userId, CHAIN);
            if (!current.isEmpty()) return current.get(0);
            Long cursor = db.lockCursor();
            if (cursor == null || cursor <= finalized)
                throw new BizException(503, "CREGIS_ADDRESS_POOL_SCANNING");
            Map<String, Object> candidate = db.lockUnassignedAddress(config.getProjectId(), CHAIN);
            if (candidate == null) throw new BizException(503, "CREGIS_ADDRESS_POOL_EMPTY");
            String value = (String) candidate.get("address");
            if (db.addressObservationCount(config.getProjectId(), value, finalized) != 0
                    || !router.provider().addressBelongs(CHAIN, value)
                    || !router.provider().zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY, value)
                    || !chain.zeroUsdtBalance(value)) {
                db.holdPoolAddress(config.getProjectId(), CHAIN, value);
                db.blockProvisionGateAny();
                return null;
            }
            BscDepositProof.Head allocationHead = chain.head();
            if (allocationHead.number() < finalized
                    || !allocationHead.hash().equalsIgnoreCase(chain.blockHash(allocationHead.number())))
                throw new IllegalStateException("CREGIS_ALLOCATION_HEAD_CHANGED");
            if (db.assignPoolAddress(((Number) candidate.get("id")).longValue(), userId,
                    allocationHead.number(), allocationHead.hash()) != 1)
                throw new IllegalStateException("CREGIS_ADDRESS_ASSIGN_CONFLICT");
            return Map.of("address", value, "state", "READY", "projectId", config.getProjectId());
        });
        if (assigned == null) throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        if (!"READY".equals(assigned.get("state")))
            throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        return Map.of("enabled", true, "network", "BEP20", "address", assigned.get("address"),
                "confirmations", config.getDepositConfirmations(), "feeUsdt", 1, "minDepositUsdt", 10);
    }

    /** Explicit, admin-only maintenance call. Never invoked by a user address request. */
    public Map<String, Object> provisionPoolAddress() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER || config.getProjectId() <= 0
                || config.getDepositConfirmations() < 15)
            throw new BizException(503, "CREGIS_PROVISION_NOT_CONFIGURED");
        boolean coinReady = router.provider().projectCoins().stream().anyMatch(coin ->
                coin.addressEnabled() && CHAIN.equals(coin.chainId())
                        && TOKEN.equalsIgnoreCase(coin.tokenId()));
        if (!coinReady) throw new BizException(503, "CREGIS_PROJECT_COIN_UNAVAILABLE");
        long creationBlock = chain.head().number();
        boolean acquired = Boolean.TRUE.equals(transactions.execute(ignored -> {
            Map<String, Object> gate = db.lockProvisionGate();
            if (gate == null || !"IDLE".equals(gate.get("state")) || !on(gate, "assignEnabled"))
                return false;
            if (tripLocked()) return false;
            if (db.allocatedAddressCount(config.getProjectId(), CHAIN) >= 60)
                throw new BizException(409, "CREGIS_ADDRESS_POOL_LIMIT");
            return db.claimProvisionGate() == 1;
        }));
        if (!acquired) throw new BizException(409, "CREGIS_PROVISION_GATE_BLOCKED");
        String requestId = UUID.randomUUID().toString();
        boolean submitted = false;
        try {
            if (db.insertPoolAttempt(config.getProjectId(), CHAIN, requestId, creationBlock) != 1)
                throw new IllegalStateException("CREGIS_ADDRESS_ATTEMPT_INSERT_FAILED");
            submitted = true;
            CregisGateway.Address created = router.provider().createAddress(CHAIN, "NexGrid USDT BEP20",
                    router.depositCallbackUrl(), requestId);
            if (!CHAIN.equals(created.chainId()) || created.address() == null
                    || !created.address().matches("(?i)0x[0-9a-f]{40}"))
                throw new IllegalStateException("CREGIS_ADDRESS_CREATE_INVALID");
            if (db.recordPoolCandidate(requestId, created.address().toLowerCase(Locale.ROOT)) != 1)
                throw new IllegalStateException("CREGIS_ADDRESS_CANDIDATE_CONFLICT");
            return Map.of("state", "VERIFYING", "requestId", requestId);
        } catch (RuntimeException failure) {
            if (submitted) {
                db.unknownPoolAttempt(requestId);
                db.blockProvisionGate();
            } else db.releaseProvisionGate();
            throw failure;
        }
    }

    public List<Map<String, Object>> deposits(long userId) {
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return List.of();
        return db.deposits(userId, config.getProjectId());
    }

    public Map<String, Object> exceptions() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return Map.ofEntries(
                Map.entry("mode", "DISABLED"), Map.entry("depositEnabled", false),
                Map.entry("depositCreditEnabled", false),
                Map.entry("provisionGate", Map.of("state", "DISABLED")),
                Map.entry("uncertainAddresses", List.of()), Map.entry("heldDeposits", List.of()),
                Map.entry("failedDeliveries", List.of()), Map.entry("pendingAcceptedDeliveries", 0),
                Map.entry("pendingDeliveries", List.of()), Map.entry("openReviewCaseCount", 0),
                Map.entry("openRiskAlertCount", 0),
                Map.entry("unattributed", List.of()), Map.entry("providerMissing", List.of()));
        Map<String, Object> gate = db.provisionGate();
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("mode", "PROVIDER");
        status.put("depositEnabled", config.isDepositEnabled());
        status.put("depositCreditEnabled", config.isDepositCreditEnabled());
        status.put("provisionGate", gate == null ? Map.of("state", "MISSING") : gate);
        status.put("unresolvedExposureUsdt", db.unresolvedExposure(config.getProjectId()));
        status.put("riskAlerts", db.riskAlerts(config.getProjectId()));
        status.put("openRiskAlertCount", db.openRiskAlertCount(config.getProjectId()));
        status.put("reconcileRuns", db.reconcileRuns(config.getProjectId()));
        status.put("reviewCases", db.reviewCases(config.getProjectId(), 0));
        status.put("openReviewCaseCount", db.openReviewCaseCount(config.getProjectId()));
        status.put("switchCases", db.switchCases(config.getProjectId()));
        status.put("uncertainAddresses", db.uncertainAddresses(config.getProjectId()));
        status.put("heldDeposits", db.heldDeposits(config.getProjectId()));
        status.put("failedDeliveries", db.failedDeliveries(config.getProjectId()));
        status.put("pendingAcceptedDeliveries", db.pendingAcceptedDeliveryCount(0));
        status.put("pendingDeliveries", db.pendingAcceptedDeliveries());
        status.put("unattributed", db.unattributedObservations(config.getProjectId()));
        status.put("providerMissing", db.providerMissing(config.getProjectId()));
        return status;
    }

    public List<Map<String, Object>> riskAlertsBefore(long beforeId) {
        if (beforeId <= 0) throw new BizException(400, "CREGIS_ALERT_CURSOR_INVALID");
        return config.getMode() == CregisProperties.Mode.PROVIDER
                ? db.riskAlertsBefore(config.getProjectId(), beforeId) : List.of();
    }

    /** Return the provider's literal acknowledgement only after a durable signed delivery. */
    public String receive(String raw) {
        return receive(raw, "127.0.0.1");
    }

    public String receive(String raw, String sourceIp) {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return "rejected";
        String payload = raw == null ? "" : raw;
        if (payload.getBytes(StandardCharsets.UTF_8).length > 16_384) return "rejected";
        Map<String, Object> callback;
        try {
            callback = json.readValue(payload, new TypeReference<>() { });
            if (callback == null) callback = Map.of();
        } catch (Exception invalid) {
            callback = Map.of();
        }
        long now = Instant.now().toEpochMilli();
        boolean timestampValid = callback.get("timestamp") instanceof Number stamp
                && stamp.longValue() >= now - 300_000 && stamp.longValue() <= now + 300_000;
        boolean signatureValid = false;
        try {
            signatureValid = config.getApiKey() != null && !config.getApiKey().isBlank()
                    && signer.verify(config.getApiKey(), callback, String.valueOf(callback.get("sign")));
        } catch (IllegalArgumentException invalid) { /* preserve rejected delivery */ }
        boolean ipValid = allowedCallbackSource(sourceIp);
        boolean accepted = timestampValid && signatureValid && ipValid && valid(callback, true);
        String reason = accepted ? "OK" : !signatureValid ? "INVALID_SIGNATURE"
                : !ipValid ? "INVALID_SOURCE_IP" : !timestampValid ? "INVALID_TIMESTAMP" : "INVALID_FIELDS";
        String txid = accepted ? String.valueOf(callback.get("txid")).toLowerCase(Locale.ROOT) : null;
        if (txid != null && !txid.startsWith("0x")) txid = "0x" + txid;
        String peer = sourceIp != null && sourceIp.length() <= 45
                && sourceIp.matches("[0-9a-fA-F:.]+") ? sourceIp : null;
        Map<String, Object> parsed = callback;
        String normalizedTxid = txid;
        boolean verifiedSignature = signatureValid;
        // Serialize the accepted inbox write with checker decisions and wallet credit.
        // Invalid evidence is retained without taking the financial gate lock.
        transactions.executeWithoutResult(ignored -> {
            if (accepted && db.lockProvisionGate() == null)
                throw new IllegalStateException("CREGIS_DEPOSIT_RISK_GATE_MISSING");
            db.insertDelivery(sha256(payload), payload, accepted ? ((Number) parsed.get("cid")).longValue() : null,
                    normalizedTxid, accepted ? String.valueOf(parsed.get("address")).toLowerCase(Locale.ROOT) : null,
                    accepted ? CregisAmount.parsePositive((String) parsed.get("amount")) : null,
                    accepted ? 1 : 0, reason, peer, verifiedSignature ? 1 : 0,
                    timestampValid ? 1 : 0, ipValid ? 1 : 0);
        });
        return accepted ? "success" : "rejected";
    }

    private boolean allowedCallbackSource(String sourceIp) {
        if (sourceIp == null || sourceIp.isBlank()) return false;
        String configured = config.getCallbackSourceIps();
        if (configured == null || configured.isBlank()) return false;
        for (String candidate : configured.split(","))
            if (sourceIp.equalsIgnoreCase(candidate.trim())) return true;
        return false;
    }

    private boolean valid(Map<String, Object> c, boolean requireFresh) {
        if (c == null || config.getProjectId() <= 0 || config.getApiKey() == null
                || config.getApiKey().isBlank()) return false;
        Object pid = c.get("pid"), cid = c.get("cid"), timestamp = c.get("timestamp");
        if (!(pid instanceof Long || pid instanceof Integer) || ((Number) pid).longValue() != config.getProjectId()
                || !(cid instanceof Long || cid instanceof Integer) || ((Number) cid).longValue() <= 0
                || !(timestamp instanceof Long || timestamp instanceof Integer)
                || (requireFresh && ((Number) timestamp).longValue() < Instant.now().toEpochMilli() - 300_000)
                || (requireFresh && ((Number) timestamp).longValue() > Instant.now().toEpochMilli() + 300_000)
                || !CHAIN.equals(c.get("chain_id"))
                || !TOKEN.equalsIgnoreCase(String.valueOf(c.get("token_id")))
                || !("USDT".equalsIgnoreCase(String.valueOf(c.get("currency")))
                    || "USDT-BEP20".equalsIgnoreCase(String.valueOf(c.get("currency")))
                    || CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(String.valueOf(c.get("currency"))))
                || !String.valueOf(c.get("address")).matches("(?i)0x[0-9a-f]{40}")
                || !String.valueOf(c.get("txid")).matches("(?i)(0x)?[0-9a-f]{64}")
                || !(c.get("amount") instanceof String amount) || CregisAmount.parsePositive(amount) == null
                || CregisAmount.parsePositive(amount).scale() > 6
                || CregisAmount.parsePositive(amount).precision() > 18
                || CregisAmount.parsePositive(amount).compareTo(new BigDecimal("999999999999.999999")) > 0
                || !String.valueOf(c.get("block_height")).matches("[0-9]{1,16}")
                || !("1".equals(String.valueOf(c.get("status"))) || "2".equals(String.valueOf(c.get("status"))))
                || !String.valueOf(c.get("nonce")).matches("[A-Za-z0-9]{6}")) return false;
        try { return signer.verify(config.getApiKey(), c, String.valueOf(c.get("sign"))); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_DEPOSIT_RECONCILE_MS:30000}")
    public void reconcile() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        if (config.getDepositConfirmations() < 15) return;
        tripIfNeeded();
        List<Map<String, Object>> pending = db.pendingDeliveries();
        for (Map<String, Object> delivery : pending) {
            long id = ((Number) delivery.get("id")).longValue();
            try { process(id, (String) delivery.get("rawJson")); }
            catch (RuntimeException unresolved) { db.markDeliveryRetry(id, safeReason(unresolved)); }
        }
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_CHAIN_SCAN_MS:300000}")
    public void scanChain() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        if (config.getDepositConfirmations() < 15) return;
        long finalized = chain.head().number() - config.getDepositConfirmations() + 1;
        scanTrackedAddresses(finalized);
        for (Map<String, Object> observation : db.missingObservations(config.getProjectId())) {
            try { reconcileObservation(observation); }
            catch (RuntimeException unresolved) {
                db.touchObservation(((Number) observation.get("id")).longValue(), safeReason(unresolved));
            }
        }
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_CANONICAL_RECHECK_MS:30000}")
    public void recheckCanonical() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        long finalized = chain.head().number() - config.getDepositConfirmations() + 1;
        for (Map<String, Object> event : db.canonicalPending(config.getProjectId())) {
            long last = ((Number) event.get("lastCheckedBlock")).longValue();
            long next = Math.min(Math.min(last + 20, ((Number) event.get("canonicalUntil")).longValue()), finalized);
            if (next <= last) continue;
            try {
                BscDepositProof.Proof proof = chain.verify((String) event.get("txid"),
                        (String) event.get("address"), (BigDecimal) event.get("grossAmount"),
                        ((Number) event.get("blockNumber")).longValue()).orElse(null);
                if (proof == null || proof.logIndex() != ((Number) event.get("logIndex")).intValue()
                        || !proof.blockHash().equalsIgnoreCase((String) event.get("blockHash"))) {
                    creditedIncident(event, "REORG_INVESTIGATING");
                    continue;
                }
                db.advanceCanonical(((Number) event.get("id")).longValue(), last, next, chain.blockHash(next));
            } catch (IllegalStateException failure) {
                if (!"CREGIS_BSC_PROOF_UNAVAILABLE".equals(failure.getMessage()))
                    creditedIncident(event, "REORG_INVESTIGATING");
            }
        }
    }

    private void creditedIncident(Map<String, Object> event, String kind) {
        long cid = ((Number) event.get("cid")).longValue();
        // Keep the gate closed even if a downstream audit or wallet freeze fails.
        db.blockProvisionGateAny();
        transactions.executeWithoutResult(ignored -> {
            List<Map<String, Object>> locked = db.lockEvent(config.getProjectId(), cid);
            if (locked.size() != 1) throw new IllegalStateException("CREGIS_INCIDENT_EVENT_MISSING");
            freezeLockedCreditedEvent(locked.get(0), cid, kind);
        });
    }

    private void freezeLockedCreditedEvent(Map<String, Object> current, long cid, String kind) {
        if (!"CREDITED".equals(current.get("status"))) return;
        db.blockProvisionGateAny();
        long id = ((Number) current.get("id")).longValue();
        long userId = ((Number) current.get("userId")).longValue();
        if (db.markCreditedIncident(id, kind) != 1)
            throw new IllegalStateException("CREGIS_INCIDENT_EVENT_CONFLICT");
        db.freezeUser(userId, "CR-" + cid, kind);
        db.revokeUserSessions(userId);
        withdrawalControl.freezePendingWithdrawalsForUser(userId, kind, "cregis-reconciler");
        BigDecimal held = BigDecimal.ZERO;
        Map<String, Object> wallet = db.lockWallet(userId);
        if (wallet != null) {
            BigDecimal available = (BigDecimal) wallet.get("usdtAvailable");
            held = available.min((BigDecimal) current.get("netAmount"));
            if (held.signum() > 0) {
                if (db.reserveRiskAmount(userId, ((Number) wallet.get("version")).longValue(), held) != 1)
                    throw new IllegalStateException("CREGIS_INCIDENT_WALLET_CONFLICT");
                if (db.insertRiskHoldLedger("CR-" + cid + ":RISK", userId, held,
                        available.subtract(held), kind) != 1)
                    throw new IllegalStateException("CREGIS_INCIDENT_LEDGER_FAILED");
            }
        }
        if (db.insertIncident(id, config.getProjectId(), cid, userId, kind, held) != 1)
            throw new IllegalStateException("CREGIS_INCIDENT_RECORD_FAILED");
    }

    void scanTrackedAddresses(long finalized) {
        List<Map<String, Object>> allocations = db.allocations(config.getProjectId(), CHAIN);
        if (allocations.isEmpty()) return;
        if (allocations.size() > 60) throw new IllegalStateException("CREGIS_PILOT_ADDRESS_LIMIT_EXCEEDED");
        if (allocations.stream().anyMatch(row -> row.get("creationBlock") == null)) {
            db.blockProvisionGateAny();
            throw new IllegalStateException("CREGIS_ADDRESS_CREATION_BLOCK_MISSING");
        }
        long first = allocations.stream().mapToLong(row -> ((Number) row.get("creationBlock")).longValue())
                .min().orElseThrow();
        db.ensureCursor(first);
        long cursor = db.cursor();
        long from = Math.max(first, cursor - 100);
        long to = Math.min(cursor + 399, finalized);
        if (to >= from) {
            List<String> addresses = allocations.stream().map(row -> (String) row.get("address")).toList();
            for (BscDepositProof.Observation log : chain.scan(from, to, addresses)) {
                Map<String, Object> owner = allocations.stream()
                        .filter(row -> log.address().equalsIgnoreCase((String) row.get("address")))
                        .findFirst().orElseThrow();
                boolean assigned = "READY".equals(owner.get("state"))
                        && log.blockNumber() > ((Number) owner.get("allocationBlock")).longValue();
                String issue = transactions.execute(ignored -> {
                    if (db.lockProvisionGate() == null)
                        throw new IllegalStateException("CREGIS_RISK_GATE_MISSING");
                    Map<String, Object> observed = db.observationByLog(config.getProjectId(),
                            log.txid(), log.logIndex());
                    if (observed != null) {
                        if (!log.address().equalsIgnoreCase((String) observed.get("address"))
                                || new BigDecimal((String) observed.get("rawAmount")).compareTo(log.amount()) != 0
                                || ((Number) observed.get("blockNumber")).longValue() != log.blockNumber()) {
                            db.blockProvisionGateAny();
                            return "CREGIS_CHAIN_OBSERVATION_CONFLICT";
                        }
                    } else if (db.insertObservation(config.getProjectId(), log.txid(), log.logIndex(),
                            log.address(), log.amount().toPlainString(), log.blockNumber(),
                            assigned ? "PROVIDER_MISSING" : "UNATTRIBUTED_HOLD") != 1)
                        throw new IllegalStateException("CREGIS_CHAIN_OBSERVATION_INSERT_FAILED");
                    if (!assigned) {
                        db.holdPoolAddress(config.getProjectId(), CHAIN, log.address());
                        db.blockProvisionGateAny();
                    }
                    tripLocked();
                    return null;
                });
                if (issue != null) throw new IllegalStateException(issue);
            }
            db.advanceCursor(Math.max(cursor, to + 1), cursor);
        }
        verifyPoolCandidate();
    }

    private void verifyPoolCandidate() {
        Map<String, Object> candidate = db.verifyingCandidate(config.getProjectId(), CHAIN);
        if (candidate == null) return;
        Long cursor = db.cursor();
        if (cursor == null || cursor <= ((Number) candidate.get("creationBlock")).longValue()) return;
        String address = (String) candidate.get("address");
        if (db.addressObservationCount(config.getProjectId(), address, cursor - 1) != 0
                || !router.provider().addressBelongs(CHAIN, address)
                || !router.provider().zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY, address)
                || !chain.zeroUsdtBalance(address)) {
            db.holdPoolAddress(config.getProjectId(), CHAIN, address);
            db.blockProvisionGateAny();
            return;
        }
        if (db.readyPoolCandidate(((Number) candidate.get("id")).longValue()) != 1
                || db.releaseProvisionGate() != 1)
            throw new IllegalStateException("CREGIS_ADDRESS_POOL_STATE_CONFLICT");
    }

    private void reconcileObservation(Map<String, Object> observation) {
        String txid = (String) observation.get("txid");
        String address = (String) observation.get("address");
        BigDecimal amount = new BigDecimal((String) observation.get("rawAmount"));
        if (amount.signum() <= 0 || amount.scale() > 6 || amount.precision() > 18)
            throw new IllegalStateException("CREGIS_CHAIN_AMOUNT_REQUIRES_REVIEW");
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), CHAIN, address);
        if (owners.size() != 1) throw new IllegalStateException("CREGIS_CHAIN_ADDRESS_UNATTRIBUTED");
        long height = ((Number) observation.get("blockNumber")).longValue();
        if (height <= ((Number) owners.get(0).get("allocationBlock")).longValue())
            throw new IllegalStateException("CREGIS_CHAIN_BEFORE_ALLOCATION");
        requireAllocationAnchor(owners.get(0));
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(txid).stream()
                .filter(t -> t.status() == 1 && t.address().equalsIgnoreCase(address)
                        && t.amount().compareTo(amount) == 0).toList();
        if (trades.size() != 1) {
            db.touchObservation(((Number) observation.get("id")).longValue(), "CREGIS_TRADE_MISSING");
            return;
        }
        BscDepositProof.Proof proof;
        try { proof = chain.verify(txid, address, amount, height).orElse(null); }
        catch (IllegalStateException failure) {
            if ("CREGIS_BSC_PROOF_UNAVAILABLE".equals(failure.getMessage())) throw failure;
            db.holdChainMismatch(((Number) observation.get("id")).longValue());
            db.blockProvisionGateAny();
            return;
        }
        if (proof == null || proof.logIndex() != ((Number) observation.get("logIndex")).intValue()) return;
        long cid = trades.get(0).cid();
        long userId = ((Number) owners.get(0).get("userId")).longValue();
        if (Boolean.TRUE.equals(transactions.execute(ignored ->
                settleOrHold(0, userId, cid, txid, address, amount, proof))))
            db.matchObservation(((Number) observation.get("id")).longValue());
    }

    private void process(long deliveryId, String raw) {
        Map<String, Object> c;
        try { c = json.readValue(raw, new TypeReference<>() { }); }
        catch (Exception invalid) { throw new IllegalStateException("CREGIS_DELIVERY_INVALID"); }
        if (!valid(c, false)) throw new IllegalStateException("CREGIS_DELIVERY_REVERIFY_FAILED");
        if (!"1".equals(String.valueOf(c.get("status")))) {
            long cid = ((Number) c.get("cid")).longValue();
            List<Map<String, Object>> existing = db.lockEvent(config.getProjectId(), cid);
            if (!existing.isEmpty() && "CREDITED".equals(existing.get(0).get("status"))) {
                creditedIncident(Map.of("cid", cid), "PROVIDER_CONFLICT_HOLD");
                db.finishDelivery(deliveryId, "PROVIDER_CONFLICT_HOLD");
            } else transactions.executeWithoutResult(ignored -> {
                db.blockProvisionGateAny();
                db.finishDelivery(deliveryId, "PROVIDER_FAILED_HOLD");
            });
            return;
        }
        String address = String.valueOf(c.get("address")).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), CHAIN, address);
        if (owners.size() != 1) throw new IllegalStateException("CREGIS_ADDRESS_UNATTRIBUTED");
        String txid = String.valueOf(c.get("txid")).toLowerCase(Locale.ROOT);
        if (!txid.startsWith("0x")) txid = "0x" + txid;
        BigDecimal amount = CregisAmount.parsePositive((String) c.get("amount"));
        long cid = ((Number) c.get("cid")).longValue();
        long blockHeight = Long.parseLong(String.valueOf(c.get("block_height")));
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(txid);
        boolean providerConfirmed = trades.size() == 1 && trades.get(0).cid() == cid
                && trades.get(0).status() == 1 && trades.get(0).address().equalsIgnoreCase(address)
                && trades.get(0).amount().compareTo(amount) == 0;
        if (!providerConfirmed) {
            if (!trades.isEmpty()) {
                List<Map<String, Object>> credited = db.lockEvent(config.getProjectId(), cid);
                if (!credited.isEmpty() && "CREDITED".equals(credited.get(0).get("status")))
                    creditedIncident(Map.of("cid", cid), "PROVIDER_CONFLICT_HOLD");
                db.blockProvisionGateAny();
                db.finishDelivery(deliveryId, "PROVIDER_MISMATCH_HOLD");
                return;
            }
            throw new IllegalStateException("CREGIS_TRADE_NOT_CONFIRMED");
        }
        BscDepositProof.Proof proof;
        try { proof = chain.verify(txid, address, amount, blockHeight).orElse(null); }
        catch (IllegalStateException failure) {
            if ("CREGIS_BSC_PROOF_UNAVAILABLE".equals(failure.getMessage())) throw failure;
            List<Map<String, Object>> credited = db.lockEvent(config.getProjectId(), cid);
            if (!credited.isEmpty() && "CREDITED".equals(credited.get(0).get("status")))
                creditedIncident(Map.of("cid", cid), "REORG_INVESTIGATING");
            db.blockProvisionGateAny();
            db.finishDelivery(deliveryId, "CHAIN_MISMATCH_HOLD");
            return;
        }
        if (proof == null) return;
        Map<String, Object> owner = owners.get(0);
        if (proof.blockNumber() <= ((Number) owner.get("allocationBlock")).longValue())
            throw new IllegalStateException("CREGIS_DEPOSIT_BEFORE_ALLOCATION");
        requireAllocationAnchor(owner);
        final String verifiedTxid = txid;
        transactions.execute(ignored -> settleOrHold(deliveryId,
                ((Number) owner.get("userId")).longValue(), cid, verifiedTxid, address, amount, proof));
    }

    /** Reuses the same provider, allocation and BSC proof path for a missed callback. */
    boolean materializeProviderRow(CregisGateway.DepositRow row) {
        if (row.status() != 1) return true;
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), CHAIN, row.address());
        if (owners.size() != 1) {
            db.insertRiskAlert(config.getProjectId(), "orphan-cid-" + row.cid(), "P0",
                    "PROVIDER_ORPHAN", "cid=" + row.cid() + ",txid=" + row.txid());
            db.blockProvisionGateAny();
            return false;
        }
        Map<String, Object> owner = owners.get(0);
        if (row.blockHeight() <= ((Number) owner.get("allocationBlock")).longValue()) {
            db.blockProvisionGateAny();
            return false;
        }
        requireAllocationAnchor(owner);
        List<CregisGateway.DepositTrade> fresh = router.provider().depositsByTxid(row.txid());
        if (fresh.size() != 1 || fresh.get(0).cid() != row.cid()
                || fresh.get(0).status() != 1 || !fresh.get(0).address().equals(row.address())
                || fresh.get(0).amount().compareTo(row.amount()) != 0) {
            db.blockProvisionGateAny();
            return false;
        }
        BscDepositProof.Proof proof = chain.verify(row.txid(), row.address(), row.amount(),
                row.blockHeight()).orElse(null);
        if (proof == null) return false;
        Map<String, Object> observed = db.observationByLog(config.getProjectId(),
                row.txid(), proof.logIndex());
        if (observed == null || !row.address().equalsIgnoreCase((String) observed.get("address"))
                || row.amount().compareTo(new BigDecimal((String) observed.get("rawAmount"))) != 0
                || row.blockHeight() != ((Number) observed.get("blockNumber")).longValue()) return false;
        if (!"PROVIDER_MISSING".equals(observed.get("status"))
                && !"MATCHED".equals(observed.get("status"))) return false;
        if (db.disputedDeliveryCount(row.cid()) != 0) return false;
        long userId = ((Number) owner.get("userId")).longValue();
        boolean handled = Boolean.TRUE.equals(transactions.execute(ignored ->
                settleOrHold(0, userId, row.cid(), row.txid(), row.address(), row.amount(), proof)));
        if (handled) db.matchObservation(((Number) observed.get("id")).longValue());
        return handled;
    }

    /** A second, independent query detects locally credited rows missing at Cregis. */
    void auditCreditedProviderRows() {
        long afterId = 0;
        while (true) {
            List<Map<String, Object>> page = db.creditedEventsForAudit(config.getProjectId(), afterId);
            if (page.isEmpty()) return;
            for (Map<String, Object> event : page) {
            afterId = ((Number) event.get("id")).longValue();
            if (!"CREDITED".equals(event.get("status"))) continue;
            long cid = ((Number) event.get("cid")).longValue();
            String txid = (String) event.get("txid");
            List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(txid);
            boolean exact = trades.size() == 1 && trades.get(0).cid() == cid
                    && trades.get(0).status() == 1
                    && trades.get(0).address().equalsIgnoreCase((String) event.get("address"))
                    && trades.get(0).amount().compareTo((BigDecimal) event.get("grossAmount")) == 0;
            if (exact) continue;
            String key = "credited-provider-missing-" + cid;
            if (db.insertRiskAlert(config.getProjectId(), key, "P0", "PLATFORM_ORPHAN",
                    "cid=" + cid + ",txid=" + txid) == 0) {
                Long age = db.riskAlertAgeSeconds(config.getProjectId(), key);
                if (age != null && age >= 300) creditedIncident(Map.of("cid", cid), "PROVIDER_CONFLICT_HOLD");
            }
            throw new IllegalStateException("CREGIS_CREDITED_PROVIDER_MISMATCH");
            }
        }
    }

    record ReviewProof(long eventId, long cid, long userId, String txid, String address,
                       BigDecimal amount, long blockNumber, String blockHash, int logIndex,
                       int confirmations, long allocationBlock, String allocationHash,
                       Instant checkedAt) {
        String evidenceHash() {
            return sha256(String.join("|", "CREGIS_REVIEW_V1", Long.toString(eventId),
                    Long.toString(cid), Long.toString(userId), txid.toLowerCase(Locale.ROOT),
                    address.toLowerCase(Locale.ROOT), amount.stripTrailingZeros().toPlainString(),
                    Long.toString(blockNumber), blockHash.toLowerCase(Locale.ROOT),
                    Integer.toString(logIndex), Long.toString(allocationBlock),
                    allocationHash.toLowerCase(Locale.ROOT)));
        }

        Map<String, Object> summary() {
            return Map.ofEntries(Map.entry("eventId", eventId), Map.entry("cid", cid),
                    Map.entry("userId", userId), Map.entry("txid", txid),
                    Map.entry("address", address), Map.entry("grossAmount", amount),
                    Map.entry("proposedNetAmount", amount.subtract(BigDecimal.ONE)),
                    Map.entry("blockNumber", blockNumber), Map.entry("blockHash", blockHash),
                    Map.entry("logIndex", logIndex), Map.entry("confirmations", confirmations),
                    Map.entry("allocationBlock", allocationBlock),
                    Map.entry("allocationHash", allocationHash),
                    Map.entry("providerMatched", true), Map.entry("chainMatched", true),
                    Map.entry("evidenceHash", evidenceHash()), Map.entry("checkedAt", checkedAt));
        }
    }

    void requireReviewProofFresh(ReviewProof proof) {
        if (proof == null || !Instant.now().isBefore(proof.checkedAt().plusSeconds(5)))
            throw new IllegalStateException("CREGIS_REVIEW_PROOF_EXPIRED");
    }

    /** Network checks run before taking the global risk gate and wallet locks. */
    ReviewProof preflightReviewedHold(long eventId) {
        Map<String, Object> event = db.reviewEventSnapshot(eventId);
        if (event == null || !"REVIEW_HOLD".equals(event.get("status")))
            throw new IllegalStateException("CREGIS_REVIEW_EVENT_CHANGED");
        long cid = ((Number) event.get("cid")).longValue();
        long userId = ((Number) event.get("userId")).longValue();
        String txid = (String) event.get("txid"), address = (String) event.get("address");
        BigDecimal amount = (BigDecimal) event.get("grossAmount");
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), CHAIN, address);
        if (owners.size() != 1 || ((Number) owners.get(0).get("userId")).longValue() != userId)
            throw new IllegalStateException("CREGIS_REVIEW_OWNER_MISMATCH");
        Map<String, Object> owner = owners.get(0);
        requireAllocationAnchor(owner);
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(txid);
        if (trades.size() != 1 || trades.get(0).cid() != cid || trades.get(0).status() != 1
                || !trades.get(0).address().equalsIgnoreCase(address)
                || trades.get(0).amount().compareTo(amount) != 0)
            throw new IllegalStateException("CREGIS_REVIEW_PROVIDER_MISMATCH");
        long blockNumber = ((Number) event.get("blockNumber")).longValue();
        BscDepositProof.Proof chainProof = chain.verify(txid, address, amount, blockNumber).orElse(null);
        if (chainProof == null || chainProof.logIndex() != ((Number) event.get("logIndex")).intValue()
                || !chainProof.blockHash().equalsIgnoreCase((String) event.get("blockHash")))
            throw new IllegalStateException("CREGIS_REVIEW_CHAIN_MISMATCH");
        return new ReviewProof(eventId, cid, userId, txid, address, amount, blockNumber,
                chainProof.blockHash(), chainProof.logIndex(), chainProof.confirmations(),
                ((Number) owner.get("allocationBlock")).longValue(), (String) owner.get("allocationHash"),
                Instant.now());
    }

    /** Called only inside a checked review-case transaction with a fresh external proof. */
    void creditReviewedHold(long cid, ReviewProof proof) {
        if (config.getMode() != CregisProperties.Mode.PROVIDER || !config.isDepositCreditEnabled())
            throw new IllegalStateException("CREGIS_CREDIT_DISABLED");
        requireReviewProofFresh(proof);
        List<Map<String, Object>> matches = db.lockEvent(config.getProjectId(), cid);
        if (matches.size() != 1 || !"REVIEW_HOLD".equals(matches.get(0).get("status")))
            throw new IllegalStateException("CREGIS_REVIEW_EVENT_CHANGED");
        Map<String, Object> event = matches.get(0);
        if (proof.cid() != cid || ((Number) event.get("id")).longValue() != proof.eventId()
                || ((Number) event.get("userId")).longValue() != proof.userId()
                || !proof.txid().equalsIgnoreCase((String) event.get("txid"))
                || !proof.address().equalsIgnoreCase((String) event.get("address"))
                || proof.amount().compareTo((BigDecimal) event.get("grossAmount")) != 0
                || proof.blockNumber() != ((Number) event.get("blockNumber")).longValue()
                || !proof.blockHash().equalsIgnoreCase((String) event.get("blockHash"))
                || proof.logIndex() != ((Number) event.get("logIndex")).intValue())
            throw new IllegalStateException("CREGIS_REVIEW_EVENT_CHANGED");
        Map<String, Object> gate = db.lockProvisionGate();
        // A 500-USDT exposure trip may be reduced by a checked hold release.
        // Emergency and incident closures still prohibit every credit.
        boolean automatic = gate != null && "IDLE".equals(gate.get("state"))
                && on(gate, "creditEnabled")
                && db.unresolvedExposure(config.getProjectId()).compareTo(EXPOSURE_TRIP) < 0;
        boolean exposureResolution = gate != null && "BLOCKED".equals(gate.get("state"))
                && "CREGIS_UNRESOLVED_EXPOSURE_500".equals(gate.get("switchReason"))
                && db.unresolvedExposure(config.getProjectId()).compareTo(EXPOSURE_TRIP) >= 0;
        if ((!automatic && !exposureResolution)
                || db.disputedDeliveryCount(cid) != 0
                || db.disputedDeliveryCountForProject(config.getProjectId()) != 0
                || db.pendingAcceptedDeliveryCount(0) != 0
                || db.openNonExposureCriticalAlertCount(config.getProjectId()) != 0
                || db.criticalHeldDepositCount(config.getProjectId()) != 0
                || !db.providerMissing(config.getProjectId()).isEmpty()
                || !db.unattributedObservations(config.getProjectId()).isEmpty())
            throw new IllegalStateException("CREGIS_DEPOSIT_RISK_GATE_BLOCKED");
        long userId = ((Number) event.get("userId")).longValue();
        if (!Long.valueOf(userId).equals(db.lockActiveUser(userId)))
            throw new IllegalStateException("CREGIS_DEPOSIT_USER_FROZEN");
        String txid = (String) event.get("txid"), address = (String) event.get("address");
        BigDecimal amount = (BigDecimal) event.get("grossAmount");
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), CHAIN, address);
        if (owners.size() != 1 || ((Number) owners.get(0).get("userId")).longValue() != userId)
            throw new IllegalStateException("CREGIS_REVIEW_OWNER_MISMATCH");
        if (((Number) owners.get(0).get("allocationBlock")).longValue() != proof.allocationBlock()
                || !proof.allocationHash().equalsIgnoreCase((String) owners.get(0).get("allocationHash")))
            throw new IllegalStateException("CREGIS_ALLOCATION_ANCHOR_MISMATCH");
        requireReviewProofFresh(proof);
        BigDecimal net = amount.subtract(BigDecimal.ONE);
        long eventId = ((Number) event.get("id")).longValue();
        if (db.releaseReviewedEvent(eventId, BigDecimal.ONE, net, proof.confirmations()) != 1)
            throw new IllegalStateException("CREGIS_REVIEW_EVENT_CONFLICT");
        Map<String, Object> wallet = db.lockWallet(userId);
        if (wallet == null) throw new IllegalStateException("CREGIS_WALLET_MISSING");
        requireReviewProofFresh(proof);
        BigDecimal after = ((BigDecimal) wallet.get("usdtAvailable")).add(net);
        if (db.creditWallet(net, userId, ((Number) wallet.get("version")).longValue()) != 1)
            throw new IllegalStateException("CREGIS_WALLET_CONFLICT");
        String bizNo = "CR-" + cid;
        if (db.insertLedger(bizNo, userId, net, after, "Reviewed Cregis USDT-BEP20 deposit " + txid) != 1)
            throw new IllegalStateException("CREGIS_LEDGER_INSERT_FAILED");
        treasury.recordTopupReserve(bizNo, net, "CREGIS:" + cid);
        Long ledgerId = db.ledgerId(bizNo);
        if (ledgerId == null || db.linkLedger(ledgerId, eventId) != 1
                || db.insertDepositOrder(userId, bizNo, txid, proof.logIndex(), net,
                        proof.confirmations(), ledgerId) != 1)
            throw new IllegalStateException("CREGIS_REVIEW_LEDGER_LINK_FAILED");
    }

    private boolean settleOrHold(long deliveryId, long userId, long cid, String txid, String address,
                                 BigDecimal amount, BscDepositProof.Proof proof) {
        // Lock first: an earlier consistent read would pin a stale REPEATABLE READ
        // snapshot and miss a callback committed while waiting for this gate.
        Map<String, Object> gate = db.lockProvisionGate();
        if (gate == null) throw new IllegalStateException("CREGIS_DEPOSIT_RISK_GATE_MISSING");
        if (db.disputedDeliveryCount(cid) != 0) return false;
        List<Map<String, Object>> existing = db.lockEvent(config.getProjectId(), cid);
        if (!existing.isEmpty()) return settle(deliveryId, userId, cid, txid, address, amount, proof);
        if (config.isDepositCreditEnabled() && "IDLE".equals(gate.get("state"))
                && on(gate, "creditEnabled") && db.pendingAcceptedDeliveryCount(deliveryId) == 0) {
            if (settle(deliveryId, userId, cid, txid, address, amount, proof)) return true;
            // A trip during settlement can close the gate before this verified
            // transfer is materialized. Keep it as a reviewed hold.
            if (!db.lockEvent(config.getProjectId(), cid).isEmpty()) return false;
        }
        String status = amount.compareTo(BigDecimal.TEN) < 0 ? "DUST_HOLD" : "REVIEW_HOLD";
        if (db.insertEvent(userId, config.getProjectId(), cid, txid, proof.logIndex(),
                address, amount, BigDecimal.ZERO, BigDecimal.ZERO, proof.blockNumber(),
                proof.blockHash(), proof.confirmations(), status) != 1)
            throw new IllegalStateException("CREGIS_PAUSED_HOLD_INSERT_FAILED");
        tripLocked();
        if (deliveryId > 0) db.finishDelivery(deliveryId, status);
        return true;
    }

    private boolean settle(long deliveryId, long userId, long cid, String txid, String address,
                        BigDecimal amount, BscDepositProof.Proof proof) {
        List<Map<String, Object>> existing = db.lockEvent(config.getProjectId(), cid);
        if (db.disputedDeliveryCount(cid) != 0) {
            db.blockProvisionGateAny();
            return false;
        }
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            boolean conflict = ((Number) row.get("userId")).longValue() != userId
                    || !txid.equalsIgnoreCase((String) row.get("txid"))
                    || !address.equalsIgnoreCase((String) row.get("address"))
                    || ((Number) row.get("logIndex")).intValue() != proof.logIndex()
                    || ((BigDecimal) row.get("grossAmount")).compareTo(amount) != 0;
            if (conflict) {
                freezeLockedCreditedEvent(row, cid, "PROVIDER_CONFLICT_HOLD");
                db.blockProvisionGateAny();
                if (deliveryId > 0) db.finishDelivery(deliveryId, "PROVIDER_CONFLICT_HOLD");
                return false;
            }
            if (deliveryId > 0) db.finishDelivery(deliveryId, String.valueOf(row.get("status")));
            return true;
        }
        String status = amount.compareTo(BigDecimal.TEN) < 0 ? "DUST_HOLD"
                : amount.compareTo(new BigDecimal("100")) > 0 ? "REVIEW_HOLD" : "CREDITED";
        Map<String, Object> gate = db.lockProvisionGate();
        if (gate == null) throw new IllegalStateException("CREGIS_DEPOSIT_RISK_GATE_MISSING");
        if (tripLocked()) return false;
        if (!"IDLE".equals(gate.get("state")) || !on(gate, "creditEnabled")
                || db.pendingAcceptedDeliveryCount(deliveryId) != 0) return false;
        if (!Long.valueOf(userId).equals(db.lockActiveUser(userId)))
            throw new IllegalStateException("CREGIS_DEPOSIT_USER_FROZEN");
        BigDecimal fee = "CREDITED".equals(status) ? BigDecimal.ONE : BigDecimal.ZERO;
        BigDecimal net = "CREDITED".equals(status) ? amount.subtract(fee) : BigDecimal.ZERO;
        if (db.insertEvent(userId, config.getProjectId(), cid, txid, proof.logIndex(), address, amount, fee, net,
                proof.blockNumber(), proof.blockHash(), proof.confirmations(), status) != 1)
            throw new IllegalStateException("CREGIS_EVENT_INSERT_FAILED");
        if (!"CREDITED".equals(status)) tripLocked();
        if ("CREDITED".equals(status)) {
            Map<String, Object> wallet = db.lockWallet(userId);
            if (wallet == null) throw new IllegalStateException("CREGIS_WALLET_MISSING");
            BigDecimal after = ((BigDecimal) wallet.get("usdtAvailable")).add(net);
            if (db.creditWallet(net, userId, ((Number) wallet.get("version")).longValue()) != 1)
                throw new IllegalStateException("CREGIS_WALLET_CONFLICT");
            String bizNo = "CR-" + cid;
            if (db.insertLedger(bizNo, userId, net, after, "Cregis USDT-BEP20 deposit " + txid) != 1)
                throw new IllegalStateException("CREGIS_LEDGER_INSERT_FAILED");
            treasury.recordTopupReserve(bizNo, net, "CREGIS:" + cid);
            Long ledgerId = db.ledgerId(bizNo), eventId = db.eventId(config.getProjectId(), cid);
            if (ledgerId == null || eventId == null || db.linkLedger(ledgerId, eventId) != 1)
                throw new IllegalStateException("CREGIS_LEDGER_LINK_FAILED");
            if (db.insertDepositOrder(userId, bizNo, txid, proof.logIndex(), net, proof.confirmations(), ledgerId) != 1)
                throw new IllegalStateException("CREGIS_DEPOSIT_ORDER_FAILED");
        }
        if (deliveryId > 0) db.finishDelivery(deliveryId, status);
        return true;
    }

    private void requireAllocationAnchor(Map<String, Object> owner) {
        Number block = (Number) owner.get("allocationBlock");
        String hash = (String) owner.get("allocationHash");
        if (block == null || hash == null || !hash.equalsIgnoreCase(chain.blockHash(block.longValue()))) {
            db.blockProvisionGateAny();
            throw new IllegalStateException("CREGIS_ALLOCATION_ANCHOR_MISMATCH");
        }
    }

    private boolean pilot(long userId) {
        if (!config.isDepositEnabled() || !config.isDepositCreditEnabled()
                || config.getMode() != CregisProperties.Mode.PROVIDER) return false;
        String raw = config.getDepositPilotUserIds();
        if (raw == null) return false;
        if (raw.split(",").length > 50) return false;
        for (String id : raw.split(",")) if (id.trim().equals(Long.toString(userId))) return true;
        return false;
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_RISK_CHECK_MS:30000}")
    public void tripIfNeeded() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        transactions.executeWithoutResult(ignored -> {
            if (db.lockProvisionGate() == null) throw new IllegalStateException("CREGIS_RISK_GATE_MISSING");
            tripLocked();
        });
    }

    /** The provision row serializes the trip against address assignment and credit. */
    private boolean tripLocked() {
        BigDecimal exposure = db.unresolvedExposure(config.getProjectId());
        if (exposure == null) throw new IllegalStateException("CREGIS_EXPOSURE_UNAVAILABLE");
        if (exposure.compareTo(EXPOSURE_TRIP) < 0) {
            db.resolveExposureAlert(config.getProjectId());
            return false;
        }
        db.tripAll("CREGIS_UNRESOLVED_EXPOSURE_500");
        db.insertRiskAlert(config.getProjectId(), "unresolved-exposure-500", "P0",
                "UNRESOLVED_EXPOSURE", "Exposure reached " + exposure.toPlainString() + " USDT");
        db.reopenExposureAlert(config.getProjectId());
        return true;
    }

    private static boolean on(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() == 1);
    }

    private static String sha256(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String safeReason(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && message.length() <= 64 && message.matches("[A-Z0-9_]+")
                ? message : "CREGIS_RECONCILIATION_UNAVAILABLE";
    }
}
