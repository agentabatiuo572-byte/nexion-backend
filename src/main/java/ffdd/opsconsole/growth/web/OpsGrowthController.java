package ffdd.opsconsole.growth.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.growth.application.OpsGrowthCommandBoundary;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthQuestEventRequest;
import ffdd.opsconsole.growth.dto.GrowthMissionRequest;
import ffdd.opsconsole.growth.dto.GrowthMonthlyMissionRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelTierRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelGuardRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelProbabilityBatchRequest;
import ffdd.opsconsole.growth.dto.GrowthVoucherRequest;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(OpsAdminApi.ADMIN_PREFIX + "/growth")
@RequiredArgsConstructor
public class OpsGrowthController {
    private final OpsGrowthService growthService;
    private final OpsGrowthCommandBoundary commandBoundary;

    @GetMapping("/phases")
    @PreAuthorize("hasAuthority('growth_h1_read')")
    public ApiResult<Map<String, Object>> phases() {
        return growthService.phases();
    }

    @GetMapping("/phases/sandbox-preview")
    @PreAuthorize("hasAuthority('growth_h1_read')")
    public ApiResult<Map<String, Object>> phaseSandboxPreview() {
        return growthService.phaseSandboxPreview();
    }

    @GetMapping("/rhythm")
    @PreAuthorize("hasAuthority('growth_h1_read')")
    public ApiResult<Map<String, Object>> rhythm() {
        return growthService.rhythm();
    }

