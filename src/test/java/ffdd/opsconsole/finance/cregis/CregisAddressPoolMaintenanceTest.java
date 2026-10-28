package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CregisAddressPoolMaintenanceTest {
    static final String CHAIN=CregisConstants.BSC_CHAIN_ID;
    static final String ADDRESS="0x"+"a".repeat(40);
    final CregisProperties config=new CregisProperties();
    final CregisDepositMapper db=mock(CregisDepositMapper.class);
    final CregisGateway provider=mock(CregisGateway.class);
    final BscDepositProof chain=mock(BscDepositProof.class);
    final java.util.concurrent.atomic.AtomicInteger available=new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<Map<String,Object>> policy=new java.util.concurrent.atomic.AtomicReference<>(Map.of(
        "enabled",1,"target",1000,"lowWatermark",300,"batchSize",50,"concurrency",2,"version",0L));
    final java.util.List<Map<String,Object>> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
    final CregisDepositService service;
    @org.junit.jupiter.api.AfterEach void close(){service.closePoolWorkers();}
    CregisAddressPoolMaintenanceTest() {
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);
        config.setDepositEnabled(true);config.setDepositCreditEnabled(true);
        var router=mock(CregisGatewayRouter.class);when(router.provider()).thenReturn(provider);
        when(router.depositCallbackUrl()).thenReturn("https://test.example/callback");
        var tx=mock(PlatformTransactionManager.class);var mutex=new java.util.concurrent.locks.ReentrantLock();
        when(tx.getTransaction(any())).thenAnswer(i->{mutex.lock();return new SimpleTransactionStatus();});
        doAnswer(i->{mutex.unlock();return null;}).when(tx).commit(any());
        doAnswer(i->{mutex.unlock();return null;}).when(tx).rollback(any());
        var gate=Map.<String,Object>of("state","IDLE","assignEnabled",1,"creditEnabled",1);
        when(db.provisionGate()).thenReturn(gate);when(db.lockProvisionGate()).thenReturn(gate);
        when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
        when(db.verifyingCandidate(88,CHAIN)).thenReturn(null);
        when(chain.head()).thenReturn(new BscDepositProof.Head(200,"0x"+"b".repeat(64)));
        when(provider.projectCoins()).thenReturn(List.of(new CregisGateway.Coin("USDT","USDT",CHAIN,
            CregisConstants.USDT_BEP20_TOKEN_ID,false,true)));
        when(db.poolPolicy()).thenAnswer(i->policy.get());
        when(db.availableAddressCount(88,CHAIN)).thenAnswer(i->available.get());
        when(db.outstandingAddressCount(88,CHAIN)).thenAnswer(i->(int)requests.stream().filter(r->java.util.Set.of("QUEUED","PROVISIONING","VERIFYING","UNKNOWN").contains(r.get("state"))).count());
        when(db.submittingAddressCount(88,CHAIN)).thenAnswer(i->(int)requests.stream().filter(r->"PROVISIONING".equals(r.get("state"))).count());
        when(db.queuedAddressCount(88,CHAIN)).thenAnswer(i->(int)requests.stream().filter(r->"QUEUED".equals(r.get("state"))).count());
        when(db.pendingAddressCount(88,CHAIN)).thenAnswer(i->(int)requests.stream().filter(r->java.util.Set.of("PROVISIONING","VERIFYING","UNKNOWN").contains(r.get("state"))).count());
        when(db.enqueuePoolAddress(eq(88L),eq(CHAIN),anyString())).thenAnswer(i->{
            var row=new java.util.concurrent.ConcurrentHashMap<String,Object>();
            row.put("id",(long)requests.size()+1);row.put("requestId",i.getArgument(2));row.put("state","QUEUED");requests.add(row);return 1;
        });
        when(db.lockQueuedAddress(88,CHAIN)).thenAnswer(i->requests.stream().filter(r->"QUEUED".equals(r.get("state"))).findFirst().orElse(null));
        when(db.claimQueuedAddress(anyLong(),anyLong())).thenAnswer(i->{requests.stream().filter(r->r.get("id").equals(i.getArgument(0))).findFirst().orElseThrow().put("state","PROVISIONING");return 1;});
        when(db.recordPoolCandidate(anyString(),anyString())).thenAnswer(i->{requests.stream().filter(r->r.get("requestId").equals(i.getArgument(0))).findFirst().orElseThrow().put("state","VERIFYING");return 1;});
        when(provider.createAddress(eq(CHAIN),anyString(),anyString(),anyString()))
            .thenAnswer(i->new CregisGateway.Address(CHAIN,ADDRESS,i.getArgument(3)));
        service=DepositPolicyFixture.withPolicy(new CregisDepositService(config,router,chain,new CregisSigner(),new ObjectMapper(),db,tx,
            mock(TreasuryLedgerRepository.class),mock(FinanceWithdrawalControlFacade.class),
            mock(SupportPaymentAttributionFacade.class),mock(CregisPayoutControl.class),
            mock(CregisExceptionSourceService.class),mock(CregisReorgResolutionService.class), DepositPolicyFixture.policy()));
    }
    @Test void batchQueuesAndCreatesFiftyBeyondSixtyTotalWithAtMostTwoConcurrentCalls() throws Exception {
        when(db.allocatedAddressCount(88,CHAIN)).thenReturn(10000);
        var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        var both=new java.util.concurrent.CountDownLatch(2);
        when(provider.createAddress(any(),any(),any(),any())).thenAnswer(i->{
            int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);both.countDown();
            assertThat(both.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();return new CregisGateway.Address(CHAIN,ADDRESS,i.getArgument(3));
        });
        service.maintainAddressPool();
        assertThat(requests).hasSize(50);assertThat(peak.get()).isEqualTo(2);
        verify(provider,times(50)).createAddress(any(),any(),any(),any());
        verify(db,never()).assignPoolAddress(anyLong(),anyLong(),anyLong(),any());
        verify(db,never()).creditWallet(any(),anyLong(),anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"FULL","ABOVE_THRESHOLD","PAUSED","DISABLED","CREDIT_OFF","POLICY_OFF","BACKOFF"})
    void noCreationWithoutCapacityOrCurrentPermission(String state) {
        switch(state) {
            case "FULL" -> available.set(1000);
            case "ABOVE_THRESHOLD" -> available.set(500);
            case "PAUSED" -> when(db.provisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));
            case "DISABLED" -> config.setDepositEnabled(false);
            case "CREDIT_OFF" -> config.setDepositCreditEnabled(false);
            case "POLICY_OFF" -> {var p=new java.util.HashMap<>(policy.get());p.put("enabled",0);policy.set(p);}
            case "BACKOFF" -> {var p=new java.util.HashMap<>(policy.get());p.put("retryAfter",System.currentTimeMillis()+60000);policy.set(p);}
        }
        service.maintainAddressPool();verify(provider,never()).createAddress(any(),any(),any(),any());
    }
    @Test void lowWatermarkRefillsToTargetAcrossBatchesAndCountsOutstandingReservations() {
        available.set(990);var p=new java.util.HashMap<>(policy.get());p.put("refillActive",1);policy.set(p);
        service.maintainAddressPool();assertThat(requests).hasSize(10);
        service.maintainAddressPool();assertThat(requests).hasSize(10);
    }
    @Test void disablingAutomaticRefillStillAllowsManualQueuedMaintenance() {
        var p=new java.util.HashMap<>(policy.get());p.put("enabled",0);policy.set(p);
        assertThat(service.provisionPoolAddress()).containsEntry("state","QUEUED");
        service.maintainAddressPool();assertThat(requests).hasSize(1);
        verify(provider,times(1)).createAddress(any(),any(),any(),any());
    }
    @Test void unknownProviderResultBlocksInsteadOfBlindlyCreatingAgain() {
        when(provider.createAddress(any(),any(),any(),any())).thenThrow(new IllegalStateException("TIMEOUT"));
        doAnswer(i->{when(db.provisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));
            when(db.lockProvisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));return 1;})
            .when(db).blockProvisionGateAny();
        service.maintainAddressPool();int submitted=mockingDetails(provider).getInvocations().size();service.maintainAddressPool();
        assertThat(mockingDetails(provider).getInvocations()).hasSize(submitted);
        verify(db,atLeastOnce()).unknownPoolAttempt(anyString());verify(db,atLeastOnce()).blockProvisionGateAny();
    }
    @Test void explicitQuotaRejectionBacksOffWithoutPausingExistingDepositAddresses() {
        when(provider.createAddress(any(),any(),any(),any())).thenThrow(new CregisGatewayException(CregisGatewayException.Kind.REJECTED,"CREGIS_ADDRESS_QUOTA_EXCEEDED"));
        service.maintainAddressPool();
        verify(db,atLeastOnce()).rejectPoolAttempt(anyString());
        verify(db,atLeastOnce()).pausePoolSubmission("CREGIS_ADDRESS_QUOTA_EXCEEDED",900);
        verify(db,never()).blockProvisionGateAny();
    }
    @Test void processDeathAfterDurableAttemptRequiresReviewInsteadOfDuplicateCreation() {
        when(db.expireProvisioning(88,CHAIN)).thenReturn(1);
        doAnswer(i->{when(db.provisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));return 1;})
            .when(db).blockProvisionGateAny();
        service.maintainAddressPool();
        verify(db).blockProvisionGateAny();verifyNoInteractions(provider);
    }
    @Test void existingUnknownClosesGateEvenAfterAnInterruptedOldVersion() {
        when(db.unknownAddressCount(88,CHAIN)).thenReturn(1);
        doAnswer(i->{when(db.provisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));return 1;})
            .when(db).blockProvisionGateAny();
        service.maintainAddressPool();
        verify(db).blockProvisionGateAny();verifyNoInteractions(provider);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void candidateUsesItsOwnHistoryDespiteGlobalCursorAlreadyPastCreation(boolean interruptedRange) {
        when(db.cursor()).thenReturn(100000L);
        when(db.verifyingCandidates(88,CHAIN,50)).thenReturn(List.of(Map.of("id",9L,"address",ADDRESS,
            "creationBlock",100L,"verificationBlock",100L)));
        available.set(1000);
        when(db.advanceCandidateVerification(9,100,187)).thenReturn(1);
        when(provider.addressBelongs(CHAIN,ADDRESS)).thenReturn(true);
        when(provider.zeroAddressBalance(CregisConstants.USDT_BEP20_CURRENCY,ADDRESS)).thenReturn(true);
        when(chain.zeroUsdtBalance(ADDRESS)).thenReturn(true);
        if(interruptedRange) when(chain.scan(100,186,List.of(ADDRESS))).thenThrow(new IllegalStateException("RPC_FAILED"));
        service.maintainAddressPool();
        verify(chain).scan(100,186,List.of(ADDRESS));
        if(interruptedRange) {
            verify(db,never()).advanceCandidateVerification(anyLong(),anyLong(),anyLong());
            verify(db,never()).readyPoolCandidate(anyLong());
        } else verify(db).readyPoolCandidate(9);
    }
    @Test void longInterruptedCandidatePersistsOneBoundedRangeAndWaitsForTheRest() {
        when(chain.head()).thenReturn(new BscDepositProof.Head(10000,"0x"+"b".repeat(64)));
        when(db.verifyingCandidates(88,CHAIN,50)).thenReturn(List.of(Map.of("id",9L,"address",ADDRESS,
            "creationBlock",100L,"verificationBlock",100L)));
        available.set(1000);
        when(db.advanceCandidateVerification(9,100,500)).thenReturn(1);
        service.maintainAddressPool();
        verify(chain).scan(100,499,List.of(ADDRESS));
        verify(db).advanceCandidateVerification(9,100,500);
        verify(db,never()).readyPoolCandidate(anyLong());
        verifyNoInteractions(provider);
    }

}
