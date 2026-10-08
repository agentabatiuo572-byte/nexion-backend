package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal statistics only. Authorization is current; event attribution never comes from current membership. */
@ApplicationService
@RequiredArgsConstructor
public class SupportAnalyticsService {
    private final SupportOwnershipService ownership;
    private final FinanceSupportPaymentFactsFacade finance;
    private final SupportAnalyticsMapper mapper;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result summarize(Query query) {
        if(query==null)throw new IllegalArgumentException("SUPPORT_ANALYTICS_QUERY_INVALID");
        ReadScope scope=query.mode()==null?ownership.defaultQueryScope(query.groupId(),query.agentId())
            :ownership.queryScope(query.mode(),query.groupId(),query.agentId());
        if(scope==null)throw invalid("SUPPORT_ANALYTICS_SCOPE_INVALID");
        var reasons=new TreeSet<String>();
        var current=new TreeMap<Long,SupportAnalyticsMapper.CurrentCustomer>();
        boolean currentFailed=false;
        try {
            var rows=mapper.currentCustomers(scope);
            if(rows==null)throw invalid("SUPPORT_ANALYTICS_CURRENT_INVALID");
            for(var row:rows) {
                if(row==null || row.customerId()==null || row.customerId()<=0 || row.handoverRequired()==null
                        || row.handoverRequired()<0 || row.handoverRequired()>1)
                    throw invalid("SUPPORT_ANALYTICS_CURRENT_INVALID");
                try { Category.valueOf(row.category());Placement.valueOf(row.placement()); }
                catch(RuntimeException ex) { throw invalid("SUPPORT_ANALYTICS_CURRENT_INVALID"); }
                if("UNKNOWN".equals(row.placement()))reasons.add("CURRENT_PLACEMENT_UNVERIFIED");
                var previous=current.putIfAbsent(row.customerId(),row);
                if(previous!=null && !previous.equals(row))throw invalid("SUPPORT_ANALYTICS_CURRENT_CONFLICT");
            }
        } catch(DataAccessException ex) { currentFailed=true;reasons.add("CURRENT_SOURCE_READ_FAILED"); }
        var currentScope=currentScope(scope,current.values(),currentFailed);
        if(query.basis()==Basis.CURRENT_ASSET) {
            var customers=new ArrayList<Customer>();
            for(var row:current.values())customers.add(customer(row,new FirstSelection(null,Status.UNKNOWN,List.of("HISTORY_NOT_REQUESTED"))));
            return new Result(query,currentScope,customers,unavailableFinancial(),restricted(0,0,Status.UNAVAILABLE),
                List.of(),Instant.now(),List.copyOf(reasons));
        }
        var eventIds=new TreeMap<String,Long>();
        var legacyIds=new TreeSet<Long>();
        boolean eventFailed=false;
        if(query.basis()==Basis.PERIOD_EVENT) {
            try {
                var rows=mapper.eventCandidates(scope);
                if(rows==null)throw invalid("SUPPORT_ANALYTICS_EVENT_INVALID");
                for(var row:rows) {
                    if(row==null || row.factId()==null || row.factId().isBlank() || row.customerId()==null || row.customerId()<=0)
                        throw invalid("SUPPORT_ANALYTICS_EVENT_INVALID");
                    Long prior=eventIds.putIfAbsent(row.factId(),row.customerId());
                    if(prior!=null && !prior.equals(row.customerId()))throw invalid("SUPPORT_ANALYTICS_EVENT_CONFLICT");
                }
                if(global(scope)) {
                    var ids=mapper.legacyProductionCustomers(scope);
                    if(ids==null || ids.stream().anyMatch(id -> id==null || id<=0))throw invalid("SUPPORT_ANALYTICS_LEGACY_SCOPE_INVALID");
                    legacyIds.addAll(ids);
                }
            } catch(DataAccessException ex) { eventFailed=true;reasons.add("EVENT_SOURCE_READ_FAILED"); }
        }
        var ids=new TreeSet<Long>(current.keySet());ids.addAll(eventIds.values());ids.addAll(legacyIds);
        // The finance façade owns source eligibility, logical-payment dedup and refund lineage/caps.
        // Unexpected façade exceptions propagate; catching a transactional proxy could conceal rollback-only.
        Snapshot snapshot=ids.isEmpty()?null:finance.readHistory(List.copyOf(ids));
        if(!ids.isEmpty() && snapshot==null)throw invalid("SUPPORT_ANALYTICS_FINANCIAL_RESPONSE_MISSING");
        var facts=new TreeMap<String,Fact>();
        var first=new TreeMap<Long,Fact>();
        ZoneId sourceZone=null;
        if(snapshot!=null) {
            try { sourceZone=ZoneId.of(snapshot.businessZone()); }
            catch(RuntimeException ex) { throw invalid("SUPPORT_ANALYTICS_FINANCIAL_INVALID"); }
            for(Fact fact:snapshot.facts()) {
                if(fact==null || !ids.contains(fact.customerId()))throw invalid("SUPPORT_ANALYTICS_FINANCIAL_SCOPE_INVALID");
                if(SupportPaymentFacts.validateCanonical(fact,snapshot.businessZone())!=null)throw invalid("SUPPORT_ANALYTICS_FINANCIAL_INVALID");
                Fact previous=facts.putIfAbsent(fact.factId(),fact);
                if(previous!=null && !sameFinancial(previous,fact))throw invalid("SUPPORT_ANALYTICS_FINANCIAL_CONFLICT");
            }
            var order=Comparator.comparing(Fact::succeededAt).thenComparingInt(f -> f.kind()==Kind.DEPOSIT?0:1).thenComparing(Fact::factId);
            for(Fact fact:facts.values())if(fact.kind()!=Kind.DEVICE_PURCHASE_REFUND)
                first.merge(fact.customerId(),fact,(left,right) -> order.compare(left,right)<=0?left:right);
            for(var issue:snapshot.issues())reasons.add("FINANCIAL_SOURCE_UNVERIFIED");
        } else reasons.add("NO_OBSERVED_FINANCIAL_SCOPE");
        var attribution=new HashMap<String,Attribution>();
        var validAttribution=new HashMap<String,AttributionRow>();
        boolean attributionFailed=false;
        if(!facts.isEmpty()) {
            try {
                var rows=mapper.attributions(scope,List.copyOf(facts.keySet()));
                if(rows==null)throw invalid("SUPPORT_ANALYTICS_ATTRIBUTION_INVALID");
                var seen=new HashSet<String>();
                for(var row:rows) {
                    if(row==null || !facts.containsKey(row.factId()) || row.customerId()==null || !ids.contains(row.customerId()))
                        throw invalid("SUPPORT_ANALYTICS_ATTRIBUTION_SCOPE_INVALID");
                    Fact fact=facts.get(row.factId());
                    if(!seen.add(row.factId()) || !aligned(row,fact,snapshot.businessZone()) || proofRejected(snapshot,fact)
                            || !validLayers(row)) {
                        attribution.put(row.factId(),unknownAttribution());validAttribution.remove(row.factId());
                        reasons.add("EVENT_ATTRIBUTION_UNVERIFIED");continue;
                    }
                    attribution.put(row.factId(),new Attribution(AttributionStatus.valueOf(row.agentStatus()),
                        AttributionStatus.valueOf(row.groupStatus()),AttributionStatus.valueOf(row.ownerStatus())));
                    validAttribution.put(row.factId(),row);
                }
            } catch(DataAccessException ex) { attributionFailed=true;reasons.add("ATTRIBUTION_SOURCE_READ_FAILED"); }
        }
        var customers=new ArrayList<Customer>();
        for(var row:current.values()) {
            Fact candidate=first.get(row.customerId());
            FirstCandidate observed=candidate==null?null:new FirstCandidate(candidate.kind().name(),candidate.source().name(),
                candidate.succeededAt().atZone(sourceZone).withZoneSameInstant(ZoneId.of(query.businessZone())).toLocalDateTime(),
                candidate.fractionalSecondDigits(),candidate.amount(),candidate.currency(),
                attribution.getOrDefault(candidate.factId(),unknownAttribution()));
            customers.add(customer(row,new FirstSelection(observed,Status.UNKNOWN,
                List.of(candidate==null?"HISTORY_UNVERIFIED":"COMPLETE_HISTORY_NOT_PROVEN"))));
        }
        var selected=new ArrayList<Fact>();
        var selectedFirst=new ArrayList<Fact>();
        boolean periodGap=eventFailed || attributionFailed;
        if(query.basis()==Basis.PERIOD_EVENT && !global(scope))
            for(var entry:eventIds.entrySet()) {
                Fact fact=facts.get(entry.getKey());
                if(fact==null || !authorizedEvent(fact,scope,eventIds,validAttribution))periodGap=true;
            }
        for(Fact fact:facts.values()) {
            boolean eligible=query.basis()==Basis.CURRENT_CUSTOMER_HISTORY?current.containsKey(fact.customerId())
                :global(scope)?legacyIds.contains(fact.customerId()) || current.containsKey(fact.customerId()) || authorizedEvent(fact,scope,eventIds,validAttribution)
                    :authorizedEvent(fact,scope,eventIds,validAttribution);
            if(!eligible || !matches(query,fact,sourceZone))continue;
            selected.add(fact);
            if(first.get(fact.customerId())==fact)selectedFirst.add(fact);
        }
        if(periodGap)reasons.add("PERIOD_EVENT_COVERAGE_UNVERIFIED");
        boolean unavailable=(query.basis()==Basis.CURRENT_CUSTOMER_HISTORY?currentFailed:eventFailed) && selected.isEmpty();
        Status financialStatus=unavailable?Status.FAILED:snapshot==null || periodGap && selected.isEmpty()?Status.UNKNOWN:Status.PARTIAL;
        var currencies=new TreeSet<String>();
        if(query.currency()!=null)currencies.add(query.currency());
        else for(Fact fact:facts.values())currencies.add(fact.currency());
        var totals=new ArrayList<CurrencyTotals>();
        for(String currency:currencies)totals.add(new CurrencyTotals(currency,
            money(selected,snapshot,Kind.DEPOSIT,currency,unavailable,periodGap),
            money(selected,snapshot,Kind.DEVICE_PURCHASE,currency,unavailable,periodGap),
            money(selected,snapshot,Kind.DEVICE_PURCHASE_REFUND,currency,unavailable,periodGap),
            new Money(null,null,null,null,null,Status.UNKNOWN,List.of("COMPLETE_NET_NOT_PROVEN"))));
        long restrictedCustomers=selected.stream().map(Fact::customerId).filter(id -> !current.containsKey(id)).distinct().count();
        long restrictedFirst=selectedFirst.stream().filter(f -> !current.containsKey(f.customerId())).count();
        var coverage=new ArrayList<SourceCoverage>();
        if(snapshot!=null)for(var c:snapshot.coverage())coverage.add(new SourceCoverage(c.source().name(),c.observedStatus().name(),
            c.historyStatus().name(),c.refundStatus().name(),c.historicalEnvironmentStatus().name(),c.supportedFrom(),c.adapterVersion()));
        reasons.add("COMPLETE_HISTORY_NOT_PROVEN");
        var summary=new FinancialSummary(financialStatus,totals,new Count((long)selectedFirst.size(),null,Status.UNKNOWN),
            partitions(selected,attribution),List.copyOf(reasons));
        return new Result(query,currentScope,customers,summary,restricted(restrictedCustomers,restrictedFirst,financialStatus),
            coverage,snapshot==null?Instant.now():snapshot.evaluatedAt(),List.copyOf(reasons));
    }

