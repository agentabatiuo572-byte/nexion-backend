package ffdd.opsconsole.media.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.media.application.OpsMediaUploadService;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.api.ApiResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/media")
@RequiredArgsConstructor
public class OpsMediaController {
    private final OpsMediaUploadService uploadService;

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<UploadedAsset> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String usage,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String operator) {
        return ApiResult.ok(uploadService.upload(file, idempotencyKey, domain, usage, entityType, entityId, operator));
    }

    @GetMapping("/uploads/{assetId}/preview-url")
    public ApiResult<UploadedAsset> previewUrl(@PathVariable String assetId) {
        return ApiResult.ok(uploadService.refreshPreviewUrl(assetId));
    }
}
