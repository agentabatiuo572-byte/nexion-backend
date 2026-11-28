package ffdd.opsconsole.device.application;

import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.TierRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The current calibration rates used by both phone dispatch and the earnings API. */
@Service
@RequiredArgsConstructor
public class PhoneTaskRewards {
    public static final String UNAVAILABLE = "PHONE_TIER_REWARD_CONFIG_UNAVAILABLE";
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);
    private final OnboardingCalibrationMapper calibration;

    public TierRow currentRates(String productTier) {
        int tier = switch (String.valueOf(productTier)) {
            case "TIER-1" -> 1;
            case "TIER-2" -> 2;
            case "TIER-3" -> 3;
            case "TIER-4" -> 4;
            case "TIER-5" -> 5;
            default -> 0;
        };
        return tier == 0 ? null : calibration.activeTier(tier);
    }

    public static BigDecimal perTask(BigDecimal dailyRate, int durationSeconds) {
        return dailyRate.multiply(BigDecimal.valueOf(durationSeconds)).divide(SECONDS_PER_DAY, 6, RoundingMode.HALF_UP);
    }
}
