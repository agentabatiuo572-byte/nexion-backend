package ffdd.opsconsole.shared.capacity;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Current catalog projections; purchase and trial snapshots are never refreshed from these values. */
public final class MonthlyTargetProjection {
    private MonthlyTargetProjection() { }
    public static boolean supportsDevice(String key, String type) {
        String classification = E3DeviceCapacityPolicy.classify(key, type);
        return classification != null && Set.of("capacityApplyToCloudShare","capacityApplyToS1","capacityApplyToPro",
                "capacityApplyToProV2","capacityApplyToRackP1","capacityApplyToRackP2").contains(classification);
    }
    public static void validate(String key, String type, BigDecimal target, BigDecimal band, Boolean clear, boolean checkEligibility) {
        if (target == null && band == null && clear == null) return;
        if (checkEligibility && !supportsDevice(key, type)) throw new BizException(422,"MONTHLY_TARGET_DEVICE_INELIGIBLE");
        if (Boolean.TRUE.equals(clear) && (target != null || band != null)) throw new BizException(422,"MONTHLY_TARGET_CLEAR_CONFLICT");
        if (target != null && (target.compareTo(BigDecimal.ONE) < 0 || target.compareTo(new BigDecimal("1000000")) > 0 || target.stripTrailingZeros().scale() > 2))
            throw new BizException(422,"MONTHLY_TARGET_USDT_INVALID");
        if (band != null && (band.compareTo(BigDecimal.ONE) < 0 || band.compareTo(new BigDecimal("50")) > 0 || band.stripTrailingZeros().scale() > 1))
            throw new BizException(422,"MONTHLY_TARGET_BAND_INVALID");
    }
    public static BigDecimal daily(BigDecimal target) { return target.divide(BigDecimal.valueOf(30),6,RoundingMode.HALF_UP); }
    public static BigDecimal shareYield(BigDecimal target, BigDecimal band, BigDecimal price, boolean high) {
        return target.multiply(BigDecimal.ONE.add(high ? band.movePointLeft(2) : band.movePointLeft(2).negate()))
                .multiply(BigDecimal.valueOf(36500)).divide(BigDecimal.valueOf(30).multiply(price),4,RoundingMode.HALF_UP);
    }
    public static boolean amplifies(BigDecimal oldTarget, BigDecimal oldBand, BigDecimal target, BigDecimal band) {
        if (oldTarget == null) return target != null;
        if (target == null) return true;
        return target.multiply(BigDecimal.ONE.add(band.movePointLeft(2)))
                .compareTo(oldTarget.multiply(BigDecimal.ONE.add(oldBand.movePointLeft(2)))) > 0;
    }
}
