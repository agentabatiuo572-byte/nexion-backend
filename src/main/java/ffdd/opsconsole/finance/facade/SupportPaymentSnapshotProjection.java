package ffdd.opsconsole.finance.facade;

import static ffdd.opsconsole.finance.facade.SupportPaymentFacts.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/** Pure internal projection, never authorization. The caller supplies currently authorized subject IDs. */
public final class SupportPaymentSnapshotProjection {
    private SupportPaymentSnapshotProjection() { }
    public static Snapshot subset(Snapshot all,Collection<Long> requested,Instant asOf) {
        var ids=new TreeSet<>(requested);
        if(ids.stream().anyMatch(id->id==null || id<=0))throw new IllegalArgumentException("Explicit positive subject scope required");
        var facts=all.facts().stream().filter(f->ids.contains(f.customerId())).toList();
        var issues=all.issues().stream().filter(i->i.customerId()==null || ids.contains(i.customerId())).toList();
        var first=new ArrayList<>(all.firstHistory().stream().filter(h->ids.contains(h.customerId())).toList());
        var customerCoverage=new ArrayList<>(all.customerCoverage().stream().filter(c->ids.contains(c.customerId())).toList());
        for(long id:ids)if(first.stream().noneMatch(h->h.customerId()==id)) {
            first.add(new FirstHistory(id,Status.UNKNOWN,List.of("SOURCE_SNAPSHOT_SUBJECT_NOT_OBSERVED")));
            for(Source source:Source.values())customerCoverage.add(new CustomerCoverage(id,source,Status.UNKNOWN,Status.UNKNOWN,Status.UNKNOWN,null,List.of("SOURCE_SNAPSHOT_SUBJECT_NOT_OBSERVED")));
        }
        var excluded=all.excludedRows().stream().filter(e->e.customerId()<=0 || ids.contains(e.customerId())).toList();
        var coverage=new ArrayList<Coverage>();
        for(var original:all.coverage()) {
            Source source=original.source();
            var scoped=customerCoverage.stream().filter(c->c.source()==source).toList();
            boolean deposit=Set.of(Source.DEPOSIT_ORDER,Source.CARD_TOPUP,Source.VIETQR,Source.HDPAY).contains(source);
            boolean lifetime=scoped.size()==ids.size() && scoped.stream().allMatch(c->c.historyStatus()==Status.READY);
            boolean environment=lifetime && scoped.stream().allMatch(c->c.historicalEnvironmentStatus()==Status.READY);
            boolean refunds=deposit || scoped.size()==ids.size() && scoped.stream().allMatch(c->c.refundStatus()==Status.READY);
            var reasons=new TreeSet<String>();
            if(!lifetime)reasons.add("LIFETIME_HISTORY_COVERAGE_NOT_ATTESTED");
            if(!environment)reasons.add("HISTORICAL_ENVIRONMENT_NOT_RECORDED");
            if(deposit)reasons.add("SUCCESSFUL_DEPOSITS_NON_REFUNDABLE");
            else if(!refunds)reasons.add("REFUND_HISTORY_COVERAGE_NOT_ATTESTED");
            scoped.forEach(c->reasons.addAll(c.reasons()));
            issues.stream().filter(i->i.source()==source).map(Issue::reason).forEach(reasons::add);
            coverage.add(new Coverage(source,issues.stream().anyMatch(i->i.source()==source)?Status.UNKNOWN:Status.READY,
                lifetime?Status.READY:Status.UNKNOWN,refunds?Status.READY:Status.UNKNOWN,environment?Status.READY:Status.UNKNOWN,
                lifetime?scoped.stream().map(CustomerCoverage::supportedFrom).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null):null,
                List.copyOf(reasons),excluded.stream().filter(e->e.source()==source).mapToLong(ExcludedRows::rows).sum(),original.adapterVersion()));
        }
        return new Snapshot(facts,issues,coverage,all.businessZone(),asOf,first,customerCoverage,excluded);
    }
}
