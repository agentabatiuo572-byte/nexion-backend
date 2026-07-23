package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.VoucherRedemptionRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** H2/H7 user-lifecycle side effects that must join their caller transaction. */
@Service
@RequiredArgsConstructor
public class AppGrowthLifecyclePublisher {
    private final AppGrowthLifecycleMapper mapper;
    private final AuditLogService auditLogService;
    private final EventOutboxService outboxService;

    public VoucherRedemption prepareVoucher(
            Long userId, String voucherId, String productNo, BigDecimal subtotalUsdt) {
        if (!StringUtils.hasText(voucherId)) return VoucherRedemption.none();
        if (!StringUtils.hasText(productNo)) throw new BizException(422, "VOUCHER_PRODUCT_NO_REQUIRED");
        VoucherRedemptionRow row = mapper.lockAvailableVoucher(
                userId, voucherId.trim(), productNo.trim(), System.currentTimeMillis());
        if (row == null) throw new BizException(409, "VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE");
        BigDecimal subtotal = nonNegative(subtotalUsdt);
        if (subtotal.compareTo(nonNegative(row.minPurchaseUsd())) < 0) {
            throw new BizException(409, "VOUCHER_MIN_PURCHASE_NOT_MET");
        }
        BigDecimal discount = switch (normalize(row.voucherType())) {
            case "FIXED", "AMOUNT", "CASH" -> nonNegative(row.amountUsd());
            case "PERCENT", "PERCENTAGE" -> subtotal.multiply(nonNegative(row.percentValue()))
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
            default -> throw new BizException(409, "VOUCHER_TYPE_UNSUPPORTED");
        };
        BigDecimal cap = nonNegative(row.maxDiscountUsd());
        if (cap.signum() > 0) discount = discount.min(cap);
        discount = discount.min(subtotal).setScale(6, RoundingMode.DOWN);
        if (discount.signum() <= 0) throw new BizException(409, "VOUCHER_DISCOUNT_ZERO");
        return new VoucherRedemption(row.grantId(), row.voucherId(), discount);
    }

    public void redeemVoucher(
            Long userId,
            VoucherRedemption redemption,
            String orderNo,
            String productNo,
            UserAttribution attribution) {
        if (redemption == null || !redemption.applied()) return;
        if (mapper.markVoucherUsed(redemption.grantId(), userId, orderNo) != 1) {
            throw new BizException(409, "VOUCHER_REDEMPTION_CONFLICT");
        }
        Map<String, Object> auditDetail = linked(
                "voucherId", redemption.voucherId(), "grantId", redemption.grantId(),
                "orderId", orderNo, "productNo", productNo, "discountUsdt", redemption.discountUsdt());
        audit("H7_VOUCHER_REDEEMED", "USER_VOUCHER_GRANT", redemption.grantId(), orderNo, userId, auditDetail);
        publish("ORDER", orderNo, "voucher.redeemed", userId, attribution, linked(
                "voucherId", redemption.voucherId(), "orderId", orderNo,
                "sku", productNo, "discountUsd", redemption.discountUsdt()));
    }

    public void trialChargeAttempted(
            Long userId,
            String claimNo,
            String outcome,
            BigDecimal amountUsdt,
            String reason,
            UserAttribution attribution) {
        Map<String, Object> detail = linked(
                "trigger", "MANUAL", "result", normalize(outcome),
                "amountUsdt", nonNegative(amountUsdt), "reason", normalize(reason),
                "paymentRail", "NEXION_USDT_WALLET");
        audit("H2_TRIAL_CHARGE_ATTEMPTED", "TRIAL_CLAIM", claimNo, claimNo, userId, detail);
        publish("TRIAL_CLAIM", claimNo, "trial.charge_attempted", userId, attribution, detail);
    }

    private void publish(
            String aggregateType,
            String aggregateId,
            String eventName,
            Long userId,
            UserAttribution attribution,
            Map<String, Object> detail) {
        if (attribution == null || attribution.accountAgeMonths() == null || !StringUtils.hasText(attribution.cohort())) {
            throw new BizException(409, "USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        }
        outboxService.publishUserEvent(
                aggregateType, aggregateId, eventName, userId, attribution.phase(),
                attribution.accountAgeMonths(), attribution.cohort(), detail);
    }

    private void audit(
            String action, String resourceType, String resourceId, String bizNo,
            Long userId, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId).bizNo(bizNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private String normalize(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record UserAttribution(String phase, Integer accountAgeMonths, String cohort) {
    }

    public record VoucherRedemption(String grantId, String voucherId, BigDecimal discountUsdt) {
        public static VoucherRedemption none() {
            return new VoucherRedemption(null, null, BigDecimal.ZERO);
        }

        public boolean applied() {
            return StringUtils.hasText(grantId);
        }
    }
}
