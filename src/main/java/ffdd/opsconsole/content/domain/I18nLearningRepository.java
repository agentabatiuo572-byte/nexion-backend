package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface I18nLearningRepository {
    void ensureSeedData(LocalDateTime now);

    List<I18nNamespaceView> listNamespaces();

    Optional<I18nMessagePairView> findMessagePair(String messageKey);

    Optional<I18nMessagePairView> findPublishedMessagePair(String messageKey);

    Optional<I18nMessagePairView> findDraftMessagePair(String messageKey);

    default Optional<I18nMessagePairView> findMessagePairForUpdate(String messageKey) {
        return findMessagePair(messageKey);
    }

    default Optional<I18nMessagePairView> findPublishedMessagePairForUpdate(String messageKey) {
        return findPublishedMessagePair(messageKey);
    }

    List<I18nMessagePairView> listMessagePairs();

    default List<I18nMessagePairView> listMessageVersions(String messageKey) {
        return findMessagePair(messageKey).map(List::of).orElseGet(List::of);
    }

    default Optional<I18nMessagePairView> findMessageVersionForUpdate(String messageKey, String version) {
        return listMessageVersions(messageKey).stream()
                .filter(row -> row.version().equalsIgnoreCase(version))
                .findFirst();
    }

    default Map<String, String> listPublishedMessages(String namespace, String locale) { return Map.of(); }

    I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String vi, String status, LocalDateTime now);

    default I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String vi,
            Map<String, String> translations, String status, LocalDateTime now) {
        return saveMessagePair(messageKey, zh, en, vi, status, now);
    }

    default I18nMessagePairView saveMessageDraftCas(String messageKey, String zh, String en, String vi,
            Map<String, String> translations, String expectedVersion, LocalDateTime now) {
        return saveMessageDraftCas(messageKey, zh, en, vi, expectedVersion, now);
    }

    /**
     * Compatibility fallback for in-memory and test adapters.
     * The production MyBatis adapter overrides this with one database-atomic CAS.
     */
    default I18nMessagePairView saveMessageDraftCas(
            String messageKey,
            String zh,
            String en,
            String vi,
            String expectedVersion,
            LocalDateTime now) {
        I18nMessagePairView current = findMessagePairForUpdate(messageKey).orElse(null);
        String expected = expectedVersion == null ? "" : expectedVersion.trim();
        if ((current == null && !expected.isEmpty())
                || (current != null && !expected.equalsIgnoreCase(current.version()))) {
            throw new IllegalStateException("I18N_MESSAGE_VERSION_CONFLICT");
        }
        return saveMessagePair(messageKey, zh, en, vi, "draft", now);
    }

    default I18nMessagePairView saveMessagePair(String messageKey, String zh, String en, String status, LocalDateTime now) {
        return saveMessagePair(messageKey, zh, en, en, status, now);
    }

    I18nMessagePairView archiveMessage(String messageKey, LocalDateTime now);

    default I18nMessagePairView restoreMessageVersion(
            String messageKey,
            String targetVersion,
            String expectedCurrentVersion,
            LocalDateTime now) {
        throw new UnsupportedOperationException("I18N_MESSAGE_VERSION_RESTORE_NOT_AVAILABLE");
    }

    List<I18nIntegrityIssueView> listIntegrityIssues();

    I18nIntegrityIssueView markIssueFixed(String issueCode, LocalDateTime now);

    List<I18nIntegrityIssueView> recomputeIntegrity(LocalDateTime now);

    List<I18nHardcodedFindingView> listHardcodedFindings();

    List<LearningCourseView> listCourses();

    Optional<LearningCourseView> findCourse(String courseId);

    /** Public App projection; unpublished message revisions must never enter these views. */
    default List<LearningCourseView> listPublishedCourses() {
        throw new UnsupportedOperationException("LEARNING_PUBLISHED_COURSES_NOT_AVAILABLE");
    }

    default Optional<LearningCourseView> findPublishedCourse(String courseId) {
        throw new UnsupportedOperationException("LEARNING_PUBLISHED_COURSE_NOT_AVAILABLE");
    }

    default Optional<LearningCourseView> findPublishedCourseForUpdate(String courseId) {
        throw new UnsupportedOperationException("LEARNING_PUBLISHED_COURSE_LOCK_NOT_AVAILABLE");
    }

    /** Current read held through completion/award commit, blocking concurrent course writes. */
    default Optional<LearningCourseView> findCourseForUpdate(String courseId) {
        throw new UnsupportedOperationException("LEARNING_COURSE_LOCK_NOT_AVAILABLE");
    }

    default Optional<LearningCourseView> findLegacyCourseDraftForActivation(String courseId) {
        return findCourseForUpdate(courseId);
    }

    LearningCourseView createCourse(String courseId, LearningCourseUpsertRequest request, LocalDateTime now);

    LearningCourseView updateCourseDraft(String courseId, LearningCourseUpsertRequest request, LocalDateTime now);

    void deleteCourseDraft(String courseId, LocalDateTime now);

    LearningCourseView updateCourseStatus(String courseId, String status, LocalDateTime now);

    default LearningCourseView publishLegacyCourseDraft(LearningCourseView course, LocalDateTime now) {
        return updateCourseStatus(course.id(), "published", now);
    }

    LearningCourseView updateCourseReward(String courseId, BigDecimal rewardNex, LocalDateTime now);

    LearningCourseView updateFeaturedCourse(String courseId, LocalDateTime now);

    default List<LearningCourseVersionView> listCourseVersions(String courseId) { return List.of(); }

    default Optional<LearningCourseVersionView> findCourseVersion(String courseId, String version) { return Optional.empty(); }

    /** A null version selects the initial draft. Hold the version rows through approval or activation. */
    default Optional<LearningCourseVersionView> findCourseVersionForActivation(String courseId, String version) {
        return version == null
                ? listCourseVersions(courseId).stream().filter(row -> "DRAFT".equals(row.status())).findFirst()
                : findCourseVersion(courseId, version);
    }

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
