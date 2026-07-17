package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppCanonicalBoundaryServiceTest {
    private final CanonicalStateMapper mapper = mock(CanonicalStateMapper.class);
    private final TamperDetectionPublisher publisher = mock(TamperDetectionPublisher.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AppCanonicalBoundaryService service = new AppCanonicalBoundaryService(mapper, publisher, idempotency);

    AppCanonicalBoundaryServiceTest() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ffdd.opsconsole.shared.api.ApiResult.class),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<ffdd.opsconsole.shared.api.ApiResult>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void rejectsClientTamperAtAllTenPreviouslyMissingBusinessBoundaries() {
        when(mapper.lockUser(42L)).thenReturn(42L);
        when(mapper.findTrialState(42L)).thenReturn("CLAIMED");
        when(mapper.walletPaired(42L)).thenReturn(true);
        when(mapper.twoFactorEnabled(42L)).thenReturn(true);
        when(mapper.currentPhase()).thenReturn("P3");
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
        when(mapper.currentPhase()).thenReturn("P2");
        when(mapper.activeDeviceCount(42L)).thenReturn(1);
        when(mapper.deviceSlotCap()).thenReturn(3);
        when(mapper.activateOwnedDevice(42L, 9L, 3)).thenReturn(1);
        when(mapper.deviceEarnings(42L)).thenReturn(new CanonicalStateMapper.DeviceEarnings(
                new BigDecimal("12.5"), new BigDecimal("3.2")));
        when(mapper.ownedDevices(42L)).thenReturn(List.of(new CanonicalStateMapper.OwnedDevice(
                9L, "DEV-9", "NexionBox S1", "BOX", "STELLARBOX-S1", "INACTIVE",
                null, LocalDateTime.of(2026, 7, 1, 0, 0), BigDecimal.ZERO, BigDecimal.ZERO,
                "RTX 4090", 96, new BigDecimal("1200"), "Singapore")));
        when(mapper.consumeValidOtp(42L, "challenge-1", "123456")).thenReturn(1);
        when(mapper.lockProduct(8L, null)).thenReturn(new CanonicalStateMapper.ProductStock(8L, new BigDecimal("1299"), 4));
        when(mapper.decrementProductStock(8L, 1)).thenReturn(1);
        when(mapper.insertOrder(eq(42L), anyString(), eq(8L), eq(1), eq(new BigDecimal("1299")))).thenReturn(1);
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
        assertThat(service.productPhase(42L, null, false).getCode()).isZero();
        assertThat(service.activateDevice(42L, 9L, null, "device-key").getCode()).isZero();
        var deviceState = service.deviceEarnings(42L, false, false, null);
        assertThat(deviceState.getCode()).isZero();
        assertThat((List<?>) deviceState.getData().get("devices")).hasSize(1);
        assertThat(service.verifyOtp(42L, "challenge-1", "123456", null, "otp-key").getCode()).isZero();
        assertThat(service.createOrder(42L, null, 8L, 1, "order-key").getCode()).isZero();
        assertThat(service.chargeTrial(42L, null, null, "trial-key").getCode()).isZero();
        verify(mapper).debitWalletUsdt(42L, new BigDecimal("90"));
        verify(mapper).insertTrialChargeLedger(42L, "TRIAL-7", new BigDecimal("90"), new BigDecimal("410"));

        verify(publisher, never()).publish(eq(42L), anyString(), anyString(), anyString());
    }

    @Test
    void normalizesLegacyNumericPhaseToThePublicPhaseContract() {
        when(mapper.currentPhase()).thenReturn("3");

        var result = service.productPhase(42L, "P3", false);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("phase", "P3");
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
}
