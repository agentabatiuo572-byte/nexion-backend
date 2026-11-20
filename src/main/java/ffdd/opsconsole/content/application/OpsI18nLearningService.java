package ffdd.opsconsole.content.application;

import static ffdd.opsconsole.content.application.LearningCourseCopy.CATEGORIES;
import static ffdd.opsconsole.content.application.LearningCourseCopy.FORMATS;
import static ffdd.opsconsole.content.application.LearningCourseCopy.LEVELS;
import static ffdd.opsconsole.content.application.LearningCourseCopy.STATUSES;
import static ffdd.opsconsole.content.application.LearningCourseCopy.normalizeStatus;
import static ffdd.opsconsole.content.domain.I18nCopyQuality.containsSunsetCapability;
import static ffdd.opsconsole.content.domain.I18nCopyQuality.containsUnsafeText;
import static ffdd.opsconsole.content.domain.I18nCopyQuality.hasCopyText;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.I18nHardcodedFindingView;
import ffdd.opsconsole.content.domain.I18nCopyQuality;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningOverview;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.I18nLearningStats;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.MessageTranslations;
import ffdd.opsconsole.content.domain.I18nNamespaceView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningCourseVersionView;
import ffdd.opsconsole.content.domain.LearningCourseNextVersionView;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.domain.LearningMetricView;
import ffdd.opsconsole.content.domain.TutorialRewardRange;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.I18nVersionActionRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.application.PlatformExperienceConfigService;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsI18nLearningService {
    public static final String COURSE_REWARD_ADJUST = "i7_course_reward_adjust";
    private static final String FOCUS_MESSAGE_KEY = "milestones.earnCross";
    private static final TutorialRewardRange REWARD_RANGE = new TutorialRewardRange(new BigDecimal("10"), new BigDecimal("50"));
    private static final List<String> SOURCES = List.of(
            "nx_i18n_namespace",
            "nx_i18n_message",
            "nx_i18n_message_version",
            "nx_i18n_integrity_issue",
            "nx_i18n_hardcoded_finding",
            "nx_help_article",
            "B1 treasury coverage facade");
    private static final Pattern COURSE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,80}$");
    private static final Pattern COURSE_VERSION_PATTERN = Pattern.compile("^v[1-9][0-9]{0,8}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE_KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+$");

    private final I18nLearningRepository learningRepository;
    private final AuditLogService auditLogService;
    private final TreasuryCoverageFacade coverageFacade;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final AuditObjectLockMapper lockMapper;
    private final EventOutboxService eventOutboxService;
    private Supplier<List<String>> operatingLanguages = () -> List.of("en");

    @Autowired
    void setPlatformExperienceConfigService(PlatformExperienceConfigService service) {
        operatingLanguages = service::operatingLanguages;
    }

    public ApiResult<I18nLearningOverview> overview() {
        return ApiResult.ok(currentOverview());
    }

    @Transactional
    public ApiResult<I18nLearningOverview> rescan(String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LocalDateTime scannedAt = now();
        learningRepository.recomputeIntegrity(scannedAt, operatingLanguages.get());
        I18nLearningOverview overview = currentOverview(scannedAt);
        audit("I6_I18N_INTEGRITY_RESCANNED", "I18N_SCAN", "full", request.operator(), idempotencyKey, request.reason(), Map.of(
                "remainingIssues", overview.stats().integrityIssues()));
        return ApiResult.ok(overview);
    }

    @Transactional
    public ApiResult<I18nMessagePairView> saveLocalizedDraft(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        MessageCopy copy = request == null ? null
                : completeMessageCopy(request.zh(), request.en(), request.vi(), operatingLanguages.get());
        ApiResult<Void> guard = requireLocalizedCopy(messageKey, idempotencyKey, request, copy);
        if (guard != null) {
            return fail(guard);
        }
        I18nMessagePairView current = learningRepository.findMessagePairForUpdate(messageKey.trim()).orElse(null);
        // I6 is an editor for message keys already registered by an App release.
        // Creating a syntactically valid key here would persist and publish a value
        // that no shipped App screen consumes, while falsely reporting success.
        if (current == null) {
            return ApiResult.fail(404, "I18N_MESSAGE_KEY_NOT_REGISTERED");
        }
        if (!StringUtils.hasText(request.expectedVersion())
                || !request.expectedVersion().trim().equals(current.version())) {
            return ApiResult.fail(409, "I18N_MESSAGE_VERSION_CONFLICT");
        }
        Map<String, String> translations = request.translations() == null ? current.translations()
                : MessageTranslations.clean(request.translations());
        if (!I18nCopyQuality.placeholdersMatch(request.en(), translations))
            return ApiResult.fail(422, "I18N_PLACEHOLDERS_MISMATCH");
        I18nMessagePairView saved;
        try {
            saved = learningRepository.saveMessageDraftCas(
                    messageKey.trim(),
                    copy.zh().trim(),
                    copy.en().trim(),
                    copy.vi().trim(),
                    translations,
                    request.expectedVersion(),
                    now());
        } catch (IllegalStateException ex) {
            if ("I18N_MESSAGE_VERSION_CONFLICT".equals(ex.getMessage())) {
                return ApiResult.fail(409, ex.getMessage());
            }
            throw ex;
        }
        audit("I6_I18N_DRAFT_SAVED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "languages", saved.localeSet(),
                "placeholders", String.join(",", saved.placeholders())));
        return ApiResult.ok(saved);
    }

    @Transactional
    public ApiResult<I18nMessagePairView> publishLocalizedMessage(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        ApiResult<Void> guard = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) return fail(guard);
        if (request == null || !StringUtils.hasText(messageKey) || !MESSAGE_KEY_PATTERN.matcher(messageKey.trim()).matches()
                || !hasCopyText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_COPY_REQUIRED");
        }
        I18nMessagePairView draft = learningRepository.findMessagePairForUpdate(messageKey.trim()).orElse(null);
        if (draft == null || !"draft".equals(draft.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "I18N_DRAFT_VERSION_NOT_FOUND");
        }
        if (!StringUtils.hasText(request.expectedVersion()) || !request.expectedVersion().trim().equals(draft.version())) {
            return ApiResult.fail(409, "I18N_DRAFT_VERSION_CONFLICT");
        }
        String en = request.en().trim();
        String zhEcho = I18nCopyQuality.hasCopyText(request.zh()) ? request.zh().trim() : en;
        String viEcho = I18nCopyQuality.hasCopyText(request.vi()) ? request.vi().trim() : en;
        if (!draft.zh().equals(zhEcho) || !draft.en().equals(en) || !draft.vi().equals(viEcho)
                || request.translations() != null && !draft.translations().equals(MessageTranslations.clean(request.translations()))) {
            return ApiResult.fail(409, "I18N_DRAFT_CHANGED_SAVE_BEFORE_PUBLISH");
        }
        ApiResult<Void> validation = validateMessageCopy(messageKey,
                new MessageCopy(draft.zh(), draft.en(), draft.vi()), request.translations());
        if (validation != null) return fail(validation);
        ApiResult<Void> quality = requirePublishableCopy(draft.zh(), draft.en(), draft.vi());
        if (quality != null) return fail(quality);
        I18nMessagePairView published = learningRepository.saveMessagePair(
                messageKey.trim(), draft.zh(), draft.en(), draft.vi(), draft.translations(), "published", now());
        audit("I6_I18N_MESSAGE_PUBLISHED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "languages", published.localeSet(),
                "placeholders", String.join(",", published.placeholders())));
        eventOutboxService.publish("I18N_MESSAGE", messageKey.trim(), "admin.i18n_published", Map.of(
                "message_key", messageKey.trim(),
                "version", published.version(),
                "locale_set", published.localeSet(),
                "operator", operator(request.operator())));
        return ApiResult.ok(published);
    }

    @Transactional
    public ApiResult<I18nIntegrityIssueView> fixIntegrity(String issueCode, String idempotencyKey, I18nIntegrityFixRequest request) {
        List<String> requiredLanguages = operatingLanguages.get();
        MessageCopy copy = request == null ? null
                : completeMessageCopy(request.zh(), request.en(), request.vi(), requiredLanguages);
        ApiResult<Void> guard = requireIntegrityFix(issueCode, idempotencyKey, request, copy);
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
        String messageKey = request.messageKey().trim();
        // Integrity repair is another write entrance into the I6 catalogue. It
        // must obey the same shipped-key boundary as the normal draft editor;
        // otherwise an operator can use a real issue code to create and later
        // publish a key no App release consumes.
        I18nMessagePairView previous = learningRepository.findMessagePairForUpdate(messageKey).orElse(null);
        if (previous == null) {
            return ApiResult.fail(404, "I18N_MESSAGE_KEY_NOT_REGISTERED");
        }
        Map<String, String> translations = request.translations() == null ? previous.translations() : request.translations();
        ApiResult<Void> translationGuard = validateMessageCopy(messageKey, copy, translations);
        if (translationGuard != null) return fail(translationGuard);
        Optional<I18nMessagePairView> published = learningRepository.findPublishedMessagePair(messageKey);
        I18nMessagePairView saved = learningRepository.saveMessagePair(
                messageKey, copy.zh().trim(), copy.en().trim(), copy.vi().trim(),
                MessageTranslations.clean(translations), "draft", now());
        List<I18nIntegrityIssueView> recomputed = learningRepository.recomputeIntegrity(now(), requiredLanguages);
        I18nIntegrityIssueView remaining = recomputed.stream().filter(issue -> issue.code().equals(issueCode.trim())).findFirst()
                .orElse(new I18nIntegrityIssueView(issueCode.trim(), current.get().kind(), 0, List.of(), "fixed"));
        int repairedCount = I18nCopyQuality.integrityIssueCodes(published.orElse(previous), requiredLanguages).contains(remaining.code())
                && !I18nCopyQuality.integrityIssueCodes(published.orElse(saved), requiredLanguages).contains(remaining.code()) ? 1 : 0;
        audit("I6_I18N_INTEGRITY_FIXED", "I18N_INTEGRITY", remaining.code(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "messageKey", saved.messageKey(), "version", saved.version(),
                "remainingCount", remaining.cnt(), "repairedCount", repairedCount));
        return ApiResult.ok(new I18nIntegrityIssueView(remaining.code(), remaining.kind(), remaining.cnt(),
                remaining.samples(), remaining.status(), repairedCount, saved.messageKey(), saved.version()));
    }

    @Transactional
    public ApiResult<LearningCourseView> createCourse(String courseId, String idempotencyKey, LearningCourseUpsertRequest request) {
        request = prepareCourseCopy(request);
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
        // "Completion condition" and "reward event" never drove completion or payout: the learning flow
        // judges by pass score only. Retiring them means refusing new values instead of storing a setting
        // that reads like a promise.
        if (StringUtils.hasText(request.completionCondition()) || StringUtils.hasText(request.rewardEvent())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_OPTION_RETIRED");
        }
        LearningCourseUpsertRequest normalized = normalizeCourseRequest(request);
        LearningCourseView created = learningRepository.createCourse(courseId.trim(), normalized, now());
        try {
            learningRepository.saveCourseVersion(created.id(), "v1", "DRAFT", normalized, null, now());
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
        request = prepareCourseCopy(request);
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourseForUpdate(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"draft".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (request.expectedRevision() != null && request.expectedRevision() != current.revision()) {
            return ApiResult.fail(409, "LEARNING_COURSE_REVISION_CONFLICT");
        }
        if (retiredOptionChanged(request.completionCondition(), current.completionCondition())
                || retiredOptionChanged(request.rewardEvent(), current.rewardEvent())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_OPTION_RETIRED");
        }
        LearningCourseVersionView snapshot = learningRepository.findCourseVersion(current.id(), current.version()).orElse(null);
        if (snapshot != null) {
            request = LearningCourseCopy.cleanQuizTranslations(request.withPreviousTranslations(snapshot.payload()));
            guard = requireCoursePayload(courseId, idempotencyKey, request);
            if (guard != null) return fail(guard);
        }
        LearningCourseUpsertRequest normalized = normalizeCourseRequest(request);
        LearningCourseView updated;
        try {
            updated = learningRepository.updateCourseDraft(current.id(), normalized, now());
        } catch (IllegalStateException ex) {
            if ("LEARNING_COURSE_REVISION_CONFLICT".equals(ex.getMessage())) {
                return ApiResult.fail(409, ex.getMessage());
            }
            throw ex;
        }
        try {
            learningRepository.saveCourseVersion(current.id(), current.version(), "DRAFT", normalized,
                    snapshot == null ? null : snapshot.revision(), now());
        } catch (UnsupportedOperationException ignored) {
            // Compatibility for in-memory adapters.
        }
        audit("I7_LEARNING_COURSE_DRAFT_UPDATED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "fromRevision", current.revision(), "toRevision", updated.revision()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<I18nMessagePairView> archiveLocalizedMessage(String messageKey, String idempotencyKey, I18nVersionActionRequest request) {
        ApiResult<Void> guard = requireVersionAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        I18nMessagePairView current = !StringUtils.hasText(messageKey)
                ? null
                : learningRepository.findPublishedMessagePairForUpdate(messageKey.trim()).orElse(null);
        if (current == null) {
            return ApiResult.fail(404, "I18N_MESSAGE_NOT_FOUND");
        }
        if (!request.expectedVersion().trim().equals(current.version())) {
            return ApiResult.fail(409, "I18N_MESSAGE_VERSION_CONFLICT");
        }
        I18nMessagePairView archived = learningRepository.archiveMessage(messageKey.trim(), now());
        audit("I6_I18N_MESSAGE_ARCHIVED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "version", archived.version(), "languages", "zh+en+vi"));
        eventOutboxService.publish("I18N_MESSAGE", messageKey.trim(), "admin.i18n_rolledback", Map.of(
                "message_key", messageKey.trim(),
                "from_version", current.version(),
                "to_status", "ARCHIVED",
                "locale_set", archived.localeSet(),
                "operator", operator(request.operator())));
        return ApiResult.ok(archived);
    }

    public ApiResult<List<I18nMessagePairView>> messageVersions(String messageKey) {
        if (!StringUtils.hasText(messageKey) || !MESSAGE_KEY_PATTERN.matcher(messageKey.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_MESSAGE_KEY_INVALID");
        }
        return ApiResult.ok(learningRepository.listMessageVersions(messageKey.trim()));
    }

    @Transactional
    public ApiResult<I18nMessagePairView> rollbackLocalizedMessage(
            String messageKey,
            String targetVersion,
            String idempotencyKey,
            I18nVersionActionRequest request) {
        ApiResult<Void> guard = requireVersionAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (!StringUtils.hasText(messageKey) || !MESSAGE_KEY_PATTERN.matcher(messageKey.trim()).matches()
                || !StringUtils.hasText(targetVersion) || !targetVersion.trim().matches("(?i)^v[1-9][0-9]{0,8}$")) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_ROLLBACK_TARGET_INVALID");
        }
        I18nMessagePairView current = learningRepository.findMessagePairForUpdate(messageKey.trim()).orElse(null);
        if (current == null) return ApiResult.fail(404, "I18N_MESSAGE_NOT_FOUND");
        if (!request.expectedVersion().trim().equals(current.version())) {
            return ApiResult.fail(409, "I18N_MESSAGE_VERSION_CONFLICT");
        }
        if ("draft".equals(current.status())) {
            return ApiResult.fail(409, "I18N_DRAFT_MUST_BE_PUBLISHED_OR_REMOVED_BEFORE_ROLLBACK");
        }
        I18nMessagePairView target = learningRepository
                .findMessageVersionForUpdate(messageKey.trim(), targetVersion.trim())
                .orElse(null);
        if (target == null || !"archived".equals(target.status())) {
            return ApiResult.fail(422, "I18N_ROLLBACK_TARGET_INVALID");
        }
        ApiResult<Void> quality = requirePublishableCopy(target.zh(), target.en(), target.vi());
        if (quality != null) return fail(quality);
        I18nMessagePairView restored;
        try {
            restored = learningRepository.restoreMessageVersion(
                    messageKey.trim(), target.version(), current.version(), now());
        } catch (IllegalStateException ex) {
            return ApiResult.fail(409, ex.getMessage());
        }
        audit("I6_I18N_MESSAGE_ROLLED_BACK", "I18N_MESSAGE", messageKey.trim(), request.operator(),
                idempotencyKey, request.reason(), Map.of(
                        "fromVersion", current.version(),
                        "targetVersion", target.version(),
                        "publishedVersion", restored.version(),
                        "languages", "zh+en+vi"));
        eventOutboxService.publish("I18N_MESSAGE", messageKey.trim(), "admin.i18n_rolledback", Map.of(
                "message_key", messageKey.trim(),
                "from_version", current.version(),
                "to_version", target.version(),
                "published_version", restored.version(),
                "locale_set", restored.localeSet(),
                "operator", operator(request.operator())));
        return ApiResult.ok(restored);
    }

    @Transactional
    public ApiResult<Void> deleteCourseDraft(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourseForUpdate(courseId);
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
        return directCourseActivation(CourseActivationKind.FIRST_PUBLISH, courseId, null, idempotencyKey, request);
    }

    public ApiResult<List<LearningCourseVersionView>> courseVersions(String courseId) {
        if (!StringUtils.hasText(courseId) || findCourse(courseId) == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        return ApiResult.ok(learningRepository.listCourseVersions(courseId.trim()));
    }

    /** Read-only hint; soft-deleted labels still reserve their unique key. */
    public ApiResult<LearningCourseNextVersionView> nextCourseVersion(String courseId) {
        if (!StringUtils.hasText(courseId)) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        LearningCourseView course = findCourse(courseId);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        int max = 0;
        for (String label : learningRepository.listAllCourseVersionLabels(courseId.trim())) {
            if (!StringUtils.hasText(label) || !COURSE_VERSION_PATTERN.matcher(label).matches()) continue;
            max = Math.max(max, Integer.parseInt(label.substring(1)));
        }
        if (StringUtils.hasText(course.version()) && COURSE_VERSION_PATTERN.matcher(course.version()).matches()) {
            max = Math.max(max, Integer.parseInt(course.version().substring(1)));
        }
        if (max >= 999_999_999) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_EXHAUSTED");
        return ApiResult.ok(new LearningCourseNextVersionView("v" + (max + 1)));
    }

    @Transactional
    public ApiResult<LearningCourseVersionView> createCourseVersion(String courseId, String idempotencyKey,
            LearningCourseUpsertRequest request) {
        request = prepareCourseCopy(request);
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) return fail(guard);
        if (!StringUtils.hasText(request.version()) || !COURSE_VERSION_PATTERN.matcher(request.version().trim()).matches()) {
            return ApiResult.fail(422, "LEARNING_COURSE_VERSION_INVALID");
        }
        LearningCourseView current = findCourseForUpdate(courseId);
        if (current == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        List<LearningCourseVersionView> versions = learningRepository.listCourseVersions(courseId.trim());
        if (versions.stream().anyMatch(version -> "DRAFT".equals(version.status()))) {
            return ApiResult.fail(409, "LEARNING_COURSE_DRAFT_VERSION_ALREADY_EXISTS");
        }
        if (!"draft".equals(normalizeStatus(request.publishState()))) {
            return ApiResult.fail(422, "LEARNING_COURSE_VERSION_MUST_START_AS_DRAFT");
        }
        // A new version may carry the stored retired text forward, but must not introduce or change it.
        if (retiredOptionChanged(request.completionCondition(), current.completionCondition())
                || retiredOptionChanged(request.rewardEvent(), current.rewardEvent())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_OPTION_RETIRED");
        }
        String requestedVersionLabel = request.version().trim();
        if (versions.stream().anyMatch(version -> version.version().equalsIgnoreCase(requestedVersionLabel))) {
            return ApiResult.fail(409, "LEARNING_COURSE_VERSION_ALREADY_EXISTS");
        }
        int requestedVersion = Integer.parseInt(requestedVersionLabel.substring(1));
        int maxStoredVersion = versions.stream().map(LearningCourseVersionView::version)
                .filter(value -> COURSE_VERSION_PATTERN.matcher(value).matches())
                .mapToInt(value -> Integer.parseInt(value.substring(1))).max().orElse(0);
        int currentVersion = COURSE_VERSION_PATTERN.matcher(current.version()).matches()
                ? Integer.parseInt(current.version().substring(1)) : 0;
        int maxVersion = Math.max(maxStoredVersion, currentVersion);
        if (requestedVersion <= maxVersion) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_NOT_INCREMENTED");
        LearningCourseVersionView saved;
        try {
            saved = learningRepository.saveCourseVersion(courseId.trim(), requestedVersionLabel,
                    "DRAFT", normalizeCourseRequest(request), null, now());
        } catch (DuplicateKeyException ex) {
            // The visible-version checks above cannot see a soft-deleted row, but the
            // (course_id, version_label) unique key still reserves that version. The INSERT
            // did not create a row; rethrow as a business conflict so the service transaction
            // rolls back and the idempotency wrapper records FAILED instead of an unknown/500.
            throw new BizException(409, "LEARNING_COURSE_VERSION_ALREADY_EXISTS");
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
        request = prepareCourseCopy(request);
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) return fail(guard);
        LearningCourseVersionView current = learningRepository.findCourseVersion(courseId, version).orElse(null);
        if (current == null) return ApiResult.fail(404, "LEARNING_COURSE_VERSION_NOT_FOUND");
        if (!"DRAFT".equals(current.status())) return ApiResult.fail(409, "LEARNING_COURSE_VERSION_NOT_DRAFT");
        // This method saves the version snapshot, so the baseline is that snapshot's own payload —
        // not the course main record, which is an independent persistent object that may differ.
        if (retiredOptionChanged(request.completionCondition(), current.payload().completionCondition())
                || retiredOptionChanged(request.rewardEvent(), current.payload().rewardEvent())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_OPTION_RETIRED");
        }
        request = LearningCourseCopy.cleanQuizTranslations(request.withPreviousTranslations(current.payload()));
        guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) return fail(guard);
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
        return directCourseActivation(CourseActivationKind.PUBLISH, courseId, version, idempotencyKey, request);
    }

    @Transactional
    public ApiResult<LearningCourseView> rollbackCourseVersion(String courseId, String version,
            String idempotencyKey, I18nActionRequest request) {
        return directCourseActivation(CourseActivationKind.ROLLBACK, courseId, version, idempotencyKey, request);
    }

    private ApiResult<LearningCourseView> directCourseActivation(CourseActivationKind kind, String courseId, String version,
            String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) return fail(guard);
        CourseActivation activation = lockCourseActivation(kind, courseId, version);
        if (activation == null) {
            return ApiResult.fail(404, kind == CourseActivationKind.FIRST_PUBLISH
                    ? "LEARNING_COURSE_NOT_FOUND" : "LEARNING_COURSE_VERSION_NOT_FOUND");
        }
        try {
            requireCourseActivation(activation);
        } catch (BizException ex) {
            return ApiResult.fail(ex.getCode(), ex.getMessage());
        }
        if (activation.amplifies()) {
            return ApiResult.fail(409, "LEARNING_COURSE_ACTIVATION_APPROVAL_REQUIRED");
        }
        TreasuryCoverageSnapshot coverage = kind == CourseActivationKind.ROLLBACK ? coverageFacade.snapshot() : null;
        return activateCourse(activation, idempotencyKey, request, coverage, null);
    }

    enum CourseActivationKind { FIRST_PUBLISH, PUBLISH, ROLLBACK }

    record CourseActivation(CourseActivationKind kind, LearningCourseView current, LearningCourseVersionView target) {
        String targetVersion() { return target == null ? current.version() : target.version(); }

        BigDecimal targetReward() { return target == null ? current.rewardNex() : target.payload().rewardNex(); }

        boolean amplifies() {
            // T11-7：只要目标历史版本有奖励（> 0），回滚一律走审批，等额、降额也算。
            BigDecimal reward = targetReward();
            return reward != null && reward.signum() > 0;
        }
    }

    CourseActivation lockCourseActivation(CourseActivationKind kind, String courseId, String version) {
        LearningCourseView current = findCourseForUpdate(courseId);
        if (current == null) return null;
        var target = learningRepository.findCourseVersionForActivation(current.id(),
                kind == CourseActivationKind.FIRST_PUBLISH ? null : version).orElse(null);
        if (target == null && kind == CourseActivationKind.FIRST_PUBLISH && "draft".equals(current.status())) {
            current = learningRepository.findLegacyCourseDraftForActivation(current.id()).orElseThrow();
        }
        return new CourseActivation(kind, current, target);
    }

    void requireCourseActivation(CourseActivation activation) {
        var current = activation.current();
        var target = activation.target();
        if (activation.kind() == CourseActivationKind.FIRST_PUBLISH) {
            if (!"draft".equals(current.status())) {
                throw new BizException(409, OpsErrorCode.INVALID_STATE_TRANSITION.name());
            }
        } else if (target == null) {
            throw new BizException(404, "LEARNING_COURSE_VERSION_NOT_FOUND");
        }
        if (activation.kind() == CourseActivationKind.ROLLBACK) {
            if (!"SUPERSEDED".equals(target.status()) || target.version().equals(current.version())) {
                throw new BizException(409, "LEARNING_COURSE_ROLLBACK_TARGET_INVALID");
            }
        } else {
            if (target != null && !"DRAFT".equals(target.status())) {
                throw new BizException(409, "LEARNING_COURSE_VERSION_NOT_DRAFT");
            }
            if (!(target == null ? hasCompleteQuiz(current) : LearningCourseCopy.hasCompleteQuiz(target.payload()))) {
                throw new BizException(422, "LEARNING_COURSE_QUIZ_INCOMPLETE");
            }
        }
        String primaryTitle = target == null ? current.titleZh() : target.payload().titleZh();
        if (primaryTitle != null && primaryTitle.trim().codePointCount(0, primaryTitle.trim().length()) > 128) {
            throw new BizException(422, "LEARNING_COURSE_COPY_TOO_LONG");
        }
        ApiResult<Void> quality = target == null
                ? LearningCourseCopy.requirePublishable(current.titleZh(), current.titleEn(), current.titleVi(),
                        current.bodyZh(), current.bodyEn(), current.bodyVi())
                : LearningCourseCopy.requirePublishable(target.payload());
        if (quality != null) throw new BizException(quality.getCode(), quality.getMessage());
    }

    ApiResult<LearningCourseView> activateCourse(CourseActivation activation, String idempotencyKey,
            I18nActionRequest request, TreasuryCoverageSnapshot coverage, String operationId) {
        var current = activation.current();
        var target = activation.target();
        LocalDateTime now = now();
        LearningCourseView updated = target != null
                ? learningRepository.activateCourseVersion(current.id(), target.version(), target.status(),
                        current.version(), current.revision(), now)
                : operationId != null ? learningRepository.publishLegacyCourseDraft(current, now)
                        : learningRepository.updateCourseStatus(current.id(), "published", now);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (operationId != null) detail.put("a2OperationId", operationId);
        switch (activation.kind()) {
            case FIRST_PUBLISH -> {
                detail.put("from", current.status());
                detail.put("to", updated.status());
                audit("I7_LEARNING_COURSE_PUBLISHED", "LEARNING_COURSE", current.id(),
                        request.operator(), idempotencyKey, request.reason(), detail);
            }
            case PUBLISH -> {
                detail.put("version", target.version());
                audit("I7_LEARNING_COURSE_VERSION_PUBLISHED", "LEARNING_COURSE_VERSION", current.id() + ":" + target.version(),
                        request.operator(), idempotencyKey, request.reason(), detail);
            }
            case ROLLBACK -> {
                detail.put("from", current.version());
                detail.put("to", target.version());
                detail.put("rewardNex", activation.targetReward());
                detail.put("coverageRatio", coverage.coverageRatio());
                detail.put("redlinePct", coverage.redlinePct());
                audit("I7_LEARNING_COURSE_VERSION_ROLLED_BACK", "LEARNING_COURSE_VERSION", current.id() + ":" + target.version(),
                        request.operator(), idempotencyKey, request.reason(), detail);
            }
        }
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<LearningCourseView> archiveCourse(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourseForUpdate(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"published".equals(current.status()) || current.featured()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LearningCourseView updated = learningRepository.updateCourseStatus(current.id(), "archived", now());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", current.status());
        detail.put("rewardNex", current.rewardNex());
        audit("I7_LEARNING_COURSE_ARCHIVED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), detail);
        return ApiResult.ok(updated);
    }

    public static boolean isCourseRewardAdjustment(AuditReplayCommand command) {
        return command != null && "I".equals(command.domain()) && COURSE_REWARD_ADJUST.equals(command.op());
    }

    public record CourseRewardReview(String version, long revision, String rewardNex, long makerAdminId) {
        public static final String PARAM = "courseRewardReview";

        public static CourseRewardReview fromCommand(AuditReplayCommand command) {
            return isCourseRewardAdjustment(command) && command.params() != null
                    && command.params().get(PARAM) instanceof CourseRewardReview review && review.makerAdminId() > 0
                    ? review : null;
        }

        static CourseRewardReview fromCourse(LearningCourseView course, long makerAdminId) {
            return new CourseRewardReview(course.version(), course.revision(),
                    course.rewardNex() == null ? null : course.rewardNex().toPlainString(), makerAdminId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CourseRewardReview reviewCourseReward(String courseId, BigDecimal rewardNex, long makerAdminId) {
        if (invalidReward(rewardNex)) throw new BizException(422, "LEARNING_REWARD_OUT_OF_RANGE");
        LearningCourseView current = findCourseForUpdate(courseId);
        if (current == null) throw new BizException(404, "LEARNING_COURSE_NOT_FOUND");
        if (!"published".equals(current.status())) throw new BizException(409, OpsErrorCode.INVALID_STATE_TRANSITION.name());
        return CourseRewardReview.fromCourse(current, makerAdminId);
    }

    @Transactional
    public ApiResult<LearningCourseView> updateCourseReward(String courseId, String idempotencyKey, LearningRewardUpdateRequest request) {
        ApiResult<Void> guard = requireRewardUpdate(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        // The direct PATCH remains a separate command. Only the A2-approved
        // updateApprovedCourseReward path, with its bound review and operationId,
        // may write a course reward.
        return ApiResult.fail(403, "LEARNING_COURSE_REWARD_APPROVAL_REQUIRED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApiResult<LearningCourseView> updateApprovedCourseReward(String courseId, String idempotencyKey,
            LearningRewardUpdateRequest request, CourseRewardReview review) {
        if (A2ReplayContext.operationId() == null) return ApiResult.fail(403, "LEARNING_COURSE_REWARD_APPROVAL_REQUIRED");
        if (review == null) return ApiResult.fail(409, "LEARNING_COURSE_REWARD_REPROPOSE_REQUIRED");
        return updateCourseReward(courseId, idempotencyKey, request, review);
    }

    private ApiResult<LearningCourseView> updateCourseReward(String courseId, String idempotencyKey,
            LearningRewardUpdateRequest request, CourseRewardReview review) {
        ApiResult<Void> guard = requireRewardUpdate(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("I", "learning_course", courseId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        LearningCourseView current = findCourseForUpdate(courseId);
        if (review != null && (current == null || !"published".equals(current.status())
                || !review.equals(CourseRewardReview.fromCourse(current, review.makerAdminId())))) {
            return ApiResult.fail(409, "LEARNING_COURSE_REWARD_SNAPSHOT_CHANGED");
        }
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if (!"published".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (request.rewardNex().compareTo(current.rewardNex() == null ? BigDecimal.ZERO : current.rewardNex()) > 0
                && coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        LearningCourseView updated = learningRepository.updateCourseReward(current.id(), request.rewardNex().setScale(6, RoundingMode.HALF_UP), now());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", current.rewardNex());
        detail.put("to", updated.rewardNex());
        detail.put("coverageRatio", coverage.coverageRatio());
        detail.put("redlinePct", coverage.redlinePct());
        if (review != null) {
            detail.put("version", current.version());
            detail.put("reviewedRevision", current.revision());
            detail.put("a2OperationId", A2ReplayContext.operationId());
        }
        audit("I7_LEARNING_REWARD_CHANGED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), detail);
        return ApiResult.ok(updated);
    }

    public static BigDecimal courseRewardAmount(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
        return currentOverview(null);
    }

    private I18nLearningOverview currentOverview(LocalDateTime scanAtOverride) {
        List<I18nNamespaceView> namespaces = learningRepository.listNamespaces();
        List<I18nMessagePairView> messages = learningRepository.listMessagePairs();
        List<I18nIntegrityIssueView> issues = learningRepository.listIntegrityIssues();
        List<I18nHardcodedFindingView> findings = learningRepository.listHardcodedFindings();
        List<LearningCourseView> courses = learningRepository.listCourses();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        int totalKeys = namespaces.stream().mapToInt(I18nNamespaceView::keys).sum();
        Map<String, I18nMessagePairView> publishedMessages = new LinkedHashMap<>();
        for (I18nMessagePairView message : messages) {
            learningRepository.findPublishedMessagePair(message.messageKey())
                    .ifPresent(published -> publishedMessages.put(message.messageKey(), published));
        }
        int managedKeys = (int) publishedMessages.values().stream().filter(I18nCopyQuality::isComplete).count();
        int openIssues = issues.stream()
                .filter(row -> !"fixed".equals(row.status()))
                .mapToInt(I18nIntegrityIssueView::cnt)
                .sum();
        int online = (int) courses.stream().filter(row -> "published".equals(row.status())).count();
        return new I18nLearningOverview(
                new I18nLearningStats(
                        managedKeys,
                        totalKeys,
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
                null,
                CATEGORIES,
                FORMATS,
                LEVELS,
                STATUSES,
                SOURCES,
                scanAtOverride != null ? scanAtOverride : latestScanAt(),
                publishedMessages);
    }

    private LocalDateTime latestScanAt() {
        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setAction("I6_I18N_INTEGRITY_RESCANNED");
        query.setResourceType("I18N_SCAN");
        query.setLimit(1);
        List<AuditLogRecord> rows = auditLogService.list(query);
        return rows == null || rows.isEmpty() ? null : rows.get(0).getCreatedAt();
    }

    private List<LearningMetricView> metrics(int onlineCourses) {
        return List.of();
    }

    private String weeklyNexPayout() {
        BigDecimal value = learningRepository.weeklyGrantedLearningReward();
        return value.stripTrailingZeros().toPlainString() + " UVT";
    }

    private String featuredCourseId(List<LearningCourseView> courses) {
        return courses.stream()
                .filter(LearningCourseView::featured)
                .map(LearningCourseView::id)
                .findFirst()
                .orElse("");
    }

    private LearningCourseUpsertRequest prepareCourseCopy(LearningCourseUpsertRequest request) {
        return request == null ? null
                : LearningCourseCopy.cleanQuizTranslations(LearningCourseCopy.complete(request, operatingLanguages.get()));
    }

    private ApiResult<Void> requireCoursePayload(String courseId, String idempotencyKey, LearningCourseUpsertRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(courseId) || !COURSE_ID_PATTERN.matcher(courseId.trim()).matches()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_ID_INVALID");
        }
        ApiResult<Void> copy = LearningCourseCopy.validateBase(request);
        if (copy != null) return copy;
        if (!LearningCourseCopy.validEnums(request)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_ENUM_UNSUPPORTED");
        }
        if (invalidReward(request.rewardNex())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_REWARD_OUT_OF_RANGE");
        }
        return LearningCourseCopy.validateContent(request);
    }

    private boolean hasCompleteQuiz(LearningCourseView course) {
        if (course.quizQuestions() == null || course.quizQuestions().isEmpty()
                || course.passScore() == null || course.passScore() < 1 || course.passScore() > 100
                || course.retryLimit() == null || course.retryLimit() < 0) {
            return false;
        }
        for (LearningQuizQuestionView question : course.quizQuestions()) {
            if (!StringUtils.hasText(question.questionId()) || !hasCopyText(question.questionZh())
                    || !hasCopyText(question.questionEn()) || !hasCopyText(question.questionVi())
                    || question.optionsZh() == null || question.optionsEn() == null || question.optionsVi() == null
                    || question.optionsZh().size() < 2
                    || question.optionsZh().size() != question.optionsEn().size()
                    || question.optionsZh().size() != question.optionsVi().size()
                    || question.optionsZh().stream().anyMatch(value -> !hasCopyText(value))
                    || question.optionsEn().stream().anyMatch(value -> !hasCopyText(value))
                    || question.optionsVi().stream().anyMatch(value -> !hasCopyText(value))
                    || question.correctOptionIndex() < 0 || question.correctOptionIndex() >= question.optionsZh().size()) {
                return false;
            }
        }
        return true;
    }

    private record MessageCopy(String zh, String en, String vi) {
        boolean complete() {
            return I18nCopyQuality.hasCopyText(en) && I18nCopyQuality.hasCopyText(zh) && I18nCopyQuality.hasCopyText(vi);
        }
    }

    private MessageCopy completeMessageCopy(String zh, String en, String vi, List<String> required) {
        return new MessageCopy(
                !hasCopyText(zh) && !required.contains("zh") ? en : zh,
                en, !hasCopyText(vi) && !required.contains("vi") ? en : vi);
    }

    private ApiResult<Void> requireLocalizedCopy(String messageKey, String idempotencyKey,
            I18nLocalizedCopyRequest request, MessageCopy copy) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(messageKey) || !MESSAGE_KEY_PATTERN.matcher(messageKey.trim()).matches()
                || copy == null || !copy.complete()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_COPY_REQUIRED");
        }
        return validateMessageCopy(messageKey, copy, request.translations());
    }

    private ApiResult<Void> validateMessageCopy(String messageKey, MessageCopy copy, Map<String, String> translations) {
        if (translations != null) {
            for (var entry : translations.entrySet()) {
                if (!MessageTranslations.LOCALES.containsKey(entry.getKey())) return ApiResult.fail(422, "I18N_LOCALE_UNSUPPORTED");
                String value = entry.getValue();
                if (!StringUtils.hasText(value)) continue;
                if (value.trim().length() > 1024) return ApiResult.fail(422, "I18N_COPY_TOO_LONG");
                if (containsUnsafeText(value)) return ApiResult.fail(422, "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
                if (containsSunsetCapability(value)) return ApiResult.fail(422, "SUNSET_CAPABILITY_READONLY");
                if (!I18nCopyQuality.placeholdersMatch(copy.en(), value)) return ApiResult.fail(422, "I18N_PLACEHOLDERS_MISMATCH");
                String qualityError = I18nCopyQuality.publishError(copy.zh(), copy.en(), value);
                if (qualityError != null) return ApiResult.fail(422, qualityError);
            }
        }
        if (containsUnsafeText(messageKey, copy.zh(), copy.en(), copy.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (containsSunsetCapability(messageKey, copy.zh(), copy.en(), copy.vi())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (!I18nCopyQuality.placeholdersMatch(copy.en(), copy.zh(), copy.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private ApiResult<Void> requirePublishableCopy(String zh, String en, String vi) {
        String error = I18nCopyQuality.publishError(zh, en, vi);
        return error == null ? null : ApiResult.fail(422, error);
    }

    private ApiResult<Void> requireIntegrityFix(String issueCode, String idempotencyKey,
            I18nIntegrityFixRequest request, MessageCopy copy) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(issueCode) || request == null || !StringUtils.hasText(request.messageKey())
                || !MESSAGE_KEY_PATTERN.matcher(request.messageKey().trim()).matches()
                || !copy.complete()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_INTEGRITY_FIX_REQUIRED");
        }
        if (containsUnsafeText(request.messageKey(), copy.zh(), copy.en(), copy.vi())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (!I18nCopyQuality.placeholdersMatch(copy.en(), copy.zh(), copy.vi())) {
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

    private ApiResult<Void> requireVersionAction(String idempotencyKey, I18nVersionActionRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) return action;
        if (!StringUtils.hasText(request.expectedVersion())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_EXPECTED_VERSION_REQUIRED");
        }
        return null;
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
                LearningCourseCopy.normalizeQuestions(request.quizQuestions()),
                request.passScore(), request.retryLimit(), trimToNull(request.completionCondition()),
                trimToNull(request.rewardEvent()), request.expectedRevision(),
                request.titleVi().trim(), request.bodyVi().trim(), trimToNull(request.version()),
                request.titleTranslations() == null ? null : MessageTranslations.clean(request.titleTranslations()),
                request.bodyTranslations() == null ? null : MessageTranslations.clean(request.bodyTranslations()));
    }

    /** Same value (including both blank) is an echo of the stored text, not new configuration. */
    private boolean retiredOptionChanged(String requested, String stored) {
        String next = requested == null ? null : requested.trim();
        String previous = stored == null ? null : stored.trim();
        return !java.util.Objects.equals(next == null || next.isEmpty() ? null : next,
                previous == null || previous.isEmpty() ? null : previous);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean invalidReward(BigDecimal rewardNex) {
        return rewardNex == null || rewardNex.compareTo(REWARD_RANGE.min()) < 0 || rewardNex.compareTo(REWARD_RANGE.max()) > 0;
    }

    private String canonical(List<String> values, String value) {
        return values.stream()
                .filter(row -> row.equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElse(values.get(0));
    }

    private LearningCourseView findCourse(String courseId) {
        return StringUtils.hasText(courseId) ? learningRepository.findCourse(courseId.trim()).orElse(null) : null;
    }

    private LearningCourseView findCourseForUpdate(String courseId) {
        return StringUtils.hasText(courseId) ? learningRepository.findCourseForUpdate(courseId.trim())
                .filter(course -> courseId.trim().equals(course.id())).orElse(null) : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String operator(String operator) {
        String resolved = AdminActorResolver.resolve(operator);
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private <T> ApiResult<T> fail(ApiResult<Void> guard) {
        return ApiResult.fail(guard.getCode(), guard.getMessage());
    }

    private void audit(String action, String resourceType, String resourceId, String operator, String idempotencyKey, String reason, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("reason", reason.trim());
        detail.putAll(extra);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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

}
