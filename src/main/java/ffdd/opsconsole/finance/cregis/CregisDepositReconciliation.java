package ffdd.opsconsole.finance.cregis;

import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Closed, overlapping provider windows; a failed page or chain pass never advances the watermark. */
@Service
@RequiredArgsConstructor
public class CregisDepositReconciliation {
    private final CregisProperties config;
    private final CregisGatewayRouter router;
    private final BscDepositProof chain;
    private final CregisDepositMapper db;
    private final CregisDepositService deposits;
    private final PlatformTransactionManager txManager;
    private final CregisPayoutControl payouts;
    private final ffdd.opsconsole.shared.audit.AuditLogService audit;

    public List<Map<String, Object>> runs() {
        return config.getMode() == CregisProperties.Mode.PROVIDER
                ? db.reconcileRuns(config.getProjectId()) : List.of();
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_FULL_RECONCILE_MS:300000}")
    public void scheduled() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        for(var network:CregisNetwork.values()) {
            if(!config.networkConfigured(network) && !db.hasNetworkActivity(network,config.getProjectId())) continue;
            try { runOnce(network); }
            catch(RuntimeException failure) { /* Recorded per-network run; continue the other chain. */ }
        }
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Map<String, Object> runOnce(CregisNetwork network) {
        if (config.getMode() != CregisProperties.Mode.PROVIDER)
            throw new IllegalStateException("CREGIS_PROVIDER_DISABLED");
        Long first = db.firstAddressSecond(network,config.getProjectId());
        boolean bep20Bootstrap=first==null && network==CregisNetwork.BEP20 && config.networkConfigured(network);
        boolean bootstrap=bep20Bootstrap || first==null && network==CregisNetwork.TRC20 && config.networkConfigured(network);
        if(bootstrap)first=Instant.now().getEpochSecond();
        if (first == null) return Map.of("status", "NO_ADDRESSES");
        db.ensureWatermark(network,Math.max(1, first - 3600));
        Long prior = db.reconcileWatermark(network);
        if (prior == null) throw new IllegalStateException("CREGIS_RECONCILE_WATERMARK_MISSING");
        long closed = Instant.now().getEpochSecond() - 300;
        if (closed <= prior) return Map.of("status", "CURRENT", "completeThrough", prior);
        long start = Math.max(1, prior - 600);
        long end = Math.min(closed, prior + 3600);
        String runId = UUID.randomUUID().toString();
        if (db.claimReconcileLease(network,runId) != 1) {
            String active = db.activeReconcileRun(network);
            return Map.of("status", "RUNNING", "runId", active == null ? "" : active);
        }
        try {
            db.failInterruptedReconcileRuns(network,config.getProjectId(), runId);
            db.createReconcileRun(network,runId, config.getProjectId(), start, end);
            if(bep20Bootstrap) {
                requireBep20Bootstrap(db.provisionGate(),db.networkSwitch(network));
                if(router.provider().projectCoins().stream().noneMatch(coin->coin.addressEnabled()
                        && network.chainId().equals(coin.chainId()) && network.tokenMatches(coin.tokenId())))
                    throw new IllegalStateException("CREGIS_BEP20_BOOTSTRAP_ASSET_UNAVAILABLE");
            } else if(bootstrap) {
                if(db.hasNetworkActivity(network,config.getProjectId()) || payouts.recoveryReason()!=null
                        || db.unresolvedExposure(config.getProjectId(),false).signum()!=0
                        || router.provider().projectCoins().stream().noneMatch(coin->coin.addressEnabled()
                            && network.chainId().equals(coin.chainId()) && network.tokenMatches(coin.tokenId())))
                    throw new IllegalStateException("CREGIS_TRC20_BOOTSTRAP_NOT_EMPTY_OR_READY");
            }
            Snapshot firstPass = snapshot(network,start, end,bep20Bootstrap);
            Snapshot secondPass = snapshot(network,start, end,bep20Bootstrap);
            if (firstPass.total != secondPass.total || !firstPass.hash.equals(secondPass.hash))
                throw new IllegalStateException("CREGIS_RECONCILE_PROVIDER_DRIFT");
            if(bootstrap && (firstPass.total!=0 || secondPass.total!=0))
                throw new IllegalStateException("CREGIS_TRC20_BOOTSTRAP_PROVIDER_NOT_EMPTY");
            BscDepositProof.Head bootstrapHead=bep20Bootstrap?chain.head(network):null;
            long finalized = bep20Bootstrap?bootstrapHead.number()-config.getDepositConfirmations()+1
                    :chain.finalizedHeight(network,config.getDepositConfirmations());
            if (!bep20Bootstrap && finalized >= 0) deposits.scanTrackedAddresses(network,finalized);
            Long cursor = bep20Bootstrap?Long.valueOf(finalized+1):db.cursor(network);
            if (finalized < 0 || cursor == null || cursor <= finalized)
                throw new IllegalStateException("CREGIS_RECONCILE_CHAIN_CURSOR_INCOMPLETE");
            String chainHash = chain.blockHash(network,finalized);
            if(bep20Bootstrap && !bootstrapHead.hash().equals(chain.blockHash(network,bootstrapHead.number())))
                throw new IllegalStateException("CREGIS_BEP20_BOOTSTRAP_CHAIN_DRIFT");
            for (CregisGateway.DepositRow row : secondPass.rows) {
                if (row.status() == 0 || !deposits.materializeProviderRow(row))
                    throw new IllegalStateException("CREGIS_RECONCILE_ROW_UNRESOLVED");
            }
            if(!bep20Bootstrap)deposits.auditCreditedProviderRows(network);
            TransactionTemplate tx = new TransactionTemplate(txManager);
            if(bep20Bootstrap)tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
            tx.executeWithoutResult(ignored -> {
                if(bep20Bootstrap) {
                    requireBep20Bootstrap(db.lockProvisionGate(),db.lockNetworkSwitch(network));
                    db.ensureCursor(network,cursor);
                    Long lockedCursor=db.lockCursor(network);
                    if(lockedCursor==null || lockedCursor>cursor || lockedCursor<cursor
                            && db.advanceCursor(network,cursor,lockedCursor)!=1)
                        throw new IllegalStateException("CREGIS_RECONCILE_CHAIN_CURSOR_INCOMPLETE");
                } else if(bootstrap && (db.lockProvisionGate()==null || db.hasNetworkActivity(network,config.getProjectId())
                        || payouts.recoveryReason()!=null || db.unresolvedExposure(config.getProjectId(),false).signum()!=0))
                    throw new IllegalStateException("CREGIS_TRC20_BOOTSTRAP_NOT_EMPTY_OR_READY");
                Long locked = db.lockReconcileWatermark(network);
                if (locked == null || !locked.equals(prior))
                    throw new IllegalStateException("CREGIS_RECONCILE_WATERMARK_CONFLICT");
                if (db.completeReconcileRun(runId, secondPass.total, secondPass.rows.size(),
                        secondPass.rows.size(), secondPass.hash, cursor, chainHash) != 1
                        || db.advanceReconcileWatermark(network,prior, end, runId) != 1)
                    throw new IllegalStateException("CREGIS_RECONCILE_COMMIT_FAILED");
                if(bep20Bootstrap)audit.recordRequired(ffdd.opsconsole.shared.audit.AuditLogWriteRequest.builder()
                        .action("CREGIS_BEP20_EMPTY_POOL_RECONCILED").resourceType("CREGIS_RECONCILE").resourceId(runId)
                        .actorType("SYSTEM").actorUsername("cregis-reconciliation").riskLevel("HIGH").result("SUCCESS")
                        .detail(Map.ofEntries(Map.entry("source","EMPTY_POOL_BOOTSTRAP"),Map.entry("network",network.network()),
                                Map.entry("windowStart",start),Map.entry("windowEnd",end),Map.entry("providerRows",0),
                                Map.entry("chainHeadBlock",bootstrapHead.number()),Map.entry("chainHeadHash",bootstrapHead.hash()),
                                Map.entry("finalizedBlock",finalized),Map.entry("chainCursorBlock",cursor),Map.entry("chainCursorHash",chainHash),
                                Map.entry("evidenceScope","WINDOW_AND_EMPTY_LOCAL_TRACKED_SET_ONLY"))).build());
                else if(bootstrap)audit.recordRequired(ffdd.opsconsole.shared.audit.AuditLogWriteRequest.builder()
                        .action("CREGIS_TRC20_EMPTY_POOL_RECONCILED").resourceType("CREGIS_RECONCILE").resourceId(runId)
                        .actorType("SYSTEM").actorUsername("cregis-reconciliation").riskLevel("HIGH").result("SUCCESS")
                        .detail(Map.of("source","EMPTY_POOL_BOOTSTRAP","network",network.network(),"windowStart",start,
                                "windowEnd",end,"providerRows",0,"chainCursorBlock",cursor,"chainCursorHash",chainHash)).build());
            });
            return Map.of("runId", runId, "status", "COMPLETE", "windowStart", start,
                    "windowEnd", end, "total", secondPass.total, "fullRowHash", secondPass.hash,
                    "chainCursorBlock", cursor, "chainCursorHash", chainHash,"bootstrap",bootstrap);
        } catch (RuntimeException failure) {
            db.failReconcileRun(runId, code(failure));
            throw failure;
        } finally {
            db.releaseReconcileLease(network,runId);
        }
    }

