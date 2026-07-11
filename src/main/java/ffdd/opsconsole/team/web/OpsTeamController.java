package ffdd.opsconsole.team.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.team.application.OpsTeamService;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankRewardRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyAuthority('network_f1_read','network_f2_read','network_f3_read','network_f4_read','network_f5_read')")
    public ApiResult<Map<String, Object>> overview() {
        return teamService.overview();
    }

    @GetMapping("/commissions")
    @PreAuthorize("hasAuthority('network_f5_read')")
    public ApiResult<Map<String, Object>> commissions() {
        return teamService.commissions();
    }

    @GetMapping("/ranks")
    @PreAuthorize("hasAuthority('network_f1_read')")
    public ApiResult<Map<String, Object>> ranks() {
        return teamService.ranks();
    }

    @GetMapping("/rates")
    @PreAuthorize("hasAuthority('network_f2_read')")
    public ApiResult<Map<String, Object>> rates() {
        return teamService.rates();
    }

    @GetMapping("/binary")
    @PreAuthorize("hasAuthority('network_f3_read')")
    public ApiResult<Map<String, Object>> binary() {
        return teamService.binary();
    }

    @GetMapping("/leadership-pool")
    @PreAuthorize("hasAuthority('network_f4_read')")
    public ApiResult<Map<String, Object>> leadershipPool() {
        return teamService.leadershipPool();
    }

    @PatchMapping("/ranks/{rank}/thresholds/{field}")
    @PreAuthorize("hasAuthority('network_f1_write')")
    public ApiResult<Map<String, Object>> updateVRankThreshold(
            @PathVariable String rank,
            @PathVariable String field,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody TeamCommissionConfigUpdateRequest request) {
        return teamService.updateVRankThreshold(rank, field, idempotencyKey, request);
    }

    @PostMapping("/ranks/{rank}/rewards")
    @PreAuthorize("hasAuthority('network_f1_write')")
    public ApiResult<Map<String, Object>> addVRankReward(
            @PathVariable String rank,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankRewardRequest request) {
        return teamService.addVRankReward(rank, idempotencyKey, request);
    }

    @PutMapping("/ranks/{rank}/rewards/{rewardId}")
    @PreAuthorize("hasAuthority('network_f1_write')")
    public ApiResult<Map<String, Object>> updateVRankReward(
            @PathVariable String rank,
            @PathVariable String rewardId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankRewardRequest request) {
        return teamService.updateVRankReward(rank, rewardId, idempotencyKey, request);
    }

    @DeleteMapping("/ranks/{rank}/rewards/{rewardId}")
    @PreAuthorize("hasAuthority('network_f1_write')")
    public ApiResult<Map<String, Object>> removeVRankReward(
            @PathVariable String rank,
            @PathVariable String rewardId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) VRankRewardRequest request) {
        return teamService.removeVRankReward(rank, rewardId, idempotencyKey, request);
    }

    @PatchMapping("/commissions/config/{key}")
    // key 多态承载多域资金放大 HIGH：F2 版税费率(royaltyPct→f2_royalty_rate)/Partner 杠杆(promo/peer/clamp→f2_policy_amplify)、F3 平衡匹配比例(binary-rate→f3_match_rate)、F4 领导池比例(pool-ratio→f4_pool_fund)；OpsTeamService 需按 key 二次精确校验
    @PreAuthorize("hasAnyAuthority('network_f2_royalty_rate','network_f2_policy_amplify','network_f3_match_rate','network_f4_pool_fund')")
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
