package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AppLearningCourseView;
import ffdd.opsconsole.content.domain.AppLearningOverview;
import ffdd.opsconsole.content.domain.AppLearningQuizResult;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.domain.LearningProgressRow;
import ffdd.opsconsole.content.domain.LearningQuizReceipt;
import ffdd.opsconsole.content.domain.LearningSandboxIdempotencyRow;
import ffdd.opsconsole.content.domain.LearningSandboxCourseRow;
import ffdd.opsconsole.content.dto.AppLearningQuizSubmitRequest;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppLearningService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final I18nLearningRepository learningRepository;
    private final AppLearningMapper learningMapper;
    private final TreasuryLedgerPostingFacade treasuryLedgerPostingFacade;
    private final EventOutboxService eventOutboxService;
    private final EarningsReleaseService earningsReleaseService;
    private final AdminIdempotencyService idempotencyService;
    private final LearningAcceptanceSandboxGate sandboxGate;
    private final LearningSandboxQuizIdempotencyService sandboxIdempotencyService;
    @Value("${nexion.learning.acceptance-run-id:}")
    private final String acceptanceRunId;

    public ApiResult<AppLearningOverview> overview(Long userId, String language) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        requireDevelopmentAccount(userId);
        String sourceEnvironment = rewardEnvironmentForRead(userId);
        Map<String, LearningProgressRow> progress = progressByCourse(userId, sourceEnvironment);
        List<AppLearningCourseView> courses = publishedCourses(sourceEnvironment).stream()
                .map(course -> toAppCourse(userId, course, language, progress.get(key(course.id(), course.version())), sourceEnvironment))
                .toList();
        int completed = (int) courses.stream().filter(AppLearningCourseView::completed).count();
        BigDecimal earned = sandbox(sourceEnvironment) ? learningMapper.sumSandboxGrantedReward(sandboxRunId(), userId) : learningMapper.sumGrantedReward(userId);
        return ApiResult.ok(new AppLearningOverview(courses, completed, courses.size(), nz(earned), true,
                sourceEnvironment, sandbox(sourceEnvironment) ? sandboxRunId() : ""));
    }

    public ApiResult<AppLearningCourseView> course(Long userId, String courseId, String language) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        requireDevelopmentAccount(userId);
        String sourceEnvironment = rewardEnvironmentForRead(userId);
        LearningCourseView course = publishedCourse(courseId, sourceEnvironment);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        return ApiResult.ok(toAppCourse(userId, course, language,
                findProgress(sourceEnvironment, userId, course), sourceEnvironment));
    }

    @Transactional
    public ApiResult<AppLearningCourseView> start(Long userId, String courseId, String language) {
        return start(userId, courseId, language, null);
    }

    @Transactional
    public ApiResult<AppLearningCourseView> start(Long userId, String courseId, String language, String expectedVersion) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        requireDevelopmentAccount(userId);
        String sourceEnvironment = lockedRewardEnvironment(userId);
        LearningCourseView course = publishedCourse(courseId, sourceEnvironment);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        ApiResult<AppLearningCourseView> versionGuard = requireExpectedVersion(course, expectedVersion);
        if (versionGuard != null) return versionGuard;
        if (startCourse(sourceEnvironment, userId, course) == 1) {
            insertLearningEvent(sourceEnvironment, userId, course, "course_started", "{}");
        }
        return ApiResult.ok(toAppCourse(userId, course, language,
                findProgress(sourceEnvironment, userId, course), sourceEnvironment));
    }

    @Transactional
    public ApiResult<AppLearningQuizResult> complete(Long userId, String courseId) {
        return complete(userId, courseId, null);
    }

    @Transactional
    public ApiResult<AppLearningQuizResult> complete(Long userId, String courseId, String expectedVersion) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        requireDevelopmentAccount(userId);
        String sourceEnvironment = lockedRewardEnvironment(userId);
        LearningCourseView course = publishedCourse(courseId, sourceEnvironment);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        ApiResult<AppLearningQuizResult> versionGuard = requireExpectedVersion(course, expectedVersion);
        if (versionGuard != null) return versionGuard;
        if (course.quizQuestions() != null && !course.quizQuestions().isEmpty()) {
            return ApiResult.fail(409, "LEARNING_QUIZ_REQUIRED");
        }
        startCourse(sourceEnvironment, userId, course);
        LearningProgressRow progress = lockedOrReadProgress(sourceEnvironment, userId, course);
        if (progress != null && progress.progressPct() >= 100) {
            return ApiResult.ok(resultAfterCompletion(
                    userId, course, 100, progress.attempts(), sourceEnvironment, false));
        }
        recordQuiz(sourceEnvironment, userId, course, 100, 100);
        int inserted = insertLearningEvent(sourceEnvironment, userId, course, "course_completed", "{\"source\":\"content\"}");
        AppLearningQuizResult result = resultAfterCompletion(userId, course, 100, 1, sourceEnvironment, true);
        publishCompletionIfNew(inserted, userId, course, sourceEnvironment);
        return ApiResult.ok(result);
    }

    public ApiResult<AppLearningQuizResult> submitQuiz(Long userId, String courseId, AppLearningQuizSubmitRequest request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        requireDevelopmentAccount(userId);
        String sourceEnvironment = rewardEnvironmentForCommand(userId);
        LearningCourseView course = publishedCourse(courseId, sourceEnvironment);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        List<LearningQuizQuestionView> questions = course.quizQuestions() == null ? List.of() : course.quizQuestions();
        if (questions.isEmpty()) return ApiResult.fail(409, "LEARNING_QUIZ_NOT_CONFIGURED");
        if (request == null || request.answers() == null || request.answers().size() != questions.size()
                || request.answers().stream().anyMatch(answer -> answer == null || answer < 0)) {
            return ApiResult.fail(422, "LEARNING_QUIZ_ANSWERS_INVALID");
        }
        if (StringUtils.hasText(request.expectedVersion()) && !course.version().equals(request.expectedVersion().trim())) {
            return ApiResult.fail(409, "LEARNING_COURSE_VERSION_CONFLICT");
        }
        if (!StringUtils.hasText(request.idempotencyKey())) {
            return ApiResult.fail(422, "LEARNING_QUIZ_IDEMPOTENCY_KEY_REQUIRED");
        }
        String idempotencyKey = request.idempotencyKey().trim();
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            return ApiResult.fail(422, "LEARNING_QUIZ_IDEMPOTENCY_KEY_INVALID");
        }
        String requestHash = sha256(course.id() + ":" + course.version() + ":" + request.expectedVersion() + ":" + request.answers());
        try {
            if (sandbox(sourceEnvironment)) {
                return ApiResult.ok(sandboxIdempotencyService.execute(
                        sandboxRunId(), userId, course.id(), course.version(), requestHash, idempotencyKey,
                        () -> submitQuizOnce(userId, course, questions, request.answers())));
            }
            AppLearningQuizResult result = idempotencyService.execute(
                    "APP_LEARNING_QUIZ:" + sha256(userId + "|" + course.id() + "|" + course.version()),
                    idempotencyKey,
                    requestHash,
                    AppLearningQuizResult.class,
                    () -> submitQuizOnce(userId, course, questions, request.answers()));
            return ApiResult.ok(result);
        } catch (BizException ex) {
            return ApiResult.fail(ex.getCode(), ex.getMessage());
        }
    }

    private AppLearningQuizResult submitQuizOnce(
            Long userId,
            LearningCourseView course,
            List<LearningQuizQuestionView> questions,
            List<Integer> answers) {
        String sourceEnvironment = lockedRewardEnvironment(userId);
        startCourse(sourceEnvironment, userId, course);
        LearningProgressRow progress = lockedOrReadProgress(sourceEnvironment, userId, course);
        int attempts = progress == null ? 0 : progress.attempts();
        if (progress != null && progress.progressPct() >= 100) {
            return resultAfterCompletion(userId, course, 100, attempts, sourceEnvironment, false);
        }
        int maxAttempts = course.retryLimit() == null ? 1 : course.retryLimit() + 1;
        if (attempts >= maxAttempts) throw new BizException(409, "LEARNING_QUIZ_RETRY_LIMIT_REACHED");
        int correct = 0;
        for (int index = 0; index < questions.size(); index += 1) {
            int answer = answers.get(index);
            if (answer >= questions.get(index).optionsZh().size()) {
                throw new BizException(422, "LEARNING_QUIZ_ANSWERS_INVALID");
            }
            if (answer == questions.get(index).correctOptionIndex()) correct += 1;
        }
        int score = BigDecimal.valueOf(correct * 100L)
                .divide(BigDecimal.valueOf(questions.size()), 0, RoundingMode.HALF_UP).intValue();
        boolean passed = score >= (course.passScore() == null ? 100 : course.passScore());
        recordQuiz(sourceEnvironment, userId, course, score, passed ? 100 : 50);
        if (passed) {
            insertLearningEvent(sourceEnvironment, userId, course, "quiz_passed", "{\"score\":" + score + "}");
            int inserted = insertLearningEvent(sourceEnvironment, userId, course, "course_completed", "{\"source\":\"quiz\"}");
            AppLearningQuizResult result = resultAfterCompletion(userId, course, score, attempts + 1, sourceEnvironment, true);
            publishCompletionIfNew(inserted, userId, course, sourceEnvironment);
            return result;
        }
        return resultAfterCompletion(userId, course, score, attempts + 1, sourceEnvironment, false);
    }

    private void publishCompletionIfNew(
            int inserted, Long userId, LearningCourseView course, String sourceEnvironment) {
        // Sandbox completion remains visible through progress and the isolated earnings entry;
        // the production H3 quest consumer must never observe it through the shared outbox.
        if (inserted != 1 || sandbox(sourceEnvironment)) return;
        eventOutboxService.publish(
                "LEARNING",
                userId + ":" + course.id() + ":" + course.version(),
                "LEARNING_COURSE_COMPLETED",
                Map.of("user_id", userId, "course_id", course.id(), "course_version", course.version(),
                        "nex_reward", nz(course.rewardNex())));
    }

    private AppLearningQuizResult resultAfterCompletion(
            Long userId, LearningCourseView course, int score, int attempts, String sourceEnvironment, boolean issueReward) {
        boolean passed = score >= (course.passScore() == null ? 100 : course.passScore());
        boolean sandbox = sandbox(sourceEnvironment);
        boolean granted = countGrantedReward(sandbox, userId, course) > 0;
        if (issueReward && passed && course.rewardNex() != null && course.rewardNex().signum() > 0) {
            if (!granted) {
                String rewardNo = sandbox
                        ? "LEARN:" + sandboxRunId() + ":" + userId + ":" + course.id() + ":" + course.version()
                        : "LEARN:" + userId + ":" + course.id() + ":" + course.version();
                granted = grantReward(sandbox, rewardNo, userId, course) == 1;
                if (granted) {
                    if (!sandbox) earningsReleaseService.creditReward(userId,
                            "LEARNING_REWARD", rewardNo, "NEX", course.rewardNex(), "PRODUCTION", rewardNo + ":NEX");
                    if (!sandbox) {
                        treasuryLedgerPostingFacade.postLedgerEntry(rewardNo, userId, "LEARNING_REWARD", "NEX", "IN",
                                course.rewardNex(), "SUCCESS", "完成课程 " + course.id() + " " + course.version());
                    }
                }
            }
            if (!granted) {
                granted = countGrantedReward(sandbox, userId, course) > 0;
            }
        }
        return new AppLearningQuizResult(course.id(), course.version(), score, passed, passed, granted,
                granted ? course.rewardNex() : BigDecimal.ZERO, attempts, true, sourceEnvironment,
                sandbox(sourceEnvironment) ? sandboxRunId() : "");
    }

    private String lockedRewardEnvironment(Long userId) {
        String sourceEnvironment = learningMapper.lockRewardEnvironment(userId);
        if (sandboxGate.isStrictDevelopmentRuntime() && "SANDBOX".equals(sourceEnvironment)) {
            return "PRODUCTION";
        }
        if (!"PRODUCTION".equals(sourceEnvironment) && !"SANDBOX".equals(sourceEnvironment)) {
            throw new BizException(409, "LEARNING_REWARD_ENVIRONMENT_INVALID");
        }
        sandboxGate.requireEnabled(sourceEnvironment);
        return sourceEnvironment;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("LEARNING_IDEMPOTENCY_HASH_UNAVAILABLE", ex);
        }
    }

    private List<LearningCourseView> publishedCourses(String sourceEnvironment) {
        if (sandbox(sourceEnvironment)) {
            return learningMapper.listSandboxPublishedCourses(sandboxRunId()).stream().map(this::toSandboxCourse).toList();
        }
        return learningRepository.listCourses().stream().filter(course -> "published".equals(course.status())).toList();
    }

    private LearningCourseView toSandboxCourse(LearningSandboxCourseRow row) {
        try {
            List<LearningQuizQuestionView> questions = StringUtils.hasText(row.quizJson())
                    ? JSON.readValue(row.quizJson(), JSON.getTypeFactory().constructCollectionType(List.class, LearningQuizQuestionView.class))
                    : List.of();
            return new LearningCourseView(row.courseId(), row.titleZh(), row.category(), row.format(), row.level(), row.rewardNex(),
                    row.featured(), row.duration(), row.version(), "published", row.bodyZh(), row.titleZh(), row.titleEn(),
                    row.bodyZh(), row.bodyEn(), questions, row.passScore(), row.retryLimit(), row.completionCondition(),
                    row.rewardEvent(), row.revision(), row.titleVi(), row.bodyVi());
        } catch (Exception ex) {
            throw new IllegalStateException("LEARNING_SANDBOX_COURSE_PAYLOAD_INVALID", ex);
        }
    }

    private LearningCourseView publishedCourse(String courseId, String sourceEnvironment) {
        if (!StringUtils.hasText(courseId)) return null;
        if (sandbox(sourceEnvironment)) {
            LearningSandboxCourseRow row = learningMapper.findSandboxPublishedCourse(sandboxRunId(), courseId.trim());
            return row == null ? null : toSandboxCourse(row);
        }
        return learningRepository.findCourse(courseId.trim())
                .filter(course -> "published".equals(course.status()))
                .orElse(null);
    }

    private Map<String, LearningProgressRow> progressByCourse(Long userId, String sourceEnvironment) {
        Map<String, LearningProgressRow> values = new LinkedHashMap<>();
        for (LearningProgressRow row : sandbox(sourceEnvironment)
                ? learningMapper.listSandboxProgress(sandboxRunId(), userId) : learningMapper.listProgress(userId)) {
            values.put(key(row.courseId(), row.courseVersion()), row);
        }
        return values;
    }

    public ApiResult<LearningQuizReceipt> quizReceipt(Long userId, String courseId, String expectedVersion, String idempotencyKey) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        requireDevelopmentAccount(userId);
        String environment = rewardEnvironmentForCommand(userId);
        LearningCourseView course = publishedCourse(courseId, environment);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        if (!StringUtils.hasText(idempotencyKey) || !course.version().equals(expectedVersion)) {
            return ApiResult.fail(409, "LEARNING_COURSE_VERSION_CONFLICT");
        }
        if (sandbox(environment)) {
            return ApiResult.ok(sandboxIdempotencyService.receipt(
                    sandboxRunId(), userId, course.id(), course.version(), idempotencyKey.trim()));
        }
        LearningSandboxIdempotencyRow stored = learningMapper.findProductionQuizReceipt(
                "APP_LEARNING_QUIZ:" + sha256(userId + "|" + course.id() + "|" + course.version()), idempotencyKey.trim());
        if (stored == null || stored.resultJson() == null) return ApiResult.ok(new LearningQuizReceipt(false, null, null));
        try {
            return ApiResult.ok(new LearningQuizReceipt(true, stored.requestHash(), JSON.readValue(stored.resultJson(), AppLearningQuizResult.class)));
        } catch (Exception ex) {
            throw new IllegalStateException("LEARNING_QUIZ_RECEIPT_INVALID", ex);
        }
    }

    private LearningProgressRow lockedOrReadProgress(String sourceEnvironment, Long userId, LearningCourseView course) {
        LearningProgressRow locked = sandbox(sourceEnvironment)
                ? learningMapper.lockSandboxProgress(sandboxRunId(), userId, course.id(), course.version())
                : learningMapper.lockProgress(userId, course.id(), course.version());
        return locked != null ? locked : findProgress(sourceEnvironment, userId, course);
    }

    private AppLearningCourseView toAppCourse(Long userId, LearningCourseView course, String language,
                                               LearningProgressRow progress, String sourceEnvironment) {
        String locale = normalizeLanguage(language);
        List<AppLearningCourseView.Question> questions = (course.quizQuestions() == null ? List.<LearningQuizQuestionView>of() : course.quizQuestions())
                .stream().map(question -> new AppLearningCourseView.Question(
                        question.questionId(), localized(locale, question.questionZh(), question.questionVi(), question.questionEn()),
                        localizedOptions(locale, question))).toList();
        int progressPct = progress == null ? 0 : progress.progressPct();
        boolean rewardGranted = progress != null && progress.progressPct() >= 100
                && countGrantedReward(sandbox(sourceEnvironment), userId, course) > 0;
        return new AppLearningCourseView(course.id(),
                localized(locale, course.titleZh(), course.titleVi(), course.titleEn()),
                localized(locale, course.bodyZh(), course.bodyVi(), course.bodyEn()),
                course.category(), course.format(), course.level(), course.duration(), course.rewardNex(), course.featured(),
                course.version(), progressPct, progressPct >= 100, progress == null ? 0 : progress.attempts(),
                progress == null ? 0 : progress.lastScore(), rewardGranted,
                true, sandbox(sourceEnvironment) ? "mock" : "provider", sourceEnvironment,
                sandbox(sourceEnvironment) ? sandboxRunId() : "",
                sandbox(sourceEnvironment) ? "ACCEPTANCE SANDBOX • NON-PRODUCTION" : "PRODUCTION LEARNING FACTS",
                questions);
    }

    private List<String> localizedOptions(String locale, LearningQuizQuestionView question) {
        List<String> values = switch (locale) {
            case "vi" -> question.optionsVi();
            case "en" -> question.optionsEn();
            default -> question.optionsZh();
        };
        if (values == null || values.isEmpty()) values = question.optionsZh();
        return values == null ? List.of() : values;
    }

    private String localized(String locale, String zh, String vi, String en) {
        String selected = switch (locale) {
            case "vi" -> vi;
            case "en" -> en;
            default -> zh;
        };
        if (StringUtils.hasText(selected)) return selected;
        if (StringUtils.hasText(zh)) return zh;
        return StringUtils.hasText(en) ? en : "";
    }

    private String normalizeLanguage(String value) {
        if (!StringUtils.hasText(value)) return "vi";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return List.of("zh", "vi", "en").contains(normalized) ? normalized : "vi";
    }

    private <T> ApiResult<T> requireExpectedVersion(LearningCourseView course, String expectedVersion) {
        if (!StringUtils.hasText(expectedVersion)) return null;
        return course.version().equals(expectedVersion.trim())
                ? null
                : ApiResult.fail(409, "LEARNING_COURSE_VERSION_CONFLICT");
    }

    private boolean sandbox(String sourceEnvironment) { return sandboxGate.enabled(sourceEnvironment); }

    private String rewardEnvironmentForRead(Long userId) {
        if (sandboxGate.isStrictDevelopmentRuntime()) {
            return "PRODUCTION";
        }
        String sourceEnvironment = learningMapper.readRewardEnvironment(userId);
        if (!"PRODUCTION".equals(sourceEnvironment) && !"SANDBOX".equals(sourceEnvironment)) {
            throw new BizException(409, "LEARNING_REWARD_ENVIRONMENT_INVALID");
        }
        sandboxGate.requireEnabled(sourceEnvironment);
        return sourceEnvironment;
    }

    private String rewardEnvironmentForCommand(Long userId) {
        String sourceEnvironment = learningMapper.readRewardEnvironment(userId);
        if (sandboxGate.isStrictDevelopmentRuntime() && "SANDBOX".equals(sourceEnvironment)) {
            return "PRODUCTION";
        }
        if (!"PRODUCTION".equals(sourceEnvironment) && !"SANDBOX".equals(sourceEnvironment)) {
            throw new BizException(409, "LEARNING_REWARD_ENVIRONMENT_INVALID");
        }
        sandboxGate.requireEnabled(sourceEnvironment);
        return sourceEnvironment;
    }

    private void requireDevelopmentAccount(Long userId) {
        if (!sandboxGate.isStrictDevelopmentRuntime()) return;
        if (learningMapper.developmentUserScope(userId) != 1) {
            throw new BizException(403, "LEARNING_DEVELOPMENT_USER_REQUIRED");
        }
    }

    private LearningProgressRow findProgress(String sourceEnvironment, Long userId, LearningCourseView course) {
        return sandbox(sourceEnvironment)
                ? learningMapper.findSandboxProgress(sandboxRunId(), userId, course.id(), course.version())
                : learningMapper.findProgress(userId, course.id(), course.version());
    }

    private int startCourse(String sourceEnvironment, Long userId, LearningCourseView course) {
        return sandbox(sourceEnvironment)
                ? learningMapper.startSandboxCourse(sandboxRunId(), userId, course.id(), course.version())
                : learningMapper.startCourse(userId, course.id(), course.version());
    }

    private int recordQuiz(String sourceEnvironment, Long userId, LearningCourseView course, int score, int progressPct) {
        return sandbox(sourceEnvironment)
                ? learningMapper.recordSandboxQuiz(sandboxRunId(), userId, course.id(), course.version(), score, progressPct)
                : learningMapper.recordQuiz(userId, course.id(), course.version(), score, progressPct);
    }

    private int insertLearningEvent(String sourceEnvironment, Long userId, LearningCourseView course, String type, String payload) {
        return sandbox(sourceEnvironment)
                ? learningMapper.insertSandboxLearningEvent(sandboxRunId(), userId, course.id(), course.version(), type, payload)
                : learningMapper.insertLearningEvent(userId, course.id(), course.version(), type, payload);
    }

    private int countGrantedReward(boolean sandbox, Long userId, LearningCourseView course) {
        return sandbox ? learningMapper.countSandboxGrantedReward(sandboxRunId(), userId, course.id(), course.version())
                : learningMapper.countGrantedReward(userId, course.id(), course.version());
    }

    private int grantReward(boolean sandbox, String rewardNo, Long userId, LearningCourseView course) {
        return sandbox ? learningMapper.grantSandboxReward(rewardNo, sandboxRunId(), userId, course.id(), course.version(), course.rewardNex())
                : learningMapper.grantReward(rewardNo, userId, course.id(), course.version(), course.rewardNex());
    }

    private String key(String courseId, String version) { return courseId + "::" + version; }
    private String sandboxRunId() {
        if (!StringUtils.hasText(acceptanceRunId) || !acceptanceRunId.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new BizException(409, "LEARNING_ACCEPTANCE_RUN_ID_REQUIRED");
        }
        return acceptanceRunId.trim();
    }
    private BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
