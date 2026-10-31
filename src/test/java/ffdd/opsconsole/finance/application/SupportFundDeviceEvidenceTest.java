package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.device.application.SupportDeviceFundAdapter;
import ffdd.opsconsole.device.application.SupportDeviceReadService;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.FundEarnings;
import ffdd.opsconsole.device.mapper.SupportDeviceReadMapper;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import java.sql.Connection;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class SupportFundDeviceEvidenceTest {
    @ParameterizedTest @ValueSource(strings={"otherTaskCashIncluded","revisionGap"})
    void adapterStillRejectsCashOrRevisionFromAnotherTaskBeforeBinding(String corruption) {
        var mapper=mock(SupportDeviceReadMapper.class);var fund=mock(SupportFundJournalFacade.class);
        var source=mock(SupportDeviceReadFacade.class);var adapter=new SupportDeviceFundAdapter(fund,mapper,source,json);
        var before=wallet(1,"10","20");
        var after=wallet(corruption.equals("revisionGap")?3:2,corruption.equals("otherTaskCashIncluded")?"10.6":"10.3","20");
        when(mapper.fundWallets(7)).thenReturn(List.of(before),List.of(after),List.of(after));
        when(source.readFundEarning(7L,NO)).thenReturn(new FundEarnings(
                List.of(row("receiptNo",NO,"taskNo",TASK,"rewardUsdt",n(".3"),"rewardNex",n("0"),"completedAt",AT)),
                List.of(),List.of(),List.of()));
        var token=adapter.begin(7);adapter.afterUsdt(token);
        assertThatThrownBy(()->adapter.recorded(token,NO)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Device cash delta mismatch");
        verify(fund,never()).bind(any(),any());
    }
    static final String NO="CTR-DEVICE",TASK="CTA-DEVICE",INC="dab1e5ef-069b-4771-8eab-b0b0661aa1fb";
    static final LocalDateTime AT=LocalDateTime.of(2026,10,12,0,0,0,750000000),WHOLE=AT.withNano(0);
    final ObjectMapper json=new ObjectMapper();
    final SupportFundDeviceEvidence helper=new SupportFundDeviceEvidence(new SupportFundJournalBindingService(mock(SupportFundJournalMapper.class),mock(DataSource.class),json),json);
    static Map<String,Object> row(Object...v){var m=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)m.put((String)v[i],v[i+1]);return m;}
    static BigDecimal n(String v){return new BigDecimal(v).setScale(6);}
    Map<String,Object> wallet(long revision,String usd,String nex){return row("id",9L,"customerId",7L,"incarnation",INC,"revision",revision,"version",revision+20,"usdtAvailable",n(usd),"nexAvailable",n(nex),"cregisRiskHeld",n("0"),"pendingWithdraw",n("0"),"sandbox",0,"deleted",0,"createdAt",WHOLE.minusDays(1));}
    Map<String,Object> raw(Map<String,Object> before,Map<String,Object> after,long revision){var r=row("id",revision,"incarnation",INC,"revision",revision,"observation","CHANGE","protocolSha256","d9126edf88cbca4d5c5d0814cb0207ca21d33c78ca00d52c755034fb9e3cbe2a","recordedDbUtc",AT.minusHours(8));for(var side:List.of("old","new")){var w=side.equals("old")?before:after;for(var pair:Map.of("id","Id","customerId","UserId","version","Version","usdtAvailable","UsdtAvailable","nexAvailable","NexAvailable","cregisRiskHeld","CregisRiskHeld","pendingWithdraw","PendingWithdraw","sandbox","Sandbox","deleted","IsDeleted","createdAt","CreatedAt").entrySet())r.put(side+pair.getValue(),w.get(pair.getKey()));}return r;}
    String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new AssertionError(e);}}
    JsonNode read(String s){try{return json.readTree(s);}catch(Exception e){throw new AssertionError(e);}}
    final class Fixture {
        boolean dual;Map<String,Object> receipt,task,operation;List<Map<String,Object>> receipts=new ArrayList<>(),tasks=new ArrayList<>(),ledgers=new ArrayList<>(),earnings=new ArrayList<>(),raw=new ArrayList<>(),ops=new ArrayList<>(),parties=new ArrayList<>(),legs=new ArrayList<>();ObjectNode source,proof;List<Event> events;Map<String,Integer> meta=new HashMap<>(Map.of("nx_compute_receipt.completed_at",6,"nx_compute_task.completed_at",6,"nx_compute_task.proof_consumed_at",0,"nx_wallet_ledger.created_at",0,"nx_earning_event.wallet_posted_at",0,"nx_earning_event.created_at",0));
        FundEarnings sources(){return new FundEarnings(receipts,tasks,ledgers,earnings);}
        SupportFundDeviceEvidence.Inputs input(){operation.put("sourceSnapshotJson",encode(source));operation.put("sourceProofJson",encode(proof));return new SupportFundDeviceEvidence.Inputs(sources(),raw,ops,parties,legs,meta);}
        SupportFundDeviceEvidence.Verified verify(){return helper.verify(operation,source,proof,events,input());}
        void coverage(Set<String> set){helper.verifyCoverage(Set.of(7L),input(),set);}
    }
    Fixture fixture(boolean dual){return fixture(dual,false);}
    Fixture fixture(boolean dual,boolean zero){
        var usd=zero?n("0"):n(".3");String usdAfter=zero?"10":"10.3";
        var f=new Fixture();f.dual=dual;f.receipt=row("id",88L,"customerId",7L,"deviceId",11L,"taskNo",TASK,"receiptNo",NO,"taskType","IG","clientName","TEST official production compute","rewardUsdt",usd,"rewardNex",dual?n("4.25"):n("0"),"earningStatus","CREDITED","sourceEnvironment","PRODUCTION","proofHash","a".repeat(64),"completedAt",AT,"createdAt",WHOLE,"updatedAt",WHOLE,"deleted",0,"userSandbox",0,"userDeleted",0);f.receipts.add(f.receipt);
        f.task=row("id",99L,"customerId",7L,"deviceId",11L,"taskNo",TASK,"taskId","TASK-IG","taskType","IG","rewardUsdt",usd,"status","COMPLETED","sourceEnvironment","PRODUCTION","clientName",f.receipt.get("clientName"),"startedAt",WHOLE.minusMinutes(1),"completedAt",AT,"proofConsumedAt",WHOLE,"deleted",0);f.tasks.add(f.task);
        f.ledgers.add(row("id",1L,"customerId",7L,"bizNo",TASK,"bizType","COMPUTE_TASK_REWARD","asset","USDT","direction","IN","amount",usd,"balanceAfter",n(usdAfter),"status","SUCCESS","createdAt",WHOLE,"updatedAt",WHOLE,"deleted",0));
        f.earnings.add(row("id",1L,"eventNo","EARN-"+TASK,"customerId",7L,"deviceId",11L,"receiptNo",NO,"asset","USDT","amount",usd,"status","POSTED","walletPostedAt",WHOLE,"createdAt",WHOLE,"updatedAt",WHOLE,"deleted",0));
        if(dual){f.ledgers.add(row("id",2L,"customerId",7L,"bizNo","CLOUD_SHARE_DAILY:11:2026-10-11","bizType","COMPUTE_TASK_REWARD","asset","UVT","direction","IN","amount",n("4.25"),"balanceAfter",n("24.25"),"status","SUCCESS","createdAt",WHOLE,"updatedAt",WHOLE,"deleted",0));f.earnings.add(row("id",2L,"eventNo","EARN-NEX-"+NO,"customerId",7L,"deviceId",11L,"receiptNo",NO,"asset","UVT","amount",n("4.25"),"status","POSTED","walletPostedAt",WHOLE,"createdAt",WHOLE,"updatedAt",WHOLE,"deleted",0));}
        var before=wallet(1,"10","20");var afterUsd=wallet(2,usdAfter,"20");var afterNex=dual?wallet(3,usdAfter,"24.25"):afterUsd;f.raw.add(raw(before,afterUsd,2));if(dual)f.raw.add(raw(afterUsd,afterNex,3));
        var journal=mock(SupportFundJournalMapper.class);var dataSource=mock(DataSource.class);var binder=new SupportFundJournalBindingService(journal,dataSource,json);var mapper=mock(SupportDeviceReadMapper.class);var owning=new SupportDeviceReadService(mapper);
        when(mapper.fundWallets(7)).thenReturn(List.of(before),List.of(afterUsd),List.of(afterNex));
        when(mapper.fundReceipt(7L,NO)).thenAnswer(i->f.receipts);when(mapper.fundReceiptTasks(7L,TASK)).thenAnswer(i->f.tasks);
        when(mapper.fundReceiptLedgers(eq(7L),anyList())).thenAnswer(i->f.ledgers);when(mapper.fundReceiptEvents(7L,NO)).thenAnswer(i->f.earnings);
        var lockedBefore=new HashMap<>(before);lockedBefore.put("userId",7L);lockedBefore.put("isDeleted",0);var lockedAfter=new HashMap<>(afterNex);lockedAfter.put("userId",7L);lockedAfter.put("isDeleted",0);when(journal.lockWallet(7)).thenReturn(List.of(lockedBefore),List.of(lockedAfter));when(journal.journal(INC,1,dual?3:2)).thenReturn(f.raw);
        when(journal.insertOperation(anyMap())).thenAnswer(i->{f.operation=new LinkedHashMap<>(i.<Map<String,Object>>getArgument(0));f.operation.put("id",1L);f.operation.put("recordedDbUtc",AT.minusHours(8));f.ops.add(f.operation);return 1;});when(journal.operation(SupportDeviceFundAdapter.key(NO))).thenAnswer(i->f.operation);
        when(journal.insertParty(1,7)).thenAnswer(i->{f.parties.add(row("operationId",1L,"customerId",7L));return 1;});when(journal.parties(1)).thenReturn(f.parties);when(journal.insertLeg(eq(1L),eq(INC),anyLong())).thenAnswer(i->{f.legs.add(row("operationId",1L,"incarnation",INC,"revision",i.getArgument(2)));return 1;});when(journal.legs(1)).thenReturn(f.legs);
        var holder=new ConnectionHolder(mock(Connection.class));holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.bindResource(dataSource,holder);
        try{var adapter=new SupportDeviceFundAdapter(binder,mapper,owning,json);var token=adapter.begin(7);adapter.afterUsdt(token);adapter.recorded(token,NO);}finally{TransactionSynchronizationManager.unbindResource(dataSource);TransactionSynchronizationManager.clear();}
        f.source=(ObjectNode)read((String)f.operation.get("sourceSnapshotJson"));f.proof=(ObjectNode)read((String)f.operation.get("sourceProofJson"));f.events=binder.decodeReceipt(f.operation,List.of(7L),f.legs);assertThat(f.operation.get("receiptSha256")).as("actual Binder immutable receipt digest").isNotNull();verify(journal,times(1)).insertOperation(anyMap());verify(mapper,never()).fundReceipts(anyList());verify(mapper,never()).fundTasks(anyList());verify(mapper,never()).fundLedgers(anyList());verify(mapper,never()).fundEvents(anyList());return f;
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void actualAdapterBindsOneWholeReceiptWithEachRealCurrencyRawLeg(boolean dual){var f=fixture(dual);var v=f.verify();assertThat(v.points()).hasSize(dual?2:1);assertThat(v.stamp().succeededAt()).isEqualTo(AT);assertThat(v.stamp().fractionalSecondDigits()).isEqualTo(6);assertThat(v.coveredPhase()).isEqualTo(NO+":CREDIT");assertThat(v.points().stream().flatMap(p->p.eventIds().stream()).toList()).containsExactlyElementsOf(f.events.stream().map(Event::id).toList());f.coverage(Set.of(NO+":CREDIT"));var allocation=new ffdd.opsconsole.finance.domain.SupportFundAllocation();for(var e:f.events)assertThat(allocation.apply(e)).isEmpty();assertThat(allocation.total("USDT")).isEqualByComparingTo("0");assertThat(allocation.total("UVT")).isEqualByComparingTo("0");}
    @ParameterizedTest @ValueSource(booleans={false,true}) void zeroUsdtKeepsItsRealVersionOnlyPointAndOptionalPositiveNexLeg(boolean dual){var f=fixture(dual,true);var v=f.verify();assertThat(v.points()).hasSize(dual?2:1);var zero=v.points().get(0);assertThat(zero.eventIds()).isEmpty();assertThat(zero.before()).isEqualTo(zero.after());assertThat(f.events).hasSize(dual?1:0);if(dual){assertThat(f.events.get(0).to().currency()).isEqualTo("UVT");assertThat(v.points().get(1).eventIds()).containsExactly(SupportDeviceFundAdapter.key(NO)+":NEX");}f.coverage(Set.of(NO+":CREDIT"));}
    static Stream<String> zeroCorruptions(){return Stream.of("missingRaw","missingLeg","revisionGap","versionGap","missingUsdLedger","missingUsdEarning","missingUsdReward","missingNexReward","missingRawCash","usdChange","nexChange","pendingChange","riskChange","zeroLedgerAmount","zeroEarningAmount","negativeReward","inventedZeroEvent","zeroPointEventId","missingPrecision");}
    @ParameterizedTest @MethodSource("zeroCorruptions") void zeroUsdtDoesNotExcuseMissingOrContradictoryCashAndOriginals(String kind){var f=fixture(true,true);switch(kind){
        case "missingRaw"->f.raw.remove(0);case "missingLeg"->f.legs.remove(0);case "revisionGap"->f.legs.get(0).put("revision",9L);case "versionGap"->f.raw.get(0).put("newVersion",99L);case "missingUsdLedger"->f.ledgers.remove(0);case "missingUsdEarning"->f.earnings.remove(0);case "missingUsdReward"->f.receipt.remove("rewardUsdt");case "missingNexReward"->f.receipt.remove("rewardNex");case "missingRawCash"->f.raw.get(0).remove("newUsdtAvailable");case "usdChange","nexChange","pendingChange","riskChange"->{String rawField=switch(kind){case "usdChange"->"UsdtAvailable";case "nexChange"->"NexAvailable";case "pendingChange"->"PendingWithdraw";default->"CregisRiskHeld";};String proofField=Character.toLowerCase(rawField.charAt(0))+rawField.substring(1);f.raw.get(0).put("new"+rawField,n("99"));((ObjectNode)f.proof.path("walletStages").get(1)).put(proofField,n("99"));}case "zeroLedgerAmount"->f.ledgers.get(0).put("amount",n(".01"));case "zeroEarningAmount"->f.earnings.get(0).put("amount",n(".01"));case "negativeReward"->f.receipt.put("rewardUsdt",n("-.01"));case "inventedZeroEvent"->{ObjectNode fake=json.valueToTree(f.events.get(0));fake.put("id",SupportDeviceFundAdapter.key(NO)+":USDT");fake.put("amount",n("0"));((ObjectNode)fake.path("to")).put("currency","USDT");f.operation.put("orderedEventsJson",encode(json.createArrayNode().add(fake).add(json.valueToTree(f.events.get(0)))));f.proof.withArray("eventIds").insert(0,SupportDeviceFundAdapter.key(NO)+":USDT");}case "zeroPointEventId"->f.proof.withArray("eventIds").insert(0,SupportDeviceFundAdapter.key(NO)+":USDT");case "missingPrecision"->f.meta.remove("nx_wallet_ledger.created_at");default->throw new AssertionError(kind);}
        assertThatThrownBy(f::verify).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->f.coverage(Set.of(NO+":CREDIT"))).isInstanceOf(IllegalArgumentException.class);
    }
    static Stream<String> corruptions(){return Stream.of("missingReceipt","duplicateReceipt","missingTask","foreignTask","badProof","testEnv","receiptDeleted","failedReceipt","failedTask","taskReward","taskTime","missingUsdLedger","missingNexLedger","duplicateLedger","foreignLedger","badLedgerType","badLedgerStatus","ledgerAmount","ledgerBalance","ledgerDeleted","wrongUtcDay","missingNexEarning","duplicateEarning","foreignEarning","badEarningStatus","earningAmount","earningDeleted","missingNexRaw","duplicateRaw","wrongRawOwner","wrongRawAmount","missingLeg","duplicateLeg","revisionGap","versionGap","wrongEventIds","eventOrder","missingPrecision","invalidStoredPrecision","cashProofAlias","extraProofField","duplicateOperation");}
    @ParameterizedTest @MethodSource("corruptions") void incompleteOrContradictoryActualSourceCannotBecomeReady(String kind){var f=fixture(true);switch(kind){
        case "missingReceipt"->f.receipts.clear();case "duplicateReceipt"->f.receipts.add(new HashMap<>(f.receipt));case "missingTask"->f.tasks.clear();case "foreignTask"->f.task.put("customerId",8L);case "badProof"->f.receipt.put("proofHash","not-proof");case "testEnv"->f.receipt.put("sourceEnvironment","TEST");case "receiptDeleted"->f.receipt.put("deleted",1);case "failedReceipt"->f.receipt.put("earningStatus","FAILED");case "failedTask"->f.task.put("status","RUNNING");case "taskReward"->f.task.put("rewardUsdt",n(".4"));case "taskTime"->f.task.put("completedAt",AT.plusNanos(1000));case "missingUsdLedger"->f.ledgers.remove(0);case "missingNexLedger"->f.ledgers.remove(1);case "duplicateLedger"->f.ledgers.add(new HashMap<>(f.ledgers.get(0)));case "foreignLedger"->f.ledgers.get(1).put("customerId",8L);case "badLedgerType"->f.ledgers.get(1).put("bizType","EARNINGS_CREDIT");case "badLedgerStatus"->f.ledgers.get(1).put("status","FAILED");case "ledgerAmount"->f.ledgers.get(1).put("amount",n("9"));case "ledgerBalance"->f.ledgers.get(1).put("balanceAfter",n("999"));case "ledgerDeleted"->f.ledgers.get(1).put("deleted",1);case "wrongUtcDay"->f.ledgers.get(1).put("bizNo","CLOUD_SHARE_DAILY:11:2026-10-12");case "missingNexEarning"->f.earnings.remove(1);case "duplicateEarning"->f.earnings.add(new HashMap<>(f.earnings.get(0)));case "foreignEarning"->f.earnings.get(1).put("customerId",8L);case "badEarningStatus"->f.earnings.get(1).put("status","FAILED");case "earningAmount"->f.earnings.get(1).put("amount",n("9"));case "earningDeleted"->f.earnings.get(1).put("deleted",1);case "missingNexRaw"->f.raw.remove(1);case "duplicateRaw"->f.raw.add(new HashMap<>(f.raw.get(1)));case "wrongRawOwner"->f.raw.get(1).put("newUserId",8L);case "wrongRawAmount"->f.raw.get(1).put("newNexAvailable",n("99"));case "missingLeg"->f.legs.remove(1);case "duplicateLeg"->f.legs.add(new HashMap<>(f.legs.get(1)));case "revisionGap"->f.legs.get(1).put("revision",4L);case "versionGap"->f.raw.get(1).put("newVersion",99L);case "wrongEventIds"->f.proof.putArray("eventIds").add("wrong");case "eventOrder"->{var reversed=new ArrayList<>(f.events);Collections.reverse(reversed);f.events=reversed;}case "missingPrecision"->f.meta.clear();case "invalidStoredPrecision"->f.meta.put("nx_compute_receipt.completed_at",0);case "cashProofAlias"->((ObjectNode)f.proof.get("walletStages").get(1)).put("revision",99);case "extraProofField"->f.proof.put("verified",true);case "duplicateOperation"->f.ops.add(new HashMap<>(f.operation));default->throw new AssertionError(kind);}
        assertThatThrownBy(f::verify).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"missingCoverage","extraCoverage","orphanLedger","orphanEarning","orphanReceipt","extraOperation","orphanCompletedTask"}) void reverseCoverageRequiresAllRealSourcesAndRejectsInventedOnes(String kind){var f=fixture(true);Set<String> covered=Set.of(NO+":CREDIT");switch(kind){case "missingCoverage"->covered=Set.of();case "extraCoverage"->covered=Set.of(NO+":CREDIT","foreign:CREDIT");case "orphanLedger"->{var l=new HashMap<>(f.ledgers.get(0));l.put("id",55L);l.put("bizNo","orphan");f.ledgers.add(l);}case "orphanEarning"->{var e=new HashMap<>(f.earnings.get(0));e.put("id",55L);e.put("receiptNo","orphan");f.earnings.add(e);}case "orphanReceipt"->{var r=new HashMap<>(f.receipt);r.put("receiptNo","orphan");f.receipts.add(r);}case "extraOperation"->{var op=new HashMap<>(f.operation);op.put("id",99L);op.put("sourceKey","orphan");f.ops.add(op);f.parties.add(row("operationId",99L,"customerId",7L));}case "orphanCompletedTask"->{var t=new HashMap<>(f.task);t.put("taskNo","orphan");f.tasks.add(t);}}var actual=covered;assertThatThrownBy(()->f.coverage(actual)).isInstanceOf(IllegalArgumentException.class);}
    @Test void trulyEmptyOwningSourcesAreEmptyButFailureIsNotAnEmptySource(){var f=fixture(false);f.receipts.clear();f.tasks.clear();f.ledgers.clear();f.earnings.clear();f.ops.clear();f.parties.clear();f.legs.clear();f.raw.clear();f.coverage(Set.of());assertThatThrownBy(()->new FundEarnings(null,List.of(),List.of(),List.of())).isInstanceOf(RuntimeException.class);}
    @Test void peerOperationsDoNotRescanEveryPartyForEachCoverageCheck(){
        var ops=new ArrayList<Map<String,Object>>();var rows=new ArrayList<Map<String,Object>>();
        for(long id=1;id<=2000;id++){
            ops.add(row("id",id,"businessType","SUPPORT_DEVICE_EARNING","sourceKey","peer-"+id,"phase","CREDIT"));
            rows.add(row("operationId",id,"customerId",8L));
        }
        int[] reads={0};
        var parties=new AbstractList<Map<String,Object>>(){
            @Override public Map<String,Object> get(int index){
                if(++reads[0]>rows.size()*4)throw new AssertionError("Coverage repeatedly scanned the complete peer-party history");
                return rows.get(index);
            }
            @Override public int size(){return rows.size();}
        };
        var input=new SupportFundDeviceEvidence.Inputs(new FundEarnings(List.of(),List.of(),List.of(),List.of()),
            List.of(),ops,parties,List.of(),Map.of());
        helper.verifyCoverage(Set.of(7L),input,Set.of());
        helper.verifyCoverage(Set.of(7L),input,Set.of());
        assertThat(reads[0]).isLessThanOrEqualTo(rows.size()*4);
        rows.set(rows.size()-1,row("operationId",2000L,"customerId",7L));
        assertThatThrownBy(()->helper.verifyCoverage(Set.of(7L),input,Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
