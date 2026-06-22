package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsEmergencyControlServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsEmergencyControlService service = new OpsEmergencyControlService(configFacade, auditLogService);

    @Test
    void geoCountryChangePersistsConfigAndAudits() {
        var result = service.updateGeoCountry(
                "VE",
                "idem-j2",
                new GeoCountryStatusRequest("blocked", "regulatory request", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("emergency.geo.country.VE", "blocked")
                .containsEntry("emergency.geo.customCountries", "VE");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("J2_GEO_COUNTRY_STATUS_CHANGED");
    }

    @Test
    void tamperThresholdRejectsOutOfRangeValue() {
        var result = service.updateTamperAlertConfig(
                "idem-j3",
                new TamperAlertConfigRequest(101, true, "too sensitive", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void nonEmergencyPlaybookRejectsEmergencyExecution() {
        var result = service.executePlaybook(
                "SOP-07",
                "idem-j4",
                new SopPlaybookRunRequest(true, "incident", "tech-on-call"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void sopOverviewContainsPlaybooksAndExecutions() {
        var result = service.sopOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("playbooks").toString()).contains("SOP-01");
        assertThat(result.getData().get("executions").toString()).contains("SOP-06");
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }
}
