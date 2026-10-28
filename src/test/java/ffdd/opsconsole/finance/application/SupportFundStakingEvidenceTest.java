package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.market.application.*;
import ffdd.opsconsole.market.facade.SupportStakingReadFacade;
import ffdd.opsconsole.market.mapper.SupportStakingSourceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual market adapter, actual Binder and actual central earnings service; SQL storage is doubled. */
class SupportFundStakingEvidenceTest {
    final ObjectMapper json=new ObjectMapper();final DataSource ds=mock(DataSource.class);
    final SupportPaymentFundFixture store=new SupportPaymentFundFixture(ds,json);
    final SupportStakingSourceMapper mapper=mock(SupportStakingSourceMapper.class);
    final SupportStakingReadService read=new SupportStakingReadService(mapper);
    final SupportStakingFundAdapter adapter=new SupportStakingFundAdapter(store.fund,mapper,read,json);
    final SupportFundJournalBindingService codec=(SupportFundJournalBindingService)store.fund;
    final SupportFundEarningsEvidence earnings=new SupportFundEarningsEvidence(codec,json);
    final SupportFundStakingEvidence evidence=new SupportFundStakingEvidence(codec,json,earnings);
    final EarningsCentralFundEvidenceTest central=new EarningsCentralFundEvidenceTest();
    final LocalDateTime at=LocalDateTime.of(2026,10,11,12,0),birth=at.minusDays(30);
    final Map<String,Object> position=new LinkedHashMap<>();final List<Map<String,Object>> ledgers=new ArrayList<>();
    static final String NO="STK-00000040";String phase;boolean g7;
    static BigDecimal n(String v){return new BigDecimal(v).setScale(6);}
    static Map<String,Object> row(Object... v){var r=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)r.put((String)v[i],v[i+1]);return r;}
    @BeforeEach void storage() {
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.bindResource(ds,store.holder);
        store.wallet(n("100"));store.wallet.put("sandbox",false);store.wallet.put("isDeleted",false);store.wallet.put("nexAvailable",n("19"));
        when(mapper.wallets(7)).thenAnswer(i->List.of(marketWallet()));
        when(mapper.byPositionNos(anyCollection())).thenAnswer(i->position.isEmpty()?List.of():List.of(new LinkedHashMap<>(position)));
        when(mapper.byCustomers(anyCollection())).thenAnswer(i->position.isEmpty()?List.of():List.of(new LinkedHashMap<>(position)));
        when(mapper.ledgers(anyCollection())).thenAnswer(i->ledgers.stream().map(LinkedHashMap::new).map(r->(Map<String,Object>)r).toList());
        central.storage();
        when(central.raw.lockWallet(7)).thenAnswer(i->List.of(new LinkedHashMap<>(store.wallet)));
        when(central.mapper.creditUsdt(eq(7L),any(),eq("PRODUCTION"),eq(0))).thenAnswer(i->{advance(i.getArgument(1));return 1;});
        when(central.raw.journal(anyString(),anyLong(),anyLong())).thenAnswer(i->store.journal.stream().filter(r->((Number)r.get("revision")).longValue()>i.<Long>getArgument(1)&&((Number)r.get("revision")).longValue()<=i.<Long>getArgument(2)).toList());
    }
    @AfterEach void clear(){if(TransactionSynchronizationManager.hasResource(ds))TransactionSynchronizationManager.unbindResource(ds);TransactionSynchronizationManager.clear();}
    Map<String,Object> marketWallet(){var w=store.wallet;return row("id",w.get("id"),"customerId",w.get("userId"),"incarnation",w.get("incarnation"),"revision",w.get("revision"),"version",w.get("version"),"usdtAvailable",w.get("usdtAvailable"),"nexAvailable",w.get("nexAvailable"),"riskHeld",w.get("cregisRiskHeld"),"pendingWithdrawal",w.get("pendingWithdraw"),"sandbox",w.get("sandbox"),"deleted",w.get("isDeleted"));}
    void advance(BigDecimal amount){store.advance(amount,n("0"));var raw=store.journal.get(store.journal.size()-1);for(String key:List.of("oldSandbox","newSandbox","oldIsDeleted","newIsDeleted"))raw.put(key,0);}
    void ledger(String suffix,BigDecimal amount,BigDecimal balance,LocalDateTime time){String type=suffix.equals("-OPEN")?(g7?"WALLET_REINVEST":"STAKING_OPEN"):suffix.equals("-CLAIM")?(g7?"REPURCHASE_CLAIM":"STAKING_CLAIM"):(g7?"REPURCHASE_EARLY_WITHDRAW":"STAKING_EARLY_WITHDRAW");ledgers.add(row("id",(long)ledgers.size()+1,"customerId",7L,"positionNo",NO,"bizNo",NO+suffix,"bizType",type,"asset","USDT","direction",suffix.equals("-OPEN")?"OUT":"IN","amount",amount,"balanceAfter",balance,"status","SUCCESS","createdAt",time,"updatedAt",time,"deleted",false));}
    void fixture(boolean g7,String phase){fixture(g7,phase,"500","2");}
    void fixture(boolean g7,String phase,String penalty,String interest){
        this.g7=g7;this.phase=phase;
        var token=adapter.begin(7,null);advance(n("-20"));
        position.putAll(row("id",40L,"customerId",7L,"positionNo",NO,"productId",2L,"productCode",g7?"REPURCHASE_90D":"USDT_30D","productName","Staking","amountUsdt",n("20"),"apyBps",n("1200"),"earlyPenaltyBps",n(penalty),"termDays",30,"lockedAt",birth,"unlockAt",at,"estimatedInterestUsdt",n(interest),"status","ACTIVE","claimedAt",null,"earlyWithdrawnAt",null,"createdAt",birth,"updatedAt",birth,"deleted",false,"userSandbox",false,"userDeleted",false));
        ledger("-OPEN",n("20"),n("80"),birth);adapter.recorded(token,NO,"OPEN");
        if(phase.equals("OPEN"))return;
        token=adapter.begin(7,NO);position.put("status",phase.equals("CLAIM")?"CLAIMED":"EARLY_WITHDRAWN");position.put(phase.equals("CLAIM")?"claimedAt":"earlyWithdrawnAt",phase.equals("CLAIM")?at:at.minusDays(1));position.put("updatedAt",phase.equals("CLAIM")?at:at.minusDays(1));
        BigDecimal claim=n("20").add(n(interest)),early=n("20").subtract(SupportStakingReadFacade.penalty(position));
        BigDecimal cash=phase.equals("CLAIM")?(g7?claim:n("20")):early;advance(cash);
        ledger(phase.equals("CLAIM")?"-CLAIM":"-EARLY",phase.equals("CLAIM")?claim:early,n("80").add(phase.equals("CLAIM")?claim:early),(LocalDateTime)position.get("updatedAt"));
        adapter.recorded(token,NO,phase);
        if(phase.equals("CLAIM")&&!g7&&n(interest).signum()>0){var actual=new EarningsReleaseService(central.mapper,central.params,central.idempotency,central.audit,central.profile,new SupportFundEarningsAdapter(store.fund,central.raw,central.mapper,json));actual.creditReward(7L,"staking_interest",NO,"USDT",n(interest),"G1-STAKING-INTEREST-40");}
    }
    SupportFundStakingEvidence.Inputs inputs(){return new SupportFundStakingEvidence.Inputs(read.readByCustomers(List.of(7L)),store.journal,new ArrayList<>(store.operations.values()),store.parties.values().stream().flatMap(Collection::stream).toList(),store.legs.values().stream().flatMap(Collection::stream).toList(),Map.of("nx_staking_position.created_at",0,"nx_staking_position.claimed_at",0,"nx_staking_position.early_withdrawn_at",0),new SupportFundEarningsEvidence.Inputs(central.entry.isEmpty()?List.of():List.of(central.entry),List.of(),List.of(),List.of(),store.journal,Map.of("nx_earnings_release_entry.created_at",0)));}
    SupportFundStakingEvidence.Verified verify(String phase){var op=store.operations.get(SupportStakingReadFacade.operationKey(NO,phase));var input=inputs();long id=((Number)op.get("id")).longValue();var events=codec.decodeReceipt(op,List.of(7L),store.legs.get(id));return evidence.verify(op,codec.read((String)op.get("sourceSnapshotJson")),codec.read((String)op.get("sourceProofJson")),events,input);}
    @ParameterizedTest @ValueSource(strings={"G1-OPEN","G1-CLAIM","G1-EARLY","G7-OPEN","G7-CLAIM","G7-EARLY"})
    void actualCaptureAndBinderReadbackAcceptEveryRealPhaseWithoutDoubleBinding(String spec){var s=spec.split("-");fixture(s[0].equals("G7"),s[1]);var v=verify(phase);assertThat(v.points()).hasSize(1);assertThat(v.coveredPhase()).isEqualTo(NO+":"+phase);assertThat(store.wallet.get("nexAvailable")).isEqualTo(n("19"));assertThat(store.operations).hasSize(phase.equals("OPEN")?1:!g7&&phase.equals("CLAIM")?3:2);assertThat(store.journal).hasSize(store.operations.size());assertThat(v.representedEarnings()).hasSize(!g7&&phase.equals("CLAIM")?1:0);assertThat(v.points().get(0).after().usdt()).isEqualByComparingTo(phase.equals("OPEN")?"80":phase.equals("CLAIM")?(g7?"102":"100"):"99");}
    @ParameterizedTest @ValueSource(strings={"G1-OPEN","G1-CLAIM","G1-EARLY","G7-OPEN","G7-CLAIM","G7-EARLY"})
    void reverseEnumerationRequiresAllRealReceiptsAndExactActiveHolding(String spec){var s=spec.split("-");fixture(s[0].equals("G7"),s[1]);Set<String> covered=phase.equals("OPEN")?Set.of(NO+":OPEN"):Set.of(NO+":OPEN",NO+":"+phase);var tails=evidence.verifyCoverage(Set.of(7L),inputs(),covered);assertThat(tails).hasSize(phase.equals("OPEN")?1:0);if(!tails.isEmpty()){assertThat(tails.get(0).amount()).isEqualByComparingTo("20");assertThat(tails.get(0).account().slot()).isEqualTo("held:staking-"+NO);}assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),inputs(),Set.of())).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"ledgerOwner","ledgerAmount","ledgerBalance","ledgerType","ledgerFailed","ledgerDeleted","duplicateLedger","missingLedger","deleted","sandbox","foreignCustomer","boolId","boolMoney","unknownProduct","wrongClaimAt","bothTerminal","wrongAmount","wrongBefore","missingOpen","duplicateLeg","walletGap","walletWrongNex","walletFlag2"})
    void contradictoryPositionLedgerOrRawNeverProducesVerified(String corruption){fixture(true,"CLAIM");var l=ledgers.get(1);var raw=store.journal.get(1);
        switch(corruption){case "ledgerOwner"->l.put("customerId",8L);case "ledgerAmount"->l.put("amount",n("21"));case "ledgerBalance"->l.put("balanceAfter",n("999"));case "ledgerType"->l.put("bizType","DEPOSIT");case "ledgerFailed"->l.put("status","FAILED");case "ledgerDeleted"->l.put("deleted",true);case "duplicateLedger"->ledgers.add(new LinkedHashMap<>(l));case "missingLedger"->ledgers.remove(1);case "deleted"->position.put("deleted",true);case "sandbox"->position.put("userSandbox",true);case "foreignCustomer"->position.put("customerId",8L);case "boolId"->position.put("id",false);case "boolMoney"->position.put("amountUsdt",false);case "unknownProduct"->position.put("productCode","UNKNOWN");case "wrongClaimAt"->position.put("claimedAt",birth);case "bothTerminal"->position.put("earlyWithdrawnAt",at);case "wrongAmount"->position.put("amountUsdt",n("30"));case "wrongBefore"->raw.put("oldUsdtAvailable",n("79"));case "missingOpen"->store.operations.remove(SupportStakingReadFacade.operationKey(NO,"OPEN"));case "duplicateLeg"->{long id=((Number)store.operations.get(SupportStakingReadFacade.operationKey(NO,"CLAIM")).get("id")).longValue();store.legs.get(id).add(new LinkedHashMap<>(store.legs.get(id).get(0)));}case "walletGap"->raw.put("newVersion",99L);case "walletWrongNex"->raw.put("newNexAvailable",n("20"));case "walletFlag2"->raw.put("newSandbox",2);default->throw new AssertionError();}
        assertThatThrownBy(()->verify("CLAIM")).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"missingEntry","missingReceipt","lessInterest","wrongSource","wrongRef","wrongIdempotency","wrongOwner","wrongAsset","rawGap","wrongCash","missingCreditRaw"})
    void principalCannotClaimReadinessWithoutTheUniqueActualCentralInterest(String corruption){fixture(false,"CLAIM");var entry=central.entry;
        switch(corruption){case "missingEntry"->entry.clear();case "missingReceipt"->store.operations.remove("EARNINGS:CREDIT:"+entry.get("entryNo"));case "lessInterest"->entry.put("amount",n("1"));case "wrongSource"->entry.put("sourceType","OTHER");case "wrongRef"->entry.put("sourceRef","foreign-position");case "wrongIdempotency"->entry.put("idempotencyKey","wrong");case "wrongOwner"->entry.put("userId",8L);case "wrongAsset"->entry.put("asset","UVT");case "rawGap"->store.journal.get(2).put("revision",4L);case "wrongCash"->store.journal.get(2).put("newUsdtAvailable",n("101"));case "missingCreditRaw"->store.journal.remove(2);default->throw new AssertionError();}
        assertThatThrownBy(()->verify("CLAIM")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void maturityOnlyChangesPositionStateWithoutMakingCashOrOperation(){fixture(true,"OPEN");position.put("status","MATURE_UNCLAIMED");position.put("updatedAt",at);assertThat(verify("OPEN").points()).hasSize(1);assertThat(evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(NO+":OPEN"))).hasSize(1);assertThat(store.journal).hasSize(1);assertThat(store.operations).hasSize(1);}
    @Test void genuinelyProtectedCentralInterestRetainsItsActualBucketAndSeparateRaw(){when(central.mapper.riskCluster(7L)).thenReturn(new ffdd.opsconsole.finance.mapper.EarningsReleaseMapper.RiskCluster("cluster-7",3,"frozen"));fixture(false,"CLAIM");assertThat(central.entry.get("bucket")).isEqualTo("bonus_locked");assertThat(verify("CLAIM").representedEarnings()).containsExactly((String)central.entry.get("entryNo"));}
    @Test void legacyPositionCanActuallyExitButMissingOpenCaptureRemainsUnknown(){fixture(true,"OPEN");store.operations.clear();store.parties.clear();store.legs.clear();var token=adapter.begin(7,NO);position.put("status","EARLY_WITHDRAWN");position.put("earlyWithdrawnAt",at.minusDays(1));position.put("updatedAt",at.minusDays(1));advance(n("19"));ledger("-EARLY",n("19"),n("99"),at.minusDays(1));assertThatCode(()->adapter.recorded(token,NO,"EARLY")).doesNotThrowAnyException();assertThat(store.wallet.get("usdtAvailable")).isEqualTo(n("99"));assertThatThrownBy(()->verify("EARLY")).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"0","10000"})
    void zeroAndWholePrincipalPenaltyUseTheActualWalletRevisionWithoutZeroCheckpoint(String penalty){fixture(true,"EARLY",penalty,"2");var verified=verify("EARLY");assertThat(verified.points()).hasSize(1);assertThat(store.journal).hasSize(2);assertThat(store.operations).hasSize(2);assertThat(verified.points().get(0).after().usdt()).isEqualByComparingTo(penalty.equals("0")?"100":"80");assertThat(evidence.verifyCoverage(Set.of(7L),inputs(),Set.of(NO+":OPEN",NO+":EARLY"))).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"G1","G7"})
    void zeroActualInterestCreatesNoInventedEarningOrSecondRaw(String product){fixture(product.equals("G7"),"CLAIM","500","0");assertThat(verify("CLAIM").representedEarnings()).isEmpty();assertThat(store.operations).hasSize(2);assertThat(store.journal).hasSize(2);assertThat(central.entry).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"2","0.5","-1"})
    void rawBinaryFlagFractionsOrNonbinaryValuesStayRejected(String bad){fixture(true,"CLAIM");store.journal.get(1).put("newSandbox",new BigDecimal(bad));assertThatThrownBy(()->verify("CLAIM")).isInstanceOf(IllegalArgumentException.class);}
    @Test void sourceSnapshotFlagCannotBeChangedToEquivalentNumber(){fixture(true,"CLAIM");position.put("deleted",0);assertThatThrownBy(()->verify("CLAIM")).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"fraction","boolean","string"})
    void sourceIdentityMustBeAnExactNumericInteger(String type){fixture(true,"CLAIM");position.put("id",switch(type){case "fraction"->new BigDecimal("0.5");case "boolean"->false;default->"40";});assertThatThrownBy(()->verify("CLAIM")).isInstanceOf(IllegalArgumentException.class);}
    @Test void duplicateActualCentralSourceCannotHideBehindOneMatchingReceipt(){fixture(false,"CLAIM");var input=inputs();var duplicate=new LinkedHashMap<>(central.entry);duplicate.put("entryNo","ER-duplicate");var bad=new SupportFundStakingEvidence.Inputs(input.sources(),input.raw(),input.operations(),input.parties(),input.legs(),input.precisions(),new SupportFundEarningsEvidence.Inputs(List.of(central.entry,duplicate),List.of(),List.of(),List.of(),store.journal,Map.of("nx_earnings_release_entry.created_at",0)));assertThatThrownBy(()->evidence.verifyCoverage(Set.of(7L),bad,Set.of(NO+":OPEN",NO+":CLAIM"))).isInstanceOf(IllegalArgumentException.class);}
    @Test void wrongEventOrderCannotChangeTheActualG7CompoundCashProof(){fixture(true,"CLAIM");var op=store.operations.get(SupportStakingReadFacade.operationKey(NO,"CLAIM"));var events=new ArrayList<>(SupportStakingReadFacade.events(position,"CLAIM"));Collections.reverse(events);assertThatThrownBy(()->evidence.verify(op,codec.read((String)op.get("sourceSnapshotJson")),codec.read((String)op.get("sourceProofJson")),events,inputs())).isInstanceOf(IllegalArgumentException.class);}
}
