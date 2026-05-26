package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.dto.KycProfileReviewRequest;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.service.KycProfileService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compliance/kyc-profiles")
public class KycProfileController {
    private final KycProfileService kycProfileService;

    public KycProfileController(KycProfileService kycProfileService) {
        this.kycProfileService = kycProfileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<List<KycProfile>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(kycProfileService.list(userId, status, limit));
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_READ')")
    public ApiResult<KycProfile> getByUserId(@PathVariable Long userId) {
        return ApiResult.ok(kycProfileService.getByUserId(userId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> submit(@Valid @RequestBody KycProfileSubmitRequest request) {
        return ApiResult.ok(kycProfileService.submit(request));
    }

    @PostMapping("/{userId}/approve")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> approve(
            @PathVariable Long userId,
            @Valid @RequestBody KycProfileReviewRequest request) {
        return ApiResult.ok(kycProfileService.approve(userId, request));
    }

    @PostMapping("/{userId}/reject")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> reject(
            @PathVariable Long userId,
            @Valid @RequestBody KycProfileReviewRequest request) {
        return ApiResult.ok(kycProfileService.reject(userId, request));
    }

    @PostMapping("/{userId}/expire")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> expire(
            @PathVariable Long userId,
            @Valid @RequestBody KycProfileReviewRequest request) {
        return ApiResult.ok(kycProfileService.expire(userId, request));
    }
}
