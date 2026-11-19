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
        LocalDateTime updatedAt, String productNo, Long bindingRevision) {
    public OnboardingYieldComparisonView(String configKey, String label, BigDecimal dailyUsdt, BigDecimal dailyNex,
            Integer sortOrder, Long revision, LocalDateTime updatedAt) {
        this(configKey,label,dailyUsdt,dailyNex,sortOrder,revision,updatedAt,null,null);
    }
}
