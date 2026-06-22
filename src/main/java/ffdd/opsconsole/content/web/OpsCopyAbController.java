package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsCopyAbService;
import ffdd.opsconsole.content.domain.CopyAbOverview;
import ffdd.opsconsole.content.domain.CopyContentRow;
import ffdd.opsconsole.content.domain.CopyExperimentRow;
import ffdd.opsconsole.content.domain.CopyFrameworkParamView;
import ffdd.opsconsole.content.dto.CopyActionRequest;
import ffdd.opsconsole.content.dto.CopyDraftSaveRequest;
import ffdd.opsconsole.content.dto.CopyFrameworkUpdateRequest;
import ffdd.opsconsole.content.dto.CopyVersionPublishRequest;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/copy-ab")
@RequiredArgsConstructor
public class OpsCopyAbController {
    private final OpsCopyAbService copyAbService;

    @GetMapping("/overview")
    public ApiResult<CopyAbOverview> overview() {
        return copyAbService.overview();
    }

    @PatchMapping("/copies/{copyKey}/draft")
    public ApiResult<CopyContentRow> saveDraft(
            @PathVariable String copyKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyDraftSaveRequest request) {
        return copyAbService.saveDraft(copyKey, idempotencyKey, request);
    }

    @PostMapping("/copies/{copyKey}/versions")
    public ApiResult<CopyContentRow> publishVersion(
            @PathVariable String copyKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyVersionPublishRequest request) {
        return copyAbService.publishVersion(copyKey, idempotencyKey, request);
    }

    @PostMapping("/copies/{copyKey}/versions/{version}/rollback")
    public ApiResult<CopyContentRow> rollbackVersion(
            @PathVariable String copyKey,
            @PathVariable String version,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.rollbackVersion(copyKey, version, idempotencyKey, request);
    }

    @PostMapping("/copies/{copyKey}/archive")
    public ApiResult<CopyContentRow> archiveCurrent(
            @PathVariable String copyKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.archiveCurrent(copyKey, idempotencyKey, request);
    }

    @PatchMapping("/framework/{paramKey}")
    public ApiResult<CopyFrameworkParamView> updateFrameworkParam(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyFrameworkUpdateRequest request) {
        return copyAbService.updateFrameworkParam(paramKey, idempotencyKey, request);
    }

    @PostMapping("/experiments/{experimentId}/stop")
    public ApiResult<CopyExperimentRow> stopExperiment(
            @PathVariable String experimentId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.stopExperiment(experimentId, idempotencyKey, request);
    }

    @PostMapping("/experiments/{experimentId}/adopt")
    public ApiResult<CopyExperimentRow> adoptExperiment(
            @PathVariable String experimentId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody CopyActionRequest request) {
        return copyAbService.adoptExperiment(experimentId, idempotencyKey, request);
    }
}
