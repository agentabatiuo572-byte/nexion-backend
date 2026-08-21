package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Server-authoritative onboarding earnings comparison row. */
public record OnboardingYieldComparisonView(
        String configKey,
        String label,
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        Integer sortOrder,
        Long revision,
        LocalDateTime updatedAt) {
}
