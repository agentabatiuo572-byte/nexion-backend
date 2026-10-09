package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import static ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Snapshot;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal statistics only. Authorization is current; event attribution never comes from current membership. */
@ApplicationService
public class SupportAnalyticsService {
    private final SupportOwnershipService ownership;
    private final FinanceSupportPaymentFactsFacade finance;
    private final SupportAnalyticsMapper mapper;
    private final SupportInvitationReadFacade invitations;
    private final SupportDeviceReadFacade devices;

    /** Legacy callers do not gain newly requested metrics from absent dependencies. */
    public SupportAnalyticsService(SupportOwnershipService ownership,FinanceSupportPaymentFactsFacade finance,SupportAnalyticsMapper mapper) {
        this.ownership=Objects.requireNonNull(ownership);this.finance=Objects.requireNonNull(finance);this.mapper=Objects.requireNonNull(mapper);
        this.invitations=null;this.devices=null;
    }
    @Autowired
    public SupportAnalyticsService(SupportOwnershipService ownership,FinanceSupportPaymentFactsFacade finance,SupportAnalyticsMapper mapper,
            SupportInvitationReadFacade invitations,SupportDeviceReadFacade devices) {
        this.ownership=Objects.requireNonNull(ownership);this.finance=Objects.requireNonNull(finance);this.mapper=Objects.requireNonNull(mapper);
        this.invitations=Objects.requireNonNull(invitations);this.devices=Objects.requireNonNull(devices);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Result summarize(Query query) {
        return evaluate(query,resolveScope(query),null);
    }

    private ReadScope resolveScope(Query query) {
        if(query==null)throw new IllegalArgumentException("SUPPORT_ANALYTICS_QUERY_INVALID");
        ReadScope scope=query.mode()==null?ownership.defaultQueryScope(query.groupId(),query.agentId())
            :ownership.queryScope(query.mode(),query.groupId(),query.agentId());
        if(scope==null || query.mode()!=null && scope.mode()!=query.mode()
                || !Objects.equals(query.groupId(),scope.requestedGroupId()) || !Objects.equals(query.agentId(),scope.requestedAgentId()))
            throw invalid("SUPPORT_ANALYTICS_SCOPE_INVALID");
        return scope;
    }

    /** Private caller owns the one RR transaction; this method never re-reads a separate card response. */
    @Transactional(readOnly=true,propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public QueryEvaluation evaluateForQuery(Query query,ReadScope authorizedScope,boolean supervisorDirectory) {
        if(invitations==null || devices==null)throw new ffdd.opsconsole.shared.exception.BizException(503,"SUPPORT_ANALYTICS_READER_UNAVAILABLE");
        if(query==null || authorizedScope==null || query.mode()!=authorizedScope.mode() || !Objects.equals(query.groupId(),authorizedScope.requestedGroupId())
                || !Objects.equals(query.agentId(),authorizedScope.requestedAgentId()))throw invalid("SUPPORT_ANALYTICS_SCOPE_INVALID");
        var capture=new QueryCapture();capture.supervisorDirectory=supervisorDirectory;
        var result=evaluate(query,authorizedScope,capture);
        return new QueryEvaluation(result,capture);
    }

    public record QueryEvaluation(Result result,QueryCapture evidence) { }
    /** Internal data, never HTTP serialized; filled only by the actual evaluation paths below. */
    public static final class QueryCapture {
        public ReadScope scope;
        public Map<Long,SupportAnalyticsMapper.CurrentCustomer> current=Map.of();
        public Map<String,Long> eventIds=Map.of();
        public Set<Long> financialIds=Set.of(),legacyIds=Set.of();
        public List<Fact> selected=List.of();
        public Snapshot snapshot,teamSnapshot;
        public Map<String,Fact> facts=Map.of(),teamFacts=Map.of();
        public Map<Long,Fact> first=Map.of();
        public Map<Long,SupportPaymentFacts.FirstHistory> firstHistory=Map.of();
        public List<AttributionRow> rawAttributions=List.of();
        public Map<String,AttributionRow> validAttributions=Map.of();
        public Map<Long,SupportInvitationReadFacade.Invitation> trees=Map.of();
        public SupportDeviceReadFacade.Snapshot stock;
        public ActivityWindow activityWindow;
        public SupportAnalyticsMapper.ActivityCoverage activityCoverage;
        public Map<Long,CustomerActivity> activities=Map.of();
        public List<AccountRow> accounts=List.of();
        public boolean currentFailed,eventFailed,attributionFailed,deviceFailed,supervisorDirectory;
        public Status rosterStatus=Status.UNKNOWN;
    }

    /** Reuses evaluated facts for the filtered current-root aggregate; performs no reader call. */
    public CurrentMetrics selectedCurrent(QueryEvaluation evaluation,List<Customer> selected) {
        var c=evaluation.evidence();
        if(selected.stream().anyMatch(row->!c.current.containsKey(row.customerId())))throw invalid("SUPPORT_ANALYTICS_SCOPE_INVALID");
        return currentMetrics(evaluation.result().query(),selected,c.facts.values(),c.snapshot,c.stock,
            new ActivityRead(c.activityWindow,c.activities),c.currentFailed);
    }
    private Result evaluate(Query query,ReadScope scope,QueryCapture capture) {
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
                if(invitations!=null)validateCurrentOwner(row,scope);
                if("UNKNOWN".equals(row.placement()))reasons.add("CURRENT_PLACEMENT_UNVERIFIED");
                var previous=current.putIfAbsent(row.customerId(),row);
                if(previous!=null && !previous.equals(row))throw invalid("SUPPORT_ANALYTICS_CURRENT_CONFLICT");
            }
        } catch(DataAccessException ex) { currentFailed=true;reasons.add("CURRENT_SOURCE_READ_FAILED"); }
        var currentScope=currentScope(scope,current.values(),currentFailed);
        if(capture!=null){capture.scope=scope;capture.current=Map.copyOf(current);capture.currentFailed=currentFailed;}
        if(query.basis()==Basis.CURRENT_ASSET && invitations==null) {
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
        if(capture!=null){capture.eventIds=Map.copyOf(eventIds);capture.financialIds=Set.copyOf(ids);capture.legacyIds=Set.copyOf(legacyIds);capture.eventFailed=eventFailed;}
        // The finance façade owns source eligibility, logical-payment dedup and refund lineage/caps.
        // Unexpected façade exceptions propagate; catching a transactional proxy could conceal rollback-only.
        Snapshot snapshot=ids.isEmpty()?null:finance.readHistory(List.copyOf(ids));
        if(!ids.isEmpty() && snapshot==null)throw invalid("SUPPORT_ANALYTICS_FINANCIAL_RESPONSE_MISSING");
        var facts=new TreeMap<String,Fact>();
        var first=new TreeMap<Long,Fact>();
        var firstHistory=new TreeMap<Long,SupportPaymentFacts.FirstHistory>();
        ZoneId sourceZone=null;
        if(snapshot!=null) {
            try { sourceZone=ZoneId.of(snapshot.businessZone()); }
            catch(RuntimeException ex) { throw invalid("SUPPORT_ANALYTICS_FINANCIAL_INVALID"); }
            for(var history:snapshot.firstHistory()) {
                if(history==null || !ids.contains(history.customerId()) || history.status()==null
                        || history.reasons().stream().anyMatch(r -> r==null || r.isBlank())
                        || history.status()==SupportPaymentFacts.Status.READY && !history.reasons().isEmpty()
                        || history.status()==SupportPaymentFacts.Status.UNKNOWN && history.reasons().isEmpty()
                        || firstHistory.putIfAbsent(history.customerId(),history)!=null)
                    throw invalid("SUPPORT_ANALYTICS_FIRST_HISTORY_INVALID");
            }
            for(Fact fact:snapshot.facts()) {
                if(fact==null || !ids.contains(fact.customerId()))throw invalid("SUPPORT_ANALYTICS_FINANCIAL_SCOPE_INVALID");
                if(SupportPaymentFacts.validateCanonical(fact,snapshot.businessZone())!=null)throw invalid("SUPPORT_ANALYTICS_FINANCIAL_INVALID");
                Fact previous=facts.putIfAbsent(fact.factId(),fact);
                if(previous!=null && !sameFinancial(previous,fact))throw invalid("SUPPORT_ANALYTICS_FINANCIAL_CONFLICT");
            }
            first.putAll(selectFirstFacts(facts.values()));
            for(var issue:snapshot.issues())reasons.add("FINANCIAL_SOURCE_UNVERIFIED");
        } else reasons.add("NO_OBSERVED_FINANCIAL_SCOPE");
        if(invitations!=null)validateSnapshotBoundary(snapshot,ids);
        if(capture!=null){capture.snapshot=snapshot;capture.facts=Map.copyOf(facts);capture.first=Map.copyOf(first);capture.firstHistory=Map.copyOf(firstHistory);}
        if(query.basis()==Basis.CURRENT_ASSET && capture==null) {
            var customers=current.values().stream().map(row->customer(row,new FirstSelection(null,Status.UNKNOWN,List.of("HISTORY_NOT_REQUESTED")))).toList();
            var extra=enrich(query,scope,current,customers,snapshot,facts,ids,currentFailed,List.of(),List.of(),List.of(),Map.of(),Map.of(),false,null);
            return new Result(query,currentScope,extra.customers(),unavailableFinancial(),restricted(0,0,Status.UNAVAILABLE),List.of(),
                snapshot==null?Instant.now():snapshot.evaluatedAt(),List.copyOf(reasons),extra.current(),extra.personnel(),extra.groups());
        }
        var attribution=new HashMap<String,Attribution>();
        var validAttribution=new HashMap<String,AttributionRow>();
        boolean attributionFailed=false;
        if(!facts.isEmpty()) {
            try {
                var rows=mapper.attributions(scope,List.copyOf(facts.keySet()));
                if(rows==null)throw invalid("SUPPORT_ANALYTICS_ATTRIBUTION_INVALID");
                if(capture!=null)capture.rawAttributions=List.copyOf(rows);
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
        if(capture!=null){capture.validAttributions=Map.copyOf(validAttribution);capture.attributionFailed=attributionFailed;}
        if(query.basis()==Basis.CURRENT_ASSET) {
            var currentCustomers=current.values().stream().map(row->customer(row,new FirstSelection(null,Status.UNKNOWN,List.of("HISTORY_NOT_REQUESTED")))).toList();
            var extra=enrich(query,scope,current,currentCustomers,snapshot,facts,ids,currentFailed,List.of(),List.of(),List.of(),validAttribution,attribution,false,capture);
            return new Result(query,currentScope,extra.customers(),unavailableFinancial(),restricted(0,0,Status.UNAVAILABLE),List.of(),
                snapshot==null?Instant.now():snapshot.evaluatedAt(),List.copyOf(reasons),extra.current(),extra.personnel(),extra.groups());
        }
        var customers=new ArrayList<Customer>();
        for(var row:current.values()) {
            Fact candidate=first.get(row.customerId());
            FirstCandidate observed=candidate==null?null:new FirstCandidate(candidate.kind().name(),candidate.source().name(),
                candidate.succeededAt().atZone(sourceZone).withZoneSameInstant(ZoneId.of(query.businessZone())).toLocalDateTime(),
                candidate.fractionalSecondDigits(),candidate.amount(),candidate.currency(),
                attribution.getOrDefault(candidate.factId(),unknownAttribution()));
            var history=firstHistory.get(row.customerId());
            boolean ready=firstReady(firstHistory,row.customerId());
            customers.add(customer(row,new FirstSelection(observed,ready?Status.AVAILABLE:Status.UNKNOWN,
                ready?List.of():history==null?List.of(candidate==null?"HISTORY_UNVERIFIED":"COMPLETE_HISTORY_NOT_PROVEN"):history.reasons(),
                ready?candidate==null?FirstState.NONE:FirstState.CONFIRMED:FirstState.UNKNOWN)));
        }
        var selected=new ArrayList<Fact>();
        var selectedFirst=new ArrayList<Fact>();
        var confirmedSelectedFirst=new ArrayList<Fact>();
        boolean periodGap=eventFailed || query.basis()==Basis.PERIOD_EVENT && !global(scope) && attributionFailed;
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
            if(first.get(fact.customerId())==fact) {
                selectedFirst.add(fact);
                if(firstReady(firstHistory,fact.customerId()))confirmedSelectedFirst.add(fact);
            }
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
        long restrictedConfirmedFirst=confirmedSelectedFirst.stream().filter(f -> !current.containsKey(f.customerId())).count();
        boolean historyGap=ids.stream().anyMatch(id -> !firstReady(firstHistory,id));
        boolean hasConfirmedHistory=ids.stream().anyMatch(id -> firstReady(firstHistory,id));
        boolean firstFailed=!hasConfirmedHistory && (unavailable || selectedFirst.isEmpty() && snapshot!=null && snapshot.issues().stream().anyMatch(i ->
            "SOURCE_READ_FAILED".equals(i.reason()) && i.source()!=SupportPaymentFacts.Source.ORDER_REFUND
                && i.source()!=SupportPaymentFacts.Source.FREE_TRIAL));
        Status firstStatus=firstFailed?Status.FAILED:snapshot==null?Status.UNKNOWN:
            periodGap || currentFailed || historyGap?(hasConfirmedHistory?Status.PARTIAL:Status.UNKNOWN):Status.AVAILABLE;
        var firstTotals=new ArrayList<FirstCurrencyTotals>();
        for(String currency:currencies)firstTotals.add(new FirstCurrencyTotals(currency,
            firstMoney(selectedFirst,confirmedSelectedFirst,Kind.DEPOSIT,currency,firstStatus,hasConfirmedHistory),
            firstMoney(selectedFirst,confirmedSelectedFirst,Kind.DEVICE_PURCHASE,currency,firstStatus,hasConfirmedHistory)));
        var coverage=new ArrayList<SourceCoverage>();
        if(snapshot!=null)for(var c:snapshot.coverage())coverage.add(new SourceCoverage(c.source().name(),c.observedStatus().name(),
            c.historyStatus().name(),c.refundStatus().name(),c.historicalEnvironmentStatus().name(),c.supportedFrom(),c.adapterVersion()));
        if(historyGap || snapshot==null)reasons.add("COMPLETE_HISTORY_NOT_PROVEN");
        var summary=new FinancialSummary(financialStatus,totals,
            new Count(firstFailed?null:(long)selectedFirst.size(),firstFailed || !hasConfirmedHistory?null:(long)confirmedSelectedFirst.size(),firstStatus),
            partitions(selected,attribution),List.copyOf(reasons),firstTotals);
        var restricted=new RestrictedSummary(new Count(financialStatus==Status.FAILED?null:restrictedCustomers,null,financialStatus),
            new Count(firstFailed?null:restrictedFirst,firstFailed || !hasConfirmedHistory?null:restrictedConfirmedFirst,firstStatus));
        if(invitations==null)return new Result(query,currentScope,customers,summary,restricted,
            coverage,snapshot==null?Instant.now():snapshot.evaluatedAt(),List.copyOf(reasons));
        if(capture!=null)capture.selected=List.copyOf(selected);
        var extra=enrich(query,scope,current,customers,snapshot,facts,ids,currentFailed,selected,selectedFirst,confirmedSelectedFirst,
            validAttribution,attribution,periodGap,capture);
        return new Result(query,currentScope,extra.customers(),summary,restricted,coverage,
            snapshot==null?Instant.now():snapshot.evaluatedAt(),List.copyOf(reasons),extra.current(),extra.personnel(),extra.groups());
    }

    private record Enrichment(List<Customer> customers,CurrentMetrics current,PersonnelSummary personnel,List<GroupAggregate> groups) { }
    private record ActivityRead(ActivityWindow window,Map<Long,CustomerActivity> customers) { }
    private record RosterRead(List<AccountRow> accounts,List<SupportAnalyticsMapper.GroupRow> groups,Status status,boolean supervisorDirectory) { }

    private Enrichment enrich(Query query,ReadScope scope,Map<Long,SupportAnalyticsMapper.CurrentCustomer> current,List<Customer> base,
            Snapshot snapshot,Map<String,Fact> ownFacts,Set<Long> financialIds,boolean currentFailed,List<Fact> selected,
            List<Fact> selectedFirst,List<Fact> confirmedFirst,Map<String,AttributionRow> saved,Map<String,Attribution> attribution,boolean periodGap,QueryCapture capture) {
        var roots=new TreeSet<>(current.keySet());
        var trees=new TreeMap<Long,SupportInvitationReadFacade.Invitation>();
        if(!roots.isEmpty()) {
            var rows=invitations.readInvitations(List.copyOf(roots));
            if(rows==null)throw invalid("SUPPORT_ANALYTICS_INVITATION_INVALID");
            for(var row:rows) {
                if(row==null || !roots.contains(row.rootCustomerId()))throw invalid("SUPPORT_ANALYTICS_INVITATION_SCOPE_INVALID");
                if(row.completeness()==null || row.rootSandbox()!=null && row.rootSandbox()!=0
                        || row.completeness()==SupportInvitationReadFacade.Completeness.COMPLETE && row.rootSandbox()==null
                        || row.rootSandbox()==null && !row.descendantCustomerIds().isEmpty()
                        || row.completeness()==SupportInvitationReadFacade.Completeness.COMPLETE && !row.reasons().isEmpty()
                        || row.directCustomerIds().stream().anyMatch(id->id==null || id<=0 || id==row.rootCustomerId())
                        || row.descendantCustomerIds().stream().anyMatch(id->id==null || id<=0 || id==row.rootCustomerId())
                        || !new HashSet<>(row.descendantCustomerIds()).containsAll(row.directCustomerIds())
                        || new HashSet<>(row.directCustomerIds()).size()!=row.directCustomerIds().size()
                        || new HashSet<>(row.descendantCustomerIds()).size()!=row.descendantCustomerIds().size()
                        || trees.putIfAbsent(row.rootCustomerId(),row)!=null)
                    throw invalid("SUPPORT_ANALYTICS_INVITATION_INVALID");
            }
        }
        // Descendant finance stays in a separate map and never enters first, attribution, current identities or group money.
        var teamFacts=new TreeMap<>(ownFacts);var extraIds=new TreeSet<Long>();
        for(var tree:trees.values())extraIds.addAll(tree.descendantCustomerIds());
        extraIds.removeAll(financialIds);
        Snapshot teamSnapshot=null;
        if(query.basis()!=Basis.CURRENT_ASSET && !extraIds.isEmpty()) {
            try {teamSnapshot=finance.readHistory(List.copyOf(extraIds));}
            catch(DataAccessException ex) {throw new IllegalStateException("SUPPORT_ANALYTICS_DESCENDANT_SOURCE_READ_FAILED",ex);}
            if(teamSnapshot==null)throw invalid("SUPPORT_ANALYTICS_FINANCIAL_RESPONSE_MISSING");
            validateSnapshotBoundary(teamSnapshot,extraIds);
            for(var f:teamSnapshot.facts()) {
                if(SupportPaymentFacts.validateCanonical(f,teamSnapshot.businessZone())!=null)throw invalid("SUPPORT_ANALYTICS_FINANCIAL_INVALID");
                var prior=teamFacts.putIfAbsent(f.factId(),f);
                if(prior!=null && !sameFinancial(prior,f))throw invalid("SUPPORT_ANALYTICS_FINANCIAL_CONFLICT");
            }
        }
        SupportDeviceReadFacade.Snapshot stock=null;boolean deviceFailed=false;
        try {stock=roots.isEmpty()?new SupportDeviceReadFacade.Snapshot(List.of(),List.of(),null):devices.readCurrent(List.copyOf(roots));}
        catch(DataAccessException ex) {deviceFailed=true;}
        if(!deviceFailed) {
            if(stock==null)throw invalid("SUPPORT_ANALYTICS_DEVICE_RESPONSE_MISSING");
            validateDevices(stock,roots);
        }
        var activity=readActivity(scope,roots,currentFailed,capture);
        var roster=readRoster(scope,capture);
        if(capture!=null){capture.trees=Map.copyOf(trees);capture.teamSnapshot=teamSnapshot;capture.teamFacts=Map.copyOf(teamFacts);
            capture.stock=stock;capture.deviceFailed=deviceFailed;capture.activityWindow=activity.window();capture.activities=Map.copyOf(activity.customers());
            capture.accounts=List.copyOf(roster.accounts());capture.rosterStatus=roster.status();}
        if(scope.mode()==ReadMode.MANAGED && roster.status()==Status.AVAILABLE
                && current.values().stream().anyMatch(r->roster.groups().stream().noneMatch(g->Objects.equals(g.id(),r.currentGroupId()))))
            throw invalid("SUPPORT_ANALYTICS_CURRENT_OWNER_INVALID");
        var customers=new ArrayList<Customer>();
        for(var old:base) {
            long root=old.customerId();var row=current.get(root);var tree=trees.get(root);
            var money=lifetime(ownFacts.values(),Set.of(root),query.currency(),snapshot,currentFailed,query.basis()==Basis.CURRENT_ASSET);
            Status lifetimeStatus=query.basis()==Basis.CURRENT_ASSET?Status.UNAVAILABLE:money.stream().anyMatch(c->c.deposits().status()==Status.PARTIAL || c.purchases().status()==Status.PARTIAL)?Status.PARTIAL:
                money.stream().anyMatch(c->c.deposits().status()==Status.FAILED || c.purchases().status()==Status.FAILED)?Status.FAILED:Status.UNKNOWN;
            var metric=new CustomerMetrics(Basis.CURRENT_CUSTOMER_HISTORY,lifetimeStatus,money,
                invitationSummary(tree,query,teamFacts.values(),snapshot,teamSnapshot,financialIds),
                deviceSummary(stock,Set.of(root),ownFacts.values(),snapshot,false),activity.customers().get(root));
            customers.add(new Customer(root,old.category(),old.placement(),old.handoverRequired(),old.first(),
                new CurrentOwner(row.ownerAgentId(),row.currentGroupId()),metric));
        }
        var aggregate=currentMetrics(query,customers,ownFacts.values(),snapshot,stock,activity,currentFailed);
        var groups=new ArrayList<GroupAggregate>();
        for(var group:roster.groups()) {
            var groupCustomers=customers.stream().filter(c->Objects.equals(c.owner().groupId(),group.id())).toList();
            var groupRows=current.values().stream().filter(c->Objects.equals(c.currentGroupId(),group.id())).toList();
            var groupScope=new ReadScope(scope.actorId(),scope.mode(),group.id(),scope.requestedAgentId());
            FinancialSummary period=query.basis()==Basis.PERIOD_EVENT?
                groupPeriod(query,group.id(),ownFacts.values(),selected,selectedFirst,confirmedFirst,snapshot,saved,attribution,periodGap):
                new FinancialSummary(Status.UNAVAILABLE,List.of(),unavailableCount(),List.of(),List.of("PERIOD_EVENT_NOT_REQUESTED"));
            groups.add(new GroupAggregate(group.id(),currentScope(groupScope,groupRows,currentFailed),
                currentMetrics(query,groupCustomers,ownFacts.values(),snapshot,stock,activity,currentFailed),period,
                personnel(roster,group.id(),scope)));
        }
        return new Enrichment(customers,aggregate,personnel(roster,null,scope),groups);
    }

    private static void validateCurrentOwner(SupportAnalyticsMapper.CurrentCustomer row,ReadScope scope) {
        boolean bound="BOUND".equals(row.category()),grouped=Set.of("GROUPED","GROUP_QUEUE").contains(row.placement());
        if(row.ownerAgentId()!=null && (!bound || row.ownerAgentId()<=0)
                || row.currentGroupId()!=null && (!grouped || row.currentGroupId()<=0)
                || bound && row.ownerAgentId()==null || grouped && row.currentGroupId()==null
                || scope.mode()==ReadMode.PERSONAL && (!bound || !scope.actorId().equals(row.ownerAgentId()))
                || scope.mode()==ReadMode.MANAGED && !grouped
                || scope.requestedGroupId()!=null && !scope.requestedGroupId().equals(row.currentGroupId())
                || scope.requestedAgentId()!=null && !scope.requestedAgentId().equals(row.ownerAgentId()))
            throw invalid("SUPPORT_ANALYTICS_CURRENT_OWNER_INVALID");
    }
    static void validateSnapshotBoundary(Snapshot snapshot,Set<Long> ids) {
        if(snapshot==null)return;
        try {ZoneId.of(snapshot.businessZone());}catch(RuntimeException ex){throw invalid("SUPPORT_ANALYTICS_FINANCIAL_INVALID");}
        if(snapshot.evaluatedAt()==null || snapshot.facts().stream().anyMatch(f->f==null || !ids.contains(f.customerId()))
                || snapshot.firstHistory().stream().anyMatch(h->h==null || !ids.contains(h.customerId()) || h.status()==null)
                || snapshot.issues().stream().anyMatch(i->i==null || i.customerId()!=null && !ids.contains(i.customerId())))
            throw invalid("SUPPORT_ANALYTICS_FINANCIAL_SCOPE_INVALID");
    }
    private static List<CustomerCurrencyTotals> lifetime(Collection<Fact> facts,Set<Long> roots,String selectedCurrency,
            Snapshot snapshot,boolean failed,boolean notRequested) {
        var own=facts.stream().filter(f->roots.contains(f.customerId())).toList();
        var currencies=new TreeSet<String>();
        if(selectedCurrency!=null)currencies.add(selectedCurrency);else own.forEach(f->currencies.add(f.currency()));
        var result=new ArrayList<CustomerCurrencyTotals>();
        for(String currency:currencies)result.add(new CustomerCurrencyTotals(currency,
            lifetimeMoney(own,snapshot,Kind.DEPOSIT,currency,roots,failed,notRequested),
            lifetimeMoney(own,snapshot,Kind.DEVICE_PURCHASE,currency,roots,failed,notRequested)));
        return result;
    }
    private static Money lifetimeMoney(List<Fact> facts,Snapshot snapshot,Kind kind,String currency,Set<Long> subjects,boolean failed,boolean notRequested) {
        if(notRequested)return new Money(null,null,null,null,null,Status.UNAVAILABLE,List.of("HISTORY_NOT_REQUESTED"));
        var rows=facts.stream().filter(f->f.kind()==kind && currency.equals(f.currency())).toList();
        boolean readFailed=snapshot!=null && snapshot.issues().stream().anyMatch(i->"SOURCE_READ_FAILED".equals(i.reason())
            && (i.source()==null || family(i.source())==kind) && (i.customerId()==null || subjects.contains(i.customerId())));
        if(failed || readFailed && rows.isEmpty())return new Money(null,null,null,null,null,Status.FAILED,List.of("SOURCE_READ_FAILED"));
        if(rows.isEmpty())return new Money(null,null,null,null,null,Status.UNKNOWN,List.of("COMPLETE_HISTORY_NOT_PROVEN"));
        return new Money(rows.stream().map(Fact::amount).reduce(BigDecimal.ZERO,BigDecimal::add),null,(long)rows.size(),null,
            rows.stream().map(Fact::customerId).distinct().count(),Status.PARTIAL,List.of("COMPLETE_HISTORY_NOT_PROVEN"));
    }
    private static InvitationSummary invitationSummary(SupportInvitationReadFacade.Invitation tree,Query query,Collection<Fact> facts,
            Snapshot rootSnapshot,Snapshot extraSnapshot,Set<Long> rootFinancialIds) {
        if(tree==null)return new InvitationSummary(new Count(null,null,Status.UNKNOWN),new Count(null,null,Status.UNKNOWN),List.of(),Status.UNKNOWN,List.of("INVITATION_SOURCE_UNVERIFIED"));
        Status state=switch(tree.completeness()){case COMPLETE->Status.AVAILABLE;case PARTIAL->Status.PARTIAL;case UNKNOWN->Status.UNKNOWN;case FAILED->Status.FAILED;};
        var descendants=new HashSet<>(tree.descendantCustomerIds());var rows=facts.stream().filter(f->descendants.contains(f.customerId()) && f.kind()==Kind.DEPOSIT).toList();
        var currencies=new TreeSet<String>();if(query.currency()!=null)currencies.add(query.currency());else rows.forEach(f->currencies.add(f.currency()));
        var totals=new ArrayList<InvitationCurrencyTotal>();
        for(String currency:currencies) {
            Money value=lifetimeMoney(rows,descendants.stream().anyMatch(rootFinancialIds::contains)?rootSnapshot:null,Kind.DEPOSIT,currency,descendants,state==Status.FAILED,query.basis()==Basis.CURRENT_ASSET);
            boolean extraFailed=extraSnapshot!=null && extraSnapshot.issues().stream().anyMatch(i->"SOURCE_READ_FAILED".equals(i.reason()) && (i.source()==null || family(i.source())==Kind.DEPOSIT)
                && (i.customerId()==null?descendants.stream().anyMatch(id->!rootFinancialIds.contains(id)):descendants.contains(i.customerId())));
            if(extraFailed && value.observedAmount()==null)value=new Money(null,null,null,null,null,Status.FAILED,List.of("SOURCE_READ_FAILED"));
            if(state==Status.UNKNOWN && value.status()!=Status.UNAVAILABLE && value.status()!=Status.FAILED)
                value=new Money(value.observedAmount(),null,value.observedEvents(),null,value.observedCustomers(),Status.UNKNOWN,List.of("INVITATION_SOURCE_UNVERIFIED"));
            totals.add(new InvitationCurrencyTotal(currency,value));
        }
        return new InvitationSummary(invitationCount(tree.directCustomerIds().size(),state),invitationCount(descendants.size(),state),totals,state,
            tree.reasons().stream().map(Enum::name).sorted().toList());
    }
    private static Count invitationCount(long value,Status state) {
        return new Count(state==Status.FAILED?null:value,state==Status.AVAILABLE?value:null,state);
    }
    private static void validateDevices(SupportDeviceReadFacade.Snapshot stock,Set<Long> roots) {
        var all=new ArrayList<>(stock.devices());all.addAll(stock.unknownHoldingDevices());var seen=new HashMap<Long,SupportDeviceReadFacade.DeviceEvidence>();
        for(var device:all) {
            if(device==null || !roots.contains(device.customerId()))throw invalid("SUPPORT_ANALYTICS_DEVICE_SCOPE_INVALID");
            if(device.deviceId()<=0 || device.connectionStatus()==null || device.hashrate()==null || device.hashrate().signum()<0
                    || device.pendingDeactivate()<0 || device.pendingDeactivate()>1)
                throw invalid("SUPPORT_ANALYTICS_DEVICE_INVALID");
            var prior=seen.putIfAbsent(device.deviceId(),device);
            if(prior!=null && !prior.equals(device))throw invalid("SUPPORT_ANALYTICS_DEVICE_CONFLICT");
        }
        var heldIds=new HashSet<Long>();stock.devices().forEach(d->heldIds.add(d.deviceId()));
        if(stock.unknownHoldingDevices().stream().anyMatch(d->heldIds.contains(d.deviceId()))
                || !roots.isEmpty() && stock.evaluatedDbAt()==null)throw invalid("SUPPORT_ANALYTICS_DEVICE_INVALID");
    }
    public static int productionDevice(SupportDeviceReadFacade.DeviceEvidence device) {
        String environment=device.sourceEnvironment();
        if(environment==null || environment.isBlank())return 0;
        if(!"PRODUCTION".equalsIgnoreCase(environment))return -1;
        return device.runId()==null || device.runId().isBlank()?1:0;
    }
    /** Same existing canonical acquisition rule for the internal query adapter and aggregate. */
    public static boolean paidDeviceFact(SupportDeviceReadFacade.DeviceEvidence device,Fact fact,Snapshot snapshot) {
        return !"PROMOTION_GIFT".equalsIgnoreCase(device.sourceChannel())
            && device.sourceOrderNo()!=null && !device.sourceOrderNo().isBlank() && fact.customerId()==device.customerId()
            && fact.kind()==Kind.DEVICE_PURCHASE && Objects.equals(fact.orderNo(),device.sourceOrderNo())
            && snapshot!=null && !proofRejected(snapshot,fact);
    }
    private static DeviceSummary deviceSummary(SupportDeviceReadFacade.Snapshot stock,Set<Long> roots,Collection<Fact> facts,Snapshot snapshot,boolean failed) {
        if(failed || stock==null)return new DeviceSummary(new Count(null,null,Status.FAILED),new Count(null,null,Status.FAILED),List.of(),stock==null?null:stock.evaluatedDbAt(),Status.FAILED,List.of(stock==null?"DEVICE_SOURCE_READ_FAILED":"CURRENT_SOURCE_READ_FAILED"));
        var held=new TreeMap<Long,SupportDeviceReadFacade.DeviceEvidence>();var unknown=new TreeMap<Long,SupportDeviceReadFacade.DeviceEvidence>();
        for(var d:stock.devices())if(roots.contains(d.customerId()) && productionDevice(d)>=0) {
            if(productionDevice(d)==1)held.put(d.deviceId(),d);else unknown.put(d.deviceId(),d);
        }
        for(var d:stock.unknownHoldingDevices())if(roots.contains(d.customerId()) && productionDevice(d)>=0)unknown.put(d.deviceId(),d);
        var partitions=new ArrayList<DevicePartition>();
        for(var state:SupportDeviceReadFacade.ConnectionStatus.values())partitions.add(new DevicePartition("CONNECTION",state.name(),exact(held.values().stream().filter(d->d.connectionStatus()==state).count())));
        // Facts already passed the canonical positive-payment check; history coverage is independent of current acquisition.
        long paid=held.values().stream().filter(d->facts.stream().anyMatch(f->paidDeviceFact(d,f,snapshot))).count();
        partitions.add(new DevicePartition("ACQUISITION",Acquisition.PAID_PURCHASE.name(),exact(paid)));
        partitions.add(new DevicePartition("ACQUISITION",Acquisition.UNKNOWN.name(),exact(held.size()-paid)));
        long unknownEnvironment=unknown.values().stream().filter(d->productionDevice(d)==0).count();
        partitions.add(new DevicePartition("ENVIRONMENT","UNKNOWN",exact(unknownEnvironment)));
        Status state=unknown.isEmpty()?Status.AVAILABLE:Status.PARTIAL;
        return new DeviceSummary(new Count((long)held.size(),unknown.isEmpty()?(long)held.size():null,state),exact(unknown.size()),partitions,
            stock.evaluatedDbAt(),state,held.size()>paid?List.of("ACQUISITION_NOT_PROVEN"):List.of());
    }
    private ActivityRead readActivity(ReadScope scope,Set<Long> roots,boolean currentFailed,QueryCapture capture) {
        ActivityWindow window=unavailableWindow();Status state=Status.UNKNOWN;var latest=new HashMap<Long,LocalDateTime>();
        try {
            var coverage=mapper.activityCoverage(scope);var rules=mapper.activityRules(scope);
            if(capture!=null)capture.activityCoverage=coverage;
            if(coverage!=null && rules!=null && rules.version()!=null && rules.version()>0 && rules.activityWindowDays()!=null && rules.activityWindowDays()>0
                    && coverage.coverageStartAt()!=null && coverage.observedThroughAt()!=null && coverage.evaluatedDbAt()!=null
                    && !coverage.coverageStartAt().isAfter(coverage.observedThroughAt()) && !coverage.observedThroughAt().isAfter(coverage.evaluatedDbAt())) {
                var from=coverage.observedThroughAt().minusDays(rules.activityWindowDays());
                window=new ActivityWindow(rules.activityWindowDays(),from,coverage.observedThroughAt(),coverage.coverageStartAt(),rules.version(),"INTERACTIVE_LOGIN",
                    coverage.coverageStartAt().isAfter(from)?Status.PARTIAL:Status.AVAILABLE);state=window.status();
                if(!roots.isEmpty()) {
                    var rows=mapper.activityEvents(scope,List.copyOf(roots),coverage.observedThroughAt());
                    if(rows==null)throw invalid("SUPPORT_ANALYTICS_ACTIVITY_INVALID");
                    for(var row:rows) {
                        if(row==null || !roots.contains(row.customerId()))throw invalid("SUPPORT_ANALYTICS_ACTIVITY_SCOPE_INVALID");
                        if(row.lastEffectiveAt()==null || row.lastEffectiveAt().isAfter(coverage.observedThroughAt())
                                || latest.putIfAbsent(row.customerId(),row.lastEffectiveAt())!=null)throw invalid("SUPPORT_ANALYTICS_ACTIVITY_INVALID");
                    }
                }
            } else if(rules!=null)window=new ActivityWindow(rules.activityWindowDays(),null,coverage==null?null:coverage.observedThroughAt(),
                coverage==null?null:coverage.coverageStartAt(),rules.version(),"INTERACTIVE_LOGIN",Status.UNKNOWN);
        }catch(DataAccessException ex){state=Status.FAILED;window=new ActivityWindow(null,null,null,null,null,"INTERACTIVE_LOGIN",state);}
        var rows=new TreeMap<Long,CustomerActivity>();
        for(long root:roots) {
            LocalDateTime at=latest.get(root);WindowState classification=window.fromInclusive()==null?WindowState.UNKNOWN:
                at!=null && !at.isBefore(window.fromInclusive())?WindowState.ACTIVE:window.status()==Status.AVAILABLE?WindowState.INACTIVE:WindowState.UNKNOWN;
            Status status=currentFailed || state==Status.FAILED?Status.FAILED:classification==WindowState.UNKNOWN?Status.UNKNOWN:Status.AVAILABLE;
            rows.put(root,new CustomerActivity(at,classification,status,status==Status.AVAILABLE?List.of():List.of(status==Status.FAILED?"ACTIVITY_SOURCE_READ_FAILED":"ACTIVITY_COVERAGE_UNVERIFIED")));
        }
        return new ActivityRead(window,rows);
    }
    private static ActivitySummary activitySummary(List<Customer> customers,ActivityWindow window,boolean failed) {
        if(failed)return new ActivitySummary(new Count(null,null,Status.FAILED),new Count(null,null,Status.FAILED),new Count(null,null,Status.FAILED),window,Status.FAILED,List.of("CURRENT_SOURCE_READ_FAILED"));
        long active=customers.stream().filter(c->c.metrics().activity().state()==WindowState.ACTIVE).count();
        long inactive=customers.stream().filter(c->c.metrics().activity().state()==WindowState.INACTIVE).count();
        long unknown=customers.size()-active-inactive;
        boolean sourceFailed=window.status()==Status.FAILED;
        Status status=sourceFailed?Status.FAILED:unknown>0 || window.status()!=Status.AVAILABLE?Status.PARTIAL:Status.AVAILABLE;
        var partitions=new ArrayList<ActivityPartition>();
        for(var category:Category.values()) {
            var rows=customers.stream().filter(c->c.category()==category).toList();
            long categoryActive=rows.stream().filter(c->c.metrics().activity().state()==WindowState.ACTIVE).count();
            long categoryInactive=rows.stream().filter(c->c.metrics().activity().state()==WindowState.INACTIVE).count();
            long categoryUnknown=rows.size()-categoryActive-categoryInactive;
            Status categoryStatus=sourceFailed?Status.FAILED:categoryUnknown>0?Status.PARTIAL:Status.AVAILABLE;
            partitions.add(new ActivityPartition(category,new Count(sourceFailed?null:categoryActive,sourceFailed || categoryUnknown>0?null:categoryActive,categoryStatus),
                new Count(sourceFailed?null:categoryInactive,sourceFailed || categoryUnknown>0?null:categoryInactive,categoryStatus),sourceFailed?new Count(null,null,Status.FAILED):exact(categoryUnknown)));
        }
        return new ActivitySummary(new Count(sourceFailed?null:active,sourceFailed || unknown>0?null:active,status),
            new Count(sourceFailed?null:inactive,sourceFailed || unknown>0?null:inactive,status),sourceFailed?new Count(null,null,Status.FAILED):exact(unknown),
            window,status,status==Status.AVAILABLE?List.of():List.of(sourceFailed?"ACTIVITY_SOURCE_READ_FAILED":"ACTIVITY_COVERAGE_UNVERIFIED"),partitions);
    }
    private static CurrentMetrics currentMetrics(Query query,List<Customer> customers,Collection<Fact> facts,Snapshot snapshot,
            SupportDeviceReadFacade.Snapshot stock,ActivityRead activity,boolean failed) {
        var roots=new TreeSet<Long>();customers.forEach(c->roots.add(c.customerId()));
        boolean asset=query.basis()==Basis.CURRENT_ASSET;
        Count confirmed=asset?unavailableCount():failed?new Count(null,null,Status.FAILED):exact(customers.stream().filter(c->c.first().state()==FirstState.CONFIRMED).count());
        Count none=asset?unavailableCount():failed?new Count(null,null,Status.FAILED):exact(customers.stream().filter(c->c.first().state()==FirstState.NONE).count());
        Count unknown=asset?unavailableCount():failed?new Count(null,null,Status.FAILED):exact(customers.stream().filter(c->c.first().state()==FirstState.UNKNOWN).count());
        var stockSummary=deviceSummary(stock,roots,facts,snapshot,failed);var activitySummary=activitySummary(customers,activity.window(),failed);
        return new CurrentMetrics(Basis.CURRENT_CUSTOMER_HISTORY,failed?Status.FAILED:asset && stockSummary.status()==Status.AVAILABLE && activitySummary.status()==Status.AVAILABLE?Status.AVAILABLE:Status.PARTIAL,
            lifetime(facts,roots,query.currency(),snapshot,failed,asset),confirmed,none,unknown,stockSummary,activitySummary);
    }
    private static FinancialSummary groupPeriod(Query query,long group,Collection<Fact> historyFacts,List<Fact> selected,List<Fact> first,List<Fact> confirmed,
            Snapshot snapshot,Map<String,AttributionRow> saved,Map<String,Attribution> attribution,boolean gap) {
        var rows=selected.stream().filter(f->saved.containsKey(f.factId()) && "KNOWN".equals(saved.get(f.factId()).groupStatus()) && Objects.equals(saved.get(f.factId()).groupId(),group)).toList();
        var firstRows=first.stream().filter(rows::contains).toList();var confirmedRows=confirmed.stream().filter(rows::contains).toList();
        var currencies=new TreeSet<String>();if(query.currency()!=null)currencies.add(query.currency());else rows.forEach(f->currencies.add(f.currency()));
        var totals=new ArrayList<CurrencyTotals>();var firstTotals=new ArrayList<FirstCurrencyTotals>();
        var readyCustomers=snapshot==null?Set.<Long>of():snapshot.firstHistory().stream()
            .filter(h->h.status()==SupportPaymentFacts.Status.READY).map(SupportPaymentFacts.FirstHistory::customerId).collect(java.util.stream.Collectors.toSet());
        boolean hasHistory=historyFacts.stream().anyMatch(f->readyCustomers.contains(f.customerId()) && saved.containsKey(f.factId())
            && "KNOWN".equals(saved.get(f.factId()).groupStatus()) && Objects.equals(saved.get(f.factId()).groupId(),group));
        Status status=snapshot==null || gap && rows.isEmpty()?Status.UNKNOWN:Status.PARTIAL;
        Status firstState=hasHistory?Status.PARTIAL:Status.UNKNOWN;
        for(String currency:currencies) {
            totals.add(new CurrencyTotals(currency,money(rows,snapshot,Kind.DEPOSIT,currency,false,gap),money(rows,snapshot,Kind.DEVICE_PURCHASE,currency,false,gap),
                money(rows,snapshot,Kind.DEVICE_PURCHASE_REFUND,currency,false,gap),new Money(null,null,null,null,null,Status.UNKNOWN,List.of("COMPLETE_NET_NOT_PROVEN"))));
            firstTotals.add(new FirstCurrencyTotals(currency,firstMoney(firstRows,confirmedRows,Kind.DEPOSIT,currency,firstState,hasHistory),firstMoney(firstRows,confirmedRows,Kind.DEVICE_PURCHASE,currency,firstState,hasHistory)));
        }
        return new FinancialSummary(status,totals,new Count((long)firstRows.size(),hasHistory?(long)confirmedRows.size():null,firstState),partitions(rows,attribution),
            List.of("COMPLETE_HISTORY_NOT_PROVEN"),firstTotals);
    }
    private static Count coveredCount(long value,boolean incomplete) {return new Count(value,incomplete?null:value,incomplete?Status.PARTIAL:Status.AVAILABLE);}
    private static Count exact(long value) {return new Count(value,value,Status.AVAILABLE);}

    private RosterRead readRoster(ReadScope scope,QueryCapture capture) {
        if(scope.mode()==ReadMode.PERSONAL)return new RosterRead(List.of(),List.of(),Status.UNAVAILABLE,false);
        boolean directory=scope.mode()==ReadMode.MANAGED || (capture==null?SupportOwnershipService.hasAuthority("platform_a1_read"):capture.supervisorDirectory);
        var groups=new TreeMap<Long,SupportAnalyticsMapper.GroupRow>();var raw=new TreeMap<Long,List<SupportAnalyticsMapper.RosterRow>>();
        try {
            var rows=mapper.scopedGroupRows(scope);
            if(rows==null)throw invalid("SUPPORT_ANALYTICS_GROUP_INVALID");
            for(var g:rows) {
                if(g==null || g.id()==null || g.id()<=0 || g.supervisorAdminId()==null || g.supervisorAdminId()<=0
                        || g.status()==null || !Set.of("ENABLED","DISABLED","ARCHIVED").contains(g.status()) || g.ownerVerified()==null || g.ownerVerified()<0 || g.ownerVerified()>1
                        || scope.mode()==ReadMode.MANAGED && (!scope.actorId().equals(g.supervisorAdminId()) || g.ownerVerified()!=1)
                        || scope.requestedGroupId()!=null && !scope.requestedGroupId().equals(g.id()))throw invalid("SUPPORT_ANALYTICS_GROUP_SCOPE_INVALID");
                if(groups.putIfAbsent(g.id(),g)!=null)throw invalid("SUPPORT_ANALYTICS_GROUP_CONFLICT");
            }
            addRosterRows(raw,mapper.serviceAccountRows(scope),"SERVICE",scope,groups.keySet());
            if(directory)addRosterRows(raw,mapper.supervisorAccountRows(scope),"SUPERVISOR",scope,groups.keySet());
        }catch(DataAccessException ex){return new RosterRead(List.of(),List.copyOf(groups.values()),Status.FAILED,directory);}
        var accounts=new ArrayList<AccountRow>();
        for(var entry:raw.entrySet())accounts.add(account(entry.getKey(),entry.getValue(),groups));
        return new RosterRead(accounts,List.copyOf(groups.values()),Status.AVAILABLE,directory);
    }
    private static void addRosterRows(Map<Long,List<SupportAnalyticsMapper.RosterRow>> raw,List<SupportAnalyticsMapper.RosterRow> rows,
            String kind,ReadScope scope,Set<Long> groups) {
        if(rows==null)throw invalid("SUPPORT_ANALYTICS_PERSONNEL_INVALID");
        for(var row:rows) {
            if(row==null || row.accountId()==null || row.accountId()<=0 || row.accountStatus()==null || row.evaluatedDbAt()==null
                    || row.compatibleRole()==null || row.compatibleRole()<0 || row.compatibleRole()>1
                    || row.profileEnabled()!=null && row.profileEnabled()!=0 && row.profileEnabled()!=1
                    || row.profileDeleted()!=null && row.profileDeleted()!=0 && row.profileDeleted()!=1
                    || row.qualificationId()!=null && (row.qualificationId()<=0 || !kind.equals(row.qualificationKind())
                        || row.qualificationState()==null || !Set.of("ENABLED","DISABLED","REMOVED").contains(row.qualificationState())
                        || !activeInterval(row.qualificationStartsAt(),row.qualificationEndsAt(),row.evaluatedDbAt()))
                    || row.memberId()!=null && (row.memberId()<=0 || row.groupId()!=null && row.groupId()<=0
                        || !activeInterval(row.memberStartsAt(),row.memberEndsAt(),row.evaluatedDbAt())))
                throw invalid("SUPPORT_ANALYTICS_PERSONNEL_INVALID");
            if(scope.mode()==ReadMode.MANAGED && ("SUPERVISOR".equals(kind)?!scope.actorId().equals(row.accountId()):!groups.contains(row.groupId()))
                    || "SERVICE".equals(kind) && scope.requestedAgentId()!=null && !scope.requestedAgentId().equals(row.accountId()))
                throw invalid("SUPPORT_ANALYTICS_PERSONNEL_SCOPE_INVALID");
            // Keep the requested qualification family even when no qualification exists.
            var typed=new SupportAnalyticsMapper.RosterRow(row.accountId(),row.accountStatus(),row.profileEnabled(),row.profileDeleted(),row.profileSeatType(),
                row.qualificationId(),kind,row.qualificationState(),row.qualificationStartsAt(),row.qualificationEndsAt(),row.memberId(),row.groupId(),
                row.memberStartsAt(),row.memberEndsAt(),row.evaluatedDbAt(),row.compatibleRole());
            raw.computeIfAbsent(row.accountId(),ignored->new ArrayList<>()).add(typed);
        }
    }
    private static boolean activeInterval(LocalDateTime start,LocalDateTime end,LocalDateTime at) {
        return start!=null && !start.isAfter(at) && (end==null || end.isAfter(at));
    }
    private static AccountRow account(long id,List<SupportAnalyticsMapper.RosterRow> rows,Map<Long,SupportAnalyticsMapper.GroupRow> groups) {
        var first=rows.get(0);var qualifications=new HashMap<String,Map<Long,SupportAnalyticsMapper.RosterRow>>();
        var members=new TreeMap<Long,SupportAnalyticsMapper.RosterRow>();
        for(var row:rows) {
            if(!Objects.equals(first.accountStatus(),row.accountStatus()) || !Objects.equals(first.profileEnabled(),row.profileEnabled())
                    || !Objects.equals(first.profileDeleted(),row.profileDeleted()) || !Objects.equals(first.profileSeatType(),row.profileSeatType())
                    || !Objects.equals(first.compatibleRole(),row.compatibleRole()))throw invalid("SUPPORT_ANALYTICS_PERSONNEL_CONFLICT");
            if(row.qualificationId()!=null) {
                var previous=qualifications.computeIfAbsent(row.qualificationKind(),ignored->new TreeMap<>()).putIfAbsent(row.qualificationId(),row);
                if(previous!=null && (!Objects.equals(previous.qualificationState(),row.qualificationState())
                        || !Objects.equals(previous.qualificationStartsAt(),row.qualificationStartsAt()) || !Objects.equals(previous.qualificationEndsAt(),row.qualificationEndsAt())))
                    throw invalid("SUPPORT_ANALYTICS_PERSONNEL_CONFLICT");
            }
            if(row.memberId()!=null) {
                var previous=members.putIfAbsent(row.memberId(),row);
                if(previous!=null && (!Objects.equals(previous.groupId(),row.groupId()) || !Objects.equals(previous.memberStartsAt(),row.memberStartsAt())
                        || !Objects.equals(previous.memberEndsAt(),row.memberEndsAt())))throw invalid("SUPPORT_ANALYTICS_PERSONNEL_CONFLICT");
            }
        }
        QualificationState service=qualificationState(qualifications.get("SERVICE")),supervisor=qualificationState(qualifications.get("SUPERVISOR"));
        boolean role=first.compatibleRole()==1,profile=Objects.equals(first.profileDeleted(),0);
        boolean hasServiceFamily=rows.stream().anyMatch(r->"SERVICE".equals(r.qualificationKind()));
        boolean hasSupervisorFamily=rows.stream().anyMatch(r->"SUPERVISOR".equals(r.qualificationKind()));
        boolean serviceAccount=hasServiceFamily && role && (service==QualificationState.ENABLED || service==QualificationState.DISABLED
            || !qualifications.containsKey("SERVICE") && profile && ("GENERAL".equals(first.profileSeatType()) || "DEDICATED".equals(first.profileSeatType())));
        boolean supervisorAccount=hasSupervisorFamily && role && (supervisor==QualificationState.ENABLED || supervisor==QualificationState.DISABLED
            || !qualifications.containsKey("SUPERVISOR") && profile && "MANAGER".equals(first.profileSeatType()));
        AccountState accountState=first.accountStatus()==1?AccountState.ENABLED:first.accountStatus()==0?AccountState.DISABLED:AccountState.UNKNOWN;
        AccountState reception=!role || accountState==AccountState.DISABLED || service==QualificationState.DISABLED || service==QualificationState.REMOVED
            || Objects.equals(first.profileEnabled(),0) || Objects.equals(first.profileDeleted(),1)?AccountState.DISABLED:
            accountState==AccountState.ENABLED && service==QualificationState.ENABLED && profile && Objects.equals(first.profileEnabled(),1)?AccountState.ENABLED:AccountState.UNKNOWN;
        MemberState memberState=MemberState.UNKNOWN;Long group=null;
        if(members.size()==1) {
            var member=members.values().iterator().next();
            if(member.memberEndsAt()==null) {
                if(member.groupId()==null)memberState=MemberState.UNGROUPED;
                else if(groups.containsKey(member.groupId()) && groups.get(member.groupId()).ownerVerified()==1) {memberState=MemberState.GROUPED;group=member.groupId();}
            }
        }
        boolean anomaly=(!serviceAccount && !supervisorAccount) || hasServiceFamily && service==QualificationState.UNKNOWN
            || hasSupervisorFamily && supervisor==QualificationState.UNKNOWN || members.size()>1 || accountState==AccountState.UNKNOWN;
        Status serviceCategory=hasServiceFamily && !serviceAccount && !(service==QualificationState.REMOVED || !qualifications.containsKey("SERVICE") && profile && "MANAGER".equals(first.profileSeatType()))?Status.UNKNOWN:Status.AVAILABLE;
        Status supervisorCategory=hasSupervisorFamily && !supervisorAccount && !(supervisor==QualificationState.REMOVED || !qualifications.containsKey("SUPERVISOR") && profile && ("GENERAL".equals(first.profileSeatType()) || "DEDICATED".equals(first.profileSeatType())))?Status.UNKNOWN:Status.AVAILABLE;
        return new AccountRow(id,serviceAccount,supervisorAccount,serviceCategory,supervisorCategory,accountState,service,supervisor,reception,memberState,group,
            hasServiceFamily && (reception!=AccountState.ENABLED || memberState==MemberState.UNKNOWN),anomaly?Status.UNKNOWN:Status.AVAILABLE);
    }
    private static QualificationState qualificationState(Map<Long,SupportAnalyticsMapper.RosterRow> rows) {
        if(rows==null || rows.size()!=1)return QualificationState.UNKNOWN;
        var row=rows.values().iterator().next();
        // Reception follows the existing current qualification contract, which requires an open interval.
        return row.qualificationEndsAt()==null?QualificationState.valueOf(row.qualificationState()):QualificationState.UNKNOWN;
    }
    private static PersonnelSummary personnel(RosterRead roster,Long group,ReadScope scope) {
        if(roster.status()==Status.UNAVAILABLE)return unavailablePersonnel();
        var groupRows=roster.groups().stream().filter(g->group==null || group.equals(g.id())).toList();
        var accounts=roster.accounts().stream().filter(a->group==null || group.equals(a.groupId())
            || a.supervisorAccount() && groupRows.stream().anyMatch(g->g.ownerVerified()==1 && Objects.equals(g.supervisorAdminId(),a.accountId()))).toList();
        if(roster.status()==Status.FAILED) {
            var failed=new Count(null,null,Status.FAILED);
            return new PersonnelSummary(List.of(),failed,failed,failed,failed,failed,List.of(),Status.FAILED,List.of("PERSONNEL_SOURCE_READ_FAILED"));
        }
        long services=accounts.stream().filter(AccountRow::serviceAccount).count(),supervisors=accounts.stream().filter(AccountRow::supervisorAccount).count();
        long members=accounts.stream().filter(a->a.serviceAccount() && a.memberState()==MemberState.GROUPED).count();
        long people=accounts.stream().filter(a->a.serviceAccount() || a.supervisorAccount()).count();
        boolean serviceGap=accounts.stream().anyMatch(a->a.serviceCategoryStatus()==Status.UNKNOWN),supervisorGap=accounts.stream().anyMatch(a->a.supervisorCategoryStatus()==Status.UNKNOWN);
        boolean categoryGap=serviceGap || supervisorGap;
        boolean memberGap=roster.accounts().stream().anyMatch(a->a.serviceCategoryStatus()==Status.UNKNOWN
            || a.serviceAccount() && a.memberState()==MemberState.UNKNOWN);
        var partitions=new ArrayList<PersonnelPartition>();
        for(var state:AccountState.values()) {
            partitions.add(new PersonnelPartition("SERVICE_ACCOUNT",state.name(),coveredCount(accounts.stream().filter(a->a.serviceAccount() && a.accountState()==state).count(),serviceGap)));
            if(roster.supervisorDirectory())partitions.add(new PersonnelPartition("SUPERVISOR_ACCOUNT",state.name(),coveredCount(accounts.stream().filter(a->a.supervisorAccount() && a.accountState()==state).count(),supervisorGap)));
            partitions.add(new PersonnelPartition("RECEPTION",state.name(),coveredCount(accounts.stream().filter(a->a.serviceAccount() && a.receptionState()==state).count(),serviceGap)));
            long memberAccounts=accounts.stream().filter(a->a.serviceAccount() && a.memberState()==MemberState.GROUPED && a.accountState()==state).count();
            partitions.add(new PersonnelPartition("GROUP_MEMBER_ACCOUNT",state.name(),coveredCount(memberAccounts,memberGap)));
        }
        for(var state:QualificationState.values()) {
            partitions.add(new PersonnelPartition("SERVICE_QUALIFICATION",state.name(),coveredCount(accounts.stream().filter(a->a.serviceAccount() && a.serviceQualification()==state).count(),serviceGap)));
            if(roster.supervisorDirectory())partitions.add(new PersonnelPartition("SUPERVISOR_QUALIFICATION",state.name(),coveredCount(accounts.stream().filter(a->a.supervisorAccount() && a.supervisorQualification()==state).count(),supervisorGap)));
        }
        for(var state:MemberState.values()) {
            long membership=accounts.stream().filter(a->a.serviceAccount() && a.memberState()==state).count();
            partitions.add(new PersonnelPartition("MEMBERSHIP",state.name(),coveredCount(membership,memberGap)));
        }
        for(String state:List.of("ENABLED","DISABLED","ARCHIVED"))partitions.add(new PersonnelPartition("GROUP",state,exact(groupRows.stream().filter(g->state.equals(g.status())).count())));
        partitions.add(new PersonnelPartition("HANDOVER","REQUIRED",coveredCount(accounts.stream().filter(AccountRow::handoverRequired).count(),
            categoryGap || memberGap || accounts.stream().anyMatch(a->a.status()==Status.UNKNOWN))));
        Status status=categoryGap || memberGap || !roster.supervisorDirectory()?Status.PARTIAL:Status.AVAILABLE;
        boolean groupCategoryGap=serviceGap || group!=null && memberGap;
        Count serviceCount=new Count(services,groupCategoryGap?null:services,groupCategoryGap?Status.PARTIAL:Status.AVAILABLE);
        Count supervisorCount=roster.supervisorDirectory()?new Count(supervisors,supervisorGap?null:supervisors,supervisorGap?Status.PARTIAL:Status.AVAILABLE):unavailableCount();
        boolean peopleGap=categoryGap || (scope.mode()==ReadMode.MANAGED || group!=null) && memberGap;
        Count peopleCount=roster.supervisorDirectory()?new Count(people,peopleGap?null:people,peopleGap?Status.PARTIAL:Status.AVAILABLE):new Count(people,null,Status.PARTIAL);
        return new PersonnelSummary(accounts,serviceCount,new Count(members,memberGap?null:members,memberGap?Status.PARTIAL:Status.AVAILABLE),supervisorCount,peopleCount,exact(groupRows.size()),partitions,status,
            status==Status.AVAILABLE?List.of():List.of(categoryGap?"ACCOUNT_CATEGORY_UNVERIFIED":memberGap?"MEMBER_HISTORY_UNVERIFIED":"SUPERVISOR_DIRECTORY_NOT_AUTHORIZED"));
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
    static boolean aligned(AttributionRow r,Fact f,String sourceZone) {
        return Objects.equals(r.customerId(),f.customerId()) && Objects.equals(r.kind(),f.kind().name()) && Objects.equals(r.source(),f.source().name())
            && Objects.equals(r.ledgerId(),f.ledgerId()) && Objects.equals(r.sourceBusinessId(),f.sourceBusinessId())
            && Objects.equals(r.orderNo(),f.orderNo()) && Objects.equals(r.orderType(),f.orderType()) && Objects.equals(r.originalFactId(),f.originalFactId())
            && Objects.equals(r.currency(),f.currency()) && r.amount()!=null && r.amount().compareTo(f.amount())==0
            && Objects.equals(r.succeededAt(),f.succeededAt()) && Objects.equals(r.sourceBusinessZone(),sourceZone)
            && Objects.equals(r.successTimeField(),f.successTimeField()) && Objects.equals(r.fractionalSecondDigits(),f.fractionalSecondDigits());
    }
    static boolean validLayers(AttributionRow row) {
        if(!"support-payment-attribution-v1".equals(row.captureSchemaVersion())
                || !("NEW_SUCCESS".equals(row.captureMode()) || "OLD_SOURCE".equals(row.captureMode())))return false;
        if(!layer(row.agentStatus(),row.agentAdminId()) || !layer(row.groupStatus(),row.groupId()) || !layer(row.ownerStatus(),row.ownerAdminId()))return false;
        if("KNOWN".equals(row.ownerStatus()) && !"KNOWN".equals(row.groupStatus()))return false;
        return !"OLD_SOURCE".equals(row.captureMode()) || "UNKNOWN".equals(row.agentStatus()) && "UNKNOWN".equals(row.groupStatus()) && "UNKNOWN".equals(row.ownerStatus());
    }
    private static boolean layer(String status,Long id) {
        return "KNOWN".equals(status)?id!=null && id>0:("UNASSIGNED".equals(status) || "UNKNOWN".equals(status)) && id==null;
    }
    static boolean proofRejected(Snapshot snapshot,Fact fact) {
        // Capture readers identify a canonical fact; legacy reconciliation identifies an original
        // source row. A null ID reports a failure of this source for the whole requested scope.
        return snapshot.issues().stream().anyMatch(i -> (i.source()==null || i.source()==fact.source())
            && (i.customerId()==null || i.customerId()==fact.customerId())
            && (i.sourceId()==null || Objects.equals(i.sourceId(),fact.factId()) || fact.sourceIds().contains(i.sourceId()))
            && SupportPaymentFacts.rejectsAttributionProof(i.reason()));
    }
    static boolean sameFinancial(Fact a,Fact b) {
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
    static Map<Long,Fact> selectFirstFacts(Collection<Fact> facts) {
        var first=new TreeMap<Long,Fact>();
        var order=Comparator.comparing(Fact::succeededAt).thenComparingInt(f -> f.kind()==Kind.DEPOSIT?0:1).thenComparing(Fact::factId);
        for(Fact fact:facts)if(fact.kind()!=Kind.DEVICE_PURCHASE_REFUND)
            first.merge(fact.customerId(),fact,(left,right) -> order.compare(left,right)<=0?left:right);
        return first;
    }
    static boolean firstReady(Map<Long,SupportPaymentFacts.FirstHistory> histories,long customer) {
        var history=histories.get(customer);
        return history!=null && history.status()==SupportPaymentFacts.Status.READY;
    }
    private static Money firstMoney(List<Fact> observed,List<Fact> confirmed,Kind kind,String currency,Status status,boolean hasConfirmedHistory) {
        if(status==Status.FAILED)return new Money(null,null,null,null,null,status,List.of("SOURCE_READ_FAILED"));
        var rows=observed.stream().filter(f -> f.kind()==kind && currency.equals(f.currency())).toList();
        var proved=confirmed.stream().filter(f -> f.kind()==kind && currency.equals(f.currency())).toList();
        return new Money(rows.stream().map(Fact::amount).reduce(BigDecimal.ZERO,BigDecimal::add),
            hasConfirmedHistory?proved.stream().map(Fact::amount).reduce(BigDecimal.ZERO,BigDecimal::add):null,
            (long)rows.size(),hasConfirmedHistory?(long)proved.size():null,
            rows.stream().map(Fact::customerId).distinct().count(),status,
            status==Status.AVAILABLE?List.of():List.of("FIRST_HISTORY_OR_SCOPE_UNVERIFIED"));
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
