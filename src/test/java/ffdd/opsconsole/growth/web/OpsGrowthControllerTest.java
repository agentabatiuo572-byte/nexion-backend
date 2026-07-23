package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.growth.application.OpsGrowthService;
import ffdd.opsconsole.growth.application.OpsGrowthCommandBoundary;
import ffdd.opsconsole.growth.dto.GrowthConfigUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthEarnMilestoneUpdateRequest;
import ffdd.opsconsole.growth.dto.GrowthVoucherRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class OpsGrowthControllerTest {
    private final OpsGrowthService growthService = mock(OpsGrowthService.class);
    private final OpsGrowthCommandBoundary commandBoundary = mock(OpsGrowthCommandBoundary.class);
    private final OpsGrowthController controller = new OpsGrowthController(growthService, commandBoundary);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void passCommandsThroughBoundary() {
        when(commandBoundary.execute(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<ApiResult<Map<String, Object>>>) invocation.getArgument(5)).get());
    }

    @Test
    void phaseOverviewDelegatesToService() {
        when(growthService.phases()).thenReturn(ApiResult.ok(Map.of("dialCount", 8)));

        assertThat(controller.phases().getData()).containsEntry("dialCount", 8);

        verify(growthService).phases();
    }

    @Test
    void phaseSandboxPreviewDelegatesToService() {
        when(growthService.phaseSandboxPreview()).thenReturn(ApiResult.ok(Map.of("writes", false)));

        assertThat(controller.phaseSandboxPreview().getData()).containsEntry("writes", false);

        verify(growthService).phaseSandboxPreview();
    }

    @Test
    void trialOverviewDelegatesToService() {
        when(growthService.trials()).thenReturn(ApiResult.ok(Map.of("domain", "H2")));

        assertThat(controller.trials().getData()).containsEntry("domain", "H2");

        verify(growthService).trials();
    }

    @Test
    void updateTrialParamDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("offsetCap", "40", "tighten", "superadmin");
        when(growthService.updateTrialParam("idem-h2-param", "offsetCap", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateTrialParam("idem-h2-param", "offsetCap", request).getData())
                .containsEntry("ok", true);

        verify(growthService).updateTrialParam("idem-h2-param", "offsetCap", request);
    }

    @Test
    void cancelTrialSessionDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("cancel", "cancelled", "risk", "superadmin");
        when(growthService.cancelTrialSession("idem-h2-cancel", "usr_9921", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.cancelTrialSession("idem-h2-cancel", "usr_9921", request).getData())
                .containsEntry("ok", true);

        verify(growthService).cancelTrialSession("idem-h2-cancel", "usr_9921", request);
    }

    @Test
    void chargeTrialSessionDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("charge", "redeemed", "test", "superadmin");
        when(growthService.chargeTrialSession("idem-h2-charge", "usr_2231", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.chargeTrialSession("idem-h2-charge", "usr_2231", request).getData())
                .containsEntry("ok", true);

        verify(growthService).chargeTrialSession("idem-h2-charge", "usr_2231", request);
    }

    @Test
    void killTrialAutoPushDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("autoPushKilled", "true", "incident", "superadmin");
        when(growthService.killTrialAutoPush("idem-h2-kill", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.killTrialAutoPush("idem-h2-kill", request).getData())
                .containsEntry("ok", true);

        verify(growthService).killTrialAutoPush("idem-h2-kill", request);
    }

    @Test
    void questEventsOverviewDelegatesToService() {
        when(growthService.questEvents()).thenReturn(ApiResult.ok(Map.of("domain", "H3_H4")));

        assertThat(controller.questEvents().getData()).containsEntry("domain", "H3_H4");

        verify(growthService).questEvents();
    }

    @Test
    void updateQuestConfigDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("reward", "80 NEX", "adjust", "superadmin");
        when(growthService.updateQuestConfig("idem-h3-config", "dayOne.tasks.0.reward", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateQuestConfig("idem-h3-config", "dayOne.tasks.0.reward", request).getData())
                .containsEntry("ok", true);

        verify(growthService).updateQuestConfig("idem-h3-config", "dayOne.tasks.0.reward", request);
    }

    @Test
    void updateQuestEventRewardDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("reward", "3000 NEX", "adjust", "superadmin");
        when(growthService.updateQuestEventReward("idem-h4-reward", "ref-5", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateQuestEventReward("idem-h4-reward", "ref-5", request).getData())
                .containsEntry("ok", true);

        verify(growthService).updateQuestEventReward("idem-h4-reward", "ref-5", request);
    }

    @Test
    void updateQuestEventStatusDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("status", "ongoing", "launch", "superadmin");
        when(growthService.updateQuestEventStatus("idem-h4-status", "regional-pk", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateQuestEventStatus("idem-h4-status", "regional-pk", request).getData())
                .containsEntry("ok", true);

        verify(growthService).updateQuestEventStatus("idem-h4-status", "regional-pk", request);
    }

    @Test
    void updateQuestEventFeaturedDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("featured", "true", "feature", "superadmin");
        when(growthService.updateQuestEventFeatured("idem-h4-featured", "ref-5", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateQuestEventFeatured("idem-h4-featured", "ref-5", request).getData())
                .containsEntry("ok", true);

        verify(growthService).updateQuestEventFeatured("idem-h4-featured", "ref-5", request);
    }

    @Test
    void updateCheckInDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("baseRewardNex", "0.2", "tighten", "superadmin");
        when(growthService.updateCheckIn("idem-h5", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateCheckIn("idem-h5", request).getData()).containsEntry("ok", true);

        verify(growthService).updateCheckIn("idem-h5", request);
    }

    @Test
    void updateCheckInRuleDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("p15", "12", "tune lucky", "superadmin");
        when(growthService.updateCheckInRule("idem-h5-rule", "p15", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateCheckInRule("idem-h5-rule", "p15", request).getData()).containsEntry("ok", true);

        verify(growthService).updateCheckInRule("idem-h5-rule", "p15", request);
    }

    @Test
    void updateStreakMilestoneDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("reward", "+20 NEX", "adjust", "superadmin");
        when(growthService.updateStreakMilestone("idem-h5-ms", 1, request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateStreakMilestone("idem-h5-ms", 1, request).getData()).containsEntry("ok", true);

        verify(growthService).updateStreakMilestone("idem-h5-ms", 1, request);
    }

    @Test
    void updatePowerUpDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("day", "35", "adjust", "superadmin");
        when(growthService.updatePowerUp("idem-h5-pu", 2, request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePowerUp("idem-h5-pu", 2, request).getData()).containsEntry("ok", true);

        verify(growthService).updatePowerUp("idem-h5-pu", 2, request);
    }

    @Test
    void updateEarnMilestoneDelegatesWithIdempotencyHeader() {
        GrowthEarnMilestoneUpdateRequest request =
                new GrowthEarnMilestoneUpdateRequest(new BigDecimal("700"), new BigDecimal("300"), "adjust", "superadmin");
        when(growthService.updateEarnMilestone("idem-h6", "earn-500", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateEarnMilestone("idem-h6", "earn-500", request).getData()).containsEntry("ok", true);

        verify(growthService).updateEarnMilestone("idem-h6", "earn-500", request);
    }

    @Test
    void updateEarnMilestoneTickIntervalDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("tick", "8", "tune", "superadmin");
        when(growthService.updateEarnMilestoneTickInterval("idem-h6-tick", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateEarnMilestoneTickInterval("idem-h6-tick", request).getData()).containsEntry("ok", true);

        verify(growthService).updateEarnMilestoneTickInterval("idem-h6-tick", request);
    }

    @Test
    void updateWithdrawGateDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("holdDays", "14", "tighten", "superadmin");
        when(growthService.updateWithdrawGate("idem-h1", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateWithdrawGate("idem-h1", request).getData()).containsEntry("ok", true);

        verify(growthService).updateWithdrawGate("idem-h1", request);
    }

    @Test
    void updatePhaseMonthDialDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("withdrawNexMinBalance", "150", "tighten", "superadmin");
        when(growthService.updatePhaseMonthDial("idem-cell", 7, "withdrawNexMinBalance", request))
                .thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePhaseMonthDial("idem-cell", 7, "withdrawNexMinBalance", request).getCode()).isZero();

        verify(growthService).updatePhaseMonthDial("idem-cell", 7, "withdrawNexMinBalance", request);
    }

    @Test
    void updatePhaseControlDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("pin", "P3", "pin", "superadmin");
        when(growthService.updatePhaseControl("idem-control", "pin", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePhaseControl("idem-control", "pin", request).getCode()).isZero();

        verify(growthService).updatePhaseControl("idem-control", "pin", request);
    }

    @Test
    void updatePhaseOverrideDelegatesWithIdempotencyHeader() {
        GrowthConfigUpdateRequest request = new GrowthConfigUpdateRequest("disabled", "true", "rollback", "superadmin");
        when(growthService.updatePhaseOverride("idem-override", "2026-W18", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updatePhaseOverride("idem-override", "2026-W18", request).getCode()).isZero();

        verify(growthService).updatePhaseOverride("idem-override", "2026-W18", request);
    }

    @Test
    void vouchersOverviewDelegatesToService() {
        when(growthService.vouchers()).thenReturn(ApiResult.ok(Map.of("domain", "H7")));

        assertThat(controller.vouchers().getData()).containsEntry("domain", "H7");

        verify(growthService).vouchers();
    }

    @Test
    void voucherCrudDelegatesWithIdempotencyHeader() {
        GrowthVoucherRequest voucher = new GrowthVoucherRequest(
                "vc-test",
                "Test",
                "fixed",
                BigDecimal.TEN,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                java.util.List.of(),
                "all",
                0L,
                0L,
                java.util.List.of("home"),
                true,
                false,
                false,
                false,
                "active",
                "create",
                "superadmin");
        GrowthConfigUpdateRequest status = new GrowthConfigUpdateRequest("status", "paused", "pause", "superadmin");
        GrowthConfigUpdateRequest delete = new GrowthConfigUpdateRequest("delete", "delete", "delete", "superadmin");
        when(growthService.createVoucher("idem-create", voucher)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(growthService.updateVoucher("idem-update", "vc-test", voucher)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(growthService.updateVoucherStatus("idem-status", "vc-test", status)).thenReturn(ApiResult.ok(Map.of("ok", true)));
        when(growthService.deleteVoucher("idem-delete", "vc-test", delete)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.createVoucher("idem-create", voucher).getCode()).isZero();
        assertThat(controller.updateVoucher("idem-update", "vc-test", voucher).getCode()).isZero();
        assertThat(controller.updateVoucherStatus("idem-status", "vc-test", status).getCode()).isZero();
        assertThat(controller.deleteVoucher("idem-delete", "vc-test", delete).getCode()).isZero();

        verify(growthService).createVoucher("idem-create", voucher);
        verify(growthService).updateVoucher("idem-update", "vc-test", voucher);
        verify(growthService).updateVoucherStatus("idem-status", "vc-test", status);
        verify(growthService).deleteVoucher("idem-delete", "vc-test", delete);
    }
}
