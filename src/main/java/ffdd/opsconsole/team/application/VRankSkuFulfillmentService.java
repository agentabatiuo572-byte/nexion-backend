package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.VRankSkuFulfillmentRow;
import ffdd.opsconsole.team.mapper.TeamFulfillmentQueueMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** E/Commerce consumer for physical V-Rank SKU entitlements. */
@Service
@RequiredArgsConstructor
@Slf4j
public class VRankSkuFulfillmentService {
    private final TeamFulfillmentQueueMapper mapper;
    private final PlatformTransactionManager transactionManager;
    private final AuditLogService auditLogService;
    private final EventOutboxService eventOutboxService;

    public int processPending(int limit) {
        List<VRankSkuFulfillmentRow> rows = mapper.pendingSkuFulfillments(Math.max(1, Math.min(limit, 50)));
        int fulfilled = 0;
        for (VRankSkuFulfillmentRow row : rows) {
            if (processOne(row)) fulfilled++;
        }
        return fulfilled;
    }

    boolean processOne(VRankSkuFulfillmentRow row) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            Boolean completed = transaction.execute(status -> {
                if (mapper.claimSkuFulfillment(row.id()) != 1) return false;
                if (mapper.reserveSkuStock(row.skuId()) != 1) {
                    throw new IllegalStateException("SKU_OUT_OF_STOCK_OR_INACTIVE");
                }
                if (mapper.insertSkuEntitlement(row.id(), row.userId(), row.skuId(), row.rankCode()) < 1) {
                    throw new IllegalStateException("SKU_ENTITLEMENT_INSERT_FAILED");
                }
                if (mapper.countGrantedSkuEntitlement(row.id(), row.userId(), row.skuId()) != 1) {
                    throw new IllegalStateException("SKU_ENTITLEMENT_READBACK_FAILED");
                }
                if (mapper.grantSkuPayout(row.userId(), row.rankCode(), row.skuId()) != 1) {
                    throw new IllegalStateException("SKU_PAYOUT_CAS_FAILED");
                }
                if (mapper.completeSkuFulfillment(row.id()) != 1) {
                    throw new IllegalStateException("SKU_FULFILLMENT_CAS_FAILED");
                }
                Map<String, Object> detail = linked(
                        "fulfillmentId", row.id(), "userId", row.userId(), "skuId", row.skuId(),
                        "rankCode", row.rankCode(), "entitlementReadback", "GRANTED");
                auditLogService.recordRequired(AuditLogWriteRequest.builder()
                        .action("F1_VRANK_SKU_ENTITLEMENT_GRANTED")
                        .resourceType("USER_SKU_ENTITLEMENT")
                        .resourceId(String.valueOf(row.id()))
                        .bizNo("F1-SKU-" + row.id())
                        .actorType("SYSTEM")
                        .actorUsername("VRANK_SKU_FULFILLMENT")
                        .result("SUCCESS")
                        .riskLevel("MEDIUM")
                        .detail(detail)
                        .build());
                eventOutboxService.publish("SKU_ENTITLEMENT", "F1-SKU-" + row.id(),
                        "sku.entitlement.granted", detail);
                return true;
            });
            return Boolean.TRUE.equals(completed);
        } catch (RuntimeException ex) {
            // The reservation/entitlement/payout transaction has rolled back. Record a retryable
            // FAILED state in a fresh transaction; scheduler reclaims it after five minutes.
            transaction.executeWithoutResult(status -> mapper.failSkuFulfillment(row.id(), ex.getMessage()));
            log.warn("V-Rank SKU fulfillment failed and scheduled for retry: id={}, sku={}, error={}",
                    row.id(), row.skuId(), ex.getMessage());
            return false;
        }
    }

    public boolean hasGrantedEntitlement(Long fulfillmentId, Long userId, String skuId) {
        return mapper.countGrantedSkuEntitlement(fulfillmentId, userId, skuId) == 1;
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
