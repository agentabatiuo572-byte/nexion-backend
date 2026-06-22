package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;

public record LearningCourseUpsertRequest(
        String titleZh,
        String titleEn,
        String bodyZh,
        String bodyEn,
        String category,
        String format,
        String difficulty,
        BigDecimal rewardNex,
        String duration,
        String publishState,
        String operator,
        String reason) {
}
