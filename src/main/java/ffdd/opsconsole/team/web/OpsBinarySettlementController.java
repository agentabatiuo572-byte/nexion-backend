package ffdd.opsconsole.team.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.application.BinaryCommissionSettlementService;
import ffdd.opsconsole.team.application.BinaryCommissionSettlementService.AssignmentResult;
import ffdd.opsconsole.team.application.BinaryCommissionSettlementService.SettlementResult;
import ffdd.opsconsole.team.dto.BinaryLegAssignmentRequest;
import ffdd.opsconsole.team.dto.BinarySettlementRequest;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected, explicit writer entrypoints used by F3 operations and acceptance fixtures. */
@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/teams/binary")
@RequiredArgsConstructor
public class OpsBinarySettlementController {
    private final BinaryCommissionSettlementService service;

    @PostMapping("/assignments")
    @PreAuthorize("hasAuthority('network_f3_write')")
    public ApiResult<AssignmentResult> assign(
            @Valid @RequestBody BinaryLegAssignmentRequest request,
            Authentication authentication) {
        AdminActor actor = authenticatedAdmin(authentication);
        return ApiResult.ok(service.assignLeg(
                request.ownerUserId(), request.memberUserId(), request.leg(), actor.id(), actor.username()));
    }

    @PostMapping("/settlements")
    @PreAuthorize("hasAuthority('network_f3_write')")
    public ApiResult<SettlementResult> settle(
            @Valid @RequestBody BinarySettlementRequest request,
            Authentication authentication) {
        AdminActor actor = authenticatedAdmin(authentication);
        return ApiResult.ok(service.settleAsAdmin(
                request.ownerUserId(), request.settlementDate(), actor.id(), actor.username()));
    }

    private AdminActor authenticatedAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null) {
            throw new AccessDeniedException("AUTHENTICATED_ADMIN_REQUIRED");
        }
        Long adminId;
        try {
            adminId = Long.valueOf(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            throw new AccessDeniedException("AUTHENTICATED_ADMIN_REQUIRED", ex);
        }
        if (adminId <= 0 || !"ADMIN".equals(detail(authentication, "subjectType"))) {
            throw new AccessDeniedException("AUTHENTICATED_ADMIN_REQUIRED");
        }
        String username = detail(authentication, "username");
        return new AdminActor(adminId, StringUtils.hasText(username) ? username : "admin:" + adminId);
    }

    private String detail(Authentication authentication, String key) {
        if (!(authentication.getDetails() instanceof Map<?, ?> details)) return "";
        Object value = details.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record AdminActor(Long id, String username) { }
}
