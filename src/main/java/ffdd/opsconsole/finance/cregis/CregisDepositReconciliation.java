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

    public List<Map<String, Object>> runs() {
        return config.getMode() == CregisProperties.Mode.PROVIDER
                ? db.reconcileRuns(config.getProjectId()) : List.of();
    }

    @Scheduled(fixedDelayString = "${NEXION_CREGIS_FULL_RECONCILE_MS:300000}")
    public void scheduled() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER) return;
        try { runOnce(); }
        catch (RuntimeException failure) {
            // The run row already records the failure. Keep the scheduler alive for the next overlap.
        }
    }

    public Map<String, Object> runOnce() {
        if (config.getMode() != CregisProperties.Mode.PROVIDER)
            throw new IllegalStateException("CREGIS_PROVIDER_DISABLED");
        Long first = db.firstAddressSecond(config.getProjectId());
        if (first == null) return Map.of("status", "NO_ADDRESSES");
        db.ensureWatermark(Math.max(1, first - 3600));
        Long prior = db.reconcileWatermark();
        if (prior == null) throw new IllegalStateException("CREGIS_RECONCILE_WATERMARK_MISSING");
        long closed = Instant.now().getEpochSecond() - 300;
        if (closed <= prior) return Map.of("status", "CURRENT", "completeThrough", prior);
        long start = Math.max(1, prior - 600);
        long end = Math.min(closed, prior + 3600);
        String runId = UUID.randomUUID().toString();
        if (db.claimReconcileLease(runId) != 1) {
            String active = db.activeReconcileRun();
            return Map.of("status", "RUNNING", "runId", active == null ? "" : active);
        }
        try {
            db.failInterruptedReconcileRuns(config.getProjectId(), runId);
            db.createReconcileRun(runId, config.getProjectId(), start, end);
            Snapshot firstPass = snapshot(start, end);
            Snapshot secondPass = snapshot(start, end);
            if (firstPass.total != secondPass.total || !firstPass.hash.equals(secondPass.hash))
                throw new IllegalStateException("CREGIS_RECONCILE_PROVIDER_DRIFT");
            long finalized = chain.head().number() - config.getDepositConfirmations() + 1;
            Long cursor = db.cursor();
            if (finalized < 0 || cursor == null || cursor <= finalized)
                throw new IllegalStateException("CREGIS_RECONCILE_CHAIN_CURSOR_INCOMPLETE");
            String chainHash = chain.blockHash(finalized);
            for (CregisGateway.DepositRow row : secondPass.rows) {
                if (row.status() == 0 || !deposits.materializeProviderRow(row))
                    throw new IllegalStateException("CREGIS_RECONCILE_ROW_UNRESOLVED");
            }
            deposits.auditCreditedProviderRows();
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(ignored -> {
                Long locked = db.lockReconcileWatermark();
                if (locked == null || !locked.equals(prior))
                    throw new IllegalStateException("CREGIS_RECONCILE_WATERMARK_CONFLICT");
                if (db.completeReconcileRun(runId, secondPass.total, secondPass.rows.size(),
                        secondPass.rows.size(), secondPass.hash, cursor, chainHash) != 1
                        || db.advanceReconcileWatermark(prior, end, runId) != 1)
                    throw new IllegalStateException("CREGIS_RECONCILE_COMMIT_FAILED");
            });
            return Map.of("runId", runId, "status", "COMPLETE", "windowStart", start,
                    "windowEnd", end, "total", secondPass.total, "fullRowHash", secondPass.hash,
                    "chainCursorBlock", cursor, "chainCursorHash", chainHash);
        } catch (RuntimeException failure) {
            db.failReconcileRun(runId, code(failure));
            throw failure;
        } finally {
            db.releaseReconcileLease(runId);
        }
    }

    private Snapshot snapshot(long start, long end) {
        List<CregisGateway.DepositRow> rows = new ArrayList<>();
        Set<Long> cids = new HashSet<>();
        long expected = -1;
        for (int page = 1; page <= 100; page++) {
            CregisGateway.DepositPage fetched = router.provider().depositPage(start, end, page, 100);
            if (expected == -1) expected = fetched.total();
            if (fetched.total() != expected || fetched.rows().size() > 100
                    || (expected > rows.size() && fetched.rows().isEmpty()))
                throw new IllegalStateException("CREGIS_RECONCILE_PAGE_INCOMPLETE");
            for (CregisGateway.DepositRow row : fetched.rows()) {
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
                BigDecimal atomic = row.amount().movePointRight(18);
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
