package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupportDeviceFundReceiptReadTest {
    final SupportDeviceReadMapper mapper=mock(SupportDeviceReadMapper.class);
    final SupportDeviceReadService service=new SupportDeviceReadService(mapper);
    final Map<String,Object> receipt=new HashMap<>(Map.of("customerId",7L,"receiptNo","CTR-EXACT","taskNo","CTA-EXACT",
        "deviceId",11L,"rewardNex",new BigDecimal("4.250000"),"completedAt",LocalDateTime.of(2026,10,12,7,59,59,999999000)));
    void empty(){when(mapper.fundReceipt(7,"CTR-EXACT")).thenReturn(List.of(receipt));when(mapper.fundReceiptTasks(7,"CTA-EXACT")).thenReturn(List.of());when(mapper.fundReceiptLedgers(eq(7L),anyList())).thenReturn(List.of());when(mapper.fundReceiptEvents(7,"CTR-EXACT")).thenReturn(List.of());}
    static Map<String,Object> row(String field,String value){return Map.of("customerId",7L,field,value,"deleted",1,"status","FAILED");}

    @Test void exactJavaIdentityKeepsDuplicatesAndBadOriginalsWithoutReadingHistory(){
        empty();var lower=new HashMap<>(receipt);lower.put("receiptNo","ctr-exact");var padded=new HashMap<>(receipt);padded.put("receiptNo","CTR-EXACT ");when(mapper.fundReceipt(7,"CTR-EXACT")).thenReturn(List.of(lower,padded,receipt));
        var task=row("taskNo","CTA-EXACT");var usd=row("bizNo","CTA-EXACT");var daily=row("bizNo","CLOUD_SHARE_DAILY:11:2026-10-11");var event=row("receiptNo","CTR-EXACT");
        when(mapper.fundReceiptTasks(7,"CTA-EXACT")).thenReturn(List.of(row("taskNo","cta-exact"),row("taskNo","CTA-EXACT "),task,task));
        when(mapper.fundReceiptLedgers(eq(7L),anyList())).thenReturn(List.of(row("bizNo","cta-exact"),row("bizNo","CTA-EXACT "),usd,daily,daily));
        when(mapper.fundReceiptEvents(7,"CTR-EXACT")).thenReturn(List.of(row("receiptNo","ctr-exact"),row("receiptNo","CTR-EXACT "),event,event));
        var result=service.readFundEarning(7,"CTR-EXACT");assertThat(result.receipts()).containsExactly(receipt);assertThat(result.tasks()).containsExactly(task,task);assertThat(result.ledgers()).containsExactly(usd,daily,daily);assertThat(result.earningEvents()).containsExactly(event,event);
        assertThatThrownBy(()->result.ledgers().get(0).put("deleted",0)).isInstanceOf(UnsupportedOperationException.class);
        verify(mapper).fundReceiptLedgers(7,List.of("CTA-EXACT","CLOUD_SHARE_DAILY:11:2026-10-11"));
        verify(mapper,never()).fundReceipts(anyList());verify(mapper,never()).fundTasks(anyList());verify(mapper,never()).fundLedgers(anyList());verify(mapper,never()).fundEvents(anyList());
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void dailyLedgerUsesBusinessToUtcDayAndSupportsBothJdbcTimeForms(boolean timestamp){
        empty();var at=LocalDateTime.of(2026,10,12,8,0);receipt.put("completedAt",timestamp?Timestamp.valueOf(at):at);service.readFundEarning(7,"CTR-EXACT");
        verify(mapper).fundReceiptLedgers(7,List.of("CTA-EXACT","CLOUD_SHARE_DAILY:11:2026-10-12"));
    }
    @Test void zeroNexDoesNotPullAnEarlierDailyCreditIntoTheCurrentReceipt(){empty();receipt.put("rewardNex",BigDecimal.ZERO);service.readFundEarning(7,"CTR-EXACT");verify(mapper).fundReceiptLedgers(7,List.of("CTA-EXACT"));}
    @Test void missingOrAmbiguousReceiptFailsBeforeDependentQueries(){
        when(mapper.fundReceipt(7,"CTR-EXACT")).thenReturn(List.of(),List.of(receipt,receipt));
        for(int i=0;i<2;i++)assertThatThrownBy(()->service.readFundEarning(7,"CTR-EXACT")).isInstanceOf(IllegalStateException.class).hasMessage("Device receipt missing or ambiguous");
        verify(mapper,never()).fundReceiptTasks(anyLong(),any());verify(mapper,never()).fundReceiptLedgers(anyLong(),anyList());verify(mapper,never()).fundReceiptEvents(anyLong(),any());
    }
    @ParameterizedTest @ValueSource(strings={"receipt","task","ledger","event"}) void missingBatchOrForeignOwnerCannotBecomeAnEmptyValidSource(String batch){
        empty();for(var rows:Arrays.<List<Map<String,Object>>>asList(null,List.of(Map.of("customerId",8L)))){
            switch(batch){case "receipt"->when(mapper.fundReceipt(7,"CTR-EXACT")).thenReturn(rows);case "task"->when(mapper.fundReceiptTasks(7,"CTA-EXACT")).thenReturn(rows);case "ledger"->when(mapper.fundReceiptLedgers(eq(7L),anyList())).thenReturn(rows);case "event"->when(mapper.fundReceiptEvents(7,"CTR-EXACT")).thenReturn(rows);}
            assertThatThrownBy(()->service.readFundEarning(7,"CTR-EXACT")).isInstanceOf(IllegalStateException.class).hasMessage("INVALID_SUPPORT_DEVICE_FUND_ROWS");
        }
    }
    @Test void queryFailureAndInvalidScopeAreNeverSilentlyAccepted(){
        assertThatThrownBy(()->service.readFundEarning(0,"CTR-EXACT")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.readFundEarning(7,null)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(mapper);
        when(mapper.fundReceipt(7,"CTR-EXACT")).thenThrow(new IllegalStateException("SQL failure"));assertThatThrownBy(()->service.readFundEarning(7,"CTR-EXACT")).hasMessage("SQL failure");
    }
    @Test void targetedSqlUsesOwnerAndIndexedBusinessIdentityWithoutHidingBadRows(){
        var config=new Configuration();config.addMapper(SupportDeviceReadMapper.class);
        for(String method:List.of("fundReceipt","fundReceiptTasks","fundReceiptLedgers","fundReceiptEvents")){
            var query=config.getMappedStatement(SupportDeviceReadMapper.class.getName()+"."+method);
            String sql=query.getBoundSql(Map.of("customer",7L,"receiptNo","CTR-EXACT","taskNo","CTA-EXACT","ledgerNos",List.of("CTA-EXACT","CLOUD_SHARE_DAILY:11:2026-10-11"))).getSql();
            assertThat(sql).contains("user_id=?","ORDER BY");assertThat(sql.toUpperCase()).doesNotContain("LIMIT","FOR UPDATE");assertThat(sql).doesNotContain("is_deleted=0","source_environment='PRODUCTION'","asset=","status=");
            assertThat(sql).contains(switch(method){case "fundReceipt","fundReceiptEvents"->"receipt_no=?";case "fundReceiptTasks"->"task_no=?";default->"biz_no IN";});
            assertThat(query.isUseCache()).isFalse();assertThat(query.isFlushCacheRequired()).isTrue();
        }
    }
}
