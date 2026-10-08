package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.RecoveryEntry;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.RecoveryWallet;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EarningsSourceRecoveryServiceTest {
    private final EarningsReleaseMapper mapper = mock(EarningsReleaseMapper.class);
    private final FundsSandboxProfileGuard profile = mock(FundsSandboxProfileGuard.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EarningsReleaseService service = new EarningsReleaseService(mapper,
            mock(RiskReleaseParamsService.class), idempotency, audit, profile);

    @BeforeEach void defaults() {
        when(profile.isStrictProductionRuntime()).thenReturn(true);
        when(mapper.lockRecoveryUser(7L, 0)).thenReturn(7L);
        when(mapper.lockRecoveryWallet(7L, 0)).thenReturn(new RecoveryWallet(70L, bd("100"), bd("100"), bd("0"), bd("0")));
        when(mapper.debitRecoveredReward(anyLong(), anyString(), any(), anyInt())).thenReturn(1);
        when(mapper.addRecoveredAmount(anyString(), any(), any())).thenReturn(1);
        when(mapper.insertRecovery(any())).thenReturn(1);
        when(idempotency.executeRetained(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
    }

    @ParameterizedTest @CsvSource({"USDT,withdrawable", "USDT,pending_review", "USDT,bonus_locked",
            "NEX,withdrawable", "NEX,pending_review", "NEX,bonus_locked"})
    void recoversOnlyOriginalAssetAndKeepsEveryRiskBucket(String asset, String bucket) {
        entry(asset, bucket, "0", "0");
        var result = service.recoverReward(request(asset, "10"), "key-1");
        assertThat(result.recovered()).isEqualByComparingTo("10");
        assertThat(result.outstanding()).isZero();
        assertThat(result.bucket()).isEqualTo(bucket);
        assertThat(result.balanceAfter()).isEqualByComparingTo("90");
        verify(mapper).debitRecoveredReward(eq(7L), eq(asset), argThat(v -> v.compareTo(bd("10")) == 0), eq(0));
        verify(mapper, never()).release(anyString(), anyString());
        var order = inOrder(mapper);
        order.verify(mapper).lockRecoveryUser(7L, 0);
        order.verify(mapper).lockRecoveryWallet(7L, 0);
        order.verify(mapper).lockRecoveryEntry("ER-1");
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test void partialConsumptionCannotBeRefilledByPrincipalOrOtherRewards() {
        entry("USDT", "pending_review", "0", "100");
        when(mapper.lockRecoveryWallet(7L, 0)).thenReturn(new RecoveryWallet(70L, bd("999"), bd("555"), bd("103"), bd("900")));
        var result = service.recoverReward(request("USDT", "10"), "partial");
        assertThat(result.recovered()).isEqualByComparingTo("7");
        assertThat(result.outstanding()).isEqualByComparingTo("3");
        assertThat(result.remainingEntitlement()).isEqualByComparingTo("3");
    }

    @Test void repeatedLegitimateRecoveryDoesNotSubtractOwnRecoveryTwice() {
        entry("NEX", "withdrawable", "3", "100");
        when(mapper.lockRecoveryWallet(7L, 0)).thenReturn(new RecoveryWallet(70L, bd("0"), bd("7"), bd("999"), bd("103")));
        var result = service.recoverReward(request("NEX", "7"), "second");
        assertThat(result.recovered()).isEqualByComparingTo("7");
        assertThat(result.cumulativeRecovered()).isEqualByComparingTo("10");
        assertThat(result.remainingEntitlement()).isZero();
    }

    @Test void fullyConsumedSourceCannotReviveAfterLaterCredits() {
        entry("USDT", "withdrawable", "0", "0");
        when(mapper.lockRecoveryWallet(7L, 0)).thenReturn(new RecoveryWallet(70L, bd("500"), bd("500"), bd("10"), bd("0")));
        var result = service.recoverReward(request("USDT", "10"), "spent");
        assertThat(result.recovered()).isZero();
        assertThat(result.outstanding()).isEqualByComparingTo("10");
        verify(mapper, never()).debitRecoveredReward(anyLong(), anyString(), any(), anyInt());
    }

    @Test void legacyEntryWithoutSourceEvidenceCannotTakeAnyWalletFunds() {
        entry("USDT", "bonus_locked", "0", null);
        var result = service.recoverReward(request("USDT", "10"), "legacy");
        assertThat(result.recovered()).isZero();
        assertThat(result.evidenceStatus()).isEqualTo("LEGACY_UNPROVEN");
        assertThat(result.outstanding()).isEqualByComparingTo("10");
    }

    @Test void ownershipAssetSourceAndEnvironmentMustMatchOriginalEntry() {
        entry("NEX", "withdrawable", "0", "0");
        assertThatThrownBy(() -> service.recoverReward(request("USDT", "10"), "wrong-asset"))
                .hasMessage("EARNINGS_RECOVERY_SOURCE_MISMATCH");
        verify(mapper, never()).debitRecoveredReward(anyLong(), anyString(), any(), anyInt());
    }

    @Test void remainingEntitlementAndExactPrecisionAreEnforced() {
        entry("USDT", "withdrawable", "8", "0");
        assertThatThrownBy(() -> service.recoverReward(request("USDT", "3"), "excess"))
                .hasMessage("EARNINGS_RECOVERY_EXCEEDS_ORIGINAL");
        for (String amount : List.of("0", "-1", "0.0000001", "1000000000000")) {
            assertThatThrownBy(() -> service.recoverReward(request("USDT", amount), "invalid"))
                    .hasMessage("EARNINGS_RECOVERY_REQUEST_INVALID");
        }
    }

    @Test void failedWalletOrAuditWriteMustAbortTheCommand() {
        entry("USDT", "withdrawable", "0", "0");
        when(mapper.debitRecoveredReward(anyLong(), anyString(), any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.recoverReward(request("USDT", "10"), "conflict"))
                .hasMessage("EARNINGS_RECOVERY_WALLET_CONFLICT");
        verify(mapper, never()).insertRecovery(any());
    }

    @Test void oldReversalOwnersAndMismatchedSourceEnvironmentCannotUseThisPrimitive() {
        for (String type : List.of("DIRECT_REFERRAL", "H8_REFERRAL", "staking_interest", "MOCK_PROMOTION_REWARD")) {
            var request = new EarningsReleaseService.RewardRecoveryRequest(7L,"ER-1",type,"OB-1","USDT",
                    "PRODUCTION",bd("1"),"approved correction basis","executor");
            assertThatThrownBy(() -> service.recoverReward(request,"wrong-owner"))
                    .hasMessage("EARNINGS_RECOVERY_REQUEST_INVALID");
        }
        verify(mapper,never()).lockRecoveryUser(anyLong(),anyInt());
    }

    @Test void replacedWalletAndRolledBackCounterCannotForgeSourceEvidence() {
        entry("USDT","withdrawable","0","10");
        when(mapper.lockRecoveryWallet(7L,0)).thenReturn(new RecoveryWallet(71L,bd("100"),bd("100"),bd("10"),bd("0")));
        assertThatThrownBy(() -> service.recoverReward(request("USDT","10"),"replaced-wallet"))
                .hasMessage("EARNINGS_RECOVERY_EVIDENCE_INVALID");
        when(mapper.lockRecoveryWallet(7L,0)).thenReturn(new RecoveryWallet(70L,bd("100"),bd("100"),bd("9"),bd("0")));
        assertThatThrownBy(() -> service.recoverReward(request("USDT","10"),"counter-rollback"))
                .hasMessage("EARNINGS_RECOVERY_EVIDENCE_INVALID");
    }

    private void entry(String asset, String bucket, String recovered, String baseline) {
        when(mapper.lockRecoveryEntry("ER-1")).thenReturn(new RecoveryEntry("ER-1", 7L,
                "PROMOTION_REWARD", "OB-1", asset, bd("10"), bucket, "ACTIVE", "PRODUCTION", 0,
                bd(recovered), baseline == null ? null : bd(baseline), baseline == null ? null : 70L));
    }

    private EarningsReleaseService.RewardRecoveryRequest request(String asset, String amount) {
        return new EarningsReleaseService.RewardRecoveryRequest(7L, "ER-1", "PROMOTION_REWARD", "OB-1", asset,
                "PRODUCTION", bd(amount), "approved whole-order refund", "promotion-executor");
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
