package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.TrustDocumentService;
import ffdd.opsconsole.content.domain.TrustDocumentsView;
import ffdd.opsconsole.content.dto.TrustDocumentRestoreRequest;
import ffdd.opsconsole.content.dto.TrustDocumentUploadRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/trust-documents")
@RequiredArgsConstructor
public class OpsTrustDocumentController {
    private final TrustDocumentService service;

    @GetMapping
    @PreAuthorize("hasAuthority('content_i4_read')")
    public ApiResult<TrustDocumentsView> overview() {
        return ApiResult.ok(service.console());
    }

    @GetMapping("/{slot}/file")
    @PreAuthorize("hasAuthority('content_i4_read')")
    public ResponseEntity<byte[]> file(@PathVariable String slot) {
        return AppTrustDocumentController.pdf(service.file(slot), CacheControl.noStore());
    }

    // HIGH：替换 App 信任中心展示的资料文件
    @PostMapping(value = "/{slot}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('content_i4_publish_standard','content_i4_trust_section_manage')")
    public ApiResult<TrustDocumentsView.Document> upload(
            @PathVariable String slot,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String referenceNo,
            @RequestParam(required = false) String verifyUrl,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String expectedSha256,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return ApiResult.ok(service.upload(slot, file,
                new TrustDocumentUploadRequest(version, language, referenceNo, verifyUrl, reason, expectedSha256),
                idempotencyKey, AdminActorResolver.resolve(null)));
    }

    // HIGH：恢复随系统发布的默认文件
    @PostMapping("/{slot}/restore-default")
    @PreAuthorize("hasAnyAuthority('content_i4_publish_standard','content_i4_trust_section_manage')")
    public ApiResult<TrustDocumentsView.Document> restoreDefault(
            @PathVariable String slot,
            @RequestBody TrustDocumentRestoreRequest request,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return ApiResult.ok(service.restoreDefault(slot, request, idempotencyKey, AdminActorResolver.resolve(null)));
    }
}
