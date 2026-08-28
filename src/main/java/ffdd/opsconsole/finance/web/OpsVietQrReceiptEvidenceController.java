package ffdd.opsconsole.finance.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.finance.application.VietQrReceiptEvidenceService;
import ffdd.opsconsole.media.dto.UploadedAsset;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/finance/vietqr")
@RequiredArgsConstructor
public class OpsVietQrReceiptEvidenceController {
    private final VietQrReceiptEvidenceService service;

    @PostMapping(value = "/receipt-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('finance_d1_bank_reconcile')")
    public ApiResult<UploadedAsset> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        return ApiResult.ok(service.upload(file, idempotencyKey));
    }
}
