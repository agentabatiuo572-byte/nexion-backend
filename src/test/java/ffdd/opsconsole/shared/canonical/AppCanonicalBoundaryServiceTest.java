package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppCanonicalBoundaryServiceTest {
    private final CanonicalStateMapper mapper = mock(CanonicalStateMapper.class);
    private final TamperDetectionPublisher publisher = mock(TamperDetectionPublisher.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppGrowthLifecyclePublisher growthLifecyclePublisher = mock(AppGrowthLifecyclePublisher.class);
    private final GrowthRhythmFacade growthRhythmFacade = mock(GrowthRhythmFacade.class);
    private final AppCanonicalBoundaryService service =
            new AppCanonicalBoundaryService(
                    mapper, publisher, idempotency, outbox, growthLifecyclePublisher, growthRhythmFacade);

    AppCanonicalBoundaryServiceTest() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ffdd.opsconsole.shared.api.ApiResult.class),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<ffdd.opsconsole.shared.api.ApiResult>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get());
        when(growthLifecyclePublisher.prepareVoucher(any(), any(), any(), any()))
                .thenReturn(AppGrowthLifecyclePublisher.VoucherRedemption.none());
        when(growthRhythmFacade.snapshot()).thenReturn(h1Snapshot("P3"));
        when(mapper.userCanonicalProfile(42L)).thenReturn(new CanonicalStateMapper.UserCanonicalProfile(
                new BigDecimal("500"), new BigDecimal("80"), LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    @Test
    void rejectsClientTamperAtAllTenPreviouslyMissingBusinessBoundaries() {
        when(mapper.lockUser(42L)).thenReturn(42L);
        when(mapper.findTrialState(42L)).thenReturn("CLAIMED");
        when(mapper.walletPaired(42L)).thenReturn(true);
        when(mapper.twoFactorEnabled(42L)).thenReturn(true);
        when(mapper.activeDeviceCount(42L)).thenReturn(3);
        when(mapper.deviceSlotCap()).thenReturn(3);
        when(mapper.consumeValidOtp(42L, "challenge-1", "123456")).thenReturn(0);

        assertRejected(service.trialEligibility(42L, "ELIGIBLE"), "TRIAL_STATE_CONFLICT");
        assertRejected(service.kycStatus(42L, false), "WALLET_PAIRING_CONFLICT");
        assertRejected(service.securityState(42L, false), "TWO_FACTOR_STATE_CONFLICT");
        assertRejected(service.productPhase(42L, "P6", true), "PRODUCT_PHASE_OVERRIDE_REJECTED");
        assertRejected(service.activateDevice(42L, 9L, 99, "device-key"), "DEVICE_SLOT_CAP_EXCEEDED");
        assertRejected(service.deviceEarnings(42L, true, true, new BigDecimal("999999")), "DEV_SEED_STATE_REJECTED");
        assertRejected(service.verifyOtp(42L, "challenge-1", "123456", true, "otp-key"), "OTP_VERIFICATION_REJECTED");
        verify(mapper).incrementOtpFailure(42L, "challenge-1");
        assertRejected(service.pushClientBill(42L, Map.of("amount", "100"), "bill-key"), "CLIENT_BILL_PUSH_REJECTED");
        assertRejected(service.createOrder(42L, "client-order-1", 8L, 1, "order-key"), "CLIENT_MINTED_ID_REJECTED");
        assertRejected(service.chargeTrial(42L, true, new BigDecimal("0.01"), "trial-key"), "CLIENT_CHARGE_OUTCOME_REJECTED");

        verify(publisher).publish(eq(42L), eq("free_trial_state"), anyString(), eq("/api/trial/eligibility"));
        verify(publisher).publish(eq(42L), eq("wallet_pairing"), anyString(), eq("/api/kyc/status"));
        verify(publisher).publish(eq(42L), eq("two_factor_state"), anyString(), eq("/api/security/state"));
        verify(publisher).publish(eq(42L), eq("product_phase_override"), anyString(), eq("/api/product/phase"));
        verify(publisher).publish(eq(42L), eq("device_slot_cap"), anyString(), eq("/api/devices/activate"));
        verify(publisher).publish(eq(42L), eq("dev_seed_state"), anyString(), eq("/api/devices/earnings"));
        verify(publisher).publish(eq(42L), eq("otp_verification"), anyString(), eq("/api/auth/otp/verify"));
        verify(publisher).publish(eq(42L), eq("bill_client_push"), anyString(), eq("/api/wallet/bills"));
        verify(publisher).publish(eq(42L), eq("client_minted_id"), anyString(), eq("/api/orders"));
        verify(publisher).publish(eq(42L), eq("charge_fail_rate"), anyString(), eq("/api/trial/charge"));
    }

    @Test
    void canonicalRequestsUseServerStateAndNeverTrustClientOwnedValues() {
        when(mapper.lockUser(42L)).thenReturn(42L);
        when(mapper.findTrialState(42L)).thenReturn(null);
        when(mapper.walletPaired(42L)).thenReturn(false);
        when(mapper.twoFactorEnabled(42L)).thenReturn(false);
        when(mapper.activeDeviceCount(42L)).thenReturn(1);
        when(mapper.deviceSlotCap()).thenReturn(3);
        when(mapper.activateOwnedDevice(42L, 9L, 3)).thenReturn(1);
        when(mapper.e3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.deviceEarnings(42L)).thenReturn(new CanonicalStateMapper.DeviceEarnings(
                new BigDecimal("12.5"), new BigDecimal("3.2")));
        when(mapper.ownedDevices(42L)).thenReturn(List.of(new CanonicalStateMapper.OwnedDevice(
                9L, "DEV-9", "NexionBox S1", "BOX", "STELLARBOX-S1", "INACTIVE",
                null, LocalDateTime.of(2026, 7, 1, 0, 0), BigDecimal.ZERO, BigDecimal.ZERO,
                "RTX 4090", 96, new BigDecimal("1200"), "Singapore")));
        when(mapper.consumeValidOtp(42L, "challenge-1", "123456")).thenReturn(1);
        when(mapper.lockProduct(8L, null)).thenReturn(
                new CanonicalStateMapper.ProductStock(8L, "BOX-8", new BigDecimal("1299"), 4));
        when(mapper.decrementProductStock(8L, 1)).thenReturn(1);
        when(mapper.insertOrder(eq(42L), anyString(), eq(8L), eq(1),
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class))).thenReturn(1);
        when(mapper.userEventAttribution(42L)).thenReturn(
                new CanonicalStateMapper.UserEventAttribution("P3", 5, "2026-W06"));
        when(mapper.lockLatestChargeableTrial(42L)).thenReturn(new CanonicalStateMapper.TrialClaim(
                7L, "TRIAL-7", "ACTIVE", new BigDecimal("100"), new BigDecimal("10")));
        when(mapper.lockWalletUsdt(42L)).thenReturn(new BigDecimal("500"));
        when(mapper.trialChargeFailRate()).thenReturn(BigDecimal.ZERO);
        when(mapper.debitWalletUsdt(42L, new BigDecimal("90"))).thenReturn(1);
        when(mapper.insertTrialChargeLedger(42L, "TRIAL-7", new BigDecimal("90"), new BigDecimal("410"))).thenReturn(1);
        when(mapper.markTrialChargeAttempt(eq(7L), anyString())).thenReturn(1);

        assertThat(service.trialEligibility(42L, null).getCode()).isZero();
        assertThat(service.kycStatus(42L, null).getCode()).isZero();
        assertThat(service.securityState(42L, null).getCode()).isZero();
        var productPhase = service.productPhase(42L, null, false);
        assertThat(productPhase.getCode()).isZero();
        assertThat(productPhase.getData())
                .containsEntry("phase", "P3")
                .containsEntry("devOverrideAllowed", false)
                .containsEntry("source", "H1_GROWTH_RHYTHM");
        @SuppressWarnings("unchecked")
        Map<String, Object> rhythm = (Map<String, Object>) productPhase.getData().get("rhythm");
        assertThat(rhythm)
                .containsEntry("sourceDomain", "H1")
                .containsEntry("totalMonths", 12)
                .containsEntry("currentMonth", 7)
                .containsEntry("currentPhase", "P3")
                .containsEntry("phaseProgressPct", 58);
        @SuppressWarnings("unchecked")
        Map<String, Object> dials = (Map<String, Object>) productPhase.getData().get("dials");
        assertThat(dials)
                .containsEntry("withdrawPenaltyFeeRate", new BigDecimal("20"))
                .containsEntry("binaryDailyCap", new BigDecimal("2000"))
                .containsEntry("complianceHoldEnabled", false);
        assertThat(service.activateDevice(42L, 9L, null, "device-key").getCode()).isZero();
        var deviceState = service.deviceEarnings(42L, false, false, null);
        assertThat(deviceState.getCode()).isZero();
        assertThat((List<?>) deviceState.getData().get("devices")).hasSize(1);
        assertThat(service.verifyOtp(42L, "challenge-1", "123456", null, "otp-key").getCode()).isZero();
        assertThat(service.createOrder(42L, null, 8L, 1, "order-key").getCode()).isZero();
        assertThat(service.chargeTrial(42L, null, null, "trial-key").getCode()).isZero();
        verify(mapper).debitWalletUsdt(42L, new BigDecimal("90"));
        verify(mapper).insertTrialChargeLedger(42L, "TRIAL-7", new BigDecimal("90"), new BigDecimal("410"));
        verify(outbox).publishUserEvent(eq("ORDER"), anyString(), eq("checkout.started"),
                eq(42L), eq("P3"), eq(5), eq("2026-W06"), any());

        verify(publisher, never()).publish(eq(42L), anyString(), anyString(), anyString());
    }

    @Test
    void returnsUserScopedCanonicalOrderHistoryIncludingCompletedTradeinReceipt() {
        when(mapper.userOrders(42L)).thenReturn(List.of(new CanonicalStateMapper.UserOrder(
                "TIO-1", 5L, "stellarbox-pro-v2", "StellarBox Pro v2", 1,
                new BigDecimal("2639"), new BigDecimal("974.25"), new BigDecimal("1664.75"),
                "USDT_WALLET", "PAID", "COMPLETED", "ACTIVATED", "TRADE_IN",
                LocalDateTime.of(2026, 7, 21, 5, 29), LocalDateTime.of(2026, 7, 21, 5, 29),
                LocalDateTime.of(2026, 7, 21, 5, 29), "Singapore", "TIN-1", 29L, 30L, "DEV-30")));

        var result = service.orders(42L);

        assertThat(result.getCode()).isZero();
        @SuppressWarnings("unchecked")
        var orders = (List<Map<String, Object>>) result.getData().get("orders");
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0))
                .containsEntry("orderNo", "TIO-1")
                .containsEntry("tradeinNo", "TIN-1")
                .containsEntry("targetDeviceInstanceNo", "DEV-30")
                .containsEntry("canonicalStatus", "activated")
                .containsEntry("amountUsdt", new BigDecimal("1664.75"));
        verify(mapper).userOrders(42L);
    }

    @Test
    void appliesThePersistedE3CapacityScheduleToTheUserDeviceProjection() {
        when(mapper.e3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.ownedDevices(42L)).thenReturn(List.of(new CanonicalStateMapper.OwnedDevice(
                9L, "DEV-9", "NexionBox S1", "BOX", "STELLARBOX-S1", "ACTIVE",
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMonths(5).minusMinutes(1),
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMonths(5).minusMinutes(1),
                new BigDecimal("100"), new BigDecimal("50"), "RTX 4090", 96,
                new BigDecimal("1200"), "Singapore")));

        var result = service.deviceEarnings(42L, false, false, null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("dailyUsdt", new BigDecimal("80.643786"));
        assertThat(result.getData()).containsEntry("dailyNex", new BigDecimal("40.321893"));
        assertThat(result.getData()).containsEntry("walletUsdt", new BigDecimal("500.000000"));
        assertThat(result.getData()).containsEntry("walletNex", new BigDecimal("80.000000"));
        assertThat(result.getData()).containsEntry("userJoinedAt", 1767196800000L);
        assertThat(result.getData().get("serverNow")).isInstanceOf(Long.class);
        @SuppressWarnings("unchecked")
        var devices = (List<Map<String, Object>>) result.getData().get("devices");
        assertThat(devices.get(0))
                .containsEntry("capacityPct", new BigDecimal("80.643786"))
                .containsEntry("capacityAgeMonths", 5)
                .containsEntry("dailyUsdt", new BigDecimal("80.643786"))
                .containsEntry("capacitySubsidized", false)
                .containsEntry("capacitySubsidyDays", 30);
        assertThat(result.getData()).containsKey("capacitySchedule");
    }

    @Test
    void failsClosedWhenTheE3CapacityScheduleIsIncomplete() {
        when(mapper.e3CapacityConfig()).thenReturn(List.of(
                new CanonicalStateMapper.E3CapacityConfig("capacityBand1DeltaPct", "-3")));

        var result = service.deviceEarnings(42L, false, false, null);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("E3_CAPACITY_CONFIG_INCOMPLETE");
        verify(mapper, never()).ownedDevices(42L);
    }

    @Test
    void authoritativeH1RhythmWinsOverTheLegacyPhaseProjection() {
        when(mapper.currentPhase()).thenReturn("P6");

        var result = service.productPhase(42L, "P3", false);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("phase", "P3");
        verify(mapper, never()).currentPhase();
    }

    @Test
    void failsClosedWhenTheAuthoritativeH1RhythmIsIncomplete() {
        when(growthRhythmFacade.snapshot()).thenReturn(new GrowthRhythmSnapshot(
                12, 0, "", 0, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("20"), 30, new BigDecimal("2000"), BigDecimal.ONE,
                false, List.of()));

        var result = service.productPhase(42L, null, false);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("H1_RHYTHM_UNAVAILABLE");
        verify(publisher, never()).publish(eq(42L), anyString(), anyString(), anyString());
    }

    @Test
    void failsClosedWhenOneAuthoritativeH1DialIsMissing() {
        when(growthRhythmFacade.snapshot()).thenReturn(new GrowthRhythmSnapshot(
                12, 7, "P3", 58, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("20"), 30, new BigDecimal("2000"), BigDecimal.ONE,
                false, List.of("H1.rhythm.currentMonth", "growth.phase.*")));

        var result = service.productPhase(42L, null, false);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("H1_RHYTHM_UNAVAILABLE");
    }

    @Test
    void serializesMutationsOnTheUserBeforeCreatingAnIdempotencyRecord() {
        when(mapper.lockUser(42L)).thenReturn(42L);
        when(mapper.deviceSlotCap()).thenReturn(3);
        when(mapper.activeDeviceCount(42L)).thenReturn(0);
        when(mapper.activateOwnedDevice(42L, 9L, 3)).thenReturn(1);

        service.activateDevice(42L, 9L, null, "device-key");

        var order = inOrder(mapper, idempotency);
        order.verify(mapper).lockUser(42L);
        order.verify(idempotency).execute(anyString(), eq("device-key"), anyString(),
                eq(ffdd.opsconsole.shared.api.ApiResult.class),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<ffdd.opsconsole.shared.api.ApiResult>>any());
    }

    private void assertRejected(ffdd.opsconsole.shared.api.ApiResult<?> result, String code) {
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo(code);
    }

    private List<CanonicalStateMapper.E3CapacityConfig> capacityConfig() {
        return Map.ofEntries(
                        Map.entry("capacityBand1DeltaPct", "-3"),
                        Map.entry("capacityBand2DeltaPct", "-6"),
                        Map.entry("capacityBand3DeltaPct", "-23.7"),
                        Map.entry("stageEarlyEnd", "3"),
                        Map.entry("stageMidEnd", "8"),
                        Map.entry("cycleMonths", "12"),
                        Map.entry("capacityFloorPct", "22"),
                        Map.entry("capacitySubsidyDays", "30"),
                        Map.entry("capacityApplyToPhone", "false"),
                        Map.entry("capacityApplyToCloudShare", "false"),
                        Map.entry("capacityApplyToPcGpu", "true"),
                        Map.entry("capacityApplyToS1", "true"),
                        Map.entry("capacityApplyToPro", "true"),
                        Map.entry("capacityApplyToProV2", "true"),
                        Map.entry("capacityApplyToRackP1", "true"),
                        Map.entry("capacityApplyToRackP2", "true"),
                        Map.entry("taskLockS1", "15"),
                        Map.entry("taskLockPro", "30"),
                        Map.entry("taskLockRack", "80"))
                .entrySet().stream()
                .map(entry -> new CanonicalStateMapper.E3CapacityConfig(entry.getKey(), entry.getValue()))
                .toList();
    }

    private GrowthRhythmSnapshot h1Snapshot(String phase) {
        return new GrowthRhythmSnapshot(
                12, 7, phase, 58,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("20"), 30, new BigDecimal("2000"), BigDecimal.ONE,
                false, List.of("H1.rhythm.currentMonth", "growth.phase.*"));
    }
}
