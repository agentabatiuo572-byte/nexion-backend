package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface I18nLearningRepository {
    void ensureSeedData(LocalDateTime now);

    List<I18nNamespaceView> listNamespaces();

    Optional<I18nMessagePairView> findMessagePair(String messageKey);

    Optional<I18nMessagePairView> findPublishedMessagePair(String messageKey);

    Optional<I18nMessagePairView> findDraftMessagePair(String messageKey);

    List<I18nMessagePairView> listMessagePairs();

    I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String vi, String status, LocalDateTime now);

    default I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String status, LocalDateTime now) {
        return saveMessagePair(messageKey, zh, en, en, status, now);
    }

    I18nMessagePairView archiveMessage(String messageKey, LocalDateTime now);

    List<I18nIntegrityIssueView> listIntegrityIssues();

    I18nIntegrityIssueView markIssueFixed(String issueCode, LocalDateTime now);

    List<I18nIntegrityIssueView> recomputeIntegrity(LocalDateTime now);

    List<I18nHardcodedFindingView> listHardcodedFindings();

    List<LearningCourseView> listCourses();

    Optional<LearningCourseView> findCourse(String courseId);

    LearningCourseView createCourse(String courseId, LearningCourseUpsertRequest request, LocalDateTime now);

    LearningCourseView updateCourseDraft(String courseId, LearningCourseUpsertRequest request, LocalDateTime now);

    void deleteCourseDraft(String courseId, LocalDateTime now);

    LearningCourseView updateCourseStatus(String courseId, String status, LocalDateTime now);

    LearningCourseView updateCourseReward(String courseId, BigDecimal rewardNex, LocalDateTime now);

    LearningCourseView updateFeaturedCourse(String courseId, LocalDateTime now);

    default List<LearningCourseVersionView> listCourseVersions(String courseId) { return List.of(); }

    default Optional<LearningCourseVersionView> findCourseVersion(String courseId, String version) { return Optional.empty(); }

    default LearningCourseVersionView saveCourseVersion(String courseId, String version, String status,
            LearningCourseUpsertRequest request, Long expectedRevision, LocalDateTime now) {
        throw new UnsupportedOperationException("LEARNING_COURSE_VERSION_STORAGE_NOT_AVAILABLE");
    }

    default void deleteCourseVersion(String courseId, String version, LocalDateTime now) {
        throw new UnsupportedOperationException("LEARNING_COURSE_VERSION_STORAGE_NOT_AVAILABLE");
    }

    default LearningCourseView activateCourseVersion(String courseId, String version, String expectedStatus,
            String expectedCurrentVersion, long expectedCurrentRevision, LocalDateTime now) {
        throw new UnsupportedOperationException("LEARNING_COURSE_VERSION_STORAGE_NOT_AVAILABLE");
    }

    default BigDecimal weeklyGrantedLearningReward() { return BigDecimal.ZERO; }
}
