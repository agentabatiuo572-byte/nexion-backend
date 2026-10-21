package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

class CregisChainScanBatchTest {
    @ParameterizedTest @ValueSource(ints={101,500,20000})
    void sameOverlapAndFinalityAdvanceOnlyAfterSuccessfulRange(int size) {
        var f = fixture(size);
        f.service.scanTrackedAddresses(30000);
        verify(f.chain).scan(900,1000L+size-101,List.of(f.address));
        verify(f.db).advanceCursor(1000L+size-100,1000);
        verify(f.db,never()).creditWallet(any(),anyLong(),anyLong());
        verifyNoInteractions(f.treasury,f.capture);
    }

    @ParameterizedTest @ValueSource(ints={500,20000})
    void failedRpcDoesNotAdvanceOrMaterializeMoney(int size) {
        var f = fixture(size);
        when(f.chain.scan(anyLong(),anyLong(),any())).thenThrow(new IllegalStateException("CREGIS_BSC_PROOF_UNAVAILABLE"));
        assertThatThrownBy(() -> f.service.scanTrackedAddresses(30000)).hasMessage("CREGIS_BSC_PROOF_UNAVAILABLE");
        verify(f.db,never()).advanceCursor(anyLong(),anyLong());
        verify(f.db,never()).insertObservation(anyLong(),any(),anyInt(),any(),any(),anyLong(),any());
        verify(f.db,never()).creditWallet(any(),anyLong(),anyLong());
        verifyNoInteractions(f.treasury,f.capture);
    }

    @ParameterizedTest @ValueSource(ints={500,20000})
    void rangeNeverPassesFinalizedHead(int size) {
        var f = fixture(size); f.service.scanTrackedAddresses(1100);
        verify(f.chain).scan(900,1100,List.of(f.address));
        verify(f.db).advanceCursor(1101,1000);
    }

    private Fixture fixture(int size) {
        var config = new CregisProperties();
        assertThat(config.getChainScanBlocks()).isEqualTo(500);
        if (size != 500) config.setChainScanBlocks(size);
        config.setMode(CregisProperties.Mode.PROVIDER);config.setProjectId(88);
        var db = mock(CregisDepositMapper.class);var chain = mock(BscDepositProof.class);
        String address = "0x"+"a".repeat(40);
        when(db.allocations(88,CregisConstants.BSC_CHAIN_ID)).thenReturn(List.of(Map.of(
                "creationBlock",100L,"allocationBlock",100L,"state","READY","address",address)));
        when(db.cursor()).thenReturn(1000L);
        when(db.verifyingCandidate(88,CregisConstants.BSC_CHAIN_ID)).thenReturn(null);
        var treasury = mock(TreasuryLedgerRepository.class);var capture=mock(SupportPaymentAttributionFacade.class);
        var service = new CregisDepositService(config,mock(CregisGatewayRouter.class),chain,new CregisSigner(),
                new ObjectMapper(),db,mock(PlatformTransactionManager.class),treasury,
                mock(FinanceWithdrawalControlFacade.class),capture,mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class));
        return new Fixture(service,db,chain,address,treasury,capture);
    }
    record Fixture(CregisDepositService service,CregisDepositMapper db,BscDepositProof chain,String address,
                   TreasuryLedgerRepository treasury,SupportPaymentAttributionFacade capture) {}
}
