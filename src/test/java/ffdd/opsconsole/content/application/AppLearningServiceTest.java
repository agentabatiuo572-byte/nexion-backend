package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningProgressRow;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.dto.AppLearningQuizSubmitRequest;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class AppLearningServiceTest {
    private final I18nLearningRepository repository = mock(I18nLearningRepository.class);
    private final AppLearningMapper mapper = mock(AppLearningMapper.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final Map<String, QuizReceipt> quizReceipts = new LinkedHashMap<>();
    private final AppLearningService service = new AppLearningService(
            repository, mapper, ledger, outbox, earningsRelease, idempotencyService);

    AppLearningServiceTest() {
        when(idempotencyService.execute(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(1);
            String requestHash = invocation.getArgument(2);
            QuizReceipt existing = quizReceipts.get(key);
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) throw new BizException(409, "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                return existing.result();
            }
            Object result = ((Supplier<?>) invocation.getArgument(4)).get();
            quizReceipts.put(key, new QuizReceipt(requestHash, result));
            return result;
        });
    }

    @Test
    void overviewReturnsPublishedCoursesInVietnameseWithRealStats() {
        when(repository.listCourses()).thenReturn(List.of(course("published"), course("draft")));
        when(mapper.listProgress(42L)).thenReturn(List.of());
        when(mapper.sumGrantedReward(42L)).thenReturn(new BigDecimal("20.000000"));

        var result = service.overview(42L, "vi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().courses()).hasSize(1);
        assertThat(result.getData().courses().get(0).title()).isEqualTo("Khóa học thử nghiệm");
        assertThat(result.getData().earnedNex()).isEqualByComparingTo("20");
    }

    @Test
    void passingQuizCreditsRewardOnlyOnceForUserCourseVersion() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1, 0);
        when(mapper.insertLearningEvent(42L, "test-course", "v2", "course_completed", "{\"source\":\"quiz\"}"))
                .thenReturn(1, 0);

        var first = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:test-course:v2"));
        var replay = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:test-course:v2"));

        assertThat(first.getData().passed()).isTrue();
        assertThat(first.getData().rewardGranted()).isTrue();
        assertThat(replay.getData()).isEqualTo(first.getData());
        verify(earningsRelease, times(1)).creditReward(42L, "LEARNING_REWARD", "LEARN:42:test-course:v2",
                "NEX", new BigDecimal("20.000000"), "PRODUCTION", "LEARN:42:test-course:v2:NEX");
        verify(ledger, times(1)).postLedgerEntry(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, times(1)).publish(
                "LEARNING", "42:test-course:v2", "LEARNING_COURSE_COMPLETED",
                java.util.Map.of("user_id", 42L, "course_id", "test-course", "course_version", "v2",
                        "nex_reward", new BigDecimal("20.000000")));
        verify(mapper, times(1)).recordQuiz(42L, "test-course", "v2", 100, 100);
    }

    @Test
    void quizRejectsMissingIdempotencyKeyBeforeItCanConsumeAnAttempt() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));

        var result = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(1), " "));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("LEARNING_QUIZ_IDEMPOTENCY_KEY_REQUIRED");
        verify(mapper, times(0)).recordQuiz(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void quizRejectsSameKeyWithDifferentAnswersWithoutConsumingAnotherAttempt() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(0);

        var first = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:test-course:v2"));
        var mismatch = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(0), "learning-quiz:test-course:v2"));

        assertThat(first.getCode()).isZero();
        assertThat(mismatch.getCode()).isEqualTo(409);
        assertThat(mismatch.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        verify(mapper, times(1)).recordQuiz(42L, "test-course", "v2", 100, 100);
    }

    @Test
    void passingQuizCreditsSandboxRewardToItsSandboxLedgerExactlyOnce() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertLearningEvent(42L, "test-course", "v2", "course_completed", "{\"source\":\"quiz\"}"))
                .thenReturn(1);

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:sandbox-course:v2"));
        var replay = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:sandbox-course:v2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rewardGranted()).isTrue();
        assertThat(replay.getData()).isEqualTo(result.getData());
        verify(earningsRelease).creditReward(42L, "MOCK_LEARNING_REWARD", "LEARN:42:test-course:v2",
                "NEX", new BigDecimal("20.000000"), "SANDBOX", "LEARN:42:test-course:v2:NEX");
        verify(ledger, never()).postLedgerEntry(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void contentOnlySandboxCompletionKeepsItsVisibleRewardWithoutPublishingProductionFacts() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(courseWithoutQuiz("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertLearningEvent(
                42L, "test-course", "v2", "course_completed", "{\"source\":\"content\"}"))
                .thenReturn(1);

        var result = service.complete(42L, "test-course");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rewardGranted()).isTrue();
        assertThat(result.getData().rewardNex()).isEqualByComparingTo("20.000000");
        verify(earningsRelease).creditReward(42L, "MOCK_LEARNING_REWARD", "LEARN:42:test-course:v2",
                "NEX", new BigDecimal("20.000000"), "SANDBOX", "LEARN:42:test-course:v2:NEX");
        verify(ledger, never()).postLedgerEntry(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void contentOnlyCompletionReplayDoesNotMutateAttemptsOrIssueAnotherReward() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(courseWithoutQuiz("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.findProgress(42L, "test-course", "v2"))
                .thenReturn(new LearningProgressRow("test-course", "v2", 100, 1, LocalDateTime.now()));

        var replay = service.complete(42L, "test-course");

        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData().completed()).isTrue();
        assertThat(replay.getData().attempts()).isEqualTo(1);
        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
        verify(earningsRelease, never()).creditReward(
                anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void inconsistentRewardEnvironmentFailsClosedBeforeCompletionSideEffects() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(courseWithoutQuiz("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("UNKNOWN");

        assertThatThrownBy(() -> service.complete(42L, "test-course"))
                .isInstanceOf(BizException.class)
                .hasMessage("LEARNING_REWARD_ENVIRONMENT_INVALID");

        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
        verify(ledger, never()).postLedgerEntry(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void longestValidCourseIdStillUsesABoundedDurableIdempotencyScope() {
        String courseId = "c".repeat(81);
        when(repository.findCourse(courseId)).thenReturn(Optional.of(course(courseId, "published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, courseId, "v2")).thenReturn(null);

        var result = service.submitQuiz(42L, courseId,
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:long-course:v2"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestHash = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService).execute(scope.capture(), anyString(), requestHash.capture(), any(), any());
        assertThat(scope.getValue()).startsWith("APP_LEARNING_QUIZ:").hasSizeLessThanOrEqualTo(96);
        assertThat(requestHash.getValue()).matches("[0-9a-f]{64}");
    }

    @Test
    void malformedAnswerFailsClosedBeforeItRecordsProgressOrIssuesReward() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(2), "learning-quiz:malformed:v2"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("LEARNING_QUIZ_ANSWERS_INVALID");
        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).grantReward(anyString(), anyLong(), anyString(), anyString(), any());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void aNewKeyAfterCompletionReplaysTheCompletedStateWithoutAnotherAttemptOrReward() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, "test-course", "v2"))
                .thenReturn(new LearningProgressRow("test-course", "v2", 100, 1, LocalDateTime.now()));
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(0);

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:completed-course:v2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().completed()).isTrue();
        assertThat(result.getData().rewardGranted()).isFalse();
        assertThat(result.getData().attempts()).isEqualTo(1);
        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).insertLearningEvent(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void quizDoesNotOpenAnAmbientTransactionThatWouldTurnExpectedErrorsInto500() throws Exception {
        assertThat(AppLearningService.class
                .getMethod("submitQuiz", Long.class, String.class, AppLearningQuizSubmitRequest.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    private record QuizReceipt(String requestHash, Object result) { }

    private static LearningCourseView course(String status) {
        return course("test-course", status);
    }

    private static LearningCourseView course(String courseId, String status) {
        var question = new LearningQuizQuestionView("q1", "请选择正确答案", "Choose the answer",
                List.of("错误", "正确"), List.of("Wrong", "Correct"), 1,
                "Chọn câu trả lời đúng", List.of("Sai", "Đúng"));
        return new LearningCourseView(courseId, "测试课程", "Basics", "Article", "Beginner",
                new BigDecimal("20.000000"), true, "5 min", "v2", status, "正文",
                "测试课程", "Test course", "中文正文", "English body", List.of(question),
                80, 2, "quiz_passed", "course_completed", 3L, "Khóa học thử nghiệm", "Nội dung tiếng Việt");
    }

    private static LearningCourseView courseWithoutQuiz(String status) {
        return new LearningCourseView("test-course", "测试课程", "Basics", "Article", "Beginner",
                new BigDecimal("20.000000"), true, "5 min", "v2", status, "正文",
                "测试课程", "Test course", "中文正文", "English body", List.of(),
                80, 2, "content_complete", "course_completed", 3L, "Khóa học thử nghiệm", "Nội dung tiếng Việt");
    }
}
