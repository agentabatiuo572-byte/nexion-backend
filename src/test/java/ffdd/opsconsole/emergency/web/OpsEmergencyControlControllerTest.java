package ffdd.opsconsole.emergency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.emergency.application.OpsEmergencyControlService;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OpsEmergencyControlControllerTest {
    private final OpsEmergencyControlService service = mock(OpsEmergencyControlService.class);
    private final OpsEmergencyControlController controller = new OpsEmergencyControlController(service);

    @Test
    void geoCountryDelegatesWithIdempotencyHeader() {
        GeoCountryStatusRequest request = new GeoCountryStatusRequest("blocked", "regulatory request", "risk-lead");
        when(service.updateGeoCountry("VE", "idem-j2", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateGeoCountry("VE", "idem-j2", request).getData()).containsEntry("ok", true);

        verify(service).updateGeoCountry("VE", "idem-j2", request);
    }

    @Test
    void geoAlertFeedRequiresReadPermissionAndAuthoritativeSuperAdminGuard() throws Exception {
        Method method = OpsEmergencyControlController.class.getMethod("geoBlockAlerts");

        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('emergency_j2_read') and @superAdminAuthorization.isSuperAdmin(authentication)");
    }

    @Test
    void sopExecuteDelegatesWithIdempotencyHeader() {
        SopPlaybookRunRequest request = new SopPlaybookRunRequest(true, "incident", "risk-lead");
        when(service.executePlaybook("SOP-03", "idem-j4", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.executePlaybook("SOP-03", "idem-j4", request).getCode()).isZero();

        verify(service).executePlaybook("SOP-03", "idem-j4", request);
    }
}
