package ffdd.opsconsole.finance.cregis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.finance.mapper.CregisExceptionSourceMapper;
import ffdd.opsconsole.finance.mapper.CregisDepositMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class CregisExceptionSourceServiceTest {
    final CregisExceptionSourceMapper db=mock(CregisExceptionSourceMapper.class);
    final CregisDepositMapper deposits=mock(CregisDepositMapper.class);
    final CregisExceptionSourceService service=new CregisExceptionSourceService(db,deposits);
    final CregisExceptionSourceService.Source source=new CregisExceptionSourceService.Source(88,77,"0X"+"A".repeat(64),2,"0x"+"b".repeat(40),BigDecimal.TEN);
    final Map<String,Object> row=new HashMap<>();
    @BeforeEach void setup(){row.putAll(Map.of("cid",77L,"address",source.address(),"grossAmount",BigDecimal.TEN,"state","AVAILABLE","version",0L));
        when(db.sourceEvent(any())).thenReturn(null);when(deposits.unresolvedExposure(anyLong())).thenReturn(BigDecimal.ZERO);
        when(db.lock(any())).thenReturn(row);when(db.transition(any(),anyString(),nullable(String.class),nullable(String.class),anyLong())).thenAnswer(i->{
            if(((Number)row.get("version")).longValue()!=i.<Long>getArgument(4))return 0;
            row.put("state",i.getArgument(1));row.put("operationKind",i.getArgument(2));row.put("operationRef",i.getArgument(3));row.put("version",i.<Long>getArgument(4)+1);return 1;});}
    @Test void refundUnknownKeepsExclusiveSourceAndNormalCreditCannotConsumeIt(){
        service.reserve(source,"REFUND","RF-one");service.reserve(source,"REFUND","RF-one");
        for(String kind:List.of("CLAIM","AUTO","REVIEW"))assertThatThrownBy(()->service.reserve(source,kind,"other")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        assertThatThrownBy(()->service.release(source,"REFUND","wrong")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        service.complete(source,"REFUND","RF-one","REFUNDED");service.complete(source,"REFUND","RF-one","REFUNDED");
        assertThatThrownBy(()->service.reserve(source,"AUTO","CR-77")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        verify(db).refundEvent(source);verify(db).finishObservation(source,"REFUNDED");
        verify(deposits).resolveExposureAlert(88);
        assertThat(source.txid()).isEqualTo("0x"+"a".repeat(64));
    }
    @Test void alreadyCreditedHistoricalEventCannotBeRefundedEvenBeforeGuardBackfill(){
        when(db.sourceEvent(source)).thenReturn(Map.of("id",1L,"cid",77L,"status","CREDITED","ledgerId",42L));
        assertThatThrownBy(()->service.reserve(source,"REFUND","RF-one")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        verify(db,never()).transition(any(),anyString(),any(),any(),anyLong());
    }
    @Test void mismatchedCidSameLogCannotBypassMutexAndOnlyOwnerMayRelease(){
        var another=new CregisExceptionSourceService.Source(88,78,source.txid(),2,source.address(),BigDecimal.TEN);
        assertThatThrownBy(()->service.reserve(another,"REFUND","RF-two")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        service.reserve(source,"CLAIM","CL-one");service.release(source,"CLAIM","CL-one");service.reserve(source,"REFUND","RF-one");
        assertThat(row.get("operationRef")).isEqualTo("RF-one");
    }
    @Test void failedCasNeverCompletesOrFinishesOriginalSource(){
        service.reserve(source,"REFUND","RF-one");when(db.transition(any(),anyString(),any(),any(),anyLong())).thenReturn(0);
        assertThatThrownBy(()->service.complete(source,"REFUND","RF-one","REFUNDED")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        verify(db,never()).refundEvent(any());verify(db,never()).finishDeliveries(any(),any());
    }
    @Test void unresolvedExposureAlertRemainsWhenOtherSourcesStillExceedThreshold(){
        when(deposits.unresolvedExposure(88)).thenReturn(new BigDecimal("500"));
        service.reserve(source,"REFUND","RF-one");service.complete(source,"REFUND","RF-one","REFUNDED");
        verify(deposits,never()).resolveExposureAlert(anyLong());
    }
    @Test void reorgAliasRequiresExistingLedgerAndRefusesPendingRefundAtNewIndex(){
        var moved=new CregisExceptionSourceService.Source(88,77,source.txid(),9,source.address(),BigDecimal.TEN);
        when(db.sourceEvent(source)).thenReturn(Map.of("id",1L,"cid",77L,"txid",source.txid(),"logIndex",2,
                "address",source.address(),"grossAmount",BigDecimal.TEN,"ledgerId",42L,"status","REORG_INVESTIGATING"));
        row.put("state","CREDITED");
        var next=new HashMap<String,Object>(row);next.put("state","RESERVED");next.put("operationKind","REFUND");next.put("operationRef","RF-pending");
        when(db.lock(moved)).thenReturn(next);
        assertThatThrownBy(()->service.relocateCredited(source,moved,"CR-REORG-1:2")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
        verify(db,never()).consumeAlias(any(),anyInt(),anyString(),anyLong());
        when(db.sourceEvent(source)).thenReturn(null);
        assertThatThrownBy(()->service.relocateCredited(source,moved,"CR-REORG-1:2")).hasMessage("CREGIS_EXCEPTION_SOURCE_OCCUPIED");
    }
}