    public Map<String,Object> runOnce() { return runOnce(CregisNetwork.BEP20); }

    private void requireBep20Bootstrap(Map<String,Object> gate,Map<String,Object> networkGate) {
        BigDecimal exposure=db.unresolvedExposure(config.getProjectId(),false);
        if(config.getMode()!=CregisProperties.Mode.PROVIDER || !config.networkConfigured(CregisNetwork.BEP20)
                || config.getDepositConfirmations()<15 || gate==null || "BUSY".equals(gate.get("state"))
                || on(gate.get("assignEnabled")) || on(gate.get("creditEnabled")) || on(gate.get("payoutEnabled"))
                || networkGate==null || on(networkGate.get("depositEnabled"))
                || db.hasNetworkActivity(CregisNetwork.BEP20,config.getProjectId())
                || db.hasBep20BootstrapMoneyActivity(config.getProjectId()) || payouts.riskReason()!=null
                || exposure==null || exposure.signum()!=0)
            throw new IllegalStateException("CREGIS_BEP20_BOOTSTRAP_NOT_EMPTY_OR_READY");
    }

    private static boolean on(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()==1;
    }

    private Snapshot snapshot(CregisNetwork network,long start, long end,boolean emptyOnly) {
        List<CregisGateway.DepositRow> rows = new ArrayList<>();
        Set<Long> cids = new HashSet<>();
        long expected = -1;
        for (int page = 1; page <= 100; page++) {
            CregisGateway.DepositPage fetched = router.provider().depositPage(network,start, end, page, 100);
            // Bootstrap must reject provider money facts before discoverProviderRow can persist any history.
            if(emptyOnly && (fetched.total()!=0 || !fetched.rows().isEmpty()))
                throw new IllegalStateException("CREGIS_BEP20_BOOTSTRAP_PROVIDER_NOT_EMPTY");
            if (expected == -1) expected = fetched.total();
            if (fetched.total() != expected || fetched.rows().size() > 100
                    || (expected > rows.size() && fetched.rows().isEmpty()))
                throw new IllegalStateException("CREGIS_RECONCILE_PAGE_INCOMPLETE");
            for (CregisGateway.DepositRow row : fetched.rows()) {
                if(CregisNetwork.fromAsset(row.chainId(),row.tokenId())!=network) throw new IllegalStateException("CREGIS_RECONCILE_NETWORK_MISMATCH");
                deposits.discoverProviderRow(row);
                if (!cids.add(row.cid())) throw new IllegalStateException("CREGIS_RECONCILE_DUPLICATE_CID");
                rows.add(row);
            }
            if (rows.size() == expected) {
                rows.sort(Comparator.comparingLong(CregisGateway.DepositRow::cid)
                        .thenComparing(CregisGateway.DepositRow::txid));
                return new Snapshot(expected, List.copyOf(rows), hash(rows));
            }
            if (rows.size() > expected) throw new IllegalStateException("CREGIS_RECONCILE_PAGE_OVERFLOW");
        }
        throw new IllegalStateException("CREGIS_RECONCILE_PAGE_LIMIT");
    }

    static String hash(List<CregisGateway.DepositRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CregisGateway.DepositRow row : rows) {
                BigDecimal atomic = row.amount().movePointRight(CregisNetwork.fromAsset(row.chainId(),row.tokenId()).decimals());
                String line = row.cid() + "|" + row.status() + "|" + row.chainId() + "|"
                        + row.tokenId() + "|" + row.address() + "|" + atomic.toBigIntegerExact()
                        + "|" + row.txid() + "|" + row.blockHeight() + "|" + row.blockTime() + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception invalid) {
            throw new IllegalStateException("CREGIS_RECONCILE_HASH_FAILED", invalid);
        }
    }

    private static String code(RuntimeException failure) {
        String value = failure.getMessage();
        return value != null && value.matches("[A-Z0-9_]{1,64}")
                ? value : "CREGIS_RECONCILE_FAILED";
    }

    private record Snapshot(long total, List<CregisGateway.DepositRow> rows, String hash) { }
}
