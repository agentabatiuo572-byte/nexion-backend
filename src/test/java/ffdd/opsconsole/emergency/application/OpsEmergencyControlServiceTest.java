package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsEmergencyControlServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsEmergencyControlService service = new OpsEmergencyControlService(configFacade, auditLogService, new ObjectMapper());

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
        assertThat(result.getData().get("actionOptions").toString())
                .contains("熔断提现通道")
                .contains("发送通知模板")
                .contains("I4");
        assertThat(result.getData().get("rollbackOptions").toString())
                .contains("常规根因恢复")
                .contains("提现限流恢复");
        assertThat(configFacade.values)
                .containsKeys("emergency.sop.playbooks", "emergency.sop.executions", "emergency.sop.actionOptions", "emergency.sop.rollbackOptions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlaybookPersistsI3NotificationReference() {
        var result = service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现\nI3·通知法务",
                        "NC-CRITICAL-001",
                        "监管点名通知 · critical · 全体超管",
                        "根因消除后常规轨恢复",
                        true,
                        "wire I3 campaign",
                        "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values.get("emergency.sop.playbooks"))
                .contains("NC-CRITICAL-001")
                .contains("监管点名通知");
        List<Map<String, Object>> playbooks = (List<Map<String, Object>>) result.getData().get("playbooks");
        Map<String, Object> draft = playbooks.stream()
                .filter(row -> "监管点名快速止血".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(draft)
                .containsEntry("notifyCampaignNo", "NC-CRITICAL-001")
                .containsEntry("notifyTemplate", "监管点名通知 · critical · 全体超管")
                .containsEntry("draft", true);
    }

    @Test
    void geoAndTamperOverviewsSeedConfigWhenDatabaseIsEmpty() {
        var geo = service.geoBlockOverview();
        var tamper = service.tamperOverview();

        assertThat(geo.getCode()).isZero();
        assertThat(tamper.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("emergency.geo.country.KP", "blocked")
                .containsKeys("emergency.geo.hits", "emergency.geo.edge.metrics", "emergency.tamper.trend", "emergency.tamper.paths", "emergency.tamper.accounts");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperAccountsArePagedAndDoNotExposeUid() {
        var result = service.tamperOverview(2, 3);

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        Map<String, Object> accountPage = (Map<String, Object>) result.getData().get("accountPage");
        Map<String, Object> firstAccount = accounts.get(0);
        assertThat(accounts).hasSize(3);
        assertThat(accountPage)
                .containsEntry("page", 2)
                .containsEntry("pageSize", 3)
                .containsEntry("total", 8)
                .containsEntry("pages", 3);
        assertThat(firstAccount)
                .containsKey("userCode")
                .containsKey("userNo")
                .doesNotContainKey("uid");
        assertThat(String.valueOf(firstAccount.get("userCode"))).startsWith("U");
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedTamperUidIsConvertedToUserCode() {
        configFacade.values.put("emergency.tamper.accounts", "[{\"uid\":\"u-83271\",\"count\":24,\"k4\":\"+42\",\"last\":\"14:18:32\",\"paths\":[\"local-balance\"],\"cluster\":\"CL-318\"}]");

        var result = service.tamperOverview(1, 5);

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        assertThat(accounts.get(0))
                .containsEntry("userCode", "U00083271")
                .doesNotContainKey("uid");
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
