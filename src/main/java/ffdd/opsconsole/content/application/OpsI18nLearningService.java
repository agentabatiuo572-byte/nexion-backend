package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.I18nHardcodedFindingView;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningOverview;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nLearningStats;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.I18nNamespaceView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningCourseVersionView;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.domain.LearningMetricView;
import ffdd.opsconsole.content.domain.TutorialRewardRange;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningQuizQuestionRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsI18nLearningService {
    private static final String FOCUS_MESSAGE_KEY = "milestones.earnCross";
    private static final TutorialRewardRange REWARD_RANGE = new TutorialRewardRange(new BigDecimal("10"), new BigDecimal("50"));
    private static final List<String> CATEGORIES = List.of("Basics", "Earn", "Team", "Wealth", "Security");
    private static final List<String> FORMATS = List.of("Article", "Video", "Hands-on");
    private static final List<String> LEVELS = List.of("Beginner", "Intermediate", "Advanced");
    private static final List<String> STATUSES = List.of("draft", "ready", "published", "archived");
    private static final List<String> SOURCES = List.of(
            "nx_i18n_namespace",
            "nx_i18n_message",
            "nx_i18n_message_version",
            "nx_i18n_integrity_issue",
            "nx_i18n_hardcoded_finding",
            "nx_help_article",
            "B1 treasury coverage facade");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern MANUAL_URL_PATTERN = Pattern.compile("https?://|href\\s*=|href=#", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUNSET_PATTERN = Pattern.compile("premium|nex\\s*v?2|nexv2|points|积分", Pattern.CASE_INSENSITIVE);
    private static final Pattern COURSE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,80}$");
    private static final Pattern COURSE_VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]{0,8}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+$");

    private final I18nLearningRepository learningRepository;
    private final AuditLogService auditLogService;
    private final TreasuryCoverageFacade coverageFacade;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditObjectLockMapper lockMapper;

    public ApiResult<I18nLearningOverview> overview() {
        return ApiResult.ok(currentOverview());
    }

    @Transactional
    public ApiResult<I18nLearningOverview> rescan(String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        learningRepository.recomputeIntegrity(now());
        I18nLearningOverview overview = currentOverview();
        audit("I6_I18N_INTEGRITY_RESCANNED", "I18N_SCAN", "full", request.operator(), idempotencyKey, request.reason(), Map.of(
                "remainingIssues", overview.stats().integrityIssues()));
        return ApiResult.ok(overview);
    }

    public ApiResult<I18nMessagePairView> saveLocalizedDraft(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        ApiResult<Void> guard = requireLocalizedCopy(messageKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        I18nMessagePairView saved = learningRepository.saveMessagePair(messageKey.trim(), request.zh().trim(), request.en().trim(), request.vi().trim(), "draft", now());
        audit("I6_I18N_DRAFT_SAVED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "languages", "zh+en+vi",
                "placeholders", String.join(",", saved.placeholders())));
        return ApiResult.ok(saved);
    }

    @Transactional
    public ApiResult<I18nMessagePairView> publishLocalizedMessage(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        ApiResult<Void> guard = requireLocalizedCopy(messageKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        I18nMessagePairView draft = learningRepository.findDraftMessagePair(messageKey.trim()).orElse(null);
        if (draft == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "I18N_DRAFT_VERSION_NOT_FOUND");
        }
        if (!StringUtils.hasText(request.expectedVersion()) || !request.expectedVersion().trim().equals(draft.version())) {
            return ApiResult.fail(409, "I18N_DRAFT_VERSION_CONFLICT");
        }
        if (!draft.zh().equals(request.zh().trim()) || !draft.en().equals(request.en().trim()) || !draft.vi().equals(request.vi().trim())) {
            return ApiResult.fail(409, "I18N_DRAFT_CHANGED_SAVE_BEFORE_PUBLISH");
        }
        I18nMessagePairView published = learningRepository.saveMessagePair(messageKey.trim(), request.zh().trim(), request.en().trim(), request.vi().trim(), "published", now());
        audit("I6_I18N_MESSAGE_PUBLISHED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "languages", "zh+en+vi",
                "placeholders", String.join(",", published.placeholders())));
        return ApiResult.ok(published);
    }

    @Transactional
    public ApiResult<I18nIntegrityIssueView> fixIntegrity(String issueCode, String idempotencyKey, I18nIntegrityFixRequest request) {
        ApiResult<Void> guard = requireIntegrityFix(issueCode, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        Optional<I18nIntegrityIssueView> current = learningRepository.listIntegrityIssues().stream()
                .filter(row -> row.code().equals(issueCode.trim()))
                .findFirst();
        if (current.isEmpty()) {
            return ApiResult.fail(404, "I18N_INTEGRITY_ISSUE_NOT_FOUND");
        }
        if ("fixed".equals(current.get().status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        learningRepository.saveMessagePair(request.messageKey().trim(), request.zh().trim(), request.en().trim(), request.vi().trim(), "draft", now());
        List<I18nIntegrityIssueView> recomputed = learningRepository.recomputeIntegrity(now());
        I18nIntegrityIssueView remaining = recomputed.stream().filter(issue -> issue.code().equals(issueCode.trim())).findFirst()
                .orElse(new I18nIntegrityIssueView(issueCode.trim(), current.get().kind(), 0, List.of(), "fixed"));
        audit("I6_I18N_INTEGRITY_FIXED", "I18N_INTEGRITY", remaining.code(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "messageKey", request.messageKey().trim(), "remainingCount", remaining.cnt()));
        return ApiResult.ok(remaining);
    }

    @Transactional
    public ApiResult<LearningCourseView> createCourse(String courseId, String idempotencyKey, LearningCourseUpsertRequest request) {
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (learningRepository.findCourse(courseId.trim()).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "LEARNING_COURSE_ALREADY_EXISTS");
        }
        if (!"draft".equals(normalizeStatus(request.publishState()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_MUST_START_AS_DRAFT");
        }
        if (StringUtils.hasText(request.version()) && !"v1".equalsIgnoreCase(request.version().trim())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_INITIAL_VERSION_MUST_BE_V1");
        }
        LearningCourseView created = learningRepository.createCourse(courseId.trim(), normalizeCourseRequest(request), now());
        try {
            learningRepository.saveCourseVersion(created.id(), "v1", "DRAFT", normalizeCourseRequest(request), null, now());
        } catch (UnsupportedOperationException ignored) {
            // Compatibility for in-memory adapters; production MyBatis persists version snapshots.
        }
        audit("I7_LEARNING_COURSE_CREATED", "LEARNING_COURSE", created.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "category", created.category(),
                "status", created.status()));
        return ApiResult.ok(created);
    }

    @Transactional
    public ApiResult<LearningCourseView> updateCourseDraft(String courseId, String idempotencyKey, LearningCourseUpsertRequest request) {
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"draft".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (request.expectedRevision() != null && request.expectedRevision() != current.revision()) {
            return ApiResult.fail(409, "LEARNING_COURSE_REVISION_CONFLICT");
        }
        LearningCourseView updated;
        try {
            updated = learningRepository.updateCourseDraft(current.id(), normalizeCourseRequest(request), now());
        } catch (IllegalStateException ex) {
            if ("LEARNING_COURSE_REVISION_CONFLICT".equals(ex.getMessage())) {
                return ApiResult.fail(409, ex.getMessage());
            }
            throw ex;
        }
        try {
            LearningCourseVersionView snapshot = learningRepository.findCourseVersion(current.id(), current.version()).orElse(null);
            learningRepository.saveCourseVersion(current.id(), current.version(), "DRAFT", normalizeCourseRequest(request),
                    snapshot == null ? null : snapshot.revision(), now());
        } catch (UnsupportedOperationException ignored) {
            // Compatibility for in-memory adapters.
        }
        audit("I7_LEARNING_COURSE_DRAFT_UPDATED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromRevision", current.revision(), "toRevision", updated.revision()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<I18nMessagePairView> archiveLocalizedMessage(String messageKey, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (!StringUtils.hasText(messageKey) || learningRepository.findPublishedMessagePair(messageKey.trim()).isEmpty()) {
            return ApiResult.fail(404, "I18N_MESSAGE_NOT_FOUND");
        }
        I18nMessagePairView archived = learningRepository.archiveMessage(messageKey.trim(), now());
        audit("I6_I18N_MESSAGE_ARCHIVED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", archived.version(), "languages", "zh+en+vi"));
        return ApiResult.ok(archived);
    }

    @Transactional
    public ApiResult<Void> deleteCourseDraft(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"draft".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        learningRepository.deleteCourseDraft(current.id(), now());
        try {
            learningRepository.deleteCourseVersion(current.id(), current.version(), now());
        } catch (UnsupportedOperationException ignored) {
            // Compatibility for in-memory adapters.
        }
        audit("I7_LEARNING_COURSE_DRAFT_DELETED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "revision", current.revision()));
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<LearningCourseView> publishCourse(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"draft".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!hasCompleteQuiz(current)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_QUIZ_INCOMPLETE");
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (current.rewardNex().signum() > 0
                && coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        LearningCourseView updated;
        LearningCourseVersionView draft = learningRepository.listCourseVersions(current.id()).stream()
                .filter(version -> "DRAFT".equals(version.status()))
                .findFirst().orElse(null);
        if (draft == null) {
            updated = learningRepository.updateCourseStatus(current.id(), "published", now());
        } else {
            try {
                updated = learningRepository.activateCourseVersion(
                        current.id(), draft.version(), "DRAFT", current.version(), current.revision(), now());
            } catch (IllegalStateException ex) {
                return ApiResult.fail(409, ex.getMessage());
            }
        }
        audit("I7_LEARNING_COURSE_PUBLISHED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.status(),
                "to", updated.status()));
        return ApiResult.ok(updated);
    }

    public ApiResult<List<LearningCourseVersionView>> courseVersions(String courseId) {
        if (!StringUtils.hasText(courseId) || findCourse(courseId) == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        return ApiResult.ok(learningRepository.listCourseVersions(courseId.trim()));
    }

    @Transactional
    public ApiResult<LearningCourseVersionView> createCourseVersion(String courseId, String idempotencyKey,
            LearningCourseUpsertRequest request) {
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (!StringUtils.hasText(request.version()) || !COURSE_VERSION_PATTERN.matcher(request.version().trim()).matches()) {
            return ApiResult.fail(422, "LEARNING_COURSE_VERSION_INVALID");
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        List<LearningCourseVersionView> versions = learningRepository.listCourseVersions(courseId.trim());
        if (versions.stream().anyMatch(version -> "DRAFT".equals(version.status()))) {
            return ApiResult.fail(409, "LEARNING_COURSE_DRAFT_VERSION_ALREADY_EXISTS");
        }
        if (!"draft".equals(normalizeStatus(request.publishState()))) {
            return ApiResult.fail(422, "LEARNING_COURSE_VERSION_MUST_START_AS_DRAFT");
        }
        if (versions.stream().anyMatch(version -> version.version().equalsIgnoreCase(request.version().trim()))) {
            return ApiResult.fail(409, "LEARNING_COURSE_VERSION_ALREADY_EXISTS");
        }
        int requestedVersion = Integer.parseInt(request.version().trim().substring(1));
        int maxStoredVersion = versions.stream().map(LearningCourseVersionView::version)
                .filter(value -> COURSE_VERSION_PATTERN.matcher(value).matches())
                .mapToInt(value -> Integer.parseInt(value.substring(1))).max().orElse(0);
        int currentVersion = COURSE_VERSION_PATTERN.matcher(current.version()).matches()
                ? Integer.parseInt(current.version().substring(1)) : 0;
        int maxVersion = Math.max(maxStoredVersion, currentVersion);
        if (requestedVersion <= maxVersion) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_NOT_INCREMENTED");
        LearningCourseVersionView saved;
        try {
            saved = learningRepository.saveCourseVersion(courseId.trim(), request.version().trim(),
                    "DRAFT", normalizeCourseRequest(request), null, now());
        } catch (IllegalStateException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        audit("I7_LEARNING_COURSE_VERSION_CREATED", "LEARNING_COURSE_VERSION", courseId + ":" + saved.version(),
                request.operator(), idempotencyKey, request.reason(), Map.of("version", saved.version()));
        return ApiResult.ok(saved);
    }

    @Transactional
    public ApiResult<LearningCourseVersionView> updateCourseVersion(String courseId, String version,
            String idempotencyKey, LearningCourseUpsertRequest request) {
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) return fail(guard);
        LearningCourseVersionView current = learningRepository.findCourseVersion(courseId, version).orElse(null);
        if (current == null) return ApiResult.fail(404, "LEARNING_COURSE_VERSION_NOT_FOUND");
        if (!"DRAFT".equals(current.status())) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_NOT_DRAFT");
        try {
            LearningCourseVersionView updated = learningRepository.saveCourseVersion(courseId, version, "DRAFT",
                    normalizeCourseRequest(request), request.expectedRevision(), now());
            audit("I7_LEARNING_COURSE_VERSION_UPDATED", "LEARNING_COURSE_VERSION", courseId + ":" + version,
                    request.operator(), idempotencyKey, request.reason(), Map.of("revision", updated.revision()));
            return ApiResult.ok(updated);
        } catch (IllegalStateException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
    }

    @Transactional
    public ApiResult<Void> deleteCourseVersion(String courseId, String version, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        LearningCourseVersionView current = learningRepository.findCourseVersion(courseId, version).orElse(null);
        if (current == null) return ApiResult.fail(404, "LEARNING_COURSE_VERSION_NOT_FOUND");
        if (!"DRAFT".equals(current.status())) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_NOT_DRAFT");
        learningRepository.deleteCourseVersion(courseId, version, now());
        audit("I7_LEARNING_COURSE_VERSION_DELETED", "LEARNING_COURSE_VERSION", courseId + ":" + version,
                request.operator(), idempotencyKey, request.reason(), Map.of("version", version));
        return ApiResult.ok(null);
    }

    @Transactional
    public ApiResult<LearningCourseView> publishCourseVersion(String courseId, String version,
            String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        LearningCourseVersionView target = learningRepository.findCourseVersion(courseId, version).orElse(null);
        if (target == null) return ApiResult.fail(404, "LEARNING_COURSE_VERSION_NOT_FOUND");
        if (!"DRAFT".equals(target.status())) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_NOT_DRAFT");
        LearningCourseView current = findCourse(courseId);
        if (current == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        if (!hasCompleteQuizPayload(target.payload())) return ApiResult.fail(422, "LEARNING_COURSE_QUIZ_INCOMPLETE");
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (target.payload().rewardNex().signum() > 0 && coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        LearningCourseView published;
        try {
            published = learningRepository.activateCourseVersion(
                    courseId, version, "DRAFT", current.version(), current.revision(), now());
        } catch (IllegalStateException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        audit("I7_LEARNING_COURSE_VERSION_PUBLISHED", "LEARNING_COURSE_VERSION", courseId + ":" + version,
                request.operator(), idempotencyKey, request.reason(), Map.of("version", version));
        return ApiResult.ok(published);
    }

    @Transactional
    public ApiResult<LearningCourseView> rollbackCourseVersion(String courseId, String version,
            String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        LearningCourseView current = findCourse(courseId);
        LearningCourseVersionView target = learningRepository.findCourseVersion(courseId, version).orElse(null);
        if (current == null || target == null) return ApiResult.fail(404, "LEARNING_COURSE_VERSION_NOT_FOUND");
        if (!"SUPERSEDED".equals(target.status()) || version.equals(current.version())) {
            return ApiResult.fail(409, "LEARNING_COURSE_ROLLBACK_TARGET_INVALID");
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (target.payload().rewardNex().compareTo(current.rewardNex()) > 0
                && coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            auditRejected("I7_LEARNING_COURSE_VERSION_ROLLBACK_REJECTED", "LEARNING_COURSE_VERSION",
                    courseId + ":" + version, request.operator(), idempotencyKey, request.reason(), Map.of(
                            "targetVersion", version,
                            "rewardNex", target.payload().rewardNex(),
                            "coverageRatio", coverage.coverageRatio(),
                            "redlinePct", coverage.redlinePct()));
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        LearningCourseView rolledBack;
        try {
            rolledBack = learningRepository.activateCourseVersion(
                    courseId, version, "SUPERSEDED", current.version(), current.revision(), now());
        } catch (IllegalStateException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        audit("I7_LEARNING_COURSE_VERSION_ROLLED_BACK", "LEARNING_COURSE_VERSION", courseId + ":" + version,
                request.operator(), idempotencyKey, request.reason(), Map.of(
                        "from", current.version(),
                        "to", version,
                        "rewardNex", target.payload().rewardNex(),
                        "coverageRatio", coverage.coverageRatio(),
                        "redlinePct", coverage.redlinePct()));
        return ApiResult.ok(rolledBack);
    }

    @Transactional
    public ApiResult<LearningCourseView> archiveCourse(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"published".equals(current.status()) || current.featured()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LearningCourseView updated = learningRepository.updateCourseStatus(current.id(), "archived", now());
        audit("I7_LEARNING_COURSE_ARCHIVED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.status(),
                "rewardNex", current.rewardNex()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<LearningCourseView> updateCourseReward(String courseId, String idempotencyKey, LearningRewardUpdateRequest request) {
        ApiResult<Void> guard = requireRewardUpdate(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "learning_course", courseId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"published".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (request.rewardNex().compareTo(current.rewardNex()) > 0
                && coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        LearningCourseView updated = learningRepository.updateCourseReward(current.id(), request.rewardNex().setScale(6, RoundingMode.HALF_UP), now());
        audit("I7_LEARNING_REWARD_CHANGED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.rewardNex(),
                "to", updated.rewardNex(),
                "coverageRatio", coverage.coverageRatio(),
                "redlinePct", coverage.redlinePct()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<LearningCourseView> updateFeaturedCourse(String idempotencyKey, LearningFeaturedUpdateRequest request) {
        ApiResult<Void> guard = requireFeaturedUpdate(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(request.courseId());
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"published".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LearningCourseView updated = learningRepository.updateFeaturedCourse(current.id(), now());
        audit("I7_LEARNING_FEATURED_CHANGED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "featured", true));
        return ApiResult.ok(updated);
    }

    private I18nLearningOverview currentOverview() {
        List<I18nNamespaceView> namespaces = learningRepository.listNamespaces();
        List<I18nMessagePairView> messages = learningRepository.listMessagePairs();
        List<I18nIntegrityIssueView> issues = learningRepository.listIntegrityIssues();
        List<I18nHardcodedFindingView> findings = learningRepository.listHardcodedFindings();
        List<LearningCourseView> courses = learningRepository.listCourses();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        int managedKeys = namespaces.stream().mapToInt(I18nNamespaceView::keys).sum();
        int openIssues = issues.stream()
                .filter(row -> !"fixed".equals(row.status()))
                .mapToInt(I18nIntegrityIssueView::cnt)
                .sum();
        int online = (int) courses.stream().filter(row -> "published".equals(row.status())).count();
        return new I18nLearningOverview(
                new I18nLearningStats(
                        managedKeys,
                        managedKeys,
                        openIssues,
                        online,
                        weeklyNexPayout(),
                        coverage.coverageRatio(),
                        coverage.redlinePct()),
                namespaces,
                issues,
                findings,
                messages.stream().filter(row -> FOCUS_MESSAGE_KEY.equals(row.messageKey())).findFirst().orElse(null),
                messages,
                courses,
                REWARD_RANGE,
                featuredCourseId(courses),
                metrics(online),
                CATEGORIES,
                FORMATS,
                LEVELS,
                STATUSES,
                SOURCES);
    }

    private List<LearningMetricView> metrics(int onlineCourses) {
        return List.of();
    }

    private String weeklyNexPayout() {
        return learningRepository.weeklyGrantedLearningReward().setScale(2, RoundingMode.HALF_UP).toPlainString() + " NEX";
    }

    private String featuredCourseId(List<LearningCourseView> courses) {
        return courses.stream()
                .filter(LearningCourseView::featured)
                .map(LearningCourseView::id)
                .findFirst()
                .orElseGet(() -> courses.stream()
                        .filter(row -> "published".equals(row.status()))
                        .map(LearningCourseView::id)
                        .findFirst()
                        .orElse(""));
    }

    private ApiResult<Void> requireCoursePayload(String courseId, String idempotencyKey, LearningCourseUpsertRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(courseId) || !COURSE_ID_PATTERN.matcher(courseId.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_ID_INVALID");
        }
        if (request == null || !StringUtils.hasText(request.titleZh()) || !StringUtils.hasText(request.titleEn())
                || !StringUtils.hasText(request.titleVi()) || !StringUtils.hasText(request.bodyZh())
                || !StringUtils.hasText(request.bodyEn()) || !StringUtils.hasText(request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_COPY_REQUIRED");
        }
        if (!containsIgnoreCase(CATEGORIES, request.category()) || !containsIgnoreCase(FORMATS, request.format())
                || !containsIgnoreCase(LEVELS, request.difficulty()) || !STATUSES.contains(normalizeStatus(request.publishState()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_ENUM_UNSUPPORTED");
        }
        if (invalidReward(request.rewardNex())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_REWARD_OUT_OF_RANGE");
        }
        if (containsUnsafeText(request.titleZh(), request.titleEn(), request.titleVi(), request.bodyZh(), request.bodyEn(), request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (containsSunsetCapability(request.titleZh(), request.titleEn(), request.titleVi(), request.bodyZh(), request.bodyEn(), request.bodyVi())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (!placeholders(request.bodyZh()).equals(placeholders(request.bodyEn()))
                || !placeholders(request.bodyZh()).equals(placeholders(request.bodyVi()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_PLACEHOLDERS_MISMATCH");
        }
        ApiResult<Void> quizGuard = requireQuizPayload(request);
        if (quizGuard != null) {
            return quizGuard;
        }
        return null;
    }

    private ApiResult<Void> requireQuizPayload(LearningCourseUpsertRequest request) {
        List<LearningQuizQuestionRequest> questions = request.quizQuestions() == null ? List.of() : request.quizQuestions();
        if (questions.isEmpty()) {
            return null;
        }
        if (request.passScore() == null || request.passScore() < 1 || request.passScore() > 100
                || request.retryLimit() == null || request.retryLimit() < 0 || request.retryLimit() > 10
                || !StringUtils.hasText(request.completionCondition()) || !StringUtils.hasText(request.rewardEvent())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_QUIZ_INCOMPLETE");
        }
        for (LearningQuizQuestionRequest question : questions) {
            List<String> zhOptions = question == null || question.optionsZh() == null ? List.of() : question.optionsZh();
            List<String> enOptions = question == null || question.optionsEn() == null ? List.of() : question.optionsEn();
            List<String> viOptions = question == null || question.optionsVi() == null ? List.of() : question.optionsVi();
            if (question == null || !StringUtils.hasText(question.questionId())
                    || !StringUtils.hasText(question.questionZh()) || !StringUtils.hasText(question.questionEn())
                    || !StringUtils.hasText(question.questionVi())
                    || zhOptions.size() < 2 || zhOptions.size() != enOptions.size() || zhOptions.size() != viOptions.size()
                    || zhOptions.stream().anyMatch(value -> !StringUtils.hasText(value))
                    || enOptions.stream().anyMatch(value -> !StringUtils.hasText(value))
                    || viOptions.stream().anyMatch(value -> !StringUtils.hasText(value))
                    || question.correctOptionIndex() == null || question.correctOptionIndex() < 0
                    || question.correctOptionIndex() >= zhOptions.size()) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_QUIZ_INCOMPLETE");
            }
        }
        return null;
    }

    private boolean hasCompleteQuiz(LearningCourseView course) {
        if (course.quizQuestions() == null || course.quizQuestions().isEmpty()
                || course.passScore() == null || course.passScore() < 1 || course.passScore() > 100
                || course.retryLimit() == null || course.retryLimit() < 0
                || !StringUtils.hasText(course.completionCondition()) || !StringUtils.hasText(course.rewardEvent())) {
            return false;
        }
        for (LearningQuizQuestionView question : course.quizQuestions()) {
            if (!StringUtils.hasText(question.questionId()) || !StringUtils.hasText(question.questionZh())
                    || !StringUtils.hasText(question.questionEn()) || !StringUtils.hasText(question.questionVi())
                    || question.optionsZh() == null || question.optionsEn() == null || question.optionsVi() == null
                    || question.optionsZh().size() < 2
                    || question.optionsZh().size() != question.optionsEn().size()
                    || question.optionsZh().size() != question.optionsVi().size()
                    || question.correctOptionIndex() < 0 || question.correctOptionIndex() >= question.optionsZh().size()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasCompleteQuizPayload(LearningCourseUpsertRequest request) {
        return request != null && request.quizQuestions() != null && !request.quizQuestions().isEmpty()
                && requireQuizPayload(request) == null;
    }

    private ApiResult<Void> requireLocalizedCopy(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(messageKey) || !MESSAGE_KEY_PATTERN.matcher(messageKey.trim()).matches()
                || request == null || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en()) || !StringUtils.hasText(request.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_COPY_REQUIRED");
        }
        if (containsUnsafeText(messageKey, request.zh(), request.en(), request.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (containsSunsetCapability(messageKey, request.zh(), request.en(), request.vi())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (!placeholders(request.zh()).equals(placeholders(request.en())) || !placeholders(request.zh()).equals(placeholders(request.vi()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private ApiResult<Void> requireIntegrityFix(String issueCode, String idempotencyKey, I18nIntegrityFixRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(issueCode) || request == null || !StringUtils.hasText(request.messageKey())
                || !MESSAGE_KEY_PATTERN.matcher(request.messageKey().trim()).matches()
                || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en()) || !StringUtils.hasText(request.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_INTEGRITY_FIX_REQUIRED");
        }
        if (containsUnsafeText(request.messageKey(), request.zh(), request.en(), request.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (!placeholders(request.zh()).equals(placeholders(request.en())) || !placeholders(request.zh()).equals(placeholders(request.vi()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private ApiResult<Void> requireRewardUpdate(String idempotencyKey, LearningRewardUpdateRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (request == null || invalidReward(request.rewardNex())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_REWARD_OUT_OF_RANGE");
        }
        return null;
    }

    private ApiResult<Void> requireFeaturedUpdate(String idempotencyKey, LearningFeaturedUpdateRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (request == null || !StringUtils.hasText(request.courseId())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_FEATURED_COURSE_REQUIRED");
        }
        return null;
    }

    private ApiResult<Void> requireAction(String idempotencyKey, I18nActionRequest request) {
        return requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
    }

    private ApiResult<Void> requireIdempotencyAndReason(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private LearningCourseUpsertRequest normalizeCourseRequest(LearningCourseUpsertRequest request) {
        return new LearningCourseUpsertRequest(
                request.titleZh().trim(),
                request.titleEn().trim(),
                request.bodyZh().trim(),
                request.bodyEn().trim(),
                canonical(CATEGORIES, request.category()),
                canonical(FORMATS, request.format()),
                canonical(LEVELS, request.difficulty()),
                request.rewardNex().setScale(6, RoundingMode.HALF_UP),
                StringUtils.hasText(request.duration()) ? request.duration().trim() : "5 min",
                normalizeStatus(request.publishState()),
                operator(request.operator()),
                request.reason().trim(),
                request.quizQuestions() == null ? List.of() : request.quizQuestions().stream().map(question -> new LearningQuizQuestionRequest(
                        question.questionId().trim(), question.questionZh().trim(), question.questionEn().trim(),
                        question.optionsZh().stream().map(String::trim).toList(),
                        question.optionsEn().stream().map(String::trim).toList(), question.correctOptionIndex(),
                        question.questionVi().trim(), question.optionsVi().stream().map(String::trim).toList())).toList(),
                request.passScore(), request.retryLimit(), trimToNull(request.completionCondition()),
                trimToNull(request.rewardEvent()), request.expectedRevision(),
                request.titleVi().trim(), request.bodyVi().trim(), trimToNull(request.version()));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean invalidReward(BigDecimal rewardNex) {
        return rewardNex == null || rewardNex.compareTo(REWARD_RANGE.min()) < 0 || rewardNex.compareTo(REWARD_RANGE.max()) > 0;
    }

    private boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(row -> row.equalsIgnoreCase(value == null ? "" : value.trim()));
    }

    private String canonical(List<String> values, String value) {
        return values.stream()
                .filter(row -> row.equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElse(values.get(0));
    }

    private String normalizeStatus(String status) {
        String value = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "draft";
        return STATUSES.contains(value) ? value : "";
    }

    private LearningCourseView findCourse(String courseId) {
        return StringUtils.hasText(courseId) ? learningRepository.findCourse(courseId.trim()).orElse(null) : null;
    }

    private boolean containsUnsafeText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)
                    && (JSON_LIKE_PATTERN.matcher(value).find() || MANUAL_URL_PATTERN.matcher(value).find())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSunsetCapability(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value) && SUNSET_PATTERN.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    private Set<String> placeholders(String text) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text == null ? "" : text);
        Set<String> values = new TreeSet<>();
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private <T> ApiResult<T> fail(ApiResult<Void> guard) {
        return ApiResult.fail(guard.getCode(), guard.getMessage());
    }

    private void audit(String action, String resourceType, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel(action.contains("REWARD") ? "MEDIUM" : "LOW")
                .detail(detail)
                .build());
    }

    private void auditRejected(String action, String resourceType, String resourceId, String operator,
            String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}
