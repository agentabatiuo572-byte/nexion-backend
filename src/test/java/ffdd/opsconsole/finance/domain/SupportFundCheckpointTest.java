package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import ffdd.opsconsole.finance.domain.SupportFundReplay.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportFundCheckpointTest {
    private final SupportFundReplay replay=new SupportFundReplay();
    private static final Stamp SECOND=new Stamp("source",LocalDateTime.of(2026,10,11,12,0),"Asia/Shanghai",0);
    private static BigDecimal n(int amount){return BigDecimal.valueOf(amount).setScale(6);}
    private static Cash c(int available,int pending,int risk){return new Cash(n(available),n(0),n(pending),n(risk));}
    private static Account a(){return new Account(1,"USDT","available");}
    private static Event earning(String id,int amount){return new Event(id,Action.PROTECTED_EARNINGS,null,a(),null,n(amount),null);}
    private static Event release(String id,String origin){return new Event(id,Action.RELEASE,a(),null,origin,null,null);}
    private static Operation cash(long id,long revision,List<Event> events,Cash before,Cash after){return new Operation(id,"op"+id,List.of(1L),events,List.of(new Point("wallet",revision,1,before,after,events.stream().map(Event::id).toList())),SECOND);}
    private static Operation zero(long id,long revision,long previous,List<Event> events,Cash cash,ZeroPhase phase){return new Operation(id,"zero"+id,List.of(1L),events,List.of(),SECOND,List.of(new Checkpoint("wallet",revision,1,cash,previous,phase)));}
    private Result run(List<Operation> ops,Cash tail,List<Holding> holdings){return replay.replay(Set.of(1L),ops,Map.of(1L,tail),Map.of("d","deposit"),List.of(),holdings);}
    private static void unknown(Result result){assertEquals(State.UNKNOWN,result.state());assertTrue(result.totals().isEmpty());}
    @Test void releaseAtRealUnchangedCheckpointCanPrecedeNextCashRevisionInSameSecond(){
        var earn=cash(1,1,List.of(earning("e",10)),c(0,0,0),c(10,0,0));
        var release=zero(2,1,1,List.of(release("rel","e")),c(10,0,0),ZeroPhase.RELEASE);
        var reserve=cash(3,2,List.of(new Event("r",Action.RESERVE,a(),null,null,n(10),null)),c(10,0,0),c(0,10,0));
        assertEquals(State.READY,run(List.of(reserve,release,earn),c(0,10,0),List.of()).state());
    }
    @Test void twoSameCheckpointReleasesUseActualLockedWriterPredecessorChain(){
        var e1=cash(1,1,List.of(earning("e1",5)),c(0,0,0),c(5,0,0));
        var e2=cash(2,2,List.of(earning("e2",5)),c(5,0,0),c(10,0,0));
        var r1=zero(3,2,2,List.of(release("rel1","e1")),c(10,0,0),ZeroPhase.RELEASE);
        var r2=zero(4,2,3,List.of(release("rel2","e2")),c(10,0,0),ZeroPhase.RELEASE);
        var reserve=cash(5,3,List.of(new Event("r",Action.RESERVE,a(),null,null,n(10),null)),c(10,0,0),c(0,10,0));
        assertEquals(State.READY,run(List.of(r2,reserve,e2,r1,e1),c(0,10,0),List.of()).state());
        unknown(run(List.of(e1,e2,r1,zero(4,2,2,List.of(release("rel2","e2")),c(10,0,0),ZeroPhase.RELEASE),reserve),c(0,10,0),List.of()));
    }
    @Test void arbitraryPointsEmptyReleaseIsNotVerifiedByTimestamp(){
        var e=cash(1,1,List.of(earning("e",10)),c(0,0,0),c(10,0,0));
        var unsupported=new Operation(2,"zero",List.of(1L),List.of(release("rel","e")),List.of(),SECOND);
        unknown(run(List.of(e,unsupported),c(10,0,0),List.of()));
    }
    @Test void missingReleaseCannotMakeProtectedEarningsWithdrawable(){
        var e=cash(1,1,List.of(earning("e",10)),c(0,0,0),c(10,0,0));
        var reserve=cash(3,2,List.of(new Event("r",Action.RESERVE,a(),null,null,n(10),null)),c(10,0,0),c(0,10,0));
        unknown(run(List.of(e,reserve),c(0,10,0),List.of()));
    }
    @Test void wrongCashIncarnationRevisionOrUnrelatedPredecessorIsUnknown(){
        var e=cash(1,1,List.of(earning("e",10)),c(0,0,0),c(10,0,0));
        unknown(run(List.of(e,zero(2,1,1,List.of(release("rel","e")),c(9,0,0),ZeroPhase.RELEASE)),c(10,0,0),List.of()));
        unknown(run(List.of(e,zero(2,2,1,List.of(release("rel","e")),c(10,0,0),ZeroPhase.RELEASE)),c(10,0,0),List.of()));
        var foreign=new Operation(2,"zero",List.of(1L),List.of(release("rel","e")),List.of(),SECOND,List.of(new Checkpoint("other",1,1,c(10,0,0),1,ZeroPhase.RELEASE)));
        unknown(run(List.of(e,foreign),c(10,0,0),List.of()));
    }
    @Test void checkpointCannotExplainCashEventWithoutARawLeg(){
        var e=cash(1,1,List.of(earning("e",10)),c(0,0,0),c(10,0,0));
        unknown(run(List.of(e,zero(2,1,1,List.of(new Event("spend",Action.CONSUME,a(),null,null,n(1),null)),c(10,0,0),ZeroPhase.RELEASE)),c(10,0,0),List.of()));
    }
    @Test void zeroRecoverySourceIsOrderedWithoutInventingWalletRevision(){
        var e=cash(1,1,List.of(earning("e",10)),c(0,0,0),c(10,0,0));
        assertEquals(State.READY,run(List.of(e,zero(2,1,1,List.of(),c(10,0,0),ZeroPhase.RECOVERY_NO_CASH)),c(10,0,0),List.of()).state());
    }
    @Test void riskNeedsExactIndependentSourceTail(){
        var risk=new Account(1,"USDT","risk:incident");var d=new Event("d",Action.DEPOSIT,null,a(),null,n(10),null);
        var move=new Event("risk",Action.MOVE,a(),risk,null,n(4),null);
        var ops=List.of(cash(1,1,List.of(d),c(0,0,0),c(10,0,0)),cash(2,2,List.of(move),c(10,0,0),c(6,0,4)));
        unknown(run(ops,c(6,0,4),List.of()));
        assertEquals(State.READY,run(ops,c(6,0,4),List.of(new Holding(risk,n(4),"incident:1"))).state());
        unknown(run(ops,c(6,0,4),List.of(new Holding(risk,n(3),"incident:1"))));
    }
    @Test void externalPositionCannotDisappearFromSourceTailAssertions(){
        var held=new Account(1,"USDT","held:position");var d=new Event("d",Action.DEPOSIT,null,a(),null,n(10),null);
        var move=new Event("open",Action.MOVE,a(),held,null,n(4),null);
        var ops=List.of(cash(1,1,List.of(d),c(0,0,0),c(10,0,0)),cash(2,2,List.of(move),c(10,0,0),c(6,0,0)));
        unknown(run(ops,c(6,0,0),List.of()));
        assertEquals(State.READY,run(ops,c(6,0,0),List.of(new Holding(held,n(4),"position:1"))).state());
    }
}
