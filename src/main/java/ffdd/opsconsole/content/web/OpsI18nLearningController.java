package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsI18nLearningService;
import ffdd.opsconsole.content.domain.I18nIntegrityIssueView;
import ffdd.opsconsole.content.domain.I18nLearningOverview;
import ffdd.opsconsole.content.domain.I18nMessagePairView;
import ffdd.opsconsole.content.domain.LearningCourseView;
import ffdd.opsconsole.content.domain.LearningCourseVersionView;
import ffdd.opsconsole.content.dto.I18nActionRequest;
import ffdd.opsconsole.content.dto.I18nIntegrityFixRequest;
import ffdd.opsconsole.content.dto.I18nLocalizedCopyRequest;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.content.dto.LearningFeaturedUpdateRequest;
import ffdd.opsconsole.content.dto.LearningRewardUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public ApiResult<I18nLearningOverview> overview(Authentication authentication) {
        I18nLearningOverview source = i18nLearningService.overview().getData();
        boolean superadmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SUPER_ADMIN".equals(authority.getAuthority())
                        || "SUPER_ADMIN".equals(authority.getAuthority()));
        boolean canReadI6 = superadmin || hasAuthority(authentication, "content_i6_read");
        boolean canReadI7 = superadmin || hasAuthority(authentication, "content_i7_read");
        if (source == null) {
            return ApiResult.ok(null);
        }
        return ApiResult.ok(filterOverview(source, canReadI6, canReadI7));
    }

    private boolean hasAuthority(Authentication authentication, String expected) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }

    private I18nLearningOverview filterOverview(I18nLearningOverview source, boolean canReadI6, boolean canReadI7) {
        var stats = source.stats();
        return new I18nLearningOverview(
                new ffdd.opsconsole.content.domain.I18nLearningStats(
                        canReadI6 ? stats.managedKeys() : 0,
                        canReadI6 ? stats.totalKeys() : 0,
                        canReadI6 ? stats.integrityIssues() : 0,
                        canReadI7 ? stats.coursesOnline() : 0,
                        canReadI7 ? stats.weeklyNexPayout() : "—",
                        canReadI7 ? stats.coverageRatio() : java.math.BigDecimal.ZERO,
                        canReadI7 ? stats.redlinePct() : java.math.BigDecimal.ZERO),
                canReadI6 ? source.namespaces() : java.util.List.of(),
                canReadI6 ? source.integrityIssues() : java.util.List.of(),
                canReadI6 ? source.hardcodedFindings() : java.util.List.of(),
                canReadI6 ? source.focusMessage() : null,
                canReadI6 ? source.messages() : java.util.List.of(),
                canReadI7 ? source.courses() : java.util.List.of(),
                canReadI7 ? source.rewardRange() : null,
                canReadI7 ? source.featuredCourseId() : "",
                canReadI7 ? source.metrics() : java.util.List.of(),
                canReadI7 ? source.categories() : java.util.List.of(),
                canReadI7 ? source.formats() : java.util.List.of(),
                canReadI7 ? source.levels() : java.util.List.of(),
                canReadI7 ? source.statuses() : java.util.List.of(),
                canReadI7 ? source.sources() : java.util.List.of());
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

    @DeleteMapping("/messages/{messageKey}")
    @PreAuthorize("hasAuthority('content_i6_write')")
    public ApiResult<I18nMessagePairView> archiveLocalizedMessage(
            @PathVariable String messageKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.archiveLocalizedMessage(messageKey, idempotencyKey, request);
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

    @GetMapping("/courses/{courseId}/versions")
    @PreAuthorize("hasAuthority('content_i7_read')")
    public ApiResult<java.util.List<LearningCourseVersionView>> courseVersions(@PathVariable String courseId) {
        return i18nLearningService.courseVersions(courseId);
    }

    @PostMapping("/courses/{courseId}/versions")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseVersionView> createCourseVersion(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningCourseUpsertRequest request) {
        return i18nLearningService.createCourseVersion(courseId, idempotencyKey, request);
    }

    @PatchMapping("/courses/{courseId}/versions/{version}")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseVersionView> updateCourseVersion(
            @PathVariable String courseId, @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningCourseUpsertRequest request) {
        return i18nLearningService.updateCourseVersion(courseId, version, idempotencyKey, request);
    }

    @DeleteMapping("/courses/{courseId}/versions/{version}")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<Void> deleteCourseVersion(
            @PathVariable String courseId, @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.deleteCourseVersion(courseId, version, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/versions/{version}/publish")
    @PreAuthorize("hasAuthority('content_i7_write') and hasAuthority('content_i7_course_reward_adjust')")
    public ApiResult<LearningCourseView> publishCourseVersion(
            @PathVariable String courseId, @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.publishCourseVersion(courseId, version, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/versions/{version}/rollback")
    @PreAuthorize("hasAuthority('content_i7_write') and hasAuthority('content_i7_course_reward_adjust')")
    public ApiResult<LearningCourseView> rollbackCourseVersion(
            @PathVariable String courseId, @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.rollbackCourseVersion(courseId, version, idempotencyKey, request);
    }

    @PatchMapping("/courses/{courseId}/draft")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningCourseView> updateCourseDraft(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody LearningCourseUpsertRequest request) {
        return i18nLearningService.updateCourseDraft(courseId, idempotencyKey, request);
    }

    @DeleteMapping("/courses/{courseId}")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<Void> deleteCourseDraft(
            @PathVariable String courseId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody I18nActionRequest request) {
        return i18nLearningService.deleteCourseDraft(courseId, idempotencyKey, request);
    }

    @PostMapping("/courses/{courseId}/publish")
    // 教程中心：课程发布
    @PreAuthorize("hasAuthority('content_i7_write') and hasAuthority('content_i7_course_reward_adjust')")
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
