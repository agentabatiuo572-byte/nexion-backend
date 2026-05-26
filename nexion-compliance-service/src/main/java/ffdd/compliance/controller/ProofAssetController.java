package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.domain.ProofAsset;
import ffdd.compliance.dto.ProofAssetCreateRequest;
import ffdd.compliance.dto.ProofAssetReviewRequest;
import ffdd.compliance.service.ProofAssetService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public ProofAssetController(ProofAssetService proofAssetService) {
        this.proofAssetService = proofAssetService;
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

    @GetMapping("/{proofNo}")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<ProofAsset> get(@PathVariable String proofNo) {
        return ApiResult.ok(proofAssetService.getByProofNo(proofNo));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> create(@Valid @RequestBody ProofAssetCreateRequest request) {
        return ApiResult.ok(proofAssetService.create(request));
    }

    @PostMapping("/{proofNo}/verify")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> verify(
            @PathVariable String proofNo,
            @Valid @RequestBody ProofAssetReviewRequest request) {
        return ApiResult.ok(proofAssetService.verify(proofNo, request));
    }

    @PostMapping("/{proofNo}/reject")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> reject(
            @PathVariable String proofNo,
            @Valid @RequestBody ProofAssetReviewRequest request) {
        return ApiResult.ok(proofAssetService.reject(proofNo, request));
    }

    @DeleteMapping("/{proofNo}")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<ProofAsset> delete(@PathVariable String proofNo) {
        return ApiResult.ok(proofAssetService.delete(proofNo));
    }
}
