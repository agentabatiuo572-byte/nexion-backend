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
    public ApiResult<I18nLearningOverview> overview() {
        return i18nLearningService.overview();
    }

    @PostMapping("/rescan")
    public ApiResult<I18nLearningOverview> rescan(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.rescan(idempotencyKey, request);
    }

    @PatchMapping("/messages/{messageKey}/draft")
    public ApiResult<I18nMessagePairView> saveLocalizedDraft(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nLocalizedCopyRequest request) {
        return i18nLearningService.saveLocalizedDraft(messageKey, idempotencyKey, request);
    }

    @PostMapping("/messages/{messageKey}/publish")
    public ApiResult<I18nMessagePairView> publishLocalizedMessage(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nLocalizedCopyRequest request) {
        return i18nLearningService.publishLocalizedMessage(messageKey, idempotencyKey, request);
    }

    @PostMapping("/messages/{messageKey}/marketing-experiment")
    public ApiResult<I18nMessagePairView> startMarketingExperiment(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.startMarketingExperiment(messageKey, idempotencyKey, request);
    }

    @PostMapping("/integrity/{issueCode}/fix")
    public ApiResult<I18nIntegrityIssueView> fixIntegrity(
            @PathVariable String issueCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nIntegrityFixRequest request) {
        return i18nLearningService.fixIntegrity(issueCode, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}")
    public ApiResult<LearningCourseView> createCourse(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningCourseUpsertRequest request) {
        return i18nLearningService.createCourse(courseId, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/publish")
    public ApiResult<LearningCourseView> publishCourse(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.publishCourse(courseId, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/archive")
    public ApiResult<LearningCourseView> archiveCourse(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.archiveCourse(courseId, idempotencyKey, request);
    }

    @PatchMapping("/courses/{courseId}/reward")
    public ApiResult<LearningCourseView> updateCourseReward(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningRewardUpdateRequest request) {
        return i18nLearningService.updateCourseReward(courseId, idempotencyKey, request);
    }

    @PatchMapping("/courses/featured")
    public ApiResult<LearningCourseView> updateFeaturedCourse(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningFeaturedUpdateRequest request) {
        return i18nLearningService.updateFeaturedCourse(idempotencyKey, request);
    }
}
