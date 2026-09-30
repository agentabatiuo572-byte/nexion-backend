package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.onboarding.web.PhoneCalibrationAdminController;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminRbacPhoneCalibrationTest {
    private static final String ROOT = "/api/admin/config/phone-calibration";
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminAccountStateMapper accounts = mock(AdminAccountStateMapper.class);
    private final AdminRbacAuthorizationFilter filter = new AdminRbacAuthorizationFilter(audit, accounts);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsRequireExactlyE2OrE6Read() throws Exception {
        for (String authority : List.of("device_e2_read", "device_e6_read")) {
            authenticate(authority);
            assertRequest("GET", ROOT, 200, null);
        }
        for (String authority : List.of("device_e1_read", "device_e2_write", "device_e6_write", "ROLE_SUPER_ADMIN")) {
            authenticate(authority);
            assertRequest("GET", ROOT, 403, "ADMIN_PERMISSION_DENIED");
        }
        authenticate();
        assertRequest("GET", ROOT, 403, "ADMIN_PERMISSION_DENIED");
    }

    @Test
    void previewIsReadOnlyButRequiresExactlyE6Read() throws Exception {
        authenticate("device_e6_read");
        assertRequest("POST", ROOT + "/preview", 200, null);
        for (String authority : List.of("device_e2_read", "device_e1_read", "device_e6_write", "device_e2_write", "ROLE_SUPER_ADMIN")) {
            authenticate(authority);
            assertRequest("POST", ROOT + "/preview", 403, "ADMIN_PERMISSION_DENIED");
        }
        authenticate();
        assertRequest("POST", ROOT + "/preview", 403, "ADMIN_PERMISSION_DENIED");
    }

    @Test
    void missingAuthenticationAndRequiredPasswordChangeStillBlockBothEndpointsThenRecover() throws Exception {
        assertRequest("GET", ROOT, 401, "ADMIN_AUTH_REQUIRED");
        assertRequest("POST", ROOT + "/preview", 401, "ADMIN_AUTH_REQUIRED");
        authenticate("device_e6_read");
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setCredentialDeliveryStatus("PASSWORD_CHANGE_REQUIRED");
        when(accounts.selectActiveByAdminId(17L)).thenReturn(state);
        assertRequest("GET", ROOT, 403, "ADMIN_PASSWORD_CHANGE_REQUIRED");
        assertRequest("POST", ROOT + "/preview", 403, "ADMIN_PASSWORD_CHANGE_REQUIRED");
        state.setCredentialDeliveryStatus("ACTIVE");
        assertRequest("GET", ROOT, 200, null);
        assertRequest("POST", ROOT + "/preview", 200, null);
    }

    @Test
    void publicationWrongMethodsAndSiblingPathsNeverAcquireAnRbacRule() throws Exception {
        authenticate("device_e6_read", "device_e2_read", "device_e6_write", "device_e2_write", "ROLE_SUPER_ADMIN");
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            assertRequest(method, ROOT, 403, "ADMIN_RBAC_RULE_MISSING");
        }
        for (String method : List.of("GET", "PUT", "PATCH", "DELETE", "HEAD")) {
            assertRequest(method, ROOT + "/preview", 403, "ADMIN_RBAC_RULE_MISSING");
        }
        for (String path : List.of(ROOT + "/publish", ROOT + "/preview/extra", ROOT + "/", ROOT + "-other")) {
            assertRequest("GET", path, 403, "ADMIN_RBAC_RULE_MISSING");
            assertRequest("POST", path, 403, "ADMIN_RBAC_RULE_MISSING");
        }
    }

    @Test
    void rejectionRetainsTheExactRequiredAuthorityInAudit() throws Exception {
        authenticate("device_e2_read");
        assertRequest("POST", ROOT + "/preview", 403, "ADMIN_PERMISSION_DENIED");
        verify(audit).record(argThat(event -> "ADMIN_PERMISSION_DENIED".equals(((Map<?, ?>) event.getDetail()).get("reason"))
                && "device_e6_read".equals(((Map<?, ?>) event.getDetail()).get("requiredAuthority"))));
    }

    @Test
    void controllerKeepsTheSameExactPermissionsAndExposesNoPublicationMethod() throws Exception {
        assertThat(PhoneCalibrationAdminController.class.getDeclaredMethod("read")
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyAuthority('device_e6_read','device_e2_read')");
        assertThat(PhoneCalibrationAdminController.class.getDeclaredMethod("preview", PhoneCalibrationAdminController.Preview.class)
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('device_e6_read')");
        assertThat(Arrays.stream(PhoneCalibrationAdminController.class.getDeclaredMethods()).map(method -> method.getName()))
                .containsExactlyInAnyOrder("read", "preview");
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("17", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private void assertRequest(String method, String path, int status, String message) throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, (request, result) -> invoked.set(true));
        assertThat(invoked.get()).as(method + " " + path).isEqualTo(status == 200);
        assertThat(response.getStatus()).isEqualTo(status);
        if (message != null) assertThat(response.getContentAsString()).contains(message);
    }
}
