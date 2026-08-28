package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningProgressRow;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.domain.LearningSandboxCourseRow;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class AppLearningServiceTest {
    private final I18nLearningRepository repository = mock(I18nLearningRepository.class);
    private final AppLearningMapper mapper = mock(AppLearningMapper.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final EarningsReleaseService earningsRelease = mock(EarningsReleaseService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final LearningAcceptanceSandboxGate sandboxGate = mock(LearningAcceptanceSandboxGate.class);
    private final LearningSandboxQuizIdempotencyService sandboxIdempotencyService = mock(LearningSandboxQuizIdempotencyService.class);
    private final Map<String, QuizReceipt> quizReceipts = new LinkedHashMap<>();
    private final AppLearningService service = new AppLearningService(
            repository, mapper, ledger, outbox, earningsRelease, idempotencyService, sandboxGate,
            sandboxIdempotencyService, "test-learning-run");

    AppLearningServiceTest() {
        when(mapper.readRewardEnvironment(anyLong())).thenReturn("PRODUCTION");
        when(sandboxGate.enabled("PRODUCTION")).thenReturn(false);
        when(sandboxGate.enabled("SANDBOX")).thenReturn(true);
        when(sandboxIdempotencyService.execute(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(6)).get());
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
        when(mapper.readRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.listProgress(42L)).thenReturn(List.of());
        when(mapper.sumGrantedReward(42L)).thenReturn(new BigDecimal("20.000000"));

        var result = service.overview(42L, "vi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().courses()).hasSize(1);
        assertThat(result.getData().courses().get(0).title()).isEqualTo("Khóa học thử nghiệm");
        assertThat(result.getData().earnedNex()).isEqualByComparingTo("20");
        assertThat(result.getData().serverCanonical()).isTrue();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().courses().get(0).serverCanonical()).isTrue();
        assertThat(result.getData().courses().get(0).sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().courses().get(0).runId()).isEmpty();
    }

    @Test
    void developmentOverviewReadsPublishedBusinessFactsWithProductionProvenance() {
        when(sandboxGate.isStrictDevelopmentRuntime()).thenReturn(true);
        allowDevelopmentAccount();
        when(mapper.readRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(repository.listCourses()).thenReturn(List.of(course("published")));
        when(mapper.listProgress(42L)).thenReturn(List.of());
        when(mapper.sumGrantedReward(42L)).thenReturn(new BigDecimal("20.000000"));

        var result = service.overview(42L, "vi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        assertThat(result.getData().courses()).singleElement().satisfies(course -> {
            assertThat(course.source()).isEqualTo("provider");
            assertThat(course.sourceEnvironment()).isEqualTo("PRODUCTION");
            assertThat(course.runId()).isEmpty();
        });
        verify(mapper).listProgress(42L);
        verify(mapper).sumGrantedReward(42L);
        verify(mapper, never()).listSandboxProgress(anyString(), anyLong());
        verify(mapper, never()).sumSandboxGrantedReward(anyString(), anyLong());
    }

    @Test
    void developmentOverviewRejectsAnAccountOutsideTheActiveDevelopmentUserScope() {
        when(sandboxGate.isStrictDevelopmentRuntime()).thenReturn(true);
        when(mapper.developmentUserScope(42L)).thenReturn(0);

        assertThatThrownBy(() -> service.overview(42L, "vi"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("LEARNING_DEVELOPMENT_USER_REQUIRED");

        verify(repository, never()).listCourses();
        verify(mapper, never()).listProgress(anyLong());
    }

    @Test
    void sandboxOverviewReadsOnlyTheIsolatedProjectionAndRewardLedger() {
        when(mapper.readRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.listSandboxPublishedCourses("test-learning-run")).thenReturn(List.of(sandboxCourse()));
        when(mapper.listSandboxProgress("test-learning-run", 42L)).thenReturn(List.of(
                new LearningProgressRow("test-course", "v2", 100, 1, 100, LocalDateTime.now())));
        when(mapper.countSandboxGrantedReward("test-learning-run", 42L, "test-course", "v2")).thenReturn(1);
        when(mapper.sumSandboxGrantedReward("test-learning-run", 42L)).thenReturn(new BigDecimal("20.000000"));

        var result = service.overview(42L, "vi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().completedCourses()).isEqualTo(1);
        assertThat(result.getData().earnedNex()).isEqualByComparingTo("20");
        verify(mapper, never()).listProgress(42L);
        verify(mapper, never()).sumGrantedReward(42L);
    }

    @Test
    void sandboxCourseDetailReadsOnlyTheIsolatedProgressProjection() {
        when(mapper.readRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.findSandboxPublishedCourse("test-learning-run", "test-course")).thenReturn(sandboxCourse());
        when(mapper.findSandboxProgress("test-learning-run", 42L, "test-course", "v2"))
                .thenReturn(new LearningProgressRow("test-course", "v2", 50, 1, 50, null));

        var result = service.course(42L, "test-course", "vi");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().progress()).isEqualTo(50);
        assertThat(result.getData().serverCanonical()).isTrue();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(result.getData().runId()).isEqualTo("test-learning-run");
        verify(mapper, never()).findProgress(42L, "test-course", "v2");
    }

    @Test
    void dottedAcceptanceRunIdRemainsAValidSandboxPartition() {
        ReflectionTestUtils.setField(service, "acceptanceRunId", "acceptance.2026-08");
        when(mapper.readRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.listSandboxPublishedCourses("acceptance.2026-08")).thenReturn(List.of(sandboxCourse()));
        when(mapper.listSandboxProgress("acceptance.2026-08", 42L)).thenReturn(List.of());
        when(mapper.sumSandboxGrantedReward("acceptance.2026-08", 42L)).thenReturn(BigDecimal.ZERO);

        var result = service.overview(42L, "vi");

        assertThat(result.getCode()).isZero();
        verify(mapper).listSandboxProgress("acceptance.2026-08", 42L);
        verify(mapper).sumSandboxGrantedReward("acceptance.2026-08", 42L);
    }

    @Test
    void controlledProfileNormalUserFailsBeforeAnySharedLearningProjectionCanBeRead() {
        when(mapper.readRewardEnvironment(42L)).thenReturn("PRODUCTION");
        doThrow(new IllegalStateException("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED"))
                .when(sandboxGate).requireEnabled("PRODUCTION");

        assertThatThrownBy(() -> service.overview(42L, "vi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED");

        verify(mapper, never()).listProgress(42L);
        verify(mapper, never()).sumGrantedReward(42L);
    }

    @Test
    void controlledProfileNormalUserFailsBeforeStartCanWriteAnyLearningFact() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        doThrow(new IllegalStateException("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED"))
                .when(sandboxGate).requireEnabled("PRODUCTION");

        assertThatThrownBy(() -> service.start(42L, "test-course", "vi", "v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LEARNING_ACCEPTANCE_SANDBOX_USER_REQUIRED");

        verify(mapper, never()).startCourse(anyLong(), anyString(), anyString());
        verify(mapper, never()).startSandboxCourse(anyString(), anyLong(), anyString(), anyString());
        verify(mapper, never()).insertLearningEvent(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, never()).insertSandboxLearningEvent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
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
        verify(idempotencyService, times(2)).execute(anyString(), anyString(), anyString(), any(), any());
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
        when(mapper.findSandboxPublishedCourse("test-learning-run", "test-course")).thenReturn(sandboxCourse());
        when(mapper.findSandboxProgress("test-learning-run", 42L, "test-course", "v2")).thenReturn(null);
        when(mapper.readRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.grantSandboxReward(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertSandboxLearningEvent("test-learning-run", 42L, "test-course", "v2", "course_completed", "{\"source\":\"quiz\"}"))
                .thenReturn(1);

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:sandbox-course:v2"));
        var replay = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:sandbox-course:v2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rewardGranted()).isTrue();
        assertThat(replay.getData()).isEqualTo(result.getData());
        verify(sandboxIdempotencyService, times(2)).execute(
                org.mockito.ArgumentMatchers.eq("test-learning-run"), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("test-course"),
                org.mockito.ArgumentMatchers.eq("v2"), anyString(),
                org.mockito.ArgumentMatchers.eq("learning-quiz:sandbox-course:v2"), any());
        verify(idempotencyService, never()).execute(anyString(), anyString(), anyString(), any(), any());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
        verify(mapper, never()).startCourse(anyLong(), anyString(), anyString());
        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).insertLearningEvent(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, never()).grantReward(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void developmentQuizWritesCanonicalProductionFactsForAnyActiveDevelopmentAccount() {
        when(sandboxGate.isStrictDevelopmentRuntime()).thenReturn(true);
        allowDevelopmentAccount();
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.readRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertLearningEvent(42L, "test-course", "v2", "course_completed", "{\"source\":\"quiz\"}"))
                .thenReturn(1);

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:development:v2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getData().runId()).isEmpty();
        verify(mapper).recordQuiz(42L, "test-course", "v2", 100, 100);
        verify(mapper, never()).recordSandboxQuiz(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyInt());
    }

    private void allowDevelopmentAccount() {
        when(mapper.developmentUserScope(42L)).thenReturn(1);
    }

    @Test
    void contentOnlySandboxCompletionKeepsItsVisibleRewardWithoutPublishingProductionFacts() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(courseWithoutQuiz("published")));
        when(mapper.findSandboxPublishedCourse("test-learning-run", "test-course")).thenReturn(sandboxCourseWithoutQuiz());
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.grantSandboxReward(anyString(), anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertSandboxLearningEvent(
                "test-learning-run", 42L, "test-course", "v2", "course_completed", "{\"source\":\"content\"}"))
                .thenReturn(1);

        var result = service.complete(42L, "test-course");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rewardGranted()).isTrue();
        assertThat(result.getData().rewardNex()).isEqualByComparingTo("20.000000");
        assertThat(result.getData().serverCanonical()).isTrue();
        assertThat(result.getData().sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(result.getData().runId()).isEqualTo("test-learning-run");
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(
                anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
        verify(mapper, never()).startCourse(anyLong(), anyString(), anyString());
        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(mapper, never()).insertLearningEvent(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, never()).grantReward(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void contentOnlyCompletionReplayDoesNotMutateAttemptsOrIssueAnotherReward() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(courseWithoutQuiz("published")));
        when(mapper.findSandboxPublishedCourse("test-learning-run", "test-course")).thenReturn(sandboxCourseWithoutQuiz());
        when(mapper.lockRewardEnvironment(42L)).thenReturn("SANDBOX");
        when(mapper.findSandboxProgress("test-learning-run", 42L, "test-course", "v2"))
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
        verify(mapper, never()).grantReward(anyString(), anyLong(), anyString(), anyString(), any());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void staleQuizVersionFailsClosedBeforeItCanConsumeAnAttempt() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:stale-version", "v1"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("LEARNING_COURSE_VERSION_CONFLICT");
        verify(mapper, never()).recordQuiz(anyLong(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void completedQuizReadbackReportsAnAlreadyGrantedServerRewardWithoutIssuingItAgain() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, "test-course", "v2"))
                .thenReturn(new LearningProgressRow("test-course", "v2", 100, 1, 100, LocalDateTime.now()));
        when(mapper.countGrantedReward(42L, "test-course", "v2")).thenReturn(1);

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:server-readback", "v2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().rewardGranted()).isTrue();
        assertThat(result.getData().rewardNex()).isEqualByComparingTo("20.000000");
        verify(mapper, never()).grantReward(anyString(), anyLong(), anyString(), anyString(), any());
        verify(earningsRelease, never()).creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void rewardFailureKeepsTheCompletionUnpublishedSoTheIdempotencyTransactionCanRollBack() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertLearningEvent(42L, "test-course", "v2", "course_completed", "{\"source\":\"quiz\"}"))
                .thenReturn(1);
        when(earningsRelease.creditReward(anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new BizException(409, "LEARNING_REWARD_CREDIT_FAILED"));

        var result = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz:reward-failure", "v2"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("LEARNING_REWARD_CREDIT_FAILED");
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void failedAnswerClosesItsAttemptSoAChangedAnswerWithANewKeyCanPassExactlyOnce() {
        when(repository.findCourse("test-course")).thenReturn(Optional.of(course("published")));
        when(mapper.lockRewardEnvironment(42L)).thenReturn("PRODUCTION");
        when(mapper.findProgress(42L, "test-course", "v2")).thenReturn(null);
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.insertLearningEvent(42L, "test-course", "v2", "course_completed", "{\"source\":\"quiz\"}"))
                .thenReturn(1);

        var failed = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(0), "learning-quiz-attempt-0", "v2"));
        var passed = service.submitQuiz(42L, "test-course",
                new AppLearningQuizSubmitRequest(List.of(1), "learning-quiz-attempt-1", "v2"));

        assertThat(failed.getCode()).isZero();
        assertThat(failed.getData().passed()).isFalse();
        assertThat(passed.getCode()).isZero();
        assertThat(passed.getData().rewardGranted()).isTrue();
        verify(mapper, times(2)).recordQuiz(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("test-course"), org.mockito.ArgumentMatchers.eq("v2"),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verify(earningsRelease, times(1)).creditReward(42L, "LEARNING_REWARD", "LEARN:42:test-course:v2",
                "NEX", new BigDecimal("20.000000"), "PRODUCTION", "LEARN:42:test-course:v2:NEX");
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

    private static LearningSandboxCourseRow sandboxCourse() {
        return new LearningSandboxCourseRow("test-course", "v2", "PUBLISHED", "测试课程", "Test course", "Khóa học thử nghiệm",
                "中文正文", "English body", "Nội dung tiếng Việt", "Basics", "Article", "Beginner", new BigDecimal("20.000000"),
                "5 min", true, "[{\"questionId\":\"q1\",\"questionZh\":\"请选择正确答案\",\"questionEn\":\"Choose the answer\",\"optionsZh\":[\"错误\",\"正确\"],\"optionsEn\":[\"Wrong\",\"Correct\"],\"correctOptionIndex\":1,\"questionVi\":\"Chọn câu trả lời đúng\",\"optionsVi\":[\"Sai\",\"Đúng\"]}]",
                80, 2, "quiz_passed", "course_completed", 1L);
    }

    private static LearningSandboxCourseRow sandboxCourseWithoutQuiz() {
        return new LearningSandboxCourseRow("test-course", "v2", "PUBLISHED", "测试课程", "Test course", "Khóa học thử nghiệm",
                "中文正文", "English body", "Nội dung tiếng Việt", "Basics", "Article", "Beginner", new BigDecimal("20.000000"),
                "5 min", true, "[]", 80, 2, "content_complete", "course_completed", 1L);
    }
}