    @PatchMapping("/rhythm/{paramKey}")
    @PreAuthorize("hasAuthority('growth_h1_write')")
    public ApiResult<Map<String, Object>> updateRhythmParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H1", "RHYTHM_PARAM_UPDATE", paramKey, idempotencyKey, request,
                () -> growthService.updateRhythmParam(idempotencyKey, paramKey, request));
    }

    @GetMapping("/trials")
    @PreAuthorize("hasAuthority('growth_h2_read')")
    public ApiResult<Map<String, Object>> trials() {
        return growthService.trials();
    }

    @PatchMapping("/trials/params/{paramKey}")
    @PreAuthorize("hasAuthority('growth_h2_write')")
    public ApiResult<Map<String, Object>> updateTrialParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H2", "TRIAL_PARAM_UPDATE", paramKey, idempotencyKey, request,
                () -> growthService.updateTrialParam(idempotencyKey, paramKey, request));
    }

    @PostMapping("/trials/sessions/{sessionId}/cancel")
    // HIGH：强制取消试用会话，不可逆操作
    @PreAuthorize("hasAuthority('growth_h2_session_cancel')")
    public ApiResult<Map<String, Object>> cancelTrialSession(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String sessionId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H2", "TRIAL_SESSION_CANCEL", sessionId, idempotencyKey, request,
                () -> growthService.cancelTrialSession(idempotencyKey, sessionId, request));
    }

    @PostMapping("/trials/sessions/{sessionId}/charge")
    // HIGH：强制触发扣款，不可逆资金操作
    @PreAuthorize("hasAuthority('growth_h2_session_charge')")
    public ApiResult<Map<String, Object>> chargeTrialSession(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String sessionId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H2", "TRIAL_SESSION_CHARGE", sessionId, idempotencyKey, request,
                () -> growthService.chargeTrialSession(idempotencyKey, sessionId, request));
    }

    @PostMapping("/trials/auto-push/kill")
    @PreAuthorize("hasAuthority('growth_h2_write')")
    public ApiResult<Map<String, Object>> killTrialAutoPush(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H2", "AUTO_PUSH_KILL", "AUTO_PUSH", idempotencyKey, request,
                () -> growthService.killTrialAutoPush(idempotencyKey, request));
    }

    @GetMapping("/quest-events")
    @PreAuthorize("hasAuthority('growth_h3_read') and hasAuthority('growth_h4_read')")
    public ApiResult<Map<String, Object>> questEvents() {
        return growthService.questEvents();
    }

    @GetMapping("/quest-events/tasks")
    @PreAuthorize("hasAuthority('growth_h3_read')")
    public ApiResult<Map<String, Object>> questTasks() {
        return growthService.questTasks();
    }

    @GetMapping("/quest-events/events-overview")
    @PreAuthorize("hasAuthority('growth_h4_read')")
    public ApiResult<Map<String, Object>> questEventOverview() {
        return growthService.questEventOverview();
    }

    @PatchMapping("/quest-events/config/{configKey}")
    @PreAuthorize("hasAuthority('growth_h3_write')")
    public ApiResult<Map<String, Object>> updateQuestConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String configKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H3", "QUEST_CONFIG_UPDATE", configKey, idempotencyKey, request,
                () -> growthService.updateQuestConfig(idempotencyKey, configKey, request));
    }

    @PostMapping("/quest-events/events")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String, Object>> createQuestEvent(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthQuestEventRequest request) {
        return commandBoundary.execute("H4", "EVENT_CREATE", request == null ? "EVENT" : request.id(), idempotencyKey, request,
                () -> growthService.createQuestEvent(idempotencyKey, request));
    }

    @PostMapping("/quest-events/missions")
    @PreAuthorize("hasAuthority('growth_h3_write')")
    public ApiResult<Map<String, Object>> createMission(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthMissionRequest request) {
        return commandBoundary.execute("H3", "MISSION_CREATE", request == null ? "MISSION" : request.missionCode(), idempotencyKey, request,
                () -> growthService.createMission(idempotencyKey, request));
    }

    @PostMapping("/quest-events/monthly-missions")
    @PreAuthorize("hasAuthority('growth_h3_write')")
    public ApiResult<Map<String, Object>> createMonthlyMission(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthMonthlyMissionRequest request) {
        return commandBoundary.execute("H3", "MONTHLY_MISSION_CREATE", request == null ? "MISSION" : request.challengeCode(), idempotencyKey, request,
                () -> growthService.createMonthlyMission(idempotencyKey, request));
    }

    @PostMapping("/quest-events/wheel-tiers")
    // 转盘档位属 H4 活动中心（按业务内容归 H4，非路径所在 H3）
    @PreAuthorize("hasAuthority('growth_h4_wheel_pool_write')")
    public ApiResult<Map<String, Object>> createWheelTier(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthWheelTierRequest request) {
        return commandBoundary.execute("H4", "WHEEL_TIER_CREATE", request == null ? "TIER" : request.tierName(), idempotencyKey, request,
                () -> growthService.createWheelTier(idempotencyKey, request));
    }

    @PostMapping("/quest-events/wheel-guards")
    // 转盘护栏属 H4 活动中心（按业务内容归 H4，非路径所在 H3）
    @PreAuthorize("hasAuthority('growth_h4_wheel_pool_write')")
    public ApiResult<Map<String, Object>> createWheelGuard(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthWheelGuardRequest request) {
        return commandBoundary.execute("H4", "WHEEL_GUARD_CREATE", request == null ? "GUARD" : request.guardKey(), idempotencyKey, request,
                () -> growthService.createWheelGuard(idempotencyKey, request));
    }

    @PatchMapping("/quest-events/wheel-tiers/probabilities")
    @PreAuthorize("hasAuthority('growth_h4_wheel_pool_write')")
    public ApiResult<Map<String, Object>> updateWheelProbabilities(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthWheelProbabilityBatchRequest request) {
        return commandBoundary.execute("H4", "WHEEL_PROBABILITIES_UPDATE", "ACTIVE_POOL", idempotencyKey, request,
                () -> growthService.updateWheelProbabilities(idempotencyKey, request));
    }

    @PatchMapping("/quest-events/wheel-tiers/{tierName}")
    @PreAuthorize("hasAuthority('growth_h4_wheel_pool_write')")
    public ApiResult<Map<String, Object>> updateWheelTier(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String tierName,
            @RequestBody GrowthWheelTierRequest request) {
        return commandBoundary.execute("H4", "WHEEL_TIER_UPDATE", tierName, idempotencyKey, request,
                () -> growthService.updateWheelTier(idempotencyKey, tierName, request));
    }

    @DeleteMapping("/quest-events/wheel-tiers/{tierName}")
    @PreAuthorize("hasAuthority('growth_h4_wheel_pool_write')")
    public ApiResult<Map<String, Object>> deleteWheelTier(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String tierName,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H4", "WHEEL_TIER_DELETE", tierName, idempotencyKey, request,
                () -> growthService.deleteWheelTier(idempotencyKey, tierName, request));
    }

    @PatchMapping("/quest-events/events/{eventId}/reward")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String, Object>> updateQuestEventReward(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H4", "EVENT_REWARD_UPDATE", eventId, idempotencyKey, request,
                () -> growthService.updateQuestEventReward(idempotencyKey, eventId, request));
    }

    @PatchMapping("/quest-events/events/{eventId}/status")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String, Object>> updateQuestEventStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H4", "EVENT_STATUS_UPDATE", eventId, idempotencyKey, request,
                () -> growthService.updateQuestEventStatus(idempotencyKey, eventId, request));
    }

    @PatchMapping("/quest-events/events/{eventId}/featured")
    @PreAuthorize("hasAuthority('growth_h4_write')")
    public ApiResult<Map<String, Object>> updateQuestEventFeatured(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H4", "EVENT_FEATURED_UPDATE", eventId, idempotencyKey, request,
                () -> growthService.updateQuestEventFeatured(idempotencyKey, eventId, request));
    }

    @PatchMapping("/phases/dials/{dialKey}")
    @PreAuthorize("hasAuthority('growth_h1_write')")
    public ApiResult<Map<String, Object>> updatePhaseDial(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String dialKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H1", "PHASE_DIAL_UPDATE", dialKey, idempotencyKey, request,
                () -> growthService.updatePhaseDial(idempotencyKey, dialKey, request));
    }

    @PatchMapping("/phases/months/{month}/dials/{dialKey}")
    @PreAuthorize("hasAuthority('growth_h1_write')")
    public ApiResult<Map<String, Object>> updatePhaseMonthDial(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable int month,
            @PathVariable String dialKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H1", "PHASE_MONTH_DIAL_UPDATE", month + ":" + dialKey, idempotencyKey, request,
                () -> growthService.updatePhaseMonthDial(idempotencyKey, month, dialKey, request));
    }

    @PatchMapping("/phases/controls/{controlKey}")
    // HIGH：Phase 手动钉住类控制，影响全局节奏
    @PreAuthorize("hasAuthority('growth_h1_control_pin_write')")
    public ApiResult<Map<String, Object>> updatePhaseControl(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String controlKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H1", "PHASE_CONTROL_UPDATE", controlKey, idempotencyKey, request,
                () -> growthService.updatePhaseControl(idempotencyKey, controlKey, request));
    }

    @PatchMapping("/phases/overrides/{overrideId}")
    // HIGH：撤销/修改 cohort override，影响 Phase 调度
    @PreAuthorize("hasAuthority('growth_h1_override_revoke')")
    public ApiResult<Map<String, Object>> updatePhaseOverride(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String overrideId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H1", "PHASE_OVERRIDE_UPDATE", overrideId, idempotencyKey, request,
                () -> growthService.updatePhaseOverride(idempotencyKey, overrideId, request));
    }

    @GetMapping("/check-in")
    @PreAuthorize("hasAuthority('growth_h5_read')")
    public ApiResult<Map<String, Object>> checkIn() {
        return growthService.checkIn();
    }

    @PatchMapping("/check-in")
    @PreAuthorize("hasAuthority('growth_h5_write')")
    public ApiResult<Map<String, Object>> updateCheckIn(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H5", "CHECKIN_UPDATE", "CHECKIN", idempotencyKey, request,
                () -> growthService.updateCheckIn(idempotencyKey, request));
    }

    @PatchMapping("/check-in/rules/{ruleKey}")
    // HIGH：签到 Lucky 概率/规则，放大 NEX 派发
    @PreAuthorize("hasAuthority('growth_h5_rule_write')")
    public ApiResult<Map<String, Object>> updateCheckInRule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String ruleKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H5", "CHECKIN_RULE_UPDATE", ruleKey, idempotencyKey, request,
                () -> growthService.updateCheckInRule(idempotencyKey, ruleKey, request));
    }

    @PatchMapping("/check-in/streak-milestones/{milestoneId}")
    @PreAuthorize("hasAuthority('growth_h5_write')")
    public ApiResult<Map<String, Object>> updateStreakMilestone(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable int milestoneId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H5", "STREAK_MILESTONE_UPDATE", String.valueOf(milestoneId), idempotencyKey, request,
                () -> growthService.updateStreakMilestone(idempotencyKey, milestoneId, request));
    }

    @PatchMapping("/check-in/power-ups/{powerUpId}")
    @PreAuthorize("hasAuthority('growth_h5_write')")
    public ApiResult<Map<String, Object>> updatePowerUp(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable int powerUpId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H5", "POWER_UP_UPDATE", String.valueOf(powerUpId), idempotencyKey, request,
                () -> growthService.updatePowerUp(idempotencyKey, powerUpId, request));
    }

    @PatchMapping("/earn-milestones/tick-interval")
    // earn 里程碑属 H5 签到&NEX（收益/间隔），按业务内容归 H5
    @PreAuthorize("hasAuthority('growth_h5_write')")
    public ApiResult<Map<String, Object>> updateEarnMilestoneTickInterval(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H5", "EARN_MILESTONE_INTERVAL_UPDATE", "TICK_INTERVAL", idempotencyKey, request,
                () -> growthService.updateEarnMilestoneTickInterval(idempotencyKey, request));
    }

    @PatchMapping("/earn-milestones/{milestoneKey}")
    // earn 里程碑属 H5 签到&NEX（里程碑/收益），按业务内容归 H5
    @PreAuthorize("hasAuthority('growth_h5_write')")
    public ApiResult<Map<String, Object>> updateEarnMilestone(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String milestoneKey,
            @RequestBody GrowthEarnMilestoneUpdateRequest request) {
        return commandBoundary.execute("H5", "EARN_MILESTONE_UPDATE", milestoneKey, idempotencyKey, request,
                () -> growthService.updateEarnMilestone(idempotencyKey, milestoneKey, request));
    }

    @GetMapping("/withdraw-gate")
    // 待人工确认：提现闸门归属域字典未明确，暂按 H5（NEX 收益体系）处理
    @PreAuthorize("hasAuthority('growth_h5_read')")
    public ApiResult<Map<String, Object>> withdrawGate() {
        return growthService.withdrawGate();
    }

    @PatchMapping("/withdraw-gate")
    // 待人工确认：提现闸门归属域字典未明确，暂按 H5（NEX 收益体系）处理
    @PreAuthorize("hasAuthority('growth_h5_write')")
    public ApiResult<Map<String, Object>> updateWithdrawGate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H5", "WITHDRAW_GATE_UPDATE", "WITHDRAW_GATE", idempotencyKey, request,
                () -> growthService.updateWithdrawGate(idempotencyKey, request));
    }

    @GetMapping("/vouchers")
    @PreAuthorize("hasAuthority('growth_h7_read')")
    public ApiResult<Map<String, Object>> vouchers() {
        return growthService.vouchers();
    }

    @PostMapping("/vouchers")
    @PreAuthorize("hasAuthority('growth_h7_write')")
    public ApiResult<Map<String, Object>> createVoucher(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthVoucherRequest request) {
        return commandBoundary.execute("H7", "VOUCHER_CREATE", request == null ? "VOUCHER" : request.id(), idempotencyKey, request,
                () -> growthService.createVoucher(idempotencyKey, request));
    }

    @PatchMapping("/vouchers/{voucherId}")
    @PreAuthorize("hasAuthority('growth_h7_write')")
    public ApiResult<Map<String, Object>> updateVoucher(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String voucherId,
            @RequestBody GrowthVoucherRequest request) {
        return commandBoundary.execute("H7", "VOUCHER_UPDATE", voucherId, idempotencyKey, request,
                () -> growthService.updateVoucher(idempotencyKey, voucherId, request));
    }

    @PatchMapping("/vouchers/{voucherId}/status")
    @PreAuthorize("hasAuthority('growth_h7_write')")
    public ApiResult<Map<String, Object>> updateVoucherStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String voucherId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H7", "VOUCHER_STATUS_UPDATE", voucherId, idempotencyKey, request,
                () -> growthService.updateVoucherStatus(idempotencyKey, voucherId, request));
    }

    @DeleteMapping("/vouchers/{voucherId}")
    @PreAuthorize("hasAuthority('growth_h7_write')")
    public ApiResult<Map<String, Object>> deleteVoucher(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String voucherId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return commandBoundary.execute("H7", "VOUCHER_DELETE", voucherId, idempotencyKey, request,
                () -> growthService.deleteVoucher(idempotencyKey, voucherId, request));
    }
}
