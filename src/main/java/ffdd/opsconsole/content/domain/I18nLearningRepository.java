package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface I18nLearningRepository {
    List<I18nNamespaceView> listNamespaces();

    Optional<I18nMessagePairView> findMessagePair(String messageKey);

    I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String status, LocalDateTime now);

    I18nMessagePairView markMarketingExperiment(String messageKey, LocalDateTime now);

    List<I18nIntegrityIssueView> listIntegrityIssues();

    I18nIntegrityIssueView markIssueFixed(String issueCode, LocalDateTime now);

    List<I18nHardcodedFindingView> listHardcodedFindings();

    List<LearningCourseView> listCourses();

    Optional<LearningCourseView> findCourse(String courseId);

    LearningCourseView createCourse(String courseId, LearningCourseUpsertRequest request, LocalDateTime now);

    LearningCourseView updateCourseStatus(String courseId, String status, LocalDateTime now);

    LearningCourseView updateCourseReward(String courseId, BigDecimal rewardNex, LocalDateTime now);

    LearningCourseView updateFeaturedCourse(String courseId, LocalDateTime now);
}
