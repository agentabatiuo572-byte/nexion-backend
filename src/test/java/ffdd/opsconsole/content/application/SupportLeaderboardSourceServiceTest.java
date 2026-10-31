package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.NetSnapshot;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.NetCustomer;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ContributionOrigin;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.ContributionChange;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.EvidenceState;
import ffdd.opsconsole.finance.domain.SupportFundAllocation;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Action;
import ffdd.opsconsole.finance.domain.SupportFundAllocation.Event;
import ffdd.opsconsole.finance.domain.SupportFundReplay;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @Test void postCutoverCompleteSourcesProduceMonthlyRanksAndPodium() {
        for(String currency:List.of("USDT","UVT"))for(Board board:List.of(Board.firstPayment,Board.netContribution)) {
            var f=completeMonthly(currency);var read=f.read(context(board,currency,Scope.all,Set.of()));
            assertEquals(Coverage.COMPLETE,read.candidateCoverage());assertEquals(List.of(7L),read.candidates().stream().map(Candidate::agentId).toList());
            var ranked=SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates());
            assertEquals(State.COMPLETE,ranked.state());assertEquals(1,ranked.rows().get(0).rank());
            assertEquals(1,SupportLeaderboard.podium(ranked).total());
        }
    }
    @Test void missingFutureAndCrossMonthCutoversKeepCompleteMetricsUnranked() {
        for(LocalDateTime cutover:Arrays.asList(null,NOW.plusSeconds(1),LocalDateTime.parse("2026-10-07T00:00:00"))) {
            var f=completeMonthly("UVT");when(f.mapper.qualificationCutoverUtc()).thenReturn(cutover);
            var read=f.read(Board.firstPayment);assertEquals(Coverage.PARTIAL,read.candidateCoverage());
            assertEquals(Coverage.COMPLETE,read.candidates().get(0).firstPayment().coverage());
            var ranked=SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates());
            assertNull(ranked.rows().get(0).rank());assertEquals(0,SupportLeaderboard.podium(ranked).total());
        }
    }
    @Test void missingQualificationMembershipAndBrokenHistoryRemainPartial() {
        var gap=LocalDateTime.parse("2026-10-03T00:00:00");
        for(int defect=0;defect<7;defect++) {
            var f=completeMonthly("UVT");
            if(defect==0)when(f.mapper.qualifications()).thenReturn(List.of());
            if(defect==1)when(f.mapper.memberships()).thenReturn(List.of());
            if(defect==2)when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,gap),new QualificationInterval(2L,7L,"ENABLED",2L,gap.plusSeconds(1),null)));
            if(defect==3)when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,gap),new QualificationInterval(2L,7L,"ENABLED",3L,gap,null)));
            if(defect==4)when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,100L,1L,START,gap),new MemberInterval(2L,7L,100L,3L,gap,null)));
            if(defect==5)when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,100L,1L,START,gap),new MemberInterval(2L,7L,100L,2L,gap.plusSeconds(1),null)));
            if(defect==6)when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,"甲",1,0,1L,1,0,1L,"DEDICATED",null,null,null,null,0,0)));
            var read=f.read(Board.firstPayment);assertEquals(Coverage.PARTIAL,read.candidateCoverage(),"defect "+defect);
            assertEquals(List.of(7L),read.candidates().stream().map(Candidate::agentId).toList());
        }
    }
    @Test void disabledRemovedAndExplicitlyUngroupedHistoriesCanBeComplete() {
        var at=LocalDateTime.parse("2026-10-03T00:00:00");
        for(String state:List.of("DISABLED","REMOVED")) {
            var f=completeMonthly("UVT");when(f.mapper.currentBindings()).thenReturn(List.of());
            when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",0),account(8,"主管",1)));
            when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,at),new QualificationInterval(2L,7L,state,2L,at,null)));
            when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,null,1L,START,state.equals("REMOVED")?at:null)));
            assertEquals(Coverage.COMPLETE,f.read(Board.firstPayment).candidateCoverage());
        }
        var f=completeMonthly("UVT");when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,null,1L,START,null)));
        assertEquals(Coverage.COMPLETE,f.read(Board.firstPayment).candidateCoverage());
    }
    @Test void cutoverTimestampChangesSourceVersionEvenWhenBothMonthsAreCovered() {
        var f=completeMonthly("UVT");var before=f.read(Board.firstPayment);
        when(f.mapper.qualificationCutoverUtc()).thenReturn(START.minusDays(1));var after=f.read(Board.firstPayment);
        assertEquals(Coverage.COMPLETE,before.candidateCoverage());assertEquals(Coverage.COMPLETE,after.candidateCoverage());
        assertEquals(before.candidates().get(0).firstPayment(),after.candidates().get(0).firstPayment());
        assertNotEquals(before.sourceVersion(),after.sourceVersion());
    }
    private static Fixture completeMonthly(String currency) {
        var f=new Fixture();when(f.mapper.qualificationCutoverUtc()).thenReturn(START);
        var fact=fact(1,101,Kind.DEPOSIT,currency,START.plusMonths(1));f.events(fact);f.financial(List.of(fact),ready(1));
        f.net(fact,change(fact,"1.25",fact.succeededAt()));return f;
    }
    @Test void firstPaymentCannotBypassTheAuthorizedCurrentGroup() {
        var f=completeMonthly("UVT");
        when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,200L,1L,START,null)));
        var fact=fact(1,101,Kind.DEPOSIT,"UVT",START.plusMonths(1));var row=owner(fact,7L,200L,"KNOWN");
        when(f.mapper.attributions()).thenReturn(List.of(row));when(f.mapper.attributionProofs()).thenReturn(List.of(netProof(fact,row)));
        assertTrue(f.read(context(Board.firstPayment,"UVT",Scope.ownGroup,Set.of(100L))).candidates().isEmpty());
        assertEquals(List.of(7L),f.read(context(Board.firstPayment,"UVT",Scope.ownGroup,Set.of(200L))).candidates().stream().map(Candidate::agentId).toList());
    }
    @Test void outOfScopeFirstPaymentDoesNotDowngradeCandidateCoverage() {
        var f=completeMonthly("UVT");when(f.mapper.qualifications()).thenReturn(List.of());
        when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,200L,1L,START,null)));
        var fact=fact(1,101,Kind.DEPOSIT,"UVT",START.plusMonths(1));var row=owner(fact,7L,200L,"KNOWN");
        when(f.mapper.attributions()).thenReturn(List.of(row));when(f.mapper.attributionProofs()).thenReturn(List.of(netProof(fact,row)));
        var outside=f.read(context(Board.firstPayment,"UVT",Scope.ownGroup,Set.of(100L)));
        assertTrue(outside.candidates().isEmpty());assertEquals(Coverage.COMPLETE,outside.candidateCoverage());
        assertEquals(Coverage.PARTIAL,f.read(context(Board.firstPayment,"UVT",Scope.ownGroup,Set.of(200L))).candidateCoverage());
    }
    @Test void provenNeverEnabledDisabledChainNeedsNoMembershipOrProfile() {
        var at=LocalDateTime.parse("2026-10-03T00:00:00");
        for(int defect=0;defect<4;defect++) {
            var f=completeMonthly("UVT");
            when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",1),new Account(9L,"未启用",1,0,1L,null,null,null,null,null,null,null,null,1,0)));
            var first=new QualificationInterval(2L,9L,defect==1?"ENABLED":"DISABLED",defect==2?2L:1L,START,at);
            when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null),first,
                new QualificationInterval(3L,9L,"DISABLED",defect==2?3L:2L,defect==3?at.plusSeconds(1):at,null)));
            var read=f.read(Board.firstPayment);assertEquals(defect==0?Coverage.COMPLETE:Coverage.PARTIAL,read.candidateCoverage(),"defect "+defect);
            if(defect==0)assertEquals(List.of(7L),read.candidates().stream().map(Candidate::agentId).toList());
        }
        var f=completeMonthly("UVT");when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"DISABLED",START,null)));
        when(f.mapper.memberships()).thenReturn(List.of());
        assertEquals(Coverage.PARTIAL,f.read(Board.firstPayment).candidateCoverage());
    }
    @Test void zeroDurationQualificationAndMembershipTransitionsPreserveVersionContinuity() {
        var at=LocalDateTime.parse("2026-10-03T00:00:00");
        for(int intervals=1;intervals<=3;intervals++) {
            var f=completeMonthly("UVT");
            if((intervals&1)!=0)when(f.mapper.qualifications()).thenReturn(List.of(
                new QualificationInterval(2L,7L,"ENABLED",3L,at,null),q(1,7,"ENABLED",START,at),
                new QualificationInterval(3L,7L,"DISABLED",2L,at,at)));
            if((intervals&2)!=0)when(f.mapper.memberships()).thenReturn(List.of(
                new MemberInterval(2L,7L,100L,3L,at,null),new MemberInterval(1L,7L,100L,1L,START,at),
                new MemberInterval(3L,7L,null,2L,at,at)));
            var read=f.read(Board.firstPayment);assertEquals(Coverage.COMPLETE,read.candidateCoverage());
            var ranked=SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates());
            assertEquals(1,ranked.rows().get(0).rank());assertEquals(1,SupportLeaderboard.podium(ranked).total());
        }
    }
    @Test void firstVersionMidMonthQualificationsCanBeCompleteOnlyWithoutEarlierOwnership() {
        var at=LocalDateTime.parse("2026-10-03T00:00:00");
        for(String state:List.of("ENABLED","DISABLED"))for(int defect=0;defect<5;defect++) {
            var f=completeMonthly("UVT");when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",1),account(9,"新客服",1)));
            when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null),
                new QualificationInterval(2L,9L,state,defect==1?2L:1L,at,defect==4?at.plusDays(1):null)));
            if(state.equals("ENABLED"))when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,100L,1L,START,null),
                new MemberInterval(2L,9L,100L,1L,at,null)));
            if(defect==2)when(f.mapper.currentBindings()).thenReturn(List.of(new Binding(1L,1L,9L,1L,at.minusSeconds(1),null)));
            if(defect==3) {
                var fact=fact(1,101,Kind.DEPOSIT,"UVT",at.atOffset(ZoneOffset.UTC).atZoneSameInstant(SupportLeaderboard.BUSINESS_ZONE).toLocalDateTime().minusSeconds(1));
                var row=owner(fact,9L,100L,"KNOWN");f.financial(List.of(fact),ready(1));f.net(fact,change(fact,"1.25",fact.succeededAt()));
                when(f.mapper.attributions()).thenReturn(List.of(row));when(f.mapper.attributionProofs()).thenReturn(List.of(netProof(fact,row)));
            }
            if(defect==4)when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null),
                new QualificationInterval(2L,9L,state,1L,at,at.plusDays(1)),new QualificationInterval(3L,9L,state,2L,at.plusDays(1).plusSeconds(1),null)));
            var read=f.read(Board.firstPayment);assertEquals(defect==0?Coverage.COMPLETE:Coverage.PARTIAL,read.candidateCoverage(),state+" defect "+defect);
            if(defect==0) {
                assertEquals(state.equals("ENABLED")?List.of(7L,9L):List.of(7L),read.candidates().stream().map(Candidate::agentId).toList());
                var ranked=SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates());
                assertEquals(1,ranked.rows().get(0).rank());assertEquals(State.COMPLETE,ranked.state());
            }
        }
        var enabled=at.plusDays(2);
        for(int defect=0;defect<5;defect++) {
            var f=completeMonthly("UVT");when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",1),account(9,"新客服",1)));
            when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null),
                new QualificationInterval(2L,9L,"DISABLED",defect==3?2L:1L,at,enabled),
                new QualificationInterval(3L,9L,"ENABLED",defect==3?3L:2L,enabled,null)));
            when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,100L,1L,START,null),
                new MemberInterval(2L,9L,100L,1L,defect==1?enabled.minusSeconds(1):enabled,null)));
            if(defect==2)when(f.mapper.currentBindings()).thenReturn(List.of(new Binding(1L,1L,9L,1L,enabled.minusSeconds(1),null)));
            if(defect==4)when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,null),
                new QualificationInterval(2L,9L,"DISABLED",1L,at,enabled),new QualificationInterval(4L,9L,"REMOVED",2L,enabled,enabled),
                new QualificationInterval(3L,9L,"ENABLED",3L,enabled,null)));
            var read=f.read(Board.firstPayment);assertEquals(defect==0 || defect==4?Coverage.COMPLETE:Coverage.PARTIAL,
                read.candidateCoverage(),"first enable defect "+defect);
            if(defect==0 || defect==4)assertEquals(List.of(7L,9L),read.candidates().stream().map(Candidate::agentId).toList());
        }
    }
    @Test void actualReplayGenesisConsumptionCountsOnlyUncreditedPartAndDoesNotBecomeFirstPayment() {
        var deposit=money(fact(1,101,Kind.DEPOSIT,"USDT",START),"50");
        var genesis=new Fact("CONSUMPTION:GEN-1",Kind.CONSUMPTION,Source.GENESIS_PRIMARY,List.of("nx_genesis_order:1"),1,201,"GEN-1","GEN-1","PRIMARY",null,
            "USDT",new BigDecimal("60"),START.plusMonths(1),"nx_genesis_order.paid_at",0,null,START.plusMonths(1),START.plusMonths(1),null,Status.UNKNOWN);
        var account=new SupportFundAllocation.Account(1,"USDT","available");
        var net=replay("USDT",Map.of("d",deposit,"c",genesis),
            trace(new Event("d",Action.DEPOSIT,null,account,null,new BigDecimal("50"),null),START),
            trace(new Event("e",Action.EARNINGS,null,account,null,BigDecimal.TEN,null),START),
            trace(new Event("c",Action.CONSUME,account,null,null,new BigDecimal("60"),null),genesis.succeededAt()));
        var fixture=withReplay(net,deposit,genesis);assertMoney(fixture.read(context(Board.netContribution,"USDT",Scope.all,Set.of())),"10");
        var genesisOnly=withReplay(net,genesis);
        assertEquals(0L,genesisOnly.read(context(Board.firstPayment,"USDT",Scope.all,Set.of())).candidates().get(0).firstPayment().value());
    }
    @Test void missingGenesisSourcesDoNotInvalidateIndependentFirstPaymentOrCustomerSnapshot() {
        var fixture=new Fixture();var deposit=fact(1,101,Kind.DEPOSIT,"USDT",START.plusMonths(1));fixture.events(deposit);
        var base=snapshot(List.of(deposit),ready(1));
        when(fixture.finance.readHistory(any())).thenReturn(new SupportPaymentFacts.Snapshot(base.facts(),
            List.of(new SupportPaymentFacts.Issue(Source.GENESIS_PRIMARY,null,"SOURCE_READ_FAILED")),base.coverage(),base.businessZone(),base.evaluatedAt(),base.firstHistory(),base.customerCoverage()));
        assertEquals(Coverage.COMPLETE,fixture.read(Board.firstPayment).candidates().get(0).firstPayment().coverage());
        assertEquals(1L,fixture.read(Board.firstPayment).candidates().get(0).firstPayment().value());
        assertEquals(Coverage.COMPLETE,fixture.read(Board.customers).candidates().get(0).customers().coverage());
    }
    @Test void actualReplayPartialFormationDoesNotCountCreditedDepositAgain() {
        for(String currency:List.of("USDT","UVT")) {
            var deposit=money(fact(1,101,Kind.DEPOSIT,currency,START),"50");
            var purchase=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,START.plusMonths(1)),"60");
            var account=new SupportFundAllocation.Account(1,currency,"available");
            var net=replay(currency,Map.of("d",deposit,"c",purchase),
                trace(new Event("d",Action.DEPOSIT,null,account,null,new BigDecimal("50"),null),START),
                trace(new Event("e",Action.EARNINGS,null,account,null,BigDecimal.TEN,null),START),
                trace(new Event("c",Action.CONSUME,account,null,null,new BigDecimal("60"),null),purchase.succeededAt()));
            assertEquals(0,new BigDecimal("60").compareTo(currency.equals("USDT")?net.customers().get(0).usdt():net.customers().get(0).nex()));
            var f=withReplay(net,deposit,purchase);assertMoney(f.read(context(Board.netContribution,currency,Scope.all,Set.of())),"10");
            assertMoney(f.read(new Context(Board.netContribution,YearMonth.of(2026,9),YearMonth.of(2026,9),currency,Scope.all,Set.of(),SupportLeaderboardPolicy.DEFINITION,NOW.toInstant(ZoneOffset.UTC))),"50");
        }
    }
    @Test void actualReplayAllowsUnequalFormationLots() {assertReplayFormationLots("6");}
    @Test void actualReplayAllowsIdenticalFormationLotsWithoutInventingLegIdentity() {assertReplayFormationLots("5");}
    private static void assertReplayFormationLots(String first) {
        for(String currency:List.of("USDT","UVT")) {
            var net=earningsPurchase(currency,first,START.plusMonths(1));
            assertEquals(2,net.changes().size());
            if(first.equals("5"))assertEquals(net.changes().get(0),net.changes().get(1));
            var purchase=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,START.plusMonths(1)),"10");
            assertMoney(withReplay(net,purchase).read(context(Board.netContribution,currency,Scope.all,Set.of())),"10");
        }
    }
    @Test void actualReplayMultiLotRefundUsesSuccessMonthAndOriginalDisabledUnboundAgentGroup() {
        for(String currency:List.of("USDT","UVT"))for(String refund:List.of("8","10")) {
            var account=new SupportFundAllocation.Account(1,currency,"available");
            var purchase=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,START),"10");
            var net=replay(currency,Map.of("c",purchase),
                trace(new Event("e1",Action.EARNINGS,null,account,null,new BigDecimal("5"),null),START),
                trace(new Event("e2",Action.EARNINGS,null,account,null,new BigDecimal("5"),null),START),
                trace(new Event("c",Action.CONSUME,account,null,null,BigDecimal.TEN,null),START),
                trace(new Event("r",Action.REFUND,null,null,"c",new BigDecimal(refund),null),START.plusMonths(1)));
            if(refund.equals("10"))assertEquals(net.changes().get(2),net.changes().get(3));
            var f=withReplay(net,purchase);
            when(f.mapper.currentBindings()).thenReturn(List.of());when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",0),account(8,"乙",1)));
            when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,START.plusDays(1)),q(2,7,"DISABLED",START.plusDays(1),null),q(3,8,"ENABLED",START,null)));
            when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,200L,2L,START.plusDays(1),null)));
            assertMoney(f.read(context(Board.netContribution,currency,Scope.ownGroup,Set.of(100L))),"-"+refund);
            assertTrue(f.read(context(Board.netContribution,currency,Scope.ownGroup,Set.of(200L))).candidates().isEmpty());
            assertMoney(f.read(new Context(Board.netContribution,YearMonth.of(2026,9),YearMonth.of(2026,9),currency,Scope.ownGroup,Set.of(100L),SupportLeaderboardPolicy.DEFINITION,NOW.toInstant(ZoneOffset.UTC))),"10");
        }
    }
    @Test void actualReplayPartialFormationRefundReversesOnlyNewContribution() {
        for(String currency:List.of("USDT","UVT")) {
            var account=new SupportFundAllocation.Account(1,currency,"available");
            var deposit=money(fact(1,101,Kind.DEPOSIT,currency,START),"50");
            var purchase=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,START),"60");
            var net=replay(currency,Map.of("d",deposit,"c",purchase),
                trace(new Event("d",Action.DEPOSIT,null,account,null,new BigDecimal("50"),null),START),
                trace(new Event("e",Action.EARNINGS,null,account,null,BigDecimal.TEN,null),START),
                trace(new Event("c",Action.CONSUME,account,null,null,new BigDecimal("60"),null),START),
                trace(new Event("r",Action.REFUND,null,null,"c",new BigDecimal("60"),null),START.plusMonths(1)));
            assertMoney(withReplay(net,deposit,purchase).read(context(Board.netContribution,currency,Scope.all,Set.of())),"-10");
        }
    }
    @Test void actualReplaySameSecondConsumptionRefundAndNewConsumptionRetainSeparateOrigins() {
        for(String currency:List.of("USDT","UVT")) {
            var account=new SupportFundAllocation.Account(1,currency,"available");var at=START.plusMonths(1);
            var first=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,at),"10");
            var second=money(fact(1,202,Kind.DEVICE_PURCHASE,currency,at),"10");
            var net=replay(currency,Map.of("c",first,"c2",second),
                trace(new Event("e1",Action.EARNINGS,null,account,null,new BigDecimal("5"),null),START),
                trace(new Event("e2",Action.EARNINGS,null,account,null,new BigDecimal("5"),null),START),
                trace(new Event("c",Action.CONSUME,account,null,null,BigDecimal.TEN,null),at),
                trace(new Event("r",Action.REFUND,null,null,"c",BigDecimal.TEN,null),at),
                trace(new Event("c2",Action.CONSUME,account,null,null,BigDecimal.TEN,null),at));
            assertEquals(2,net.origins().size());assertEquals(6,net.changes().size());
            assertMoney(withReplay(net,first,second).read(context(Board.netContribution,currency,Scope.all,Set.of())),"10");
        }
    }
    @Test void actualReplayRepeatedCreditedConsumptionDoesNotRequireAnInventedFormation() {
        for(String currency:List.of("USDT","UVT")) {
            var account=new SupportFundAllocation.Account(1,currency,"available");var at=START.plusMonths(1);
            var deposit=money(fact(1,101,Kind.DEPOSIT,currency,START),"50");
            var first=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,at),"20");var second=money(fact(1,202,Kind.DEVICE_PURCHASE,currency,at),"20");
            var net=replay(currency,Map.of("d",deposit,"c",first,"c2",second),
                trace(new Event("d",Action.DEPOSIT,null,account,null,new BigDecimal("50"),null),START),
                trace(new Event("c",Action.CONSUME,account,null,null,new BigDecimal("20"),null),at),
                trace(new Event("r",Action.REFUND,null,null,"c",new BigDecimal("20"),null),at),
                trace(new Event("c2",Action.CONSUME,account,null,null,new BigDecimal("20"),null),at));
            assertEquals(1,net.origins().size());assertEquals(1,net.changes().size());
            assertMoney(withReplay(net,deposit,first,second).read(context(Board.netContribution,currency,Scope.all,Set.of())),"0");
        }
    }
    @Test void tamperedRealReplayCannotHideMissingOversizedOrMismatchedFormationWithAReadyLabel() {
        for(String currency:List.of("USDT","UVT")) {
            var at=START.plusMonths(1);var purchase=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,at),"10");
            var valid=earningsPurchase(currency,"5",at);var rows=valid.changes();
            var doubled=new ArrayList<>(rows);doubled.addAll(rows);
            // A matching forged lifetime total still cannot authorize formation above the payment fact.
            var bad=new ArrayList<NetSnapshot>();
            bad.add(new NetSnapshot(tail(currency,"20"),valid.origins(),doubled));
            bad.add(new NetSnapshot(tail(currency,"0"),valid.origins(),List.of()));
            bad.add(new NetSnapshot(tail(currency,"20"),valid.origins(),rows));
            for(int field=0;field<3;field++) {
                final int changed=field;
                bad.add(new NetSnapshot(valid.customers(),valid.origins(),rows.stream().map(c->new ContributionChange(c.contributionId(),c.originCustomerId(),c.currency(),c.amount(),
                    changed==0?c.succeededAt().plusSeconds(1):c.succeededAt(),c.businessZone(),changed==1?1:c.fractionalSecondDigits(),
                    changed==2?"other-operation":c.sourceReference())).toList()));
            }
            for(var net:bad) {
                var read=withReplay(net,purchase).read(context(Board.netContribution,currency,Scope.all,Set.of()));
                assertUnknownMoney(read,Reason.SOURCE_INCOMPLETE);
                assertTrue(SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates()).rows().stream().allMatch(r->r.rank()==null));
            }
            var wrongCurrency=rows.stream().map(c->new ContributionChange(c.contributionId(),c.originCustomerId(),currency.equals("USDT")?"UVT":"USDT",c.amount(),c.succeededAt(),c.businessZone(),c.fractionalSecondDigits(),c.sourceReference())).toList();
            assert503(()->withReplay(new NetSnapshot(valid.customers(),valid.origins(),wrongCurrency),purchase).read(context(Board.netContribution,currency,Scope.all,Set.of())));
            assert503(()->withReplay(new NetSnapshot(valid.customers(),List.of(valid.origins().get(0),valid.origins().get(0)),rows),purchase).read(context(Board.netContribution,currency,Scope.all,Set.of())));
            var wrongRoot=rows.stream().map(c->new ContributionChange(c.contributionId(),2,c.currency(),c.amount(),c.succeededAt(),c.businessZone(),c.fractionalSecondDigits(),c.sourceReference())).toList();
            assert503(()->withReplay(new NetSnapshot(valid.customers(),valid.origins(),wrongRoot),purchase).read(context(Board.netContribution,currency,Scope.all,Set.of())));
            var f=withReplay(valid,purchase);when(f.mapper.attributionProofs()).thenReturn(List.of());
            assert503(()->f.read(context(Board.netContribution,currency,Scope.all,Set.of())));
            when(f.mapper.attributions()).thenReturn(List.of());
            assertUnknownMoney(f.read(context(Board.netContribution,currency,Scope.all,Set.of())),Reason.ATTRIBUTION_UNKNOWN);
        }
    }
    @Test void tamperedRealDepositFormationMustEqualActualPaymentEvenWithAnAlignedTail() {
        for(String currency:List.of("USDT","UVT")) {
            var at=START.plusMonths(1);var deposit=money(fact(1,101,Kind.DEPOSIT,currency,at),"10");
            var account=new SupportFundAllocation.Account(1,currency,"available");
            var valid=replay(currency,Map.of("d",deposit),trace(new Event("d",Action.DEPOSIT,null,account,null,BigDecimal.TEN,null),at));
            for(String wrong:List.of("9","11")) {
                var c=valid.changes().get(0);var changed=new ContributionChange(c.contributionId(),c.originCustomerId(),c.currency(),new BigDecimal(wrong),c.succeededAt(),c.businessZone(),c.fractionalSecondDigits(),c.sourceReference());
                assertUnknownMoney(withReplay(new NetSnapshot(tail(currency,wrong),valid.origins(),List.of(changed)),deposit).read(context(Board.netContribution,currency,Scope.all,Set.of())),Reason.SOURCE_INCOMPLETE);
            }
        }
    }
    private static List<NetCustomer> tail(String currency,String amount) {
        return List.of(new NetCustomer(1,EvidenceState.READY,List.of(),currency.equals("USDT")?new BigDecimal(amount):BigDecimal.ZERO,currency.equals("UVT")?new BigDecimal(amount):BigDecimal.ZERO));
    }
    private record Trace(Event event,LocalDateTime at) {}
    private static Trace trace(Event event,LocalDateTime at) {return new Trace(event,at);}
    private static NetSnapshot replay(String currency,Map<String,Fact> payments,Trace... traces) {
        var allocation=new SupportFundAllocation();var operations=new ArrayList<SupportFundReplay.Operation>();
        long revision=0;
        for(var trace:traces) {
            var before=cash(allocation);allocation.apply(trace.event());var after=cash(allocation);long id=++revision;
            operations.add(new SupportFundReplay.Operation(id,"op"+id,List.of(1L),List.of(trace.event()),
                List.of(new SupportFundReplay.Point("inc",id,1,before,after,List.of(trace.event().id()))),
                new SupportFundReplay.Stamp("source"+id,trace.at(),"Asia/Shanghai",0)));
        }
        var factIds=new HashMap<String,String>();payments.forEach((event,fact)->factIds.put(event,fact.factId()));
        var result=new SupportFundReplay().replay(Set.of(1L),operations,Map.of(1L,cash(allocation)),factIds,List.of());
        assertEquals(SupportFundReplay.State.READY,result.state(),()->result.toString());
        var totals=result.totals().get(1L);
        return new NetSnapshot(List.of(new NetCustomer(1,EvidenceState.READY,List.of(),totals.get("USDT"),totals.get("UVT"))),
            result.origins().values().stream().map(o->new ContributionOrigin(o.contributionId(),o.customerId(),o.paymentFactId(),o.formedAt().succeededAt(),o.formedAt().businessZone(),o.formedAt().fractionalSecondDigits(),o.formedAt().sourceReference())).toList(),
            result.changes().stream().map(c->new ContributionChange(c.contributionId(),c.originCustomerId(),c.currency(),c.amount(),c.occurredAt().succeededAt(),c.occurredAt().businessZone(),c.occurredAt().fractionalSecondDigits(),c.occurredAt().sourceReference())).toList());
    }
    private static SupportFundReplay.Cash cash(SupportFundAllocation allocation) {
        return new SupportFundReplay.Cash(allocation.balance(new SupportFundAllocation.Account(1,"USDT","available")),
            allocation.balance(new SupportFundAllocation.Account(1,"UVT","available")),
            allocation.pendingWithdrawal(new SupportFundAllocation.Account(1,"USDT","available")),BigDecimal.ZERO);
    }
    private static NetSnapshot earningsPurchase(String currency,String first,LocalDateTime at) {
        var account=new SupportFundAllocation.Account(1,currency,"available");
        var purchase=money(fact(1,201,Kind.DEVICE_PURCHASE,currency,at),"10");
        return replay(currency,Map.of("c",purchase),
            trace(new Event("e1",Action.EARNINGS,null,account,null,new BigDecimal(first),null),START),
            trace(new Event("e2",Action.EARNINGS,null,account,null,BigDecimal.TEN.subtract(new BigDecimal(first)),null),START),
            trace(new Event("c",Action.CONSUME,account,null,null,BigDecimal.TEN,null),at));
    }
    private static Fixture withReplay(NetSnapshot net,Fact... facts) {
        var fixture=new Fixture();fixture.events(facts);fixture.financial(List.of(facts),ready(1));
        doReturn(net).when(fixture.funds).readNetContributions(any());return fixture;
    }
    @Test void netRefundBelongsToItsSuccessMonthInsteadOfRewritingThePurchaseMonth() {
        var f=new Fixture();var original=money(fact(1,201,Kind.DEVICE_PURCHASE,"USDT",LocalDateTime.parse("2026-09-01T10:00:00")),"80");
        f.events(original);f.financial(List.of(original),ready(1));
        f.net(original,change(original,"80",original.succeededAt()),change(original,"-80",LocalDateTime.parse("2026-10-01T10:00:00")));
        assertMoney(f.read(context(Board.netContribution,"USDT",Scope.all,Set.of())),"-80");
        var september=YearMonth.of(2026,9);
        assertMoney(f.read(new Context(Board.netContribution,september,september,"USDT",Scope.all,Set.of(),SupportLeaderboardPolicy.DEFINITION,NOW.toInstant(ZoneOffset.UTC))),"80");
    }
    private static final LocalDateTime NOW=LocalDateTime.parse("2026-10-09T02:00:00");
    private static final LocalDateTime START=LocalDateTime.parse("2026-09-01T00:00:00");
    private static final YearMonth OCT=YearMonth.of(2026,10);
    private static final class Fixture {
        final SupportLeaderboardMapper mapper=mock(SupportLeaderboardMapper.class);
        final FinanceSupportPaymentFactsFacade finance=mock(FinanceSupportPaymentFactsFacade.class);
        final SupportFundsReadFacade funds=mock(SupportFundsReadFacade.class);
        final SupportLeaderboardSourceService service=new SupportLeaderboardSourceService(mapper,finance,funds);
        Fixture() {
            when(funds.readNetContributions(any())).thenAnswer(i->{Collection<Long> ids=i.getArgument(0);
                return new NetSnapshot(ids.stream().map(id->new NetCustomer(id,EvidenceState.UNKNOWN,List.of("SOURCE_MISSING"),null,null)).toList(),List.of(),List.of());});
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
        void net(Fact original,ContributionChange... changes) {
            BigDecimal usdt=BigDecimal.ZERO,nex=BigDecimal.ZERO;
            for(var change:changes)if(change.currency().equals("USDT"))usdt=usdt.add(change.amount());else nex=nex.add(change.amount());
            doReturn(new NetSnapshot(List.of(new NetCustomer(original.customerId(),EvidenceState.READY,List.of(),usdt,nex)),
                List.of(origin(original)),List.of(changes))).when(funds).readNetContributions(any());
        }
        void financial(List<Fact> facts,List<SupportPaymentFacts.FirstHistory> history) {
            doReturn(snapshot(facts,history)).when(finance).readHistory(any());
        }
        void events(Fact... facts) {
            when(mapper.attributions()).thenReturn(Arrays.stream(facts).map(SupportLeaderboardSourceServiceTest::attribution).toList());
            when(mapper.attributionProofs()).thenReturn(Arrays.stream(facts).map(f -> netProof(f,attribution(f))).toList());
        }
        SupportLeaderboardSourceService.Read read(Board board) {return read(context(board,"UVT",Scope.all,Set.of()));}
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
        var r=f.read(context(Board.firstPayment,"UVT",Scope.ownGroup,Set.of(200L)));
        assertEquals("当前二组",r.candidates().get(0).groupName());assertEquals(1L,r.candidates().get(0).firstPayment().value());
        assertTrue(f.read(context(Board.customers,"UVT",Scope.ownGroup,Set.of(100L))).candidates().isEmpty());
    }
    @Test void ownGroupExcludesExplicitlyUngroupedMemberWhileAllKeepsCandidate() {
        var f=new Fixture();when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,null,1L,START,null)));
        assertTrue(f.read(context(Board.customers,"UVT",Scope.ownGroup,Set.of(100L))).candidates().isEmpty());
        var all=f.read(Board.customers);assertEquals(List.of(7L),all.candidates().stream().map(Candidate::agentId).toList());
        assertEquals("待分组",all.candidates().get(0).groupName());assertEquals(1L,all.candidates().get(0).customers().value());
        assertEquals(Coverage.COMPLETE,all.candidateCoverage());
    }
    @Test void managedGroupsExcludeUngroupedAndMissingMemberWhileAllKeepsCandidate() {
        var f=new Fixture();when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,null,1L,START,null)));
        assertTrue(f.read(context(Board.customers,"UVT",Scope.managedGroups,Set.of(100L,200L))).candidates().isEmpty());
        var all=f.read(Board.customers);assertEquals(List.of(7L),all.candidates().stream().map(Candidate::agentId).toList());
        assertEquals("待分组",all.candidates().get(0).groupName());assertEquals(1L,all.candidates().get(0).customers().value());
        assertEquals(Coverage.COMPLETE,all.candidateCoverage());
        when(f.mapper.memberships()).thenReturn(List.of());
        assertTrue(f.read(context(Board.customers,"UVT",Scope.managedGroups,Set.of(100L,200L))).candidates().isEmpty());
        assertEquals(List.of(7L),f.read(Board.customers).candidates().stream().map(Candidate::agentId).toList());
    }
    @Test void missingNicknameUsesNeutralStablePublicKeyAndAvatarOnlyUsesControlledReference() {
        var f=new Fixture();when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,null,1,0,1L,1,0,1L,"SUPPORT",
            "private-storage-id",9L,"ATTACHED",7L,1,1)));
        var c=f.read(Board.customers).candidates().get(0);assertEquals("专属客服 #7",c.name());
        assertEquals("/api/admin/content/support-workbench/leaderboard/7/avatar?assetVersion=9",c.avatarUrl());
        when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,"甲",1,0,1L,1,0,1L,"SUPPORT","private-storage-id",9L,"ATTACHED",8L,1,1)));
        assertNull(f.read(Board.customers).candidates().get(0).avatarUrl());
    }
    @Test void lifetimeFirstUsesSharedCanonicalOrderingAcrossCurrenciesBeforeMonthlyFilter() {
        var f=new Fixture();var old=fact(1,2,Kind.DEPOSIT,"USDT",LocalDateTime.parse("2026-09-30T23:00:00"));
        var later=fact(1,3,Kind.DEVICE_PURCHASE,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(old,later);f.financial(List.of(later,old),ready(1));
        var r=f.read(Board.firstPayment);assertEquals(0L,r.candidates().get(0).firstPayment().value());assertEquals(Coverage.COMPLETE,r.candidates().get(0).firstPayment().coverage());
        var same=LocalDateTime.parse("2026-10-01T10:00:00");var d2=fact(1,2,Kind.DEPOSIT,"USDT",same);var d11=fact(1,11,Kind.DEPOSIT,"UVT",same);
        var purchase=fact(1,1,Kind.DEVICE_PURCHASE,"UVT",same);
        assertEquals(d11,SupportAnalyticsService.selectFirstFacts(List.of(d2,purchase,d11)).get(1L));
        assertEquals(d11,SupportAnalyticsService.selectFirstFacts(List.of(d11,d2,purchase)).get(1L));
    }
    @Test void confirmedFirstCountIsIndependentFromUnknownNetContribution() {
        for(String currency:List.of("UVT","USDT")) {
            var f=new Fixture();var fact=fact(1,2,Kind.DEPOSIT,"USDT",LocalDateTime.parse("2026-10-01T10:00:00"));
            when(f.mapper.qualificationCutoverUtc()).thenReturn(START);
            f.events(fact);f.financial(List.of(fact),ready(1));var r=f.read(context(Board.firstPayment,currency,Scope.all,Set.of()));
            var c=r.candidates().get(0);assertEquals(1L,c.firstPayment().value());assertEquals(Coverage.COMPLETE,c.firstPayment().coverage());
            assertNull(c.amount().value());
            assertEquals(Coverage.UNKNOWN,c.amount().coverage());assertEquals(currency,c.amount().currency());
            assertEquals(Reason.SOURCE_INCOMPLETE,c.amount().reason());assertEquals(Coverage.COMPLETE,r.candidateCoverage());
        }
    }
    @Test void unknownFirstAndMoneyStayNullOnTheSingleAmountContract() {
        var f=new Fixture();for(Board board:List.of(Board.netContribution,Board.firstPayment,Board.customers)) {
            var row=f.read(board).candidates().get(0);assertNull(row.firstPayment().value());assertNull(row.amount().value());
            assertEquals(AmountKind.NET_CONTRIBUTION,row.amount().kind());assertEquals(Reason.SOURCE_INCOMPLETE,row.amount().reason());
        }
    }
    @Test void missingOrRejectedCanonicalAttributionDoesNotAwardConfirmedFirst() {
        var f=new Fixture();var fact=fact(1,2,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));f.financial(List.of(fact),ready(1));
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
        var f=new Fixture();when(f.mapper.accounts()).thenReturn(List.of(new Account(7L,"甲",1,0,1L,null,null,null,null,null,null,null,null,1,1)));
        assert503(() -> f.read(Board.customers));when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",1)));
        when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,START)));when(f.mapper.currentBindings()).thenReturn(List.of());
        assertTrue(f.read(Board.firstPayment).candidates().isEmpty());assertTrue(f.read(Board.customers).candidates().isEmpty());
    }
    @Test void fingerprintTracksQualificationVersionAndFullCanonicalSourceVersionWithoutMetricChange() {
        var f=new Fixture();var first=f.read(Board.customers);when(f.mapper.qualifications()).thenReturn(List.of(new QualificationInterval(1L,7L,"ENABLED",2L,START,null)));
        assertNotEquals(first.sourceVersion(),f.read(Board.customers).sourceVersion());
        var fact=fact(1,2,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(fact);f.financial(List.of(fact),ready(1));
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
        var fact=fact(1,2,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));f.events(fact);f.financial(List.of(fact),ready(1));
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
        var f=new Fixture();TransactionSynchronizationManager.clear();assertThrows(IllegalStateException.class,() -> f.service.readForAuthorizedLeaderboard(context(Board.customers,"UVT",Scope.all,Set.of())));
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        try {assertThrows(IllegalStateException.class,() -> f.service.readForAuthorizedLeaderboard(context(Board.customers,"UVT",Scope.all,Set.of())));} finally {TransactionSynchronizationManager.clear();}
        Transactional annotation=SupportLeaderboardSourceService.class.getMethod("readForAuthorizedLeaderboard",Context.class).getAnnotation(Transactional.class);
        assertEquals(Propagation.MANDATORY,annotation.propagation());assertTrue(annotation.readOnly());
        assertEquals(409,assertThrows(BizException.class,() -> f.read(context(Board.customers,"UVT",Scope.ownGroup,Set.of(999L)))).getCode());
    }
    @Test void oneCapturedTupleReusesTheSoleProjectionAndCannotCrossTransactionBoundaries() {
        var f=new Fixture();TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        TransactionSynchronizationManager.initSynchronization();
        var material=f.service.captureForSampling();
        try {
            var all=f.service.projectCaptured(material,context(Board.customers,"USDT",Scope.all,Set.of()));
            var own=f.service.projectCaptured(material,context(Board.customers,"UVT",Scope.ownGroup,Set.of(100L)));
            assertEquals(1L,all.candidates().get(0).customers().value());assertEquals(1L,own.candidates().get(0).customers().value());
            assertEquals(all.context().evaluatedAt(),own.context().evaluatedAt());
            verify(f.finance,times(1)).readHistory(List.of(1L));verify(f.mapper,times(1)).accounts();
            TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.initSynchronization();
            assertThrows(IllegalStateException.class,()->f.service.projectCaptured(material,context(Board.customers,"UVT",Scope.all,Set.of())));
        }finally {TransactionSynchronizationManager.clear();}
    }
    @Test void samplingCaptureRequiresActiveRrSynchronizationWithoutWeakeningOriginalSingleRead() {
        var f=new Fixture();assertThrows(IllegalStateException.class,f.service::captureForSampling);
        assertEquals(1,f.read(Board.customers).candidates().size());
    }
    @Test void depositAndBalanceConsumptionAreNotDoubleCountedAndLifetimeTotalIsNotMonthlyMoney() {
        var f=new Fixture();var deposit=money(fact(1,1,Kind.DEPOSIT,"USDT",START),"1000");
        var purchase=money(fact(1,2,Kind.DEVICE_PURCHASE,"USDT",LocalDateTime.parse("2026-10-01T10:00:00")),"600");
        f.events(deposit,purchase);f.financial(List.of(deposit,purchase),ready(1));
        f.net(deposit,change(deposit,"1000",deposit.succeededAt()),change(deposit,"-400",NOW));
        assertMoney(f.read(context(Board.netContribution,"USDT",Scope.all,Set.of())),"-400");
        assertEquals(new BigDecimal("600"),f.read(Board.customers).evidence().net().customers().get(0).usdt());
        assertMoney(f.read(new Context(Board.netContribution,YearMonth.of(2026,9),YearMonth.of(2026,9),"USDT",Scope.all,Set.of(),"v3",NOW.toInstant(ZoneOffset.UTC))),"1000");
    }
    @Test void originalAgentAndGroupKeepCrossMonthReductionAfterTransferUnbindAndDisable() {
        var f=new Fixture();var deposit=money(fact(1,1,Kind.DEPOSIT,"USDT",START),"100");
        f.events(deposit);f.financial(List.of(deposit),ready(1));
        f.net(deposit,change(deposit,"100",START),change(deposit,"-35",LocalDateTime.parse("2026-10-01T10:00:00")));
        when(f.mapper.currentBindings()).thenReturn(List.of());when(f.mapper.accounts()).thenReturn(List.of(account(7,"甲",0),account(8,"乙",1)));
        when(f.mapper.qualifications()).thenReturn(List.of(q(1,7,"ENABLED",START,START.plusDays(1)),q(2,7,"DISABLED",START.plusDays(1),null),q(3,8,"ENABLED",START,null)));
        when(f.mapper.memberships()).thenReturn(List.of(new MemberInterval(1L,7L,200L,2L,START.plusDays(1),null)));
        var oldGroup=f.read(context(Board.netContribution,"USDT",Scope.ownGroup,Set.of(100L)));
        assertEquals(List.of(7L),oldGroup.candidates().stream().map(Candidate::agentId).toList());assertMoney(oldGroup,"-35");
        var newGroup=f.read(context(Board.netContribution,"USDT",Scope.ownGroup,Set.of(200L)));
        assertTrue(newGroup.candidates().isEmpty());
        var all=f.read(context(Board.netContribution,"USDT",Scope.all,Set.of()));
        assertEquals(0,new BigDecimal("-35").compareTo(all.candidates().stream().filter(c->c.agentId()==7).findFirst().orElseThrow().amount().value()));
    }
    @Test void oneNetAmountIsSharedAcrossAllThreeBoardsAndCurrenciesNeverConvert() {
        var f=new Fixture();var deposit=money(fact(1,1,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00")),"123.123456");
        f.events(deposit);f.financial(List.of(deposit),ready(1));f.net(deposit,change(deposit,"123.123456",deposit.succeededAt()));
        for(Board board:List.of(Board.netContribution,Board.firstPayment,Board.customers)) {
            assertMoney(f.read(context(board,"UVT",Scope.all,Set.of())),"123.123456");
            assertMoney(f.read(context(board,"USDT",Scope.all,Set.of())),"0");
            assertEquals(AmountKind.NET_CONTRIBUTION,f.read(board).candidates().get(0).amount().kind());
        }
        verify(f.funds,atLeastOnce()).readNetContributions(List.of(1L));
    }
    @Test void knownEmptyRequiresCompleteNetChainAndMissingCustomerNeverFabricatesZero() {
        var f=new Fixture();doReturn(new NetSnapshot(List.of(new NetCustomer(1,EvidenceState.READY,List.of(),BigDecimal.ZERO,BigDecimal.ZERO)),List.of(),List.of())).when(f.funds).readNetContributions(any());
        assertMoney(f.read(Board.netContribution),"0");
        doReturn(new NetSnapshot(List.of(),List.of(),List.of())).when(f.funds).readNetContributions(any());
        var missing=f.read(Board.netContribution);assertUnknownMoney(missing,Reason.SOURCE_INCOMPLETE);
        assertTrue(SupportLeaderboard.calculate(missing.context(),missing.sourceVersion(),missing.candidateCoverage(),missing.candidates()).rows().stream().allMatch(r->r.rank()==null));
        when(f.mapper.productionCustomers()).thenReturn(List.of());when(f.mapper.currentBindings()).thenReturn(List.of());
        assertMoney(f.read(Board.netContribution),"0");verify(f.funds).readNetContributions(List.of());
    }
    @Test void unknownComponentAllowsOnlyReadySubtotalWithoutRankAndKeepsFirstAndCustomersIndependent() {
        var f=new Fixture();var deposit=fact(1,1,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));
        f.events(deposit);f.financial(List.of(deposit),ready(1));f.net(deposit,change(deposit,"1.25",deposit.succeededAt()));
        when(f.mapper.productionCustomers()).thenReturn(List.of(1L,2L));
        var net=f.funds.readNetContributions(List.of(1L));
        doReturn(new NetSnapshot(List.of(net.customers().get(0),new NetCustomer(2,EvidenceState.UNKNOWN,List.of("NEW_ACCOUNT_BIRTH_NOT_PROVEN"),null,null)),net.origins(),net.changes())).when(f.funds).readNetContributions(any());
        var snapshot=snapshot(List.of(deposit),List.of(new SupportPaymentFacts.FirstHistory(1,Status.READY,List.of()),new SupportPaymentFacts.FirstHistory(2,Status.READY,List.of())));
        when(f.finance.readHistory(any())).thenReturn(snapshot);
        var first=f.read(Board.firstPayment);assertEquals(1L,first.candidates().get(0).firstPayment().value());assertEquals(Coverage.COMPLETE,first.candidates().get(0).firstPayment().coverage());
        assertEquals(new BigDecimal("1.25"),first.candidates().get(0).amount().value());assertEquals(Coverage.PARTIAL,first.candidates().get(0).amount().coverage());
        assertEquals(Reason.SOURCE_INCOMPLETE,first.candidates().get(0).amount().reason());var customers=f.read(Board.customers);
        assertEquals(State.COMPLETE,SupportLeaderboard.calculate(customers.context(),customers.sourceVersion(),customers.candidateCoverage(),customers.candidates()).state());
        assertEquals(1L,customers.candidates().get(0).customers().value());
    }
    @Test void signedReadySubtotalSurvivesOtherUnknownRootsButNeverClaimsACompleteRank() {
        var f=new Fixture();var deposit=money(fact(1,1,Kind.DEPOSIT,"USDT",START),"100");
        f.events(deposit);f.financial(List.of(deposit),ready(1));f.net(deposit,change(deposit,"100",START),change(deposit,"-35",LocalDateTime.parse("2026-10-01T10:00:00")));
        var complete=f.funds.readNetContributions(List.of(1L));when(f.mapper.productionCustomers()).thenReturn(List.of(1L,2L));
        doReturn(new NetSnapshot(List.of(complete.customers().get(0),new NetCustomer(2,EvidenceState.UNKNOWN,List.of("SOURCE_MISSING"),null,null)),complete.origins(),complete.changes())).when(f.funds).readNetContributions(any());
        var history=snapshot(List.of(deposit),List.of(new SupportPaymentFacts.FirstHistory(1,Status.READY,List.of()),new SupportPaymentFacts.FirstHistory(2,Status.UNKNOWN,List.of("NEW_ACCOUNT_BIRTH_NOT_PROVEN"))));
        when(f.finance.readHistory(any())).thenReturn(history);
        var read=f.read(context(Board.netContribution,"USDT",Scope.all,Set.of()));
        assertEquals(new BigDecimal("-35"),read.candidates().get(0).amount().value());assertEquals(Coverage.PARTIAL,read.candidates().get(0).amount().coverage());
        var projected=SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates());
        assertTrue(projected.rows().stream().allMatch(r->r.rank()==null));assertEquals(State.PROVISIONAL,projected.state());
    }
    @Test void attributionProofMissingConflictingOrUnknownNeverAwardsNetAmount() {
        var f=new Fixture();var deposit=fact(1,1,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));
        f.events(deposit);f.financial(List.of(deposit),ready(1));f.net(deposit,change(deposit,"1.25",deposit.succeededAt()));
        assertMoney(f.read(Board.netContribution),"1.25");
        var saved=netProof(deposit,attribution(deposit));
        when(f.mapper.attributionProofs()).thenReturn(List.of(new AttributionProof(saved.factId(),saved.sourcePartition(),saved.captureDbUtc(),saved.sourceFactJson(),saved.attributionEvidenceJson().replace("\"id\":7","\"id\":8"))));
        assertUnknownMoney(f.read(Board.netContribution),Reason.ATTRIBUTION_UNKNOWN);
        when(f.mapper.attributionProofs()).thenReturn(List.of(proof(deposit.factId(),"{}")));
        assertUnknownMoney(f.read(Board.netContribution),Reason.ATTRIBUTION_UNKNOWN);
        when(f.mapper.attributions()).thenReturn(List.of());when(f.mapper.attributionProofs()).thenReturn(List.of());
        assertUnknownMoney(f.read(Board.netContribution),Reason.ATTRIBUTION_UNKNOWN);
    }
    @Test void legacyImmutableNexAttributionMatchesMigratedUvtFactWithoutAcceptingWrongCurrencyOrAmount() {
        var f=new Fixture();var deposit=fact(1,1,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));
        f.events(deposit);f.financial(List.of(deposit),ready(1));f.net(deposit,change(deposit,"1.25",deposit.succeededAt()));
        var saved=netProof(deposit,attribution(deposit));
        String original=saved.sourceFactJson().replace("\"currency\":\"UVT\"","\"currency\":\"NEX\"");
        when(f.mapper.attributionProofs()).thenReturn(List.of(new AttributionProof(saved.factId(),saved.sourcePartition(),saved.captureDbUtc(),original,saved.attributionEvidenceJson())));
        assertMoney(f.read(Board.netContribution),"1.25");
        when(f.mapper.attributionProofs()).thenReturn(List.of(new AttributionProof(saved.factId(),saved.sourcePartition(),saved.captureDbUtc(),original.replace("NEX","USDT"),saved.attributionEvidenceJson())));
        assertUnknownMoney(f.read(Board.netContribution),Reason.ATTRIBUTION_UNKNOWN);
    }
    @Test void unassignedOwnerIsExcludedWithoutSpreadingButUnknownIsNotZero() {
        var f=new Fixture();var deposit=fact(1,1,Kind.DEPOSIT,"UVT",LocalDateTime.parse("2026-10-01T10:00:00"));
        f.financial(List.of(deposit),ready(1));f.net(deposit,change(deposit,"1.25",deposit.succeededAt()));
        for(String status:List.of("UNASSIGNED","UNKNOWN")) {
            var row=owner(deposit,null,null,status);when(f.mapper.attributions()).thenReturn(List.of(row));when(f.mapper.attributionProofs()).thenReturn(List.of(netProof(deposit,row)));
            if(status.equals("UNASSIGNED"))assertMoney(f.read(Board.netContribution),"0");else assertUnknownMoney(f.read(Board.netContribution),Reason.ATTRIBUTION_UNKNOWN);
        }
    }
    @Test void netSuccessMonthIsHalfOpenAndCutoffInclusiveAndChangesMustResolveOrigin() {
        var f=new Fixture();var deposit=money(fact(1,1,Kind.DEPOSIT,"UVT",START),"10");f.events(deposit);f.financial(List.of(deposit),ready(1));
        var cutoff=NOW.atOffset(ZoneOffset.UTC).atZoneSameInstant(SupportLeaderboard.BUSINESS_ZONE).toLocalDateTime();
        f.net(deposit,change(deposit,"10",START),change(deposit,"-1",LocalDateTime.parse("2026-10-01T00:00:00")),change(deposit,"-2",cutoff),change(deposit,"-1",cutoff.plusSeconds(1)),change(deposit,"-1",LocalDateTime.parse("2026-11-01T00:00:00")));
        assertMoney(f.read(Board.netContribution),"-3");
        var valid=f.funds.readNetContributions(List.of(1L));doReturn(new NetSnapshot(valid.customers(),List.of(),valid.changes())).when(f.funds).readNetContributions(any());
        assert503(()->f.read(Board.netContribution));
    }
    @Test void missingFormationOrTailDisagreementDoesNotTreatReadyLabelAsCompleteHistory() {
        var f=new Fixture();var deposit=fact(1,1,Kind.DEPOSIT,"UVT",START);f.events(deposit);f.financial(List.of(deposit),ready(1));
        doReturn(new NetSnapshot(List.of(new NetCustomer(1,EvidenceState.READY,List.of(),BigDecimal.ZERO,new BigDecimal("1.25"))),List.of(origin(deposit)),List.of())).when(f.funds).readNetContributions(any());
        assertUnknownMoney(f.read(Board.netContribution),Reason.SOURCE_INCOMPLETE);
        doReturn(null).when(f.funds).readNetContributions(any());assert503(()->f.read(Board.netContribution));
        doThrow(new DataRetrievalFailureException("private trace")).when(f.funds).readNetContributions(any());assert503(()->f.read(Board.netContribution));
    }
    @Test void currencyCannotBeGuessedFromAnotherOriginAndFormationCannotBeCountedTwice() {
        var f=new Fixture();var deposit=money(fact(1,1,Kind.DEPOSIT,"USDT",START),"10");f.events(deposit);f.financial(List.of(deposit),ready(1));
        var formation=change(deposit,"10",START);
        var wrongCurrency=new ContributionChange(origin(deposit).contributionId(),1,"UVT",BigDecimal.ONE,START.plusDays(1),"Asia/Shanghai",0,"wrong-currency");
        f.net(deposit,formation,wrongCurrency);assert503(()->f.read(Board.netContribution));
        var duplicateFormation=new ContributionChange(formation.contributionId(),1,"USDT",formation.amount(),START,"Asia/Shanghai",0,"duplicate-formation");
        f.net(deposit,formation,duplicateFormation);assertUnknownMoney(f.read(Board.netContribution),Reason.SOURCE_INCOMPLETE);
    }
    private static ContributionOrigin origin(Fact fact) {return new ContributionOrigin("contribution:"+fact.factId(),fact.customerId(),fact.factId(),fact.succeededAt(),"Asia/Shanghai",fact.fractionalSecondDigits(),fact.factId());}
    private static ContributionChange change(Fact fact,String amount,LocalDateTime at) {return new ContributionChange(origin(fact).contributionId(),fact.customerId(),fact.currency(),new BigDecimal(amount),at,"Asia/Shanghai",0,at.equals(fact.succeededAt())?fact.factId():fact.factId()+":"+at);}
    private static AttributionProof netProof(Fact fact,AttributionRow row) {
        try {
            var source=new LinkedHashMap<String,Object>();source.put("factId",fact.factId());source.put("customerId",fact.customerId());source.put("ledgerId",fact.ledgerId());
            source.put("kind",fact.kind().name());source.put("source",fact.source().name());source.put("currency",fact.currency());source.put("amount",fact.amount());
            source.put("succeededAt",fact.succeededAt().toString());source.put("businessZone","Asia/Shanghai");source.put("fractionalSecondDigits",fact.fractionalSecondDigits());
            var evidence=new LinkedHashMap<String,Object>();evidence.put("schemaVersion",row.captureSchemaVersion());evidence.put("captureMode",row.captureMode());
            evidence.put("captureZone","UTC");evidence.put("captureDbUtc",NOW.toString());evidence.put("sourcePartition",null);
            evidence.put("agent",layer(row.agentStatus(),row.agentAdminId()));evidence.put("group",layer(row.groupStatus(),row.groupId()));evidence.put("owner",layer(row.ownerStatus(),row.ownerAdminId()));
            var json=new ObjectMapper();return new AttributionProof(fact.factId(),null,NOW,json.writeValueAsString(source),json.writeValueAsString(evidence));
        }catch(java.io.IOException ex){throw new AssertionError(ex);}
    }
    private static Map<String,Object> layer(String status,Long id) {var layer=new LinkedHashMap<String,Object>();layer.put("status",status);layer.put("id",id);return layer;}
    private static void assertMoney(SupportLeaderboardSourceService.Read r,String expected) {
        var a=r.candidates().get(0).amount();assertNotNull(a.value());assertEquals(0,new BigDecimal(expected).compareTo(a.value()));
        assertEquals(Coverage.COMPLETE,a.coverage());assertEquals(Reason.NONE,a.reason());
    }
    private static void assertUnknownMoney(SupportLeaderboardSourceService.Read r,Reason expected) {
        var a=r.candidates().get(0).amount();assertNull(a.value());assertEquals(Coverage.UNKNOWN,a.coverage());assertEquals(expected,a.reason());
    }
    private static Fact money(Fact f,String amount) {
        return new Fact(f.factId(),f.kind(),f.source(),f.sourceIds(),f.customerId(),f.ledgerId(),f.sourceBusinessId(),f.orderNo(),f.orderType(),f.originalFactId(),
            f.currency(),new BigDecimal(amount),f.succeededAt(),f.successTimeField(),f.fractionalSecondDigits(),f.providerPaidAt(),f.ledgerRecordedAt(),f.sourceConfirmationAt(),f.sourceVersion(),f.historicalEnvironmentStatus());
    }
    private static AttributionRow owner(Fact f,Long agent,Long group,String status) {
        return new AttributionRow(f.factId(),f.customerId(),f.kind().name(),f.source().name(),f.ledgerId(),f.sourceBusinessId(),f.orderNo(),f.orderType(),f.originalFactId(),
            f.currency(),f.amount(),f.succeededAt(),"Asia/Shanghai",f.successTimeField(),f.fractionalSecondDigits(),"NEW_SUCCESS","support-payment-attribution-v1",agent,group,group==null?null:8L,status,status,status);
    }
    private static void assert503(org.junit.jupiter.api.function.Executable action) {assertEquals(503,assertThrows(BizException.class,action).getCode());}
    private static Account account(long id,String nickname,int enabled) {return new Account(id,nickname,enabled,0,1L,enabled,0,1L,"SUPPORT",null,null,null,null,1,id==7?1:0);}
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
