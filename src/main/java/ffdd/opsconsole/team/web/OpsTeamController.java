package ffdd.opsconsole.team.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.team.application.OpsTeamService;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankRewardRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/teams")
@RequiredArgsConstructor
public class OpsTeamController {
    private final OpsTeamService teamService;

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

    @GetMapping("/rates")
    public ApiResult<Map<String, Object>> rates() {
        return teamService.rates();
    }

    @GetMapping("/binary")
    public ApiResult<Map<String, Object>> binary() {
        return teamService.binary();
    }

    @GetMapping("/leadership-pool")
    public ApiResult<Map<String, Object>> leadershipPool() {
        return teamService.leadershipPool();
    }

    @PatchMapping("/ranks/{rank}/thresholds/{field}")
    public ApiResult<Map<String, Object>> updateVRankThreshold(
            @PathVariable String rank,
            @PathVariable String field,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TeamCommissionConfigUpdateRequest request) {
        return teamService.updateVRankThreshold(rank, field, idempotencyKey, request);
    }

    @PostMapping("/ranks/{rank}/rewards")
    public ApiResult<Map<String, Object>> addVRankReward(
            @PathVariable String rank,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankRewardRequest request) {
        return teamService.addVRankReward(rank, idempotencyKey, request);
    }

    @PutMapping("/ranks/{rank}/rewards/{rewardId}")
    public ApiResult<Map<String, Object>> updateVRankReward(
            @PathVariable String rank,
            @PathVariable String rewardId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankRewardRequest request) {
        return teamService.updateVRankReward(rank, rewardId, idempotencyKey, request);
    }

    @DeleteMapping("/ranks/{rank}/rewards/{rewardId}")
    public ApiResult<Map<String, Object>> removeVRankReward(
            @PathVariable String rank,
            @PathVariable String rewardId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VRankRewardRequest request) {
        return teamService.removeVRankReward(rank, rewardId, idempotencyKey, request);
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
