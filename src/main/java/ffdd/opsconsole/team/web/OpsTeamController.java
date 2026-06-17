package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.team.application.OpsTeamService;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/teams")
public class OpsTeamController {
    private final OpsTeamService teamService;

    public OpsTeamController(OpsTeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        return teamService.overview();
    }

    @GetMapping("/commissions")
    public ApiResult<Map<String, Object>> commissions() {
        return teamService.commissions();
    }

    @GetMapping("/ranks")
    public ApiResult<Map<String, Object>> ranks() {
        return teamService.ranks();
    }

    @PatchMapping("/commissions/config/{key}")
    public ApiResult<Map<String, Object>> updateConfig(
            @PathVariable String key,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TeamCommissionConfigUpdateRequest request) {
        TeamCommissionConfigUpdateRequest normalized = new TeamCommissionConfigUpdateRequest(
                key,
                request == null ? null : request.value(),
                request == null ? null : request.reason(),
                request == null ? null : request.operator());
        return teamService.updateConfig(idempotencyKey, normalized);
    }
}
