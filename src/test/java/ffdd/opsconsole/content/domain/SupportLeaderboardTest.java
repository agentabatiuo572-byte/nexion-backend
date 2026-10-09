package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ffdd.opsconsole.content.domain.SupportLeaderboard.*;

class SupportLeaderboardTest {
    private static final Instant NOW = Instant.parse("2026-10-09T01:00:00Z");
    private static final YearMonth MONTH = YearMonth.of(2026,10);
    private Context context(Board board, Instant now, String currency, Scope scope, Set<Long> groups, String definition) {
        YearMonth month = YearMonth.from(now.atZone(BUSINESS_ZONE));
        return new Context(board,board == Board.customers ? null : month,month,currency,scope,groups,definition,now);
    }
    private Context context(Board board) {return context(board,NOW,"USDT",Scope.all,Set.of(),"definition-1");}
    private Count count(long value) {return new Count(value,Coverage.COMPLETE,Reason.NONE);}
    private Candidate agent(long id, long first, long customers, String amount, Board board) {
        return agent(id,first,customers,amount,board,"USDT",MONTH,"A",null);
    }
    private Candidate agent(long id,long first,long customers,String amount,Board board,String currency,YearMonth month,String group,Instant birth) {
        return new Candidate(id,"Agent " + id,null,group,Qualification.ACTIVE,count(first),count(customers),
            new Amount(amount == null ? null : new BigDecimal(amount),currency,month,
                board == Board.purchase ? AmountKind.PURCHASE : AmountKind.DEPOSIT,
                amount == null ? Coverage.UNKNOWN : Coverage.COMPLETE,amount == null ? Reason.REFUNDS_UNKNOWN : Reason.NONE,"finance-1"),birth);
    }
    private Snapshot snapshot(Board board, Candidate... agents) {return calculate(context(board),"source-1",Coverage.COMPLETE,List.of(agents));}
    private Publication published(long id, Board board, Instant at, Candidate... agents) {
        return new Publication(id,at,calculate(context(board,at,"USDT",Scope.all,Set.of(),"definition-1"),"old-"+id,Coverage.COMPLETE,List.of(agents)));
    }
    private Row row(Snapshot s,long id) {return s.rows().stream().filter(r -> r.agentId() == id).findFirst().orElseThrow();}
    private void code(int status, Runnable action) {assertEquals(status,assertThrows(BizException.class,action::run).getCode());}

