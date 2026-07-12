package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.I18nLearningRepository;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningQuizQuestionView;
import ffdd.opsconsole.content.dto.AppLearningQuizSubmitRequest;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AppLearningServiceTest {
    private final I18nLearningRepository repository = mock(I18nLearningRepository.class);
    private final AppLearningMapper mapper = mock(AppLearningMapper.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AppLearningService service = new AppLearningService(repository, mapper, ledger);

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
        when(mapper.grantReward(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1, 0);

        var first = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(1)));
        var replay = service.submitQuiz(42L, "test-course", new AppLearningQuizSubmitRequest(List.of(1)));

        assertThat(first.getData().passed()).isTrue();
        assertThat(first.getData().rewardGranted()).isTrue();
        assertThat(replay.getData().rewardGranted()).isFalse();
        verify(mapper, times(1)).creditWallet(42L, new BigDecimal("20.000000"));
        verify(ledger, times(1)).postLedgerEntry(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    private static LearningCourseView course(String status) {
        var question = new LearningQuizQuestionView("q1", "请选择正确答案", "Choose the answer",
                List.of("错误", "正确"), List.of("Wrong", "Correct"), 1,
                "Chọn câu trả lời đúng", List.of("Sai", "Đúng"));
        return new LearningCourseView("test-course", "测试课程", "Basics", "Article", "Beginner",
                new BigDecimal("20.000000"), true, "5 min", "v2", status, "正文",
                "测试课程", "Test course", "中文正文", "English body", List.of(question),
                80, 2, "quiz_passed", "course_completed", 3L, "Khóa học thử nghiệm", "Nội dung tiếng Việt");
    }
}
