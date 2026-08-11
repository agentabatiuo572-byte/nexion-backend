package ffdd.opsconsole.market.application;

import ffdd.opsconsole.market.mapper.G2AcceptanceSandboxMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Application transaction boundary backed only by the isolated MyBatis mapper. */
@Repository
@Profile({"acceptance", "test"})
@RequiredArgsConstructor
public class G2AcceptanceSandboxRepository {
    private static final String SOURCE = "mock";
    private static final String ENVIRONMENT = "SANDBOX";
    private final G2AcceptanceSandboxMapper mapper;

    public void verifySchema() {
        if (mapper.sandboxTableCount() != 4) {
            throw new IllegalStateException("G2_ACCEPTANCE_SANDBOX_MIGRATION_REQUIRED: apply scripts/migrations/20260811_g2_acceptance_sandbox.sql before startup");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate() {
        String batchNo = "SBX-G2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        mapper.insertBatch(new G2AcceptanceSandboxMapper.BatchWrite(batchNo, SOURCE, ENVIRONMENT, "QUEUED", now, now));
        mapper.insertOrder(new G2AcceptanceSandboxMapper.OrderWrite(batchNo + "-COMPLETED", batchNo, "COMPLETED", "QUEUED", null, null, new BigDecimal("12.500000"), SOURCE, ENVIRONMENT, now, now));
        mapper.insertOrder(new G2AcceptanceSandboxMapper.OrderWrite(batchNo + "-SKIPPED", batchNo, "SKIPPED", "QUEUED", "INACTIVE_ATTRIBUTION", "验收夹具：归属关系无效，订单跳过且不生成 sandbox 账本", new BigDecimal("7.500000"), SOURCE, ENVIRONMENT, now, now));
        return snapshot(batchNo, false, null);
    }

    public Map<String, Object> latest() {
        String batchNo = mapper.latestBatchNo();
        return batchNo == null ? emptySnapshot() : snapshot(batchNo, false, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> process(String batchNo, String commandKey) {
        requireBatch(batchNo);
        String existingBatch = mapper.idempotencyBatch(commandKey);
        if (existingBatch != null) {
            if (!batchNo.equals(existingBatch)) throw new BizException(409, "G2_ACCEPTANCE_SANDBOX_IDEMPOTENCY_CONFLICT");
            return snapshot(batchNo, true, receipt(batchNo, true));
        }
        LocalDateTime now = LocalDateTime.now();
        for (G2AcceptanceSandboxMapper.QueuedRow row : mapper.queuedRows(batchNo)) {
            if ("COMPLETED".equals(row.fixtureOutcome())) {
                if (mapper.complete(row.exchangeNo(), now) == 1) {
                    insertLedger(batchNo, row.exchangeNo(), "OUT", row.amountUsdt().negate(), now);
                    insertLedger(batchNo, row.exchangeNo(), "IN", row.amountUsdt(), now);
                }
            } else mapper.skip(row.exchangeNo(), now);
        }
        mapper.markProcessed(batchNo, now);
        try { mapper.insertIdempotency(commandKey, batchNo, now); }
        catch (DuplicateKeyException race) { return snapshot(batchNo, true, receipt(batchNo, true)); }
        return snapshot(batchNo, false, receipt(batchNo, false));
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanup(String batchNo) {
        requireBatch(batchNo);
        mapper.deleteLedger(batchNo); mapper.deleteIdempotency(batchNo); mapper.deleteOrders(batchNo); mapper.deleteBatch(batchNo);
    }

    private void insertLedger(String batchNo, String exchangeNo, String direction, BigDecimal amount, LocalDateTime now) {
        mapper.insertLedger(new G2AcceptanceSandboxMapper.LedgerWrite(UUID.randomUUID().toString(), batchNo, exchangeNo, "USDT", direction, amount, SOURCE, ENVIRONMENT, now));
    }
    private void requireBatch(String batchNo) { if (mapper.batchCount(batchNo) != 1) throw new BizException(404, "G2_ACCEPTANCE_SANDBOX_BATCH_NOT_FOUND"); }
    private Map<String, Object> snapshot(String batchNo, boolean replayed, Map<String, Object> receipt) {
        Map<String, Object> batch = mapper.batch(batchNo); List<Map<String, Object>> orders = new ArrayList<>();
        for (G2AcceptanceSandboxMapper.OrderRow row : mapper.orders(batchNo)) orders.add(linked("exchangeNo", row.exchangeNo(), "status", row.status(), "reasonCode", row.reasonCode() == null ? "" : row.reasonCode(), "reason", row.reason() == null ? "" : row.reason(), "amountUsdt", row.amountUsdt(), "sandboxLedgerEntries", mapper.ledgerEntries(row.exchangeNo())));
        return linked("source", SOURCE, "sourceEnvironment", ENVIRONMENT, "evidenceComplete", true, "batch", linked("batchNo", batch.get("batchNo"), "status", batch.get("status"), "replayed", replayed), "orders", orders, "ledgerSummary", linked("completedLedgerEntries", mapper.ledgerCount(batchNo), "skippedLedgerEntries", 0, "productionWalletTouched", false, "productionLedgerTouched", false), "batchResult", receipt);
    }
    private Map<String, Object> emptySnapshot() { return linked("source", SOURCE, "sourceEnvironment", ENVIRONMENT, "evidenceComplete", true, "batch", null, "orders", List.of(), "ledgerSummary", linked("completedLedgerEntries", 0, "skippedLedgerEntries", 0, "productionWalletTouched", false, "productionLedgerTouched", false), "batchResult", null); }
    private Map<String, Object> receipt(String batchNo, boolean replayed) {
        List<Map<String, Object>> completed = new ArrayList<>(), skipped = new ArrayList<>();
        for (G2AcceptanceSandboxMapper.OrderRow row : mapper.orders(batchNo)) { Map<String,Object> item=linked("exchangeNo",row.exchangeNo(),"status",row.status(),"reasonCode",row.reasonCode()==null?"":row.reasonCode(),"reason",row.reason()==null?"":row.reason()); if("COMPLETED".equals(row.status())) completed.add(item); if("SKIPPED".equals(row.status())) skipped.add(item); }
        return linked("batchNo", batchNo, "completed", completed, "skipped", skipped, "completedCount", completed.size(), "skippedCount", skipped.size(), "replayed", replayed, "outcome", completed.isEmpty() ? "SKIPPED" : "PARTIAL");
    }
    private Map<String, Object> linked(Object... values) { Map<String,Object> data=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2)data.put(String.valueOf(values[i]),values[i+1]); return data; }
}
