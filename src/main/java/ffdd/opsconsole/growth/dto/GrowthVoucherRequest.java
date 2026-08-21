package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.List;

public record GrowthVoucherRequest(
        String id,
        String name,
        String type,
        BigDecimal amountUSD,
        BigDecimal percent,
        BigDecimal minPurchaseUSD,
        BigDecimal maxDiscountUSD,
        List<String> applicableSkus,
        String audience,
        Long startAt,
        Long endAt,
        List<String> claimSurfaces,
        Boolean popupEnabled,
        Boolean stackWithTrial,
        Boolean stackWithOthers,
        Boolean splittable,
        Long issuanceLimit,
        Long expectedVersion,
        String status,
        String reason,
        String operator,
        Long popupDelayMs,
        Long popupCooldownHours,
        Long popupMaxPerSession,
        Boolean popupCadenceEnabled) {
}
