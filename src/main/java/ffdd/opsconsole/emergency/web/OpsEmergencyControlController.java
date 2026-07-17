package ffdd.opsconsole.emergency.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.emergency.application.OpsEmergencyControlService;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.GeoCountryListRequest;
import ffdd.opsconsole.emergency.dto.GeoEdgeJudgeRequest;
import ffdd.opsconsole.emergency.dto.GeoEmergencyBlockRequest;
import ffdd.opsconsole.emergency.dto.GeoEndpointCountriesRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookUpdateRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.emergency.dto.TamperReportRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({OpsAdminApi.ADMIN_PREFIX + "/emergency", OpsAdminApi.ADMIN_PREFIX + "/emergency-control"})
@RequiredArgsConstructor
public class OpsEmergencyControlController {
    private final OpsEmergencyControlService emergencyControlService;

    @GetMapping("/geo-block")
    @PreAuthorize("hasAuthority('emergency_j2_read')")
    public ApiResult<Map<String, Object>> geoBlockOverview() {
        return emergencyControlService.geoBlockOverview();
    }

    @GetMapping("/geo-block/alerts")
    @PreAuthorize("hasAuthority('emergency_j2_read') and @superAdminAuthorization.isSuperAdmin(authentication)")
    public ApiResult<Map<String, Object>> geoBlockAlerts() {
        return emergencyControlService.geoBlockAlerts();
    }

    @PutMapping("/geo-block/countries/{countryCode}")
    @PreAuthorize("hasAuthority('emergency_j2_country_manage')")
    public ApiResult<Map<String, Object>> updateGeoCountry(
            @PathVariable String countryCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoCountryStatusRequest request) {
        return emergencyControlService.updateGeoCountry(countryCode, idempotencyKey, request);
    }

    @PutMapping("/geo-block/country-lists/{status}")
    @PreAuthorize("hasAuthority('emergency_j2_country_manage')")
    public ApiResult<Map<String, Object>> replaceGeoCountryList(
            @PathVariable String status,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoCountryListRequest request) {
        return emergencyControlService.replaceGeoCountryList(status, idempotencyKey, request);
    }

    @PutMapping("/geo-block/endpoints/{endpointKey}")
    @PreAuthorize("hasAuthority('emergency_j2_write')")
    public ApiResult<Map<String, Object>> updateGeoEndpoint(
            @PathVariable String endpointKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoEndpointCountriesRequest request) {
        return emergencyControlService.updateGeoEndpoint(endpointKey, idempotencyKey, request);
    }

    @PutMapping("/geo-block/edge-judge")
    @PreAuthorize("hasAuthority('emergency_j2_edge_source_manage')")
    public ApiResult<Map<String, Object>> updateGeoEdgeJudge(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoEdgeJudgeRequest request) {
        return emergencyControlService.updateGeoEdgeJudge(idempotencyKey, request);
    }

    @PostMapping("/geo-block/emergency-blocks")
    @PreAuthorize("hasAuthority('emergency_j2_emergency_block')")
    public ApiResult<Map<String, Object>> emergencyGeoBlock(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoEmergencyBlockRequest request) {
        return emergencyControlService.emergencyGeoBlock(idempotencyKey, request);
    }

    @GetMapping("/tamper/overview")
    @PreAuthorize("hasAuthority('emergency_j3_read')")
    public ApiResult<Map<String, Object>> tamperOverview(
            @RequestParam(value = "window", defaultValue = "24h") String window,
            @RequestParam(value = "accountPage", defaultValue = "1") int accountPage,
            @RequestParam(value = "accountPageSize", defaultValue = "5") int accountPageSize) {
        return emergencyControlService.tamperOverview(window, accountPage, accountPageSize);
    }

    @GetMapping("/tamper/config-alerts")
    @PreAuthorize("hasAuthority('emergency_j3_alert_config')")
    public ApiResult<Map<String, Object>> tamperConfigAlerts() {
        return emergencyControlService.tamperConfigAlerts();
    }

    @PutMapping("/tamper/alert-config")
    @PreAuthorize("hasAuthority('emergency_j3_alert_config')")
    public ApiResult<Map<String, Object>> updateTamperAlertConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TamperAlertConfigRequest request) {
        return emergencyControlService.updateTamperAlertConfig(idempotencyKey, request);
    }

    @PostMapping("/tamper/reports")
    @PreAuthorize("hasAuthority('emergency_j3_export')")
    public ApiResult<Map<String, Object>> createTamperReport(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TamperReportRequest request) {
        return emergencyControlService.createTamperReport(idempotencyKey, request);
    }

    @GetMapping("/sop/playbooks")
    @PreAuthorize("hasAuthority('emergency_j4_read')")
    public ApiResult<Map<String, Object>> sopOverview() {
        return emergencyControlService.sopOverview();
    }

    @PostMapping("/sop/playbooks")
    @PreAuthorize("hasAuthority('emergency_j4_write')")
    public ApiResult<Map<String, Object>> createPlaybook(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookCreateRequest request) {
        return emergencyControlService.createPlaybook(idempotencyKey, request);
    }

    @PutMapping("/sop/playbooks/{code}")
    @PreAuthorize("hasAuthority('emergency_j4_write')")
    public ApiResult<Map<String, Object>> updatePlaybook(
            @PathVariable String code,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookUpdateRequest request) {
        return emergencyControlService.updatePlaybook(code, idempotencyKey, request);
    }

    @PostMapping("/sop/playbooks/{code}/drills")
    @PreAuthorize("hasAuthority('emergency_j4_write')")
    public ApiResult<Map<String, Object>> drillPlaybook(
            @PathVariable String code,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookRunRequest request) {
        return emergencyControlService.drillPlaybook(code, idempotencyKey, request);
    }

    @PostMapping("/sop/playbooks/{code}/executions")
    @PreAuthorize("hasAuthority('emergency_j4_playbook_execute')")
    public ApiResult<Map<String, Object>> executePlaybook(
            @PathVariable String code,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookRunRequest request) {
        return emergencyControlService.executePlaybook(code, idempotencyKey, request);
    }

    @PostMapping("/sop/playbooks/{code}/executions/{executionId}/rollback")
    @PreAuthorize("hasAuthority('emergency_j4_playbook_execute') and hasAuthority('emergency_j1_gate_resume')")
    public ApiResult<Map<String, Object>> rollbackPlaybookExecution(
            @PathVariable String code,
            @PathVariable String executionId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookRunRequest request) {
        return emergencyControlService.rollbackPlaybookExecution(code, executionId, idempotencyKey, request);
    }
}
