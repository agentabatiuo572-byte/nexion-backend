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
    final CregisDepositService service;
    CregisAddressPoolMaintenanceTest() {
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);
        config.setDepositEnabled(true);config.setDepositCreditEnabled(true);
        var router=mock(CregisGatewayRouter.class);when(router.provider()).thenReturn(provider);
        when(router.depositCallbackUrl()).thenReturn("https://test.example/callback");
        var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var gate=Map.<String,Object>of("state","IDLE","assignEnabled",1,"creditEnabled",1);
        when(db.provisionGate()).thenReturn(gate);when(db.lockProvisionGate()).thenReturn(gate);
        when(db.unresolvedExposure(88)).thenReturn(BigDecimal.ZERO);
        when(db.verifyingCandidate(88,CHAIN)).thenReturn(null);
        when(chain.head()).thenReturn(new BscDepositProof.Head(200,"0x"+"b".repeat(64)));
        when(provider.projectCoins()).thenReturn(List.of(new CregisGateway.Coin("USDT","USDT",CHAIN,
            CregisConstants.USDT_BEP20_TOKEN_ID,false,true)));
        when(db.insertPoolAttempt(eq(88L),eq(CHAIN),anyString(),eq(200L))).thenReturn(1);
        when(provider.createAddress(eq(CHAIN),anyString(),anyString(),anyString()))
            .thenAnswer(i->new CregisGateway.Address(CHAIN,ADDRESS,i.getArgument(3)));
        when(db.recordPoolCandidate(anyString(),eq(ADDRESS))).thenReturn(1);
        service=new CregisDepositService(config,router,chain,new CregisSigner(),new ObjectMapper(),db,tx,
            mock(TreasuryLedgerRepository.class),mock(FinanceWithdrawalControlFacade.class),
            mock(SupportPaymentAttributionFacade.class),mock(CregisPayoutControl.class),
            mock(CregisExceptionSourceService.class),mock(CregisReorgResolutionService.class));
    }
    @Test void lowReserveCreatesOneCandidateEvenBeyondSixtyTotalAddresses() {
        when(db.allocatedAddressCount(88,CHAIN)).thenReturn(10000);
        service.maintainAddressPool();
        var order=inOrder(db,provider);
        order.verify(db).insertPoolAttempt(eq(88L),eq(CHAIN),anyString(),eq(200L));
        order.verify(provider).createAddress(eq(CHAIN),anyString(),anyString(),anyString());
        order.verify(db).recordPoolCandidate(anyString(),eq(ADDRESS));
        verify(db,never()).claimProvisionGate();
        verify(db,never()).assignPoolAddress(anyLong(),anyLong(),anyLong(),any());
        verify(db,never()).creditWallet(any(),anyLong(),anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"FULL","PENDING","PAUSED","DISABLED","CREDIT_OFF","LOCK_RACE"})
    void noCreationWithoutAvailableCapacityAndCurrentPermission(String state) {
        switch(state) {
            case "FULL" -> when(db.availableAddressCount(88,CHAIN)).thenReturn(5);
            case "PENDING" -> when(db.pendingAddressCount(88,CHAIN)).thenReturn(1);
            case "PAUSED" -> when(db.provisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));
            case "DISABLED" -> config.setDepositEnabled(false);
            case "CREDIT_OFF" -> config.setDepositCreditEnabled(false);
            case "LOCK_RACE" -> when(db.pendingAddressCount(88,CHAIN)).thenReturn(0,1);
        }
        service.maintainAddressPool();
        verify(provider,never()).createAddress(any(),any(),any(),any());
    }
    @Test void unknownProviderResultBlocksInsteadOfBlindlyCreatingAgain() {
        when(provider.createAddress(any(),any(),any(),any())).thenThrow(new IllegalStateException("TIMEOUT"));
        doAnswer(i->{when(db.provisionGate()).thenReturn(Map.of("state","BLOCKED","assignEnabled",0,"creditEnabled",0));return 1;})
            .when(db).blockProvisionGateAny();
        service.maintainAddressPool();service.maintainAddressPool();
        verify(provider,times(1)).createAddress(any(),any(),any(),any());
        verify(db).unknownPoolAttempt(anyString());
        verify(db).blockProvisionGateAny();
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
        when(db.verifyingCandidate(88,CHAIN)).thenReturn(Map.of("id",9L,"address",ADDRESS,
            "creationBlock",100L,"verificationBlock",100L));
        when(db.pendingAddressCount(88,CHAIN)).thenReturn(1);
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
        when(db.verifyingCandidate(88,CHAIN)).thenReturn(Map.of("id",9L,"address",ADDRESS,
            "creationBlock",100L,"verificationBlock",100L));
        when(db.pendingAddressCount(88,CHAIN)).thenReturn(1);
        when(db.advanceCandidateVerification(9,100,500)).thenReturn(1);
        service.maintainAddressPool();
        verify(chain).scan(100,499,List.of(ADDRESS));
        verify(db).advanceCandidateVerification(9,100,500);
        verify(db,never()).readyPoolCandidate(anyLong());
        verifyNoInteractions(provider);
    }

}
