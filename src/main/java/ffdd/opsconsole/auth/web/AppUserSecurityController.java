package ffdd.opsconsole.auth.web;

import ffdd.opsconsole.auth.application.AppUserSecurityService;
import ffdd.opsconsole.auth.dto.AppPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AppAccountDeletionRequest;
import ffdd.opsconsole.auth.dto.AppAccountDeletionCancelRequest;
import ffdd.opsconsole.auth.dto.AppSecurityMutationResponse;
import ffdd.opsconsole.auth.dto.AppSecurityStateResponse;
import ffdd.opsconsole.auth.dto.AppTwoFactorUpdateRequest;
import ffdd.opsconsole.auth.dto.AppTwoFactorChallengeRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/app/security")
@RequiredArgsConstructor
public class AppUserSecurityController {
    private final AppUserSecurityService securityService;

    public ApiResult<AppSecurityStateResponse> overview(Authentication authentication) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.overview(context.userId(), context.sessionId()));
    }

    @GetMapping
    public ApiResult<AppSecurityStateResponse> overview(Authentication authentication,
            @RequestParam(required = false) String cursor) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.overview(context.userId(), context.sessionId(), cursor));
    }

    @PostMapping("/password")
    public ApiResult<AppSecurityMutationResponse> changePassword(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String commandKey,
            @RequestBody(required = false) AppPasswordChangeRequest request) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.changePassword(context.userId(), context.sessionId(), commandKey, request));
    }

    @GetMapping("/password/commands/{commandKey}")
    public ApiResult<AppSecurityMutationResponse> passwordCommandReceipt(
            Authentication authentication, @PathVariable String commandKey) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.passwordCommandReceipt(context.userId(), context.sessionId(), commandKey));
    }

    @PutMapping("/two-factor")
    public ApiResult<AppSecurityMutationResponse> updateTwoFactor(
            Authentication authentication,
            @RequestBody(required = false) AppTwoFactorUpdateRequest request) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.updateTwoFactor(context.userId(), request));
    }

    @PostMapping("/two-factor/challenge")
    public ApiResult<Map<String, Object>> sendTwoFactorChallenge(
            Authentication authentication,
            @RequestBody(required = false) AppTwoFactorChallengeRequest request) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.sendTwoFactorChallenge(context.userId(), request));
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    public ApiResult<AppSecurityMutationResponse> revokeSession(
            Authentication authentication,
            @PathVariable String sessionId) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.revokeSession(context.userId(), context.sessionId(), sessionId));
    }

    @PostMapping("/sessions/revoke-others")
    public ApiResult<AppSecurityMutationResponse> revokeOtherSessions(Authentication authentication) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.revokeOtherSessions(context.userId(), context.sessionId()));
    }

    @GetMapping("/account-deletion")
    public ApiResult<Map<String, Object>> accountDeletionStatus(Authentication authentication) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.accountDeletionStatus(context.userId(), context.sessionId()));
    }

    @PostMapping("/account-deletion")
    public ApiResult<Map<String, Object>> requestAccountDeletion(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) AppAccountDeletionRequest request) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.requestAccountDeletion(
                context.userId(), context.sessionId(), idempotencyKey, request));
    }

    @PostMapping("/account-deletion/cancel")
    public ApiResult<Map<String, Object>> cancelAccountDeletion(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) AppAccountDeletionCancelRequest request) {
        UserContext context = requireUser(authentication);
        return ApiResult.ok(securityService.cancelAccountDeletion(
                context.userId(), context.sessionId(), idempotencyKey, request));
    }

    private UserContext requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        Object sessionValue = details.get("sessionId");
        String sessionId = sessionValue == null ? "" : String.valueOf(sessionValue);
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        try {
            long userId = Long.parseLong(authentication.getName());
            if (userId <= 0) throw new NumberFormatException("non-positive");
            return new UserContext(userId, sessionId.trim());
        } catch (RuntimeException ex) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
    }

    private record UserContext(Long userId, String sessionId) {
    }
}