    private static Customer customer(SupportAnalyticsMapper.CurrentCustomer row,FirstSelection first) {
        return new Customer(row.customerId(),Category.valueOf(row.category()),Placement.valueOf(row.placement()),row.handoverRequired()!=0,first);
    }
    private static CurrentScope currentScope(ReadScope scope,Collection<SupportAnalyticsMapper.CurrentCustomer> rows,boolean failed) {
        var placements=new ArrayList<PlacementCount>();
        for(Placement p:Placement.values())placements.add(new PlacementCount(p,rows.stream().filter(r -> p.name().equals(r.placement())).count()));
        return new CurrentScope(scope.mode(),scope.requestedGroupId(),scope.requestedAgentId(),failed?Status.FAILED:Status.AVAILABLE,
            failed?null:(long)rows.size(),failed?null:rows.stream().filter(r -> "BOUND".equals(r.category())).count(),
            failed?null:rows.stream().filter(r -> "PENDING".equals(r.category())).count(),
            failed?null:rows.stream().filter(r -> "ANOMALY".equals(r.category())).count(),failed?List.of():placements);
    }
    private static boolean global(ReadScope scope) {return scope.mode()==ReadMode.ALL && scope.requestedGroupId()==null && scope.requestedAgentId()==null;}
    private static Attribution unknownAttribution() {return new Attribution(AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN,AttributionStatus.UNKNOWN);}
    private static boolean matches(Query query,Fact fact,ZoneId sourceZone) {
        if(query.currency()!=null && !query.currency().equals(fact.currency()))return false;
        if(query.basis()!=Basis.PERIOD_EVENT)return true;
        Instant at=fact.succeededAt().atZone(sourceZone).toInstant();ZoneId zone=ZoneId.of(query.businessZone());
        return !at.isBefore(query.fromInclusive().atZone(zone).toInstant()) && at.isBefore(query.toExclusive().atZone(zone).toInstant());
    }
    private static boolean authorizedEvent(Fact fact,ReadScope scope,Map<String,Long> events,Map<String,AttributionRow> rows) {
        AttributionRow row=rows.get(fact.factId());
        if(row==null || !Objects.equals(events.get(fact.factId()),fact.customerId()))return false;
        if(scope.requestedAgentId()!=null && !("KNOWN".equals(row.agentStatus()) && scope.requestedAgentId().equals(row.agentAdminId())))return false;
        if(scope.requestedGroupId()!=null && !("KNOWN".equals(row.groupStatus()) && scope.requestedGroupId().equals(row.groupId())))return false;
        return switch(scope.mode()) {
            case PERSONAL -> "KNOWN".equals(row.agentStatus()) && scope.actorId().equals(row.agentAdminId());
            case MANAGED -> "KNOWN".equals(row.groupStatus()); // The event SQL independently rechecks the current group owner.
            case ALL -> true;
        };
    }
    private static boolean aligned(AttributionRow r,Fact f,String sourceZone) {
        return Objects.equals(r.customerId(),f.customerId()) && Objects.equals(r.kind(),f.kind().name()) && Objects.equals(r.source(),f.source().name())
            && Objects.equals(r.ledgerId(),f.ledgerId()) && Objects.equals(r.sourceBusinessId(),f.sourceBusinessId())
            && Objects.equals(r.orderNo(),f.orderNo()) && Objects.equals(r.orderType(),f.orderType()) && Objects.equals(r.originalFactId(),f.originalFactId())
            && Objects.equals(r.currency(),f.currency()) && r.amount()!=null && r.amount().compareTo(f.amount())==0
            && Objects.equals(r.succeededAt(),f.succeededAt()) && Objects.equals(r.sourceBusinessZone(),sourceZone)
            && Objects.equals(r.successTimeField(),f.successTimeField()) && Objects.equals(r.fractionalSecondDigits(),f.fractionalSecondDigits());
    }
    private static boolean validLayers(AttributionRow row) {
        if(!"support-payment-attribution-v1".equals(row.captureSchemaVersion())
                || !("NEW_SUCCESS".equals(row.captureMode()) || "OLD_SOURCE".equals(row.captureMode())))return false;
        if(!layer(row.agentStatus(),row.agentAdminId()) || !layer(row.groupStatus(),row.groupId()) || !layer(row.ownerStatus(),row.ownerAdminId()))return false;
        if("KNOWN".equals(row.ownerStatus()) && !"KNOWN".equals(row.groupStatus()))return false;
        return !"OLD_SOURCE".equals(row.captureMode()) || "UNKNOWN".equals(row.agentStatus()) && "UNKNOWN".equals(row.groupStatus()) && "UNKNOWN".equals(row.ownerStatus());
    }
    private static boolean layer(String status,Long id) {
        return "KNOWN".equals(status)?id!=null && id>0:("UNASSIGNED".equals(status) || "UNKNOWN".equals(status)) && id==null;
    }
    private static boolean proofRejected(Snapshot snapshot,Fact fact) {
        // Capture readers identify a canonical fact; legacy reconciliation identifies an original
        // source row. A null ID reports a failure of this source for the whole requested scope.
        return snapshot.issues().stream().anyMatch(i -> i.source()==fact.source()
            && (i.sourceId()==null || Objects.equals(i.sourceId(),fact.factId()) || fact.sourceIds().contains(i.sourceId()))
            && Set.of("INVALID_PERSISTED_SOURCE_PROOF","CAPTURED_SOURCE_PROOF_MISMATCH",
                "CONFLICTING_CAPTURED_SOURCE_PROJECTION","SOURCE_READ_FAILED","MISSING_SETTLEMENT_LEDGER",
                "MISSING_AUTHORITATIVE_SOURCE","MISSING_INTENT_IDENTITY","MISSING_CARD_SETTLEMENT",
                "MISSING_SOURCE_CONFIRMATION_TIME").contains(Objects.toString(i.reason(),"")));
    }
    private static boolean sameFinancial(Fact a,Fact b) {
        return a.customerId()==b.customerId() && a.kind()==b.kind() && a.source()==b.source() && a.ledgerId()==b.ledgerId()
            && Objects.equals(a.sourceBusinessId(),b.sourceBusinessId()) && Objects.equals(a.orderNo(),b.orderNo())
            && Objects.equals(a.orderType(),b.orderType()) && Objects.equals(a.originalFactId(),b.originalFactId())
            && Objects.equals(a.currency(),b.currency()) && a.amount().compareTo(b.amount())==0 && Objects.equals(a.succeededAt(),b.succeededAt())
            && Objects.equals(a.successTimeField(),b.successTimeField()) && a.fractionalSecondDigits()==b.fractionalSecondDigits();
    }
    private static Money money(List<Fact> selected,Snapshot snapshot,Kind kind,String currency,boolean unavailable,boolean periodGap) {
        var rows=selected.stream().filter(f -> f.kind()==kind && f.currency().equals(currency)).toList();
        boolean failed=unavailable || snapshot!=null && rows.isEmpty() && snapshot.issues().stream().anyMatch(i -> family(i.source())==kind && "SOURCE_READ_FAILED".equals(i.reason()));
        boolean unknown=snapshot==null || periodGap && rows.isEmpty();
        if(failed || unknown)return new Money(null,null,null,null,null,failed?Status.FAILED:Status.UNKNOWN,
            List.of(failed?"SOURCE_READ_FAILED":"SOURCE_COVERAGE_UNVERIFIED"));
        BigDecimal amount=rows.stream().map(Fact::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        return new Money(amount,null,(long)rows.size(),null,rows.stream().map(Fact::customerId).distinct().count(),Status.PARTIAL,
            List.of("COMPLETE_HISTORY_NOT_PROVEN","COMPLETE_REFUNDS_NOT_PROVEN","HISTORICAL_ENVIRONMENT_UNVERIFIED"));
    }
    private static Kind family(SupportPaymentFacts.Source source) {
        if(source==null)return null;
        return switch(source) {
            case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY -> Kind.DEPOSIT;
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT -> Kind.DEVICE_PURCHASE;
            case ORDER_REFUND -> Kind.DEVICE_PURCHASE_REFUND;default -> null;
        };
    }
    private static List<AttributionPartition> partitions(List<Fact> facts,Map<String,Attribution> attributions) {
        var result=new ArrayList<AttributionPartition>();
        for(String layer:List.of("AGENT","GROUP","OWNER"))for(AttributionStatus status:AttributionStatus.values()) {
            long count=0;
            for(Fact f:facts) {
                Attribution a=attributions.getOrDefault(f.factId(),unknownAttribution());
                AttributionStatus actual=switch(layer) {case "AGENT" -> a.agent();case "GROUP" -> a.group();default -> a.owner();};
                if(actual==status)count++;
            }
            result.add(new AttributionPartition(layer,status,count));
        }
        return result;
    }
    private static RestrictedSummary restricted(long customers,long first,Status status) {
        return new RestrictedSummary(new Count(status==Status.FAILED?null:customers,null,status),new Count(status==Status.FAILED?null:first,null,Status.UNKNOWN));
    }
    private static FinancialSummary unavailableFinancial() {
        return new FinancialSummary(Status.UNAVAILABLE,List.of(),new Count(null,null,Status.UNAVAILABLE),List.of(),List.of("CURRENT_ASSET_FINANCE_NOT_REQUESTED"));
    }
    private static IllegalStateException invalid(String code) {return new IllegalStateException(code);}
}
