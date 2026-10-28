package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.finance.domain.SupportFundAllocation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Pure orchestration of verified source facts. FIFO and fund basis remain in Allocation. */
public final class SupportFundReplay {
    public enum State { READY, UNKNOWN }
    public enum Gap { WALLET, AUTH_BIRTH, RAW_HISTORY, RAW_BINDING, RECEIPT, SOURCE, SOURCE_COVERAGE,
        UNSUPPORTED_SOURCE, CAUSAL_ORDER, CASH_TRACE, ALLOCATION, ORIGIN, CONTINUITY_UNPROVEN, READ_FAILED, CLOSURE_LIMIT }
    public record Cash(BigDecimal usdt,BigDecimal nex,BigDecimal pending,BigDecimal risk) {
        public Cash {
            for(var value:List.of(usdt,nex,pending,risk)) if(value.signum()<0 || value.stripTrailingZeros().scale()>6)
                throw new IllegalArgumentException("Invalid four-bucket cash");
            usdt=usdt.setScale(6);nex=nex.setScale(6);pending=pending.setScale(6);risk=risk.setScale(6);
        }
    }
    public record Stamp(String sourceReference,LocalDateTime succeededAt,String businessZone,int fractionalSecondDigits) {
        public Stamp {
            Objects.requireNonNull(sourceReference);Objects.requireNonNull(succeededAt);ZoneId.of(businessZone);
            if(sourceReference.isBlank() || fractionalSecondDigits<0 || fractionalSecondDigits>6
                    || succeededAt.getNano()%(int)Math.pow(10,9-fractionalSecondDigits)!=0)
                throw new IllegalArgumentException("Unproven success time");
        }
    }
    public record Point(String incarnation,long revision,long customerId,Cash before,Cash after,List<String> eventIds) {
        public Point {eventIds=List.copyOf(eventIds);}
    }
    public enum ZeroPhase { RELEASE, RECOVERY_NO_CASH }
    /** Source adapter must validate the real wallet checkpoint and locked-writer predecessor. This is not a raw leg. */
    public record Checkpoint(String incarnation,long revision,long customerId,Cash cash,long previousOperationId,ZeroPhase phase) {}
    public record Holding(Account account,BigDecimal amount,String sourceReference) {}
    public record Operation(long id,String key,List<Long> parties,List<Event> events,List<Point> points,Stamp stamp,List<Checkpoint> checkpoints) {
        public Operation {parties=List.copyOf(parties);events=List.copyOf(events);points=List.copyOf(points);checkpoints=List.copyOf(checkpoints);}
        public Operation(long id,String key,List<Long> parties,List<Event> events,List<Point> points,Stamp stamp) {
            this(id,key,parties,events,points,stamp,List.of());
        }
    }
    public record Origin(String contributionId,long customerId,String paymentFactId,Stamp formedAt) {}
    public record ConfirmedChange(String contributionId,long originCustomerId,String currency,BigDecimal amount,
                                  Stamp occurredAt) {}
    public record Result(State state,List<Gap> reasons,Map<String,Origin> origins,List<ConfirmedChange> changes,
                         Map<Long,Map<String,BigDecimal>> totals) {
        public Result {reasons=List.copyOf(reasons);origins=Map.copyOf(origins);changes=List.copyOf(changes);totals=Map.copyOf(totals);}
    }
    public Result replay(Set<Long> component,List<Operation> operations,Map<Long,Cash> tails,Map<String,String> paymentFacts,List<Gap> gaps) {
        return replay(component,operations,tails,paymentFacts,gaps,List.of());
    }
    public Result replay(Set<Long> component,List<Operation> operations,Map<Long,Cash> tails,Map<String,String> paymentFacts,List<Gap> gaps,List<Holding> holdings) {
        if(!gaps.isEmpty())return unknown(gaps);
        try {return verified(component,operations,tails,paymentFacts,holdings);}
        catch(ReplayFailure ex) {return unknown(List.of(ex.gap));}
        catch(IllegalArgumentException ex) {return unknown(List.of(Gap.ALLOCATION));}
    }
    private Result verified(Set<Long> component,List<Operation> operations,Map<Long,Cash> tails,Map<String,String> paymentFacts,List<Holding> holdings) {
        require(!component.isEmpty() && tails.keySet().equals(component),Gap.WALLET);
        var byId=new HashMap<Long,Operation>();var eventOwners=new HashMap<String,Operation>();
        var edges=new HashMap<Long,Set<Long>>();var degrees=new HashMap<Long,Integer>();
        var revisions=new HashMap<String,TreeMap<Long,Operation>>();
        for(var op:operations) {
            require(op.id()>0 && byId.put(op.id(),op)==null && !op.parties().isEmpty()
                    && component.containsAll(op.parties()),Gap.RECEIPT);
            edges.put(op.id(),new HashSet<>());degrees.put(op.id(),0);
            for(var event:op.events()) {
                SupportFundAllocation.validate(event);
                require(eventOwners.put(event.id(),op)==null,Gap.RECEIPT);
                require(event.from()==null || op.parties().contains(event.from().customerId()),Gap.RECEIPT);
                require(event.to()==null || op.parties().contains(event.to().customerId()),Gap.RECEIPT);
            }
            var assigned=new ArrayList<String>();
            for(var point:op.points()) {
                require(op.parties().contains(point.customerId()) && point.revision()>0,Gap.CASH_TRACE);
                require(revisions.computeIfAbsent(point.incarnation(),k->new TreeMap<>()).put(point.revision(),op)==null,Gap.RAW_BINDING);
                assigned.addAll(point.eventIds());
            }
            if(op.checkpoints().isEmpty())require(assigned.stream().distinct().toList().equals(op.events().stream().map(Event::id).toList()),Gap.CASH_TRACE);
            else {
                require(op.points().isEmpty() && op.checkpoints().stream().map(Checkpoint::customerId).distinct().toList().equals(op.parties()),Gap.CASH_TRACE);
                for(var checkpoint:op.checkpoints()) {
                    require(checkpoint.incarnation()!=null && !checkpoint.incarnation().isBlank() && checkpoint.revision()>=0
                        && checkpoint.cash()!=null && checkpoint.phase()!=null && checkpoint.previousOperationId()>0
                        && checkpoint.previousOperationId()<op.id(),Gap.CAUSAL_ORDER);
                    require(checkpoint.phase()==ZeroPhase.RELEASE? !op.events().isEmpty() && op.events().stream().allMatch(e->e.action()==Action.RELEASE)
                        :op.events().isEmpty(),Gap.CASH_TRACE);
                }
            }
        }
        for(var op:operations) for(var event:op.events()) if(event.reference()!=null) {
            var parent=eventOwners.get(event.reference());require(parent!=null,Gap.ORIGIN);
            if(parent.id()!=op.id())edge(parent.id(),op.id(),edges,degrees);
            else require(op.events().indexOf(eventOwners.get(event.reference()).events().stream().filter(e->e.id().equals(event.reference())).findFirst().orElseThrow())
                    <op.events().indexOf(event),Gap.CAUSAL_ORDER);
        }
        for(var rows:revisions.values()) {
            Operation prior=null;for(var op:rows.values()) {
                if(prior!=null && prior.id()!=op.id())edge(prior.id(),op.id(),edges,degrees);prior=op;
            }
        }
        for(var op:operations)for(var checkpoint:op.checkpoints()) {
            var rows=revisions.get(checkpoint.incarnation());require(rows!=null,Gap.RAW_HISTORY);
            var prior=rows.floorEntry(checkpoint.revision());var next=rows.higherEntry(checkpoint.revision());
            require(prior!=null && prior.getValue().points().stream().anyMatch(p->p.customerId()==checkpoint.customerId()
                && p.incarnation().equals(checkpoint.incarnation()) && p.revision()==checkpoint.revision() && p.after().equals(checkpoint.cash())),Gap.CASH_TRACE);
            edge(prior.getValue().id(),op.id(),edges,degrees);
            if(next!=null)edge(op.id(),next.getValue().id(),edges,degrees);
            var predecessor=byId.get(checkpoint.previousOperationId());
            require(predecessor!=null && predecessor.parties().contains(checkpoint.customerId()),Gap.CAUSAL_ORDER);
            long latest=operations.stream().filter(p->p.id()<op.id() &&p.parties().contains(checkpoint.customerId())).mapToLong(Operation::id).max().orElse(0);
            require(latest==predecessor.id(),Gap.CAUSAL_ORDER);
            boolean sameCheckpoint=predecessor.checkpoints().stream().anyMatch(p->p.customerId()==checkpoint.customerId()
                &&p.incarnation().equals(checkpoint.incarnation()) &&p.revision()==checkpoint.revision() &&p.cash().equals(checkpoint.cash()));
            boolean sameCashTail=predecessor.points().stream().anyMatch(p->p.customerId()==checkpoint.customerId()
                &&p.incarnation().equals(checkpoint.incarnation()) &&p.revision()==checkpoint.revision() &&p.after().equals(checkpoint.cash()));
            require(sameCheckpoint ||sameCashTail,Gap.CASH_TRACE);edge(predecessor.id(),op.id(),edges,degrees);
        }
        // Unordered operations touching one participant can change FIFO, even with equal timestamps.
        for(int i=0;i<operations.size();i++)for(int j=i+1;j<operations.size();j++) {
            var a=operations.get(i);var b=operations.get(j);
            if(!Collections.disjoint(a.parties(),b.parties()))require(reachable(a.id(),b.id(),edges,new HashSet<>())
                    ||reachable(b.id(),a.id(),edges,new HashSet<>()),Gap.CAUSAL_ORDER);
        }
        var queue=new PriorityQueue<Long>();degrees.forEach((id,d)->{if(d==0)queue.add(id);});
        var ordered=new ArrayList<Operation>();
        while(!queue.isEmpty()) {long id=queue.remove();ordered.add(byId.get(id));for(long next:edges.get(id))if(degrees.compute(next,(k,v)->v-1)==0)queue.add(next);}
        require(ordered.size()==operations.size(),Gap.CAUSAL_ORDER);
        var allocation=new SupportFundAllocation();var origins=new LinkedHashMap<String,Origin>();
        var changes=new ArrayList<ConfirmedChange>();var totals=new HashMap<Long,Map<String,BigDecimal>>();
        for(long id:component)totals.put(id,new HashMap<>(Map.of("USDT",BigDecimal.ZERO,"UVT",BigDecimal.ZERO)));
        for(var op:ordered) {
            var events=new HashMap<String,Event>();op.events().forEach(event->events.put(event.id(),event));
            var applied=new HashSet<String>();
            if(!op.checkpoints().isEmpty()) {
                for(var checkpoint:op.checkpoints())cash(allocation,checkpoint.customerId(),checkpoint.cash());
                if(!op.events().isEmpty())require(allocation.applyBatch(op.events()).isEmpty(),Gap.CASH_TRACE);
                for(var checkpoint:op.checkpoints())cash(allocation,checkpoint.customerId(),checkpoint.cash());
            }
            for(int index=0;index<op.points().size();) {
                var point=op.points().get(index);int end=index+1;
                while(end<op.points().size() && !point.eventIds().isEmpty() &&op.points().get(end).eventIds().equals(point.eventIds()))end++;
                var simultaneous=op.points().subList(index,end);
                for(var p:simultaneous)cash(allocation,p.customerId(),p.before());
                for(String id:point.eventIds())require(applied.add(id),Gap.CASH_TRACE);
                List<Change> delta=point.eventIds().isEmpty()?List.of():allocation.applyBatch(point.eventIds().stream().map(events::get).toList());
                for(var p:simultaneous)cash(allocation,p.customerId(),p.after());
                for(var change:delta) {
                    if(change.amount().signum()>0 && !origins.containsKey(change.contributionId())) {
                        Event originEvent=events.get(change.contributionId());require(originEvent!=null,Gap.ORIGIN);
                        long customer=originEvent.action()==Action.DEPOSIT?originEvent.to().customerId():originEvent.from().customerId();
                        String fact=paymentFacts.get(change.contributionId());require(fact!=null && op.stamp()!=null,Gap.ORIGIN);
                        origins.put(change.contributionId(),new Origin(change.contributionId(),customer,fact,op.stamp()));
                    }
                    var origin=origins.get(change.contributionId());require(origin!=null && op.stamp()!=null,Gap.ORIGIN);
                    changes.add(new ConfirmedChange(change.contributionId(),origin.customerId(),change.currency(),change.amount(),op.stamp()));
                    totals.get(origin.customerId()).merge(change.currency(),change.amount(),BigDecimal::add);
                }
                index=end;
            }
        }
        tails.forEach((id,tail)->cash(allocation,id,tail));
        var expectedHoldings=new HashMap<Account,BigDecimal>();var seenHoldings=new HashSet<Account>();
        for(var holding:holdings) {
            require(holding!=null &&holding.account()!=null &&component.contains(holding.account().customerId())
                &&!holding.account().slot().equals("available") &&holding.amount()!=null &&holding.amount().signum()>=0
                &&holding.amount().stripTrailingZeros().scale()<=6 &&holding.sourceReference()!=null &&!holding.sourceReference().isBlank()
                &&seenHoldings.add(holding.account()),Gap.SOURCE);
            if(holding.amount().signum()>0)expectedHoldings.put(holding.account(),holding.amount());
        }
        var actualHoldings=allocation.heldBalances();require(actualHoldings.keySet().equals(expectedHoldings.keySet()),Gap.SOURCE_COVERAGE);
        actualHoldings.forEach((account,amount)->require(amount.compareTo(expectedHoldings.get(account))==0,Gap.CASH_TRACE));
        var immutable=new HashMap<Long,Map<String,BigDecimal>>();totals.forEach((id,total)->immutable.put(id,Map.copyOf(total)));
        return new Result(State.READY,List.of(),origins,changes,immutable);
    }
    private static void cash(SupportFundAllocation funds,long id,Cash cash) {
        require(funds.balance(new Account(id,"USDT","available")).compareTo(cash.usdt())==0
                && funds.balance(new Account(id,"UVT","available")).compareTo(cash.nex())==0
                && funds.pendingWithdrawal(new Account(id,"USDT","available")).compareTo(cash.pending())==0
                && funds.heldBalances().entrySet().stream().filter(e->e.getKey().customerId()==id &&e.getKey().currency().equals("USDT")
                    &&e.getKey().slot().startsWith("risk:")).map(Map.Entry::getValue).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(cash.risk())==0,Gap.CASH_TRACE);
    }
    private static void edge(long a,long b,Map<Long,Set<Long>> edges,Map<Long,Integer> degrees) {
        if(edges.get(a).add(b))degrees.merge(b,1,Integer::sum);
    }
    private static boolean reachable(long a,long b,Map<Long,Set<Long>> edges,Set<Long> seen) {
        if(!seen.add(a))return false;for(long next:edges.get(a))if(next==b || reachable(next,b,edges,seen))return true;return false;
    }
    private static Result unknown(List<Gap> gaps) {return new Result(State.UNKNOWN,gaps.stream().distinct().toList(),Map.of(),List.of(),Map.of());}
    private static void require(boolean valid,Gap gap) {if(!valid)throw new ReplayFailure(gap);}
    private static final class ReplayFailure extends RuntimeException {private final Gap gap;ReplayFailure(Gap gap){this.gap=gap;}}
}
