package ffdd.opsconsole.content.web;

import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.exception.GlobalExceptionHandler;
import ffdd.opsconsole.user.application.OpsUser360Service;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SupportWorkbenchFullProfileControllerTest {
    private static final String PATH = "/api/admin/content/support-workbench/customers/{customerId}/full-profile";
    private final ProductionSupportPathGuard guard = mock(ProductionSupportPathGuard.class);
    private final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    private final OpsUser360Service user360 = mock(OpsUser360Service.class);
    private final SupportWorkbenchController controller = new SupportWorkbenchController(
            mock(SupportWorkbenchService.class), mock(SupportMaintenanceService.class), guard,
            mock(SupportCustomerProfileService.class), mock(SupportAnalyticsPrivateQueryService.class), ownership, user360);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(mock(AuditLogService.class))).build();

    @Test
    void routeKeepsWorkbenchReadAuthorities() throws Exception {
        var method = SupportWorkbenchController.class.getMethod("fullProfile", Long.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/customers/{customerId}/full-profile");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyAuthority('service_m1_read','service_m3_read')");
    }

    @Test
    void guardRejectsBeforeOwnershipOrAggregation() throws Exception {
        doThrow(new BizException(503, "SUPPORT_PATH_UNAVAILABLE")).when(guard).requireOpsWriteAllowed();
        mvc.perform(get(PATH, 52)).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(ownership, user360);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, 9007199254740992L, Long.MAX_VALUE})
    void unsafeIdsRejectBeforeOwnershipOrAggregation(Long customerId) {
        assertThatThrownBy(() -> controller.fullProfile(customerId))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_ID_INVALID");
        verify(guard).requireOpsWriteAllowed();
        verifyNoInteractions(ownership, user360);
    }

    @Test
    void invalidIdUsesTrueHttp422AndMalformedIdUses400() throws Exception {
        mvc.perform(get(PATH, 0)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("SUPPORT_ID_INVALID"));
        mvc.perform(get(PATH, "customer-other")).andExpect(status().isBadRequest());
        verifyNoInteractions(ownership, user360);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void deniedScopeKeepsOriginalStatusAndNeverCallsAggregation(int code) throws Exception {
        doThrow(new BizException(code, "SUPPORT_CUSTOMER_NOT_FOUND")).when(ownership).requireRead(52L);
        mvc.perform(get(PATH, 52)).andExpect(status().is(code))
                .andExpect(jsonPath("$.code").value(code));
        var ordered = inOrder(guard, ownership);
        ordered.verify(guard).requireOpsWriteAllowed();
        ordered.verify(ownership).requireRead(52L);
        verifyNoInteractions(user360);
    }

    @Test
    void successfulDelegationPreservesTheOriginalRoleProjection() throws Exception {
        var result = ApiResult.<Map<String, Object>>ok(Map.of("profile", Map.of("userNo", "U00000052"),
                "devices", Map.of("sourceStatus", "READY")));
        when(user360.detail(52L)).thenReturn(result);
        assertThat(controller.fullProfile(52L)).isSameAs(result);
        var ordered = inOrder(guard, ownership, user360);
        ordered.verify(guard).requireOpsWriteAllowed();
        ordered.verify(ownership).requireRead(52L);
        ordered.verify(user360).detail(52L);
        mvc.perform(get(PATH, 52)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.userNo").value("U00000052"))
                .andExpect(jsonPath("$.data.devices.sourceStatus").value("READY"))
                .andExpect(jsonPath("$.data.sessions").doesNotExist())
                .andExpect(jsonPath("$.data.audit").doesNotExist());
    }

    @Test
    void serviceFailuresAreNotWrappedAsSuccessOrSwallowed() throws Exception {
        var result = ApiResult.<Map<String, Object>>fail(404, "USER_NOT_FOUND");
        when(user360.detail(52L)).thenReturn(result);
        assertThat(controller.fullProfile(52L)).isSameAs(result);
        doThrow(new BizException(503, "PROFILE_AUDIT_UNAVAILABLE")).when(user360).detail(52L);
        mvc.perform(get(PATH, 52)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("PROFILE_AUDIT_UNAVAILABLE"));
    }
}
