package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.platform.dto.AdminAccountActionRequest;
import ffdd.opsconsole.platform.dto.AdminAccountCreateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountOverview;
import ffdd.opsconsole.platform.dto.AdminAccountProfileUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountRoleUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountSecurityBaselineUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminAccountStatusUpdateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacActionCreateRequest;
import ffdd.opsconsole.platform.dto.AdminRbacGrantUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsAdminAccountControllerTest {
    private final OpsAdminAccountService accountService = mock(OpsAdminAccountService.class);
    private final OpsAdminAccountController controller = new OpsAdminAccountController(accountService);

    @Test
    void overviewDelegatesToApplicationService() {
        AdminAccountOverview overview = new AdminAccountOverview(
                new AdminAccountOverview.AdminAccountStats(0, 0, 0, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(accountService.overview()).thenReturn(ApiResult.ok(overview));

        ApiResult<AdminAccountOverview> result = controller.overview();

        assertThat(result.getData()).isSameAs(overview);
        verify(accountService).overview();
    }

    @Test
    void accountMutationsPassIdempotencyHeader() {
        AdminAccountRoleUpdateRequest roleRequest =
                new AdminAccountRoleUpdateRequest("risk", "transfer", "superadmin");
        AdminAccountStatusUpdateRequest statusRequest =
                new AdminAccountStatusUpdateRequest("disabled", "offboard", "superadmin");
        AdminAccountActionRequest actionRequest = new AdminAccountActionRequest("identity verified", "superadmin");
        AdminAccountProfileUpdateRequest profileRequest =
                new AdminAccountProfileUpdateRequest("risk.shift", "风控值班", null, "rename", "superadmin");
        AdminAccountCreateRequest createRequest =
                new AdminAccountCreateRequest("王新", "wangxin@nexion.io", "risk", "mail", "join", "superadmin");

        controller.createAccount("idem-create", createRequest);
        controller.changeRole("idem-role", "op-001", roleRequest);
        controller.updateProfile("idem-profile", "op-001", profileRequest);
        controller.updateStatus("idem-status", "op-001", statusRequest);
        controller.reset2fa("idem-2fa", "op-001", actionRequest);
        controller.resetPassword("idem-password", "op-001", actionRequest);
        controller.revokeSessions("idem-session", "op-001", actionRequest);

        verify(accountService).createAccount("idem-create", createRequest);
        verify(accountService).changeRole("idem-role", "op-001", roleRequest);
        verify(accountService).updateProfile("idem-profile", "op-001", profileRequest);
        verify(accountService).updateStatus("idem-status", "op-001", statusRequest);
        verify(accountService).reset2fa("idem-2fa", "op-001", actionRequest);
        verify(accountService).resetPassword("idem-password", "op-001", actionRequest);
        verify(accountService).revokeSessions("idem-session", "op-001", actionRequest);
    }

    @Test
    void rbacAndSecurityMutationsDelegate() {
        AdminAccountSecurityBaselineUpdateRequest securityRequest =
                new AdminAccountSecurityBaselineUpdateRequest("45min / 10h", "tighten session", "superadmin");
        AdminRbacGrantUpdateRequest grantRequest =
                new AdminRbacGrantUpdateRequest(List.of("C", "C", "-", "-", "-", "M", "R"), "update grants", "superadmin");
        AdminRbacActionCreateRequest actionCreateRequest =
                new AdminRbacActionCreateRequest("批量补发收益(E3)", "增长/内容", "register", "superadmin");

        controller.updateSecurityBaseline("idem-sec", "session", securityRequest);
        controller.updateRbacGrants("idem-rbac", "balance_adjust", grantRequest);
        controller.registerRbacAction("idem-new-rbac", actionCreateRequest);

        verify(accountService).updateSecurityBaseline("idem-sec", "session", securityRequest);
        verify(accountService).updateRbacGrants("idem-rbac", "balance_adjust", grantRequest);
        verify(accountService).registerRbacAction("idem-new-rbac", actionCreateRequest);
    }
}