    @Test void fullPrecisionCompetitionRanksAndNumericTieOrder() {
        Snapshot s=snapshot(Board.deposit,agent(20,0,0,"9.0001",Board.deposit),agent(10,0,0,"9.0001",Board.deposit),
            agent(3,0,0,"9.0002",Board.deposit),agent(2,0,0,"8",Board.deposit));
        assertEquals(List.of(3L,10L,20L,2L),s.rows().stream().map(Row::agentId).toList());
        assertEquals(List.of(1,2,2,4),s.rows().stream().map(Row::rank).toList());
        assertTrue(row(s,10).isTied()); assertTrue(row(s,20).isTied()); assertFalse(row(s,3).isTied());
    }
    @Test void allZeroIsCompleteTieNotEmptyAndScaledEqualAmountsTie() {
        Snapshot s=snapshot(Board.deposit,agent(10,0,0,"0.00",Board.deposit),agent(2,0,0,"0",Board.deposit));
        assertEquals(State.COMPLETE,s.state()); assertEquals(List.of(1,1),s.rows().stream().map(Row::rank).toList());
        assertTrue(s.rows().stream().allMatch(Row::isTied));
        assertEquals(2,page(s,null,1,20,null,2).ranked());
    }
    @Test void searchPaginationSummaryGapAndSelfPageUseSameFullSet() {
        Snapshot s=snapshot(Board.firstPayment,agent(1,10,40,null,Board.firstPayment),agent(2,8,30,null,Board.firstPayment),
            agent(3,8,25,null,Board.firstPayment),agent(4,5,20,null,Board.firstPayment));
        Page p=page(s,null,2,2,s.viewVersion(),4);
        assertEquals(List.of(3L,4L),p.rows().stream().map(Row::agentId).toList());
        assertEquals(4,p.self().row().rank()); assertEquals(new BigDecimal("3"),p.self().gap()); assertEquals(2,p.self().pageNum());
        Page search=page(s,"Agent 4",1,2,s.viewVersion(),4);
        assertEquals(4,search.rows().get(0).rank()); assertEquals(4,search.total()); assertEquals(1,search.matched());
        assertEquals(p.self().row(),publicSummary(s,4,s.viewVersion()));
        assertNull(page(s,null,1,2,null,1).self().gap());
        assertEquals(Reason.NOT_A_CANDIDATE,page(s,null,1,20,null,90).self().reason());
        assertEquals(2,page(s,"Agent 1",1,2,null,4).self().pageNum());
    }
    @Test void unknownReferenceAmountDoesNotInvalidateCountRankOrMovement() {
        Instant oldTime=Instant.parse("2026-10-08T15:45:00Z");
        Snapshot now=snapshot(Board.firstPayment,agent(1,8,50,null,Board.firstPayment),agent(2,10,70,null,Board.firstPayment));
        Publication yesterday=published(1,Board.firstPayment,oldTime,agent(1,8,40,null,Board.firstPayment),agent(2,5,60,null,Board.firstPayment));
        Snapshot s=withMovement(now,List.of(yesterday));
        assertEquals(State.COMPLETE,s.state()); assertEquals(1,row(s,2).rank());
        assertEquals(MovementKind.UP,row(s,2).movement().kind()); assertEquals(1,row(s,2).movement().places());
        assertNull(row(s,2).amount().value());
    }
    @Test void incompleteMainOrCandidatesSuppressAllAuthoritativeRanksAndGap() {
        Snapshot money=snapshot(Board.deposit,agent(1,10,40,"100",Board.deposit),agent(2,5,20,null,Board.deposit));
        assertEquals(State.PROVISIONAL,money.state()); assertTrue(money.rows().stream().allMatch(r -> r.rank()==null && !r.isTied()));
        assertEquals(0,page(money,null,1,20,null,1).ranked()); assertNull(page(money,null,1,20,null,1).self().gap());
        Snapshot roster=calculate(context(Board.firstPayment),"source-1",Coverage.UNKNOWN,List.of(agent(1,10,40,null,Board.firstPayment)));
        assertEquals(State.PROVISIONAL,roster.state()); assertNull(roster.rows().get(0).rank());
        code(503,() -> calculate(context(Board.firstPayment),"source-1",Coverage.FAILED,List.of()));
        Candidate c=agent(1,10,20,null,Board.firstPayment);
        Candidate partial=new Candidate(c.agentId(),c.name(),c.avatarUrl(),c.groupName(),c.qualification(),
            new Count(10L,Coverage.PARTIAL,Reason.HISTORY_UNKNOWN),c.customers(),c.amount(),null);
        assertNull(snapshot(Board.firstPayment,partial).rows().get(0).rank());
    }
    @Test void separateBoardsNeverSumDepositsAndPurchasesAndCountIgnoresCurrency() {
        Snapshot deposit=snapshot(Board.deposit,agent(1,1,10,"100",Board.deposit));
        Snapshot purchase=snapshot(Board.purchase,agent(1,1,10,"80",Board.purchase));
        assertEquals(new BigDecimal("100"),deposit.rows().get(0).amount().value());
        assertEquals(new BigDecimal("80"),purchase.rows().get(0).amount().value());
        Snapshot usdt=snapshot(Board.firstPayment,agent(1,10,20,"100",Board.firstPayment));
        Snapshot nex=calculate(context(Board.firstPayment,NOW,"NEX",Scope.all,Set.of(),"definition-1"),"nex-source",Coverage.COMPLETE,
            List.of(agent(1,10,20,"400",Board.firstPayment,"NEX",MONTH,"A",null)));
        assertEquals(usdt.comparisonKey(),nex.comparisonKey()); assertEquals(usdt.rows().get(0).rank(),nex.rows().get(0).rank());
        assertNotEquals(usdt.viewVersion(),nex.viewVersion());
    }
    @Test void customersReferenceMonthMustBeCurrentAndHiddenHistoricalMonthRejected() {
        Context c=context(Board.customers); assertNull(c.rankMonth()); assertEquals(MONTH,c.referenceMonth());
        code(422,() -> new Context(Board.customers,YearMonth.of(2026,9),MONTH,"USDT",Scope.all,Set.of(),"def",NOW));
        code(422,() -> new Context(Board.customers,null,YearMonth.of(2026,9),"USDT",Scope.all,Set.of(),"def",NOW));
        Snapshot s=snapshot(Board.customers,agent(1,1,20,null,Board.customers),agent(2,9,10,"100",Board.customers));
        assertEquals(1,row(s,1).rank()); assertEquals(2,row(s,2).rank());
    }
    @Test void conflictingDuplicateAgentsPeriodKindCurrencyAndInvalidMetricsAreRejected() {
        Candidate c=agent(1,0,0,"10",Board.deposit);
        code(422,() -> snapshot(Board.deposit,c,c));
        code(422,() -> snapshot(Board.purchase,c));
        code(422,() -> snapshot(Board.deposit,agent(1,0,0,"10",Board.deposit,"NEX",MONTH,"A",null)));
        code(422,() -> snapshot(Board.deposit,agent(1,0,0,"10",Board.deposit,"USDT",YearMonth.of(2026,9),"A",null)));
        code(422,() -> new Count(null,Coverage.COMPLETE,Reason.NONE));
        code(422,() -> new Count(0L,Coverage.UNKNOWN,Reason.HISTORY_UNKNOWN));
        code(422,() -> new Count(-1L,Coverage.COMPLETE,Reason.NONE));
        code(422,() -> new Amount(new BigDecimal("-1"),"USDT",MONTH,AmountKind.DEPOSIT,Coverage.COMPLETE,Reason.NONE,"v"));
        code(422,() -> new Candidate(1,"A","/api/admin/content/support-agents/1/avatar","A",Qualification.ACTIVE,c.firstPayment(),c.customers(),c.amount(),null));
    }
    @Test void crossPageAndPublicDetailRequireExpectedVersionAndChangedVersionConflicts() {
        Snapshot s=snapshot(Board.firstPayment,agent(1,1,1,null,Board.firstPayment));
        code(422,() -> page(s,null,2,20,null,1)); code(422,() -> publicSummary(s,1,null));
        code(409,() -> page(s,null,1,20,"slb-v1:"+"0".repeat(64),1));
        code(409,() -> publicSummary(s,1,"slb-v1:"+"0".repeat(64)));
        code(404,() -> publicSummary(s,99,s.viewVersion()));
        assertTrue(page(s,null,9007199254740991L,100,s.viewVersion(),1).rows().isEmpty());
    }
    @Test void sourceHiddenRowsIdentityAndAvatarInvalidateViewVersionNotComparison() {
        Candidate c=agent(1,1,2,"10",Board.firstPayment);
        Snapshot a=snapshot(Board.firstPayment,c);
        Candidate changed=new Candidate(1,"Renamed","/api/admin/content/support-workbench/leaderboard/1/avatar?assetVersion=2","B",Qualification.ACTIVE,c.firstPayment(),c.customers(),c.amount(),null);
        Snapshot b=snapshot(Board.firstPayment,changed);
        assertEquals(a.comparisonKey(),b.comparisonKey()); assertNotEquals(a.viewVersion(),b.viewVersion());
        assertNotEquals(a.viewVersion(),calculate(context(Board.firstPayment),"source-2",Coverage.COMPLETE,List.of(c)).viewVersion());
        Snapshot hidden=snapshot(Board.firstPayment,c,agent(2,0,1,"1",Board.firstPayment));
        assertNotEquals(page(a,"Agent 1",1,20,null,1).viewVersion(),page(hidden,"Agent 1",1,20,null,1).viewVersion());
    }
    @Test void yesterdayUsesLastCompletePublicationInShanghaiAndDoesNotMutateBaseline() {
        Instant earlier=Instant.parse("2026-10-08T13:00:00Z"),latest=Instant.parse("2026-10-08T15:58:30Z");
        Publication a=published(1,Board.firstPayment,earlier,agent(1,10,1,null,Board.firstPayment),agent(2,5,1,null,Board.firstPayment));
        Publication b=published(2,Board.firstPayment,latest,agent(1,5,1,null,Board.firstPayment),agent(2,10,1,null,Board.firstPayment));
        Publication afterMidnight=published(3,Board.firstPayment,Instant.parse("2026-10-08T16:00:00Z"),agent(1,10,1,null,Board.firstPayment),agent(2,5,1,null,Board.firstPayment));
        Snapshot now=snapshot(Board.firstPayment,agent(1,11,1,null,Board.firstPayment),agent(2,10,1,null,Board.firstPayment));
        Snapshot s=withMovement(now,List.of(afterMidnight,a,b));
        assertEquals(MovementKind.UP,row(s,1).movement().kind()); assertEquals(2,row(s,1).movement().previousRank());
        assertEquals(latest,row(s,1).movement().baselineAt()); assertEquals(b.snapshot().viewVersion(),row(s,1).movement().baselineVersion());
        assertEquals(2,row(b.snapshot(),1).rank());
        Snapshot provisional=calculate(context(Board.firstPayment,latest.plusSeconds(10),"USDT",Scope.all,Set.of(),"definition-1"),"bad",Coverage.UNKNOWN,List.of(agent(1,99,1,null,Board.firstPayment)));
        assertEquals(latest,row(withMovement(now,List.of(a,b,new Publication(4,latest.plusSeconds(10),provisional))),1).movement().baselineAt());
    }
    @Test void historicalMonthMonthStartMissingAndIncompleteBaselinesNeverInventMovement() {
        Snapshot now=snapshot(Board.firstPayment,agent(1,1,1,null,Board.firstPayment));
        assertEquals(Reason.NO_BASELINE,row(withMovement(now,List.of()),1).movement().reason());
        Instant first=Instant.parse("2026-09-30T16:01:00Z");
        Snapshot start=calculate(context(Board.firstPayment,first,"USDT",Scope.all,Set.of(),"definition-1"),"s",Coverage.COMPLETE,List.of(agent(1,0,1,null,Board.firstPayment)));
        assertEquals(Reason.MONTH_START,row(withMovement(start,List.of()),1).movement().reason());
        Context history=new Context(Board.firstPayment,YearMonth.of(2026,9),YearMonth.of(2026,9),"USDT",Scope.all,Set.of(),"definition-1",NOW);
        Snapshot historical=calculate(history,"s",Coverage.COMPLETE,List.of(agent(1,1,1,null,Board.firstPayment,"USDT",YearMonth.of(2026,9),"A",null)));
        assertEquals(Reason.HISTORICAL_MONTH,row(withMovement(historical,List.of()),1).movement().reason());
        Snapshot partial=calculate(context(Board.firstPayment),"s",Coverage.PARTIAL,List.of(agent(1,1,1,null,Board.firstPayment)));
        assertEquals(MovementKind.UNAVAILABLE,row(withMovement(partial,List.of()),1).movement().kind());
    }
    @Test void amountBaselineCurrencyDiffersButCountReferenceCurrencyDoesNotBlockMovement() {
        Instant before=NOW.minusSeconds(86400);
        Publication usdt=published(1,Board.firstPayment,before,agent(1,1,1,null,Board.firstPayment));
        Snapshot nex=calculate(context(Board.firstPayment,NOW,"NEX",Scope.all,Set.of(),"definition-1"),"nex",Coverage.COMPLETE,
            List.of(agent(1,2,1,null,Board.firstPayment,"NEX",MONTH,"A",null)));
        assertEquals(MovementKind.SAME,row(withMovement(nex,List.of(usdt)),1).movement().kind());
        Publication money=published(2,Board.deposit,before,agent(1,1,1,"10",Board.deposit));
        Snapshot nexMoney=calculate(context(Board.deposit,NOW,"NEX",Scope.all,Set.of(),"definition-1"),"nex",Coverage.COMPLETE,
            List.of(agent(1,2,1,"10",Board.deposit,"NEX",MONTH,"A",null)));
        assertEquals(Reason.NO_BASELINE,row(withMovement(nexMoney,List.of(money)),1).movement().reason());
    }
    @Test void globalRegroupingKeepsMovementButGroupMembershipAndApprovedScopeChangesBlock() {
        Instant before=NOW.minusSeconds(86400);
        Publication global=published(1,Board.firstPayment,before,agent(1,1,1,null,Board.firstPayment));
        Snapshot relabeled=snapshot(Board.firstPayment,agent(1,2,1,null,Board.firstPayment,"USDT",MONTH,"B",null));
        assertEquals(MovementKind.SAME,row(withMovement(relabeled,List.of(global)),1).movement().kind());
        Context oldGroup=context(Board.firstPayment,before,"USDT",Scope.ownGroup,Set.of(10L),"definition-1");
        Publication grouped=new Publication(2,before,calculate(oldGroup,"old",Coverage.COMPLETE,List.of(agent(1,1,1,null,Board.firstPayment))));
        Context currentGroup=context(Board.firstPayment,NOW,"USDT",Scope.ownGroup,Set.of(10L),"definition-1");
        Snapshot added=calculate(currentGroup,"new",Coverage.COMPLETE,List.of(agent(1,2,1,null,Board.firstPayment),agent(2,2,1,null,Board.firstPayment,"USDT",MONTH,"A",before.plusSeconds(1))));
        assertEquals(Reason.MEMBERS_CHANGED,row(withMovement(added,List.of(grouped)),1).movement().reason());
        Snapshot changedScope=calculate(context(Board.firstPayment,NOW,"USDT",Scope.ownGroup,Set.of(20L),"definition-1"),"new",Coverage.COMPLETE,List.of(agent(1,2,1,null,Board.firstPayment)));
        assertEquals(Reason.SCOPE_CHANGED,row(withMovement(changedScope,List.of(grouped)),1).movement().reason());
        Snapshot managed=calculate(context(Board.firstPayment,NOW,"USDT",Scope.managedGroups,Set.of(10L,20L),"definition-1"),"new",Coverage.COMPLETE,List.of(agent(1,2,1,null,Board.firstPayment)));
        assertEquals(Reason.SCOPE_CHANGED,row(withMovement(managed,List.of(grouped)),1).movement().reason());
    }
    @Test void newRequiresProvenInitialQualificationAfterBaselineNotRegrantOrBackfill() {
        Instant before=NOW.minusSeconds(86400);
        Publication old=published(1,Board.firstPayment,before,agent(1,1,1,null,Board.firstPayment));
        Snapshot proven=snapshot(Board.firstPayment,agent(1,2,1,null,Board.firstPayment),agent(2,3,1,null,Board.firstPayment,"USDT",MONTH,"A",before.plusSeconds(1)));
        Snapshot s=withMovement(proven,List.of(old));
        assertEquals(MovementKind.NEW,row(s,2).movement().kind()); assertNull(row(s,2).movement().places());
        Snapshot backfill=snapshot(Board.firstPayment,agent(1,2,1,null,Board.firstPayment),agent(2,3,1,null,Board.firstPayment));
        assertEquals(Reason.MEMBERS_CHANGED,row(withMovement(backfill,List.of(old)),2).movement().reason());
        Snapshot priorBirth=snapshot(Board.firstPayment,agent(1,2,1,null,Board.firstPayment),agent(2,3,1,null,Board.firstPayment,"USDT",MONTH,"A",before.minusSeconds(1)));
        assertEquals(Reason.MEMBERS_CHANGED,row(withMovement(priorBirth,List.of(old)),2).movement().reason());
    }
    @Test void definitionChangeAndCompetitionRankMovementUseGenuineOldRanks() {
        Instant before=NOW.minusSeconds(86400);
        List<Candidate> oldAgents=new ArrayList<>();
        for(long i=1;i<=8;i++) oldAgents.add(agent(i,9-i,1,null,Board.firstPayment));
        Publication old=new Publication(1,before,calculate(context(Board.firstPayment,before,"USDT",Scope.all,Set.of(),"definition-1"),"old",Coverage.COMPLETE,oldAgents));
        List<Candidate> newAgents=new ArrayList<>();
        for(long i=1;i<=8;i++) newAgents.add(agent(i,i==1 ? 10 : i==8 || i==2 ? 8 : 1,1,null,Board.firstPayment));
        Snapshot now=calculate(context(Board.firstPayment),"new",Coverage.COMPLETE,newAgents);
        assertEquals(6,row(withMovement(now,List.of(old)),8).movement().places());
        Snapshot changed=calculate(context(Board.firstPayment,NOW,"USDT",Scope.all,Set.of(),"definition-2"),"new",Coverage.COMPLETE,newAgents);
        assertEquals(Reason.DEFINITION_CHANGED,row(withMovement(changed,List.of(old)),8).movement().reason());
    }
    @Test void publicWhitelistAndImmutableSnapshotDoNotExposeQualificationProofOrPrivateObjects() {
        Set<String> fields=new HashSet<>(); for(var component:Row.class.getRecordComponents()) fields.add(component.getName());
        assertEquals(Set.of("agentId","name","avatarUrl","groupName","qualification","rank","isTied","firstPayment","customers","amount","rankMetricCoverage","movement"),fields);
        Snapshot s=snapshot(Board.firstPayment,agent(1,1,1,null,Board.firstPayment));
        assertThrows(UnsupportedOperationException.class,() -> s.rows().clear());
        assertThrows(UnsupportedOperationException.class,() -> s.qualificationBirths().clear());
        code(422,() -> new Publication(0,NOW,s)); code(422,() -> new Publication(1,NOW.minusSeconds(1),s));
    }
    @Test void repeatedMovementEnrichmentAndCandidateInputOrderAreVersionStable() {
        Instant before=NOW.minusSeconds(86400);
        Publication old=published(1,Board.firstPayment,before,agent(1,1,1,null,Board.firstPayment),agent(2,2,1,null,Board.firstPayment));
        Candidate a=agent(1,3,1,null,Board.firstPayment),b=agent(2,2,1,null,Board.firstPayment);
        Snapshot base=snapshot(Board.firstPayment,a,b),reordered=snapshot(Board.firstPayment,b,a);
        assertEquals(base.viewVersion(),reordered.viewVersion());
        Snapshot once=withMovement(base,List.of(old)),twice=withMovement(once,List.of(old));
        assertEquals(once.viewVersion(),twice.viewVersion()); assertEquals(once.rows(),twice.rows());
        assertEquals(base.viewVersion(),withMovement(base,List.of()).viewVersion());
    }
    @Test void delayedPublicationUsesStatisticalCutoffForNewAndEveryMovementButSelectsLastCommit() {
        Instant cutoff=Instant.parse("2026-10-08T02:00:00Z"),commit=cutoff.plusSeconds(300),birth=cutoff.plusSeconds(120);
        Snapshot earlier=calculate(context(Board.firstPayment,cutoff.minusSeconds(60),"USDT",Scope.all,Set.of(),"definition-1"),
            "early",Coverage.COMPLETE,List.of(agent(1,5,1,null,Board.firstPayment),agent(2,10,1,null,Board.firstPayment)));
        Snapshot last=calculate(context(Board.firstPayment,cutoff,"USDT",Scope.all,Set.of(),"definition-1"),
            "last",Coverage.COMPLETE,List.of(agent(1,10,1,null,Board.firstPayment),agent(2,5,1,null,Board.firstPayment)));
        Publication earlyPublication=new Publication(1,commit.minusSeconds(60),earlier),lastPublication=new Publication(2,commit,last);
        Snapshot current=snapshot(Board.firstPayment,agent(1,8,1,null,Board.firstPayment),agent(2,9,1,null,Board.firstPayment),
            agent(3,10,1,null,Board.firstPayment,"USDT",MONTH,"A",birth));
        Snapshot result=withMovement(current,List.of(lastPublication,earlyPublication));
        assertEquals(MovementKind.NEW,row(result,3).movement().kind());
        assertEquals(MovementKind.DOWN,row(result,1).movement().kind()); assertEquals(2,row(result,1).movement().places());
        assertTrue(result.rows().stream().allMatch(r -> cutoff.equals(r.movement().baselineAt())
            && last.viewVersion().equals(r.movement().baselineVersion())));
        assertEquals(1,row(last,1).rank()); assertEquals(Reason.NO_BASELINE,row(last,1).movement().reason());
        assertEquals(result.viewVersion(),withMovement(result,List.of(earlyPublication,lastPublication)).viewVersion());
    }
    @Test void laterDifferentDefinitionBlocksEarlierMatchingDefinitionWithoutCrossScopeContamination() {
        Instant early=NOW.minusSeconds(86400),late=early.plusSeconds(120);
        Candidate c=agent(1,1,1,null,Board.firstPayment);
        Publication a=published(1,Board.firstPayment,early,c);
        Snapshot changedDefinition=calculate(context(Board.firstPayment,late,"USDT",Scope.all,Set.of(),"definition-2"),"b",Coverage.COMPLETE,List.of(c));
        Publication b=new Publication(2,late,changedDefinition);
        Snapshot current=snapshot(Board.firstPayment,c);
        assertEquals(Reason.DEFINITION_CHANGED,row(withMovement(current,List.of(a,b)),1).movement().reason());
        Snapshot anotherScope=calculate(context(Board.firstPayment,late.plusSeconds(120),"USDT",Scope.ownGroup,Set.of(10L),"definition-2"),"other",Coverage.COMPLETE,List.of(c));
        Publication unrelated=new Publication(3,late.plusSeconds(120),anotherScope);
        assertEquals(MovementKind.SAME,row(withMovement(current,List.of(a,unrelated)),1).movement().kind());
        assertEquals(a.snapshot().viewVersion(),row(withMovement(current,List.of(a,unrelated)),1).movement().baselineVersion());
    }
    @Test void equalPublicationTimesChooseHighestPersistentPublicationId() {
        Instant before=NOW.minusSeconds(86400);
        Publication low=published(1,Board.firstPayment,before,agent(1,10,1,null,Board.firstPayment),agent(2,5,1,null,Board.firstPayment));
        Publication high=published(2,Board.firstPayment,before,agent(1,5,1,null,Board.firstPayment),agent(2,10,1,null,Board.firstPayment));
        Snapshot result=withMovement(snapshot(Board.firstPayment,agent(1,11,1,null,Board.firstPayment),agent(2,10,1,null,Board.firstPayment)),List.of(high,low));
        assertEquals(2,row(result,1).movement().previousRank());
        assertEquals(high.snapshot().viewVersion(),row(result,1).movement().baselineVersion());
    }
    @Test void snapshotDigestDetectsChangedContentAndForgedVersionAfterMovementEnrichment() {
        Snapshot current=snapshot(Board.firstPayment,agent(1,4,2,null,Board.firstPayment));
        Snapshot enriched=withMovement(current,List.of());
        assertTrue(matchesSnapshotVersion(current));
        assertTrue(matchesSnapshotVersion(enriched));
        Row original=enriched.rows().get(0);
        Row renamed=new Row(original.agentId(),"Changed",original.avatarUrl(),original.groupName(),original.qualification(),
            original.rank(),original.isTied(),original.firstPayment(),original.customers(),original.amount(),original.rankMetricCoverage(),original.movement());
        Snapshot changed=new Snapshot(enriched.context(),enriched.sourceVersion(),enriched.candidateCoverage(),enriched.state(),
            enriched.comparisonKey(),enriched.viewVersion(),List.of(renamed),enriched.qualificationBirths());
        assertFalse(matchesSnapshotVersion(changed));
        Snapshot forged=new Snapshot(enriched.context(),enriched.sourceVersion(),enriched.candidateCoverage(),enriched.state(),
            enriched.comparisonKey(),"slb-v1:"+"0".repeat(64),enriched.rows(),enriched.qualificationBirths());
        assertFalse(matchesSnapshotVersion(forged));
        assertFalse(matchesSnapshotVersion(null));
    }
    @Test void allPrimaryMetricFailuresRejectPublicationWhileReferenceFailureKeepsCompleteCountRanks() {
        for(Board board:Board.values()) {
            List<Candidate> failed=new ArrayList<>();
            for(long id=1;id<=2;id++) {
                Count primary=new Count(null,Coverage.FAILED,Reason.SOURCE_FAILED);
                Amount amount=new Amount(null,"USDT",MONTH,board==Board.purchase ? AmountKind.PURCHASE : AmountKind.DEPOSIT,
                    Coverage.FAILED,Reason.SOURCE_FAILED,"failed-finance");
                failed.add(new Candidate(id,"Agent "+id,null,"A",Qualification.ACTIVE,
                    board==Board.firstPayment ? primary : count(1),board==Board.customers ? primary : count(1),amount,null));
            }
            for(Coverage roster:List.of(Coverage.COMPLETE,Coverage.PARTIAL,Coverage.UNKNOWN))
                code(503,()->calculate(context(board),"failed",roster,failed));
            failed.add(agent(3,3,3,null,board));
            // One verified count or genuinely UNKNOWN money source remains provisional, not an all-FAILED set.
            assertEquals(State.PROVISIONAL,calculate(context(board),"mixed",Coverage.COMPLETE,failed).state());
        }
        for(Board board:List.of(Board.firstPayment,Board.customers)) {
            Amount failedReference=new Amount(null,"USDT",MONTH,AmountKind.DEPOSIT,Coverage.FAILED,Reason.SOURCE_FAILED,"failed-reference");
            Candidate c=new Candidate(1,"Agent 1",null,"A",Qualification.ACTIVE,count(2),count(3),failedReference,null);
            Snapshot complete=snapshot(board,c);
            assertEquals(State.COMPLETE,complete.state()); assertEquals(1,row(complete,1).rank());
            Publication previous=new Publication(1,NOW.minusSeconds(86400),calculate(context(board,NOW.minusSeconds(86400),"USDT",Scope.all,Set.of(),"definition-1"),"old",Coverage.COMPLETE,List.of(c)));
            assertEquals(MovementKind.SAME,row(withMovement(complete,List.of(previous)),1).movement().kind());
        }
        assertEquals(State.COMPLETE,snapshot(Board.deposit).state());
    }
}
