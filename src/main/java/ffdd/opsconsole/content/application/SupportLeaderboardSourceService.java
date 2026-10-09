package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.AttributionRow;
import ffdd.opsconsole.finance.facade.FinanceSupportPaymentFactsFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Fact;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Kind;
import ffdd.opsconsole.shared.exception.BizException;
import java.io.*;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal full-source adapter. The public owner authorizes before entry and rechecks before returning HTTP. */
@ApplicationService
public class SupportLeaderboardSourceService {
    private final SupportLeaderboardMapper mapper;
    private final FinanceSupportPaymentFactsFacade finance;
    public SupportLeaderboardSourceService(SupportLeaderboardMapper mapper,FinanceSupportPaymentFactsFacade finance) {
        this.mapper=Objects.requireNonNull(mapper);this.finance=Objects.requireNonNull(finance);
    }
    /** Raw customer/payment/proof tuples stay internal and must never be serialized or logged by public controllers. */
    public record Evidence(List<Account> accounts,List<QualificationInterval> qualifications,List<MemberInterval> memberships,
                           List<Group> groups,List<Binding> bindings,List<Long> productionCustomers,List<AttributionRow> attributions,
                           List<AttributionProof> attributionProofs,SupportPaymentFacts.Snapshot finance) {
        public Evidence {
            accounts=List.copyOf(accounts);qualifications=List.copyOf(qualifications);memberships=List.copyOf(memberships);
            groups=List.copyOf(groups);bindings=List.copyOf(bindings);productionCustomers=List.copyOf(productionCustomers);
            attributions=List.copyOf(attributions);attributionProofs=List.copyOf(attributionProofs);
        }
    }
    public record Read(Context context,String sourceVersion,Coverage candidateCoverage,List<Candidate> candidates,
                       List<YearMonth> selectableMonths,Evidence evidence) {
        public Read {candidates=List.copyOf(candidates);selectableMonths=List.copyOf(selectableMonths);}
    }

