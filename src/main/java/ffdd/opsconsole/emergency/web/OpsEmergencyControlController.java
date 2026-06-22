package ffdd.opsconsole.emergency.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.emergency.application.OpsEmergencyControlService;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/emergency")
@RequiredArgsConstructor
public class OpsEmergencyControlController {
    private final OpsEmergencyControlService emergencyControlService;

    @GetMapping("/geo-block")
    public ApiResult<Map<String, Object>> geoBlockOverview() {
        return emergencyControlService.geoBlockOverview();
    }

    @PutMapping("/geo-block/countries/{countryCode}")
    public ApiResult<Map<String, Object>> updateGeoCountry(
            @PathVariable String countryCode,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoCountryStatusRequest request) {
        return emergencyControlService.updateGeoCountry(countryCode, idempotencyKey, request);
    }

    @PutMapping("/geo-block/endpoints/{endpointKey}")
    public ApiResult<Map<String, Object>> updateGeoEndpoint(
            @PathVariable String endpointKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoEndpointCountriesRequest request) {
        return emergencyControlService.updateGeoEndpoint(endpointKey, idempotencyKey, request);
    }

    @PutMapping("/geo-block/edge-judge")
    public ApiResult<Map<String, Object>> updateGeoEdgeJudge(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoEdgeJudgeRequest request) {
        return emergencyControlService.updateGeoEdgeJudge(idempotencyKey, request);
    }

    @PostMapping("/geo-block/emergency-blocks")
    public ApiResult<Map<String, Object>> emergencyGeoBlock(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GeoEmergencyBlockRequest request) {
        return emergencyControlService.emergencyGeoBlock(idempotencyKey, request);
    }

    @GetMapping("/tamper/overview")
    public ApiResult<Map<String, Object>> tamperOverview() {
        return emergencyControlService.tamperOverview();
    }

    @PatchMapping("/tamper/alert-config")
    public ApiResult<Map<String, Object>> updateTamperAlertConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TamperAlertConfigRequest request) {
        return emergencyControlService.updateTamperAlertConfig(idempotencyKey, request);
    }

    @PostMapping("/tamper/reports")
    public ApiResult<Map<String, Object>> createTamperReport(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TamperReportRequest request) {
        return emergencyControlService.createTamperReport(idempotencyKey, request);
    }

    @GetMapping("/sop/playbooks")
    public ApiResult<Map<String, Object>> sopOverview() {
        return emergencyControlService.sopOverview();
    }

    @PostMapping("/sop/playbooks")
    public ApiResult<Map<String, Object>> createPlaybook(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookCreateRequest request) {
        return emergencyControlService.createPlaybook(idempotencyKey, request);
    }

    @PutMapping("/sop/playbooks/{code}")
    public ApiResult<Map<String, Object>> updatePlaybook(
            @PathVariable String code,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookUpdateRequest request) {
        return emergencyControlService.updatePlaybook(code, idempotencyKey, request);
    }

    @PostMapping("/sop/playbooks/{code}/drills")
    public ApiResult<Map<String, Object>> drillPlaybook(
            @PathVariable String code,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookRunRequest request) {
        return emergencyControlService.drillPlaybook(code, idempotencyKey, request);
    }

    @PostMapping("/sop/playbooks/{code}/executions")
    public ApiResult<Map<String, Object>> executePlaybook(
            @PathVariable String code,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody SopPlaybookRunRequest request) {
        return emergencyControlService.executePlaybook(code, idempotencyKey, request);
    }
}
