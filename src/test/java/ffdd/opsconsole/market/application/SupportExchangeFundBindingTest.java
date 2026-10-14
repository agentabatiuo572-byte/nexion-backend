package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.market.mapper.SupportExchangeSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupportExchangeFundBindingTest {
    final SupportFundJournalFacade fund=mock(SupportFundJournalFacade.class);
    final SupportExchangeSourceMapper mapper=mock(SupportExchangeSourceMapper.class);
    final SupportExchangeReadFacade source=mock(SupportExchangeReadFacade.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final SupportExchangeFundAdapter adapter=new SupportExchangeFundAdapter(fund,mapper,source,json);
    static BigDecimal n(String v){return new BigDecimal(v);}
    static Map<String,Object> wallet(String usd,String nex,long revision){var result=new LinkedHashMap<String,Object>(Map.of("id",19L,"customerId",7L,"incarnation","d71e682f-f7de-4af6-8c8f-90601bbbafef","revision",revision,"usdtAvailable",n(usd),"nexAvailable",n(nex),"riskHeld",n("0"),"pendingWithdrawal",n("0"),"sandbox",0,"deleted",0));result.put("version",revision+10);return result;}
    @Test void independentWalletVersionCannotBeSubstitutedForFundRevision(){
        var before=new HashMap<>(wallet("20","0",2));before.put("version",12L);
        var after=new HashMap<>(wallet("0","6.333333",3));after.put("version",99L);
        assertThatThrownBy(()->SupportExchangeFundAdapter.verifyWallet(before,after,n("-20"),n("6.333333"),1)).isInstanceOf(IllegalStateException.class);
    }
    static Map<String,Object> order(String status){return Map.of("id",77L,"customerId",7L,"exchangeNo","EX-abcdefgh","fromAsset","USDT","toAsset","NEX","fromAmount",n("20"),"toAmount",n("6.333333"),"rate",n("3.00000000"),"status",status,"updatedAt",LocalDateTime.of(2026,10,11,12,13,14));}
    static Map<String,Object> fee(){return Map.of("exchangeNo","EX-abcdefgh","totalFeeUsdt",n("1"),"burnPoolUsdt",n(".3"),"feeBufferUsdt",n(".7"),"priceUsdt",n("3.00000000"),"deleted",0);}
    void prepare(Map<String,Object> before,Map<String,Object> after,Map<String,Object> order,List<Map<String,Object>> ledgers,List<Map<String,Object>> fees,int legs){
        when(mapper.wallets(7)).thenReturn(List.of(before),List.of(after));
        when(source.readByOrderNos(List.of("EX-abcdefgh"))).thenReturn(List.of(new SupportExchangeReadFacade.Source(order,ledgers,fees)));
        when(fund.bind(any(),any())).thenAnswer(i->{var input=(SupportFundJournalFacade.ReceiptInput)i.getArgument(1);return new SupportFundJournalFacade.BoundReceipt(88L,input.operationKey(),"sha",legs);});
    }
    @Test void bindsRealNetCashAndOriginalPhaseTimeExactlyOnce() throws Exception {
        prepare(wallet("20","0",2),wallet("0","6.333333",3),order("COMPLETED"),List.of(),List.of(fee()),1);
        adapter.recorded(adapter.begin(7),"EX-abcdefgh","IMMEDIATE",n("1"));
        var capture=ArgumentCaptor.forClass(SupportFundJournalFacade.ReceiptInput.class);verify(fund).bind(any(),capture.capture());
        var input=capture.getValue();assertThat(input.businessType()).isEqualTo("SUPPORT_EXCHANGE");assertThat(input.sourceTable()).isEqualTo("nx_exchange_order");assertThat(input.phase()).isEqualTo("IMMEDIATE");
        var snapshot=json.readTree(input.sourceSnapshotJson());assertThat(snapshot.get("feeCurrency").asText()).isEqualTo("USDT");assertThat(snapshot.get("feeAmount").decimalValue()).isEqualByComparingTo("1");
        assertThat(snapshot.get("phaseAt")).isEqualTo(snapshot.get("order").get("updatedAt"));
        var events=json.readTree(input.orderedEventsJson());assertThat(events.size()).isEqualTo(2);assertThat(events.get(0).get("action").asText()).isEqualTo("FEE");assertThat(events.get(1).get("targetAmount").decimalValue()).isEqualByComparingTo("6.333333");
        verify(fund).begin(List.of(7L));verifyNoMoreInteractions(fund);
    }
    @Test void legacyCancelledWithoutReservationBindsNoCashAndInventsNoHold() throws Exception {
        prepare(wallet("20","0",2),wallet("20","0",2),order("CANCELLED"),List.of(),List.of(),0);
        adapter.recorded(adapter.begin(7),"EX-abcdefgh","CANCEL",BigDecimal.ZERO);
        var capture=ArgumentCaptor.forClass(SupportFundJournalFacade.ReceiptInput.class);verify(fund).bind(any(),capture.capture());
        var input=capture.getValue();assertThat(json.readTree(input.orderedEventsJson()).size()).isZero();
        assertThat(json.readTree(input.sourceProofJson()).get("sourceReservationPresent").booleanValue()).isFalse();
    }
    @Test void legacyReservedCancelRetainsTrueReturnWithoutRequiringInventedReceipt(){
        prepare(wallet("0","0",2),wallet("20","0",3),order("CANCELLED"),List.of(Map.of("bizNo","EX-abcdefgh-HOLD","bizType","EXCHANGE_SWAP","direction","OUT","status","SUCCESS","deleted",0)),List.of(),1);
        adapter.recorded(adapter.begin(7),"EX-abcdefgh","CANCEL",BigDecimal.ZERO);verify(fund).bind(any(),any());
    }
    @Test void wrongNetCashFailsBeforeBinding(){
        prepare(wallet("20","0",2),wallet("0","6.333334",3),order("COMPLETED"),List.of(),List.of(fee()),1);
        var before=adapter.begin(7);assertThatThrownBy(()->adapter.recorded(before,"EX-abcdefgh","IMMEDIATE",n("1"))).hasMessage("Exchange cash boundary mismatch");verify(fund,never()).bind(any(),any());
    }
    @Test void wrongActualFeeOrStalePriceFailsBeforeBinding(){
        var wrong=new HashMap<>(fee());wrong.put("priceUsdt",n("2"));
        prepare(wallet("20","0",2),wallet("0","6.333333",3),order("COMPLETED"),List.of(),List.of(wrong),1);
        var before=adapter.begin(7);assertThatThrownBy(()->adapter.recorded(before,"EX-abcdefgh","IMMEDIATE",n("1"))).hasMessage("Exchange fee identity mismatch");verify(fund,never()).bind(any(),any());
    }
    @Test void realEightDecimalRateIsNotRoundedToSix(){
        var o=new HashMap<>(order("COMPLETED"));o.put("rate",n("3.12345678"));o.put("toAmount",n("6.083004"));
        var fee=new HashMap<>(fee());fee.put("priceUsdt",n("3.12345678"));
        BigDecimal target=n("19").divide(n("3.12345678"),6,java.math.RoundingMode.DOWN);o.put("toAmount",target);
        prepare(wallet("20","0",2),wallet("0",target.toPlainString(),3),o,List.of(),List.of(fee),1);
        adapter.recorded(adapter.begin(7),"EX-abcdefgh","IMMEDIATE",n("1"));verify(fund).bind(any(),any());
    }
    @Test void nexDirectionBindsOnlyRealNetUsdtAndFeeFromItsConvertedSource() throws Exception {
        var o=new HashMap<>(order("COMPLETED"));o.put("fromAsset","NEX");o.put("toAsset","USDT");o.put("fromAmount",n("10"));o.put("toAmount",n(".666667"));o.put("rate",n(".11666667"));
        var f=new HashMap<>(fee());f.put("priceUsdt",n(".11666667"));f.put("totalFeeUsdt",n(".5"));f.put("burnPoolUsdt",n(".15"));f.put("feeBufferUsdt",n(".35"));
        prepare(wallet("50","10",2),wallet("50.666667","0",3),o,List.of(),List.of(f),1);
        adapter.recorded(adapter.begin(7),"EX-abcdefgh","IMMEDIATE",n(".5"));
        var capture=ArgumentCaptor.forClass(SupportFundJournalFacade.ReceiptInput.class);verify(fund).bind(any(),capture.capture());
        var events=json.readTree(capture.getValue().orderedEventsJson());assertThat(events.size()).isEqualTo(3);assertThat(events.get(0).get("targetAmount").decimalValue()).isEqualByComparingTo("1.166667");assertThat(events.get(1).get("from").get("slot").asText()).isEqualTo("held:exchange-target-EX-abcdefgh");assertThat(events.get(2).get("amount").decimalValue()).isEqualByComparingTo(".666667");
    }
    @Test void queuedHoldBindsTheRealDebitWithoutChargingOrCreatingContribution() throws Exception {
        prepare(wallet("20","0",2),wallet("0","0",3),order("QUEUED"),List.of(Map.of("bizNo","EX-abcdefgh-HOLD","bizType","EXCHANGE_SWAP","direction","OUT","status","SUCCESS","deleted",0)),List.of(),1);
        adapter.recorded(adapter.begin(7),"EX-abcdefgh","HOLD",BigDecimal.ZERO);
        var capture=ArgumentCaptor.forClass(SupportFundJournalFacade.ReceiptInput.class);verify(fund).bind(any(),capture.capture());
        var events=json.readTree(capture.getValue().orderedEventsJson());assertThat(events.size()).isOne();assertThat(events.get(0).get("action").asText()).isEqualTo("MOVE");assertThat(events.get(0).get("to").get("slot").asText()).isEqualTo("held:exchange-EX-abcdefgh");
    }
}
