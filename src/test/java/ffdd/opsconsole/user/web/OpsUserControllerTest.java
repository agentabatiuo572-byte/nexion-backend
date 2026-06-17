package ffdd.opsconsole.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsUserControllerTest {
    private final OpsUserService userService = mock(OpsUserService.class);
    private final OpsUserController controller = new OpsUserController(userService);

    @Test
    void overviewDelegatesToService() {
        when(userService.overview()).thenReturn(ApiResult.ok(Map.of("domain", "C")));

        ApiResult<Map<String, Object>> result = controller.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("domain", "C");
    }

    @Test
    void statusEndpointPassesIdempotencyKey() {
        UserStatusUpdateRequest request = new UserStatusUpdateRequest("FROZEN", "risk hold", "superadmin");
        controller.updateStatus(1L, "idem-c1", request);

        verify(userService).updateStatus(eq(1L), eq("idem-c1"), any(UserStatusUpdateRequest.class));
        assertThat(OpsAdminApi.ADMIN_PREFIX + "/users/profiles/{userId}/status")
                .startsWith("/api/admin/users");
    }
}
