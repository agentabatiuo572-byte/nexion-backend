package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AppLearningCourseView;
import ffdd.opsconsole.content.domain.AppLearningOverview;
import ffdd.opsconsole.content.domain.AppLearningQuizResult;
import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.domain.LearningProgressRow;
import ffdd.opsconsole.content.dto.AppLearningQuizSubmitRequest;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class AppLearningService {
    private final I18nLearningRepository learningRepository;
    private final AppLearningMapper learningMapper;
    private final TreasuryLedgerPostingFacade treasuryLedgerPostingFacade;
    private final EventOutboxService eventOutboxService;

    public ApiResult<AppLearningOverview> overview(Long userId, String language) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        Map<String, LearningProgressRow> progress = progressByCourse(userId);
        List<AppLearningCourseView> courses = learningRepository.listCourses().stream()
                .filter(course -> "published".equals(course.status()))
                .map(course -> toAppCourse(course, language, progress.get(key(course.id(), course.version()))))
                .toList();
        int completed = (int) courses.stream().filter(AppLearningCourseView::completed).count();
        BigDecimal earned = learningMapper.sumGrantedReward(userId);
        return ApiResult.ok(new AppLearningOverview(courses, completed, courses.size(), nz(earned)));
    }

    public ApiResult<AppLearningCourseView> course(Long userId, String courseId, String language) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        LearningCourseView course = publishedCourse(courseId);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        return ApiResult.ok(toAppCourse(course, language,
                learningMapper.findProgress(userId, course.id(), course.version())));
    }

    @Transactional
    public ApiResult<AppLearningCourseView> start(Long userId, String courseId, String language) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        LearningCourseView course = publishedCourse(courseId);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        learningMapper.startCourse(userId, course.id(), course.version());
        learningMapper.insertLearningEvent(userId, course.id(), course.version(), "course_started", "{}");
        return ApiResult.ok(toAppCourse(course, language,
                learningMapper.findProgress(userId, course.id(), course.version())));
    }

    @Transactional
    public ApiResult<AppLearningQuizResult> complete(Long userId, String courseId) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        LearningCourseView course = publishedCourse(courseId);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        if (course.quizQuestions() != null && !course.quizQuestions().isEmpty()) {
            return ApiResult.fail(409, "LEARNING_QUIZ_REQUIRED");
        }
        learningMapper.recordQuiz(userId, course.id(), course.version(), 100, 100);
        int inserted = learningMapper.insertLearningEvent(
                userId, course.id(), course.version(), "course_completed", "{\"source\":\"content\"}");
        publishCompletionIfNew(inserted, userId, course);
        return ApiResult.ok(resultAfterCompletion(userId, course, 100, 1));
    }

    @Transactional
    public ApiResult<AppLearningQuizResult> submitQuiz(Long userId, String courseId, AppLearningQuizSubmitRequest request) {
        if (userId == null || userId <= 0) return ApiResult.fail(403, "USER_AUTH_REQUIRED");
        LearningCourseView course = publishedCourse(courseId);
        if (course == null) return ApiResult.fail(404, "LEARNING_COURSE_NOT_FOUND");
        List<LearningQuizQuestionView> questions = course.quizQuestions() == null ? List.of() : course.quizQuestions();
        if (questions.isEmpty()) return ApiResult.fail(409, "LEARNING_QUIZ_NOT_CONFIGURED");
        if (request == null || request.answers() == null || request.answers().size() != questions.size()
                || request.answers().stream().anyMatch(answer -> answer == null || answer < 0)) {
            return ApiResult.fail(422, "LEARNING_QUIZ_ANSWERS_INVALID");
        }
        LearningProgressRow progress = learningMapper.findProgress(userId, course.id(), course.version());
        int attempts = progress == null ? 0 : progress.attempts();
        int maxAttempts = course.retryLimit() == null ? 1 : course.retryLimit() + 1;
        if (attempts >= maxAttempts) return ApiResult.fail(409, "LEARNING_QUIZ_RETRY_LIMIT_REACHED");
        int correct = 0;
        for (int index = 0; index < questions.size(); index += 1) {
            int answer = request.answers().get(index);
            if (answer >= questions.get(index).optionsZh().size()) {
                return ApiResult.fail(422, "LEARNING_QUIZ_ANSWERS_INVALID");
            }
            if (answer == questions.get(index).correctOptionIndex()) correct += 1;
        }
        int score = BigDecimal.valueOf(correct * 100L)
                .divide(BigDecimal.valueOf(questions.size()), 0, RoundingMode.HALF_UP).intValue();
        boolean passed = score >= (course.passScore() == null ? 100 : course.passScore());
        learningMapper.recordQuiz(userId, course.id(), course.version(), score, passed ? 100 : 50);
        if (passed) {
            learningMapper.insertLearningEvent(userId, course.id(), course.version(), "quiz_passed", "{\"score\":" + score + "}");
            int inserted = learningMapper.insertLearningEvent(
                    userId, course.id(), course.version(), "course_completed", "{\"source\":\"quiz\"}");
            publishCompletionIfNew(inserted, userId, course);
        }
        return ApiResult.ok(resultAfterCompletion(userId, course, score, attempts + 1));
    }

    private void publishCompletionIfNew(int inserted, Long userId, LearningCourseView course) {
        if (inserted != 1) return;
        eventOutboxService.publish(
                "LEARNING",
                userId + ":" + course.id() + ":" + course.version(),
                "LEARNING_COURSE_COMPLETED",
                Map.of("user_id", userId, "course_id", course.id(), "course_version", course.version(),
                        "nex_reward", nz(course.rewardNex())));
    }

    private AppLearningQuizResult resultAfterCompletion(Long userId, LearningCourseView course, int score, int attempts) {
        boolean passed = score >= (course.passScore() == null ? 100 : course.passScore());
        boolean granted = false;
        if (passed && course.rewardNex() != null && course.rewardNex().signum() > 0) {
            String rewardNo = "LEARN:" + userId + ":" + course.id() + ":" + course.version();
            granted = learningMapper.grantReward(rewardNo, userId, course.id(), course.version(), course.rewardNex()) == 1;
            if (granted) {
                learningMapper.creditWallet(userId, course.rewardNex());
                treasuryLedgerPostingFacade.postLedgerEntry(rewardNo, userId, "LEARNING_REWARD", "NEX", "IN",
                        course.rewardNex(), "SUCCESS", "完成课程 " + course.id() + " " + course.version());
            }
        }
        return new AppLearningQuizResult(course.id(), course.version(), score, passed, passed, granted,
                granted ? course.rewardNex() : BigDecimal.ZERO, attempts);
    }

    private LearningCourseView publishedCourse(String courseId) {
        if (!StringUtils.hasText(courseId)) return null;
        return learningRepository.findCourse(courseId.trim())
                .filter(course -> "published".equals(course.status()))
                .orElse(null);
    }

    private Map<String, LearningProgressRow> progressByCourse(Long userId) {
        Map<String, LearningProgressRow> values = new LinkedHashMap<>();
        for (LearningProgressRow row : learningMapper.listProgress(userId)) {
            values.put(key(row.courseId(), row.courseVersion()), row);
        }
        return values;
    }

    private AppLearningCourseView toAppCourse(LearningCourseView course, String language, LearningProgressRow progress) {
        String locale = normalizeLanguage(language);
        List<AppLearningCourseView.Question> questions = (course.quizQuestions() == null ? List.<LearningQuizQuestionView>of() : course.quizQuestions())
                .stream().map(question -> new AppLearningCourseView.Question(
                        question.questionId(), localized(locale, question.questionZh(), question.questionVi(), question.questionEn()),
                        localizedOptions(locale, question))).toList();
        int progressPct = progress == null ? 0 : progress.progressPct();
        return new AppLearningCourseView(course.id(),
                localized(locale, course.titleZh(), course.titleVi(), course.titleEn()),
                localized(locale, course.bodyZh(), course.bodyVi(), course.bodyEn()),
                course.category(), course.format(), course.level(), course.duration(), course.rewardNex(), course.featured(),
                course.version(), progressPct, progressPct >= 100, questions);
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

    private String key(String courseId, String version) { return courseId + "::" + version; }
    private BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
