package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper;
import ffdd.opsconsole.content.mapper.SupportPaymentAttributionMapper.*;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.BeforeSource;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade.FreshLedgerReceipt;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable success evidence. Planning reads cannot authorize attribution. */
@ApplicationService
public class SupportPaymentAttributionService implements SupportPaymentAttributionFacade {
    private static final DateTimeFormatter ISO6=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");
    private final SupportPaymentAttributionMapper mapper;
    private final FinanceSupportPaymentFactsFacade finance;
    private final AuditLogService audit;
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final Object tokenOwner=new Object();

    public SupportPaymentAttributionService(SupportPaymentAttributionMapper mapper,FinanceSupportPaymentFactsFacade finance,
            AuditLogService audit,DataSource dataSource,ObjectMapper json) {
        this.mapper=mapper;this.finance=finance;this.audit=audit;this.dataSource=dataSource;this.json=json;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Prepared prepare(long customer,Source source,String stableBusinessKey) {
        return prepare(customer,source,stableBusinessKey,null);
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Prepared prepare(long customer,Source source,String stableBusinessKey,String sourcePartition) {
        Object resource=writableResource();
        if(customer<=0 || source==null || source==Source.FREE_TRIAL || source==Source.UNMATCHED_LEDGER || !text(stableBusinessKey)
                || (source==Source.DEPOSIT_ORDER || source==Source.VIETQR ? !text(sourcePartition) : sourcePartition!=null)) {
            throw new IllegalArgumentException("SUPPORT_PAYMENT_SOURCE_KEY_INVALID");
        }
        if(mapper.lockCustomer(customer)==null)throw new IllegalStateException("SUPPORT_PAYMENT_CUSTOMER_MISSING");
        List<Binding> bindings=List.copyOf(mapper.assignments(customer));
        List<Route> routes=List.copyOf(mapper.routes(customer));
        TreeSet<Long> agents=new TreeSet<>(),adminIds=new TreeSet<>(),groupIds=new TreeSet<>();
        for(Binding b:bindings)if(!Objects.equals(b.isDeleted(),1) && positive(b.agentAdminId()))agents.add(b.agentAdminId());
        adminIds.addAll(agents);
        for(Long agent:agents)for(Member m:mapper.planMembers(agent))if(positive(m.groupId()))groupIds.add(m.groupId());
        for(Route r:routes)if(positive(r.groupId()))groupIds.add(r.groupId());
        for(Long group:groupIds) {
            Group planned=mapper.planGroup(group);
            if(planned!=null && positive(planned.supervisorAdminId()))adminIds.add(planned.supervisorAdminId());
        }
        Map<Long,Admin> admins=new TreeMap<>();
        for(Long id:adminIds)admins.put(id,mapper.lockAdmin(id));
        Map<Long,Group> groups=new TreeMap<>();
        for(Long id:groupIds)groups.put(id,mapper.lockGroup(id));
        Map<Long,List<Member>> members=new TreeMap<>();
        for(Long agent:agents)members.put(agent,List.copyOf(mapper.members(agent)));
        Map<Long,List<Owner>> owners=new TreeMap<>();
        for(Long group:groupIds)owners.put(group,List.copyOf(mapper.owners(group)));
        Map<Long,List<Qualification>> qualifications=new TreeMap<>();
        for(Long id:adminIds)qualifications.put(id,List.copyOf(mapper.qualifications(id)));
        LocalDateTime capture=Objects.requireNonNull(mapper.databaseUtc(),"SUPPORT_PAYMENT_CLOCK_MISSING");
        Captured captured=capture(customer,capture,bindings,routes,admins,groups,members,owners,qualifications);
        BeforeSource before=Objects.requireNonNull(finance.beforeSource(customer,source,stableBusinessKey,sourcePartition),"SUPPORT_PAYMENT_BEFORE_MISSING");
        if(before.customerId()!=customer || before.source()!=source || !text(before.stableBusinessKey())
                || !Objects.equals(before.sourcePartition(),sourcePartition))throw new IllegalStateException("SUPPORT_PAYMENT_BEFORE_MISMATCH");
        // No present-day witness survives the OLD_SOURCE gate, including in JSON.
        if(before.oldSource())captured=new Captured(Layer.unknown("OLD_SOURCE"),Layer.unknown("OLD_SOURCE"),Layer.unknown("OLD_SOURCE"),capture,Map.of());
        Guard guard=new Guard();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { guard.active.set(false); }
        });
        return new Token(tokenOwner,resource,guard,new CaptureContext(customer,source,before.stableBusinessKey(),sourcePartition,before,captured));
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public int insertLedger(Prepared prepared,BigDecimal amount,BigDecimal balanceAfter,String remark) {
        Token token=requireToken(prepared);
        if(!token.ledgerAttempted.compareAndSet(false,true))throw new IllegalStateException("SUPPORT_PAYMENT_LEDGER_ALREADY_ATTEMPTED");
        FreshLedgerReceipt receipt=Objects.requireNonNull(
            finance.insertFreshPaymentLedger(token.context.before(),amount,balanceAfter,remark),"SUPPORT_PAYMENT_LEDGER_RECEIPT_MISSING");
        if(receipt.ledgerId()<=0)throw new IllegalStateException("SUPPORT_PAYMENT_LEDGER_RECEIPT_INVALID");
        token.receipt.set(receipt);
        return 1;
    }

    private Token requireToken(Prepared prepared) {
        Object resource=writableResource();
        if(!(prepared instanceof Token token) || token.owner!=tokenOwner || token.resource!=resource || !token.guard.active.get()) {
            throw new IllegalArgumentException("SUPPORT_PAYMENT_PREPARED_INVALID");
        }
        return token;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(Prepared prepared) {
        Token token=requireToken(prepared);
        CaptureContext context=token.context;
        FreshLedgerReceipt receipt=token.receipt.get();
        var result=Objects.requireNonNull(receipt==null?finance.readSettled(context.before()):
            finance.readSettled(context.before(),receipt),"SUPPORT_PAYMENT_FACT_MISSING");
        if(result.isEmpty())return; // Only the finance facade can attest an excluded actual source.
        Fact fact=result.get();validate(fact,context);
        if(receipt==null && !context.before().oldSource())throw new IllegalStateException("SUPPORT_PAYMENT_FRESH_LEDGER_REQUIRED");
        if(receipt!=null && fact.ledgerId()!=receipt.ledgerId())throw new IllegalStateException("SUPPORT_PAYMENT_LEDGER_RECEIPT_MISMATCH");
        Captured captured=context.captured();
        Map<String,Object> evidence=map("schemaVersion","support-payment-attribution-v1","captureMode",context.before().oldSource()?"OLD_SOURCE":"NEW_SUCCESS",
            "captureDbUtc",time(captured.captureDbUtc()),"captureZone","UTC","captureFractionalSecondDigits",6,
            "stableBusinessKey",context.stableBusinessKey(),"sourcePartition",context.sourcePartition(),
            "agent",layer(captured.agent()),"group",layer(captured.group()),"owner",layer(captured.owner()));
        evidence.put("beforeSource",beforeWitness(context.before()));
        evidence.putAll(captured.witnesses());
        StoredRow row=new StoredRow(fact.factId(),fact.customerId(),fact.kind().name(),fact.source().name(),fact.ledgerId(),fact.sourceBusinessId(),
            fact.orderNo(),fact.orderType(),fact.originalFactId(),fact.currency(),fact.amount(),fact.succeededAt(),context.before().businessZone(),
            fact.successTimeField(),fact.fractionalSecondDigits(),context.sourcePartition(),captured.captureDbUtc(),captured.agent().id(),captured.group().id(),captured.owner().id(),
            captured.agent().status(),captured.group().status(),captured.owner().status(),context.before().oldSource()?"OLD_SOURCE":"NEW_SUCCESS",encode(sourceFact(fact,context.before().businessZone())),encode(evidence));
        // A missing-PK locking read under RR would take a gap lock before this insert.
        // Only a failed duplicate insert proves there is an existing identity to read.
        try {
            if(mapper.insert(row)!=1)throw new IllegalStateException("SUPPORT_PAYMENT_INSERT_FAILED");
        } catch(DuplicateKeyException duplicate) {
            StoredRow existing=mapper.findEvidence(fact.factId());
            if(existing==null)throw duplicate;
            if(!sameFact(existing,fact,context.before().businessZone(),context.sourcePartition())) {
                throw new IllegalStateException("SUPPORT_PAYMENT_FACT_CONFLICT",duplicate);
            }
            return;
        }
        audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_PAYMENT_ATTRIBUTION_CAPTURED")
            .resourceType("SUPPORT_PAYMENT_ATTRIBUTION").resourceId(fact.factId()).bizNo(fact.sourceBusinessId()).userId(fact.customerId())
            .actorType("SYSTEM").result("SUCCESS").detail(map("factId",fact.factId(),"captureMode",row.captureMode(),
                "agentStatus",row.agentStatus(),"groupStatus",row.groupStatus(),"ownerStatus",row.ownerStatus())).build());
    }

    private Object writableResource() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        Object resource=TransactionSynchronizationManager.getResource(dataSource);
        if(resource==null)throw new IllegalStateException("SUPPORT_PAYMENT_TRANSACTION_REQUIRED");
        return resource;
    }

