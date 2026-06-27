package ffdd.opsconsole.content.web;

import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.content.application.OpsSupportAgentService;
import ffdd.opsconsole.content.domain.SupportAgentAssignmentView;
import ffdd.opsconsole.content.domain.SupportAgentOverview;
import ffdd.opsconsole.content.domain.SupportAgentPageView;
import ffdd.opsconsole.content.domain.SupportAgentProfileView;
import ffdd.opsconsole.content.dto.SupportAgentAssignmentRequest;
import ffdd.opsconsole.content.dto.SupportAgentProfileUpdateRequest;
import ffdd.opsconsole.content.dto.SupportAgentQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/content/support-agents")
@RequiredArgsConstructor
public class OpsSupportAgentController {
    private final OpsSupportAgentService supportAgentService;

    @GetMapping
    public ApiResult<SupportAgentOverview> overview() {
        return supportAgentService.overview();
    }

    @GetMapping("/page")
    public ApiResult<SupportAgentPageView> agents(SupportAgentQueryRequest request) {
        return supportAgentService.agents(request);
    }

    @PatchMapping("/{adminId}/profile")
    public ApiResult<SupportAgentProfileView> updateProfile(
            @PathVariable Long adminId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportAgentProfileUpdateRequest request) {
        return supportAgentService.updateProfile(adminId, idempotencyKey, request);
    }

    @PostMapping("/{adminId}/assignments")
    public ApiResult<SupportAgentAssignmentView> assignAdvisorUser(
            @PathVariable Long adminId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportAgentAssignmentRequest request) {
        return supportAgentService.assignAdvisorUser(adminId, idempotencyKey, request);
    }

    @DeleteMapping("/{adminId}/assignments/{assignmentId}")
    public ApiResult<SupportAgentAssignmentView> deactivateAdvisorAssignment(
            @PathVariable Long adminId,
            @PathVariable Long assignmentId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SupportAgentAssignmentRequest request) {
        return supportAgentService.deactivateAdvisorAssignment(adminId, assignmentId, idempotencyKey, request);
    }
}
