package ffdd.compliance.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditLogWriteRequest;
import ffdd.common.exception.BizException;
import ffdd.compliance.domain.KycProfile;
import ffdd.compliance.dto.KycExpiryResult;
import ffdd.compliance.dto.KycProfileReviewRequest;
import ffdd.compliance.dto.KycProfileSubmitRequest;
import ffdd.compliance.service.KycProfileService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
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
    private final AuditLogService auditLogService;

    public KycProfileController(KycProfileService kycProfileService, AuditLogService auditLogService) {
        this.kycProfileService = kycProfileService;
        this.auditLogService = auditLogService;
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

    @GetMapping("/app/me")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<KycProfile> getMyKycProfile() {
        return ApiResult.ok(kycProfileService.getByUserId(currentRoleUserId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> submit(@Valid @RequestBody KycProfileSubmitRequest request) {
        KycProfile profile = kycProfileService.submit(request);
        auditKyc("KYC_SUBMIT", profile);
        return ApiResult.ok(profile);
    }

    @PostMapping("/app/me")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<KycProfile> submitMyKycProfile(@RequestBody KycProfileSubmitRequest request) {
        request.setUserId(currentRoleUserId());
        KycProfile profile = kycProfileService.submit(request);
        auditKyc("KYC_APP_SUBMIT", profile);
        return ApiResult.ok(profile);
    }

    @PostMapping("/maintenance/expire-approved")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycExpiryResult> expireApproved(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "ops-kyc-expiry") String reviewer) {
        KycExpiryResult result = kycProfileService.expireApprovedProfiles(limit, reviewer);
        auditLogService.record(AuditLogWriteRequest.builder()
                .action("KYC_EXPIRE_APPROVED_BATCH")
                .resourceType("KYC_PROFILE")
                .riskLevel("HIGH")
                .detail(detail("limit", limit, "reviewer", reviewer, "expired", result.getExpired()))
                .build());
        return ApiResult.ok(result);
    }

    @PostMapping("/{userId}/approve")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> approve(
            @PathVariable Long userId,
            @Valid @RequestBody KycProfileReviewRequest request) {
        KycProfile profile = kycProfileService.approve(userId, request);
        auditKyc("KYC_APPROVE", profile);
        return ApiResult.ok(profile);
    }

    @PostMapping("/{userId}/reject")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> reject(
            @PathVariable Long userId,
            @Valid @RequestBody KycProfileReviewRequest request) {
        KycProfile profile = kycProfileService.reject(userId, request);
        auditKyc("KYC_REJECT", profile);
        return ApiResult.ok(profile);
    }

    @PostMapping("/{userId}/expire")
    @PreAuthorize("hasAuthority('PERM_COMPLIANCE_WRITE')")
    public ApiResult<KycProfile> expire(
            @PathVariable Long userId,
            @Valid @RequestBody KycProfileReviewRequest request) {
        KycProfile profile = kycProfileService.expire(userId, request);
        auditKyc("KYC_EXPIRE", profile);
        return ApiResult.ok(profile);
    }

    private void auditKyc(String action, KycProfile profile) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("KYC_PROFILE")
                .resourceId(profile.getKycNo())
                .bizNo(profile.getKycNo())
                .userId(profile.getUserId())
                .riskLevel("HIGH")
                .detail(detail(
                        "status", profile.getStatus(),
                        "country", profile.getCountry(),
                        "documentType", profile.getDocumentType(),
                        "reviewedBy", profile.getReviewedBy(),
                        "reviewedAt", profile.getReviewedAt(),
                        "expiresAt", profile.getExpiresAt()))
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
