package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.team.domain.VRankSkuFulfillmentRow;
import ffdd.opsconsole.team.mapper.TeamFulfillmentQueueMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
                if (!"PENDING_GRANT".equals(mapper.lockSkuPayoutStatus(row.userId(), row.rankCode(), row.skuId()))) {
                    return false;
                }
                VRankSkuFulfillmentRow current = originalFulfillment(row.userId(), row.rankCode(), row.skuId());
                if (!Objects.equals(current.id(), row.id())) throw new BizException(409, "SKU_FULFILLMENT_ASSOCIATION_CONFLICT");
                if (mapper.claimSkuFulfillment(row.id()) != 1) return false;
                grantEntitlement(row, false);
                if (mapper.grantSkuPayout(row.userId(), row.rankCode(), row.skuId()) != 1) {
                    throw new IllegalStateException("SKU_PAYOUT_CAS_FAILED");
                }
                if (mapper.completeSkuFulfillment(row.id()) != 1) {
                    throw new IllegalStateException("SKU_FULFILLMENT_CAS_FAILED");
                }
                granted(row, "SYSTEM", "VRANK_SKU_FULFILLMENT");
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

    @Transactional(rollbackFor = Exception.class)
    public void reverseSkuReward(Long userId, String rankCode, String skuId, String payoutStatus,
                                 String operator, String reason) {
        if (!Objects.equals(payoutStatus, mapper.lockSkuPayoutStatus(userId, rankCode, skuId))) {
            throw new BizException(409, "SKU_PAYOUT_STATE_CONFLICT");
        }
        VRankSkuFulfillmentRow row = originalFulfillment(userId, rankCode, skuId);
        boolean pending = "PENDING_GRANT".equals(payoutStatus);
        if (pending ? !List.of("PENDING", "FAILED").contains(row.status()) : !"FULFILLED".equals(row.status())) {
            throw new BizException(409, "SKU_FULFILLMENT_STATE_CONFLICT");
        }
        // Keep the same payout -> queue -> product -> entitlement order as granting.
        if (!pending && mapper.returnSkuStock(skuId) != 1) throw new BizException(409, "SKU_STOCK_RETURN_FAILED");
        Map<String, Object> entitlement = mapper.lockSkuEntitlement(row.id());
        if (pending) {
            if (entitlement != null) throw new BizException(409, "SKU_ENTITLEMENT_STATE_CONFLICT");
        } else {
            requireEntitlement(row, entitlement, "GRANTED");
            if (mapper.changeSkuEntitlementStatus(row.id(), userId, rankCode, skuId, "GRANTED", "REVERSED") != 1) {
                throw new IllegalStateException("SKU_ENTITLEMENT_REVERSE_FAILED");
            }
            requireEntitlement(row, mapper.lockSkuEntitlement(row.id()), "REVERSED");
        }
        if (mapper.cancelSkuFulfillment(row.id(), row.status(), reason) != 1) {
            throw new IllegalStateException("SKU_FULFILLMENT_CANCEL_FAILED");
        }
        record(row, "F1_VRANK_SKU_ENTITLEMENT_REVERSED", "ADMIN", operator, pending ? "NONE" : "REVERSED");
    }

    @Transactional(rollbackFor = Exception.class)
    public void reissueSkuReward(Long userId, String rankCode, String skuId, String operator) {
        if (!"REVERSED".equals(mapper.lockSkuPayoutStatus(userId, rankCode, skuId))) {
            throw new BizException(409, "SKU_PAYOUT_STATE_CONFLICT");
        }
        VRankSkuFulfillmentRow row = originalFulfillment(userId, rankCode, skuId);
        if (mapper.resumeSkuFulfillment(row.id()) != 1) throw new BizException(409, "SKU_FULFILLMENT_STATE_CONFLICT");
        grantEntitlement(row, true);
        if (mapper.completeSkuFulfillment(row.id()) != 1) throw new IllegalStateException("SKU_FULFILLMENT_CAS_FAILED");
        granted(row, "ADMIN", operator);
    }

    private VRankSkuFulfillmentRow originalFulfillment(Long userId, String rankCode, String skuId) {
        List<VRankSkuFulfillmentRow> rows = mapper.lockSkuFulfillments(userId, rankCode, skuId);
        if (rows.size() != 1) throw new BizException(409, "SKU_FULFILLMENT_ASSOCIATION_CONFLICT");
        VRankSkuFulfillmentRow row = rows.get(0);
        if (!Objects.equals(userId, row.userId()) || !Objects.equals(rankCode, row.rankCode())
                || !Objects.equals(skuId, row.skuId())) throw new BizException(409, "SKU_FULFILLMENT_ASSOCIATION_CONFLICT");
        return row;
    }

    private void grantEntitlement(VRankSkuFulfillmentRow row, boolean reissue) {
        if (mapper.reserveSkuStock(row.skuId()) != 1) throw new BizException(409, "SKU_OUT_OF_STOCK_OR_INACTIVE");
        Map<String, Object> entitlement = mapper.lockSkuEntitlement(row.id());
        if (entitlement == null) {
            if (mapper.insertSkuEntitlement(row.id(), row.userId(), row.skuId(), row.rankCode()) < 1) {
                throw new IllegalStateException("SKU_ENTITLEMENT_INSERT_FAILED");
            }
        } else {
            if (!reissue) throw new BizException(409, "SKU_ENTITLEMENT_STATE_CONFLICT");
            requireEntitlement(row, entitlement, "REVERSED");
            if (mapper.changeSkuEntitlementStatus(row.id(), row.userId(), row.rankCode(), row.skuId(), "REVERSED", "GRANTED") != 1) {
                throw new IllegalStateException("SKU_ENTITLEMENT_REISSUE_FAILED");
            }
        }
        requireEntitlement(row, mapper.lockSkuEntitlement(row.id()), "GRANTED");
    }

    private void requireEntitlement(VRankSkuFulfillmentRow row, Map<String, Object> entitlement, String status) {
        if (entitlement == null || !Objects.equals(row.userId(), ((Number) entitlement.get("userId")).longValue())
                || !Objects.equals(row.rankCode(), entitlement.get("rankCode"))
                || !Objects.equals(row.skuId(), entitlement.get("skuId"))
                || !status.equals(entitlement.get("status")) || !"VRANK_REWARD".equals(entitlement.get("source"))
                || ((Number) entitlement.get("deleted")).intValue() != 0) {
            throw new BizException(409, "SKU_ENTITLEMENT_READBACK_FAILED");
        }
    }

    private Map<String, Object> record(VRankSkuFulfillmentRow row, String action, String actorType,
                                     String operator, String status) {
        Map<String, Object> detail = linked("fulfillmentId", row.id(), "userId", row.userId(),
                "skuId", row.skuId(), "rankCode", row.rankCode(), "entitlementReadback", status);
        auditLogService.recordRequired(AuditLogWriteRequest.builder().action(action)
                .resourceType("USER_SKU_ENTITLEMENT").resourceId(String.valueOf(row.id())).bizNo("F1-SKU-" + row.id())
                .actorType(actorType).actorUsername(operator).result("SUCCESS").riskLevel("MEDIUM").detail(detail).build());
        return detail;
    }

    private void granted(VRankSkuFulfillmentRow row, String actorType, String operator) {
        Map<String, Object> detail = record(row, "F1_VRANK_SKU_ENTITLEMENT_GRANTED", actorType, operator, "GRANTED");
        eventOutboxService.publish("SKU_ENTITLEMENT", "F1-SKU-" + row.id(), "sku.entitlement.granted", detail);
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
