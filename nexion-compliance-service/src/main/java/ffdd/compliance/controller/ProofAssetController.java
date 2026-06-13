package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.common.exception.BizException;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.ProofAssetCreateRequest;
import ffdd.compliance.dto.ProofAssetReviewRequest;
import ffdd.compliance.service.ProofAssetService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compliance/proof-assets")
public class ProofAssetController {
    private final ProofAssetService proofAssetService;
    private final AuditLogService auditLogService;

    public ProofAssetController(ProofAssetService proofAssetService, AuditLogService auditLogService) {
        this.proofAssetService = proofAssetService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<List<ProofAsset>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String proofType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(proofAssetService.list(userId, proofType, status, limit));
    }

    @GetMapping("/app/mine")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<List<ProofAsset>> listMine(
            @RequestParam(required = false) String proofType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(proofAssetService.list(currentRoleUserId(), proofType, status, limit));
    }

    @GetMapping("/{proofNo}")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<ProofAsset> get(@PathVariable String proofNo) {
        return ApiResult.ok(proofAssetService.getByProofNo(proofNo));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> create(@Valid @RequestBody ProofAssetCreateRequest request) {
        ProofAsset proof = proofAssetService.create(request);
        auditProof("PROOF_ASSET_CREATE", proof);
        return ApiResult.ok(proof);
    }

    @PostMapping("/{proofNo}/verify")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> verify(
            @PathVariable String proofNo,
            @Valid @RequestBody ProofAssetReviewRequest request) {
        ProofAsset proof = proofAssetService.verify(proofNo, request);
        auditProof("PROOF_ASSET_VERIFY", proof);
        return ApiResult.ok(proof);
    }

    @PostMapping("/{proofNo}/reject")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> reject(
            @PathVariable String proofNo,
            @Valid @RequestBody ProofAssetReviewRequest request) {
        ProofAsset proof = proofAssetService.reject(proofNo, request);
        auditProof("PROOF_ASSET_REJECT", proof);
        return ApiResult.ok(proof);
    }

    @DeleteMapping("/{proofNo}")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> delete(@PathVariable String proofNo) {
        ProofAsset proof = proofAssetService.delete(proofNo);
        auditProof("PROOF_ASSET_ARCHIVE", proof);
        return ApiResult.ok(proof);
    }

    private void auditProof(String action, ProofAsset proof) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
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
                        "relatedBizNo", proof.getRelatedBizNo(),
                        "reviewedBy", proof.getReviewedBy(),
                        "reviewedAt", proof.getReviewedAt()))
                .build());
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

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            throw new BizException("Authenticated user is required");
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            throw new BizException("Authenticated user id is invalid");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }
}
