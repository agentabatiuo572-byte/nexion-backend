package ffdd.compute.controller;

import ffdd.common.api.ApiResult;
import ffdd.common.exception.BizException;
import ffdd.compute.dto.TrialClaimRequest;
import ffdd.compute.dto.TrialClaimResponse;
import ffdd.compute.service.TrialClaimService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/compute/trials")
public class TrialClaimController {
    private final TrialClaimService trialClaimService;

    public TrialClaimController(TrialClaimService trialClaimService) {
        this.trialClaimService = trialClaimService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<TrialClaimResponse> current() {
        return ApiResult.ok(trialClaimService.current(currentRoleUserId()));
    }

    @PostMapping("/claim")
    @PreAuthorize("hasAuthority('ROLE_USER')")
    public ApiResult<TrialClaimResponse> claim(@Valid @RequestBody(required = false) TrialClaimRequest request) {
        String clientRequestNo = request == null ? null : request.getClientRequestNo();
        return ApiResult.ok(trialClaimService.claim(currentRoleUserId(), clientRequestNo));
    }

    private Long currentRoleUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
            throw new BizException("Authenticated user is required");
        }
        String subject = String.valueOf(authentication.getPrincipal());
        if (!StringUtils.hasText(subject)) {
            throw new BizException("Authenticated user id is required");
        }
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ignored) {
            throw new BizException("Authenticated user id is invalid");
        }
    }
}
