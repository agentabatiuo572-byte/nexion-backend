package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsI18nLearningService;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningOverview;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/i18n-learning")
@RequiredArgsConstructor
public class OpsI18nLearningController {
    private final OpsI18nLearningService i18nLearningService;

    @GetMapping("/overview")
    // 双域合并总览（i18n 文案 + 教程中心），任一读权限即可
    @PreAuthorize("hasAnyAuthority('content_i6_read','content_i7_read')")
    public ApiResult<I18nLearningOverview> overview() {
        return i18nLearningService.overview();
    }

    @PostMapping("/rescan")
    // i18n 文案重扫
    @PreAuthorize("hasAuthority('content_i6_write')")
    public ApiResult<I18nLearningOverview> rescan(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.rescan(idempotencyKey, request);
    }

    @PatchMapping("/messages/{messageKey}/draft")
    // i18n 词条多语种草稿
    @PreAuthorize("hasAuthority('content_i6_write')")
    public ApiResult<I18nMessagePairView> saveLocalizedDraft(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nLocalizedCopyRequest request) {
        return i18nLearningService.saveLocalizedDraft(messageKey, idempotencyKey, request);
    }

    @PostMapping("/messages/{messageKey}/publish")
    // i18n 词条多语种发布
    @PreAuthorize("hasAuthority('content_i6_write')")
    public ApiResult<I18nMessagePairView> publishLocalizedMessage(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nLocalizedCopyRequest request) {
        return i18nLearningService.publishLocalizedMessage(messageKey, idempotencyKey, request);
    }

    @PostMapping("/messages/{messageKey}/marketing-experiment")
    // i18n marketing 文案多版实验
    @PreAuthorize("hasAuthority('content_i6_write')")
    public ApiResult<I18nMessagePairView> startMarketingExperiment(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.startMarketingExperiment(messageKey, idempotencyKey, request);
    }

    @PostMapping("/integrity/{issueCode}/fix")
    // i18n 完整性修复
    @PreAuthorize("hasAuthority('content_i6_write')")
    public ApiResult<I18nIntegrityIssueView> fixIntegrity(
            @PathVariable String issueCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nIntegrityFixRequest request) {
        return i18nLearningService.fixIntegrity(issueCode, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}")
    // 教程中心：新建课程
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseView> createCourse(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningCourseUpsertRequest request) {
        return i18nLearningService.createCourse(courseId, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/publish")
    // 教程中心：课程发布
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseView> publishCourse(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.publishCourse(courseId, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/archive")
    // 教程中心：课程下架
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseView> archiveCourse(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.archiveCourse(courseId, idempotencyKey, request);
    }

    @PatchMapping("/courses/{courseId}/reward")
    // HIGH：课程奖励调整（amplifies，关联 B1 兑付覆盖率红线）
    @PreAuthorize("hasAuthority('content_i7_course_reward_adjust')")
    public ApiResult<LearningCourseView> updateCourseReward(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningRewardUpdateRequest request) {
        return i18nLearningService.updateCourseReward(courseId, idempotencyKey, request);
    }

    @PatchMapping("/courses/featured")
    // 教程中心：推荐课配置
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseView> updateFeaturedCourse(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningFeaturedUpdateRequest request) {
        return i18nLearningService.updateFeaturedCourse(idempotencyKey, request);
    }
}
