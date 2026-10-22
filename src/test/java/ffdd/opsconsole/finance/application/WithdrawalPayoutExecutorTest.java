package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalPayoutExecutorTest {

    @Test void stopAfterUnlockedEligibilityButBeforeGateAdmissionNeverCreatesOrOrphans(){
        var mapper=mock(WithdrawalPayoutMapper.class);var router=mock(CregisGatewayRouter.class);
        var gateway=mock(CregisGateway.class);var finalizer=mock(WithdrawalPayoutFinalizer.class);var guard=mock(WithdrawalPayoutDispatchGuard.class);
        var candidate=row("REVIEW_PASSED");when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);when(router.provider()).thenReturn(gateway);
        when(mapper.claimable(any(),eq(50))).thenReturn(List.of(candidate));
        // The fast eligibility read was true, but the guarded current check sees a committed stop.
        when(guard.begin(eq(candidate),any())).thenReturn(null).thenThrow(new IllegalStateException("gate unavailable"));
        var executor=new WithdrawalPayoutExecutor(mapper,router,finalizer,mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class),payouts(),guard);
        executor.process();executor.process();
        verify(gateway,never()).createPayout(any());verify(mapper,never()).claimNewPayout(any(),any(),any());
        verify(finalizer,never()).orphaned(any(),any(),any(),any());verify(finalizer,never()).retry(any(),any());
    }
    @Test void committedIntentSendsOnceEvenWhenStopCommitsImmediatelyAfterAdmission(){
        var mapper=mock(WithdrawalPayoutMapper.class);var router=mock(CregisGatewayRouter.class);var gateway=mock(CregisGateway.class);
        var finalizer=mock(WithdrawalPayoutFinalizer.class);var guard=mock(WithdrawalPayoutDispatchGuard.class);var control=payouts();
        var candidate=row("REVIEW_PASSED");when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);when(router.provider()).thenReturn(gateway);
        when(router.payoutCallbackUrl()).thenReturn("https://callback.invalid/payout");when(mapper.claimable(any(),eq(50))).thenReturn(List.of(candidate));
        when(guard.begin(eq(candidate),any())).thenAnswer(call->{when(control.enabled()).thenReturn(false);return candidate;});
        when(gateway.createPayout(any())).thenReturn(new CregisGateway.PayoutSubmission(9001,candidate.providerIdempotencyKey()));
        var executor=new WithdrawalPayoutExecutor(mapper,router,finalizer,mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class),control,guard);
        executor.process();executor.process();
        verify(gateway).createPayout(any());verify(finalizer).submitted(candidate,9001,"provider");
        verify(mapper,never()).markProviderIntent(any(),any());verify(finalizer,never()).retry(any(),any());
    }

    @Test
    void sandboxTickNeverTouchesRealWithdrawalOrdersOrWallets() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(mapper.claimable(any(LocalDateTime.class), anyInt())).thenReturn(List.of(row));
        when(mapper.claimNewPayout(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(router.mode()).thenReturn(CregisProperties.Mode.LOCAL_SANDBOX);
        when(router.isolatedLocalSandbox()).thenReturn(gateway);
        when(gateway.createPayout(any())).thenReturn(new CregisGateway.PayoutSubmission(9001L, row.withdrawalNo()));
        when(finalizer.submitted(row, 9001L, "mock")).thenReturn(true);

        new WithdrawalPayoutExecutor(mapper, router, finalizer, mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class), payouts(),mock(WithdrawalPayoutDispatchGuard.class)).process();

        verify(gateway, never()).createPayout(any());
        verify(mapper, never()).claimNewPayout(any(), any(), any());
        verify(finalizer, never()).submitted(any(), anyLong(), any());
        verify(finalizer, never()).completeSandbox(any(), anyLong());
    }

    @Test
    void disabledProductionFailsClosedWithoutAutomaticSandboxFallback() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(mapper.incompleteSandboxSubmissions(anyInt())).thenReturn(List.of());
        when(mapper.claimable(any(), anyInt())).thenReturn(List.of(row));
        when(mapper.claimNewPayout(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(router.mode()).thenReturn(CregisProperties.Mode.DISABLED);

        new WithdrawalPayoutExecutor(mapper, router, finalizer, mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class), payouts(),mock(WithdrawalPayoutDispatchGuard.class)).process();

        verify(router, never()).isolatedLocalSandbox();
        verify(router, never()).provider();
        verify(finalizer).retry(row, "CREGIS_PROVIDER_DISABLED");
        verify(finalizer, never()).completeSandbox(any(), anyLong());
    }

    @Test
    void unknownSubmissionTimeoutMovesToDurableManualReconciliationWithoutAutomaticResend() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);
        when(router.payoutEnabled()).thenReturn(true);
        when(router.provider()).thenReturn(gateway);
        when(router.payoutCallbackUrl()).thenReturn("https://callback.invalid/payout");
        when(mapper.claimable(any(), anyInt())).thenReturn(List.of(row));
        when(mapper.claimNewPayout(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(gateway.createPayout(any())).thenThrow(new RuntimeException("timeout after send"));
        when(mapper.markProviderIntent(eq(row.withdrawalNo()),any())).thenReturn(1);

        var guard=mock(WithdrawalPayoutDispatchGuard.class);when(guard.begin(eq(row),any())).thenReturn(row);
        new WithdrawalPayoutExecutor(mapper, router, finalizer, mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class), payouts(),guard).process();

        verify(gateway).createPayout(any());
        verify(finalizer).orphaned(row, null, "provider", "CREGIS_SUBMISSION_UNKNOWN");
        verify(finalizer, never()).retry(eq(row), any());
        verify(finalizer, never()).submitted(any(), anyLong(), any());
    }

    @Test
    void providerPayinOnlyDoesNotSubmitCryptoPayout() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);
        when(mapper.claimable(any(), anyInt())).thenReturn(List.of(row));
        when(mapper.claimNewPayout(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);

        var closed = payouts();
        when(closed.enabled()).thenReturn(false);
        new WithdrawalPayoutExecutor(mapper, router, finalizer, mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class), closed,mock(WithdrawalPayoutDispatchGuard.class)).process();

        verify(router, never()).provider();
        verify(mapper, never()).claimNewPayout(any(),any(),any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={14,15})
    void vendorSuccessRequiresCanonicalFifteenConfirmationsEvenWithNewPayoutsClosed(int confirmations) {
        var mapper = mock(WithdrawalPayoutMapper.class);
        var router = mock(CregisGatewayRouter.class);
        var gateway = mock(CregisGateway.class);
        var finalizer = mock(WithdrawalPayoutFinalizer.class);
        var chain = mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class);
        var control = payouts();
        when(control.enabled()).thenReturn(false);
        var base = row("SENT");
        var sent = new WithdrawalPayoutMapper.PayoutRow(base.withdrawalNo(),base.userId(),base.chain(),
                base.targetAddress(),base.amount(),base.netReceive(),base.nexBurned(),"SENT",base.payoutDueAt(),
                9001L,base.providerIdempotencyKey(),"provider",1);
        String tx = "0x" + "a".repeat(64);
        var proof = new ffdd.opsconsole.finance.cregis.BscDepositProof.Proof(2,100,"0x"+"b".repeat(64),confirmations);
        when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);
        when(router.provider()).thenReturn(gateway);
        when(mapper.incompleteProviderSubmissions("",50)).thenReturn(List.of(sent));
        when(gateway.queryPayout(any())).thenReturn(new CregisGateway.PayoutOrder(9001L,"USDT","2510",
                ffdd.opsconsole.finance.cregis.CregisConstants.USDT_BEP20_TOKEN_ID,sent.targetAddress(),
                sent.netReceive(),sent.providerIdempotencyKey(),CregisGateway.PayoutStatus.SUCCEEDED,tx));
        when(chain.verifyPayout(tx,sent.targetAddress(),sent.netReceive())).thenReturn(java.util.Optional.of(proof));
        new WithdrawalPayoutExecutor(mapper,router,finalizer,chain,control,mock(WithdrawalPayoutDispatchGuard.class)).process();
        if (confirmations==15) verify(finalizer).confirmProviderChain(sent,tx,proof,15);
        else verify(finalizer,never()).confirmProviderChain(any(),any(),any(),anyInt());
        verify(mapper,never()).claimNewPayout(any(),any(),any());
        verify(gateway,never()).createPayout(any());
    }

    @Test
    void expiredCreateIsUnknownAndNeverSentAgain() {
        var mapper = mock(WithdrawalPayoutMapper.class);
        var router = mock(CregisGatewayRouter.class);
        var finalizer = mock(WithdrawalPayoutFinalizer.class);
        when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);
        var expired = row("PROCESSING");
        when(mapper.expiredProviderAttempts(any(),eq(50))).thenReturn(List.of(expired));
        new WithdrawalPayoutExecutor(mapper,router,finalizer,mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class),payouts(),mock(WithdrawalPayoutDispatchGuard.class)).process();
        verify(finalizer).orphaned(expired,null,"provider","CREGIS_SUBMISSION_UNKNOWN");
        verify(router,never()).provider();
    }

    private ffdd.opsconsole.finance.cregis.CregisPayoutControl payouts() {
        var control = mock(ffdd.opsconsole.finance.cregis.CregisPayoutControl.class);
        when(control.enabled()).thenReturn(true);
        when(control.requiredConfirmations()).thenReturn(15);
        return control;
    }

    @Test void pendingFirstFiftyDoNotStarveTheFiftyFirstAndCursorWraps() {
        var mapper = mock(WithdrawalPayoutMapper.class);
        var router = mock(CregisGatewayRouter.class);
        var gateway = mock(CregisGateway.class);
        var finalizer = mock(WithdrawalPayoutFinalizer.class);
        var pending = java.util.stream.IntStream.rangeClosed(1,51).mapToObj(i -> {
            var base = row("SENT");
            String no = "WD-" + String.format("%03d",i);
            return new WithdrawalPayoutMapper.PayoutRow(no,42L,base.chain(),base.targetAddress(),
                    base.amount(),base.netReceive(),base.nexBurned(),"SENT",null,(long)i,no,"provider",1);
        }).toList();
        when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);
        when(router.provider()).thenReturn(gateway);
        when(mapper.incompleteProviderSubmissions("",50)).thenReturn(pending.subList(0,50));
        when(mapper.incompleteProviderSubmissions("WD-050",50)).thenReturn(pending.subList(50,51));
        when(mapper.incompleteProviderSubmissions("WD-051",50)).thenReturn(List.of());
        when(gateway.queryPayout(any())).thenAnswer(call -> {
            var query = call.getArgument(0,CregisGateway.PayoutQuery.class);
            return new CregisGateway.PayoutOrder(query.cid(),ffdd.opsconsole.finance.cregis.CregisConstants.USDT_BEP20_CURRENCY,
                    "2510",ffdd.opsconsole.finance.cregis.CregisConstants.USDT_BEP20_TOKEN_ID,query.address(),query.amount(),
                    query.thirdPartyId(),CregisGateway.PayoutStatus.AWAITING_AUDIT,null);
        });
        var control = payouts(); when(control.enabled()).thenReturn(false);
        var executor = new WithdrawalPayoutExecutor(mapper,router,finalizer,
                mock(ffdd.opsconsole.finance.cregis.BscDepositProof.class),control,mock(WithdrawalPayoutDispatchGuard.class));
        executor.process();
        verify(gateway,never()).queryPayout(argThat(q -> q.cid()==51));
        executor.process();
        verify(gateway).queryPayout(argThat(q -> q.cid()==51));
        executor.process();
        verify(gateway,org.mockito.Mockito.times(2)).queryPayout(argThat(q -> q.cid()==1));
        verify(gateway,never()).createPayout(any());
        verify(finalizer,never()).terminal(any(),anyLong(),any(),any(),any(),any(),any(),any());
    }

    @Test void realExecutorRegistersForPublicTestWhileLocalDevSimulatorRemainsIsolated() {
        var condition = new WithdrawalPayoutExecutor.RuntimeEnabled();
        var context = mock(org.springframework.context.annotation.ConditionContext.class);
        var env = new org.springframework.mock.env.MockEnvironment(); env.setActiveProfiles("dev");
        when(context.getEnvironment()).thenReturn(env);
        var metadata = mock(org.springframework.core.type.AnnotatedTypeMetadata.class);
        assertThat(condition.matches(context,metadata)).isFalse();
        env.setProperty("nexion.deployment.public-test","true");
        assertThat(condition.matches(context,metadata)).isTrue();
        env.setProperty("nexion.deployment.public-test","false"); env.setActiveProfiles("prod");
        assertThat(condition.matches(context,metadata)).isTrue();
    }

    private WithdrawalPayoutMapper.PayoutRow row(String status) {
        return new WithdrawalPayoutMapper.PayoutRow(
                "WD-PAYOUT-1", 42L, "USDT-BEP20", "0x1111111111111111111111111111111111111111",
                new BigDecimal("10.000000"), new BigDecimal("9.000000"), BigDecimal.ZERO,
                status, LocalDateTime.now().plusHours(24), null, "WD-PAYOUT-1", null, 0);
    }
}
