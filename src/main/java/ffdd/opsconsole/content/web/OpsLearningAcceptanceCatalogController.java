package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.LearningAcceptanceSandboxCatalogService;
import ffdd.opsconsole.content.domain.LearningSandboxCourseRow;
import ffdd.opsconsole.content.dto.LearningCourseUpsertRequest;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative CRUD for isolated, run-scoped acceptance course definitions. */
@RestController
@Profile({"dev", "test"})
@RequestMapping("/api/admin/content/learning-acceptance/catalog")
@RequiredArgsConstructor
public class OpsLearningAcceptanceCatalogController {
    private final LearningAcceptanceSandboxCatalogService service;

    @GetMapping
    @PreAuthorize("hasAuthority('content_i7_read')")
    public ApiResult<List<LearningSandboxCourseRow>> list(@RequestParam String runId) { return service.list(runId); }

    @GetMapping("/command-result")
    @PreAuthorize("hasAuthority('content_i7_read')")
    public ApiResult<java.util.Map<String,Object>> commandResult(@RequestParam String runId, @RequestParam String commandScope, @RequestParam String idempotencyKey) {
        return service.commandResult(runId, commandScope, idempotencyKey);
    }

    @PostMapping("/{courseId}")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<LearningSandboxCourseRow> saveDraft(@RequestParam String runId, @PathVariable String courseId,
                                                          @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
                                                          @RequestBody LearningCourseUpsertRequest request) {
        return service.saveDraft(runId, courseId, request, idempotencyKey);
    }

    @PostMapping("/{courseId}/versions/{version}/publish")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<Void> publish(@RequestParam String runId, @RequestParam long expectedRevision, @PathVariable String courseId, @PathVariable String version,
                                   @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return service.publish(runId, courseId, version, expectedRevision, idempotencyKey);
    }

    @DeleteMapping("/{courseId}/versions/{version}")
    @PreAuthorize("hasAuthority('content_i7_write')")
    public ApiResult<Void> deleteDraft(@RequestParam String runId, @RequestParam long expectedRevision, @PathVariable String courseId, @PathVariable String version,
                                       @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return service.deleteDraft(runId, courseId, version, expectedRevision, idempotencyKey);
    }
}
