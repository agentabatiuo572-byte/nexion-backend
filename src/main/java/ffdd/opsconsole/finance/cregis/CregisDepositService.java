package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
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
    private final org.springframework.beans.factory.ObjectProvider<ffdd.opsconsole.finance.purchase.PurchasePaymentService> directPurchases;
    private final java.util.concurrent.ExecutorService poolWorkers = java.util.concurrent.Executors.newFixedThreadPool(10, r -> {
        Thread thread = new Thread(r,"cregis-address-pool"); thread.setDaemon(true); return thread;
    });
    private final Object allocationScanLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean poolMaintenanceRunning = new java.util.concurrent.atomic.AtomicBoolean();
    @jakarta.annotation.PreDestroy public void closePoolWorkers() {poolWorkers.shutdown();}
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CregisDepositService.class);
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
    private final SupportPaymentAttributionFacade paymentAttribution;
    private final CregisPayoutControl payouts;
    private final CregisExceptionSourceService exceptionSources;
    private final CregisReorgResolutionService reorgResolutions;
    private final CregisDepositPolicyService policies;

    public CregisDepositService(CregisProperties config, CregisGatewayRouter router,
                                BscDepositProof chain, CregisSigner signer, ObjectMapper json,
                                CregisDepositMapper db, PlatformTransactionManager txManager,
                                TreasuryLedgerRepository treasury,
                                FinanceWithdrawalControlFacade withdrawalControl,
                                SupportPaymentAttributionFacade paymentAttribution, CregisPayoutControl payouts,
                                CregisExceptionSourceService exceptionSources, CregisReorgResolutionService reorgResolutions,
                                CregisDepositPolicyService policies) {
        this(config,router,chain,signer,json,db,txManager,treasury,withdrawalControl,paymentAttribution,payouts,exceptionSources,reorgResolutions,policies,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public CregisDepositService(CregisProperties config, CregisGatewayRouter router,
                                BscDepositProof chain, CregisSigner signer, ObjectMapper json,
                                CregisDepositMapper db, PlatformTransactionManager txManager,
                                TreasuryLedgerRepository treasury,
                                FinanceWithdrawalControlFacade withdrawalControl,
                                SupportPaymentAttributionFacade paymentAttribution, CregisPayoutControl payouts,
                                CregisExceptionSourceService exceptionSources, CregisReorgResolutionService reorgResolutions,
                                CregisDepositPolicyService policies,org.springframework.beans.factory.ObjectProvider<ffdd.opsconsole.finance.purchase.PurchasePaymentService> directPurchases) {
        this.directPurchases=directPurchases;
        this.config = config;
        this.router = router;
        this.chain = chain;
        this.signer = signer;
        this.json = json;
        this.db = db;
        this.transactions = new TransactionTemplate(txManager);
        this.treasury = treasury;
        this.withdrawalControl = withdrawalControl;
        this.paymentAttribution = java.util.Objects.requireNonNull(paymentAttribution);
        this.payouts = payouts;
        this.exceptionSources = exceptionSources;
        this.reorgResolutions = reorgResolutions;
        this.policies = policies;
    }

    public Map<String, Object> address(CregisNetwork network,long userId) {
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if ((!config.depositNetworkEnabled(network) || (network==CregisNetwork.ERC20 && !config.networkConfigured(network))) || !config.isDepositEnabled() || !config.isDepositCreditEnabled()
                || config.getMode() != CregisProperties.Mode.PROVIDER)
            return Map.of("enabled", false, "creditEnabled", false,
                    "network", network.network(), "reason", "CREGIS_DEPOSIT_PAUSED");
        if(network==CregisNetwork.ERC20) {
            boolean available=router.provider().projectCoins().stream().anyMatch(coin->coin.addressEnabled()
                    && network.chainId().equals(coin.chainId()) && network.tokenId().equalsIgnoreCase(coin.tokenId()));
            if(!available) return Map.of("enabled",false,"creditEnabled",false,"network",network.network(),"reason","CREGIS_DEPOSIT_NETWORK_UNAVAILABLE");
            chain.head(network);
        }
        tripIfNeeded();
        if (config.getDepositConfirmations() < 15) throw new BizException(503, "CREGIS_CONFIRMATIONS_INVALID");
        var currentPolicy = policies.current();
        List<Map<String, Object>> rows = db.addressForUser(userId, network.chainId());
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            if (((Number) row.get("projectId")).longValue() != config.getProjectId())
                throw new BizException(409, "CREGIS_PROJECT_ADDRESS_CONFLICT");
            Map<String, Object> gate = db.provisionGate();
            if (gate == null || !"IDLE".equals(gate.get("state")) || !on(gate, "assignEnabled"))
                return Map.of("enabled", false, "creditEnabled", false,
                        "network", network.network(), "reason", "CREGIS_DEPOSIT_PAUSED");
            if ("READY".equals(row.get("state"))) return Map.of(
                    "enabled", true, "creditEnabled", config.isDepositCreditEnabled() && on(gate, "creditEnabled"),
                    "network", network.network(), "address", row.get("address"),
                    "confirmations", currentPolicy.confirmations(), "feeUsdt", currentPolicy.feeUsdt(),
                    "minDepositUsdt", currentPolicy.minDepositUsdt(),
                    "feeUsdtExact", currentPolicy.feeUsdt().toPlainString(),
                    "minDepositUsdtExact", currentPolicy.minDepositUsdt().toPlainString());
            throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        }
        Map<String, Object> visibleGate = db.provisionGate();
        if (visibleGate == null || !"IDLE".equals(visibleGate.get("state"))
                || !on(visibleGate, "assignEnabled"))
            return Map.of("enabled", false, "creditEnabled", false,
                    "network", network.network(), "reason", "CREGIS_DEPOSIT_PAUSED");
        int available=db.availableAddressCount(config.getProjectId(), network.chainId());
        if (available == 0) return preparingAddress(network);
        long finalized = chain.head(network).number() - config.getDepositConfirmations() + 1;
        if (finalized < 0) throw new BizException(503, "CREGIS_CHAIN_NOT_FINALIZED");
        Long before = db.cursor(network);
        if (before == null || before <= finalized) synchronized(allocationScanLock) {
            Long currentCursor=db.cursor(network);
            if(currentCursor==null || currentCursor<=finalized) scanTrackedAddresses(network,finalized);
        }
        // Random selection spreads simultaneous first visits; provider I/O never holds the global gate.
        Map<String,Object> selected=db.peekUnassignedAddress(config.getProjectId(),network.chainId(),
                java.util.concurrent.ThreadLocalRandom.current().nextInt(available));
        if(selected==null || selected.isEmpty()) return preparingAddress(network);
        long selectedId=((Number)selected.get("id")).longValue();
        String value=(String)selected.get("address");
        boolean clean=router.provider().addressBelongs(network.chainId(),value)
                && router.provider().zeroAddressBalance(network.currency(),value)
                && chain.zeroUsdtBalance(network,value);
        BscDepositProof.Head allocationHead=chain.head(network);
        if(allocationHead.number()<finalized
                || !allocationHead.hash().equalsIgnoreCase(chain.blockHash(network,allocationHead.number())))
            throw new IllegalStateException("CREGIS_ALLOCATION_HEAD_CHANGED");
        Instant proofDeadline=Instant.now().plusSeconds(5);
        Map<String, Object> assigned = transactions.execute(ignored -> {
            Map<String, Object> gate = db.lockProvisionGate();
            if (gate == null || !"IDLE".equals(gate.get("state")) || !on(gate, "assignEnabled"))
                throw new BizException(503, "CREGIS_ADDRESS_POOL_PAUSED");
            if (tripLocked()) return null;
            List<Map<String, Object>> current = db.addressForUser(userId, network.chainId());
            if (!current.isEmpty()) return current.get(0);
            Long cursor = db.lockCursor(network);
            if (cursor == null || cursor <= finalized || !Instant.now().isBefore(proofDeadline)) return preparingAddress(network);
            Map<String,Object> candidate=db.lockPoolCandidate(config.getProjectId(),network.chainId(),selectedId);
            if(candidate==null || candidate.isEmpty()) return preparingAddress(network);
            if(!clean || db.addressObservationCount(network,config.getProjectId(),value,finalized)!=0) {
                db.holdPoolAddress(config.getProjectId(),network.chainId(),value);db.blockProvisionGateAny();return null;
            }
            if (db.assignPoolAddress(((Number) candidate.get("id")).longValue(), userId,
                    allocationHead.number(), allocationHead.hash()) != 1)
                throw new IllegalStateException("CREGIS_ADDRESS_ASSIGN_CONFLICT");
            return Map.of("address", value, "state", "READY", "projectId", config.getProjectId());
        });
        if (assigned == null) throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        if ("CREGIS_ADDRESS_PREPARING".equals(assigned.get("reason"))) return assigned;
        if (!"READY".equals(assigned.get("state")))
            throw new BizException(409, "CREGIS_ADDRESS_REQUIRES_REVIEW");
        Map<String, Object> currentGate = db.provisionGate();
        boolean creditEnabled = config.isDepositCreditEnabled() && currentGate != null
                && "IDLE".equals(currentGate.get("state")) && on(currentGate, "assignEnabled")
                && on(currentGate, "creditEnabled");
        return Map.of("enabled", true, "creditEnabled", creditEnabled,
                "network", network.network(), "address", assigned.get("address"),
                "confirmations", currentPolicy.confirmations(), "feeUsdt", currentPolicy.feeUsdt(), "minDepositUsdt", currentPolicy.minDepositUsdt(),
                "feeUsdtExact", currentPolicy.feeUsdt().toPlainString(), "minDepositUsdtExact", currentPolicy.minDepositUsdt().toPlainString());
    }

    private Map<String,Object> preparingAddress(CregisNetwork network) {
        return Map.of("enabled", false, "creditEnabled", false, "network", network.network(),
                "reason", "CREGIS_ADDRESS_PREPARING");
    }

    /** Bounded batches; the database gate coordinates creators across backend instances. */
    @Scheduled(fixedDelayString = "${NEXION_CREGIS_POOL_MAINTENANCE_MS:10000}")
    public void maintainAddressPool() {
        if(!poolMaintenanceRunning.compareAndSet(false,true)) return;
        try { for(var network:CregisNetwork.values()) if(config.networkConfigured(network) && config.depositNetworkEnabled(network)) maintainAddressPool(network); }
        finally { poolMaintenanceRunning.set(false); }
    }
    private void maintainAddressPool(CregisNetwork network) {
        if (config.getMode() != CregisProperties.Mode.PROVIDER || !config.isDepositEnabled()
                || !config.isDepositCreditEnabled() || config.getDepositConfirmations() < 15) return;
        try {
            transactions.executeWithoutResult(ignored -> {
                if (db.lockProvisionGate() == null) return;
                if (db.expireProvisioning(config.getProjectId(), network.chainId()) > 0
                        || db.unknownAddressCount(config.getProjectId(), network.chainId()) > 0) db.blockProvisionGateAny();
            });
            Map<String,Object> gate = db.provisionGate();
            if (gate == null || !on(gate,"assignEnabled") || !on(gate,"creditEnabled")
                    || !java.util.Set.of("IDLE","BUSY").contains(String.valueOf(gate.get("state")))) return;
            var policy=CregisAddressPoolPolicy.from(db.poolPolicy(network));
            long finalized=chain.head(network).number()-config.getDepositConfirmations()+1;
            var candidates=new java.util.concurrent.ConcurrentLinkedQueue<>(db.verifyingCandidates(config.getProjectId(),network.chainId(),policy.batchSize()));
            parallelPoolWork(policy.concurrency(),()->{
                Map<String,Object> candidate;
                while((candidate=candidates.poll())!=null) {
                    try {verifyPoolCandidate(network,candidate,finalized);}
                    catch(RuntimeException failure) {log.warn("Cregis candidate verification deferred: {}",safeReason(failure));}
                }
            });
            enqueuePoolAddresses(network,true);
            dispatchQueuedAddresses(network,policy);
        } catch (RuntimeException failure) {
            log.warn("Cregis address pool maintenance deferred: {}", safeReason(failure));
        }
    }

    private void parallelPoolWork(int concurrency,Runnable worker) {
        var work=new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for(int i=0;i<concurrency;i++) work.add(java.util.concurrent.CompletableFuture.runAsync(worker,poolWorkers));
        java.util.concurrent.CompletableFuture.allOf(work.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }

    public Map<String, Object> provisionPoolAddress(CregisNetwork network) {
        int added=enqueuePoolAddresses(network,false);
        if(added==0) throw new BizException(409,"CREGIS_PROVISION_GATE_BLOCKED");
        return Map.of("state","QUEUED","count",added);
    }

    private int enqueuePoolAddresses(CregisNetwork network,boolean automatic) {
        if (!config.depositNetworkEnabled(network) || !config.networkConfigured(network) || config.getMode() != CregisProperties.Mode.PROVIDER || config.getProjectId() <= 0
                || config.getDepositConfirmations() < 15) throw new BizException(503,"CREGIS_PROVISION_NOT_CONFIGURED");
        return transactions.execute(ignored -> {
            Map<String,Object> gate=db.lockProvisionGate();
            if(gate==null || !"IDLE".equals(gate.get("state")) || !on(gate,"assignEnabled") || !on(gate,"creditEnabled")
                    || tripLocked()) return 0;
            var raw=db.poolPolicy(network);var policy=CregisAddressPoolPolicy.from(raw);
            if((automatic && !policy.enabled()) || submissionCoolingDown(raw)) return 0;
            int available=db.availableAddressCount(config.getProjectId(),network.chainId());
            boolean active=on(raw,"refillActive") || available<policy.lowWatermark();
            if(available>=policy.target()) {db.setPoolRefillActive(network,false);return 0;}
            if(automatic && !active) return 0;
            int count=Math.min(automatic?policy.batchSize():1,Math.max(0,policy.target()-available-db.outstandingAddressCount(config.getProjectId(),network.chainId())));
            if(active) db.setPoolRefillActive(network,true);
            for(int i=0;i<count;i++) if(db.enqueuePoolAddress(config.getProjectId(),network.chainId(),UUID.randomUUID().toString())!=1)
                throw new IllegalStateException("CREGIS_ADDRESS_QUEUE_INSERT_FAILED");
            return count;
        });
    }

    private void dispatchQueuedAddresses(CregisNetwork network,CregisAddressPoolPolicy batchPolicy) {
        var remaining=new java.util.concurrent.atomic.AtomicInteger(batchPolicy.batchSize());
        parallelPoolWork(batchPolicy.concurrency(),()->{
            while(remaining.getAndDecrement()>0) {
                try {if(!submitQueuedAddress(network)) return;}
                catch(RuntimeException failure) {log.warn("Cregis address submission deferred: {}",safeReason(failure));return;}
            }
        });
    }

    private boolean submitQueuedAddress(CregisNetwork network) {
        if(submissionCoolingDown(db.poolPolicy(network))) return false;
        if(db.queuedAddressCount(config.getProjectId(),network.chainId())==0) return false;
        boolean coinReady=router.provider().projectCoins().stream().anyMatch(coin->coin.addressEnabled()
                && network.chainId().equals(coin.chainId()) && network.tokenId().equalsIgnoreCase(coin.tokenId()));
        if(!coinReady) throw new BizException(503,"CREGIS_PROJECT_COIN_UNAVAILABLE");
        long creationBlock=chain.head(network).number();
        Map<String,Object> request=transactions.execute(ignored->{
            var gate=db.lockProvisionGate();
            if(gate==null || !"IDLE".equals(gate.get("state")) || !on(gate,"assignEnabled") || !on(gate,"creditEnabled") || tripLocked()) return null;
            var raw=db.poolPolicy(network);var policy=CregisAddressPoolPolicy.from(raw);
            if(submissionCoolingDown(raw) || db.submittingAddressCount(config.getProjectId(),network.chainId())>=policy.concurrency()) return null;
            var row=db.lockQueuedAddress(config.getProjectId(),network.chainId());
            if(row==null || row.isEmpty()) return null;
            if(db.availableAddressCount(config.getProjectId(),network.chainId())+db.pendingAddressCount(config.getProjectId(),network.chainId())>=policy.target()) {
                db.cancelQueuedAddress(((Number)row.get("id")).longValue());return null;
            }
            if(db.claimQueuedAddress(((Number)row.get("id")).longValue(),creationBlock)!=1)
                throw new IllegalStateException("CREGIS_ADDRESS_QUEUE_CLAIM_FAILED");
            return row;
        });
        if(request==null) return false;
        String requestId=(String)request.get("requestId");
        try {
            CregisGateway.Address created=router.provider().createAddress(network.chainId(),"UVEL USDT "+network.network(),router.depositCallbackUrl(),requestId);
            if(!network.chainId().equals(created.chainId()) || created.address()==null || !created.address().matches("(?i)0x[0-9a-f]{40}"))
                throw new IllegalStateException("CREGIS_ADDRESS_CREATE_INVALID");
            if(db.recordPoolCandidate(requestId,created.address().toLowerCase(Locale.ROOT))!=1)
                throw new IllegalStateException("CREGIS_ADDRESS_CANDIDATE_CONFLICT");
            return true;
        } catch(RuntimeException failure) {
            transactions.executeWithoutResult(ignored->{
                db.lockProvisionGate();
                if(failure instanceof CregisGatewayException rejected && rejected.kind()==CregisGatewayException.Kind.REJECTED) {
                    db.rejectPoolAttempt(requestId);
                    db.pausePoolSubmission(network,safeReason(failure),"CREGIS_ADDRESS_RATE_LIMITED".equals(failure.getMessage())?60:900);
                } else {db.unknownPoolAttempt(requestId);db.blockProvisionGateAny();}
            });
            throw failure;
        }
    }

    private boolean submissionCoolingDown(Map<String,Object> raw) {
        return raw!=null && raw.get("retryAfter") instanceof Number until && until.longValue()>System.currentTimeMillis();
    }

    public List<Map<String, Object>> deposits(long userId) {
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return List.of();
        if (config.getDepositConfirmations() < 15) throw new BizException(503, "CREGIS_CONFIRMATIONS_INVALID");
        return db.deposits(userId, config.getProjectId(), config.getDepositConfirmations()).stream().map(row -> {
            Map<String, Object> result = new java.util.LinkedHashMap<>(row);
            result.put("grossAmountUsdtExact", ((BigDecimal) row.get("grossAmountUsdt")).toPlainString());
            result.put("creditedUsdtExact", ((BigDecimal) row.get("creditedUsdt")).toPlainString());
            return result;
        }).toList();
    }

    public Map<String, Object> adminDeposits(long beforeId, int limit) {
        if (beforeId < 0 || limit < 1 || limit > 50)
            throw new BizException(400, "CREGIS_DEPOSIT_PAGE_INVALID");
        if (config.getMode() != CregisProperties.Mode.PROVIDER)
            return Map.of("available", false, "items", List.of(), "hasMore", false, "nextBeforeId", "0");
        List<Map<String, Object>> rows = db.adminDeposits(config.getProjectId(), beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Map<String, Object>> items = hasMore ? rows.subList(0, limit) : rows;
        String nextBeforeId = items.isEmpty() ? "0" : String.valueOf(items.get(items.size() - 1).get("id"));
        return Map.of("available", true, "items", items, "hasMore", hasMore, "nextBeforeId", nextBeforeId);
    }

    public Map<String, Object> exceptions() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return Map.ofEntries(
                Map.entry("mode", "DISABLED"), Map.entry("depositEnabled", false),
                Map.entry("depositCreditEnabled", false),
                Map.entry("providerPayout", payouts.capability()),
                Map.entry("addressPool", Map.of("available", false, "total", 0, "unassigned", 0,
                        "assigned", 0, "verifying", 0, "unknown", 0, "limit", 0)),
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
        status.put("providerPayout", payouts.capability());
        status.put("networkCapabilities",payouts.networkCapabilities());
        Map<String, Object> addressPool = new java.util.LinkedHashMap<>(db.addressPool(config.getProjectId(), CregisNetwork.BEP20.chainId()));
        addressPool.put("available", true);
        addressPool.put("limit", 0); // Zero means no account/address-count cap.
        var poolRaw=db.poolPolicy(CregisNetwork.BEP20);
        var poolPolicy=CregisAddressPoolPolicy.from(poolRaw);
        addressPool.put("reserveTarget",poolPolicy.target());
        addressPool.put("lowWatermark",poolPolicy.lowWatermark());
        addressPool.put("batchSize",poolPolicy.batchSize());
        addressPool.put("concurrency",poolPolicy.concurrency());
        addressPool.put("lastError",poolRaw.get("lastError"));
        addressPool.put("retryAfter",poolRaw.get("retryAfter"));
        addressPool.put("automatic",poolPolicy.enabled() && config.isDepositEnabled() && config.isDepositCreditEnabled());
        status.put("addressPool", addressPool);
        var addressPools=new java.util.LinkedHashMap<String,Object>();
        for(var network:CregisNetwork.values()) {
            var pool=new java.util.LinkedHashMap<>(network==CregisNetwork.BEP20?addressPool:db.addressPool(config.getProjectId(),network.chainId()));
            pool.put("network",network.network()); pool.put("configured",config.networkConfigured(network));
            var raw=db.poolPolicy(network);
            if(raw==null || raw.isEmpty()) {pool.put("available",false);addressPools.put(network.network(),pool);continue;}
            var policy=CregisAddressPoolPolicy.from(raw);
            pool.put("reserveTarget",policy.target());pool.put("lowWatermark",policy.lowWatermark());
            pool.put("batchSize",policy.batchSize());pool.put("concurrency",policy.concurrency());
            pool.put("lastError",raw.get("lastError"));pool.put("retryAfter",raw.get("retryAfter"));
            pool.put("available",config.networkConfigured(network));pool.put("limit",0);
            pool.put("automatic",policy.enabled() && config.isDepositEnabled() && config.isDepositCreditEnabled() && config.depositNetworkEnabled(network));
            addressPools.put(network.network(),pool);
        }
        status.put("addressPools",addressPools);
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
        boolean ipValid = config.allowsCallbackSourceIp(sourceIp);
        boolean accepted = timestampValid && signatureValid && ipValid && valid(callback, true);
        if(accepted&&directPurchases!=null&&directPurchases.getObject().observeCregisCallback(callback))return "success";
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
            if (accepted) policies.discover(CregisNetwork.fromAsset(String.valueOf(parsed.get("chain_id")),String.valueOf(parsed.get("token_id"))),config.getProjectId(), normalizedTxid);
            db.insertDelivery(sha256(payload), payload, accepted ? ((Number) parsed.get("cid")).longValue() : null,
                    normalizedTxid, accepted ? String.valueOf(parsed.get("address")).toLowerCase(Locale.ROOT) : null,
                    accepted ? CregisAmount.parsePositive((String) parsed.get("amount")) : null,
                    accepted ? 1 : 0, reason, peer, verifiedSignature ? 1 : 0,
                    timestampValid ? 1 : 0, ipValid ? 1 : 0);
        });
        return accepted ? "success" : "rejected";
    }

    private boolean valid(Map<String, Object> c, boolean requireFresh) {
        if (c == null || config.getProjectId() <= 0 || config.getApiKey() == null
                || config.getApiKey().isBlank()) return false;
        CregisNetwork network;
        try { network=CregisNetwork.fromAsset(String.valueOf(c.get("chain_id")),String.valueOf(c.get("token_id"))); }
        catch(IllegalArgumentException unsupported) { return false; }
        Object pid = c.get("pid"), cid = c.get("cid"), timestamp = c.get("timestamp");
        if (!(pid instanceof Long || pid instanceof Integer) || ((Number) pid).longValue() != config.getProjectId()
                || !(cid instanceof Long || cid instanceof Integer) || ((Number) cid).longValue() <= 0
                || !(timestamp instanceof Long || timestamp instanceof Integer)
                || (requireFresh && ((Number) timestamp).longValue() < Instant.now().toEpochMilli() - 300_000)
                || (requireFresh && ((Number) timestamp).longValue() > Instant.now().toEpochMilli() + 300_000)
                || !network.chainId().equals(c.get("chain_id"))
                || !network.tokenId().equalsIgnoreCase(String.valueOf(c.get("token_id")))
                || !("USDT".equalsIgnoreCase(String.valueOf(c.get("currency")))
                    || network.policyTokenId().equalsIgnoreCase(String.valueOf(c.get("currency")))
                    || network.currency().equalsIgnoreCase(String.valueOf(c.get("currency"))))
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
        RuntimeException scanFailure=null;
        for(var network:CregisNetwork.values()) {
            if(!config.networkConfigured(network)) continue;
            try { long finalized=chain.head(network).number()-config.getDepositConfirmations()+1;
                scanTrackedAddresses(network,finalized);
            } catch(RuntimeException failure) { scanFailure=failure;log.warn("Cregis {} scan deferred: {}",network,safeReason(failure)); }
        }
        for (Map<String, Object> observation : db.missingObservations(config.getProjectId())) {
            try { reconcileObservation(observation); }
            catch (RuntimeException unresolved) {
                db.touchObservation(((Number) observation.get("id")).longValue(), safeReason(unresolved));
            }
        }
        if(scanFailure!=null) throw scanFailure;
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_CANONICAL_RECHECK_MS:30000}")
    public void recheckCanonical() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        for (Map<String, Object> event : db.canonicalPending(config.getProjectId())) {
            CregisNetwork network=networkOf(event);
            if(!config.networkConfigured(network)) continue;
            try {
            long finalized=chain.head(network).number()-config.getDepositConfirmations()+1;
            long last = ((Number) event.get("lastCheckedBlock")).longValue();
            long next = Math.min(Math.min(last + 20, ((Number) event.get("canonicalUntil")).longValue()), finalized);
            if (next <= last) continue;
                BscDepositProof.Proof proof = chain.verify(network,(String) event.get("txid"),
                        (String) event.get("address"), (BigDecimal) event.get("grossAmount"),
                        ((Number) event.get("blockNumber")).longValue()).orElse(null);
                if (proof == null || proof.logIndex() != ((Number) event.get("logIndex")).intValue()
                        || !proof.blockHash().equalsIgnoreCase((String) event.get("blockHash"))) {
                    creditedIncident(network,event, "REORG_INVESTIGATING");
                    continue;
                }
                db.advanceCanonical(((Number) event.get("id")).longValue(), last, next, chain.blockHash(network,next));
            } catch (IllegalStateException failure) {
                if (!"CREGIS_BSC_PROOF_UNAVAILABLE".equals(failure.getMessage()))
                    creditedIncident(network,event, "REORG_INVESTIGATING");
            }
        }
    }

    private void creditedIncident(CregisNetwork network,Map<String, Object> event, String kind) {
        long cid = ((Number) event.get("cid")).longValue();
        // Keep the gate closed even if a downstream audit or wallet freeze fails.
        db.blockProvisionGateAny();
        transactions.executeWithoutResult(ignored -> {
            List<Map<String, Object>> locked = db.lockEvent(network,config.getProjectId(), cid);
            if (locked.size() != 1) throw new IllegalStateException("CREGIS_INCIDENT_EVENT_MISSING");
            freezeLockedCreditedEvent(locked.get(0), cid, kind);
        });
    }

    private void freezeLockedCreditedEvent(Map<String, Object> current, long cid, String kind) {
        if (!"CREDITED".equals(current.get("status"))) return;
        db.blockProvisionGateAny();
        long id = ((Number) current.get("id")).longValue();
        long userId = ((Number) current.get("userId")).longValue();
        var holdBefore = reorgResolutions.beforeIncident(userId);
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
            }
        }
        if (db.insertIncident(id, config.getProjectId(), cid, userId, kind, held) != 1)
            throw new IllegalStateException("CREGIS_INCIDENT_RECORD_FAILED");
        if (held.signum() > 0) {
            Long incidentId=db.latestIncidentId(id,kind);
            if (incidentId==null || incidentId<=0 || db.insertRiskHoldLedger("CR-" + cid + ":RISK:" + incidentId,
                    userId,held,((BigDecimal)wallet.get("usdtAvailable")).subtract(held),kind)!=1)
                throw new IllegalStateException("CREGIS_INCIDENT_LEDGER_FAILED");
        }
        reorgResolutions.recordIncident(id, kind, held, holdBefore);
    }

    void scanTrackedAddresses(CregisNetwork network,long finalized) {
        Map<String,Object> bounds = db.allocationBounds(config.getProjectId(), network.chainId());
        if (bounds == null || bounds.get("maxId") == null) {
            // An empty topup scan set is complete even when all proven pool addresses now belong to direct purchases.
            db.ensureCursor(network,finalized+1);Long cursor=db.cursor(network);
            if(cursor!=null&&cursor<=finalized)db.advanceCursor(network,finalized+1,cursor);
            return;
        }
        if (((Number) bounds.get("missingBlocks")).longValue() > 0) {
            db.blockProvisionGateAny();
            throw new IllegalStateException("CREGIS_ADDRESS_CREATION_BLOCK_MISSING");
        }
        long first = ((Number) bounds.get("firstBlock")).longValue();
        long maxId = ((Number) bounds.get("maxId")).longValue();
        db.ensureCursor(network,first);
        long cursor = db.cursor(network);
        long from = Math.max(first, cursor - 100);
        long to = Math.min(cursor + (long) config.getChainScanBlocks() - 101, finalized);
        if (to >= from) {
            long afterId = 0;
            while (afterId < maxId) {
                List<Map<String,Object>> allocations = db.allocations(config.getProjectId(), network.chainId(), afterId, maxId);
                if (allocations.isEmpty()) throw new IllegalStateException("CREGIS_ADDRESS_SCAN_PAGE_MISSING");
                List<String> addresses = allocations.stream().map(row -> (String) row.get("address")).toList();
                for (BscDepositProof.Observation log : chain.scan(network,from, to, addresses)) {
                    Map<String,Object> owner = allocations.stream()
                            .filter(row -> log.address().equalsIgnoreCase((String) row.get("address")))
                            .findFirst().orElseThrow();
                    observePoolLog(network,log, owner);
                }
                afterId = ((Number) allocations.get(allocations.size()-1).get("id")).longValue();
            }
            db.advanceCursor(network,Math.max(cursor, to + 1), cursor);
        }
        verifyPoolCandidate(network,finalized);
    }

    private void observePoolLog(CregisNetwork network,BscDepositProof.Observation log, Map<String,Object> owner) {
        boolean assigned = "READY".equals(owner.get("state"))
                && log.blockNumber() > ((Number) owner.get("allocationBlock")).longValue();
        String issue = transactions.execute(ignored -> {
            if (db.lockProvisionGate() == null)
                throw new IllegalStateException("CREGIS_RISK_GATE_MISSING");
            // A scan page may predate the atomic direct-purpose allocation of this previously unassigned address.
            if(directPurchases!=null&&directPurchases.getObject().ownsCregisAddress(network,log.address()))return null;
            policies.discover(network,config.getProjectId(), log.txid());
            Map<String, Object> observed = db.observationByLog(network,config.getProjectId(),
                    log.txid(), log.logIndex());
            if (observed != null) {
                if (!log.address().equalsIgnoreCase((String) observed.get("address"))
                        || new BigDecimal((String) observed.get("rawAmount")).compareTo(log.amount()) != 0
                        || ((Number) observed.get("blockNumber")).longValue() != log.blockNumber()) {
                    db.blockProvisionGateAny();
                    return "CREGIS_CHAIN_OBSERVATION_CONFLICT";
                }
            } else if (db.insertObservation(network,config.getProjectId(), log.txid(), log.logIndex(),
                    log.address(), log.amount().toPlainString(), log.blockNumber(),
                    assigned ? "PROVIDER_MISSING" : "UNATTRIBUTED_HOLD") != 1)
                throw new IllegalStateException("CREGIS_CHAIN_OBSERVATION_INSERT_FAILED");
            if (!assigned && (observed == null || !java.util.Set.of("MATCHED", "REFUNDED")
                    .contains(String.valueOf(observed.get("status"))))) {
                db.holdPoolAddress(config.getProjectId(), network.chainId(), log.address());
                db.blockProvisionGateAny();
            }
            tripLocked();
            return null;
        });
        if (issue != null) throw new IllegalStateException(issue);
    }

    private void verifyPoolCandidate(CregisNetwork network,long finalized) {
        Map<String, Object> candidate = db.verifyingCandidate(config.getProjectId(), network.chainId());
        if (candidate == null || candidate.isEmpty()) return;
        verifyPoolCandidate(network,candidate,finalized);
    }

    private void verifyPoolCandidate(CregisNetwork network,Map<String,Object> candidate,long finalized) {
        long creation = ((Number) candidate.get("creationBlock")).longValue();
        long checkpoint = ((Number) candidate.get("verificationBlock")).longValue();
        if (finalized < creation) return;
        long from = Math.max(creation, checkpoint - 100);
        long to = Math.min(checkpoint + (long) config.getChainScanBlocks() - 101, finalized);
        String address = (String) candidate.get("address");
        if (to >= from) {
            for (var observation : chain.scan(network,from, to, List.of(address))) {
                if (!address.equalsIgnoreCase(observation.address()))
                    throw new IllegalStateException("CREGIS_CANDIDATE_SCAN_ADDRESS_MISMATCH");
                observePoolLog(network,observation, Map.of("state", "VERIFYING"));
            }
            long next = Math.max(checkpoint, to + 1);
            if (db.advanceCandidateVerification(((Number) candidate.get("id")).longValue(), checkpoint, next) != 1) return;
            checkpoint = next;
        }
        if (checkpoint <= finalized) return;
        if (db.addressObservationCount(network,config.getProjectId(), address, finalized) != 0
                || !router.provider().addressBelongs(network.chainId(), address)
                || !router.provider().zeroAddressBalance(network.currency(), address)
                || !chain.zeroUsdtBalance(network,address)) {
            db.holdPoolAddress(config.getProjectId(), network.chainId(), address);
            db.blockProvisionGateAny();
            return;
        }
        transactions.executeWithoutResult(ignored -> {
            Map<String,Object> gate = db.lockProvisionGate();
            if (gate == null || !java.util.Set.of("IDLE","BUSY").contains(String.valueOf(gate.get("state")))
                    || !on(gate,"assignEnabled")) return;
            db.readyPoolCandidate(((Number) candidate.get("id")).longValue());
            // Complete a legacy in-flight candidate after upgrading from the old BUSY workflow.
            if ("BUSY".equals(gate.get("state"))) db.releaseProvisionGate();
        });
    }

    private void reconcileObservation(Map<String, Object> observation) {
        CregisNetwork network=networkOf(observation);
        String txid = (String) observation.get("txid");
        String address = (String) observation.get("address");
        BigDecimal amount = new BigDecimal((String) observation.get("rawAmount"));
        if (amount.signum() <= 0 || amount.scale() > 6 || amount.precision() > 18)
            throw new IllegalStateException("CREGIS_CHAIN_AMOUNT_REQUIRES_REVIEW");
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), network.chainId(), address);
        if (owners.size() != 1) throw new IllegalStateException("CREGIS_CHAIN_ADDRESS_UNATTRIBUTED");
        long height = ((Number) observation.get("blockNumber")).longValue();
        if (height <= ((Number) owners.get(0).get("allocationBlock")).longValue())
            throw new IllegalStateException("CREGIS_CHAIN_BEFORE_ALLOCATION");
        requireAllocationAnchor(network,owners.get(0));
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(network,txid).stream()
                .filter(t -> network.chainId().equals(t.chainId()) && network.tokenId().equalsIgnoreCase(t.tokenId()) && t.status() == 1 && t.address().equalsIgnoreCase(address)
                        && t.amount().compareTo(amount) == 0).toList();
        if (trades.size() != 1) {
            db.touchObservation(((Number) observation.get("id")).longValue(), "CREGIS_TRADE_MISSING");
            return;
        }
        BscDepositProof.Proof proof;
        try { proof = chain.verify(network,txid, address, amount, height).orElse(null); }
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
                settleOrHold(network,0, userId, cid, txid, address, amount, proof))))
            db.matchObservation(((Number) observation.get("id")).longValue());
    }

    private void process(long deliveryId, String raw) {
        Map<String, Object> c;
        try { c = json.readValue(raw, new TypeReference<>() { }); }
        catch (Exception invalid) { throw new IllegalStateException("CREGIS_DELIVERY_INVALID"); }
        if (!valid(c, false)) throw new IllegalStateException("CREGIS_DELIVERY_REVERIFY_FAILED");
        CregisNetwork network=CregisNetwork.fromAsset(String.valueOf(c.get("chain_id")),String.valueOf(c.get("token_id")));
        if (!"1".equals(String.valueOf(c.get("status")))) {
            long cid = ((Number) c.get("cid")).longValue();
            List<Map<String, Object>> existing = db.lockEvent(network,config.getProjectId(), cid);
            if (!existing.isEmpty() && "CREDITED".equals(existing.get(0).get("status"))) {
                creditedIncident(network,Map.of("cid", cid), "PROVIDER_CONFLICT_HOLD");
                db.finishDelivery(deliveryId, "PROVIDER_CONFLICT_HOLD");
            } else transactions.executeWithoutResult(ignored -> {
                db.blockProvisionGateAny();
                db.finishDelivery(deliveryId, "PROVIDER_FAILED_HOLD");
            });
            return;
        }
        String address = String.valueOf(c.get("address")).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), network.chainId(), address);
        if (owners.size() != 1) throw new IllegalStateException("CREGIS_ADDRESS_UNATTRIBUTED");
        String txid = String.valueOf(c.get("txid")).toLowerCase(Locale.ROOT);
        if (!txid.startsWith("0x")) txid = "0x" + txid;
        BigDecimal amount = CregisAmount.parsePositive((String) c.get("amount"));
        long cid = ((Number) c.get("cid")).longValue();
        long blockHeight = Long.parseLong(String.valueOf(c.get("block_height")));
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(network,txid);
        boolean providerConfirmed = trades.size() == 1 && network.chainId().equals(trades.get(0).chainId()) && network.tokenId().equalsIgnoreCase(trades.get(0).tokenId()) && trades.get(0).cid() == cid
                && trades.get(0).status() == 1 && trades.get(0).address().equalsIgnoreCase(address)
                && trades.get(0).amount().compareTo(amount) == 0;
        if (!providerConfirmed) {
            if (!trades.isEmpty()) {
                List<Map<String, Object>> credited = db.lockEvent(network,config.getProjectId(), cid);
                if (!credited.isEmpty() && "CREDITED".equals(credited.get(0).get("status")))
                    creditedIncident(network,Map.of("cid", cid), "PROVIDER_CONFLICT_HOLD");
                db.blockProvisionGateAny();
                db.finishDelivery(deliveryId, "PROVIDER_MISMATCH_HOLD");
                return;
            }
            throw new IllegalStateException("CREGIS_TRADE_NOT_CONFIRMED");
        }
        BscDepositProof.Proof proof;
        try { proof = chain.verify(network,txid, address, amount, blockHeight).orElse(null); }
        catch (IllegalStateException failure) {
            if ("CREGIS_BSC_PROOF_UNAVAILABLE".equals(failure.getMessage())) throw failure;
            List<Map<String, Object>> credited = db.lockEvent(network,config.getProjectId(), cid);
            if (!credited.isEmpty() && "CREDITED".equals(credited.get(0).get("status")))
                creditedIncident(network,Map.of("cid", cid), "REORG_INVESTIGATING");
            db.blockProvisionGateAny();
            db.finishDelivery(deliveryId, "CHAIN_MISMATCH_HOLD");
            return;
        }
        if (proof == null) return;
        Map<String, Object> owner = owners.get(0);
        if (proof.blockNumber() <= ((Number) owner.get("allocationBlock")).longValue())
            throw new IllegalStateException("CREGIS_DEPOSIT_BEFORE_ALLOCATION");
        requireAllocationAnchor(network,owner);
        final String verifiedTxid = txid;
        transactions.execute(ignored -> settleOrHold(network,deliveryId,
                ((Number) owner.get("userId")).longValue(), cid, verifiedTxid, address, amount, proof));
    }

    void discoverProviderRow(CregisGateway.DepositRow row) {
        CregisNetwork network=CregisNetwork.fromAsset(row.chainId(),row.tokenId());
        if(directPurchases!=null&&directPurchases.getObject().ownsCregisAddress(network,row.address()))return;
        policies.discover(network,config.getProjectId(),row.txid());
    }

    /** Reuses the same provider, allocation and BSC proof path for a missed callback. */
    boolean materializeProviderRow(CregisGateway.DepositRow row) {
        CregisNetwork network=CregisNetwork.fromAsset(row.chainId(),row.tokenId());
        if(directPurchases!=null){Boolean handled=directPurchases.getObject().materializeCregisProviderRow(row);if(handled!=null)return handled;}
        if (row.status() != 1) return true;
        policies.discover(network,config.getProjectId(), row.txid());
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), network.chainId(), row.address());
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
        requireAllocationAnchor(network,owner);
        List<CregisGateway.DepositTrade> fresh = router.provider().depositsByTxid(network,row.txid());
        if (fresh.size() != 1 || !network.chainId().equals(fresh.get(0).chainId()) || !network.tokenId().equalsIgnoreCase(fresh.get(0).tokenId()) || fresh.get(0).cid() != row.cid()
                || fresh.get(0).status() != 1 || !fresh.get(0).address().equals(row.address())
                || fresh.get(0).amount().compareTo(row.amount()) != 0) {
            db.blockProvisionGateAny();
            return false;
        }
        BscDepositProof.Proof proof = chain.verify(network,row.txid(), row.address(), row.amount(),
                row.blockHeight()).orElse(null);
        if (proof == null) return false;
        Map<String, Object> observed = db.observationByLog(network,config.getProjectId(),
                row.txid(), proof.logIndex());
        if (observed == null || !row.address().equalsIgnoreCase((String) observed.get("address"))
                || row.amount().compareTo(new BigDecimal((String) observed.get("rawAmount"))) != 0
                || row.blockHeight() != ((Number) observed.get("blockNumber")).longValue()) return false;
        if (!"PROVIDER_MISSING".equals(observed.get("status"))
                && !"MATCHED".equals(observed.get("status"))) return false;
        if (db.disputedDeliveryCount(row.cid()) != 0) return false;
        long userId = ((Number) owner.get("userId")).longValue();
        boolean handled = Boolean.TRUE.equals(transactions.execute(ignored ->
                settleOrHold(network,0, userId, row.cid(), row.txid(), row.address(), row.amount(), proof)));
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
                CregisNetwork network=networkOf(event);
            afterId = ((Number) event.get("id")).longValue();
            if (!"CREDITED".equals(event.get("status"))) continue;
            long cid = ((Number) event.get("cid")).longValue();
            String txid = (String) event.get("txid");
            List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(network,txid);
            boolean exact = trades.size() == 1 && network.chainId().equals(trades.get(0).chainId()) && network.tokenId().equalsIgnoreCase(trades.get(0).tokenId()) && trades.get(0).cid() == cid
                    && trades.get(0).status() == 1
                    && trades.get(0).address().equalsIgnoreCase((String) event.get("address"))
                    && trades.get(0).amount().compareTo((BigDecimal) event.get("grossAmount")) == 0;
            if (exact) continue;
            String key = "credited-provider-missing-" + cid;
            if (db.insertRiskAlert(config.getProjectId(), key, "P0", "PLATFORM_ORPHAN",
                    "cid=" + cid + ",txid=" + txid) == 0) {
                Long age = db.riskAlertAgeSeconds(config.getProjectId(), key);
                if (age != null && age >= 300) creditedIncident(network,Map.of("cid", cid), "PROVIDER_CONFLICT_HOLD");
            }
            throw new IllegalStateException("CREGIS_CREDITED_PROVIDER_MISMATCH");
            }
        }
    }

    record ReviewProof(long eventId, long cid, long userId, String txid, String address,
                       BigDecimal amount, long blockNumber, String blockHash, int logIndex,
                       int confirmations, long allocationBlock, String allocationHash,
                       Instant checkedAt, long policyVersion, BigDecimal feeAmount, int requiredConfirmations,CregisNetwork network) {
        ReviewProof(long eventId,long cid,long userId,String txid,String address,BigDecimal amount,long blockNumber,String blockHash,int logIndex,int confirmations,long allocationBlock,String allocationHash,Instant checkedAt) {
            this(eventId,cid,userId,txid,address,amount,blockNumber,blockHash,logIndex,confirmations,allocationBlock,allocationHash,checkedAt,0,BigDecimal.ONE,15,CregisNetwork.BEP20);
        }
        ReviewProof(long eventId,long cid,long userId,String txid,String address,BigDecimal amount,long blockNumber,String blockHash,int logIndex,int confirmations,long allocationBlock,String allocationHash,Instant checkedAt,long policyVersion,BigDecimal feeAmount,int requiredConfirmations) {
            this(eventId,cid,userId,txid,address,amount,blockNumber,blockHash,logIndex,confirmations,allocationBlock,allocationHash,checkedAt,policyVersion,feeAmount,requiredConfirmations,CregisNetwork.BEP20);
        }
        String evidenceHash() {
            return sha256(String.join("|", "CREGIS_REVIEW_V2", network.network(),Long.toString(eventId),
                    Long.toString(cid), Long.toString(userId), txid.toLowerCase(Locale.ROOT),
                    address.toLowerCase(Locale.ROOT), amount.stripTrailingZeros().toPlainString(),
                    Long.toString(blockNumber), blockHash.toLowerCase(Locale.ROOT),
                    Integer.toString(logIndex), Long.toString(allocationBlock),
                    allocationHash.toLowerCase(Locale.ROOT), Long.toString(policyVersion), feeAmount.stripTrailingZeros().toPlainString(), Integer.toString(requiredConfirmations)));
        }

        Map<String, Object> summary() {
            return Map.ofEntries(Map.entry("network",network.network()),Map.entry("eventId", eventId), Map.entry("cid", cid),
                    Map.entry("userId", userId), Map.entry("txid", txid),
                    Map.entry("address", address), Map.entry("grossAmount", amount),
                    Map.entry("feeAmount", feeAmount), Map.entry("policyVersion",policyVersion),
                    Map.entry("proposedNetAmount", amount.subtract(feeAmount)),
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
        CregisNetwork network=networkOf(event);
        long cid = ((Number) event.get("cid")).longValue();
        long userId = ((Number) event.get("userId")).longValue();
        String txid = (String) event.get("txid"), address = (String) event.get("address");
        BigDecimal amount = (BigDecimal) event.get("grossAmount");
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), network.chainId(), address);
        if (owners.size() != 1 || ((Number) owners.get(0).get("userId")).longValue() != userId)
            throw new IllegalStateException("CREGIS_REVIEW_OWNER_MISMATCH");
        Map<String, Object> owner = owners.get(0);
        requireAllocationAnchor(network,owner);
        List<CregisGateway.DepositTrade> trades = router.provider().depositsByTxid(network,txid);
        if (trades.size() != 1 || !network.chainId().equals(trades.get(0).chainId()) || !network.tokenId().equalsIgnoreCase(trades.get(0).tokenId()) || trades.get(0).cid() != cid || trades.get(0).status() != 1
                || !trades.get(0).address().equalsIgnoreCase(address)
                || trades.get(0).amount().compareTo(amount) != 0)
            throw new IllegalStateException("CREGIS_REVIEW_PROVIDER_MISMATCH");
        long blockNumber = ((Number) event.get("blockNumber")).longValue();
        BscDepositProof.Proof chainProof = chain.verify(network,txid, address, amount, blockNumber).orElse(null);
        if (chainProof == null || chainProof.logIndex() != ((Number) event.get("logIndex")).intValue()
                || !chainProof.blockHash().equalsIgnoreCase((String) event.get("blockHash")))
            throw new IllegalStateException("CREGIS_REVIEW_CHAIN_MISMATCH");
        var policy = policies.snapshot(network,config.getProjectId(),txid);
        if (chainProof.confirmations() < policy.confirmations()) throw new IllegalStateException("CREGIS_REVIEW_CHAIN_NOT_FINAL");
        return new ReviewProof(eventId, cid, userId, txid, address, amount, blockNumber,
                chainProof.blockHash(), chainProof.logIndex(), chainProof.confirmations(),
                ((Number) owner.get("allocationBlock")).longValue(), (String) owner.get("allocationHash"),
                Instant.now(),policy.version(),policy.feeUsdt(),policy.confirmations(),network);
    }

    /** Called only inside a checked review-case transaction with a fresh external proof. */
    void creditReviewedHold(long cid, ReviewProof proof) {
        if (config.getMode() != CregisProperties.Mode.PROVIDER || !config.isDepositCreditEnabled())
            throw new IllegalStateException("CREGIS_CREDIT_DISABLED");
        requireReviewProofFresh(proof);
        CregisNetwork network=proof.network();
        if(!config.depositNetworkEnabled(network)) throw new IllegalStateException("CREGIS_CREDIT_DISABLED");
        List<Map<String, Object>> matches = db.lockEvent(network,config.getProjectId(), cid);
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
        var exceptionSource = new CregisExceptionSourceService.Source(config.getProjectId(), cid,
                (String) event.get("txid"), ((Number) event.get("logIndex")).intValue(),
                (String) event.get("address"), (BigDecimal) event.get("grossAmount"),network);
        exceptionSources.reserve(exceptionSource, "REVIEW", "CR-" + cid + ":REVIEW");
        long userId = ((Number) event.get("userId")).longValue();
        if (!Long.valueOf(userId).equals(db.lockActiveUser(userId)))
            throw new IllegalStateException("CREGIS_DEPOSIT_USER_FROZEN");
        String txid = (String) event.get("txid"), address = (String) event.get("address");
        BigDecimal amount = (BigDecimal) event.get("grossAmount");
        List<Map<String, Object>> owners = db.addressOwner(config.getProjectId(), network.chainId(), address);
        if (owners.size() != 1 || ((Number) owners.get(0).get("userId")).longValue() != userId)
            throw new IllegalStateException("CREGIS_REVIEW_OWNER_MISMATCH");
        if (((Number) owners.get(0).get("allocationBlock")).longValue() != proof.allocationBlock()
                || !proof.allocationHash().equalsIgnoreCase((String) owners.get(0).get("allocationHash")))
            throw new IllegalStateException("CREGIS_ALLOCATION_ANCHOR_MISMATCH");
        requireReviewProofFresh(proof);
        var policy = policies.snapshot(network,config.getProjectId(),txid);
        if (policy.version()!=proof.policyVersion() || policy.feeUsdt().compareTo(proof.feeAmount()) != 0 || proof.confirmations() < policy.confirmations()) throw new IllegalStateException("CREGIS_REVIEW_POLICY_CHANGED");
        if(policies.integerPrincipalRequired(network,config.getProjectId(),txid)
                &&(amount.stripTrailingZeros().scale()>0||amount.compareTo(policy.minDepositUsdt())<0))
            throw new BizException(409,"CREGIS_TOPUP_PRINCIPAL_REQUIRES_EXCEPTION");
        BigDecimal net = amount.subtract(policy.feeUsdt());
        if (net.signum()<=0) throw new IllegalStateException("CREGIS_REVIEW_NET_INVALID");
        long eventId = ((Number) event.get("id")).longValue();
        var prepared = paymentAttribution.prepare(userId, Source.DEPOSIT_ORDER, "CR-" + cid,
                String.valueOf(config.getProjectId()));
        if (db.releaseReviewedEvent(eventId, policy.feeUsdt(), net, proof.confirmations()) != 1)
            throw new IllegalStateException("CREGIS_REVIEW_EVENT_CONFLICT");
        Map<String, Object> wallet = db.lockWallet(userId);
        if (wallet == null) throw new IllegalStateException("CREGIS_WALLET_MISSING");
        requireReviewProofFresh(proof);
        BigDecimal after = ((BigDecimal) wallet.get("usdtAvailable")).add(net);
        paymentAttribution.beforeCash(prepared);
        if (db.creditWallet(net, userId, ((Number) wallet.get("version")).longValue()) != 1)
            throw new IllegalStateException("CREGIS_WALLET_CONFLICT");
        String bizNo = "CR-" + cid;
        if (paymentAttribution.insertLedger(prepared, net, after, "Reviewed Cregis USDT-"+network.network()+" deposit " + txid) != 1)
            throw new IllegalStateException("CREGIS_LEDGER_INSERT_FAILED");
        treasury.recordTopupReserve(bizNo, net, "CREGIS:" + cid);
        Long ledgerId = db.ledgerId(bizNo);
        if (ledgerId == null || db.linkLedger(ledgerId, eventId) != 1
                || db.insertDepositOrder(network,userId, bizNo, txid, proof.logIndex(), net,
                        proof.confirmations(), ledgerId) != 1)
            throw new IllegalStateException("CREGIS_REVIEW_LEDGER_LINK_FAILED");
        paymentAttribution.record(prepared);
        exceptionSources.complete(exceptionSource, "REVIEW", "CR-" + cid + ":REVIEW", "CREDITED");
    }

    private boolean settleOrHold(CregisNetwork network,long deliveryId, long userId, long cid, String txid, String address,
                                 BigDecimal amount, BscDepositProof.Proof proof) {
        // Lock first: an earlier consistent read would pin a stale REPEATABLE READ
        // snapshot and miss a callback committed while waiting for this gate.
        Map<String, Object> gate = db.lockProvisionGate();
        if (gate == null) throw new IllegalStateException("CREGIS_DEPOSIT_RISK_GATE_MISSING");
        if (db.disputedDeliveryCount(cid) != 0) return false;
        List<Map<String, Object>> existing = db.lockEvent(network,config.getProjectId(), cid);
        if (!existing.isEmpty()) return settle(network,deliveryId, userId, cid, txid, address, amount, proof);
        if (config.depositNetworkEnabled(network) && config.isDepositCreditEnabled() && "IDLE".equals(gate.get("state"))
                && on(gate, "creditEnabled")
                && !hasBlockingPendingDelivery(network,deliveryId, cid, txid, address, amount, proof.blockNumber())) {
            if (settle(network,deliveryId, userId, cid, txid, address, amount, proof)) return true;
            // A trip during settlement can close the gate before this verified
            // transfer is materialized. Keep it as a reviewed hold.
            if (!db.lockEvent(network,config.getProjectId(), cid).isEmpty()) return false;
        }
        var policy = policies.snapshot(network,config.getProjectId(), txid);
        if (proof.confirmations() < policy.confirmations()) return false;
        String status = amount.compareTo(policy.minDepositUsdt()) < 0 ? "DUST_HOLD" : "REVIEW_HOLD";
        if (db.insertEvent(network,userId, config.getProjectId(), cid, txid, proof.logIndex(),
                address, amount, BigDecimal.ZERO, BigDecimal.ZERO, proof.blockNumber(),
                proof.blockHash(), proof.confirmations(), status) != 1)
            throw new IllegalStateException("CREGIS_PAUSED_HOLD_INSERT_FAILED");
        recordTransferSender(network,cid,proof);
        tripLocked();
        if (deliveryId > 0) db.finishDelivery(deliveryId, status);
        return true;
    }

    private boolean settle(CregisNetwork network,long deliveryId, long userId, long cid, String txid, String address,
                        BigDecimal amount, BscDepositProof.Proof proof) {
        List<Map<String, Object>> existing = db.lockEvent(network,config.getProjectId(), cid);
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
        var policy = policies.snapshot(network,config.getProjectId(), txid);
        if (proof.confirmations() < policy.confirmations()) return false;
        String status = amount.compareTo(policy.minDepositUsdt()) < 0 ? "DUST_HOLD"
                : policies.integerPrincipalRequired(network,config.getProjectId(),txid)&&amount.stripTrailingZeros().scale()>0
                        || amount.compareTo(new BigDecimal("100")) > 0 ? "REVIEW_HOLD" : "CREDITED";
        Map<String, Object> gate = db.lockProvisionGate();
        if (gate == null) throw new IllegalStateException("CREGIS_DEPOSIT_RISK_GATE_MISSING");
        if (tripLocked()) return false;
        if (!config.depositNetworkEnabled(network) || !"IDLE".equals(gate.get("state")) || !on(gate, "creditEnabled")
                || hasBlockingPendingDelivery(network,deliveryId, cid, txid, address, amount, proof.blockNumber())) return false;
        var exceptionSource = new CregisExceptionSourceService.Source(config.getProjectId(), cid, txid,
                proof.logIndex(), address, amount,network);
        if ("CREDITED".equals(status)) exceptionSources.reserve(exceptionSource, "AUTO", "CR-" + cid);
        if (!Long.valueOf(userId).equals(db.lockActiveUser(userId)))
            throw new IllegalStateException("CREGIS_DEPOSIT_USER_FROZEN");
        var prepared = "CREDITED".equals(status)
                ? paymentAttribution.prepare(userId, Source.DEPOSIT_ORDER, "CR-" + cid,
                        String.valueOf(config.getProjectId())) : null;
        BigDecimal fee = "CREDITED".equals(status) ? policy.feeUsdt() : BigDecimal.ZERO;
        BigDecimal net = "CREDITED".equals(status) ? amount.subtract(fee) : BigDecimal.ZERO;
        if (db.insertEvent(network,userId, config.getProjectId(), cid, txid, proof.logIndex(), address, amount, fee, net,
                proof.blockNumber(), proof.blockHash(), proof.confirmations(), status) != 1)
            throw new IllegalStateException("CREGIS_EVENT_INSERT_FAILED");
        recordTransferSender(network,cid,proof);
        if (!"CREDITED".equals(status)) tripLocked();
        if ("CREDITED".equals(status)) {
            Map<String, Object> wallet = db.lockWallet(userId);
            if (wallet == null) throw new IllegalStateException("CREGIS_WALLET_MISSING");
            BigDecimal after = ((BigDecimal) wallet.get("usdtAvailable")).add(net);
            paymentAttribution.beforeCash(prepared);
            if (db.creditWallet(net, userId, ((Number) wallet.get("version")).longValue()) != 1)
                throw new IllegalStateException("CREGIS_WALLET_CONFLICT");
            String bizNo = "CR-" + cid;
            if (paymentAttribution.insertLedger(prepared, net, after, "Cregis USDT-"+network.network()+" deposit " + txid) != 1)
                throw new IllegalStateException("CREGIS_LEDGER_INSERT_FAILED");
            treasury.recordTopupReserve(bizNo, net, "CREGIS:" + cid);
            Long ledgerId = db.ledgerId(bizNo), eventId = db.eventId(network,config.getProjectId(), cid);
            if (ledgerId == null || eventId == null || db.linkLedger(ledgerId, eventId) != 1)
                throw new IllegalStateException("CREGIS_LEDGER_LINK_FAILED");
            if (db.insertDepositOrder(network,userId, bizNo, txid, proof.logIndex(), net, proof.confirmations(), ledgerId) != 1)
                throw new IllegalStateException("CREGIS_DEPOSIT_ORDER_FAILED");
            paymentAttribution.record(prepared);
            exceptionSources.complete(exceptionSource, "AUTO", "CR-" + cid, "CREDITED");
        }
        if (deliveryId > 0) db.finishDelivery(deliveryId, status);
        return true;
    }

    private void recordTransferSender(CregisNetwork network,long cid,BscDepositProof.Proof proof) {
        if(proof.fromAddress()!=null && db.recordTransferSender(network,config.getProjectId(),cid,proof.fromAddress())!=1)
            throw new IllegalStateException("CREGIS_TRANSFER_SENDER_SNAPSHOT_FAILED");
    }

    /** Independent verified successes proceed; receipts sharing CID or transaction must match this credit. */
    private boolean hasBlockingPendingDelivery(CregisNetwork network,long deliveryId, long cid, String txid, String address,
                                               BigDecimal amount, long blockNumber) {
        for (Map<String, Object> receipt : db.pendingAcceptedDeliveryEvidence(deliveryId)) {
            try {
                Map<String, Object> callback = json.readValue((String) receipt.get("rawJson"), new TypeReference<>() { });
                if (!valid(callback, false) || !"1".equals(String.valueOf(callback.get("status")))) return true;
                CregisNetwork receiptNetwork=CregisNetwork.fromAsset(String.valueOf(callback.get("chain_id")),String.valueOf(callback.get("token_id")));
                if(receiptNetwork!=network) continue;
                boolean sameCid = ((Number) callback.get("cid")).longValue() == cid;
                boolean sameTx = txid.replaceFirst("(?i)^0x", "").equalsIgnoreCase(
                        String.valueOf(callback.get("txid")).replaceFirst("(?i)^0x", ""));
                if (!sameCid && !sameTx) continue;
                if (!sameCid || !sameTx
                        || !address.equalsIgnoreCase(String.valueOf(callback.get("address")))
                        || amount.compareTo(CregisAmount.parsePositive((String) callback.get("amount"))) != 0
                        || Long.parseLong(String.valueOf(callback.get("block_height"))) != blockNumber) return true;
            } catch (Exception unverified) {
                return true;
            }
        }
        return false;
    }

    private void requireAllocationAnchor(CregisNetwork network,Map<String, Object> owner) {
        Number block = (Number) owner.get("allocationBlock");
        String hash = (String) owner.get("allocationHash");
        if (block == null || hash == null || !hash.equalsIgnoreCase(chain.blockHash(network,block.longValue()))) {
            db.blockProvisionGateAny();
            throw new IllegalStateException("CREGIS_ALLOCATION_ANCHOR_MISMATCH");
        }
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
    public Map<String,Object> address(long userId) { return address(CregisNetwork.BEP20,userId); }
    public Map<String,Object> provisionPoolAddress() { return provisionPoolAddress(CregisNetwork.BEP20); }
    void scanTrackedAddresses(long finalized) { scanTrackedAddresses(CregisNetwork.BEP20,finalized); }
    private static CregisNetwork networkOf(Map<String,Object> row) {
        Object value=row.get("chainId");
        return value==null ? CregisNetwork.BEP20 : CregisNetwork.fromChainId(String.valueOf(value));
    }
}