    private Captured capture(long customer,LocalDateTime at,List<Binding> bindings,List<Route> routes,Map<Long,Admin> admins,
            Map<Long,Group> groups,Map<Long,List<Member>> members,Map<Long,List<Owner>> owners,Map<Long,List<Qualification>> qualifications) {
        List<Binding> live=bindings.stream().filter(b->!Objects.equals(b.isDeleted(),1)).toList();
        Window<Binding> b=window(live,Binding::startsAt,Binding::endsAt,at);
        for(Binding row:live) {
            if(!Objects.equals(row.userId(),customer) || !Objects.equals(row.isDeleted(),0) || !positive(row.id()) || !positive(row.version())
                    || !positive(row.agentAdminId()) || !("ACTIVE".equals(row.status()) || "INACTIVE".equals(row.status()))
                    || ("ACTIVE".equals(row.status()) && row.endsAt()!=null) || ("INACTIVE".equals(row.status()) && row.endsAt()==null)) {
                b=new Window<>(false,List.of(),"BINDING_INVALID");break;
            }
        }
        Layer agent=Layer.unknown(b.reason());
        Binding assignment=null;
        if(b.valid()) {
            if(b.current().isEmpty())agent=Layer.unassigned("NO_CURRENT_BINDING");
            else if(b.current().size()==1) {
                Binding candidate=b.current().get(0);
                if(Objects.equals(candidate.userId(),customer) && Objects.equals(candidate.isDeleted(),0) && "ACTIVE".equals(candidate.status())
                        && candidate.endsAt()==null && positive(candidate.version()) && positive(candidate.agentAdminId())
                        && validAdmin(admins.get(candidate.agentAdminId()),candidate.agentAdminId())) {
                    agent=Layer.known(candidate.agentAdminId());assignment=candidate;
                } else agent=Layer.unknown("BINDING_INVALID");
            }
        }
        Window<Route> route=window(routes,Route::startsAt,Route::endsAt,at);
        Layer group=Layer.unknown("GROUP_HISTORY_MISSING");
        if(assignment!=null) {
            Window<Member> membership=window(members.getOrDefault(assignment.agentAdminId(),List.of()),Member::startsAt,Member::endsAt,at);
            if(!membership.valid())group=Layer.unknown(membership.reason());
            else if(membership.current().size()==1) {
                Member m=membership.current().get(0);
                if(!Objects.equals(m.agentAdminId(),assignment.agentAdminId()) || !positive(m.version()) || m.endsAt()!=null)group=Layer.unknown("MEMBER_INVALID");
                else if(m.groupId()==null)group=Layer.unassigned("EXPLICIT_MEMBER_UNASSIGNED");
                else if(!groups.containsKey(m.groupId()))group=Layer.unknown("LOCK_PLAN_CHANGED");
                else if(!validGroup(groups.get(m.groupId()),m.groupId()))group=Layer.unknown("GROUP_MISSING");
                else group=Layer.known(m.groupId());
            }
            if(!routes.isEmpty()) {
                if(!route.valid() || route.current().size()!=1 || !Objects.equals(route.current().get(0).customerId(),customer)
                        || !Objects.equals(route.current().get(0).groupId(),group.id()))group=Layer.unknown("ROUTE_BINDING_CONFLICT");
            }
        } else if("UNASSIGNED".equals(agent.status())) {
            if(!route.valid())group=Layer.unknown(route.reason());
            else if(route.current().size()==1) {
                Route r=route.current().get(0);
                if(!Objects.equals(r.customerId(),customer) || !positive(r.version()) || r.endsAt()!=null)group=Layer.unknown("ROUTE_INVALID");
                else if(r.groupId()==null)group=Layer.unassigned("EXPLICIT_QUEUE_UNASSIGNED");
                else if(!validGroup(groups.get(r.groupId()),r.groupId()))group=Layer.unknown("GROUP_MISSING");
                else group=Layer.known(r.groupId());
            }
        } else group=Layer.unknown("BINDING_UNKNOWN");
        Layer owner="UNASSIGNED".equals(group.status())?Layer.unassigned("GROUP_UNASSIGNED"):Layer.unknown("GROUP_UNKNOWN");
        if("KNOWN".equals(group.status())) {
            Group currentGroup=groups.get(group.id());
            Window<Owner> history=window(owners.getOrDefault(group.id(),List.of()),Owner::startsAt,Owner::endsAt,at);
            if(!history.valid())owner=Layer.unknown(history.reason());
            else if(history.current().size()==1) {
                Owner o=history.current().get(0);
                if(!admins.containsKey(o.supervisorAdminId()) || !admins.containsKey(currentGroup.supervisorAdminId()))owner=Layer.unknown("LOCK_PLAN_CHANGED");
                else if(!Objects.equals(o.groupId(),group.id()) || !Objects.equals(o.supervisorAdminId(),currentGroup.supervisorAdminId())
                        || !positive(o.version()) || o.endsAt()!=null || !validAdmin(admins.get(o.supervisorAdminId()),o.supervisorAdminId()))owner=Layer.unknown("OWNER_INVALID");
                else owner=Layer.known(o.supervisorAdminId());
            } else owner=Layer.unknown("OWNER_HISTORY_MISSING");
        }
        Map<String,Object> witnesses=map("bindingHistory",bindings.stream().map(this::bindingWitness).toList(),
            "routeHistory",routes.stream().map(r->history(r.id(),r.startsAt(),r.endsAt(),r.version(),r.operationId(),map("customerId",r.customerId(),"groupId",r.groupId()))).toList(),
            "admins",admins.values().stream().filter(Objects::nonNull).map(a->map("id",a.id(),"status",a.status(),"version",a.version(),"isDeleted",a.isDeleted())).toList(),
            "groups",groups.values().stream().filter(Objects::nonNull).map(g->map("id",g.id(),"supervisorAdminId",g.supervisorAdminId(),"status",g.status(),"version",g.version())).toList(),
            "memberHistory",members.values().stream().flatMap(List::stream).map(m->history(m.id(),m.startsAt(),m.endsAt(),m.version(),m.operationId(),map("agentAdminId",m.agentAdminId(),"groupId",m.groupId()))).toList(),
            "ownerHistory",owners.values().stream().flatMap(List::stream).map(o->history(o.id(),o.startsAt(),o.endsAt(),o.version(),o.operationId(),map("groupId",o.groupId(),"supervisorAdminId",o.supervisorAdminId()))).toList(),
            "qualifications",qualifications.values().stream().flatMap(List::stream).map(q->history(q.id(),q.startsAt(),q.endsAt(),q.version(),q.operationId(),map("adminId",q.adminId(),"qualificationKind",q.qualificationKind(),"state",q.state()))).toList());
        return new Captured(agent,group,owner,at,witnesses);
    }

