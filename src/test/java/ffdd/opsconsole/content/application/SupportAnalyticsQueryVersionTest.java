package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.ConnectionStatus;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.DeviceEvidence;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.RuntimeEvidence;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static ffdd.opsconsole.content.application.SupportAnalyticsQueryVersion.*;

class SupportAnalyticsQueryVersionTest {
    private static final LocalDateTime T = LocalDateTime.of(2026,10,1,8,0);
    private static final Reads COMPLETE = new Reads(ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,
        ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.NOT_REQUESTED);
    private SupportAnalyticsQueryRequest.Normalized query(Map<String,String> raw) {
        Map<String,List<String>> m = new HashMap<>(); raw.forEach((k,v) -> m.put(k,List.of(v)));
        return SupportAnalyticsQueryRequest.fromParameters(m).normalize(Set.of("USDT"),EnumSet.allOf(SupportAnalyticsQueryRequest.Sort.class));
    }
    private String token(Builder b) { var v = evaluate(query(Map.of()), b.build()); assertThat(v.status()).isEqualTo(SupportAnalyticsQueryVersion.Status.READY); return v.value(); }

    @Test void evaluatedClockDoesNotChangeTokenButSavedWatermarkDoes() {
        Builder a = new Builder(), b = new Builder();
        b.finance = new Snapshot(b.finance.facts(),b.finance.issues(),b.finance.coverage(),"Asia/Shanghai",Instant.parse("2026-10-09T12:30:00Z"),b.finance.firstHistory());
        assertThat(token(a)).isEqualTo(token(b));
        b.coverage = new ActivityCoverage(T.minusDays(30),T.plusSeconds(1),T.minusDays(7),T.plusSeconds(1));
        assertThat(token(a)).isNotEqualTo(token(b));
    }
    @Test void pageNumberAndExpectedTokenAreExcludedButPageSizeIsIncluded() {
        Builder b = new Builder(); String v = token(b);
        var page2 = query(Map.of("pageNum","2","expectedVersion",v));
        assertThat(evaluate(page2,b.build()).value()).isEqualTo(v);
        assertThat(evaluate(query(Map.of("pageSize","10")),b.build()).value()).isNotEqualTo(v);
        requireExpected(page2,evaluate(page2,b.build()));
    }
    @Test void candidateOutsideCurrentFilterStillInvalidatesTheFullVersion() {
        Builder a = new Builder(), b = new Builder();
        b.candidateIds = List.of(100L,200L);
        b.customers = List.of(customer("Customer","ACTIVE"),new Customer(200,"BOUND","UNGROUPED",false,1L,null,"Other","DORMANT",T,T,null,null,null,null,null));
        b.authority = authority(List.of(b.authority.relationships().get(0),new Relationship(RelationKind.ASSIGNMENT,12,200L,1L,null,null,"ACTIVE",false,1L,T,null)),b.authority.roles(),b.authority.qualifications());
        b.first = List.of(b.first.get(0),new First(200,null,"NONE","AVAILABLE",Proof.MISSING,List.of()));
        b.invitations = List.of(b.invitations.get(0),new Invitation(200,0,List.of(),List.of(),Completeness.COMPLETE,Set.of()));
        b.activities = List.of(b.activities.get(0),new Activity(200,null,null,null,null,"INACTIVE","AVAILABLE"));
        b.finance = snapshot(b.finance.facts(),b.finance.coverage(),List.of(b.finance.firstHistory().get(0),new FirstHistory(200,SupportPaymentFacts.Status.READY,List.of())));
        var filtered = query(Map.of("keyword","Customer"));
        assertThat(evaluate(filtered,a.build()).value()).isNotEqualTo(evaluate(filtered,b.build()).value());
        assertThat(evaluate(filtered,b.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.READY);
    }
    @Test void canonicalSourceChangesInvalidateEvenWhenCountAmountCustomerAndFirstIdAreUnchanged() {
        Builder a = new Builder(), b = new Builder();
        b.finance = snapshot(List.of(fact(Source.CARD_TOPUP,new BigDecimal("10.00"))), b.finance.coverage(), b.finance.firstHistory());
        assertThat(token(a)).isNotEqualTo(token(b));
    }
    @Test void historicalEventCandidateDoesNotRequireCurrentPrivateActivityOrDevices() {
        Builder b = new Builder(); b.customers = List.of(); b.invitations = List.of(); b.activities = List.of();
        b.events = List.of(new EventCandidate("DEPOSIT:7",100));
        b.attributions = List.of(new Attribution("DEPOSIT:7",100,"DEPOSIT","DEPOSIT_ORDER",7L,"source7",null,null,null,"USDT",
            BigDecimal.TEN,T,"Asia/Shanghai","paid_at",6,"TRANSACTIONAL","v1",1L,null,null,"KNOWN","UNASSIGNED","UNASSIGNED",null,Proof.ACCEPTED));
        var q = query(Map.of("basis","PERIOD_EVENT","from","2026-10-01T00:00:00Z","to","2026-10-02T00:00:00Z"));
        assertThat(evaluate(q,b.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.READY);
        b.attributions = List.of(); assertThat(evaluate(q,b.build()).value()).isNull();
    }
    @Test void firstHistoryAndSelectedFirstIdentityCannotBeReplacedByEqualSums() {
        Builder a = new Builder(), b = new Builder();
        Fact later = new Fact("DEPOSIT:8",Kind.DEPOSIT,Source.DEPOSIT_ORDER,List.of("source8"),100,8,"source8",null,null,null,"USDT",
            BigDecimal.TEN,T.minusSeconds(1),"paid_at",6,null,T,T,"v1",SupportPaymentFacts.Status.UNKNOWN);
        b.finance = snapshot(List.of(later),b.finance.coverage(),b.finance.firstHistory());
        b.first = List.of(new First(100,"DEPOSIT:8","CONFIRMED","AVAILABLE",Proof.ACCEPTED,List.of()));
        assertThat(token(a)).isNotEqualTo(token(b));
        b = new Builder();
        b.finance = snapshot(b.finance.facts(),b.finance.coverage(),List.of(new FirstHistory(100,SupportPaymentFacts.Status.UNKNOWN,List.of("HISTORY_UNPROVEN"))));
        assertThat(token(a)).isNotEqualTo(token(b));
    }
    @Test void bindingQualificationAndRoleGrantIdentitiesMatterEvenWithSameMembership() {
        Builder a = new Builder(), b = new Builder();
        Authority old = b.authority;
        b.authority = authority(List.of(new Relationship(RelationKind.ASSIGNMENT,11,100L,1L,null,null,"ACTIVE",false,1L,T,null)),old.roles(),old.qualifications());
        assertThat(token(a)).isNotEqualTo(token(b));
        b = new Builder(); old = b.authority;
        b.authority = authority(old.relationships(),List.of(new Role(9,2,1,"service",1)),old.qualifications());
        assertThat(token(a)).isNotEqualTo(token(b));
        b = new Builder(); old = b.authority;
        b.authority = authority(old.relationships(),old.roles(),List.of(new Qualification(9,1,"SERVICE","ENABLED",1L,T,null)));
        assertThat(token(a)).isNotEqualTo(token(b));
    }
    @Test void orderIdenticalDuplicatesAndDecimalScaleHaveNoEffectButConflictingIdentityFails() {
        Builder a = new Builder(), b = new Builder();
        Fact equivalent = fact(Source.DEPOSIT_ORDER,new BigDecimal("10.000000"));
        List<Coverage> reversed = new ArrayList<>(b.finance.coverage()); Collections.reverse(reversed);
        b.finance = snapshot(List.of(equivalent,equivalent),reversed,b.finance.firstHistory());
        assertThat(token(a)).isEqualTo(token(b));
        b.finance = snapshot(List.of(fact(Source.DEPOSIT_ORDER,BigDecimal.TEN),fact(Source.DEPOSIT_ORDER,new BigDecimal("11"))),b.finance.coverage(),b.finance.firstHistory());
        Builder conflict = b;
        assertThatThrownBy(() -> evaluate(query(Map.of()),conflict.build())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void heartbeatAndDerivedPerDeviceStateMatterEvenWithoutChangedAggregateCount() {
        Builder a = new Builder(), b = new Builder();
        a.devices = List.of(device(T,ConnectionStatus.ONLINE,BigDecimal.ONE));
        b.devices = List.of(device(T.plusSeconds(1),ConnectionStatus.ONLINE,new BigDecimal("1.0")));
        assertThat(token(a)).isNotEqualTo(token(b));
        b.devices = List.of(device(T,ConnectionStatus.OFFLINE,BigDecimal.ONE));
        assertThat(token(a)).isNotEqualTo(token(b));
    }
    @Test void nullIsDistinctFromZeroAndLengthPrefixesPreventConcatenationCollisions() {
        Builder a = new Builder(), b = new Builder();
        a.devices = List.of(device(T,ConnectionStatus.UNKNOWN,null)); b.devices = List.of(device(T,ConnectionStatus.UNKNOWN,BigDecimal.ZERO));
        assertThat(token(a)).isNotEqualTo(token(b));
        a = new Builder(); b = new Builder();
        a.customers = List.of(customer("ab","c")); b.customers = List.of(customer("a","bc"));
        assertThat(token(a)).isNotEqualTo(token(b));
        Builder malformed = new Builder(); malformed.customers = List.of(customer(String.valueOf((char)0xD800),"ACTIVE"));
        assertThatThrownBy(() -> evaluate(query(Map.of()),malformed.build())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invitationMembershipAndActivityIdentityMatterEvenWhenNumbersAreEqual() {
        Builder a = new Builder(), b = new Builder();
        a.invitations = List.of(new Invitation(100,0,List.of(200L),List.of(200L),Completeness.COMPLETE,Set.of()));
        b.invitations = List.of(new Invitation(100,0,List.of(201L),List.of(201L),Completeness.COMPLETE,Set.of()));
        a.descendantFinance=snapshot(List.of(),a.finance.coverage(),List.of(new FirstHistory(200,SupportPaymentFacts.Status.READY,List.of())));
        b.descendantFinance=snapshot(List.of(),b.finance.coverage(),List.of(new FirstHistory(201,SupportPaymentFacts.Status.READY,List.of())));
        assertThat(token(a)).isNotEqualTo(token(b));
        a = new Builder(); b = new Builder(); b.activities = List.of(new Activity(100,11L,2L,"LOGIN:11",T,"ACTIVE","AVAILABLE"));
        assertThat(token(a)).isNotEqualTo(token(b));
    }
    @Test void semanticUnknownMayHaveTokenButUnknownObservationPartialGraphOrMissingInputsCannot() {
        Builder b = new Builder(); assertThat(token(b)).startsWith("saq-v1:"); // history/refund/acquisition meaning remains UNKNOWN
        b.reads = new Reads(ReadState.COMPLETE,ReadState.COMPLETE,ReadState.UNKNOWN,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.NOT_REQUESTED);
        assertThat(evaluate(query(Map.of()),b.build())).isEqualTo(new Version(SupportAnalyticsQueryVersion.Status.UNKNOWN,null));
        b = new Builder(); b.invitations = List.of(new Invitation(100,0,List.of(),List.of(),Completeness.PARTIAL,Set.of(Reason.DANGLING_SPONSOR)));
        assertThat(evaluate(query(Map.of()),b.build()).value()).isNull();
        b = new Builder(); b.activities = List.of(); assertThat(evaluate(query(Map.of()),b.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.UNKNOWN);
        b = new Builder(); b.customers = List.of(); assertThat(evaluate(query(Map.of()),b.build()).value()).isNull();
        b = new Builder(); b.finance = snapshot(b.finance.facts(),List.of(b.finance.coverage().get(0)),b.finance.firstHistory());
        assertThat(evaluate(query(Map.of()),b.build()).value()).isNull();
        assertThat(evaluate(query(Map.of("filter","WAITING_REPLY")),new Builder().build()).value()).isNull();
    }
    @Test void birthReadFailureAndSourceFailureNeverReturnEmptyReadyVersion() {
        Builder b = new Builder(); b.finance = snapshot(b.finance.facts(),b.finance.coverage(),List.of(new FirstHistory(100,SupportPaymentFacts.Status.UNKNOWN,List.of("BIRTH_SOURCE_READ_FAILED"))));
        assertThat(evaluate(query(Map.of()),b.build())).isEqualTo(new Version(SupportAnalyticsQueryVersion.Status.FAILED,null));
        b = new Builder(); b.finance = new Snapshot(List.of(),List.of(new Issue(Source.DEPOSIT_ORDER,"s","SOURCE_READ_FAILED",100L)),b.finance.coverage(),"Asia/Shanghai",Instant.EPOCH,b.finance.firstHistory());
        assertThat(evaluate(query(Map.of()),b.build()).value()).isNull();
    }
    @Test void conflictsContainOnlyFixedCodeAndDirectTamperingHasNoReadyToken() {
        Builder b = new Builder(); var q = query(Map.of("pageNum","2","expectedVersion","saq-v1:"+"0".repeat(64)));
        assertThatThrownBy(() -> requireExpected(q,evaluate(q,b.build()))).isInstanceOf(VersionConflict.class).hasMessage("SUPPORT_ANALYTICS_QUERY_CHANGED");
        assertThatThrownBy(() -> requireExpected(q,new Version(SupportAnalyticsQueryVersion.Status.UNKNOWN,null))).hasMessage("SUPPORT_ANALYTICS_VERSION_UNVERIFIABLE");
        assertThatThrownBy(() -> evaluate(query(Map.of("groupId","9")),b.build())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void taskDueCrossingChangesVersionWithoutHashingTheEvaluationClock() {
        Builder before=new Builder(),after=new Builder();
        before.reads=after.reads=new Reads(ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE);
        before.tasks=List.of(task(false));after.tasks=List.of(task(false));
        var q=query(Map.of("filter","DUE"));assertThat(evaluate(q,before.build()).value()).isNotNull().isEqualTo(evaluate(q,after.build()).value());
        after.tasks=List.of(task(true));assertThat(evaluate(q,before.build()).value()).isNotEqualTo(evaluate(q,after.build()).value());
        after.tasks=List.of();assertThat(evaluate(q,after.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.UNKNOWN);
    }
    @Test void missingDescendantObservationCannotPretendReadyWhileUnknownHistoryMeaningCan() {
        Builder b=new Builder();b.invitations=List.of(new Invitation(100,0,List.of(200L),List.of(200L),Completeness.COMPLETE,Set.of()));
        assertThat(evaluate(query(Map.of()),b.build()).value()).isNull();
        b.descendantFinance=snapshot(List.of(),b.finance.coverage(),List.of(new FirstHistory(200,SupportPaymentFacts.Status.UNKNOWN,List.of("HISTORY_UNPROVEN"))));
        assertThat(evaluate(query(Map.of()),b.build()).value()).isNotNull();
        b.descendantFinance=snapshot(List.of(),List.of(b.finance.coverage().get(0)),b.descendantFinance.firstHistory());
        assertThat(evaluate(query(Map.of()),b.build()).value()).isNull();
    }
    @Test void unknownActivityOrMissingReadsCannotHideAnExplicitBirthReadFailure() {
        Builder b=new Builder();b.reads=new Reads(ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.COMPLETE,ReadState.UNKNOWN,ReadState.NOT_REQUESTED);
        b.finance=snapshot(b.finance.facts(),b.finance.coverage(),List.of(new FirstHistory(100,SupportPaymentFacts.Status.UNKNOWN,List.of("BIRTH_SOURCE_READ_FAILED"))));
        assertThat(evaluate(query(Map.of()),b.build())).isEqualTo(new Version(SupportAnalyticsQueryVersion.Status.FAILED,null));
        b.reads=null;assertThat(evaluate(query(Map.of()),b.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.FAILED);
    }
    @Test void missingCoverageCannotHideFinanceIssueCoverageReasonOrSelectedFirstFailure() {
        for(String domain:List.of("ISSUE","COVERAGE","FIRST")) {
            Builder b=new Builder();var source=b.finance.coverage().get(0);b.finance=snapshot(b.finance.facts(),List.of(source),b.finance.firstHistory());
            if(domain.equals("ISSUE"))b.finance=new Snapshot(b.finance.facts(),List.of(new Issue(Source.DEPOSIT_ORDER,"source7","SOURCE_READ_FAILED",100L)),b.finance.coverage(),b.finance.businessZone(),b.finance.evaluatedAt(),b.finance.firstHistory());
            if(domain.equals("COVERAGE"))b.finance=snapshot(b.finance.facts(),List.of(new Coverage(source.source(),SupportPaymentFacts.Status.UNKNOWN,source.historyStatus(),source.refundStatus(),source.historicalEnvironmentStatus(),source.supportedFrom(),List.of("SOURCE_READ_FAILED"),0,"v1")),b.finance.firstHistory());
            if(domain.equals("FIRST"))b.first=List.of(new First(100,"DEPOSIT:7","UNKNOWN","UNKNOWN",Proof.MISSING,List.of("BIRTH_SOURCE_READ_FAILED")));
            assertThat(evaluate(query(Map.of()),b.build()).status()).as(domain).isEqualTo(SupportAnalyticsQueryVersion.Status.FAILED);
        }
    }
    @Test void unknownFinanceCannotHideFailedInvitationAndTreeOrderDoesNotMatter() {
        var partial=new Invitation(100,0,List.of(),List.of(),Completeness.PARTIAL,Set.of(Reason.DANGLING_SPONSOR));
        for(var failed:List.of(new Invitation(200,0,List.of(),List.of(),Completeness.FAILED,Set.of()),
                new Invitation(200,0,List.of(),List.of(),Completeness.UNKNOWN,Set.of(Reason.SOURCE_READ_FAILED)))) {
            for(var trees:List.of(List.of(partial,failed),List.of(failed,partial))) {
                Builder b=new Builder();b.finance=unknownCoverage(b.finance);b.invitations=trees;
                assertThat(evaluate(query(Map.of()),b.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.FAILED);
            }
        }
    }
    @Test void unknownRootDomainsCannotHideAnyDescendantFinanceFailureDomain() {
        for(String domain:List.of("READ","ISSUE","COVERAGE","FIRST_HISTORY")) {
            Builder b=new Builder();b.finance=unknownCoverage(b.finance);b.invitations=List.of(new Invitation(100,0,List.of(200L),List.of(200L),Completeness.PARTIAL,Set.of(Reason.DANGLING_SPONSOR)));
            var complete=new Builder().finance;var history=List.of(new FirstHistory(200,SupportPaymentFacts.Status.READY,List.of()));
            b.descendantFinance=snapshot(List.of(),complete.coverage(),history);
            if(domain.equals("ISSUE"))b.descendantFinance=new Snapshot(List.of(),List.of(new Issue(Source.DEPOSIT_ORDER,"s","SOURCE_READ_FAILED",200L)),List.of(),complete.businessZone(),complete.evaluatedAt(),history);
            if(domain.equals("COVERAGE")) {var source=complete.coverage().get(0);b.descendantFinance=snapshot(List.of(),List.of(new Coverage(source.source(),SupportPaymentFacts.Status.UNKNOWN,source.historyStatus(),source.refundStatus(),source.historicalEnvironmentStatus(),source.supportedFrom(),List.of("SOURCE_READ_FAILED"),0,"v1")),history);}
            if(domain.equals("FIRST_HISTORY"))b.descendantFinance=snapshot(List.of(),List.of(),List.of(new FirstHistory(200,SupportPaymentFacts.Status.UNKNOWN,List.of("BIRTH_SOURCE_READ_FAILED"))));
            var e=b.build();if(domain.equals("READ"))e=new Evidence(e.reads(),e.authority(),e.candidateCustomerIds(),e.customers(),e.eventCandidates(),e.rules(),e.finance(),e.attributions(),e.first(),e.invitations(),e.devices(),e.unknownHoldingDevices(),e.activityCoverage(),e.activities(),e.tasks(),e.legacyCandidateCustomerIds(),e.descendantFinance(),ReadState.FAILED);
            assertThat(evaluate(query(Map.of()),e).status()).as(domain).isEqualTo(SupportAnalyticsQueryVersion.Status.FAILED);
        }
    }
    @Test void anyReadFailureWinsOverAnotherUnknownReadEvenWhenTasksAreNotRequested() {
        for(int domain=0;domain<8;domain++) {
            Builder b=new Builder();ReadState[] states=new ReadState[8];Arrays.fill(states,ReadState.UNKNOWN);states[domain]=ReadState.FAILED;
            b.reads=new Reads(states[0],states[1],states[2],states[3],states[4],states[5],states[6],states[7]);
            assertThat(evaluate(query(Map.of()),b.build()).status()).as("read domain %s",domain).isEqualTo(SupportAnalyticsQueryVersion.Status.FAILED);
        }
    }
    @Test void unknownWithoutExplicitFailureStaysUnknownAndTheCompleteNormalCaseStaysReady() {
        Builder unknown=new Builder();unknown.finance=unknownCoverage(unknown.finance);
        assertThat(evaluate(query(Map.of()),unknown.build())).isEqualTo(new Version(SupportAnalyticsQueryVersion.Status.UNKNOWN,null));
        unknown.invitations=List.of(new Invitation(100,0,List.of(),List.of(),Completeness.PARTIAL,Set.of(Reason.DANGLING_SPONSOR)));
        assertThat(evaluate(query(Map.of()),unknown.build()).status()).isEqualTo(SupportAnalyticsQueryVersion.Status.UNKNOWN);
        assertThat(token(new Builder())).startsWith("saq-v1:");
    }
    private static Snapshot unknownCoverage(Snapshot original) {
        var coverage=new ArrayList<>(original.coverage());var c=coverage.get(0);
        coverage.set(0,new Coverage(c.source(),SupportPaymentFacts.Status.UNKNOWN,c.historyStatus(),c.refundStatus(),c.historicalEnvironmentStatus(),c.supportedFrom(),c.reasons(),0,c.adapterVersion()));
        return snapshot(original.facts(),coverage,original.firstHistory());
    }
    private static Task task(boolean due) {
        return new Task(100,true,1L,20L,"OPEN",30L,null,T.minusDays(7),null,T,true,null,null,null,null,null,null,due,false,true,0L,"ACTIVE","ACTIVE",List.of(),T);
    }

    private static Fact fact(Source source, BigDecimal amount) {
        return new Fact("DEPOSIT:7",Kind.DEPOSIT,source,List.of("source7"),100,7,"source7",null,null,null,"USDT",amount,T,"paid_at",6,null,T,T,"v1",SupportPaymentFacts.Status.UNKNOWN);
    }
    private static Snapshot snapshot(List<Fact> facts,List<Coverage> coverage,List<FirstHistory> history) {
        return new Snapshot(facts,List.of(),coverage,"Asia/Shanghai",Instant.EPOCH,history);
    }
    private static Customer customer(String name,String state) { return new Customer(100,"BOUND","UNGROUPED",false,1L,null,name,state,T,T,null,null,null,null,null); }
    private static Device device(LocalDateTime heartbeat,ConnectionStatus state,BigDecimal hashrate) {
        return new Device(new DeviceEvidence(20,100,"order20","CHANNEL","IDC",hashrate,"HELD","ACTIVE",T,null,0,"PRODUCTION",null,
            new RuntimeEvidence(20L,"RUNNING",heartbeat,null,null,1,T),state),null,Acquisition.UNKNOWN,null,List.of("ACQUISITION_UNPROVEN"));
    }
    private static Authority authority(List<Relationship> relationships,List<Role> roles,List<Qualification> quals) {
        return new Authority(new ReadScope(1L,ReadMode.PERSONAL,null,null),List.of(),List.of(new Personnel(1,1,1L,true,false,1L)),roles,
            List.of(new Permission(1,2,3,4,"service_m1_read",1,1)),quals,List.of(),relationships);
    }
    private static final class Builder {
        Reads reads = COMPLETE;
        List<Long> candidateIds = List.of(100L);
        List<EventCandidate> events = List.of();
        List<Attribution> attributions = List.of();
        Authority authority = authority(List.of(new Relationship(RelationKind.ASSIGNMENT,10,100L,1L,null,null,"ACTIVE",false,1L,T,null)),
            List.of(new Role(1,2,1,"service",1)),List.of(new Qualification(1,1,"SERVICE","ENABLED",1L,T,null)));
        List<Customer> customers = List.of(customer("Customer","ACTIVE"));
        Snapshot finance = snapshot(List.of(fact(Source.DEPOSIT_ORDER,BigDecimal.TEN)),Arrays.stream(Source.values())
            .filter(s -> s != Source.FREE_TRIAL && s != Source.UNMATCHED_LEDGER)
            .map(s -> new Coverage(s,SupportPaymentFacts.Status.READY,SupportPaymentFacts.Status.UNKNOWN,SupportPaymentFacts.Status.UNKNOWN,
                SupportPaymentFacts.Status.UNKNOWN,T,List.of("HISTORY_UNPROVEN"),0,"v1")).toList(),List.of(new FirstHistory(100,SupportPaymentFacts.Status.READY,List.of())));
        List<First> first = List.of(new First(100,"DEPOSIT:7","CONFIRMED","AVAILABLE",Proof.ACCEPTED,List.of()));
        List<Invitation> invitations = List.of(new Invitation(100,0,List.of(),List.of(),Completeness.COMPLETE,Set.of()));
        List<Device> devices = List.of();
        ActivityCoverage coverage = new ActivityCoverage(T.minusDays(30),T,T.minusDays(7),T);
        List<Activity> activities = List.of(new Activity(100,10L,1L,"LOGIN:10",T,"ACTIVE","AVAILABLE"));
        List<Task> tasks=List.of();Snapshot descendantFinance=null;
        Evidence build() { return new Evidence(reads,authority,candidateIds,customers,events,
            new SupportRules(1L,7,7,7,"INFINITE",null,"SUPERVISOR",null),finance,attributions,first,invitations,devices,List.of(),coverage,activities,tasks,List.of(),descendantFinance,descendantFinance==null?ReadState.NOT_REQUESTED:ReadState.COMPLETE); }
    }
}