    @Transactional(readOnly=true,propagation=Propagation.MANDATORY)
    public Read readForAuthorizedLeaderboard(Context authorizedContext) {
        Objects.requireNonNull(authorizedContext);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),Connection.TRANSACTION_REPEATABLE_READ))
            throw new IllegalStateException("SUPPORT_LEADERBOARD_CALLER_RR_REQUIRED");
        final LocalDateTime cutoff;
        final Evidence raw;
        try {
            cutoff=mapper.nowUtc();
            if (cutoff==null) throw failed();
            List<Account> accounts=ordered(mapper.accounts(),Account::id);
            List<QualificationInterval> qualifications=ordered(mapper.qualifications(),QualificationInterval::id);
            List<MemberInterval> memberships=ordered(mapper.memberships(),MemberInterval::id);
            List<Group> groups=ordered(mapper.groups(),Group::id);
            List<Binding> bindings=ordered(mapper.currentBindings(),Binding::id);
            List<Long> customers=ordered(mapper.productionCustomers(),id -> id);
            if (customers.stream().anyMatch(id -> id<=0)) throw failed();
            List<AttributionRow> attributions=ordered(mapper.attributions(),AttributionRow::factId);
            List<AttributionProof> proofs=ordered(mapper.attributionProofs(),AttributionProof::factId);
            // No private ALL scope or writer is used: these IDs are explicit internal public-aggregate source subjects.
            SupportPaymentFacts.Snapshot financial=customers.isEmpty()?null:finance.readHistory(customers);
            if (!customers.isEmpty() && financial==null) throw failed();
            raw=new Evidence(accounts,qualifications,memberships,groups,bindings,customers,attributions,proofs,financial);
        } catch (DataAccessException ex) {throw failed();}
        Instant evaluatedAt=cutoff.toInstant(ZoneOffset.UTC);
        YearMonth currentMonth=YearMonth.from(evaluatedAt.atZone(SupportLeaderboard.BUSINESS_ZONE));
        Context context=new Context(authorizedContext.board(),authorizedContext.rankMonth(),
            authorizedContext.board()==Board.customers?currentMonth:authorizedContext.referenceMonth(),authorizedContext.currency(),
            authorizedContext.scope(),authorizedContext.approvedGroupIds(),authorizedContext.definitionVersion(),evaluatedAt);
        return project(context,cutoff,raw,currentMonth);
    }

    private static Read project(Context context,LocalDateTime cutoff,Evidence raw,YearMonth currentMonth) {
        Map<Long,Account> accounts=new TreeMap<>();
        for (Account a:raw.accounts()) {
            if (a.id()==null || a.id()<=0 || a.version()==null || a.version()<0 || a.status()==null || a.isDeleted()==null
                    || a.compatibleRole()==null || !binary(a.status()) || !binary(a.isDeleted()) || !binary(a.compatibleRole())
                    || a.profileEnabled()!=null && !binary(a.profileEnabled()) || a.profileDeleted()!=null && !binary(a.profileDeleted())) throw failed();
            accounts.put(a.id(),a);
        }
        Map<Long,List<QualificationInterval>> qualifications=new HashMap<>();
        for (QualificationInterval q:raw.qualifications()) {
            interval(q.id(),q.agentId(),q.version(),q.startsAt(),q.endsAt(),accounts);
            if (q.state()==null || !Set.of("ENABLED","DISABLED","REMOVED").contains(q.state())) throw failed();
            qualifications.computeIfAbsent(q.agentId(),id -> new ArrayList<>()).add(q);
        }
        for (var intervals:qualifications.values()) rejectOverlaps(intervals.stream().map(q -> new Times(q.startsAt(),q.endsAt())).toList());
        Map<Long,List<MemberInterval>> memberships=new HashMap<>();
        for (MemberInterval m:raw.memberships()) {
            interval(m.id(),m.agentId(),m.version(),m.startsAt(),m.endsAt(),accounts);
            if (m.groupId()!=null && m.groupId()<=0) throw failed();
            memberships.computeIfAbsent(m.agentId(),id -> new ArrayList<>()).add(m);
        }
        for (var intervals:memberships.values()) rejectOverlaps(intervals.stream().map(m -> new Times(m.startsAt(),m.endsAt())).toList());
        Map<Long,Group> groups=new HashMap<>();
        for (Group g:raw.groups()) {
            if (g.id()==null || g.id()<=0 || g.version()==null || g.version()<1 || g.name()==null || g.name().isBlank()
                    || g.supervisorId()==null || g.supervisorId()<=0 || g.status()==null
                    || !Set.of("ENABLED","DISABLED","ARCHIVED").contains(g.status()) || g.createdAt()==null || g.updatedAt()==null) throw failed();
            groups.put(g.id(),g);
        }
        if (!groups.keySet().containsAll(context.approvedGroupIds())) throw new BizException(409,"SUPPORT_LEADERBOARD_SCOPE_CHANGED");
        Set<Long> customerIds=new TreeSet<>(raw.productionCustomers());
        Map<Long,Set<Long>> bound=new HashMap<>(); Set<Long> boundCustomers=new HashSet<>();
        for (Binding b:raw.bindings()) {
            if (b.id()==null || b.id()<=0 || b.customerId()==null || !customerIds.contains(b.customerId())
                    || b.agentId()==null || !accounts.containsKey(b.agentId()) || b.version()==null || b.version()<1
                    || b.startsAt()==null || b.startsAt().isAfter(cutoff) || b.endsAt()!=null || !boundCustomers.add(b.customerId())) throw failed();
            bound.computeIfAbsent(b.agentId(),id -> new TreeSet<>()).add(b.customerId());
        }
        Financial first=first(raw,customerIds,context,evaluatedFrom(context),evaluatedTo(context));
        List<Candidate> candidates=new ArrayList<>();
        // There is no qualification-birth/completeness certificate for historical SERVICE capture in this schema.
        Coverage candidateCoverage=context.board()==Board.customers?Coverage.COMPLETE:Coverage.PARTIAL;
        Instant monthStart=context.referenceMonth().atDay(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();
        Instant monthEnd=context.referenceMonth().plusMonths(1).atDay(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();
        for (Account a:accounts.values()) {
            List<QualificationInterval> history=qualifications.getOrDefault(a.id(),List.of());
            QualificationInterval current=history.stream().filter(q -> active(q.startsAt(),q.endsAt(),cutoff)).findFirst().orElse(null);
            if (current!=null && "ENABLED".equals(current.state()) && (a.profileEnabled()==null || a.profileDeleted()==null
                    || a.profileVersion()==null || a.profileVersion()<1 || a.profileSeatType()==null || a.profileSeatType().isBlank())) throw failed();
            boolean serviceNow=current!=null && "ENABLED".equals(current.state()) && a.status()==1 && a.isDeleted()==0
                && Objects.equals(a.profileEnabled(),1) && Objects.equals(a.profileDeleted(),0) && a.compatibleRole()==1;
            long customers=bound.getOrDefault(a.id(),Set.of()).size();
            boolean monthly=history.stream().anyMatch(q -> "ENABLED".equals(q.state())
                && !q.startsAt().isAfter(cutoff)
                && q.startsAt().toInstant(ZoneOffset.UTC).isBefore(monthEnd)
                && (q.endsAt()==null || q.endsAt().toInstant(ZoneOffset.UTC).isAfter(monthStart))
                && (q.endsAt()==null || q.endsAt().isAfter(q.startsAt())));
            if (!(context.board()==Board.customers ? serviceNow || customers>0 : monthly)) continue;
            MemberInterval member=memberships.getOrDefault(a.id(),List.of()).stream().filter(m -> active(m.startsAt(),m.endsAt(),cutoff)).findFirst().orElse(null);
            Long groupId=member==null?null:member.groupId();
            if (groupId!=null && !groups.containsKey(groupId)) throw failed();
            if (context.scope()!=Scope.all && (groupId==null || !context.approvedGroupIds().contains(groupId))) continue;
            boolean serviceHistoryKnown=history.stream().anyMatch(q -> "ENABLED".equals(q.state()) && !q.startsAt().isAfter(cutoff)
                && (q.endsAt()==null || q.endsAt().isAfter(q.startsAt())));
            if (context.board()==Board.customers && customers>0 && !serviceHistoryKnown) candidateCoverage=Coverage.PARTIAL;
            Qualification qualification=customers>0 && !serviceHistoryKnown?Qualification.UNKNOWN:customers>0 && !serviceNow?Qualification.HANDOVER_REQUIRED:current==null?Qualification.UNKNOWN
                :"REMOVED".equals(current.state())?Qualification.REMOVED:serviceNow?Qualification.ACTIVE:Qualification.DISABLED;
            String name=a.nickname()==null || a.nickname().isBlank()?"专属客服 #"+a.id():a.nickname().strip();
            String avatar="ATTACHED".equals(a.avatarState()) && a.id().equals(a.avatarAdminId()) && a.avatarAssetId()!=null
                && a.avatarVersion()!=null && a.avatarVersion()>0
                ?"/api/admin/content/support-workbench/leaderboard/"+a.id()+"/avatar?assetVersion="+a.avatarVersion():null;
            Count firstPayment=new Count(first.complete()?first.confirmedFirst().getOrDefault(a.id(),0L):first.confirmedFirst().get(a.id()),first.complete()?Coverage.COMPLETE:Coverage.PARTIAL,
                first.reason());
            Amount amount=new Amount(null,context.currency(),context.referenceMonth(),context.board()==Board.purchase?AmountKind.PURCHASE:AmountKind.DEPOSIT,
                Coverage.UNKNOWN,context.board()==Board.purchase?Reason.SOURCE_INCOMPLETE:Reason.REFUNDS_UNKNOWN,"financial-source-unverified");
            candidates.add(new Candidate(a.id(),name,avatar,groupId==null?"待分组":groups.get(groupId).name(),qualification,
                firstPayment,new Count(customers,Coverage.COMPLETE,Reason.NONE),amount,null));
        }
        String sourceVersion=fingerprint(context,raw,candidates,candidateCoverage);
        List<Candidate> versioned=new ArrayList<>();
        for (Candidate c:candidates) {
            Amount old=c.amount(); Amount amount=new Amount(old.value(),old.currency(),old.month(),old.kind(),old.coverage(),old.reason(),sourceVersion);
            versioned.add(new Candidate(c.agentId(),c.name(),c.avatarUrl(),c.groupName(),c.qualification(),c.firstPayment(),c.customers(),amount,null));
        }
        // Current month is available with disclosed coverage; no historic month is certified complete by these sources.
        return new Read(context,sourceVersion,candidateCoverage,versioned,List.of(currentMonth),raw);
    }

    private record Financial(Map<Long,Long> confirmedFirst,boolean complete,Reason reason) { }
    private static Financial first(Evidence raw,Set<Long> ids,Context context,Instant from,Instant to) {
        SupportPaymentFacts.Snapshot snapshot=raw.finance();
        if (snapshot==null) return new Financial(Map.of(),ids.isEmpty(),ids.isEmpty()?Reason.NONE:Reason.HISTORY_UNKNOWN);
        try {SupportAnalyticsService.validateSnapshotBoundary(snapshot,ids);} catch(IllegalStateException | IllegalArgumentException ex) {throw failed();}
        if (!SupportLeaderboard.BUSINESS_ZONE.getId().equals(snapshot.businessZone())) throw failed();
        Set<SupportPaymentFacts.Source> coverageSources=new HashSet<>();
        for(var c:snapshot.coverage()) {
            if(c.source()==null || c.observedStatus()==null || c.historyStatus()==null || c.refundStatus()==null
                    || c.historicalEnvironmentStatus()==null || c.adapterVersion()==null || c.adapterVersion().isBlank()
                    || c.reasons().stream().anyMatch(r -> r==null || r.isBlank()) || !coverageSources.add(c.source())) throw failed();
        }
        if (snapshot.issues().stream().anyMatch(i -> i.reason()==null || i.reason().contains("READ_FAILED"))
                || snapshot.coverage().stream().anyMatch(c -> c.reasons().stream().anyMatch(r -> r.contains("READ_FAILED")))
                || snapshot.firstHistory().stream().anyMatch(h -> h.reasons().stream().anyMatch(r -> r.contains("READ_FAILED")))) throw failed();
        Map<String,Fact> facts=new TreeMap<>();
        for (Fact f:snapshot.facts()) {
            if (SupportPaymentFacts.validateCanonical(f,snapshot.businessZone())!=null) throw failed();
            Fact old=facts.putIfAbsent(f.factId(),f);
            if (old!=null && !SupportAnalyticsService.sameFinancial(old,f)) throw failed();
        }
        Map<Long,SupportPaymentFacts.FirstHistory> histories=new HashMap<>();
        for (var h:snapshot.firstHistory()) {
            if (h.status()==SupportPaymentFacts.Status.READY && !h.reasons().isEmpty()
                    || h.status()==SupportPaymentFacts.Status.UNKNOWN && h.reasons().isEmpty() || histories.putIfAbsent(h.customerId(),h)!=null) throw failed();
        }
        Map<String,AttributionRow> attributions=new HashMap<>();
        for (AttributionRow r:raw.attributions()) {
            if (r.customerId()==null || !ids.contains(r.customerId())) throw failed();
            attributions.put(r.factId(),r);
        }
        Set<String> proofIds=new HashSet<>(); raw.attributionProofs().forEach(p -> proofIds.add(p.factId()));
        if (!proofIds.equals(attributions.keySet())) throw failed();
        boolean complete=ids.stream().allMatch(id -> SupportAnalyticsService.firstReady(histories,id))
            && coverageSources.containsAll(Set.of(SupportPaymentFacts.Source.DEPOSIT_ORDER,SupportPaymentFacts.Source.CARD_TOPUP,
                SupportPaymentFacts.Source.VIETQR,SupportPaymentFacts.Source.HDPAY,SupportPaymentFacts.Source.WALLET_ORDER,
                SupportPaymentFacts.Source.TRADE_IN,SupportPaymentFacts.Source.CAPACITY_KEEP,SupportPaymentFacts.Source.TRIAL_CONVERT));
        Reason reason=complete?Reason.NONE:Reason.HISTORY_UNKNOWN;
        Map<Long,Long> result=new HashMap<>();
        for (Fact f:SupportAnalyticsService.selectFirstFacts(facts.values()).values()) {
            Instant occurred=f.succeededAt().atZone(SupportLeaderboard.BUSINESS_ZONE).toInstant();
            if (occurred.isBefore(from) || !occurred.isBefore(to) || occurred.isAfter(context.evaluatedAt())) continue;
            AttributionRow attribution=attributions.get(f.factId());
            boolean proved=attribution!=null && SupportAnalyticsService.aligned(attribution,f,snapshot.businessZone())
                && SupportAnalyticsService.validLayers(attribution) && !SupportAnalyticsService.proofRejected(snapshot,f);
            if (!proved || "UNKNOWN".equals(attribution.agentStatus())) {
                complete=false;if (reason==Reason.NONE) reason=Reason.ATTRIBUTION_UNKNOWN;continue;
            }
            if (SupportAnalyticsService.firstReady(histories,f.customerId()) && "KNOWN".equals(attribution.agentStatus()))
                result.merge(attribution.agentAdminId(),1L,Math::addExact);
        }
        return new Financial(Map.copyOf(result),complete,reason);
    }
    private static Instant evaluatedFrom(Context c) {return c.referenceMonth().atDay(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();}
    private static Instant evaluatedTo(Context c) {return c.referenceMonth().plusMonths(1).atDay(1).atStartOfDay(SupportLeaderboard.BUSINESS_ZONE).toInstant();}
    private record Times(LocalDateTime start,LocalDateTime end) { }
    private static void rejectOverlaps(List<Times> intervals) {
        List<Times> sorted=intervals.stream().sorted(Comparator.comparing(Times::start)).toList();Times previous=null;
        for (Times t:sorted) {
            if (t.start().equals(t.end())) continue; // Empty half-open intervals cannot overlap or establish eligibility.
            if (previous!=null && (previous.end()==null || previous.end().isAfter(t.start()))) throw failed();
            previous=t;
        }
    }
    private static boolean active(LocalDateTime from,LocalDateTime to,LocalDateTime at) {return !from.isAfter(at) && (to==null || to.isAfter(at));}
    private static boolean binary(Integer v) {return v==0 || v==1;}
    private static void interval(Long id,Long agent,Long version,LocalDateTime start,LocalDateTime end,Map<Long,Account> accounts) {
        if (id==null || id<=0 || agent==null || !accounts.containsKey(agent) || version==null || version<1 || start==null
                || end!=null && end.isBefore(start)) throw failed();
    }
    private static <T,K extends Comparable<? super K>> List<T> ordered(List<T> values,java.util.function.Function<T,K> key) {
        if (values==null) throw failed();Set<K> keys=new HashSet<>();
        for (T value:values) if (value==null || key.apply(value)==null || !keys.add(key.apply(value))) throw failed();
        return values.stream().sorted(Comparator.comparing(key)).toList();
    }
    private static String fingerprint(Context context,Evidence raw,List<Candidate> candidates,Coverage coverage) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            write(out,"support-leaderboard-source-v1");write(out,context.board());write(out,context.rankMonth()==null?null:context.rankMonth().toString());
            write(out,context.referenceMonth().toString());write(out,context.currency());write(out,context.scope());
            write(out,context.approvedGroupIds().stream().sorted().toList());write(out,context.definitionVersion());
            write(out,raw.accounts());write(out,raw.qualifications());write(out,raw.memberships());write(out,raw.groups());write(out,raw.bindings());
            write(out,raw.productionCustomers());write(out,raw.attributions());write(out,raw.attributionProofs());
            SupportPaymentFacts.Snapshot s=raw.finance();write(out,s==null?null:s.businessZone());
            if (s!=null) {
                write(out,s.facts().stream().sorted(Comparator.comparing(Fact::factId)).toList());
                write(out,s.issues().stream().sorted(Comparator.comparing(Object::toString)).toList());
                write(out,s.coverage().stream().sorted(Comparator.comparing(c -> c.source().name())).toList());
                write(out,s.firstHistory().stream().sorted(Comparator.comparingLong(SupportPaymentFacts.FirstHistory::customerId)).toList());
            }
            // Evaluation clocks are reported separately; source identity is the complete read tuple and projection, not a clock.
            write(out,coverage);write(out,candidates);
            return "slbs-v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException | ReflectiveOperationException | NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    private static void write(DataOutputStream out,Object value) throws IOException,ReflectiveOperationException {
        if(value==null) {out.writeByte(0);return;}
        out.writeByte(1);
        if (value instanceof Collection<?> rows) {out.writeInt(rows.size());for(Object row:rows)write(out,row);return;}
        if (value.getClass().isRecord()) {
            write(out,value.getClass().getName());
            for(RecordComponent field:value.getClass().getRecordComponents()) {
                write(out,field.getName());write(out,field.getAccessor().invoke(value));
            }
            return;
        }
        out.writeByte(3);String text=value instanceof BigDecimal decimal?decimal.stripTrailingZeros().toPlainString():value.toString();
        byte[] encoded=(value.getClass().getName()+":"+text).getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded);
    }
    private static BizException failed() {return new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED");}
}