    private static <T> Window<T> window(List<T> rows,Function<T,LocalDateTime> start,Function<T,LocalDateTime> end,LocalDateTime at) {
        List<T> current=new ArrayList<>();
        for(T row:rows) {
            LocalDateTime s=start.apply(row),e=end.apply(row);
            if(s==null || s.isAfter(at) || (e!=null && e.isBefore(s)))return new Window<>(false,List.of(),"HISTORY_INVALID");
            if(!s.isAfter(at) && (e==null || e.isAfter(at)))current.add(row);
        }
        if(current.size()>1)return new Window<>(false,List.copyOf(current),"HISTORY_OVERLAP");
        return new Window<>(true,List.copyOf(current),"HISTORY_MISSING");
    }
    private void validate(Fact f,CaptureContext c) {
        if(f.customerId()!=c.customer() || f.source()!=c.source() || f.kind()==null || !text(f.factId()) || !text(f.sourceBusinessId())
                || f.ledgerId()<=0 || !text(f.currency()) || f.amount()==null || f.amount().signum()<=0 || f.amount().stripTrailingZeros().scale()>6
                || f.amount().precision()-f.amount().scale()>12 || f.succeededAt()==null || !text(f.successTimeField())
                || f.fractionalSecondDigits()<0 || f.fractionalSecondDigits()>6 || !text(c.before().businessZone()))throw new IllegalStateException("SUPPORT_PAYMENT_FACT_INVALID");
        ZoneId.of(c.before().businessZone());
        int quantum=1;for(int i=f.fractionalSecondDigits();i<9;i++)quantum*=10;
        if(f.succeededAt().getNano()%quantum!=0)throw new IllegalStateException("SUPPORT_PAYMENT_FACT_INVALID");
        String canonical=switch(f.kind()) {
            case DEPOSIT -> "DEPOSIT:"+f.ledgerId();
            case DEVICE_PURCHASE -> "PURCHASE:"+f.orderNo();
            case DEVICE_PURCHASE_REFUND -> "ORDER_REFUND:"+f.ledgerId();
        };
        Kind expected=switch(f.source()) {
            case DEPOSIT_ORDER,CARD_TOPUP,VIETQR,HDPAY -> Kind.DEPOSIT;
            case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP,TRIAL_CONVERT -> Kind.DEVICE_PURCHASE;
            case ORDER_REFUND -> Kind.DEVICE_PURCHASE_REFUND;
            case FREE_TRIAL,UNMATCHED_LEDGER -> null;
        };
        if(expected!=f.kind() || !canonical.equals(f.factId()) || (f.kind()!=Kind.DEPOSIT && !text(f.orderNo()))
                || (f.kind()==Kind.DEVICE_PURCHASE_REFUND
                    ? !Objects.equals(f.originalFactId(),"PURCHASE:"+f.orderNo()) : f.originalFactId()!=null))throw new IllegalStateException("SUPPORT_PAYMENT_FACT_INVALID");
    }
    private static boolean sameFact(StoredRow s,Fact f,String zone,String partition) {
        return Objects.equals(s.factId(),f.factId()) && s.customerId()==f.customerId() && s.ledgerId()==f.ledgerId() && s.kind().equals(f.kind().name()) && s.source().equals(f.source().name())
            && Objects.equals(s.sourceBusinessId(),f.sourceBusinessId()) && Objects.equals(s.orderNo(),f.orderNo()) && Objects.equals(s.orderType(),f.orderType())
            && Objects.equals(s.originalFactId(),f.originalFactId()) && Objects.equals(s.currency(),f.currency()) && s.amount().compareTo(f.amount())==0
            && Objects.equals(s.succeededAt(),f.succeededAt()) && Objects.equals(s.sourceBusinessZone(),zone)
            && Objects.equals(s.successTimeField(),f.successTimeField()) && s.fractionalSecondDigits()==f.fractionalSecondDigits()
            && Objects.equals(s.sourcePartition(),partition);
    }
    private Map<String,Object> sourceFact(Fact f,String zone) {
        return map("factId",f.factId(),"kind",f.kind(),"source",f.source(),"sourceIds",f.sourceIds(),"customerId",f.customerId(),"ledgerId",f.ledgerId(),
            "sourceBusinessId",f.sourceBusinessId(),"orderNo",f.orderNo(),"orderType",f.orderType(),"originalFactId",f.originalFactId(),
            "currency",f.currency(),"amount",f.amount(),"succeededAt",time(f.succeededAt()),
            "succeededAtInstant",time(f.succeededAt().atZone(ZoneId.of(zone)).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())+"Z","successTimeField",f.successTimeField(),
            "fractionalSecondDigits",f.fractionalSecondDigits(),"businessZone",zone,"providerPaidAt",time(f.providerPaidAt()),"ledgerRecordedAt",time(f.ledgerRecordedAt()),
            "sourceConfirmationAt",time(f.sourceConfirmationAt()),"sourceVersion",f.sourceVersion(),"historicalEnvironmentStatus",f.historicalEnvironmentStatus());
    }
    private Map<String,Object> beforeWitness(BeforeSource before) {
        return map("customerId",before.customerId(),"source",before.source(),"stableBusinessKey",before.stableBusinessKey(),
            "sourcePartition",before.sourcePartition(),"oldSource",before.oldSource(),"existingLedgerId",before.existingLedgerId(),
            "existingFactId",before.existingFactId(),"existingSuccessAt",time(before.existingSuccessAt()),"sourceIds",before.sourceIds(),
            "sourceVersion",before.sourceVersion(),"successTimeField",before.successTimeField(),"fractionalSecondDigits",before.fractionalSecondDigits(),"businessZone",before.businessZone());
    }
    private Map<String,Object> bindingWitness(Binding b) {
        return history(b.id(),b.startsAt(),b.endsAt(),b.version(),b.operationId(),map("userId",b.userId(),"agentAdminId",b.agentAdminId(),"status",b.status(),
            "isDeleted",b.isDeleted(),"source",b.source(),"segmentRootId",b.segmentRootId(),"depth",b.depth(),"parentAssignmentId",b.parentAssignmentId(),"ruleVersion",b.ruleVersion()));
    }
    private static Map<String,Object> history(Long id,LocalDateTime starts,LocalDateTime ends,Long version,String operation,Map<String,Object> extra) {
        Map<String,Object> result=map("id",id,"startsAt",time(starts),"endsAt",time(ends),"version",version,"operationId",operation,"zone","UTC");
        result.putAll(extra);return result;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException ex) { throw new IllegalStateException("SUPPORT_PAYMENT_EVIDENCE_SERIALIZATION_FAILED",ex); }
    }
    private static Map<String,Object> layer(Layer layer) { return map("status",layer.status(),"id",layer.id(),"reason",layer.reason()); }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    private static String time(LocalDateTime time) { return time==null?null:ISO6.format(time); }
    private static boolean text(String text) { return text!=null && !text.isBlank(); }
    private static boolean positive(Long id) { return id!=null && id>0; }
    private static boolean validAdmin(Admin admin,Long id) { return admin!=null && Objects.equals(admin.id(),id) && positive(admin.version()); }
    private static boolean validGroup(Group group,Long id) { return group!=null && Objects.equals(group.id(),id) && positive(group.version()); }
    private record Layer(Long id,String status,String reason) {
        static Layer known(Long id) { return new Layer(id,"KNOWN",null); }
        static Layer unknown(String reason) { return new Layer(null,"UNKNOWN",reason); }
        static Layer unassigned(String reason) { return new Layer(null,"UNASSIGNED",reason); }
    }
    private record Window<T>(boolean valid,List<T> current,String reason) { }
    private record Captured(Layer agent,Layer group,Layer owner,LocalDateTime captureDbUtc,Map<String,Object> witnesses) { }
    private record CaptureContext(long customer,Source source,String stableBusinessKey,String sourcePartition,BeforeSource before,Captured captured) { }
    private static final class Guard {
        private final AtomicBoolean active=new AtomicBoolean(true);
    }
    private static final class Token implements Prepared {
        private final Object owner;
        private final Object resource;
        private final Guard guard;
        private final CaptureContext context;
        private final AtomicBoolean ledgerAttempted=new AtomicBoolean(false);
        private final AtomicReference<FreshLedgerReceipt> receipt=new AtomicReference<>();
        private Token(Object owner,Object resource,Guard guard,CaptureContext context) {
            this.owner=owner;this.resource=resource;this.guard=guard;this.context=context;
        }
    }
}
