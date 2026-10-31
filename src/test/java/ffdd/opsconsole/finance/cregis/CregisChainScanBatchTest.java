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
        f.service.scanTrackedAddresses(CregisNetwork.BEP20,30000);
        verify(f.chain).scan(CregisNetwork.BEP20,900,1000L+size-101,List.of(f.address));
        verify(f.db).advanceCursor(CregisNetwork.BEP20,1000L+size-100,1000);
        verify(f.db,never()).creditWallet(any(),anyLong(),anyLong());
        verifyNoInteractions(f.treasury,f.capture);
    }

    @ParameterizedTest @ValueSource(ints={500,20000})
    void failedRpcDoesNotAdvanceOrMaterializeMoney(int size) {
        var f = fixture(size);
        when(f.chain.scan(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),anyLong(),any())).thenThrow(new IllegalStateException("CREGIS_BSC_PROOF_UNAVAILABLE"));
        assertThatThrownBy(() -> f.service.scanTrackedAddresses(CregisNetwork.BEP20,30000)).hasMessage("CREGIS_BSC_PROOF_UNAVAILABLE");
        verify(f.db,never()).advanceCursor(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),anyLong());
        verify(f.db,never()).insertObservation(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),any(),anyInt(),any(),any(),anyLong(),any());
        verify(f.db,never()).creditWallet(any(),anyLong(),anyLong());
        verifyNoInteractions(f.treasury,f.capture);
    }

    @ParameterizedTest @ValueSource(ints={500,20000})
    void rangeNeverPassesFinalizedHead(int size) {
        var f = fixture(size); f.service.scanTrackedAddresses(CregisNetwork.BEP20,1100);
        verify(f.chain).scan(CregisNetwork.BEP20,900,1100,List.of(f.address));
        verify(f.db).advanceCursor(CregisNetwork.BEP20,1101,1000);
    }


    @ParameterizedTest @ValueSource(booleans={false,true})
    void moreThanSixtyAddressesArePagedWithoutSkippingFailedPages(boolean failSecondPage) {
        var f=fixture(500);
        when(f.db.allocationBounds(88,CregisConstants.BSC_CHAIN_ID))
            .thenReturn(Map.of("firstBlock",100L,"maxId",121L,"missingBlocks",0));
        for(long start: new long[]{0,60,120}) {
            var page=new java.util.ArrayList<Map<String,Object>>();
            for(long id=start+1;id<=Math.min(start+60,121);id++)
                page.add(Map.of("id",id,"creationBlock",100L,"allocationBlock",100L,"state","READY",
                    "address","0x"+String.format("%040x",id)));
            when(f.db.allocations(88,CregisConstants.BSC_CHAIN_ID,start,121)).thenReturn(page);
        }
        if(failSecondPage) when(f.chain.scan(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),anyLong(),any())).thenReturn(List.of())
            .thenThrow(new IllegalStateException("RPC_FAILED"));
        if(failSecondPage) {
            assertThatThrownBy(()->f.service.scanTrackedAddresses(CregisNetwork.BEP20,30000)).hasMessage("RPC_FAILED");
            verify(f.db,never()).advanceCursor(org.mockito.ArgumentMatchers.eq(CregisNetwork.BEP20),anyLong(),anyLong());
        } else {
            f.service.scanTrackedAddresses(CregisNetwork.BEP20,30000);
            var pages=org.mockito.ArgumentCaptor.forClass(List.class);
            verify(f.chain,times(3)).scan(eq(CregisNetwork.BEP20),eq(900L),eq(1399L),pages.capture());
            assertThat(pages.getAllValues().stream().map(List::size).toList()).containsExactly(60,60,1);
            verify(f.db).advanceCursor(CregisNetwork.BEP20,1400,1000);
        }
    }

    private Fixture fixture(int size) {
        var config = new CregisProperties();
        assertThat(config.getChainScanBlocks()).isEqualTo(500);
        if (size != 500) config.setChainScanBlocks(size);
        config.setMode(CregisProperties.Mode.PROVIDER);config.setBscRpcUrl("https://bsc.example.test");config.setProjectId(88);
        var db = mock(CregisDepositMapper.class);var chain = mock(BscDepositProof.class);
        String address = "0x"+"a".repeat(40);
        when(db.allocationBounds(88,CregisConstants.BSC_CHAIN_ID)).thenReturn(Map.of("firstBlock",100L,"maxId",1L,"missingBlocks",0));
        when(db.allocations(88,CregisConstants.BSC_CHAIN_ID,0,1)).thenReturn(List.of(Map.of("id",1L,
                "creationBlock",100L,"allocationBlock",100L,"state","READY","address",address)));
        when(db.cursor(CregisNetwork.BEP20)).thenReturn(1000L);
        when(db.verifyingCandidate(88,CregisConstants.BSC_CHAIN_ID)).thenReturn(null);
        var treasury = mock(TreasuryLedgerRepository.class);var capture=mock(SupportPaymentAttributionFacade.class);
        var service = DepositPolicyFixture.withPolicy(new CregisDepositService(config,mock(CregisGatewayRouter.class),chain,new CregisSigner(),
                new ObjectMapper(),db,mock(PlatformTransactionManager.class),treasury,
                mock(FinanceWithdrawalControlFacade.class),capture,mock(CregisPayoutControl.class), org.mockito.Mockito.mock(CregisExceptionSourceService.class), org.mockito.Mockito.mock(CregisReorgResolutionService.class), DepositPolicyFixture.policy()));
        return new Fixture(service,db,chain,address,treasury,capture);
    }
    record Fixture(CregisDepositService service,CregisDepositMapper db,BscDepositProof chain,String address,
                   TreasuryLedgerRepository treasury,SupportPaymentAttributionFacade capture) {}
}
