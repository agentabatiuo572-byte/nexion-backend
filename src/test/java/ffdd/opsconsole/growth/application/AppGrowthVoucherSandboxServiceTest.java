package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.mapper.AppGrowthEngagementMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppGrowthVoucherSandboxServiceTest {
    private final AppGrowthEngagementMapper mapper = mock(AppGrowthEngagementMapper.class);
    private final WheelSandboxProfile profile = mock(WheelSandboxProfile.class);
    private final AppGrowthVoucherSandboxService service =
            new AppGrowthVoucherSandboxService(mapper, profile);

    @BeforeEach
    void sandboxUser() {
        when(profile.mode()).thenReturn(WheelSandboxProfile.Mode.SANDBOX);
        when(profile.requireSandbox(42L)).thenReturn(new WheelSandboxProfile.Scope("run-a-20260818", 42L));
        when(mapper.findSandboxUser(42L)).thenReturn(42L);
    }

    @Test
    void readsCanonicalDefinitionsWithRunScopedPopupStateAndProvenance() {
        when(mapper.voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong()))
                .thenReturn(List.of(Map.ofEntries(
                        Map.entry("voucherId", "V-1"), Map.entry("voucherName", "Sandbox voucher"),
                        Map.entry("voucherType", "fixed"), Map.entry("amountUsd", 50),
                        Map.entry("percentValue", 0), Map.entry("minPurchaseUsd", 0),
                        Map.entry("maxDiscountUsd", 50), Map.entry("applicableSkus", "[]"),
                        Map.entry("audience", "all"), Map.entry("claimSurfaces", "[\"home\"]"),
                        Map.entry("startAt", 0), Map.entry("endAt", 0), Map.entry("popupEnabled", true),
                        Map.entry("popupDelayMs", 300), Map.entry("popupCooldownHours", 1),
                        Map.entry("popupMaxPerSession", 1), Map.entry("popupCadenceEnabled", true),
                        Map.entry("popupLastSeenAt", 0), Map.entry("popupSessionCount", 0),
                        Map.entry("stackWithTrial", false), Map.entry("stackWithOthers", false),
                        Map.entry("splittable", false), Map.entry("definitionStatus", "active"),
                        Map.entry("definitionDeleted", 0), Map.entry("grantStatus", "UNCLAIMED"))));

        var result = service.voucherState(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("source", "nx_growth_voucher + nx_voucher_popup_sandbox_state")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("serverCanonical", true)
                .containsEntry("runId", "run-a-20260818");
        Map<?, ?> provenance = (Map<?, ?>) result.getData().get("provenance");
        assertThat(provenance.get("source"))
                .isEqualTo("nx_growth_voucher + nx_voucher_popup_sandbox_state");
        assertThat(provenance.get("sourceEnvironment")).isEqualTo("SANDBOX");
        assertThat(provenance.get("runId")).isEqualTo("run-a-20260818");
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = (Map<String, Object>) ((List<?>) result.getData().get("vouchers")).get(0);
        assertThat(voucher).containsEntry("popupEligible", true)
                .containsEntry("nextEligibleAt", 0L);
    }

    @Test
    void popupSeenUsesRunAndUserScopeSoAnotherRunCannotReuseCooldownState() {
        when(mapper.markVoucherPopupSeenSandbox(eq("run-a-20260818"), eq(42L), eq("V-1"), anyLong()))
                .thenReturn(1);
        when(mapper.voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong())).thenReturn(List.of());

        service.markVoucherPopupSeen(42L, "V-1");

        verify(mapper).markVoucherPopupSeenSandbox(eq("run-a-20260818"), eq(42L), eq("V-1"), anyLong());
        verify(mapper).voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong());
        when(profile.requireSandbox(42L)).thenReturn(new WheelSandboxProfile.Scope("run-b-20260818", 42L));
        when(mapper.markVoucherPopupSeenSandbox(eq("run-b-20260818"), eq(42L), eq("V-1"), anyLong()))
                .thenReturn(1);
        when(mapper.voucherStateSandbox(eq("run-b-20260818"), eq(42L), anyLong())).thenReturn(List.of());

        service.markVoucherPopupSeen(42L, "V-1");

        verify(mapper).markVoucherPopupSeenSandbox(eq("run-b-20260818"), eq(42L), eq("V-1"), anyLong());
    }

    @Test
    void cooldownIsReturnedAsNextEligibleAtAndMalformedBoundsFailClosed() {
        long lastSeenAt = System.currentTimeMillis() - 1_000L;
        when(mapper.voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong()))
                .thenReturn(List.of(Map.ofEntries(
                        Map.entry("voucherId", "V-1"), Map.entry("audience", "all"),
                        Map.entry("popupEnabled", true), Map.entry("popupCadenceEnabled", true),
                        Map.entry("popupDelayMs", 300), Map.entry("popupCooldownHours", 1),
                        Map.entry("popupMaxPerSession", 1), Map.entry("popupLastSeenAt", lastSeenAt),
                        Map.entry("popupSessionCount", 1), Map.entry("definitionDeleted", 0),
                        Map.entry("definitionStatus", "active"), Map.entry("grantStatus", "UNCLAIMED"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) service.voucherState(42L).getData().get("vouchers")).get(0);
        assertThat(row.get("popupEligible")).isEqualTo(false);
        assertThat((Long) row.get("nextEligibleAt")).isGreaterThan(lastSeenAt);

        when(mapper.voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong()))
                .thenReturn(List.of(Map.of("popupDelayMs", 300, "popupCooldownHours", 1,
                        "popupMaxPerSession", 99, "popupLastSeenAt", 0L)));
        assertThatThrownBy(() -> service.voucherState(42L))
                .hasMessageContaining("VOUCHER_CADENCE_INVALID");

        when(mapper.voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong()))
                .thenReturn(List.of(Map.of("popupDelayMs", -1, "popupCooldownHours", 1,
                        "popupMaxPerSession", 1, "popupLastSeenAt", 0L)));
        assertThatThrownBy(() -> service.voucherState(42L))
                .hasMessageContaining("VOUCHER_CADENCE_INVALID");
    }

    @Test
    void expiredDefinitionsAreNotClaimableOrPopupEligibleEvenIfMapperReturnsOne() {
        long expiredAt = System.currentTimeMillis() - 1_000L;
        when(mapper.voucherStateSandbox(eq("run-a-20260818"), eq(42L), anyLong()))
                .thenReturn(List.of(Map.ofEntries(
                        Map.entry("voucherId", "V-EXPIRED"), Map.entry("audience", "all"),
                        Map.entry("popupEnabled", true), Map.entry("popupCadenceEnabled", true),
                        Map.entry("popupDelayMs", 300), Map.entry("popupCooldownHours", 1),
                        Map.entry("popupMaxPerSession", 1), Map.entry("popupLastSeenAt", 0L),
                        Map.entry("startAt", 0L), Map.entry("endAt", expiredAt),
                        Map.entry("definitionDeleted", 0), Map.entry("definitionStatus", "active"),
                        Map.entry("grantStatus", "UNCLAIMED"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) service.voucherState(42L).getData().get("vouchers")).get(0);
        assertThat(row).containsEntry("claimable", false).containsEntry("popupEligible", false);
    }

    @Test
    void claimCreatesRunScopedGrantAndSameIdempotencyKeyReplays() {
        when(mapper.lockSandboxClaimableVoucher(eq("run-a-20260818"), eq(42L), eq("V-1"), eq("home"), anyLong()))
                .thenReturn(new AppGrowthEngagementMapper.VoucherClaimDefinition("V-1", "all"));
        when(mapper.lockSandboxVoucherClaim(eq("run-a-20260818"), eq(42L), eq("V-1")))
                .thenReturn(null)
                .thenReturn(new AppGrowthEngagementMapper.SandboxVoucherClaim("SBX-GRANT-1", "AVAILABLE", "claim-key"));
        when(mapper.insertSandboxVoucherClaim(eq("run-a-20260818"), eq(42L), eq("V-1"), anyString(), eq("claim-key"), anyLong()))
                .thenReturn(1);

        var first = service.claimVoucher(42L, "V-1", "home", "claim-key");
        var replay = service.claimVoucher(42L, "V-1", "home", "claim-key");

        assertThat(first.getCode()).isZero();
        assertThat(first.getData()).containsEntry("voucherId", "V-1")
                .containsEntry("status", "AVAILABLE").containsEntry("replay", false)
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "nx_voucher_popup_sandbox_state")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-a-20260818");
        Map<?, ?> firstProvenance = (Map<?, ?>) first.getData().get("provenance");
        assertThat(firstProvenance.get("source")).isEqualTo("nx_voucher_popup_sandbox_state");
        assertThat(firstProvenance.get("sourceEnvironment")).isEqualTo("SANDBOX");
        assertThat(firstProvenance.get("runId")).isEqualTo("run-a-20260818");
        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData()).containsEntry("grantId", "SBX-GRANT-1")
                .containsEntry("replay", true)
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "nx_voucher_popup_sandbox_state")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "run-a-20260818");
        Map<?, ?> replayProvenance = (Map<?, ?>) replay.getData().get("provenance");
        assertThat(replayProvenance.get("source")).isEqualTo("nx_voucher_popup_sandbox_state");
        assertThat(replayProvenance.get("sourceEnvironment")).isEqualTo("SANDBOX");
        assertThat(replayProvenance.get("runId")).isEqualTo("run-a-20260818");
    }

    @Test
    void claimWithDifferentKeyAfterWinnerIsAConflict() {
        when(mapper.lockSandboxClaimableVoucher(eq("run-a-20260818"), eq(42L), eq("V-1"), eq("home"), anyLong()))
                .thenReturn(new AppGrowthEngagementMapper.VoucherClaimDefinition("V-1", "all"));
        when(mapper.lockSandboxVoucherClaim(eq("run-a-20260818"), eq(42L), eq("V-1")))
                .thenReturn(new AppGrowthEngagementMapper.SandboxVoucherClaim("SBX-GRANT-1", "AVAILABLE", "winner-key"));

        var result = service.claimVoucher(42L, "V-1", "home", "loser-key");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("VOUCHER_ALREADY_CLAIMED");
    }

    @Test
    void claimPromotesExistingPopupSeenRowAndThenReplaysSameKey() {
        when(mapper.lockSandboxClaimableVoucher(eq("run-a-20260818"), eq(42L), eq("V-1"), eq("home"), anyLong()))
                .thenReturn(new AppGrowthEngagementMapper.VoucherClaimDefinition("V-1", "all"));
        when(mapper.lockSandboxVoucherClaim(eq("run-a-20260818"), eq(42L), eq("V-1")))
                .thenReturn(new AppGrowthEngagementMapper.SandboxVoucherClaim(null, "UNCLAIMED", null))
                .thenReturn(new AppGrowthEngagementMapper.SandboxVoucherClaim("SBX-GRANT-1", "AVAILABLE", "claim-key"));
        when(mapper.claimExistingSandboxVoucher(eq("run-a-20260818"), eq(42L), eq("V-1"), anyString(), eq("claim-key")))
                .thenReturn(1);

        var first = service.claimVoucher(42L, "V-1", "home", "claim-key");
        var replay = service.claimVoucher(42L, "V-1", "home", "claim-key");

        assertThat(first.getCode()).isZero();
        assertThat(first.getData()).containsEntry("status", "AVAILABLE").containsEntry("replay", false);
        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData()).containsEntry("grantId", "SBX-GRANT-1").containsEntry("replay", true);
        verify(mapper).claimExistingSandboxVoucher(eq("run-a-20260818"), eq(42L), eq("V-1"), anyString(), eq("claim-key"));
    }

    @Test
    void explicitRequestRunIdMustMatchServerSandboxFence() {
        assertThatThrownBy(() -> service.voucherState(42L, "wrong-run-seven-closures"))
                .hasMessage("VOUCHER_SANDBOX_RUN_ID_MISMATCH");
    }
}
