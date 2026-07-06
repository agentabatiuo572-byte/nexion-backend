package ffdd.opsconsole.growth.web;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthQuestEventRequest;
import ffdd.opsconsole.growth.dto.GrowthMissionRequest;
import ffdd.opsconsole.growth.dto.GrowthMonthlyMissionRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelTierRequest;
import ffdd.opsconsole.growth.dto.GrowthWheelGuardRequest;
import ffdd.opsconsole.growth.dto.GrowthVoucherRequest;
import java.util.Map;
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

    @GetMapping("/phases")
    public ApiResult<Map<String, Object>> phases() {
        return growthService.phases();
    }

    @GetMapping("/phases/sandbox-preview")
    public ApiResult<Map<String, Object>> phaseSandboxPreview() {
        return growthService.phaseSandboxPreview();
    }

    @GetMapping("/rhythm")
    public ApiResult<Map<String, Object>> rhythm() {
        return growthService.rhythm();
    }

    @PatchMapping("/rhythm/{paramKey}")
    public ApiResult<Map<String, Object>> updateRhythmParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateRhythmParam(idempotencyKey, paramKey, request);
    }

    @GetMapping("/trials")
    public ApiResult<Map<String, Object>> trials() {
        return growthService.trials();
    }

    @PatchMapping("/trials/params/{paramKey}")
    public ApiResult<Map<String, Object>> updateTrialParam(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String paramKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateTrialParam(idempotencyKey, paramKey, request);
    }

    @PostMapping("/trials/sessions/{sessionId}/cancel")
    public ApiResult<Map<String, Object>> cancelTrialSession(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String sessionId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.cancelTrialSession(idempotencyKey, sessionId, request);
    }

    @PostMapping("/trials/sessions/{sessionId}/charge")
    public ApiResult<Map<String, Object>> chargeTrialSession(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String sessionId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.chargeTrialSession(idempotencyKey, sessionId, request);
    }

    @PostMapping("/trials/auto-push/kill")
    public ApiResult<Map<String, Object>> killTrialAutoPush(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.killTrialAutoPush(idempotencyKey, request);
    }

    @GetMapping("/quest-events")
    public ApiResult<Map<String, Object>> questEvents() {
        return growthService.questEvents();
    }

    @PatchMapping("/quest-events/config/{configKey}")
    public ApiResult<Map<String, Object>> updateQuestConfig(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String configKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateQuestConfig(idempotencyKey, configKey, request);
    }

    @PostMapping("/quest-events/events")
    public ApiResult<Map<String, Object>> createQuestEvent(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthQuestEventRequest request) {
        return growthService.createQuestEvent(idempotencyKey, request);
    }

    @PostMapping("/quest-events/missions")
    public ApiResult<Map<String, Object>> createMission(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthMissionRequest request) {
        return growthService.createMission(idempotencyKey, request);
    }

    @PostMapping("/quest-events/monthly-missions")
    public ApiResult<Map<String, Object>> createMonthlyMission(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthMonthlyMissionRequest request) {
        return growthService.createMonthlyMission(idempotencyKey, request);
    }

    @PostMapping("/quest-events/wheel-tiers")
    public ApiResult<Map<String, Object>> createWheelTier(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthWheelTierRequest request) {
        return growthService.createWheelTier(idempotencyKey, request);
    }

    @PostMapping("/quest-events/wheel-guards")
    public ApiResult<Map<String, Object>> createWheelGuard(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthWheelGuardRequest request) {
        return growthService.createWheelGuard(idempotencyKey, request);
    }

    @PatchMapping("/quest-events/events/{eventId}/reward")
    public ApiResult<Map<String, Object>> updateQuestEventReward(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateQuestEventReward(idempotencyKey, eventId, request);
    }

    @PatchMapping("/quest-events/events/{eventId}/status")
    public ApiResult<Map<String, Object>> updateQuestEventStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateQuestEventStatus(idempotencyKey, eventId, request);
    }

    @PatchMapping("/quest-events/events/{eventId}/featured")
    public ApiResult<Map<String, Object>> updateQuestEventFeatured(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String eventId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateQuestEventFeatured(idempotencyKey, eventId, request);
    }

    @PatchMapping("/phases/dials/{dialKey}")
    public ApiResult<Map<String, Object>> updatePhaseDial(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String dialKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updatePhaseDial(idempotencyKey, dialKey, request);
    }

    @PatchMapping("/phases/months/{month}/dials/{dialKey}")
    public ApiResult<Map<String, Object>> updatePhaseMonthDial(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable int month,
            @PathVariable String dialKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updatePhaseMonthDial(idempotencyKey, month, dialKey, request);
    }

    @PatchMapping("/phases/controls/{controlKey}")
    public ApiResult<Map<String, Object>> updatePhaseControl(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String controlKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updatePhaseControl(idempotencyKey, controlKey, request);
    }

    @PatchMapping("/phases/overrides/{overrideId}")
    public ApiResult<Map<String, Object>> updatePhaseOverride(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String overrideId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updatePhaseOverride(idempotencyKey, overrideId, request);
    }

    @GetMapping("/check-in")
    public ApiResult<Map<String, Object>> checkIn() {
        return growthService.checkIn();
    }

    @PatchMapping("/check-in")
    public ApiResult<Map<String, Object>> updateCheckIn(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateCheckIn(idempotencyKey, request);
    }

    @PatchMapping("/check-in/rules/{ruleKey}")
    public ApiResult<Map<String, Object>> updateCheckInRule(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String ruleKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateCheckInRule(idempotencyKey, ruleKey, request);
    }

    @PatchMapping("/check-in/streak-milestones/{milestoneId}")
    public ApiResult<Map<String, Object>> updateStreakMilestone(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable int milestoneId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateStreakMilestone(idempotencyKey, milestoneId, request);
    }

    @PatchMapping("/check-in/power-ups/{powerUpId}")
    public ApiResult<Map<String, Object>> updatePowerUp(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable int powerUpId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updatePowerUp(idempotencyKey, powerUpId, request);
    }

    @PatchMapping("/earn-milestones/tick-interval")
    public ApiResult<Map<String, Object>> updateEarnMilestoneTickInterval(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateEarnMilestoneTickInterval(idempotencyKey, request);
    }

    @PatchMapping("/earn-milestones/{milestoneKey}")
    public ApiResult<Map<String, Object>> updateEarnMilestone(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String milestoneKey,
            @RequestBody GrowthEarnMilestoneUpdateRequest request) {
        return growthService.updateEarnMilestone(idempotencyKey, milestoneKey, request);
    }

    @GetMapping("/withdraw-gate")
    public ApiResult<Map<String, Object>> withdrawGate() {
        return growthService.withdrawGate();
    }

    @PatchMapping("/withdraw-gate")
    public ApiResult<Map<String, Object>> updateWithdrawGate(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateWithdrawGate(idempotencyKey, request);
    }

    @GetMapping("/vouchers")
    public ApiResult<Map<String, Object>> vouchers() {
        return growthService.vouchers();
    }

    @PostMapping("/vouchers")
    public ApiResult<Map<String, Object>> createVoucher(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody GrowthVoucherRequest request) {
        return growthService.createVoucher(idempotencyKey, request);
    }

    @PatchMapping("/vouchers/{voucherId}")
    public ApiResult<Map<String, Object>> updateVoucher(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String voucherId,
            @RequestBody GrowthVoucherRequest request) {
        return growthService.updateVoucher(idempotencyKey, voucherId, request);
    }

    @PatchMapping("/vouchers/{voucherId}/status")
    public ApiResult<Map<String, Object>> updateVoucherStatus(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String voucherId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.updateVoucherStatus(idempotencyKey, voucherId, request);
    }

    @DeleteMapping("/vouchers/{voucherId}")
    public ApiResult<Map<String, Object>> deleteVoucher(
            @RequestHeader(value = OpsAdminApi.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @PathVariable String voucherId,
            @RequestBody GrowthConfigUpdateRequest request) {
        return growthService.deleteVoucher(idempotencyKey, voucherId, request);
    }
}
