package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** H1 phase-dial approval is a funding-amplification gate: only another effective super admin may approve. */
@Component
@RequiredArgsConstructor
public class H1GrowthApproval {
    public static final String OP = "h1_phase_dial";
    private final SuperAdminAuthorization superAdmins;

    public static boolean supports(AuditReplayCommand command) {
        return command != null && "H".equals(command.domain()) && OP.equals(command.op());
    }

    /** Non-amplifying direct writes and rejections remain on their existing paths; this is the approval gate only. */
    public ApiResult<Void> validateChecker(AuditReplayCommand command, Long checkerId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!supports(command) || checkerId == null || checkerId <= 0
                || authentication == null
                || !String.valueOf(checkerId).equals(authentication.getName())
                || !superAdmins.isSuperAdmin(authentication)
                || !hasAuthority(authentication, "platform_a2_operation_approve")) {
            return ApiResult.fail(403, "H1_APPROVAL_FORBIDDEN");
        }
        return ApiResult.ok();
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
