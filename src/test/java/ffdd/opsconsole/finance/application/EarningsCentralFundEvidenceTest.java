package ffdd.opsconsole.finance.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.facade.SupportFundJournalFacade;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.finance.mapper.SupportFundJournalMapper;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.risk.dto.EarningsManualReleaseRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Actual central service and adapter; only SQL storage and the transactional Binder boundary are doubles. */
class EarningsCentralFundEvidenceTest {
    final ObjectMapper json=new ObjectMapper();
    final EarningsReleaseMapper mapper=mock(EarningsReleaseMapper.class);
    final SupportFundJournalMapper raw=mock(SupportFundJournalMapper.class);
    final SupportFundJournalFacade journal=mock(SupportFundJournalFacade.class);
    final RiskReleaseParamsService params=mock(RiskReleaseParamsService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final FundsSandboxProfileGuard profile=mock(FundsSandboxProfileGuard.class);
    final Map<String,Object> wallet=new LinkedHashMap<>(),entry=new LinkedHashMap<>();
    final List<Map<String,Object>> raws=new ArrayList<>(),audits=new ArrayList<>(),recoveries=new ArrayList<>(),applied=new ArrayList<>();
    final List<SupportFundJournalFacade.ReceiptInput> receipts=new ArrayList<>();
    final SupportFundEarningsEvidence evidence=new SupportFundEarningsEvidence(
        new SupportFundJournalBindingService(raw,mock(DataSource.class),json),json);
    final SupportFundEarningsAdapter adapter=new SupportFundEarningsAdapter(journal,raw,mapper,json);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final EarningsReleaseService service=new EarningsReleaseService(mapper,params,idempotency,audit,profile,adapter);
    final LocalDateTime at=LocalDateTime.of(2026,10,11,12,0);
    static BigDecimal n(String value){return new BigDecimal(value).setScale(6);}

    @BeforeEach void storage() {
        wallet.putAll(Map.of("id",70L,"userId",7L,"usdtAvailable",n("0"),"nexAvailable",n("0"),"pendingWithdraw",n("0"),"cregisRiskHeld",n("0"),"version",0L,"revision",0L,"incarnation","wallet-seven"));
        wallet.put("sandbox",false);wallet.put("isDeleted",false);wallet.put("createdAt",at);
        when(profile.isStrictProductionRuntime()).thenReturn(true);
        when(params.freezeFrom()).thenReturn(3);when(params.pendingFrom()).thenReturn(2);
        when(idempotency.executeRetained(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
        when(params.requireCoverageForAmplifyingRelease()).thenReturn(new TreasuryCoverageSnapshot(n("120"),n("100"),true));
        when(journal.begin(anyCollection())).thenAnswer(i->new SupportFundJournalFacade.Before(){});
        when(raw.lockWallet(7L)).thenAnswer(i->List.of(new LinkedHashMap<>(wallet)));
        when(raw.journal(anyString(),anyLong(),anyLong())).thenAnswer(i->raws.stream()
            .filter(r->((Number)r.get("revision")).longValue()>i.<Long>getArgument(1)&&((Number)r.get("revision")).longValue()<=i.<Long>getArgument(2)).toList());
        when(mapper.previousFundOperation(7L)).thenAnswer(i->receipts.isEmpty()?null:(long)receipts.size());
        when(journal.bind(any(),any())).thenAnswer(i->{var receipt=i.<SupportFundJournalFacade.ReceiptInput>getArgument(1);receipts.add(receipt);
            return new SupportFundJournalFacade.BoundReceipt(receipts.size(),receipt.operationKey(),"a".repeat(64),raws.size());});
        when(mapper.insert(any())).thenAnswer(i->{var write=i.<EarningsReleaseMapper.EntryWrite>getArgument(0);
            entry.put("id",100L);entry.put("entryNo",write.entryNo());entry.put("userId",write.userId());entry.put("clusterId",write.clusterId());
            entry.put("sourceType",write.sourceType());entry.put("sourceRef",write.sourceRef());entry.put("asset",write.asset());entry.put("amount",write.amount());
            entry.put("bucket",write.bucket());entry.put("idempotencyKey",write.idempotencyKey());entry.put("sourceEnvironment",write.sourceEnvironment());
            entry.put("status","ACTIVE");entry.put("isDeleted",false);entry.put("recoveredAmount",n("0"));entry.put("debitBaseline",n("0"));
            entry.put("sourceWalletId",70L);entry.put("releasedAt",null);entry.put("releaseSource",null);entry.put("createdAt",at);entry.put("updatedAt",at);return 1;});
        when(mapper.creditUsdt(eq(7L),any(),eq("PRODUCTION"),eq(0))).thenAnswer(i->{credit("usdtAvailable",i.getArgument(1));return 1;});
        when(mapper.creditNex(eq(7L),any(),eq("PRODUCTION"),eq(0))).thenAnswer(i->{credit("nexAvailable",i.getArgument(1));return 1;});
        when(mapper.fundEntry(anyString())).thenAnswer(i->new LinkedHashMap<>(entry));
        when(mapper.fundEntryRoute(anyString())).thenAnswer(i->Map.of("userId",7L,"sourceEnvironment","PRODUCTION"));
        when(mapper.lockProtectedEntry(anyString())).thenAnswer(i->new EarningsReleaseMapper.ProtectedEntry((String)entry.get("entryNo"),7L,(String)entry.get("clusterId"),(String)entry.get("asset"),(BigDecimal)entry.get("amount"),(String)entry.get("bucket")));
        when(mapper.release(anyString(),eq("manual"))).thenAnswer(i->{entry.put("bucket","withdrawable");entry.put("releasedAt",at);entry.put("releaseSource","manual");return 1;});
        doAnswer(i->{var write=i.<AuditLogWriteRequest>getArgument(0);var row=new LinkedHashMap<String,Object>();row.put("id",(long)audits.size()+1);
            row.put("action",write.getAction());row.put("resourceType",write.getResourceType());row.put("resourceId",write.getResourceId());
            row.put("result","SUCCESS");row.put("isDeleted",false);row.put("detailJson",json.writeValueAsString(write.getDetail()));row.put("createdAt",at);audits.add(row);return null;})
            .when(audit).recordRequired(any());
        when(mapper.fundReleaseAudit(anyString(),anyString())).thenAnswer(i->new LinkedHashMap<>(audits.get(audits.size()-1)));
    }
    void credit(String field,BigDecimal amount) {
        var before=new LinkedHashMap<>(wallet);wallet.put(field,((BigDecimal)wallet.get(field)).add(amount));
        wallet.put("revision",((Number)wallet.get("revision")).longValue()+1);wallet.put("version",wallet.get("revision"));
        var row=new LinkedHashMap<String,Object>();row.put("incarnation",wallet.get("incarnation"));row.put("revision",wallet.get("revision"));
        for(String key:List.of("id","userId","usdtAvailable","nexAvailable","pendingWithdraw","cregisRiskHeld","version","createdAt")) {
            String suffix=Character.toUpperCase(key.charAt(0))+key.substring(1);row.put("old"+suffix,before.get(key));row.put("new"+suffix,wallet.get(key));
        }
        row.put("oldSandbox",0);row.put("newSandbox",0);row.put("oldIsDeleted",0);row.put("newIsDeleted",0);raws.add(row);
    }
    SupportFundEarningsEvidence.Inputs inputs(){return new SupportFundEarningsEvidence.Inputs(List.of(new LinkedHashMap<>(entry)),recoveries,applied,audits,raws,
        Map.of("nx_earnings_release_entry.created_at",0,"nx_earnings_release_entry.released_at",0,"nx_earnings_source_recovery.created_at",3));}
    Map<String,Object> operation(int index){var r=receipts.get(index);return Map.of("id",(long)index+1,"businessType",r.businessType(),"phase",r.phase(),"operationKey",r.operationKey(),"sourceTable",r.sourceTable(),"sourceKey",r.sourceKey(),"sourceEnvironment",r.sourceEnvironment());}
    List<Event> events(int index)throws Exception{return Arrays.asList(json.readValue(receipts.get(index).orderedEventsJson(),Event[].class));}
    SupportFundEarningsEvidence.Verified verifyReceipt(int index)throws Exception {var r=receipts.get(index);return evidence.verify(operation(index),json.readTree(r.sourceSnapshotJson()),json.readTree(r.sourceProofJson()),events(index),inputs());}
    String protectedCredit(String asset){return service.credit(7L,"USER:7","PROMOTION_REWARD","reward-7",asset,n("20"),"bonus_locked","idem-7");}
    void recoveryStorage(BigDecimal debited) {
        when(mapper.lockRecoveryUser(7L,0)).thenReturn(7L);
        when(mapper.lockRecoveryWallet(7L,0)).thenAnswer(i->new EarningsReleaseMapper.RecoveryWallet(70L,(BigDecimal)wallet.get("usdtAvailable"),(BigDecimal)wallet.get("nexAvailable"),debited,debited));
        when(mapper.lockRecoveryEntry(anyString())).thenAnswer(i->new EarningsReleaseMapper.RecoveryEntry((String)entry.get("entryNo"),7L,"PROMOTION_REWARD","reward-7",(String)entry.get("asset"),n("20"),(String)entry.get("bucket"),"ACTIVE","PRODUCTION",0,(BigDecimal)entry.get("recoveredAmount"),(BigDecimal)entry.get("debitBaseline"),70L));
        when(mapper.otherSourceRecoveriesSinceCredit(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(),any())).thenReturn(List.of());
        when(mapper.debitRecoveredReward(eq(7L),anyString(),any(),eq(0))).thenAnswer(i->{credit("USDT".equals(i.getArgument(1))?"usdtAvailable":"nexAvailable",i.<BigDecimal>getArgument(2).negate());return 1;});
        when(mapper.addRecoveredAmount(anyString(),any(),any())).thenAnswer(i->{entry.put("recoveredAmount",((BigDecimal)entry.get("recoveredAmount")).add(i.getArgument(2)));return 1;});
        when(mapper.insertRecovery(any())).thenAnswer(i->{var w=i.<EarningsReleaseMapper.RecoveryWrite>getArgument(0);var r=w.receipt();var row=new LinkedHashMap<String,Object>();
            row.put("id",(long)recoveries.size()+1);row.put("recoveryNo",r.recoveryNo());row.put("entryNo",r.entryNo());row.put("userId",r.userId());row.put("asset",r.asset());
            row.put("sourceType",w.sourceType());row.put("sourceRef",w.sourceRef());row.put("sourceEnvironment",w.sourceEnvironment());row.put("requested",r.requested());
            row.put("recovered",r.recovered());row.put("outstanding",r.outstanding());row.put("cumulativeRecovered",r.cumulativeRecovered());row.put("remainingEntitlement",r.remainingEntitlement());
            row.put("bucket",r.bucket());row.put("balanceBefore",w.balanceBefore());row.put("balanceAfter",r.balanceAfter());row.put("debitCounterBefore",w.debitCounterBefore());
            row.put("debitBaseline",w.sourceDebitBaseline());row.put("evidenceStatus",r.evidenceStatus());row.put("reason",w.reason());row.put("actor",w.actor());row.put("createdAt",at);recoveries.add(row);return 1;});
        when(mapper.fundRecovery(anyString())).thenAnswer(i->new LinkedHashMap<>(recoveries.get(recoveries.size()-1)));
    }
    EarningsReleaseService.RewardRecoveryReceipt recover(String no,String asset,String amount) {
        return service.recoverReward(new EarningsReleaseService.RewardRecoveryRequest(7L,no,"PROMOTION_REWARD","reward-7",asset,"PRODUCTION",n(amount),"original reward correction","treasury"),"recover-idem");
    }

    @Test void independentActualCreditBindsOneRawAndAuthenticSource()throws Exception {
        String no=service.creditReward(7L,"PROMOTION_REWARD","reward-7","USDT",n("20"),"idem-7");
        assertEquals(1,receipts.size());assertEquals(1,raws.size());assertEquals(n("20"),wallet.get("usdtAvailable"));
        assertEquals(no+":CREDIT",verifyReceipt(0).coveredPhase());assertTrue(verifyReceipt(0).checkpoints().isEmpty());
        evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(no+":CREDIT"));
        var order=inOrder(mapper,journal);order.verify(mapper).lockCreditUser(7L,0);order.verify(mapper).lockCreditWallet(7L,0);order.verify(journal).begin(List.of(7L));order.verify(mapper).insert(any());
    }
    @Test void outerCompoundCreditDoesNotDoubleBindButRetainsActualEntryAndMoney() {
        service.creditRewardInFundOperation(7L,"H2_TRIAL_BONUS","trial:NEX","NEX",n("20"),"trial-idem");
        assertEquals(n("20"),wallet.get("nexAvailable"));assertEquals("H2_TRIAL_BONUS",entry.get("sourceType"));assertEquals(1,raws.size());verifyNoInteractions(journal);
    }
    @Test void sameSourceColumnFlagTypeMustRemainCanonicalRatherThanUsingWalletRawFlagEquivalence()throws Exception {
        protectedCredit("USDT");var r=receipts.get(0);var source=(ObjectNode)json.readTree(r.sourceSnapshotJson());
        ((ObjectNode)source.path("entry")).put("isDeleted",0);
        assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(0),source,json.readTree(r.sourceProofJson()),events(0),inputs()));
        assertDoesNotThrow(()->verifyReceipt(0));
    }
    @Test void actualManualReleaseNativeBooleanWalletAtNumericRawCheckpointIsValidWithoutNewRevision()throws Exception {
        String no=protectedCredit("NEX");service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        assertEquals(2,receipts.size());assertEquals(1,raws.size());assertEquals(1L,wallet.get("revision"));
        var verified=verifyReceipt(1);assertEquals(no+":RELEASE",verified.coveredPhase());assertEquals(1,verified.checkpoints().size());assertEquals(1,verified.checkpoints().get(0).previousOperationId());
        assertEquals(verifyReceipt(0).stamp().succeededAt(),verified.stamp().succeededAt());
        evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(no+":CREDIT",no+":RELEASE"));
        assertThrows(IllegalArgumentException.class,()->evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(no+":CREDIT")));
    }
    @Test void nativeFlagsRejectNonbinaryFractionsReversalAndBooleanIdentity()throws Exception {
        String no=protectedCredit("USDT");service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        var r=receipts.get(1);var source=json.readTree(r.sourceSnapshotJson());
        for(Object bad:List.of(2,new BigDecimal("0.5"),true,"0")) {
            var proof=(ObjectNode)json.readTree(r.sourceProofJson());for(String path:List.of("cashBefore","cashAfter"))((ObjectNode)proof.path(path)).set("sandbox",json.valueToTree(bad));
            ((ObjectNode)proof.path("checkpoint").path("wallet")).set("sandbox",json.valueToTree(bad));
            assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),source,proof,events(1),inputs()));
        }
        for(String field:List.of("id","userId","version","revision","usdtAvailable")) {
            var proof=(ObjectNode)json.readTree(r.sourceProofJson());for(String path:List.of("cashBefore","cashAfter"))((ObjectNode)proof.path(path)).put(field,false);
            ((ObjectNode)proof.path("checkpoint").path("wallet")).put(field,false);
            assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),source,proof,events(1),inputs()));
        }
    }
    @Test void missingAuditAlteredOriginalSourceAndCheckpointPredecessorFailClosed()throws Exception {
        String no=protectedCredit("USDT");service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        var r=receipts.get(1);var source=(ObjectNode)json.readTree(r.sourceSnapshotJson());var proof=(ObjectNode)json.readTree(r.sourceProofJson());
        ((ObjectNode)source.path("beforeEntry")).put("amount",21);
        var changedSource=source;assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),changedSource,proof,events(1),inputs()));
        source=(ObjectNode)json.readTree(r.sourceSnapshotJson());var validSource=source;
        ((ObjectNode)proof.path("checkpoint")).put("previousOperationId",0);
        assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),validSource,proof,events(1),inputs()));
        audits.clear();assertThrows(IllegalArgumentException.class,()->verifyReceipt(1));
    }
    @Test void actualPartialRecoveryPreservesBucketAndOnlyReferencesTheOriginalEarning()throws Exception {
        String no=protectedCredit("NEX");recoveryStorage(n("15"));var result=recover(no,"NEX","8");
        assertEquals(n("5"),result.recovered());assertEquals(n("3"),result.outstanding());assertEquals("bonus_locked",entry.get("bucket"));
        assertEquals(n("15"),wallet.get("nexAvailable"));assertEquals(n("0"),wallet.get("usdtAvailable"));assertEquals(2,raws.size());
        assertEquals("EARNINGS:"+no,events(1).get(0).reference());assertEquals(result.recoveryNo()+":RECOVER",verifyReceipt(1).coveredPhase());
        evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(no+":CREDIT",result.recoveryNo()+":RECOVER"));
        recoveries.get(0).put("debitCounterBefore",n("20"));
        var r=receipts.get(1);var source=(ObjectNode)json.readTree(r.sourceSnapshotJson());((ObjectNode)source.path("recovery")).put("debitCounterBefore",n("20"));
        assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),source,json.readTree(r.sourceProofJson()),events(1),inputs()));
    }
    @Test void actualZeroRecoveryCreatesOnlySourceReceiptAndCheckpoint()throws Exception {
        String no=protectedCredit("USDT");recoveryStorage(n("20"));var result=recover(no,"USDT","8");
        assertEquals(n("0"),result.recovered());assertEquals(1,raws.size());assertEquals(n("20"),wallet.get("usdtAvailable"));assertTrue(events(1).isEmpty());
        assertEquals(1,verifyReceipt(1).checkpoints().size());verify(mapper,never()).debitRecoveredReward(anyLong(),anyString(),any(),anyInt());
        assertThrows(IllegalArgumentException.class,()->evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(no+":CREDIT")));
    }
    @Test void otherRecoveryExclusionRequiresEveryOriginalSqlSourcePredicate()throws Exception {
        String no=protectedCredit("NEX");recoveryStorage(n("15"));recover(no,"NEX","8");
        recoveries.get(0).put("debitCounterBefore",n("25"));
        var r=receipts.get(1);var source=(ObjectNode)json.readTree(r.sourceSnapshotJson());((ObjectNode)source.path("recovery")).put("debitCounterBefore",n("25"));
        var otherEntry=new LinkedHashMap<>(entry);otherEntry.put("entryNo","ER-OTHER");otherEntry.put("recoveredAmount",n("10"));
        var otherRecovery=new LinkedHashMap<>(recoveries.get(0));otherRecovery.put("entryNo","ER-OTHER");otherRecovery.put("recoveryNo","ERC-OTHER");
        otherRecovery.put("recovered",n("10"));otherRecovery.put("debitCounterBefore",n("15"));
        var in=inputs();var batch=new SupportFundEarningsEvidence.Inputs(List.of(entry,otherEntry),List.of(recoveries.get(0),otherRecovery),in.appliedProofs(),in.audits(),in.raw(),in.precisions());
        assertDoesNotThrow(()->evidence.verify(operation(1),source,json.readTree(r.sourceProofJson()),events(1),batch));
        for(String field:List.of("sourceWalletId","userId","asset","sourceEnvironment")) {
            Object original=otherEntry.get(field);otherEntry.put(field,field.equals("asset")?"USDT":field.equals("sourceEnvironment")?"SANDBOX":99L);
            assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),source,json.readTree(r.sourceProofJson()),events(1),batch));otherEntry.put(field,original);
        }
        otherRecovery.put("debitCounterBefore",n("26"));
        assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),source,json.readTree(r.sourceProofJson()),events(1),batch));
    }
    @Test void legacyRecoveryStillRunsCashBusinessButCannotClaimSupportedSource()throws Exception {
        String no=protectedCredit("USDT");entry.put("debitBaseline",null);recoveryStorage(n("0"));var result=recover(no,"USDT","8");
        assertEquals(n("0"),result.recovered());assertEquals("LEGACY_UNPROVEN",result.evidenceStatus());assertEquals(2,receipts.size());assertEquals(1,raws.size());
        assertThrows(IllegalArgumentException.class,()->verifyReceipt(1));
    }
    @Test void historicalJanusReceiptStillRequiresConsumedProductionProof()throws Exception {
        String no=protectedCredit("USDT");
        service.manualReleaseOnce(no,new EarningsManualReleaseRequest("approved investigation","superadmin"));
        var receipt=receipts.get(1);
        var source=(ObjectNode)json.readTree(receipt.sourceSnapshotJson());
        // Reconstruct a retained pre-retirement receipt; no current Janus producer is invoked.
        entry.put("releaseSource","attest");
        applied.add(new LinkedHashMap<>(Map.of("proofId","proof-7","userId",7L,"deviceId","phone-7","commandVersion",1L,"proofHash","e".repeat(64),"executorMode","PRODUCTION","earningsConsumedAt",at)));
        var historicalAudit=audits.get(audits.size()-1);
        historicalAudit.put("action","K1_EARNINGS_ATTESTATION_RELEASED");
        historicalAudit.put("detailJson",json.writeValueAsString(Map.of("userId",7L,"deviceId","phone-7","before","bonus_locked","after","withdrawable","source","JANUS_PRODUCTION_EXECUTOR")));
        source.put("authority","JANUS");
        ((ObjectNode)source.path("entry")).put("releaseSource","attest");
        ((ObjectNode)source.path("audit")).put("action","K1_EARNINGS_ATTESTATION_RELEASED");
        ((ObjectNode)source.path("audit")).put("detailJson",historicalAudit.get("detailJson").toString());
        var historicalProof=new LinkedHashMap<>(applied.get(0));
        historicalProof.put("earningsConsumedAt",at.toString());
        source.set("appliedProof",json.valueToTree(historicalProof));
        var verified=evidence.verify(operation(1),source,json.readTree(receipt.sourceProofJson()),events(1),inputs());
        assertEquals(1,verified.checkpoints().size());assertEquals(1,raws.size());
        applied.get(0).put("earningsConsumedAt",null);
        assertThrows(IllegalArgumentException.class,()->evidence.verify(operation(1),source,json.readTree(receipt.sourceProofJson()),events(1),inputs()));
    }
}
