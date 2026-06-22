package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

public record I18nLearningStats(
        int managedKeys,
        int totalKeys,
        int integrityIssues,
        int coursesOnline,
        String weeklyNexPayout,
        BigDecimal coverageRatio,
        BigDecimal redlinePct) {
}
