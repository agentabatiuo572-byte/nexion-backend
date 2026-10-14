package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportFundReplayTest {
    private static final Stamp OCT=new Stamp("deposit",LocalDateTime.of(2026,10,1,0,0),"Asia/Taipei",0);
    private static final Stamp NOV=new Stamp("withdrawal",LocalDateTime.of(2026,11,1,0,0),"Asia/Taipei",0);
    private final SupportFundReplay replay=new SupportFundReplay();
    private static BigDecimal n(int value){return BigDecimal.valueOf(value).setScale(6);}
    private static Cash cash(int available,int nex,int pending){return new Cash(n(available),n(nex),n(pending),n(0));}
    private static Account a(long id,String currency){return new Account(id,currency,"available");}
    private static Event credit(String id,long customer,int amount){return new Event(id,Action.DEPOSIT,null,a(customer,"USDT"),null,n(amount),null);}
    private static Event spend(String id,int amount){return new Event(id,Action.CONSUME,a(1,"USDT"),null,null,n(amount),null);}
    private static Operation op(long id,List<Event> events,Cash before,Cash after){return new Operation(id,"op"+id,List.of(1L),events,
        List.of(new Point("a",id,1,before,after,events.stream().map(Event::id).toList())),id==4?NOV:OCT);}
    private Result run(List<Operation> ops,Cash tail){return replay.replay(Set.of(1L),ops,Map.of(1L,tail),Map.of("d","DEPOSIT:1","c","PURCHASE:1"),List.of());}
    private void unknown(Result result,Gap gap){assertEquals(State.UNKNOWN,result.state());assertTrue(result.reasons().contains(gap));assertTrue(result.totals().isEmpty());assertTrue(result.changes().isEmpty());assertTrue(result.origins().isEmpty());}
    @Test void depositConsumptionAndGrossWithdrawalDoNotDoubleCountAndRetainOriginalMonth(){
        var reserve=new Event("r",Action.RESERVE,a(1,"USDT"),null,null,n(400),null);
        var confirm=new Event("w",Action.CONFIRM,null,null,"r",null,null);
        var result=run(List.of(op(4,List.of(confirm),cash(0,0,400),cash(0,0,0)),op(2,List.of(spend("c",600)),cash(1000,0,0),cash(400,0,0)),
            op(1,List.of(credit("d",1,1000)),cash(0,0,0),cash(1000,0,0)),op(3,List.of(reserve),cash(400,0,0),cash(0,0,400))),cash(0,0,0));
        assertEquals(State.READY,result.state());assertEquals(n(600),result.totals().get(1L).get("USDT"));
        assertEquals("d",result.changes().get(1).contributionId());assertEquals(NOV,result.changes().get(1).occurredAt());assertEquals(OCT,result.origins().get("d").formedAt());
    }
    @Test void laterReleasedEarningsWithdrawWithoutReducingDeposit(){
        var earn=new Event("e",Action.EARNINGS,null,a(1,"USDT"),null,n(200),null);
        var reserve=new Event("r",Action.RESERVE,a(1,"USDT"),null,null,n(200),null);
        var confirm=new Event("w",Action.CONFIRM,null,null,"r",null,null);
        var result=run(List.of(op(1,List.of(credit("d",1,1000)),cash(0,0,0),cash(1000,0,0)),op(2,List.of(earn),cash(1000,0,0),cash(1200,0,0)),
            op(3,List.of(reserve),cash(1200,0,0),cash(1000,0,200)),op(4,List.of(confirm),cash(1000,0,200),cash(1000,0,0))),cash(1000,0,0));
        assertEquals(State.READY,result.state());assertEquals(n(1000),result.totals().get(1L).get("USDT"));assertEquals(1,result.changes().size());
    }
    @Test void refundRestoresDepositSourceWithoutReversingDepositTwice(){
        var refund=new Event("rf",Action.REFUND,null,null,"c",n(600),null);
        var result=run(List.of(op(1,List.of(credit("d",1,1000)),cash(0,0,0),cash(1000,0,0)),op(2,List.of(spend("c",600)),cash(1000,0,0),cash(400,0,0)),
            op(3,List.of(refund),cash(400,0,0),cash(1000,0,0))),cash(1000,0,0));
        assertEquals(State.READY,result.state());assertEquals(n(1000),result.totals().get(1L).get("USDT"));assertEquals(1,result.changes().size());
    }
    @Test void missingReferenceQuarantinesWholeComponent(){unknown(run(List.of(op(1,List.of(new Event("w",Action.CONFIRM,null,null,"missing",null,null)),cash(0,0,1),cash(0,0,0))),cash(0,0,0)),Gap.ORIGIN);}
    @Test void duplicateEventAcrossReceiptsIsNotAcceptedAsDomainNoop(){unknown(run(List.of(op(1,List.of(credit("d",1,1)),cash(0,0,0),cash(1,0,0)),op(2,List.of(credit("d",1,1)),cash(1,0,0),cash(2,0,0))),cash(2,0,0)),Gap.RECEIPT);}
    @Test void tailMismatchHasNoPartialTotal(){unknown(run(List.of(op(1,List.of(credit("d",1,1)),cash(0,0,0),cash(1,0,0))),cash(2,0,0)),Gap.CASH_TRACE);}
    @Test void intermediateTraceMismatchEvenWhenFinalDeltaMatches(){
        var events=List.of(credit("d",1,10),spend("c",10));var operation=new Operation(1,"op",List.of(1L),events,OCTPoints(events),OCT);
        unknown(run(List.of(operation),cash(0,0,0)),Gap.CASH_TRACE);
    }
    private static List<Point> OCTPoints(List<Event> events){return List.of(new Point("a",1,1,cash(0,0,0),cash(11,0,0),List.of("d")),new Point("a",2,1,cash(11,0,0),cash(0,0,0),List.of("c")));}
    @Test void secondFailedEventDiscardsFirstContribution(){unknown(run(List.of(op(1,List.of(credit("d",1,10),spend("c",11)),cash(0,0,0),cash(0,0,0))),cash(0,0,0)),Gap.ALLOCATION);}
    @Test void sourceGapCannotProduceKnownPartialMoney(){var result=replay.replay(Set.of(1L),List.of(op(1,List.of(credit("d",1,1)),cash(0,0,0),cash(1,0,0))),Map.of(1L,cash(1,0,0)),Map.of("d","f"),List.of(Gap.SOURCE));unknown(result,Gap.SOURCE);}
    @Test void absentOriginAttributionKeyDoesNotInventContribution(){unknown(replay.replay(Set.of(1L),List.of(op(1,List.of(credit("d",1,1)),cash(0,0,0),cash(1,0,0))),Map.of(1L,cash(1,0,0)),Map.of(),List.of()),Gap.ORIGIN);}
    @Test void metadataOnlyOperationCanReconcileZeroCash(){assertEquals(State.READY,run(List.of(op(1,List.of(),cash(0,0,0),cash(0,0,0))),cash(0,0,0)).state());}
    @Test void emptyEventsCannotExplainCashChange(){unknown(run(List.of(op(1,List.of(),cash(0,0,0),cash(1,0,0))),cash(1,0,0)),Gap.CASH_TRACE);}
    @Test void unorderedSameParticipantCannotChooseTimestampOrder(){
        var a=new Operation(1,"one",List.of(1L),List.of(),List.of(),OCT);var b=new Operation(2,"two",List.of(1L),List.of(),List.of(),NOV);
        unknown(run(List.of(a,b),cash(0,0,0)),Gap.CAUSAL_ORDER);
    }
    @Test void releaseHasToPointToOriginalEarnings(){
        var e=new Event("e",Action.PROTECTED_EARNINGS,null,a(1,"USDT"),null,n(10),null);
        var release=new Event("release",Action.RELEASE,a(1,"USDT"),null,"e",null,null);
        var reserve=new Event("r",Action.RESERVE,a(1,"USDT"),null,null,n(10),null);
        var result=run(List.of(op(1,List.of(e),cash(0,0,0),cash(10,0,0)),op(2,List.of(release),cash(10,0,0),cash(10,0,0)),op(3,List.of(reserve),cash(10,0,0),cash(0,0,10))),cash(0,0,10));
        assertEquals(State.READY,result.state());assertEquals(0,result.totals().get(1L).get("USDT").signum());
    }
    @Test void twoTerminalOutcomesAreUnknown(){
        var reserve=new Event("r",Action.RESERVE,a(1,"USDT"),null,null,n(10),null);
        var c=new Event("w",Action.CONFIRM,null,null,"r",null,null);var r=new Event("return",Action.RETURN,null,null,"r",null,null);
        unknown(run(List.of(op(1,List.of(credit("d",1,10)),cash(0,0,0),cash(10,0,0)),op(2,List.of(reserve),cash(10,0,0),cash(0,0,10)),
            op(3,List.of(c),cash(0,0,10),cash(0,0,0)),op(4,List.of(r),cash(0,0,0),cash(10,0,0))),cash(10,0,0)),Gap.ALLOCATION);
    }
    @Test void foreignPartyIsRejected(){var operation=new Operation(1,"op",List.of(1L,2L),List.of(),List.of(),OCT);unknown(run(List.of(operation),cash(0,0,0)),Gap.RECEIPT);}
    @Test void dualCurrencyReservationAndFeeReturnKeepIndependentBasis(){
        var deposit=new Event("n",Action.DEPOSIT,null,a(1,"NEX"),null,n(2),null);
        var reserve=new Event("r",Action.RESERVE,a(1,"USDT"),null,null,n(10),null);
        var fee=new Event("fee",Action.FEE,a(1,"NEX"),null,null,n(2),null);
        var returned=new Event("return",Action.RETURN,null,null,"r",null,null);
        var feeRefund=new Event("feeRefund",Action.REFUND,null,null,"fee",n(2),null);
        var result=replay.replay(Set.of(1L),List.of(op(1,List.of(credit("d",1,10),deposit),cash(0,0,0),cash(10,2,0)),
            op(2,List.of(reserve,fee),cash(10,2,0),cash(0,0,10)),op(3,List.of(returned,feeRefund),cash(0,0,10),cash(10,2,0))),
            Map.of(1L,cash(10,2,0)),Map.of("d","deposit","n","nex"),List.of());
        assertEquals(State.READY,result.state());assertEquals(n(10),result.totals().get(1L).get("USDT"));assertEquals(n(2),result.totals().get(1L).get("NEX"));
    }
    @Test void threeCustomerTradeThenWithdrawalKeepsAOrigin(){
        var ab=new Event("ab",Action.TRADE,a(1,"USDT"),a(2,"USDT"),null,n(10),n(10));
        var bc=new Event("bc",Action.TRADE,a(2,"USDT"),a(3,"USDT"),null,n(10),n(10));
        var reserve=new Event("r",Action.RESERVE,a(3,"USDT"),null,null,n(10),null);
        var confirm=new Event("w",Action.CONFIRM,null,null,"r",null,null);
        var abOp=new Operation(2,"ab",List.of(1L,2L),List.of(ab),List.of(new Point("a",2,1,cash(10,0,0),cash(0,0,0),List.of("ab")),new Point("b",1,2,cash(0,0,0),cash(10,0,0),List.of("ab"))),OCT);
        var bcOp=new Operation(3,"bc",List.of(2L,3L),List.of(bc),List.of(new Point("b",2,2,cash(10,0,0),cash(0,0,0),List.of("bc")),new Point("c",1,3,cash(0,0,0),cash(10,0,0),List.of("bc"))),OCT);
        var rOp=new Operation(4,"r",List.of(3L),List.of(reserve),List.of(new Point("c",2,3,cash(10,0,0),cash(0,0,10),List.of("r"))),null);
        var wOp=new Operation(5,"w",List.of(3L),List.of(confirm),List.of(new Point("c",3,3,cash(0,0,10),cash(0,0,0),List.of("w"))),NOV);
        var result=replay.replay(Set.of(1L,2L,3L),List.of(wOp,bcOp,abOp,rOp,op(1,List.of(credit("d",1,10)),cash(0,0,0),cash(10,0,0))),
            Map.of(1L,cash(0,0,0),2L,cash(0,0,0),3L,cash(0,0,0)),Map.of("d","deposit"),List.of());
        assertEquals(State.READY,result.state());assertEquals(1L,result.changes().get(1).originCustomerId());assertEquals("d",result.changes().get(1).contributionId());
        assertEquals(n(0),result.totals().get(1L).get("USDT"));
    }
}
