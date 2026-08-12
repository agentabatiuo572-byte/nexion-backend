package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;

/** Run-scoped acceptance course definition; never aliases the formal catalog. */
public record LearningSandboxCourseRow(
        String courseId, String version, String status, String titleZh, String titleEn, String titleVi,
        String bodyZh, String bodyEn, String bodyVi, String category, String format, String level,
        BigDecimal rewardNex, String duration, boolean featured, String quizJson, Integer passScore,
        Integer retryLimit, String completionCondition, String rewardEvent, long revision) {}
