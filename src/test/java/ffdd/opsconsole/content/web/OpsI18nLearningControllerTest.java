package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.OpsI18nLearningService;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OpsI18nLearningControllerTest {
    private final OpsI18nLearningService service = mock(OpsI18nLearningService.class);
    private final OpsI18nLearningController controller = new OpsI18nLearningController(service);

    @Test
    void overviewAndI18nActionsDelegate() {
        I18nLocalizedCopyRequest copy = new I18nLocalizedCopyRequest("中文 {amount}", "English {amount}", "Marina K.", "同步词条");
        I18nActionRequest action = new I18nActionRequest("Marina K.", "启动扫描");
        I18nIntegrityFixRequest fix = new I18nIntegrityFixRequest("中文 {amount}", "English {amount}", "Marina K.", "修复问题");
        when(service.overview()).thenReturn(ApiResult.ok(null));
        when(service.rescan("idem-i6-scan", action)).thenReturn(ApiResult.ok(null));
        when(service.saveLocalizedDraft("milestones.earnCross", "idem-i6-draft", copy)).thenReturn(ApiResult.ok(null));
        when(service.publishLocalizedMessage("milestones.earnCross", "idem-i6-pub", copy)).thenReturn(ApiResult.ok(null));
        when(service.startMarketingExperiment("milestones.earnCross", "idem-i6-ab", action)).thenReturn(ApiResult.ok(null));
        when(service.fixIntegrity("missing-zh", "idem-i6-fix", fix)).thenReturn(ApiResult.ok(null));

        assertThat(controller.overview().getCode()).isZero();
        assertThat(controller.rescan("idem-i6-scan", action).getCode()).isZero();
        assertThat(controller.saveLocalizedDraft("milestones.earnCross", "idem-i6-draft", copy).getCode()).isZero();
        assertThat(controller.publishLocalizedMessage("milestones.earnCross", "idem-i6-pub", copy).getCode()).isZero();
        assertThat(controller.startMarketingExperiment("milestones.earnCross", "idem-i6-ab", action).getCode()).isZero();
        assertThat(controller.fixIntegrity("missing-zh", "idem-i6-fix", fix).getCode()).isZero();

        verify(service).overview();
        verify(service).rescan("idem-i6-scan", action);
        verify(service).saveLocalizedDraft("milestones.earnCross", "idem-i6-draft", copy);
        verify(service).publishLocalizedMessage("milestones.earnCross", "idem-i6-pub", copy);
        verify(service).startMarketingExperiment("milestones.earnCross", "idem-i6-ab", action);
        verify(service).fixIntegrity("missing-zh", "idem-i6-fix", fix);
    }

    @Test
    void learningCourseActionsDelegateWithIdempotencyHeader() {
        LearningCourseUpsertRequest create = new LearningCourseUpsertRequest(
                "新计算指南", "New compute guide", "中文", "English", "Basics", "Article", "Beginner",
                new BigDecimal("20"), "6 min", "draft", "Marina K.", "新建课程");
        LearningRewardUpdateRequest reward = new LearningRewardUpdateRequest(new BigDecimal("30"), "Marina K.", "调整奖励");
        LearningFeaturedUpdateRequest featured = new LearningFeaturedUpdateRequest("what-is-nexion", "Marina K.", "切换推荐位");
        I18nActionRequest action = new I18nActionRequest("Marina K.", "课程动作");
        when(service.createCourse("new-compute-guide", "idem-i7-create", create)).thenReturn(ApiResult.ok(null));
        when(service.publishCourse("new-compute-guide", "idem-i7-pub", action)).thenReturn(ApiResult.ok(null));
        when(service.archiveCourse("new-compute-guide", "idem-i7-archive", action)).thenReturn(ApiResult.ok(null));
        when(service.updateCourseReward("new-compute-guide", "idem-i7-reward", reward)).thenReturn(ApiResult.ok(null));
        when(service.updateFeaturedCourse("idem-i7-featured", featured)).thenReturn(ApiResult.ok(null));

        assertThat(controller.createCourse("new-compute-guide", "idem-i7-create", create).getCode()).isZero();
        assertThat(controller.publishCourse("new-compute-guide", "idem-i7-pub", action).getCode()).isZero();
        assertThat(controller.archiveCourse("new-compute-guide", "idem-i7-archive", action).getCode()).isZero();
        assertThat(controller.updateCourseReward("new-compute-guide", "idem-i7-reward", reward).getCode()).isZero();
        assertThat(controller.updateFeaturedCourse("idem-i7-featured", featured).getCode()).isZero();

        verify(service).createCourse("new-compute-guide", "idem-i7-create", create);
        verify(service).publishCourse("new-compute-guide", "idem-i7-pub", action);
        verify(service).archiveCourse("new-compute-guide", "idem-i7-archive", action);
        verify(service).updateCourseReward("new-compute-guide", "idem-i7-reward", reward);
        verify(service).updateFeaturedCourse("idem-i7-featured", featured);
    }
}
