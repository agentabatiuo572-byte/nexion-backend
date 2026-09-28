package ffdd.opsconsole.finance.cregis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
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
    private final CregisProperties config;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final CregisSigner signer;
    private final ObjectMapper json;
    private final CregisDepositMapper db;
    private final TransactionTemplate transactions;
    private final TreasuryLedgerRepository treasury;

    public CregisDepositService(CregisProperties config, CregisGatewayRouter router,
                                BscDepositProof chain, CregisSigner signer, ObjectMapper json,
                                CregisDepositMapper db, PlatformTransactionManager txManager,
                                TreasuryLedgerRepository treasury) {
        this.config = config;
        this.router = router;
        this.chain = chain;
        this.signer = signer;
        this.json = json;
        this.db = db;
        this.transactions = new TransactionTemplate(txManager);
        this.treasury = treasury;
    }

    public Map<String, Object> address(long userId) {
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if (!pilot(userId)) return Map.of("enabled", false, "network", "BEP20");
        if (config.getDepositConfirmations() < 15) throw new BizException(503, "CREGIS_CONFIRMATIONS_INVALID");
        List<Map<String, Object>> rows = db.addressForUser(userId, CHAIN);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            if (((Number) row.get("projectId")).longValue() != config.getProjectId())
                throw new BizException(409, "CREGIS_PROJECT_ADDRESS_CONFLICT");
            if ("READY".equals(row.get("state"))) return Map.of(
                    "enabled", true, "network", "BEP20", "address", row.get("address"),
                    "confirmations", config.getDepositConfirmations(), "feeUsdt", 1,
                    "minDepositUsdt", 10);
            throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        }
        CregisGateway gateway = router.provider();
        boolean supported = gateway.projectCoins().stream().anyMatch(coin -> coin.addressEnabled()
                && CHAIN.equals(coin.chainId()) && TOKEN.equalsIgnoreCase(coin.tokenId()));
        if (!supported) throw new BizException(503, "CREGIS_BEP20_NOT_ENABLED");
        // Avoid creating an unservable address while the chain reader is unavailable.
        chain.head();
        if (db.claimProvisionGate() != 1)
            throw new BizException(409, "CREGIS_ADDRESS_PROVISIONING_OR_BLOCKED");
        // The first durable attempt owns this user. A timeout can have created an address,
        // so no request may repeat it automatically, even after process restart.
        String requestId = UUID.randomUUID().toString();
        try {
            if (db.allocatedAddressCount(config.getProjectId(), CHAIN) >= 50) {
                db.releaseProvisionGate();
                throw new BizException(409, "CREGIS_PILOT_ADDRESS_LIMIT_REACHED");
            }
            db.insertAddressAttempt(userId, config.getProjectId(), CHAIN, requestId);
        } catch (org.springframework.dao.DuplicateKeyException concurrent) {
            db.releaseProvisionGate();
            throw new BizException(409, "CREGIS_ADDRESS_PROVISIONING");
        } catch (BizException rejected) {
            throw rejected;
        } catch (RuntimeException uncertainInsert) {
            db.blockProvisionGate();
            throw new BizException(503, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        }
        try {
            CregisGateway.Address created = gateway.createAddress(CHAIN, "NexGrid", router.depositCallbackUrl(), requestId);
            // Preserve the candidate before any later ownership/RPC failure for manual recovery.
            if (db.recordCandidateAddress(created.address().toLowerCase(Locale.ROOT), userId, requestId) != 1)
                throw new IllegalStateException("CREGIS_ADDRESS_STATE_CONFLICT");
            if (!gateway.addressBelongs(CHAIN, created.address()))
                throw new IllegalStateException("CREGIS_ADDRESS_OWNERSHIP_UNVERIFIED");
            if (!chain.zeroUsdtBalance(created.address()))
                throw new IllegalStateException("CREGIS_ADDRESS_BALANCE_NONZERO");
            BscDepositProof.Head allocation = chain.head();
            if (db.readyAddress(created.address().toLowerCase(Locale.ROOT), allocation.number(),
                    allocation.hash(), userId, requestId) != 1)
                throw new IllegalStateException("CREGIS_ADDRESS_STATE_CONFLICT");
            if (db.releaseProvisionGate() != 1)
                throw new IllegalStateException("CREGIS_PROVISION_GATE_CONFLICT");
            return address(userId);
        } catch (RuntimeException unknown) {
            db.unknownAddress(userId, requestId);
            db.blockProvisionGate();
            throw new BizException(503, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        }
    }

    public List<Map<String, Object>> deposits(long userId) {
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return List.of();
        return db.deposits(userId, config.getProjectId());
    }

    public Map<String, Object> exceptions() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return Map.of("mode", "DISABLED");
        Map<String, Object> gate = db.provisionGate();
        return Map.of("mode", "PROVIDER", "provisionGate", gate == null ? Map.of("state", "MISSING") : gate,
                "uncertainAddresses", db.uncertainAddresses(config.getProjectId()),
                "heldDeposits", db.heldDeposits(config.getProjectId()),
                "failedDeliveries", db.failedDeliveries(config.getProjectId()),
                "providerMissing", db.providerMissing(config.getProjectId()));
    }

    /** Return the provider's literal acknowledgement only after a durable signed delivery. */
    public String receive(String raw) {
        if (config.getMode() != CregisProperties.Mode.PROVIDER || raw == null
                || raw.getBytes(StandardCharsets.UTF_8).length > 16_384) return "rejected";
        Map<String, Object> callback;
        String reason = "OK";
        try {
            callback = json.readValue(raw, new TypeReference<>() { });
            if (!valid(callback, true)) reason = "INVALID";
        } catch (Exception invalid) {
            callback = Map.of();
            reason = "INVALID";
        }
        boolean accepted = "OK".equals(reason);
        if (!accepted) return "rejected";
        String txid = accepted ? String.valueOf(callback.get("txid")).toLowerCase(Locale.ROOT) : null;
        if (txid != null && !txid.startsWith("0x")) txid = "0x" + txid;
        db.insertDelivery(sha256(raw), raw, accepted ? ((Number) callback.get("cid")).longValue() : null,
                txid, accepted ? String.valueOf(callback.get("address")).toLowerCase(Locale.ROOT) : null,
                accepted ? CregisAmount.parsePositive((String) callback.get("amount")) : null,
                accepted ? 1 : 0, reason);
        return "OK".equals(reason) ? "success" : "rejected";
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
        List<Map<String, Object>> allocations = db.allocations(config.getProjectId(), CHAIN);
        if (allocations.isEmpty()) return;
        if (allocations.size() > 50) throw new IllegalStateException("CREGIS_PILOT_ADDRESS_LIMIT_EXCEEDED");
        long first = allocations.stream().mapToLong(row -> ((Number) row.get("allocationBlock")).longValue())
                .min().orElseThrow();
        db.ensureCursor(first);
        long cursor = db.cursor();
        long finalized = chain.head().number() - config.getDepositConfirmations() + 1;
        long from = Math.max(first, cursor - 100);
        long to = Math.min(cursor + 399, finalized);
        if (to >= from) {
            List<String> addresses = allocations.stream().map(row -> (String) row.get("address")).toList();
            for (BscDepositProof.Observation log : chain.scan(from, to, addresses)) {
                db.insertObservation(config.getProjectId(), log.txid(), log.logIndex(), log.address(),
                        log.amount().toPlainString(), log.blockNumber());
            }
            db.advanceCursor(Math.max(cursor, to + 1), cursor);
        }
        for (Map<String, Object> observation : db.missingObservations(config.getProjectId())) {
            try { reconcileObservation(observation); }
            catch (RuntimeException unresolved) {
                db.touchObservation(((Number) observation.get("id")).longValue(), safeReason(unresolved));
            }
        }
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
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(txid).stream()
                .filter(t -> t.status() == 1 && t.address().equalsIgnoreCase(address)
                        && t.amount().compareTo(amount) == 0).toList();
        if (trades.size() != 1) {
            db.touchObservation(((Number) observation.get("id")).longValue(), "CREGIS_TRADE_MISSING");
            return;
        }
        BscDepositProof.Proof proof = chain.verify(txid, address, amount, height).orElse(null);
        if (proof == null || proof.logIndex() != ((Number) observation.get("logIndex")).intValue()) return;
        if (!config.isDepositCreditEnabled()) return;
        long cid = trades.get(0).cid();
        long userId = ((Number) owners.get(0).get("userId")).longValue();
        transactions.executeWithoutResult(ignored -> settle(0, userId, cid, txid, address, amount, proof));
        db.matchObservation(((Number) observation.get("id")).longValue());
    }

    private void process(long deliveryId, String raw) {
        Map<String, Object> c;
        try { c = json.readValue(raw, new TypeReference<>() { }); }
        catch (Exception invalid) { throw new IllegalStateException("CREGIS_DELIVERY_INVALID"); }
        if (!valid(c, false)) throw new IllegalStateException("CREGIS_DELIVERY_REVERIFY_FAILED");
        if (!"1".equals(String.valueOf(c.get("status")))) {
            db.finishDelivery(deliveryId, "PROVIDER_FAILED");
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
        boolean providerConfirmed = router.provider().depositsByTxid(txid).stream().anyMatch(t ->
                t.cid() == cid && t.status() == 1 && t.address().equalsIgnoreCase(address)
                        && t.amount().compareTo(amount) == 0);
        if (!providerConfirmed) throw new IllegalStateException("CREGIS_TRADE_NOT_CONFIRMED");
        BscDepositProof.Proof proof = chain.verify(txid, address, amount, blockHeight).orElse(null);
        if (proof == null) return;
        if (!config.isDepositCreditEnabled()) return;
        Map<String, Object> owner = owners.get(0);
        if (proof.blockNumber() <= ((Number) owner.get("allocationBlock")).longValue())
            throw new IllegalStateException("CREGIS_DEPOSIT_BEFORE_ALLOCATION");
        final String verifiedTxid = txid;
        transactions.executeWithoutResult(ignored -> settle(deliveryId,
                ((Number) owner.get("userId")).longValue(), cid, verifiedTxid, address, amount, proof));
    }

    private void settle(long deliveryId, long userId, long cid, String txid, String address,
                        BigDecimal amount, BscDepositProof.Proof proof) {
        List<Map<String, Object>> existing = db.lockEvent(config.getProjectId(), cid);
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (((Number) row.get("userId")).longValue() != userId
                    || !txid.equalsIgnoreCase((String) row.get("txid"))
                    || !address.equalsIgnoreCase((String) row.get("address"))
                    || ((Number) row.get("logIndex")).intValue() != proof.logIndex()
                    || ((BigDecimal) row.get("grossAmount")).compareTo(amount) != 0)
                throw new IllegalStateException("CREGIS_DEPOSIT_REPLAY_CONFLICT");
            if (deliveryId > 0) db.finishDelivery(deliveryId, String.valueOf(row.get("status")));
            return;
        }
        String status = amount.compareTo(BigDecimal.TEN) < 0 ? "DUST_HOLD"
                : amount.compareTo(new BigDecimal("100")) > 0 ? "REVIEW_HOLD" : "CREDITED";
        BigDecimal fee = "CREDITED".equals(status) ? BigDecimal.ONE : BigDecimal.ZERO;
        BigDecimal net = "CREDITED".equals(status) ? amount.subtract(fee) : BigDecimal.ZERO;
        if (db.insertEvent(userId, config.getProjectId(), cid, txid, proof.logIndex(), address, amount, fee, net,
                proof.blockNumber(), proof.blockHash(), proof.confirmations(), status) != 1)
            throw new IllegalStateException("CREGIS_EVENT_INSERT_FAILED");
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
