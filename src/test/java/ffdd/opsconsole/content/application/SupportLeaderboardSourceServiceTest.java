package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Status;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportLeaderboardSourceServiceTest {
    private static final LocalDateTime NOW=LocalDateTime.parse("2026-10-09T02:00:00");
    private static final LocalDateTime START=LocalDateTime.parse("2026-09-01T00:00:00");
    private static final YearMonth OCT=YearMonth.of(2026,10);
    private static final class Fixture {
        final SupportLeaderboardMapper mapper=mock(SupportLeaderboardMapper.class);
        final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
        final SupportLeaderboardSourceService service=new SupportLeaderboardSourceService(mapper,finance);
        Fixture() {
            when(mapper.nowUtc()).thenReturn(NOW);
            when(mapper.accounts()).thenReturn(List.of(account(7,"甲",1),account(8,"主管",1)));
            when(mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null)));
            when(mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,100L,1L,START,null)));
            when(mapper.groups()).thenReturn(List.of(group(100,"当前一组"),group(200,"当前二组")));
            when(mapper.currentBindings()).thenReturn(List.of(binding(1,1,7)));
            when(mapper.productionCustomers()).thenReturn(List.of(1L));
            when(mapper.attributions()).thenReturn(List.of());when(mapper.attributionProofs()).thenReturn(List.of());
            financial(List.of(),List.of(new SupportPaymentFacts.FirstHistory(1,Status.UNKNOWN,List.of("HISTORY_UNVERIFIED"))));
        }
        void financial(List<Fact> facts,List<SupportPaymentFacts.FirstHistory> history) {
            doReturn(snapshot(facts,history)).when(finance).readHistory(any());
        }
        void events(Fact... facts) {
            when(mapper.attributions()).thenReturn(Arrays.stream(facts).map(SupportLeaderboardSourceServiceTest::attribution).toList());
            when(mapper.attributionProofs()).thenReturn(Arrays.stream(facts).map(f -> proof(f.factId(),"evidence-v1")).toList());
        }
        SupportLeaderboardSourceService.Read read(Board board) {return read(context(board,"NEX",Scope.all,Set.of()));}
        SupportLeaderboardSourceService.Read read(Context context) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
            try {return service.readForAuthorizedLeaderboard(context);} finally {TransactionSynchronizationManager.clear();}
        }
    }
    @Test void currentCustomersRemainCompleteWithoutHistoricalQualificationOrFinancialCertificates() {
        var f=new Fixture();var r=f.read(Board.customers);
        assertEquals(Coverage.COMPLETE,r.candidateCoverage());assertEquals(1,r.candidates().size());
        var c=r.candidates().get(0);assertEquals(7,c.agentId());assertEquals(1L,c.customers().value());
        assertEquals(Coverage.COMPLETE,c.customers().coverage());assertNull(c.firstPayment().value());
        assertEquals(Coverage.PARTIAL,c.firstPayment().coverage());assertNull(c.amount().value());
        assertEquals(Coverage.UNKNOWN,c.amount().coverage());assertNull(c.qualificationBirth());
        var calculated=SupportLeaderboard.calculate(r.context(),r.sourceVersion(),r.candidateCoverage(),r.candidates());
        assertEquals(State.COMPLETE,calculated.state());assertEquals(1,calculated.rows().get(0).rank());
        verify(f.finance).readHistory(List.of(1L));
    }
    @Test void monthlyEligibilityUsesShanghaiMonthIntersectionAndNeverClaimsCaptureCompleteness() {
        var f=new Fixture();when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",0),account(8,"乙",1),account(9,"丙",1)));
        when(f.mapper.qualifications()).thenReturn(List.of(
            q(1,7,"ENABLED",START,LocalDateTime.parse("2026-09-30T16:30:00")),
            q(2,7,"DISABLED",LocalDateTime.parse("2026-09-30T16:30:00"),null),
            q(3,8,"ENABLED",START,LocalDateTime.parse("2026-09-30T16:00:00")),
            q(4,9,"ENABLED",NOW.plusHours(1),null)));
        var r=f.read(Board.firstPayment);assertEquals(List.of(7L),r.candidates().stream().map(Candidate::agentId).toList());
        assertEquals(Coverage.PARTIAL,r.candidateCoverage());assertEquals(Qualification.HANDOVER_REQUIRED,r.candidates().get(0).qualification());
        assertEquals(List.of(OCT),r.selectableMonths());
    }
    @Test void disabledAndRemovedAgentsWithBindingsRemainHandoverCandidatesAndPureSupervisorIsExcluded() {
        var f=new Fixture();when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",0),account(8,"乙",0),account(9,"纯主管",1)));
        when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,START.plusDays(1)),q(2,7,"DISABLED",START.plusDays(1),null),
            q(3,8,"ENABLED",START,START.plusDays(1)),q(4,8,"REMOVED",START.plusDays(1),null)));
        when(f.mapper.productionCustomers()).thenReturn(List.of(1L,2L));when(f.mapper.currentBindings()).thenReturn(List.of(binding(1,1,7),binding(2,2,8)));
        f.financial(List.of(),List.of());var r=f.read(Board.customers);
        assertEquals(List.of(7L,8L),r.candidates().stream().map(Candidate::agentId).toList());
        assertTrue(r.candidates().stream().allMatch(c -> c.qualification()==Qualification.HANDOVER_REQUIRED));assertEquals(Coverage.COMPLETE,r.candidateCoverage());
    }
    @Test void bindingWithoutProvenServiceHistoryMakesCandidateSetPartialInsteadOfInventingQualification() {
        var f=new Fixture();when(f.mapper.qualifications()).thenReturn(List.of());var r=f.read(Board.customers);
        assertEquals(Coverage.PARTIAL,r.candidateCoverage());assertEquals(Qualification.UNKNOWN,r.candidates().get(0).qualification());
        assertEquals(1L,r.candidates().get(0).customers().value());assertNull(r.candidates().get(0).qualificationBirth());
    }
    @Test void currentGroupFiltersDoNotRewriteHistoricalEventOwnership() {
        var f=new Fixture();when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,100L,1L,START,NOW.minusDays(1)),
            new MemberInterval(2L,7L,200L,2L,NOW.minusDays(1),null)));
        var fact=fact(1,2,Kind.DEPOSIT,"USDT",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(fact);f.financial(List.of(fact),ready(1));
        var r=f.read(context(Board.firstPayment,"NEX",Scope.ownGroup,Set.of(200L)));
        assertEquals("当前二组",r.candidates().get(0).groupName());assertEquals(1L,r.candidates().get(0).firstPayment().value());
        assertTrue(f.read(context(Board.customers,"NEX",Scope.ownGroup,Set.of(100L))).candidates().isEmpty());
    }
    @Test void ownGroupExcludesExplicitlyUngroupedMemberWhileAllKeepsCandidate() {
        var f=new Fixture();when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,null,1L,START,null)));
        assertTrue(f.read(context(Board.customers,"NEX",Scope.ownGroup,Set.of(100L))).candidates().isEmpty());
        var all=f.read(Board.customers);assertEquals(List.of(7L),all.candidates().stream().map(Candidate::agentId).toList());
        assertEquals("待分组",all.candidates().get(0).groupName());assertEquals(1L,all.candidates().get(0).customers().value());
        assertEquals(Coverage.COMPLETE,all.candidateCoverage());
    }
    @Test void managedGroupsExcludeUngroupedAndMissingMemberWhileAllKeepsCandidate() {
        var f=new Fixture();when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,null,1L,START,null)));
        assertTrue(f.read(context(Board.customers,"NEX",Scope.managedGroups,Set.of(100L,200L))).candidates().isEmpty());
        var all=f.read(Board.customers);assertEquals(List.of(7L),all.candidates().stream().map(Candidate::agentId).toList());
        assertEquals("待分组",all.candidates().get(0).groupName());assertEquals(1L,all.candidates().get(0).customers().value());
        assertEquals(Coverage.COMPLETE,all.candidateCoverage());
        when(f.mapper.memberships()).thenReturn(List.of());
        assertTrue(f.read(context(Board.customers,"NEX",Scope.managedGroups,Set.of(100L,200L))).candidates().isEmpty());
        assertEquals(List.of(7L),f.read(Board.customers).candidates().stream().map(Candidate::agentId).toList());
    }
    @Test void missingNicknameUsesNeutralStablePublicKeyAndAvatarOnlyUsesControlledReference() {
        var f=new Fixture();when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,null,1,0,1L,1,0,1L,"SUPPORT",
            "private-storage-id",9L,"ATTACHED",7L,1)));
        var c=f.read(Board.customers).candidates().get(0);assertEquals("专属客服 #7",c.name());
        assertEquals("/api/admin/content/support-workbench/leaderboard/7/avatar?assetVersion=9",c.avatarUrl());
        when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,"甲",1,0,1L,1,0,1L,"SUPPORT","private-storage-id",9L,"ATTACHED",8L,1)));
        assertNull(f.read(Board.customers).candidates().get(0).avatarUrl());
    }
    @Test void lifetimeFirstUsesSharedCanonicalOrderingAcrossCurrenciesBeforeMonthlyFilter() {
        var f=new Fixture();var old=fact(1,2,Kind.DEPOSIT,"USDT",LocalDateTime.parse("2026-09-30T23:00:00"));
        var later=fact(1,3,Kind.DEVICE_PURCHASE,"NEX",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(old,later);f.financial(List.of(later,old),ready(1));
        var r=f.read(Board.firstPayment);assertEquals(0L,r.candidates().get(0).firstPayment().value());assertEquals(Coverage.COMPLETE,r.candidates().get(0).firstPayment().coverage());
        var same=LocalDateTime.parse("2026-10-01T10:00:00");var d2=fact(1,2,Kind.DEPOSIT,"USDT",same);var d11=fact(1,11,Kind.DEPOSIT,"NEX",same);
        var purchase=fact(1,1,Kind.DEVICE_PURCHASE,"NEX",same);
        assertEquals(d11,SupportAnalyticsService.selectFirstFacts(List.of(d2,purchase,d11)).get(1L));
        assertEquals(d11,SupportAnalyticsService.selectFirstFacts(List.of(d11,d2,purchase)).get(1L));
    }
    @Test void confirmedFirstCountDoesNotInheritUnknownReferenceRefundAmount() {
        for(String currency:List.of("NEX","USDT")) {
            var f=new Fixture();var fact=fact(1,2,Kind.DEPOSIT,"USDT",LocalDateTime.parse("2026-10-01T10:00:00"));
            f.events(fact);f.financial(List.of(fact),ready(1));var r=f.read(context(Board.firstPayment,currency,Scope.all,Set.of()));
            var c=r.candidates().get(0);assertEquals(1L,c.firstPayment().value());assertEquals(Coverage.COMPLETE,c.firstPayment().coverage());
            assertNull(c.amount().value());assertEquals(Coverage.UNKNOWN,c.amount().coverage());assertEquals(currency,c.amount().currency());
            assertEquals(Reason.REFUNDS_UNKNOWN,c.amount().reason());assertEquals(Coverage.PARTIAL,r.candidateCoverage());
        }
    }
    @Test void unknownFirstAndMoneyStayNullAndDepositPurchaseAreNeverAdded() {
        var f=new Fixture();var deposit=f.read(Board.deposit);var purchase=f.read(Board.purchase);
        assertNull(deposit.candidates().get(0).firstPayment().value());assertNull(deposit.candidates().get(0).amount().value());
        assertEquals(AmountKind.DEPOSIT,deposit.candidates().get(0).amount().kind());assertEquals(Reason.REFUNDS_UNKNOWN,deposit.candidates().get(0).amount().reason());
        assertNull(purchase.candidates().get(0).amount().value());assertEquals(AmountKind.PURCHASE,purchase.candidates().get(0).amount().kind());
        assertEquals(Reason.SOURCE_INCOMPLETE,purchase.candidates().get(0).amount().reason());
    }
    @Test void missingOrRejectedCanonicalAttributionDoesNotAwardConfirmedFirst() {
        var f=new Fixture();var fact=fact(1,2,Kind.DEPOSIT,"NEX",LocalDateTime.parse("2026-10-01T10:00:00"));f.financial(List.of(fact),ready(1));
        var unknown=f.read(Board.firstPayment).candidates().get(0).firstPayment();assertNull(unknown.value());assertEquals(Reason.ATTRIBUTION_UNKNOWN,unknown.reason());f.events(fact);
        var s=snapshot(List.of(fact),ready(1));when(f.finance.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(s.facts(),
            List.of(new SupportPaymentFacts.Issue(Source.CARD_TOPUP,fact.sourceIds().get(0),"CAPTURED_SOURCE_PROOF_MISMATCH",1L)),s.coverage(),s.businessZone(),s.evaluatedAt(),s.firstHistory()));
        assertNull(f.read(Board.firstPayment).candidates().get(0).firstPayment().value());
    }
    @Test void failedFinancialReadAndForeignBoundaryRejectWith503() {
        var f=new Fixture();when(f.finance.readHistory(any())).thenThrow(new DataRetrievalFailureException("private details"));assert503(() -> f.read(Board.customers));
        var failed=new SupportPaymentFacts.FirstHistory(1,Status.UNKNOWN,List.of("SOURCE_READ_FAILED"));f.financial(List.of(),List.of(failed));assert503(() -> f.read(Board.customers));
        f.financial(List.of(),ready(99));assert503(() -> f.read(Board.customers));when(f.mapper.nowUtc()).thenReturn(null);assert503(() -> f.read(Board.customers));
    }
    @Test void duplicateBindingsAndInvalidIntervalsCannotBecomeCompleteCounts() {
        var f=new Fixture();when(f.mapper.currentBindings()).thenReturn(List.of(binding(1,1,7),binding(2,1,8)));assert503(() -> f.read(Board.customers));
        when(f.mapper.currentBindings()).thenReturn(List.of(binding(1,1,7)));when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null),q(2,7,"DISABLED",NOW,null)));
        assert503(() -> f.read(Board.customers));when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,START.minusSeconds(1))));assert503(() -> f.read(Board.firstPayment));
    }
    @Test void missingCurrentServiceProfileCannotSilentlyOmitCandidateAndEmptyIntervalsProveNoEligibility() {
        var f=new Fixture();when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,"甲",1,0,1L,null,null,null,null,null,null,null,null,1)));
        assert503(() -> f.read(Board.customers));when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",1)));
        when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,START)));when(f.mapper.currentBindings()).thenReturn(List.of());
        assertTrue(f.read(Board.firstPayment).candidates().isEmpty());assertTrue(f.read(Board.customers).candidates().isEmpty());
    }
    @Test void fingerprintTracksQualificationVersionAndFullCanonicalSourceVersionWithoutMetricChange() {
        var f=new Fixture();var first=f.read(Board.customers);when(f.mapper.qualifications()).thenReturn(List.of(new QualificationInterval(1L,7L,"ENABLED",2L,START,null)));
        assertNotEquals(first.sourceVersion(),f.read(Board.customers).sourceVersion());
        var fact=fact(1,2,Kind.DEPOSIT,"NEX",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(fact);f.financial(List.of(fact),ready(1));
        var before=f.read(Board.firstPayment);var changed=new Fact(fact.factId(),fact.kind(),fact.source(),fact.sourceIds(),fact.customerId(),fact.ledgerId(),fact.sourceBusinessId(),
            fact.orderNo(),fact.orderType(),fact.originalFactId(),fact.currency(),fact.amount(),fact.succeededAt(),fact.successTimeField(),fact.fractionalSecondDigits(),
            fact.providerPaidAt(),fact.ledgerRecordedAt(),fact.sourceConfirmationAt(),"source-v2",fact.historicalEnvironmentStatus());
        f.financial(List.of(changed),ready(1));var after=f.read(Board.firstPayment);assertEquals(before.candidates().get(0).firstPayment(),after.candidates().get(0).firstPayment());
        assertNotEquals(before.sourceVersion(),after.sourceVersion());
    }
    @Test void sourceVersionIgnoresReaderClocksAndReadOrderingButTracksActualProofAndCoverage() {
        var f=new Fixture();var a=f.read(Board.customers);when(f.mapper.nowUtc()).thenReturn(NOW.plusSeconds(1));
        when(f.mapper.accounts()).thenReturn(List.of(account(8,"主管",1),account(7,"甲",1)));
        var s=snapshot(List.of(),List.of(new SupportPaymentFacts.FirstHistory(1,Status.UNKNOWN,List.of("HISTORY_UNVERIFIED"))));
        when(f.finance.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(s.facts(),s.issues(),s.coverage(),s.businessZone(),s.evaluatedAt().plusSeconds(1),s.firstHistory()));
        var b=f.read(Board.customers);assertEquals(a.sourceVersion(),b.sourceVersion());assertNotEquals(a.context().evaluatedAt(),b.context().evaluatedAt());
        var fact=fact(1,2,Kind.DEPOSIT,"NEX",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(fact);f.financial(List.of(fact),ready(1));
        var withProof=f.read(Board.customers);when(f.mapper.attributionProofs()).thenReturn(List.of(proof(fact.factId(),"evidence-v2")));
        assertNotEquals(withProof.sourceVersion(),f.read(Board.customers).sourceVersion());
        var financial=snapshot(List.of(fact),ready(1));var changed=new ArrayList<>(financial.coverage());var c=changed.get(0);
        changed.set(0,new SupportPaymentFacts.Coverage(c.source(),c.observedStatus(),c.historyStatus(),c.refundStatus(),c.historicalEnvironmentStatus(),c.supportedFrom(),List.of("REFUND_CERTIFICATE_UNAVAILABLE"),c.excludedFreeOrNonProductionRows(),c.adapterVersion()));
        var before=f.read(Board.customers);when(f.finance.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(financial.facts(),financial.issues(),changed,financial.businessZone(),financial.evaluatedAt(),financial.firstHistory()));
        assertNotEquals(before.sourceVersion(),f.read(Board.customers).sourceVersion());
    }
    @Test void evidenceRemainsInternalAndPublicCoreRowsHaveNoCustomerOrPaymentFields() {
        var f=new Fixture();var r=f.read(Board.customers);assertEquals(List.of(1L),r.evidence().productionCustomers());
        assertEquals(Set.of("agentId","name","avatarUrl","groupName","qualification","rank","isTied","firstPayment","customers","amount","rankMetricCoverage","movement"),
            Arrays.stream(Row.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
        assertEquals(r.sourceVersion(),r.candidates().get(0).amount().sourceVersion());
    }
    @Test void explicitCallerRepeatableReadIsMandatoryAndMissingScopeGroupConflicts() throws Exception {
        var f=new Fixture();TransactionSynchronizationManager.clear();assertThrows(IllegalStateException.class,() -> f.service.readForAuthorizedLeaderboard(context(Board.customers,"NEX",Scope.all,Set.of())));
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        try {assertThrows(IllegalStateException.class,() -> f.service.readForAuthorizedLeaderboard(context(Board.customers,"NEX",Scope.all,Set.of())));} finally {TransactionSynchronizationManager.clear();}
        Transactional annotation=SupportLeaderboardSourceService.class.getMethod("readForAuthorizedLeaderboard",Context.class).getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY,annotation.propagation());assertTrue(annotation.readOnly());
        assertEquals(409,assertThrows(BizException.class,() -> f.read(context(Board.customers,"NEX",Scope.ownGroup,Set.of(999L)))).getCode());
    }
    private static void assert503(org.junit.jupiter.api.function.Executable action) {assertEquals(503,assertThrows(BizException.class,action).getCode());}
    private static Account account(long id,String nickname,int enabled) {return new Account(id,nickname,enabled,0,1L,enabled,0,1L,"SUPPORT",null,null,null,null,1);}
    private static QualificationInterval q(long id,long agent,String state,LocalDateTime start,LocalDateTime end) {return new QualificationInterval(id,agent,state,1L,start,end);}
    private static Binding binding(long id,long customer,long agent) {return new Binding(id,customer,agent,1L,START,null);}
    private static Group group(long id,String name) {return new Group(id,name,"ENABLED",8L,1L,START,START);}
    private static Context context(Board board,String currency,Scope scope,Set<Long> groups) {return new Context(board,board==Board.customers?null:OCT,OCT,currency,scope,groups,"definition-v1",NOW.toInstant(ZoneOffset.UTC));}
    private static List<SupportPaymentFacts.FirstHistory> ready(long id) {return List.of(new SupportPaymentFacts.FirstHistory(id,Status.READY,List.of()));}
    private static SupportPaymentFacts.Snapshot snapshot(List<Fact> facts,List<SupportPaymentFacts.FirstHistory> histories) {
        var coverage=Arrays.stream(Source.values()).filter(s -> s!=Source.FREE_TRIAL && s!=Source.UNMATCHED_LEDGER).map(s ->
            new SupportPaymentFacts.Coverage(s,Status.READY,Status.UNKNOWN,Status.UNKNOWN,Status.UNKNOWN,null,List.of("HISTORY_UNVERIFIED","FINAL_DEPOSIT_REFUND_SOURCE_UNAVAILABLE"),0,"adapter-v1")).toList();
        return new SupportPaymentFacts.Snapshot(facts,List.of(),coverage,"Asia/Shanghai",NOW.toInstant(ZoneOffset.UTC),histories);
    }
    private static Fact fact(long customer,long ledger,Kind kind,String currency,LocalDateTime at) {
        String order=kind==Kind.DEVICE_PURCHASE?"private-order-"+ledger:null;
        return new Fact(kind==Kind.DEPOSIT?"DEPOSIT:"+ledger:"PURCHASE:"+order,kind,kind==Kind.DEPOSIT?Source.CARD_TOPUP:Source.WALLET_ORDER,
            List.of("private-source-"+ledger),customer,ledger,"private-business-"+ledger,order,order==null?null:"NEW",null,currency,new BigDecimal("1.25"),at,
            kind==Kind.DEPOSIT?"nx_wallet_ledger.created_at":"nx_order.paid_at",0,null,at,null,"source-v1",Status.UNKNOWN);
    }
    private static AttributionRow attribution(Fact f) {
        return new AttributionRow(f.factId(),f.customerId(),f.kind().name(),f.source().name(),f.ledgerId(),f.sourceBusinessId(),f.orderNo(),f.orderType(),f.originalFactId(),
            f.currency(),f.amount(),f.succeededAt(),"Asia/Shanghai",f.successTimeField(),f.fractionalSecondDigits(),"NEW_SUCCESS","support-payment-attribution-v1",7L,100L,8L,"KNOWN","KNOWN","KNOWN");
    }
    private static AttributionProof proof(String id,String evidence) {return new AttributionProof(id,"partition-v1",NOW,"{\"canonical\":true}",evidence);}
}
