package ffdd.opsconsole.team.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.team.application.LeadershipPoolService;
import ffdd.opsconsole.team.application.OpsTeamService;
import ffdd.opsconsole.team.dto.TeamCommissionConfigUpdateRequest;
import ffdd.opsconsole.team.dto.VRankOverrideRequest;
import ffdd.opsconsole.team.dto.VRankPromotionLogQuery;
import ffdd.opsconsole.team.dto.VRankRewardPayoutActionRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/teams")
@RequiredArgsConstructor
public class OpsTeamController {
    private final OpsTeamService teamService;
    private final LeadershipPoolService leadershipPoolService;

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

    /** F4: 手动触发领导池周结算(注入+票权分配)。F4-MD2,幂等(同周已结算则跳过)。 */
    @PostMapping("/leadership-pool/settle")
    @PreAuthorize("hasAuthority('network_f4_write')")
    public ApiResult<Map<String, Object>> leadershipPoolSettle() {
        int settled = leadershipPoolService.injectAndSettleCurrentWeek();
        return ApiResult.ok(Map.of("settled", settled, "source", "LeadershipPoolService.injectAndSettleCurrentWeek"));
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
    // key 多态承载多域资金放大 HIGH：F2 版税费率(royaltyPct→f2_royalty_rate)/Partner 杠杆(promo/peer/clamp→f2_policy_amplify)、F3 平衡匹配比例(binary-rate→f3_match_rate)、F4 领导池比例(pool-ratio→f4_pool_fund)/票权权重(F.pool.votes.V{n}→f4_write)/配额门槛(F.quota.*→f4_write)/大使审批(F.ambassador.{label}.status→f4_ambassador_approve)/榜单控制(F.leaderboard.*→f4_leaderboard_control)、F5 佣金事件状态处置(F.commission.{id}.status→f5_commission_dispose/reject)；OpsTeamService 需按 key 二次精确校验
    @PreAuthorize("hasAnyAuthority('network_f2_royalty_rate','network_f2_policy_amplify','network_f3_match_rate','network_f4_pool_fund','network_f4_write','network_f4_ambassador_approve','network_f4_leaderboard_control','network_f5_commission_dispose','network_f5_commission_reject')")
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

    // ============================================================
    // Sprint 5:F1 V-Rank 晋升引擎 HTTP 端点暴露(3 个)
    // ============================================================

    /**
     * 端点 1:手动触发 V-Rank 评估。
     * <p>调 VRankPromotionEngine.evaluate(MANUAL ctx),返回 {before, after, promoted, rewards[]}。
     * 引擎内部已带 @Transactional + audit_no 幂等链,故此端点不强制 Idempotency-Key。
     */
    @PostMapping("/vrank/evaluate/{userId}")
    @PreAuthorize("hasAuthority('network_f1_promote_user')")
    public ApiResult<Map<String, Object>> evaluateVRank(@PathVariable Long userId) {
        return teamService.evaluateVRank(userId);
    }

    /**
     * 端点 2:F1-MD1 手动晋升/回滚(越级 / 降阶处置)。
     * <p>body=VRankOverrideRequest{targetV, direction[promote|rollback], reason, operator}。
     * requireCommand + A2 对象锁 + @Transactional UPDATE v_rank + INSERT level_log[is_manual=1]。
     * promote 方向额外调 rewardDispatcher.dispatch;rollback 不派奖不剥夺。
     */
    @PostMapping("/users/{userId}/vrank/override")
    @PreAuthorize("hasAuthority('network_f1_promote_user')")
    public ApiResult<Map<String, Object>> overrideVRank(
            @PathVariable Long userId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankOverrideRequest request) {
        return teamService.overrideVRank(userId, idempotencyKey, request);
    }

    /**
     * 端点 3:V-Rank 晋升流水查询。
     * <p>筛选 userId/v/cohort/from/to,查 nx_user_level_log WHERE level_type='VRANK'。
     * LEFT JOIN nx_user 取 nickname;reason LIKE '[MANUAL]%' 标记 isManual。
     */
    @GetMapping("/promotion-log")
    @PreAuthorize("hasAuthority('network_f1_read')")
    public ApiResult<Map<String, Object>> promotionLog(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String v,
            @RequestParam(required = false) String cohort,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return teamService.queryPromotionLog(new VRankPromotionLogQuery(userId, v, cohort, from, to));
    }

    // ============================================================
    // Sprint 6:F1 V-Rank 派发流水端点第二组(3 个:query/reissue/reverse)
    // ============================================================

    /**
     * 端点 4:派发流水查询(GET /api/admin/teams/reward-payouts)。
     * <p>筛选 type/v/status/userId/cursor,查 nx_v_rank_reward_payout WHERE is_deleted=0
     * ORDER BY granted_at DESC LIMIT 100。@PreAuthorize network_f1_read。
     */
    @GetMapping("/reward-payouts")
    @PreAuthorize("hasAuthority('network_f1_read')")
    public ApiResult<Map<String, Object>> rewardPayouts(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String v,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String cursor) {
        return teamService.queryRewardPayouts(type, v, status, userId, cursor);
    }

    /**
     * 端点 5:F1-MD4 派发补发(POST /api/admin/teams/reward-payouts/{payoutId}/reissue)。
     * <p>找原 payout → 重派:资金类(usdt/nex) B1 预检 + 新 commission_event + D4 IN/PENDING;
     * 权益类(voucher/sku/custom) 不走 D4;UPDATE payout status=REISSUED。
     * @PreAuthorize network_f1_reward_reissue(HIGH)。
     */
    @PostMapping("/reward-payouts/{payoutId}/reissue")
    @PreAuthorize("hasAuthority('network_f1_reward_reissue')")
    public ApiResult<Map<String, Object>> reissueRewardPayout(
            @PathVariable String payoutId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankRewardPayoutActionRequest request) {
        return teamService.reissueRewardPayout(payoutId, idempotencyKey, request);
    }

    /**
     * 端点 6:F1-MD4 派发撤销(POST /api/admin/teams/reward-payouts/{payoutId}/reverse)。
     * <p>UPDATE payout status=REVERSED + reversed_at;资金类 D4 红冲
     * (commission_event.status=REVERSED + ledgerPostingFacade OUT/SUCCESS 反向冲正)。
     * @PreAuthorize network_f1_reward_reverse(HIGH)。
     */
    @PostMapping("/reward-payouts/{payoutId}/reverse")
    @PreAuthorize("hasAuthority('network_f1_reward_reverse')")
    public ApiResult<Map<String, Object>> reverseRewardPayout(
            @PathVariable String payoutId,
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody VRankRewardPayoutActionRequest request) {
        return teamService.reverseRewardPayout(payoutId, idempotencyKey, request);
    }
}
