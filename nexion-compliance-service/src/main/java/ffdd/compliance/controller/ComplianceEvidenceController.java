package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.EvidenceDownloadUrlResponse;
import ffdd.compliance.dto.EvidenceUploadPolicyRequest;
import ffdd.compliance.dto.EvidenceUploadPolicyResponse;
import ffdd.compliance.dto.KycDocumentUploadRequest;
import ffdd.compliance.dto.ProofAssetUploadRequest;
import ffdd.compliance.service.ComplianceEvidenceService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final AuditLogService auditLogService;

    public ComplianceEvidenceController(ComplianceEvidenceService evidenceService, AuditLogService auditLogService) {
        this.evidenceService = evidenceService;
        this.auditLogService = auditLogService;
    }

    @PostMapping(value = "/kyc-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> uploadKycDocument(
            @Valid @ModelAttribute KycDocumentUploadRequest request,
            @RequestPart("file") MultipartFile file) {
        KycProfile profile = evidenceService.uploadKycDocument(request, file);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("KYC_DOCUMENT_UPLOAD")
                .resourceType("KYC_PROFILE")
                .resourceId(profile.getKycNo())
                .bizNo(profile.getKycNo())
                .userId(profile.getUserId())
                .riskLevel("HIGH")
                .detail(detail(
                        "status", profile.getStatus(),
                        "country", profile.getCountry(),
                        "documentType", profile.getDocumentType(),
                        "contentType", file.getContentType(),
                        "sizeBytes", file.getSize()))
                .build());
        return ApiResult.ok(profile);
    }

    @PostMapping(value = "/proof-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> uploadProofAsset(
            @Valid @ModelAttribute ProofAssetUploadRequest request,
            @RequestPart("file") MultipartFile file) {
        ProofAsset proof = evidenceService.uploadProofAsset(request, file);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("PROOF_ASSET_UPLOAD")
                .resourceType("PROOF_ASSET")
                .resourceId(proof.getProofNo())
                .bizNo(proof.getProofNo())
                .userId(proof.getUserId())
                .riskLevel("HIGH")
                .detail(detail(
                        "proofType", proof.getProofType(),
                        "status", proof.getStatus(),
                        "contentType", proof.getContentType(),
                        "sizeBytes", proof.getSizeBytes(),
                        "relatedBizType", proof.getRelatedBizType(),
                        "relatedBizNo", proof.getRelatedBizNo()))
                .build());
        return ApiResult.ok(proof);
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

    private Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}
