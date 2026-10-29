package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentSnapshotProjection;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;

/** Detached, immutable financial facts only. No authenticated scope or private HTTP response is retained. */
public record SupportAnalyticsFactSnapshot(Instant asOf,List<Long> subjects,SupportPaymentFacts.Snapshot payments,
        NetSnapshot contributions,List<AttributionRow> attributions,List<AttributionProof> proofs,long capturedAtNanos) {
    public SupportAnalyticsFactSnapshot(Instant asOf,List<Long> subjects,SupportPaymentFacts.Snapshot payments,
            NetSnapshot contributions,List<AttributionRow> attributions,List<AttributionProof> proofs) {
        this(asOf,subjects,payments,contributions,attributions,proofs,System.nanoTime());
    }
    public SupportAnalyticsFactSnapshot {
        Objects.requireNonNull(asOf);Objects.requireNonNull(payments);Objects.requireNonNull(contributions);
        subjects=List.copyOf(subjects);attributions=List.copyOf(attributions);proofs=List.copyOf(proofs);
        var ids=new HashSet<>(subjects);
        if(ids.size()!=subjects.size() || ids.stream().anyMatch(id->id==null || id<=0)
            || payments.facts().stream().anyMatch(f->!ids.contains(f.customerId()))
            || !ids.equals(new HashSet<>(payments.firstHistory().stream().map(SupportPaymentFacts.FirstHistory::customerId).toList()))
            || payments.firstHistory().size()!=ids.size()
            || attributions.stream().anyMatch(a->!ids.contains(a.customerId()))
            || !ids.equals(new HashSet<>(contributions.customers().stream().map(NetCustomer::customerId).toList()))
            || contributions.customers().size()!=ids.size()
            || contributions.origins().stream().anyMatch(o->!ids.contains(o.customerId()))
            || contributions.changes().stream().anyMatch(c->!ids.contains(c.originCustomerId())))throw new IllegalArgumentException("SOURCE_SNAPSHOT_SCOPE_INVALID");
        for(var coverage:payments.coverage())if(coverage.excludedFreeOrNonProductionRows()!=payments.excludedRows().stream()
                .filter(e->e.source()==coverage.source()).mapToLong(SupportPaymentFacts.ExcludedRows::rows).sum())
            throw new IllegalArgumentException("SOURCE_SNAPSHOT_EXCLUSIONS_NOT_DISAGGREGATED");
    }
    public void requireFresh(Instant now) {
        requireFresh(now,System.nanoTime());
    }
    void requireFresh(Instant now,long nowNanos) {
        // Reserve 500ms of the five-second product limit for serialization, proxying and transport.
        long elapsed=nowNanos-capturedAtNanos;
        if(elapsed<0 || elapsed>4_500_000_000L || now.isBefore(asOf) || Duration.between(asOf,now).compareTo(Duration.ofMillis(4500))>0)
            throw new BizException(503,"SUPPORT_ANALYTICS_SOURCE_SNAPSHOT_EXPIRED");
    }
    SupportPaymentFacts.Snapshot payments(Collection<Long> ids) {return SupportPaymentSnapshotProjection.subset(payments,ids,asOf);}
    NetSnapshot contributions(Collection<Long> requested) {
        var ids=new TreeSet<>(requested);
        var rows=new ArrayList<>(contributions.customers().stream().filter(c->ids.contains(c.customerId())).toList());
        for(long id:ids)if(rows.stream().noneMatch(c->c.customerId()==id))
            rows.add(new NetCustomer(id,EvidenceState.UNKNOWN,List.of("SOURCE_SNAPSHOT_SUBJECT_NOT_OBSERVED"),null,null));
        return new NetSnapshot(rows,contributions.origins().stream().filter(o->ids.contains(o.customerId())).toList(),
            contributions.changes().stream().filter(c->ids.contains(c.originCustomerId())).toList());
    }
    List<EventCandidate> eventCandidates(ReadScope scope,Set<Long> currentGroups) {
        return attributions.stream().filter(a->eventAllowed(a,scope,currentGroups)).map(a->new EventCandidate(a.factId(),a.customerId())).toList();
    }
    List<AttributionRow> attributions(Collection<String> factIds,ReadScope scope,Set<Long> groups,Set<Long> currentCustomers) {
        var ids=new HashSet<>(factIds);
        return attributions.stream().filter(a->ids.contains(a.factId()) && (currentCustomers.contains(a.customerId()) || eventAllowed(a,scope,groups))).toList();
    }
    List<AttributionProof> proofs(Collection<String> factIds,ReadScope scope,Set<Long> groups,Set<Long> currentCustomers) {
        var allowed=new HashSet<>(attributions(factIds,scope,groups,currentCustomers).stream().map(AttributionRow::factId).toList());
        return proofs.stream().filter(p->allowed.contains(p.factId())).toList();
    }
    private static boolean eventAllowed(AttributionRow a,ReadScope scope,Set<Long> groups) {
        if(scope.requestedAgentId()!=null && (!"KNOWN".equals(a.agentStatus()) || !scope.requestedAgentId().equals(a.agentAdminId())))return false;
        return switch(scope.mode()) {
            case PERSONAL -> "KNOWN".equals(a.agentStatus()) && scope.actorId().equals(a.agentAdminId());
            case MANAGED -> "KNOWN".equals(a.groupStatus()) && a.groupId()!=null && groups.contains(a.groupId())
                && (scope.requestedGroupId()==null || scope.requestedGroupId().equals(a.groupId()));
            case ALL -> scope.requestedGroupId()==null || "KNOWN".equals(a.groupStatus()) && scope.requestedGroupId().equals(a.groupId());
        };
    }
}
