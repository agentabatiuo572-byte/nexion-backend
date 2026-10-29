package ffdd.opsconsole.finance.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.team.application.*;
import ffdd.opsconsole.team.facade.SupportDirectReferralReadFacade;
import ffdd.opsconsole.team.mapper.*;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.support.*;

/** Actual Direct service and actual Binder, backed only by explicit storage doubles. */
class SupportFundDirectReferralEvidenceTest {
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final DataSource dataSource=mock(DataSource.class);
    final SupportPaymentFundFixture cash=new SupportPaymentFundFixture(dataSource,json);
    final SupportDirectReferralReadMapper read=mock(SupportDirectReferralReadMapper.class);
    final DirectReferralMapper write=mock(DirectReferralMapper.class);
    final DirectReferralPolicyService policies=mock(DirectReferralPolicyService.class);
    final TreasuryLedgerPostingFacade ledger=mock(TreasuryLedgerPostingFacade.class);
    final SupportDirectReferralReadService sources=new SupportDirectReferralReadService(read);
    final SupportDirectReferralFundAdapter adapter=new SupportDirectReferralFundAdapter(cash.fund,sources,read,json);
    final SupportFundJournalBindingService codec=(SupportFundJournalBindingService)cash.fund;
    final SupportFundDirectReferralEvidence evidence=new SupportFundDirectReferralEvidence(codec,json);
    final Map<String,Object> settlement=new LinkedHashMap<>();
    final List<Map<String,Object>> entries=new ArrayList<>(),events=new ArrayList<>(),ledgers=new ArrayList<>();
    final LocalDateTime at=LocalDateTime.of(2026,10,10,12,0);
    DirectReferralService service;int debitCalls;
    static BigDecimal n(String v){return new BigDecimal(v);}
    @BeforeEach void setup() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.bindResource(dataSource,cash.holder);
        cash.wallet(BigDecimal.ZERO);when(policies.scope()).thenReturn(new DirectReferralPolicyService.Scope("PRODUCTION","",0));
        settlement.put("id",1L);settlement.put("settlement_no","DR-test");settlement.put("source_environment","PRODUCTION");settlement.put("run_id","");settlement.put("source_type","direct_device_earning");settlement.put("source_ref","receipt-original");settlement.put("beneficiary_user_id",7L);
        settlement.put("support_user_sandbox",0);settlement.put("support_user_deleted",0);settlement.put("amount_usdt",n("10"));settlement.put("amount_nex",n("20"));settlement.put("usdt_event_id",11L);settlement.put("nex_event_id",12L);
        settlement.put("credited_at",at);settlement.put("credited_usdt_at",at);settlement.put("credited_nex_at",at);settlement.put("status","UNLOCKED");settlement.put("reversal_recorded",0);settlement.put("refund_ratio",BigDecimal.ZERO);settlement.put("updated_at",at);
        settlement.put("created_at",at);settlement.put("release_at",at);when(write.eventView(anyLong())).thenAnswer(i->events.stream().filter(e->e.get("id").equals(i.getArgument(0))).findFirst().orElse(Map.of()));
        for(String s:List.of("usdt","nex"))for(String prefix:List.of("cancelled_","recovered_","recovery_pending_"))settlement.put(prefix+s,BigDecimal.ZERO);
        for(String asset:List.of("USDT","UVT")) {var event=new LinkedHashMap<String,Object>();event.put("id",asset.equals("USDT")?11L:12L);event.put("user_id",7L);event.put("currency",asset);event.put("amount_usdt",asset.equals("USDT")?n("10"):BigDecimal.ZERO);event.put("amount_nex",asset.equals("UVT")?n("20"):BigDecimal.ZERO);event.put("is_deleted",0);event.put("support_user_sandbox",0);event.put("support_user_deleted",0);event.put("status","UNLOCKED");event.put("version",1L);events.add(event);credit(asset);}
        when(read.settlementsByCustomers(any())).thenAnswer(i->List.of(new LinkedHashMap<>(settlement)));when(read.settlementsByNos(any())).thenAnswer(i->List.of(new LinkedHashMap<>(settlement)));
        when(read.reissuesByCustomers(any())).thenReturn(List.of());when(read.eventsByIds(any())).thenAnswer(i->events.stream().filter(e->i.<Collection<Long>>getArgument(0).contains(e.get("id"))).map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());when(read.entries(anyString())).thenAnswer(i->entries.stream().map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());
        when(read.ledgers(anyString(),anyString())).thenAnswer(i->ledgers.stream().filter(l->l.get("bizNo").equals(i.getArgument(0))||l.get("bizNo").equals(((String)i.getArgument(0)).replace("-RELEASE","-REVERSE"))||((String)l.get("bizNo")).startsWith(i.getArgument(1))).map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());when(read.wallets(anyLong())).thenAnswer(i->cash.mapper.lockWallet(7L));when(read.journal(anyString(),anyLong(),anyLong())).thenAnswer(i->cash.mapper.journal(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
        when(read.previousOperation(anyLong())).thenAnswer(i->cash.operations.values().stream().mapToLong(o->((Number)o.get("id")).longValue()).max().orElse(0));
        when(write.group(anyString())).thenAnswer(i->new LinkedHashMap<>(settlement));when(write.lockGroup(anyString())).thenAnswer(i->new LinkedHashMap<>(settlement));when(write.lockWallet(eq(7L),eq(0))).thenAnswer(i->new DirectReferralMapper.Wallet((BigDecimal)cash.wallet.get("usdtAvailable"),(BigDecimal)cash.wallet.get("nexAvailable")));
        when(write.debit(eq(7L),eq(0),any(),any())).thenAnswer(i->{debitCalls++;cash.advance(i.<BigDecimal>getArgument(2).negate(),i.<BigDecimal>getArgument(3).negate());return 1;});
        doAnswer(i->{String asset=i.getArgument(3);BigDecimal amount=i.getArgument(5);var row=new LinkedHashMap<String,Object>();row.put("id",200L+ledgers.size());row.put("customerId",7L);row.put("bizNo",i.getArgument(0));row.put("bizType",i.getArgument(2));row.put("asset",asset);row.put("direction",i.getArgument(4));row.put("amount",amount);row.put("status","SUCCESS");row.put("deleted",0);row.put("balanceAfter",cash.wallet.get(asset.equals("USDT")?"usdtAvailable":"nexAvailable"));ledgers.add(row);return null;}).when(ledger).postLedgerEntry(anyString(),anyLong(),anyString(),anyString(),anyString(),any(),anyString(),anyString());
        when(write.assetRecovery(anyString(),any(),any(),anyInt(),any(),any(),any(),any(),any(),anyString())).thenAnswer(i->{var keys=List.of("cancelled_usdt","cancelled_nex","reversal_recorded","refund_ratio","recovered_usdt","recovered_nex","recovery_pending_usdt","recovery_pending_nex","status");for(int j=0;j<keys.size();j++)settlement.put(keys.get(j),i.getArgument(j+1));return 1;});
        when(write.recoveredEventStatus(anyLong(),anyString())).thenAnswer(i->{events.stream().filter(e->e.get("id").equals(i.getArgument(0))).forEach(e->{e.put("status",i.getArgument(1));e.put("version",((Number)e.get("version")).longValue()+1);});return 1;});
        when(write.reverseReleaseEntries(anyLong(),anyString(),anyString())).thenAnswer(i->{entries.stream().filter(e->e.get("sourceRef").equals(i.getArgument(1))||e.get("sourceRef").equals(i.getArgument(2))).forEach(e->e.put("status","REVERSED"));return 2;});
        service=new DirectReferralService(write,policies,mock(EarningsReleaseService.class),mock(EarningsReleaseMapper.class),mock(RiskReleaseParamsService.class),ledger,mock(EventOutboxService.class),json,Clock.systemUTC(),null,null,adapter);
    }
    @AfterEach void cleanup() {for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);TransactionSynchronizationManager.setActualTransactionActive(false);}
    void credit(String asset) throws Exception {
        var entry=new LinkedHashMap<String,Object>();String no="ENTRY-"+("USDT".equals(asset)?"USDT":"NEX");
        entry.put("id",asset.equals("USDT")?101L:102L);entry.put("entryNo",no);entry.put("userId",7L);entry.put("clusterId","USER:7");entry.put("sourceType","DIRECT_REFERRAL");entry.put("sourceRef","DR-test:"+("USDT".equals(asset)?"USDT":"NEX"));entry.put("asset",asset);entry.put("amount",n(asset.equals("USDT")?"10":"20"));entry.put("bucket","withdrawable");entry.put("status","ACTIVE");entry.put("idempotencyKey",no);entry.put("sourceEnvironment","PRODUCTION");entry.put("isDeleted",0);entry.put("recoveredAmount",BigDecimal.ZERO);entry.put("debitBaseline",BigDecimal.ZERO);entry.put("sourceWalletId",70L);entry.put("releasedAt",null);entry.put("releaseSource",null);entry.put("createdAt",at);entry.put("updatedAt",at);
        var before=new LinkedHashMap<>(cash.wallet);var token=cash.fund.begin(List.of(7L));cash.advance(asset.equals("USDT")?n("10"):BigDecimal.ZERO,asset.equals("UVT")?n("20"):BigDecimal.ZERO);
        var event=new Event("EARNINGS:"+no,Action.EARNINGS,null,new Account(7,asset,"available"),null,(BigDecimal)entry.get("amount"),null);
        var proof=Map.of("cashBefore",before,"cashAfter",new LinkedHashMap<>(cash.wallet),"cashTrace",List.of(Map.of("sourceId","nx_earnings_release_entry:"+no,"eventIds",List.of(event.id()),"journal",cash.journal.get(cash.journal.size()-1))));
        cash.fund.bind(token,new SupportFundJournalFacade.ReceiptInput("EARNINGS:CREDIT:"+no,"SUPPORT_EARNINGS","CREDIT","nx_earnings_release_entry",no,"PRODUCTION",encode(List.of(event)),encode(Map.of("entry",entry)),encode(proof)));entries.add(entry);
    }
    String encode(Object value) throws Exception {return json.writeValueAsString(normalize(value));}
    Object normalize(Object value) {if(value instanceof LocalDateTime t)return t.toString();if(value instanceof Map<?,?> m){var r=new LinkedHashMap<String,Object>();m.forEach((k,v)->r.put(k.toString(),normalize(v)));return r;}if(value instanceof Collection<?> rows)return rows.stream().map(this::normalize).toList();return value;}
    void adjust(String usdt,String nex) {
        try {var before=new LinkedHashMap<>(cash.wallet);var token=cash.fund.begin(List.of(7L));cash.advance(n(usdt),n(nex));var actions=new ArrayList<Event>();String key="FIXTURE:OTHER:"+cash.operations.size();
            for(String asset:List.of("USDT","UVT")){BigDecimal amount=n(asset.equals("USDT")?usdt:nex);if(amount.signum()!=0)actions.add(new Event(key+":"+asset,amount.signum()>0?Action.DEPOSIT:Action.CONSUME,amount.signum()<0?new Account(7,asset,"available"):null,amount.signum()>0?new Account(7,asset,"available"):null,null,amount.abs(),null));}
            cash.fund.bind(token,new SupportFundJournalFacade.ReceiptInput(key,"TEST_OTHER_SOURCE","ADJUST","test_other_source",key,"PRODUCTION",encode(actions),encode(Map.of("fixture","explicit other-source storage double")),encode(Map.of("cashBefore",before,"cashAfter",new LinkedHashMap<>(cash.wallet)))));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    final class ReissueFixture {
        final Map<String,Object> event=new LinkedHashMap<>(),projected=new LinkedHashMap<>(),root=new LinkedHashMap<>();final List<Map<String,Object>> recovery=new ArrayList<>();
        ReissueFixture() {
            event.put("id",55L);event.put("user_id",7L);event.put("currency","USDT");event.put("amount_usdt",n("10"));event.put("amount_nex",BigDecimal.ZERO);event.put("status","UNLOCKED");event.put("version",1L);event.put("is_deleted",0);event.put("support_user_sandbox",0);event.put("support_user_deleted",0);event.put("updated_at",at);events.add(event);
            for(var pair:Map.of("id","id","user_id","customerId","currency","asset","amount_usdt","amountUsdt","amount_nex","amountNex","status","status","version","version","updated_at","updatedAt").entrySet())projected.put(pair.getValue(),event.get(pair.getKey()));
            projected.put("commissionType","network");projected.put("sourceUserId",9L);projected.put("sourceUserName","Origin***");projected.put("layerNo",2);projected.put("orderNo","REISSUE-55");projected.put("orderAmountUsd",n("100"));projected.put("unlockAt",at);projected.put("frozenFromStatus",null);projected.put("remark","Seven layer reissue");projected.put("createdAt",at);projected.put("deleted",0);projected.put("userSandbox",0);projected.put("userDeleted",0);
            root.put("id",8L);root.put("order_no","ORDER-refund");root.put("user_id",9L);root.put("paid_at",at);root.put("payment_status","REFUNDED");root.put("order_status","REFUNDED");root.put("is_deleted",0);root.put("support_original_event_id",44L);root.put("support_root_depth",1);root.put("support_traversal_max_depth",1);root.put("support_invalid_ancestor",0);root.put("support_missing_ancestor",0);root.put("support_max_parent_count",1);root.put("support_initial_parent_count",1);
            when(read.reissuesByCustomers(any())).thenAnswer(i->List.of(new LinkedHashMap<>(event)));when(read.reissueRootOrders(55L)).thenAnswer(i->List.of(new LinkedHashMap<>(root)));when(read.recoveries(55L)).thenAnswer(i->recovery.stream().map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());
            when(write.purchase("ORDER-refund")).thenReturn(new DirectReferralMapper.Source(9L,7L,"Origin***",at,n("100"),BigDecimal.ZERO,null,0,1,1));when(write.lockOrder("PRODUCTION","","ORDER-refund")).thenReturn(Map.of("settlement_mode","SEVEN_V2"));
            when(write.reissueDescendants("ORDER-refund")).thenAnswer(i->List.of(Map.of("id",55L,"user_id",7L,"currency","USDT","amount",n("10"),"credited",1,"reversed",0)));
            when(write.eventRecovery(55L)).thenAnswer(i->recovery.isEmpty()?null:new LinkedHashMap<>(recovery.get(0)));
            when(write.saveEventRecovery(eq(55L),eq("ORDER-refund"),eq("PRODUCTION"),eq(""),eq(7L),eq("USDT"),any(),any(),any())).thenAnswer(i->{if(recovery.isEmpty()){var row=new LinkedHashMap<String,Object>();for(var pair:Map.of("event_id",0,"order_no",1,"source_environment",2,"run_id",3,"user_id",4,"asset",5,"target_amount",6).entrySet())row.put(pair.getKey(),i.getArgument(pair.getValue()));recovery.add(row);}recovery.get(0).put("recovered_amount",i.getArgument(7));recovery.get(0).put("pending_amount",i.getArgument(8));recovery.get(0).put("updated_at",at);return 1;});
            var commissionMapper=mock(ffdd.opsconsole.treasury.mapper.SupportCommissionReadMapper.class);var originals=mock(ffdd.opsconsole.treasury.facade.SupportCommissionReadFacade.class);
            when(commissionMapper.fundWallets(7L)).thenAnswer(i->{var wallet=new LinkedHashMap<String,Object>();for(String k:List.of("id","incarnation","revision","version","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","sandbox","createdAt"))wallet.put(k,cash.wallet.get(k));wallet.put("customerId",7L);wallet.put("deleted",0);wallet.put("userSandbox",0);wallet.put("userDeleted",0);return List.of(wallet);});
            when(originals.readFundCommissions(any())).thenAnswer(i->new ffdd.opsconsole.treasury.facade.SupportCommissionReadFacade.Sources(List.of(new LinkedHashMap<>(projected)),ledgers.stream().filter(l->((String)l.get("bizNo")).startsWith("F5-COMMISSION-")).toList()));
            var originalAdapter=new ffdd.opsconsole.treasury.application.SupportCommissionFundAdapter(cash.fund,commissionMapper,originals,json);var before=originalAdapter.begin(7L,55L,"RELEASE");cash.advance(n("10"),BigDecimal.ZERO);
            var release=new LinkedHashMap<String,Object>();release.put("id",300L);release.put("customerId",7L);release.put("bizNo","F5-COMMISSION-55-RELEASE");release.put("bizType","TEAM_COMMISSION");release.put("asset","USDT");release.put("direction","IN");release.put("amount",n("10"));release.put("balanceAfter",cash.wallet.get("usdtAvailable"));release.put("status","SUCCESS");release.put("remark","Commission wallet release | eventId=55");release.put("deleted",0);release.put("createdAt",at);release.put("updatedAt",at);ledgers.add(release);originalAdapter.recorded(before);
        }
        void recover(){service.recoverOrderReissues("ORDER-refund");projected.put("status",event.get("status"));projected.put("version",event.get("version"));}
        void verify(){var in=input();var token=evidence.validate(Set.of(7L),in);assertFalse(token.allows(entries.get(0),codec,json));for(var op:cash.operations.values())if("SUPPORT_DIRECT_REFERRAL".equals(op.get("businessType")))evidence.verify(op,codec.read((String)op.get("sourceSnapshotJson")),codec.read((String)op.get("sourceProofJson")),codec.decodeReceipt(op,List.of(7L),cash.legs.getOrDefault(((Number)op.get("id")).longValue(),List.of())),in);}
    }
    @Test void coverageDoesNotRescanAllPartiesForEachPeerOperationOrHideLaterOwnedOrphan() {
        var ops=new ArrayList<Map<String,Object>>();var rows=new ArrayList<Map<String,Object>>();
        for(long id=1;id<=2000;id++){
            ops.add(Map.of("id",id,"businessType","SUPPORT_DIRECT_REFERRAL","operationKey","PEER:"+id,"sourceKey","S:"+id));
            rows.add(Map.of("operationId",id,"customerId",9L));
        }
        int[] reads={0};var observed=new AbstractList<Map<String,Object>>(){
            @Override public Map<String,Object> get(int index){
                if(++reads[0]>rows.size()*8)throw new AssertionError("Direct coverage repeatedly scanned the complete party history");
                return rows.get(index);
            }
            @Override public int size(){return rows.size();}
        };
        var in=new SupportFundDirectReferralEvidence.Inputs(List.of(),ops,observed,List.of(),List.of(),Map.of());
        evidence.verifyCoverage(Set.of(7L),in,Set.of());evidence.verifyCoverage(Set.of(7L),in,Set.of());
        rows.add(Map.of("operationId",1L,"customerId",7L));
        assertThrows(IllegalArgumentException.class,()->evidence.verifyCoverage(Set.of(7L),in,Set.of()));
    }
    @Test void indexedCoveragePreservesEmptyAndUnmatchedPartyInterpretation() {
        var empty=new SupportFundDirectReferralEvidence.Inputs(List.of(),List.of(Map.of("businessType","SUPPORT_DIRECT_REFERRAL")),List.of(),List.of(),List.of(),Map.of());
        assertDoesNotThrow(()->evidence.verifyCoverage(Set.of(7L),empty,Set.of()));
        var op=Map.<String,Object>of("id",1L,"businessType","SUPPORT_DIRECT_REFERRAL");
        var unmatched=new SupportFundDirectReferralEvidence.Inputs(List.of(),List.of(op),List.of(Map.of("operationId",2L,"customerId","uninterpreted")),List.of(),List.of(),Map.of());
        assertDoesNotThrow(()->evidence.verifyCoverage(Set.of(7L),unmatched,Set.of()));
        var malformed=new SupportFundDirectReferralEvidence.Inputs(List.of(),List.of(op),List.of(Map.of("operationId",n("1.5"),"customerId",9L)),List.of(),List.of(),Map.of());
        assertThrows(IllegalArgumentException.class,()->evidence.verifyCoverage(Set.of(7L),malformed,Set.of()));
    }
    SupportFundDirectReferralEvidence.Inputs input(){return new SupportFundDirectReferralEvidence.Inputs(sources.readByCustomers(List.of(7L)),new ArrayList<>(cash.operations.values()),cash.parties.values().stream().flatMap(Collection::stream).toList(),cash.legs.values().stream().flatMap(Collection::stream).toList(),cash.journal,Map.of("nx_direct_referral_settlement.updated_at",3,"nx_unilevel_event_recovery.updated_at",3,"nx_earnings_release_entry.created_at",0));}
    Map<String,Object> last(){return cash.operations.values().stream().filter(o->"SUPPORT_DIRECT_REFERRAL".equals(o.get("businessType"))).reduce((a,b)->b).orElseThrow();}
    void verifyAll(){var input=input();var token=evidence.validate(Set.of(7L),input);var covered=new HashSet<String>();for(var op:cash.operations.values())if("SUPPORT_DIRECT_REFERRAL".equals(op.get("businessType"))){var result=evidence.verify(op,codec.read((String)op.get("sourceSnapshotJson")),codec.read((String)op.get("sourceProofJson")),codec.decodeReceipt(op,List.of(7L),cash.legs.getOrDefault(((Number)op.get("id")).longValue(),List.of())),input);covered.add(result.coveredPhase());}evidence.verifyCoverage(Set.of(7L),input,covered);assertTrue(token.allows(entries.get(0),codec,json));}
    @Test void dualAssetRecoveryIsOneActualDebitAndBinderLegThenAuthorizesOnlyExactReversedCredit() {service.reverse("DR-test",BigDecimal.ONE);assertEquals(1,debitCalls);assertEquals(2,ledgers.size());assertEquals(1,cash.legs.get(((Number)last().get("id")).longValue()).size());verifyAll();assertEquals(BigDecimal.ZERO,entries.get(0).get("recoveredAmount"));}
    @Test void zeroCashReversalRetainsRealCheckpointAndHistoricalCreditStrictDefault() {adjust("-10","-20");service.reverse("DR-test",BigDecimal.ONE);assertEquals(0,debitCalls);var op=last();assertTrue(cash.legs.getOrDefault(((Number)op.get("id")).longValue(),List.of()).isEmpty());verifyAll();var earning=new SupportFundEarningsEvidence(codec,json);var credit=cash.operations.get("EARNINGS:CREDIT:ENTRY-USDT");assertThrows(IllegalArgumentException.class,()->earning.verify(credit,codec.read((String)credit.get("sourceSnapshotJson")),codec.read((String)credit.get("sourceProofJson")),List.of(new Event("EARNINGS:ENTRY-USDT",Action.EARNINGS,null,new Account(7,"USDT","available"),null,n("10"),null)),new SupportFundEarningsEvidence.Inputs(entries,List.of(),List.of(),List.of(),cash.journal,Map.of("nx_earnings_release_entry.created_at",0))));}
    @Test void partialThenLaterFundsRetryPreservesOldFormulaAndCompleteCumulativeChain() {adjust("-7","-15");service.reverse("DR-test",BigDecimal.ONE);assertEquals("RECOVERY_PENDING",settlement.get("status"));adjust("50","50");service.retryRecovery("DR-test");assertEquals(2,debitCalls);assertEquals("REVERSED",settlement.get("status"));verifyAll();assertEquals(n("43"),cash.wallet.get("usdtAvailable"));}
    @Test void alteredCumulativeTailAndMissingReceiptFailClosed() {service.reverse("DR-test",BigDecimal.ONE);settlement.put("recovered_usdt",n("9"));assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));settlement.put("recovered_usdt",n("10"));cash.operations.remove(last().get("operationKey"));assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));}
    @Test void staleCapabilityFractionalIdentityAndMissingOutLedgerCannotAuthorize() {service.reverse("DR-test",BigDecimal.ONE);var token=evidence.validate(Set.of(7L),input());entries.get(0).put("amount",n("11"));assertFalse(token.allows(entries.get(0),codec,json));entries.get(0).put("amount",n("10"));settlement.put("beneficiary_user_id",n("7.5"));assertThrows(IllegalArgumentException.class,()->sources.readByCustomers(List.of(7L)).stream().map(SupportDirectReferralReadFacade::customer).toList());settlement.put("beneficiary_user_id",7L);ledgers.clear();assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));}
    @Test void missingStatisticalCreditRemainsUnknownWithoutBlockingLegitimateCash() {entries.clear();service.reverse("DR-test",BigDecimal.ONE);assertEquals(1,debitCalls);assertEquals(2,ledgers.size());assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));}
    @Test void failureStateIsBoundWithoutCashOrReversedAuthorityThenRetryClosesActualChain() {service.markRecoveryFailure("DR-test");assertEquals("FAILURE",last().get("phase"));assertEquals(0,debitCalls);assertEquals("ACTIVE",entries.get(0).get("status"));assertFalse(evidence.validate(Set.of(7L),input()).allows(entries.get(0),codec,json));service.retryRecovery("DR-test");verifyAll();assertEquals(1,debitCalls);}
    @Test void paidNetworkSingleEventRecoveryPreservesOtherAssetAndItsActiveCredit() {firstPaidNetworkReversal("PAID");}
    @Test void actualValidatedReversalTokenLetsCentralHistoricalCreditVerifyAndCoverageRemainStrict() {
        service.reverse("DR-test",BigDecimal.ONE);var token=evidence.validate(Set.of(7L),input());var central=new SupportFundEarningsEvidence(codec,json);
        var inputs=new SupportFundEarningsEvidence.Inputs(entries,List.of(),List.of(),List.of(),cash.journal,Map.of("nx_earnings_release_entry.created_at",0),token);var covered=new HashSet<String>();
        for(var op:cash.operations.values())if("SUPPORT_EARNINGS".equals(op.get("businessType"))){var result=central.verify(op,codec.read((String)op.get("sourceSnapshotJson")),codec.read((String)op.get("sourceProofJson")),codec.decodeReceipt(op,List.of(7L),cash.legs.get(((Number)op.get("id")).longValue())),inputs);covered.add(result.coveredPhase());}
        central.verifyCoverage(Set.of(7L),inputs,covered);assertEquals(Set.of("ENTRY-USDT:CREDIT","ENTRY-NEX:CREDIT"),covered);assertFalse(token.allows(Map.of("entryNo","ENTRY-USDT","sourceType","DIRECT_REFERRAL","status","REVERSED"),codec,json));
    }
    @Test void reissuedCommissionUsesActualOriginalAdapterAndBinderThenPartialAndFullRecoveryChain() {var r=new ReissueFixture();adjust("-17","0");r.recover();assertEquals(n("3"),r.recovery.get(0).get("recovered_amount"));assertEquals("RECOVERY_PENDING",r.event.get("status"));adjust("20","0");r.recover();assertEquals(n("10"),r.recovery.get(0).get("recovered_amount"));assertEquals("REVERSED",r.event.get("status"));r.verify();assertEquals(2,debitCalls);}
    @Test void reissueZeroCashHasActualEventRecoveryAndCheckpointWithoutInventedCentralRecovery() {var r=new ReissueFixture();adjust("-20","0");r.recover();assertEquals(0,debitCalls);assertEquals(n("10"),r.recovery.get(0).get("pending_amount"));assertEquals("REISSUE",last().get("phase"));r.verify();}
    @Test void cyclicMissingAmbiguousOrNonRefundedReissueAncestryStaysUnknownAfterLegitimateCash() {var r=new ReissueFixture();r.recover();r.verify();for(String field:List.of("support_traversal_max_depth","support_missing_ancestor","support_max_parent_count","support_initial_parent_count")){Object old=r.root.get(field);r.root.put(field,field.equals("support_traversal_max_depth")?32:field.equals("support_missing_ancestor")?1:2);assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));r.root.put(field,old);}r.root.put("payment_status","PAID");r.root.put("order_status","PAID");assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));assertEquals(1,debitCalls);}
    @Test void actualPrecisionReadKeepsCreatedAtKeyAndRejectsDuplicateOrFractionalPrecision() {
        var rows=new ArrayList<Map<String,Object>>();rows.add(Map.of("tableName","nx_earnings_release_entry","columnName","created_at","precision",0));rows.add(Map.of("tableName","nx_direct_referral_settlement","columnName","updated_at","precision",3));when(read.timePrecisions()).thenAnswer(i->rows);
        assertEquals(Map.of("nx_earnings_release_entry.created_at",0,"nx_direct_referral_settlement.updated_at",3),sources.timePrecisions());rows.add(rows.get(0));assertThrows(IllegalArgumentException.class,sources::timePrecisions);rows.remove(2);rows.set(0,Map.of("tableName","nx_earnings_release_entry","columnName","created_at","precision",n("0.5")));assertThrows(ArithmeticException.class,sources::timePrecisions);
    }
    static java.util.stream.Stream<Arguments> wrongLinkedEvents() {return Map.<String,Object>of("user_id",8L,"currency","UVT","amount_usdt",n("11"),"is_deleted",1,"support_user_sandbox",1,"support_user_deleted",1).entrySet().stream().map(p->Arguments.of(p.getKey(),p.getValue()));}
    @ParameterizedTest @MethodSource("wrongLinkedEvents") void wrongLinkedEventCannotAuthorizeEvenWhenCapturedAsHistoricalFacts(String field,Object wrong) {
        events.get(0).put(field,wrong);service.reverse("DR-test",BigDecimal.ONE);assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));assertEquals(1,debitCalls);
    }
    Map<String,Object> firstPaidNetworkReversal(String orderStatus) {
        settlement.put("source_type","network");settlement.put("source_ref","ORDER-paid");var root=new LinkedHashMap<String,Object>();
        root.put("id",8L);root.put("order_no","ORDER-paid");root.put("user_id",9L);root.put("paid_at",at);root.put("payment_status","PAID");root.put("order_status",orderStatus);root.put("is_deleted",0);root.put("support_root_depth",0);
        when(read.orders("ORDER-paid")).thenAnswer(i->List.of(new LinkedHashMap<>(root)));
        when(write.purchase("ORDER-paid")).thenAnswer(i->new DirectReferralMapper.Source(9L,7L,"Origin***",at,n("100"),BigDecimal.ZERO,null,0,"REFUNDED".equals(root.get("payment_status"))?1:0,1));
        when(write.groupForEvent(11L)).thenReturn("DR-test");when(write.lockEvents(11L,12L)).thenAnswer(i->events.stream().map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());
        service.reverseEvent(11L);assertEquals(1,debitCalls);assertEquals("ACTIVE",entries.get(1).get("status"));assertEquals(n("20"),cash.wallet.get("nexAvailable"));verifyAll();return root;
    }
    void originalOrderRefunds(Map<String,Object> root) {root.put("payment_status","REFUNDED");root.put("order_status","REFUNDED");}
    void refundRemainingNetworkAsset() {
        when(write.lockOrder("PRODUCTION","","ORDER-paid")).thenReturn(Map.of("id",1L,"settlement_mode","SEVEN_V2"));when(write.orderGroups("PRODUCTION","","ORDER-paid")).thenReturn(List.of("DR-test"));when(write.reissueDescendants("ORDER-paid")).thenReturn(List.of());service.refund("ORDER-paid");
    }
    @ParameterizedTest @ValueSource(strings={"PAID","PROVISIONING","PROVISIONING_FAILED","COMPLETED"}) void actualOrderRefundOnlyAdvancesItsTwoStatusesWithoutChangingProvenCash(String initial) {
        var root=firstPaidNetworkReversal(initial);var wallet=new LinkedHashMap<>(cash.wallet);int operations=cash.operations.size();originalOrderRefunds(root);verifyAll();assertEquals(wallet,cash.wallet);assertEquals(operations,cash.operations.size());assertEquals(1,debitCalls);
    }
    @ParameterizedTest @ValueSource(strings={"PAID","PROVISIONING","PROVISIONING_FAILED","COMPLETED"}) void laterActualRefundRecoversTheOtherAssetAndRetainsFirstReceipt(String initial) {
        var root=firstPaidNetworkReversal(initial);originalOrderRefunds(root);refundRemainingNetworkAsset();assertEquals(2,debitCalls);assertEquals(2,ledgers.size());assertEquals("REVERSED",entries.get(1).get("status"));assertEquals(0,((BigDecimal)cash.wallet.get("nexAvailable")).signum());verifyAll();
    }
    @Test void laterOtherAssetRefundAndPendingRetryRetainThePaidToRefundedSourceChain() {
        var root=firstPaidNetworkReversal("COMPLETED");adjust("0","-17");originalOrderRefunds(root);refundRemainingNetworkAsset();assertEquals("RECOVERY_PENDING",settlement.get("status"));verifyAll();adjust("0","50");service.retryRecovery("DR-test");assertEquals(3,debitCalls);assertEquals(3,ledgers.size());assertEquals("REVERSED",settlement.get("status"));assertEquals(n("33"),cash.wallet.get("nexAvailable"));verifyAll();
    }
    static java.util.stream.Stream<Arguments> invalidRootChanges() {return java.util.stream.Stream.of(Arguments.of("payment_status","PAID"),Arguments.of("order_status","PAID"),Arguments.of("user_id",10L),Arguments.of("paid_at",LocalDateTime.of(2026,10,10,12,0,1)),Arguments.of("paid_at",null),Arguments.of("id",9L),Arguments.of("order_no","FORGED"),Arguments.of("is_deleted",1),Arguments.of("support_root_depth",1),Arguments.of("order_status","CHARGEBACK"));}
    @ParameterizedTest @MethodSource("invalidRootChanges") void singleSidedRefundOrChangedOrderIdentityTimeAndAncestryStayUnknown(String field,Object wrong) {
        var root=firstPaidNetworkReversal("COMPLETED");originalOrderRefunds(root);root.put(field,wrong);assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));assertEquals(1,debitCalls);
    }
    @Test void capturedRefundedOrderCannotMoveBackToPaid() {
        var root=firstPaidNetworkReversal("COMPLETED");originalOrderRefunds(root);refundRemainingNetworkAsset();verifyAll();root.put("payment_status","PAID");root.put("order_status","COMPLETED");assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));assertEquals(2,debitCalls);
    }
    static java.util.stream.Stream<Arguments> paidOrderAdvances() {return java.util.stream.Stream.of(Arguments.of("PAID","PROVISIONING"),Arguments.of("PAID","PROVISIONING_FAILED"),Arguments.of("PAID","COMPLETED"),Arguments.of("PROVISIONING","PROVISIONING_FAILED"),Arguments.of("PROVISIONING","COMPLETED"));}
    @ParameterizedTest @MethodSource("paidOrderAdvances") void actualPaidActivationGraphKeepsCashAndTheNextOtherAssetCapture(String initial,String next) {
        var root=firstPaidNetworkReversal(initial);var wallet=new LinkedHashMap<>(cash.wallet);int operations=cash.operations.size();root.put("order_status",next);verifyAll();assertEquals(wallet,cash.wallet);assertEquals(operations,cash.operations.size());
        when(write.groupForEvent(12L)).thenReturn("DR-test");service.reverseEvent(12L);assertEquals(2,debitCalls);assertEquals(2,ledgers.size());verifyAll();
    }
    static java.util.stream.Stream<Arguments> paidOrderRegressions() {return java.util.stream.Stream.of(Arguments.of("PROVISIONING","PAID"),Arguments.of("COMPLETED","PAID"),Arguments.of("PROVISIONING_FAILED","PAID"),Arguments.of("COMPLETED","PROVISIONING_FAILED"),Arguments.of("PROVISIONING_FAILED","COMPLETED"),Arguments.of("COMPLETED","PROVISIONING"));}
    @ParameterizedTest @MethodSource("paidOrderRegressions") void reverseOrCrossTerminalPaidStatesStayUnknown(String initial,String next) {var root=firstPaidNetworkReversal(initial);root.put("order_status",next);assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));assertEquals(1,debitCalls);}
    @Test void actualChargebackHasNoInventedDirectRefundOrCashAuthority() {var root=firstPaidNetworkReversal("PAID");root.put("payment_status","CHARGEBACK");root.put("order_status","CHARGEBACK");assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));assertEquals(1,debitCalls);assertEquals("ACTIVE",entries.get(1).get("status"));}
    void nativeBooleanWalletFlags(boolean zeroCash,String changedRawField,Object changedRawValue) {
        if(zeroCash)adjust("-10","-20");cash.wallet.put("sandbox",false);cash.wallet.put("isDeleted",false);
        when(write.debit(eq(7L),eq(0),any(),any())).thenAnswer(i->{debitCalls++;cash.advance(i.<BigDecimal>getArgument(2).negate(),i.<BigDecimal>getArgument(3).negate());var raw=cash.journal.get(cash.journal.size()-1);for(String side:List.of("old","new"))for(String field:List.of("Sandbox","IsDeleted"))raw.put(side+field,0);if(changedRawField!=null)raw.put(changedRawField,changedRawValue);return 1;});
        if(zeroCash&&changedRawField!=null)cash.journal.get(cash.journal.size()-1).put(changedRawField,changedRawValue);
        service.reverse("DR-test",BigDecimal.ONE);
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void actualBooleanWalletAndNumericRawFlagsVerifyCashAndZeroCheckpoint(boolean zeroCash) {
        nativeBooleanWalletFlags(zeroCash,null,null);var proof=codec.read((String)last().get("sourceProofJson"));assertTrue(proof.path("cashBefore").path("sandbox").isBoolean());assertTrue(proof.path("cashAfter").path("isDeleted").isBoolean());assertEquals(zeroCash?0:1,debitCalls);verifyAll();
    }
    static java.util.stream.Stream<Arguments> invalidRawFlags() {return java.util.stream.Stream.of("oldSandbox","newSandbox","oldIsDeleted","newIsDeleted").flatMap(field->java.util.stream.Stream.of(1,2,-1,"0",null,n("0.5")).map(value->Arguments.of(field,value)));}
    @ParameterizedTest @MethodSource("invalidRawFlags") void changedMissingOrNonBinaryNativeFlagsRemainUnproven(String field,Object wrong) {
        var failure=assertThrows(IllegalStateException.class,()->nativeBooleanWalletFlags(false,field,wrong));assertEquals(Integer.valueOf(1).equals(wrong)?"FUND_JOURNAL_TUPLE_MISMATCH":"INVALID_FUND_ROW:"+field,failure.getMessage());assertTrue(cash.operations.values().stream().noneMatch(o->"SUPPORT_DIRECT_REFERRAL".equals(o.get("businessType"))));
    }
    @ParameterizedTest @MethodSource("invalidRawFlags") void readbackStillRejectsChangedMissingOrNonBinaryNativeFlags(String field,Object wrong) {
        nativeBooleanWalletFlags(false,null,null);verifyAll();cash.journal.get(cash.journal.size()-1).put(field,wrong);assertThrows(IllegalArgumentException.class,()->evidence.validate(Set.of(7L),input()));
    }
}
