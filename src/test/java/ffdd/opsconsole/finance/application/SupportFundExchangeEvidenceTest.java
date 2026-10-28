package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.market.application.SupportExchangeReadService;
import ffdd.opsconsole.market.facade.SupportExchangeReadFacade;
import ffdd.opsconsole.market.mapper.SupportExchangeSourceMapper;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SupportFundExchangeEvidenceTest {
    final ObjectMapper json=new ObjectMapper();
    final SupportExchangeReadFacade exchange=new SupportExchangeReadService(mock(SupportExchangeSourceMapper.class));
    final SupportFundExchangeEvidence evidence=new SupportFundExchangeEvidence(new SupportFundJournalBindingService(mock(SupportFundJournalMapper.class),mock(DataSource.class),json),json,exchange);
    static final String NO="EX-fixture",INC="d71e682f-f7de-4af6-8c8f-90601bbbafef";
    static final LocalDateTime HOLD_AT=LocalDateTime.of(2026,10,11,10,0),TERMINAL_AT=HOLD_AT.plusSeconds(2);
    static BigDecimal n(String v){return new BigDecimal(v).setScale(6);}
    static Map<String,Object> row(Object... values){var m=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    static Object normalize(Object v){if(v instanceof LocalDateTime t)return t.toString();if(v instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,x)->r.put(k.toString(),normalize(x)));return r;}if(v instanceof Collection<?> c)return c.stream().map(SupportFundExchangeEvidenceTest::normalize).toList();return v;}
    ObjectNode node(Object v){return (ObjectNode)json.valueToTree(normalize(v));}
    String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new AssertionError(e);}}
    String key(String phase){try{return "EXCHANGE:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((NO+"\n"+phase).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}}
    Map<String,Object> wallet(BigDecimal usd,BigDecimal nex,long revision){return row("id",19L,"customerId",7L,"incarnation",INC,"revision",revision,"version",10+revision,"usdtAvailable",usd,"nexAvailable",nex,"riskHeld",n("0"),"pendingWithdrawal",n("0"),"sandbox",0,"deleted",0);}
    Map<String,Object> order(String status,String asset,BigDecimal target,LocalDateTime at){return row("id",77L,"customerId",7L,"exchangeNo",NO,"fromAsset",asset,"toAsset",asset.equals("USDT")?"UVT":"USDT","fromAmount",n("20"),"toAmount",target,"rate",new BigDecimal("3.00000000"),"status",status,"createdAt",HOLD_AT,"updatedAt",at,"deleted",0,"userSandbox",0,"userDeleted",0);}
    Map<String,Object> ledger(long id,String suffix,String asset,String direction,BigDecimal amount,BigDecimal after,LocalDateTime at){return row("id",id,"customerId",7L,"exchangeNo",NO,"bizNo",NO+suffix,"bizType","EXCHANGE_SWAP","asset",asset,"direction",direction,"amount",amount,"balanceAfter",after,"status","SUCCESS","createdAt",at,"updatedAt",at,"deleted",0);}
    final class Fixture {
        String phase,asset;Map<String,Object> current;List<Map<String,Object>> ledgers=new ArrayList<>(),fees=new ArrayList<>(),raw=new ArrayList<>(),ops=new ArrayList<>(),parties=new ArrayList<>(),legs=new ArrayList<>();
        ObjectNode source,proof;Map<String,Object> operation;List<Event> events;Map<String,Integer> precisions=new HashMap<>(Map.of("nx_exchange_order.updated_at",0));
        SupportFundExchangeEvidence.Inputs input(){operation.put("sourceSnapshotJson",encode(source));operation.put("sourceProofJson",encode(proof));return new SupportFundExchangeEvidence.Inputs(List.of(new SupportExchangeReadFacade.Source(current,ledgers,fees)),raw,ops,parties,legs,precisions);}
        SupportFundExchangeEvidence.Verified verify(){return evidence.verify(operation,source,proof,events,input());}
    }
    Fixture fixture(String phase,String asset){
        var f=new Fixture();f.phase=phase;f.asset=asset;boolean reserved=!phase.equals("IMMEDIATE");
        BigDecimal fee=Set.of("IMMEDIATE","COMPLETE").contains(phase)?n("1"):n("0");
        BigDecimal grossTarget=asset.equals("USDT")?n("6.666666"):n("60"),netTarget=asset.equals("USDT")?n("6.333333"):n("59");BigDecimal target=Set.of("HOLD","CANCEL").contains(phase)?grossTarget:netTarget;String targetAsset=asset.equals("USDT")?"UVT":"USDT";
        String status=phase.equals("HOLD")?"QUEUED":phase.equals("CANCEL")?"CANCELLED":"COMPLETED";
        f.current=order(status,asset,target,phase.equals("HOLD")||phase.equals("IMMEDIATE")?HOLD_AT:TERMINAL_AT);
        var holdLedger=ledger(1,"-HOLD",asset,"OUT",n("20"),n("80"),HOLD_AT);
        if(reserved)f.ledgers.add(holdLedger);
        if(phase.equals("IMMEDIATE"))f.ledgers.add(ledger(2,"-OUT",asset,"OUT",n("20"),n("80"),HOLD_AT));
        if(Set.of("IMMEDIATE","COMPLETE").contains(phase))f.ledgers.add(ledger(3,"-IN",targetAsset,"IN",target,n("100").add(target),(LocalDateTime)f.current.get("updatedAt")));
        if(phase.equals("CANCEL"))f.ledgers.add(ledger(4,"-REFUND",asset,"IN",n("20"),n("100"),TERMINAL_AT));
        if(fee.signum()>0)f.fees.add(row("id",9L,"exchangeNo",NO,"totalFeeUsdt",fee,"burnRatio",new BigDecimal(".30"),"burnPoolUsdt",n(".3"),"feeBufferUsdt",n(".7"),"priceUsdt",new BigDecimal("3.00000000"),"nexEquivalent",n(".1"),"createdAt",f.current.get("updatedAt"),"updatedAt",f.current.get("updatedAt"),"deleted",0));
        if(reserved&&!phase.equals("HOLD"))append(f,"HOLD",order("QUEUED",asset,grossTarget,HOLD_AT),List.of(holdLedger),List.of(),null,wallet(n("100"),n("100"),0),wallet(asset.equals("USDT")?n("80"):n("100"),asset.equals("UVT")?n("80"):n("100"),1),n("0"),1);
        var before=wallet(reserved&&!phase.equals("HOLD")&&asset.equals("USDT")?n("80"):n("100"),reserved&&!phase.equals("HOLD")&&asset.equals("UVT")?n("80"):n("100"),f.ops.size());
        BigDecimal usd=(BigDecimal)before.get("usdtAvailable"),nex=(BigDecimal)before.get("nexAvailable");
        if(phase.equals("HOLD")||phase.equals("IMMEDIATE")){if(asset.equals("USDT"))usd=usd.subtract(n("20"));else nex=nex.subtract(n("20"));}
        if(Set.of("IMMEDIATE","COMPLETE").contains(phase)){if(targetAsset.equals("USDT"))usd=usd.add(target);else nex=nex.add(target);}
        if(phase.equals("CANCEL")){if(asset.equals("USDT"))usd=usd.add(n("20"));else nex=nex.add(n("20"));}
        Map<String,Object> prior=reserved&&!phase.equals("HOLD")?order("QUEUED",asset,grossTarget,HOLD_AT):null;
        append(f,phase,f.current,f.ledgers,f.fees,prior,before,wallet(usd,nex,f.ops.size()+1),fee,f.ops.size()+1);
        return f;
    }
    void append(Fixture f,String phase,Map<String,Object> order,List<Map<String,Object>> ledgers,List<Map<String,Object>> fees,Map<String,Object> prior,Map<String,Object> before,Map<String,Object> after,BigDecimal fee,long id){
        var original=new SupportExchangeReadFacade.Source(order,ledgers,fees);var events=exchange.events(original,phase);
        var source=node(row("schemaVersion","SUPPORT_EXCHANGE_FUND_V1","phase",phase,"order",order,"ledgers",ledgers,"feeAllocations",fees,"feeCurrency","USDT","feeAmount",fee,"phaseAt",order.get("updatedAt"),"orderBefore",prior,"ledgersBefore",prior==null?List.of():ledgers.stream().filter(l->(NO+"-HOLD").equals(l.get("bizNo"))).toList(),"feeAllocationsBefore",List.of()));
        var proof=node(row("schemaVersion","SUPPORT_EXCHANGE_FUND_V1","cashBefore",before,"cashAfter",after,"eventIds",events.stream().map(Event::id).toList(),"sourceReservationPresent",!phase.equals("IMMEDIATE"),"eventPhase",phase,"originalHoldOperationKey",Set.of("COMPLETE","CANCEL").contains(phase)?key("HOLD"):null,"sourcePhaseAt",order.get("updatedAt")));
        var op=row("id",id,"operationKey",key(phase),"businessType","SUPPORT_EXCHANGE","phase",phase,"sourceTable","nx_exchange_order","sourceKey",NO,"sourceEnvironment","PRODUCTION","sourceSnapshotJson",encode(source),"sourceProofJson",encode(proof),"orderedEventsJson",encode(events));
        var raw=row("id",id,"incarnation",INC,"revision",id,"observation","CHANGE","recordedDbUtc",HOLD_AT.minusHours(8).plusSeconds(id));
        for(var side:List.of("old","new")){var wallet=side.equals("old")?before:after;for(var mapping:Map.of("id","Id","customerId","UserId","version","Version","usdtAvailable","UsdtAvailable","nexAvailable","NexAvailable","riskHeld","CregisRiskHeld","pendingWithdrawal","PendingWithdraw","sandbox","Sandbox","deleted","IsDeleted").entrySet())raw.put(side+mapping.getValue(),wallet.get(mapping.getKey()));raw.put(side+"CreatedAt",HOLD_AT.minusDays(1));}
        f.ops.add(op);f.raw.add(raw);f.parties.add(row("operationId",id,"customerId",7L));f.legs.add(row("operationId",id,"incarnation",INC,"revision",id));f.source=source;f.proof=proof;f.operation=op;f.events=events;
    }
    static Stream<String> phases(){return Stream.of("IMMEDIATE-USDT","IMMEDIATE-UVT","HOLD-USDT","HOLD-UVT","COMPLETE-USDT","COMPLETE-UVT","CANCEL-USDT","CANCEL-UVT");}
    @ParameterizedTest @MethodSource("phases") void allRealPhasesKeepExactRawGroupAndSourceStamp(String spec){var parts=spec.split("-");var f=fixture(parts[0],parts[1]);var v=f.verify();assertThat(v.coveredPhase()).isEqualTo(NO+":"+parts[0]);assertThat(v.points()).hasSize(1);assertThat(v.points().get(0).eventIds()).containsExactlyElementsOf(f.events.stream().map(Event::id).toList());assertThat(v.stamp().succeededAt()).isEqualTo(f.current.get("updatedAt"));}
    @ParameterizedTest @ValueSource(strings={"HOLD","IMMEDIATE","COMPLETE","CANCEL"}) void reverseCoverageIncludesEveryPhaseAndOnlyTrueQueuedHolding(String phase){var f=fixture(phase,"USDT");var covered=new HashSet<String>();for(var op:f.ops)covered.add(NO+":"+op.get("phase"));var holdings=evidence.verifyCoverage(Set.of(7L),f.input(),covered);assertThat(holdings).hasSize(phase.equals("HOLD")?1:0);if(!holdings.isEmpty()){assertThat(holdings.get(0).amount()).isEqualByComparingTo("20");assertThat(holdings.get(0).account().slot()).isEqualTo("held:exchange-"+NO);}}
    static Stream<String> corruptions(){return Stream.of("extraLedger","duplicateLedger","foreignLedger","badLedgerType","badLedgerAsset","badLedgerAmount","deletedLedger","failedLedger","duplicateFee","badFeeTotal","badFeePrice","badFeeCurrency","missingHold","holdSnapshotChanged","cashAfterWrong","rawForeignOwner","rawVersionGap","duplicateLeg","foreignLeg","wrongEventIds","eventOrder","walletIncarnation","sourceStamp","missingPrecision","beforeOrder","unexpectedProofKey");}
    @ParameterizedTest @MethodSource("corruptions") void contradictorySourceOrCashCannotBecomeVerified(String corruption){
        var f=fixture("COMPLETE","USDT");var lastRaw=f.raw.get(f.raw.size()-1);var lastLeg=f.legs.get(f.legs.size()-1);
        switch(corruption){
            case "extraLedger" -> f.ledgers.add(ledger(5,"-OUT","USDT","OUT",n("20"),n("60"),TERMINAL_AT));
            case "duplicateLedger" -> f.ledgers.add(new HashMap<>(f.ledgers.get(1)));
            case "foreignLedger" -> f.ledgers.get(1).put("customerId",8L);
            case "badLedgerType" -> f.ledgers.get(1).put("bizType","PAYMENT");
            case "badLedgerAsset" -> f.ledgers.get(1).put("asset","USDT");
            case "badLedgerAmount" -> f.ledgers.get(1).put("amount",n("7"));
            case "deletedLedger" -> f.ledgers.get(1).put("deleted",1);
            case "failedLedger" -> f.ledgers.get(1).put("status","FAILED");
            case "duplicateFee" -> f.fees.add(new HashMap<>(f.fees.get(0)));
            case "badFeeTotal" -> f.fees.get(0).put("totalFeeUsdt",n("2"));
            case "badFeePrice" -> f.fees.get(0).put("priceUsdt",new BigDecimal("3.00000001"));
            case "badFeeCurrency" -> f.source.put("feeCurrency","UVT");
            case "missingHold" -> {f.ops.remove(0);f.legs.remove(0);f.parties.remove(0);}
            case "holdSnapshotChanged" -> {var old=(ObjectNode)read(f.ops.get(0).get("sourceSnapshotJson"));((ObjectNode)old.get("order")).put("fromAmount",21);f.ops.get(0).put("sourceSnapshotJson",encode(old));}
            case "cashAfterWrong" -> ((ObjectNode)f.proof.get("cashAfter")).put("nexAvailable",999);
            case "rawForeignOwner" -> lastRaw.put("newUserId",8L);
            case "rawVersionGap" -> lastRaw.put("newVersion",99L);
            case "duplicateLeg" -> f.legs.add(new HashMap<>(lastLeg));
            case "foreignLeg" -> lastLeg.put("incarnation","another-wallet");
            case "wrongEventIds" -> f.proof.putArray("eventIds").add("foreign");
            case "eventOrder" -> {var reversed=new ArrayList<>(f.events);Collections.reverse(reversed);f.events=reversed;}
            case "walletIncarnation" -> ((ObjectNode)f.proof.get("cashBefore")).put("incarnation","wrong");
            case "sourceStamp" -> f.proof.put("sourcePhaseAt",TERMINAL_AT.plusSeconds(1).toString());
            case "missingPrecision" -> f.precisions.clear();
            case "beforeOrder" -> ((ObjectNode)f.source.get("orderBefore")).put("status","COMPLETED");
            case "unexpectedProofKey" -> f.proof.put("cashTrace","invented");
            default -> throw new AssertionError(corruption);
        }
        assertThatThrownBy(f::verify).isInstanceOf(IllegalArgumentException.class);
    }
    com.fasterxml.jackson.databind.JsonNode read(Object s){try{return json.readTree(s.toString());}catch(Exception e){throw new AssertionError(e);}}
    @Test void terminalCurrentTimeCannotReplaceTheImmutableHoldStamp(){var f=fixture("COMPLETE","USDT");var hold=f.ops.get(0);var v=evidence.verify(hold,read(hold.get("sourceSnapshotJson")),read(hold.get("sourceProofJson")),exchange.events(new SupportExchangeReadFacade.Source(order("QUEUED","USDT",n("6.666666"),HOLD_AT),List.of(f.ledgers.get(0)),List.of()),"HOLD"),f.input());assertThat(v.stamp().succeededAt()).isEqualTo(HOLD_AT);assertThat(v.stamp().succeededAt()).isNotEqualTo(f.current.get("updatedAt"));}
    @Test void holdStampAdvancedPastItsRealLedgerCannotBeAccepted(){var f=fixture("COMPLETE","USDT");var hold=f.ops.get(0);var source=(ObjectNode)read(hold.get("sourceSnapshotJson"));var proof=(ObjectNode)read(hold.get("sourceProofJson"));((ObjectNode)source.get("order")).put("updatedAt",TERMINAL_AT.toString());source.put("phaseAt",TERMINAL_AT.toString());proof.put("sourcePhaseAt",TERMINAL_AT.toString());hold.put("sourceSnapshotJson",encode(source));hold.put("sourceProofJson",encode(proof));var events=exchange.events(new SupportExchangeReadFacade.Source(order("QUEUED","USDT",n("6.666666"),HOLD_AT),List.of(f.ledgers.get(0)),List.of()),"HOLD");assertThatThrownBy(()->evidence.verify(hold,source,proof,events,f.input())).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @MethodSource("phases") void actualMarketAdapterProtocolIsAcceptedOnlyWithItsExactRawBoundary(String spec){
        var parts=spec.split("-");var f=fixture(parts[0],parts[1]);var journal=mock(ffdd.opsconsole.finance.facade.SupportFundJournalFacade.class);var mapper=mock(SupportExchangeSourceMapper.class);var sources=mock(SupportExchangeReadFacade.class);
        var before=json.convertValue(f.proof.get("cashBefore"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});var after=json.convertValue(f.proof.get("cashAfter"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});when(mapper.wallets(7)).thenReturn(List.of(before),List.of(after));
        var current=new SupportExchangeReadFacade.Source(f.current,f.ledgers,f.fees);
        if(Set.of("COMPLETE","CANCEL").contains(f.phase)){var prior=json.convertValue(f.source.get("orderBefore"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});when(sources.readByOrderNos(List.of(NO))).thenReturn(List.of(new SupportExchangeReadFacade.Source(prior,List.of(f.ledgers.get(0)),List.of())),List.of(current));}else when(sources.readByOrderNos(List.of(NO))).thenReturn(List.of(current));
        when(journal.bind(any(),any())).thenAnswer(i->{var r=(ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput)i.getArgument(1);return new ffdd.opsconsole.finance.facade.SupportFundJournalFacade.BoundReceipt(((Number)f.operation.get("id")).longValue(),r.operationKey(),"sha",1);});
        var adapter=new ffdd.opsconsole.market.application.SupportExchangeFundAdapter(journal,mapper,sources,json);var token=Set.of("COMPLETE","CANCEL").contains(f.phase)?adapter.begin(7,NO):adapter.begin(7);adapter.recorded(token,NO,f.phase,Set.of("IMMEDIATE","COMPLETE").contains(f.phase)?n("1"):n("0"));
        var receipt=org.mockito.ArgumentCaptor.forClass(ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput.class);verify(journal).bind(any(),receipt.capture());f.source=(ObjectNode)read(receipt.getValue().sourceSnapshotJson());f.proof=(ObjectNode)read(receipt.getValue().sourceProofJson());f.operation.put("orderedEventsJson",receipt.getValue().orderedEventsJson());assertThat(f.verify().points()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"GEO_BLOCKED","USER_CAP","PLATFORM_CAP"}) void realNoCashGateRequiresNoLedgerFeeOrReceipt(String status){var f=fixture("IMMEDIATE","USDT");f.current.put("status",status);f.ledgers.clear();f.fees.clear();f.ops.clear();f.parties.clear();f.legs.clear();f.raw.clear();assertThat(evidence.verifyCoverage(Set.of(7L),f.input(),Set.of())).isEmpty();f.ledgers.add(ledger(8,"-OUT","USDT","OUT",n("20"),n("80"),HOLD_AT));assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),f.input(),Set.of())).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"USDT","UVT"}) void zeroActualFeeKeepsOnlyTheRealExchange(String asset){
        var f=fixture("IMMEDIATE",asset);var target=asset.equals("USDT")?n("6.666666"):n("60");var balance=n("100").add(target);var cashField=asset.equals("USDT")?"nexAvailable":"usdtAvailable";var rawField=asset.equals("USDT")?"newNexAvailable":"newUsdtAvailable";
        f.current.put("toAmount",target);f.ledgers.get(1).put("amount",target);f.ledgers.get(1).put("balanceAfter",balance);f.fees.clear();f.raw.get(0).put(rawField,balance);((ObjectNode)f.proof.get("cashAfter")).put(cashField,balance);
        f.source.set("order",node(f.current));f.source.set("ledgers",json.valueToTree(normalize(f.ledgers)));f.source.putArray("feeAllocations");f.source.put("feeAmount",n("0"));f.events=exchange.events(new SupportExchangeReadFacade.Source(f.current,f.ledgers,f.fees),"IMMEDIATE");f.proof.set("eventIds",json.valueToTree(f.events.stream().map(Event::id).toList()));f.operation.put("orderedEventsJson",encode(f.events));
        assertThat(f.verify().points()).hasSize(1);assertThat(f.events).hasSize(1);assertThat(f.events.get(0).action()).isEqualTo(Action.EXCHANGE);
    }
    @Test void legitimateRoundedZeroTargetBindsItsRealCashButRemainsUnknown(){
        var f=fixture("IMMEDIATE","USDT");f.current.put("rate",new BigDecimal("30000000.00000000"));f.current.put("toAmount",n("0"));f.ledgers.get(1).put("amount",n("0"));f.ledgers.get(1).put("balanceAfter",n("100"));f.fees.clear();f.raw.get(0).put("newNexAvailable",n("100"));((ObjectNode)f.proof.get("cashAfter")).put("nexAvailable",n("100"));
        var journal=mock(ffdd.opsconsole.finance.facade.SupportFundJournalFacade.class);var mapper=mock(SupportExchangeSourceMapper.class);var sources=mock(SupportExchangeReadFacade.class);var before=json.convertValue(f.proof.get("cashBefore"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});var after=json.convertValue(f.proof.get("cashAfter"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});when(mapper.wallets(7)).thenReturn(List.of(before),List.of(after));when(sources.readByOrderNos(List.of(NO))).thenReturn(List.of(new SupportExchangeReadFacade.Source(f.current,f.ledgers,f.fees)));
        when(journal.bind(any(),any())).thenAnswer(i->{var r=(ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput)i.getArgument(1);return new ffdd.opsconsole.finance.facade.SupportFundJournalFacade.BoundReceipt(1,r.operationKey(),"sha",1);});var adapter=new ffdd.opsconsole.market.application.SupportExchangeFundAdapter(journal,mapper,sources,json);adapter.recorded(adapter.begin(7),NO,"IMMEDIATE",n("0"));
        var receipt=org.mockito.ArgumentCaptor.forClass(ffdd.opsconsole.finance.facade.SupportFundJournalFacade.ReceiptInput.class);verify(journal).bind(any(),receipt.capture());f.source=(ObjectNode)read(receipt.getValue().sourceSnapshotJson());f.proof=(ObjectNode)read(receipt.getValue().sourceProofJson());f.operation.put("orderedEventsJson",receipt.getValue().orderedEventsJson());f.events=List.of();assertThat(read(receipt.getValue().orderedEventsJson())).isEmpty();assertThat(f.proof.get("cashAfter").get("usdtAvailable").decimalValue()).isEqualByComparingTo("80");assertThatThrownBy(f::verify).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),f.input(),Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"COMPLETE","CANCEL"}) void legacyTerminalWithoutTheTrueHoldCannotBeAccepted(String phase){var f=fixture(phase,"USDT");f.ops.remove(0);f.legs.remove(0);f.parties.remove(0);f.ledgers.remove(0);assertThatThrownBy(f::verify).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),f.input(),Set.of(NO+":"+phase))).isInstanceOf(IllegalArgumentException.class);}
    @Test void missingReversePhaseAndInventedHeldTailFailClosed(){var f=fixture("HOLD","USDT");assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),f.input(),Set.of())).isInstanceOf(IllegalArgumentException.class);f.current.put("status","FAILED");assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),f.input(),Set.of(NO+":HOLD"))).isInstanceOf(IllegalArgumentException.class);}
}
