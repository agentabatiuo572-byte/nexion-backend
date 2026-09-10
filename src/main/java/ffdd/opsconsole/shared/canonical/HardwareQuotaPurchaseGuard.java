package ffdd.opsconsole.shared.canonical;

import ffdd.opsconsole.shared.canonical.mapper.HardwareQuotaPurchaseMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** The caller holds its product lock and a READ_COMMITTED settlement transaction. */
public final class HardwareQuotaPurchaseGuard {
    private HardwareQuotaPurchaseGuard() { }

    public static Reservation reserve(HardwareQuotaPurchaseMapper mapper, Long userId,
                                      String productNo, LocalDateTime occurredAtUtc) {
        var tiers = mapper.lockHardwarePurchaseTiers(productNo);
        if (tiers == null) throw new BizException(503, "ORDER_MONTHLY_QUOTA_UNAVAILABLE");
        boolean needsFacts = tiers.stream().anyMatch(tier -> tier != null
                && ((tier.directRefs() != null && tier.directRefs() > 0)
                || (tier.monthVolumeUsd() != null && tier.monthVolumeUsd().signum() > 0)));
        var facts = needsFacts ? mapper.hardwarePurchaseFacts(userId) : null;
        if (needsFacts && facts == null) throw new BizException(503, "ORDER_MONTHLY_QUOTA_UNAVAILABLE");
        LocalDateTime month = occurredAtUtc.withDayOfMonth(1).toLocalDate().atStartOfDay();
        for (var tier : tiers) {
            if (tier == null || tier.id() == null || tier.monthlyQuota() == null || tier.monthlyQuota() < 0) {
                throw new BizException(503, "ORDER_MONTHLY_QUOTA_UNAVAILABLE");
            }
            if (!Integer.valueOf(1).equals(tier.status())) throw new BizException(409, "ORDER_MONTHLY_QUOTA_PAUSED");
            if (!requirementsMet(tier.directRefs(), tier.monthVolumeUsd(), tier.unlockMode(),
                    facts == null ? null : facts.activeDirect(), facts == null ? null : facts.monthlyVolumeUsd())) {
                throw new BizException(409, "ORDER_MONTHLY_QUOTA_REQUIREMENTS_NOT_MET");
            }
            var usage = mapper.lockHardwarePurchaseUsage(tier.id(), month, month.plusMonths(1));
            if (usage == null || usage.stream().anyMatch(value -> value == null || value < 0)) {
                throw new BizException(503, "ORDER_MONTHLY_QUOTA_UNAVAILABLE");
            }
            if (usage.stream().mapToLong(Integer::longValue).sum() >= tier.monthlyQuota()) {
                throw new BizException(409, "ORDER_MONTHLY_QUOTA_EXHAUSTED");
            }
        }
        return new Reservation(List.copyOf(tiers), occurredAtUtc);
    }

    public static void record(HardwareQuotaPurchaseMapper mapper, Reservation reservation,
                              Long userId, String orderNo) {
        for (var tier : reservation.tiers()) {
            if (mapper.recordHardwarePurchase(tier, userId, orderNo, reservation.occurredAtUtc()) != 1) {
                throw new BizException(409, "ORDER_MONTHLY_QUOTA_CONFLICT");
            }
        }
    }

    public static boolean requirementsMet(Integer requiredDirect, BigDecimal requiredVolume, String mode,
                                          Long currentDirect, BigDecimal currentVolume) {
        boolean directRequired = requiredDirect != null && requiredDirect > 0;
        boolean volumeRequired = requiredVolume != null && requiredVolume.signum() > 0;
        if (!directRequired && !volumeRequired) return true;
        boolean directMet = !directRequired || Math.max(0L, currentDirect == null ? 0L : currentDirect) >= requiredDirect;
        boolean volumeMet = !volumeRequired || (currentVolume == null ? BigDecimal.ZERO : currentVolume)
                .max(BigDecimal.ZERO).compareTo(requiredVolume) >= 0;
        return "EITHER".equalsIgnoreCase(mode)
                ? (directRequired && directMet) || (volumeRequired && volumeMet) : directMet && volumeMet;
    }

    public record Reservation(List<HardwareQuotaPurchaseMapper.Tier> tiers, LocalDateTime occurredAtUtc) { }
}
