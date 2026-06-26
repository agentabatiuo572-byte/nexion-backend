package ffdd.opsconsole.emergency.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.emergency.application.OpsKillSwitchService;
import ffdd.opsconsole.emergency.dto.EmergencyConfigUpdateRequest;
import ffdd.opsconsole.emergency.dto.EmergencyDisableRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/emergency/kill-switches")
@RequiredArgsConstructor
public class OpsKillSwitchController {
    private final OpsKillSwitchService killSwitchService;

    @GetMapping
    public ApiResult<Map<String, Object>> matrix() {
        return killSwitchService.matrix();
    }

    @PutMapping("/{key}")
    public ApiResult<Map<String, Object>> toggle(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody KillSwitchToggleRequest request) {
        return killSwitchService.toggle(key, idempotencyKey, request);
    }

    @PostMapping("/emergency-disable")
    public ApiResult<Map<String, Object>> emergencyDisable(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody EmergencyDisableRequest request) {
        return killSwitchService.emergencyDisable(idempotencyKey, request);
    }

    @PatchMapping("/emergency-sla/{paramKey}")
    public ApiResult<Map<String, Object>> updateEmergencySla(
            @PathVariable String paramKey,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody EmergencyConfigUpdateRequest request) {
        return killSwitchService.updateEmergencySla(paramKey, idempotencyKey, request);
    }

    @PatchMapping("/auto-rules/{ruleId}")
    public ApiResult<Map<String, Object>> updateAutoRule(
            @PathVariable String ruleId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody EmergencyConfigUpdateRequest request) {
        return killSwitchService.updateAutoRule(ruleId, idempotencyKey, request);
    }
}
