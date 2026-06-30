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
import ffdd.opsconsole.content.domain.LearningMetricView;
import ffdd.opsconsole.content.domain.TutorialRewardRange;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
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
            "nx_i18n_integrity_issue",
            "nx_i18n_hardcoded_finding",
            "nx_help_article",
            "B1 treasury coverage facade");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z0-9_.-]+}");
    private static final Pattern JSON_LIKE_PATTERN = Pattern.compile("^\\s*[\\[{]");
    private static final Pattern MANUAL_URL_PATTERN = Pattern.compile("https?://|href\\s*=|href=#", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUNSET_PATTERN = Pattern.compile("premium|nex\\s*v?2|nexv2|points|积分", Pattern.CASE_INSENSITIVE);
    private static final Pattern COURSE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{2,80}$");

    private final I18nLearningRepository learningRepository;
    private final AuditLogService auditLogService;
    private final TreasuryCoverageFacade coverageFacade;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<I18nLearningOverview> overview() {
        if (readTimeSeedPolicy.enabled()) {
            learningRepository.ensureSeedData(now());
        }
        return ApiResult.ok(currentOverview());
    }

    public ApiResult<I18nLearningOverview> rescan(String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
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
        I18nMessagePairView saved = learningRepository.saveMessagePair(messageKey.trim(), request.zh().trim(), request.en().trim(), "draft", now());
        audit("I6_I18N_DRAFT_SAVED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "languages", "en+zh",
                "placeholders", String.join(",", saved.placeholders())));
        return ApiResult.ok(saved);
    }

    public ApiResult<I18nMessagePairView> publishLocalizedMessage(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        ApiResult<Void> guard = requireLocalizedCopy(messageKey, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        I18nMessagePairView published = learningRepository.saveMessagePair(messageKey.trim(), request.zh().trim(), request.en().trim(), "published", now());
        audit("I6_I18N_MESSAGE_PUBLISHED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "languages", "en+zh",
                "placeholders", String.join(",", published.placeholders())));
        return ApiResult.ok(published);
    }

    public ApiResult<I18nMessagePairView> startMarketingExperiment(String messageKey, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (!StringUtils.hasText(messageKey) || learningRepository.findMessagePair(messageKey.trim()).isEmpty()) {
            return ApiResult.fail(404, "I18N_MESSAGE_NOT_FOUND");
        }
        I18nMessagePairView updated = learningRepository.markMarketingExperiment(messageKey.trim(), now());
        audit("I6_I18N_MARKETING_EXPERIMENT_STARTED", "I18N_MESSAGE", messageKey.trim(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "framework", "I1 Copy A/B"));
        return ApiResult.ok(updated);
    }

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
        I18nIntegrityIssueView fixed = learningRepository.markIssueFixed(issueCode.trim(), now());
        audit("I6_I18N_INTEGRITY_FIXED", "I18N_INTEGRITY", fixed.code(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "kind", fixed.kind(),
                "count", fixed.cnt()));
        return ApiResult.ok(fixed);
    }

    public ApiResult<LearningCourseView> createCourse(String courseId, String idempotencyKey, LearningCourseUpsertRequest request) {
        ApiResult<Void> guard = requireCoursePayload(courseId, idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        if (learningRepository.findCourse(courseId.trim()).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "LEARNING_COURSE_ALREADY_EXISTS");
        }
        LearningCourseView created = learningRepository.createCourse(courseId.trim(), normalizeCourseRequest(request), now());
        audit("I7_LEARNING_COURSE_CREATED", "LEARNING_COURSE", created.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "category", created.category(),
                "status", created.status()));
        return ApiResult.ok(created);
    }

    public ApiResult<LearningCourseView> publishCourse(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if ("published".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LearningCourseView updated = learningRepository.updateCourseStatus(current.id(), "published", now());
        audit("I7_LEARNING_COURSE_PUBLISHED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.status(),
                "to", updated.status()));
        return ApiResult.ok(updated);
    }

    public ApiResult<LearningCourseView> archiveCourse(String courseId, String idempotencyKey, I18nActionRequest request) {
        ApiResult<Void> guard = requireAction(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        }
        if ("archived".equals(current.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        LearningCourseView updated = learningRepository.updateCourseStatus(current.id(), "archived", now());
        audit("I7_LEARNING_COURSE_ARCHIVED", "LEARNING_COURSE", current.id(), request.operator(), idempotencyKey, request.reason(), Map.of(
                "from", current.status(),
                "rewardNex", current.rewardNex()));
        return ApiResult.ok(updated);
    }

    public ApiResult<LearningCourseView> updateCourseReward(String courseId, String idempotencyKey, LearningRewardUpdateRequest request) {
        ApiResult<Void> guard = requireRewardUpdate(idempotencyKey, request);
        if (guard != null) {
            return fail(guard);
        }
        LearningCourseView current = findCourse(courseId);
        if (current == null) {
            return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
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
                        weeklyNexPayout(online),
                        coverage.coverageRatio(),
                        coverage.redlinePct()),
                namespaces,
                issues,
                findings,
                learningRepository.findMessagePair(FOCUS_MESSAGE_KEY).orElse(null),
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
        if (!readTimeSeedPolicy.enabled()) {
            return List.of();
        }
        return List.of(
                new LearningMetricView("本周开课", decimalK(onlineCourses * 3150L)),
                new LearningMetricView("完课率", "42%"),
                new LearningMetricView("周完课触达回访(D7)", "+3.1pp"));
    }

    private String weeklyNexPayout(int onlineCourses) {
        return readTimeSeedPolicy.enabled() ? decimalK(onlineCourses * 5760L) : "0K";
    }

    private String decimalK(long value) {
        return BigDecimal.valueOf(value)
                .divide(new BigDecimal("1000"), 1, RoundingMode.HALF_UP)
                .toPlainString() + "K";
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
                || !StringUtils.hasText(request.bodyZh()) || !StringUtils.hasText(request.bodyEn())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_COPY_REQUIRED");
        }
        if (!containsIgnoreCase(CATEGORIES, request.category()) || !containsIgnoreCase(FORMATS, request.format())
                || !containsIgnoreCase(LEVELS, request.difficulty()) || !STATUSES.contains(normalizeStatus(request.publishState()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_ENUM_UNSUPPORTED");
        }
        if (invalidReward(request.rewardNex())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_REWARD_OUT_OF_RANGE");
        }
        if (containsUnsafeText(request.titleZh(), request.titleEn(), request.bodyZh(), request.bodyEn())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (containsSunsetCapability(request.titleZh(), request.titleEn(), request.bodyZh(), request.bodyEn())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (!placeholders(request.bodyZh()).equals(placeholders(request.bodyEn()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LEARNING_COURSE_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private ApiResult<Void> requireLocalizedCopy(String messageKey, String idempotencyKey, I18nLocalizedCopyRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(messageKey) || request == null || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_COPY_REQUIRED");
        }
        if (containsUnsafeText(messageKey, request.zh(), request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (containsSunsetCapability(messageKey, request.zh(), request.en())) {
            return ApiResult.fail(OpsErrorCode.RETIRED_FEATURE.httpStatus(), "SUNSET_CAPABILITY_READONLY");
        }
        if (!placeholders(request.zh()).equals(placeholders(request.en()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_PLACEHOLDERS_MISMATCH");
        }
        return null;
    }

    private ApiResult<Void> requireIntegrityFix(String issueCode, String idempotencyKey, I18nIntegrityFixRequest request) {
        ApiResult<Void> action = requireIdempotencyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (action != null) {
            return action;
        }
        if (!StringUtils.hasText(issueCode) || request == null || !StringUtils.hasText(request.zh()) || !StringUtils.hasText(request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_INTEGRITY_FIX_REQUIRED");
        }
        if (containsUnsafeText(request.zh(), request.en())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "I18N_RAW_JSON_OR_URL_NOT_ALLOWED");
        }
        if (!placeholders(request.zh()).equals(placeholders(request.en()))) {
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
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
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
                request.reason().trim());
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
}
