package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.EvidenceDownloadUrlResponse;
import ffdd.compliance.dto.EvidenceUploadPolicyRequest;
import ffdd.compliance.dto.EvidenceUploadPolicyResponse;
import ffdd.compliance.dto.KycDocumentUploadRequest;
import ffdd.compliance.dto.ProofAssetUploadRequest;
import ffdd.compliance.service.ComplianceEvidenceService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/compliance/evidence")
public class ComplianceEvidenceController {
    private final ComplianceEvidenceService evidenceService;

    public ComplianceEvidenceController(ComplianceEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @PostMapping(value = "/kyc-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> uploadKycDocument(
            @Valid @ModelAttribute KycDocumentUploadRequest request,
            @RequestPart("file") MultipartFile file) {
        return ApiResult.ok(evidenceService.uploadKycDocument(request, file));
    }

    @PostMapping(value = "/proof-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> uploadProofAsset(
            @Valid @ModelAttribute ProofAssetUploadRequest request,
            @RequestPart("file") MultipartFile file) {
        return ApiResult.ok(evidenceService.uploadProofAsset(request, file));
    }

    @PostMapping("/upload-policies")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<EvidenceUploadPolicyResponse> createUploadPolicy(
            @Valid @RequestBody EvidenceUploadPolicyRequest request) {
        return ApiResult.ok(evidenceService.createUploadPolicy(request));
    }

    @GetMapping("/download-url")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<EvidenceDownloadUrlResponse> createDownloadUrl(
            @RequestParam String objectKey,
            @RequestParam(defaultValue = "900") int expiresInSeconds) {
        return ApiResult.ok(evidenceService.createDownloadUrl(objectKey, expiresInSeconds));
    }
}
