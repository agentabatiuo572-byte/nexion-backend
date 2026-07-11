package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsCopyAbService;
import ffdd.opsconsole.content.domain.CopyAbOverview;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.domain.CopyPositionView;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyCreateRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyPositionCreateRequest;
import ffdd.opsconsole.content.dto.CopyPositionUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/copy-ab")
@RequiredArgsConstructor
public class OpsCopyAbController {
    private final OpsCopyAbService copyAbService;

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('content_i1_read')")
    public ApiResult<CopyAbOverview> overview() {
        return copyAbService.overview();
    }

    @PostMapping("/copies")
    @PreAuthorize("hasAuthority('content_i1_copy_create')")
    public ApiResult<CopyContentRow> createCopy(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyCreateRequest request) {
        return copyAbService.createCopy(idempotencyKey, request);
    }

    @GetMapping("/positions")
    @PreAuthorize("hasAuthority('content_i1_read')")
    public ApiResult<List<CopyPositionView>> listPositions() {
        return copyAbService.listPositions();
    }

    @PostMapping("/positions")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyPositionView> createPosition(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyPositionCreateRequest request) {
        return copyAbService.createPosition(idempotencyKey, request);
    }

    @PatchMapping("/positions/{positionKey}")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyPositionView> updatePosition(
            @PathVariable String positionKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyPositionUpdateRequest request) {
        return copyAbService.updatePosition(positionKey, idempotencyKey, request);
    }

    @DeleteMapping("/positions/{positionKey}")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<Void> deletePosition(
            @PathVariable String positionKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.deletePosition(positionKey, idempotencyKey, request);
    }

    @PatchMapping("/copies/{copyKey}/draft")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyContentRow> saveDraft(
            @PathVariable String copyKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyDraftSaveRequest request) {
        return copyAbService.saveDraft(copyKey, idempotencyKey, request);
    }

    @PostMapping("/copies/{copyKey}/versions")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyContentRow> publishVersion(
            @PathVariable String copyKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyVersionPublishRequest request) {
        return copyAbService.publishVersion(copyKey, idempotencyKey, request);
    }

    @PostMapping("/copies/{copyKey}/versions/{version}/rollback")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyContentRow> rollbackVersion(
            @PathVariable String copyKey,
            @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.rollbackVersion(copyKey, version, idempotencyKey, request);
    }

    @PostMapping("/copies/{copyKey}/archive")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyContentRow> archiveCurrent(
            @PathVariable String copyKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.archiveCurrent(copyKey, idempotencyKey, request);
    }

    @PatchMapping("/framework/{paramKey}")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyFrameworkParamView> updateFrameworkParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyFrameworkUpdateRequest request) {
        return copyAbService.updateFrameworkParam(paramKey, idempotencyKey, request);
    }

    @PostMapping("/experiments/{experimentId}/stop")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyExperimentRow> stopExperiment(
            @PathVariable String experimentId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.stopExperiment(experimentId, idempotencyKey, request);
    }

    @PostMapping("/experiments/{experimentId}/adopt")
    @PreAuthorize("hasAuthority('content_i1_write')")
    public ApiResult<CopyExperimentRow> adoptExperiment(
            @PathVariable String experimentId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.adoptExperiment(experimentId, idempotencyKey, request);
    }
}
