package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import org.springframework.transaction.support.TransactionSynchronization;

/** Internal full-source adapter. The public owner authorizes before entry and rechecks before returning HTTP. */
@ApplicationService
public class SupportLeaderboardSourceService {
    private final SupportLeaderboardMapper mapper;
    private final FinanceSupportPaymentFactsFacade finance;
    private final SupportFundsReadFacade funds;
    public SupportLeaderboardSourceService(SupportLeaderboardMapper mapper,FinanceSupportPaymentFactsFacade finance,SupportFundsReadFacade funds) {
        this.mapper=Objects.requireNonNull(mapper);this.finance=Objects.requireNonNull(finance);this.funds=Objects.requireNonNull(funds);
    }
    /** Raw customer/payment/proof tuples stay internal and must never be serialized or logged by public controllers. */
    public record Evidence(List<Account> accounts,List<QualificationInterval> qualifications,List<MemberInterval> memberships,
                           List<Group> groups,List<Binding> bindings,List<Long> productionCustomers,List<AttributionRow> attributions,
                           List<AttributionProof> attributionProofs,SupportPaymentFacts.Snapshot finance,NetSnapshot net,
                           LocalDateTime qualificationCutoverUtc) {
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
        CapturedFacts captured=captureMaterial();
        return projectCaptured(captured,authorizedContext);
    }

    /** Internal material only: its constructor and evidence are not a public projection. */
    static final class CapturedFacts {
        private final LocalDateTime cutoff;
        private final Evidence evidence;
        private final TransactionSynchronization boundary;
        private CapturedFacts(LocalDateTime cutoff,Evidence evidence,TransactionSynchronization boundary) {
            this.cutoff=cutoff;this.evidence=evidence;this.boundary=boundary;
        }
        LocalDateTime cutoff() { return cutoff; }
    }

    /** System sampling owns this RR boundary; it never forges a signed-in principal or private ReadScope. */
    @Transactional(readOnly=true,propagation=Propagation.MANDATORY)
    public CapturedFacts captureForSampling() {
        if(!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("SUPPORT_LEADERBOARD_CAPTURE_SYNCHRONIZATION_REQUIRED");
        return captureMaterial();
    }

    private CapturedFacts captureMaterial() {
        requireReadBoundary();
        final LocalDateTime cutoff;
        final Evidence raw;
        try {
            cutoff=mapper.nowUtc();
            if (cutoff==null) throw failed();
            raw=readEvidence();
        } catch (DataAccessException ex) {throw failed();}
        TransactionSynchronization boundary=null;
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            boundary=new TransactionSynchronization() { };
            TransactionSynchronizationManager.registerSynchronization(boundary);
        }
        return new CapturedFacts(cutoff,raw,boundary);
    }

    private static void requireReadBoundary() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),Connection.TRANSACTION_REPEATABLE_READ))
            throw new IllegalStateException("SUPPORT_LEADERBOARD_CALLER_RR_REQUIRED");
    }

    private Evidence readEvidence() {
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
            NetSnapshot net=funds.readNetContributions(customers);
            if (net==null) throw failed();
            return new Evidence(accounts,qualifications,memberships,groups,bindings,customers,attributions,proofs,financial,net,
                mapper.qualificationCutoverUtc());
    }

    /** Pure reuse of the sole projection algorithm, only while the capturing RR transaction is active. */
    Read projectCaptured(CapturedFacts captured,Context authorizedContext) {
        requireReadBoundary();
        Objects.requireNonNull(captured);Objects.requireNonNull(authorizedContext);
        if(captured.boundary!=null && (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(captured.boundary)))
            throw new IllegalStateException("SUPPORT_LEADERBOARD_CAPTURE_BOUNDARY_CHANGED");
        LocalDateTime cutoff=captured.cutoff;
        Instant evaluatedAt=cutoff.toInstant(ZoneOffset.UTC);
        YearMonth currentMonth=YearMonth.from(evaluatedAt.atZone(SupportLeaderboard.BUSINESS_ZONE));
        Context context=new Context(authorizedContext.board(),authorizedContext.rankMonth(),
            authorizedContext.board()==Board.customers?currentMonth:authorizedContext.referenceMonth(),authorizedContext.currency(),
            authorizedContext.scope(),authorizedContext.approvedGroupIds(),authorizedContext.definitionVersion(),evaluatedAt);
        return project(context,cutoff,captured.evidence,currentMonth);
    }

    private static Read project(Context context,LocalDateTime cutoff,Evidence raw,YearMonth currentMonth) {
        Map<Long,Account> accounts=new TreeMap<>();
        for (Account a:raw.accounts()) {
            if (a.id()==null || a.id()<=0 || a.version()==null || a.version()<0 || a.status()==null || a.isDeleted()==null
                    || a.compatibleRole()==null || a.serviceProfile()==null || !binary(a.status()) || !binary(a.isDeleted())
                    || !binary(a.compatibleRole()) || !binary(a.serviceProfile())
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
        Map<Long,LocalDateTime> earliestOwnership=new HashMap<>();
        for (Binding b:raw.bindings()) {
            if (b.id()==null || b.id()<=0 || b.customerId()==null || !customerIds.contains(b.customerId())
                    || b.agentId()==null || !accounts.containsKey(b.agentId()) || b.version()==null || b.version()<1
                    || b.startsAt()==null || b.startsAt().isAfter(cutoff) || b.endsAt()!=null || !boundCustomers.add(b.customerId())) throw failed();
            bound.computeIfAbsent(b.agentId(),id -> new TreeSet<>()).add(b.customerId());
            earliestOwnership.merge(b.agentId(),b.startsAt(),(left,right) -> left.isBefore(right)?left:right);
        }
        Financial first=first(raw,customerIds,context,evaluatedFrom(context),evaluatedTo(context));
        Net net=net(raw,customerIds,context,accounts.keySet());
        for(AttributionRow row:raw.attributions())if("KNOWN".equals(row.agentStatus()) && row.agentAdminId()!=null) {
            LocalDateTime occurred=row.succeededAt()!=null && SupportLeaderboard.BUSINESS_ZONE.getId().equals(row.sourceBusinessZone())
                ? LocalDateTime.ofInstant(row.succeededAt().atZone(SupportLeaderboard.BUSINESS_ZONE).toInstant(),ZoneOffset.UTC)
                : LocalDateTime.MIN;
            earliestOwnership.merge(row.agentAdminId(),occurred,(left,right) -> left.isBefore(right)?left:right);
        }
        List<Candidate> candidates=new ArrayList<>();
        LocalDateTime monthFrom=LocalDateTime.ofInstant(evaluatedFrom(context),ZoneOffset.UTC);
        LocalDateTime cutover=raw.qualificationCutoverUtc();
        Coverage candidateCoverage=context.board()==Board.customers || cutover!=null && !monthFrom.isBefore(cutover)
            && !cutoff.isBefore(cutover)?Coverage.COMPLETE:Coverage.PARTIAL;
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
            List<MemberInterval> memberHistory=memberships.getOrDefault(a.id(),List.of());
            MemberInterval member=memberHistory.stream().filter(m -> active(m.startsAt(),m.endsAt(),cutoff)).findFirst().orElse(null);
            Long groupId=member==null?null:member.groupId();
            if (groupId!=null && !groups.containsKey(groupId)) throw failed();
            boolean inScope=context.scope()==Scope.all || groupId!=null && context.approvedGroupIds().contains(groupId);
            boolean attributed=net.amounts().containsKey(a.id()) || first.confirmedFirst().containsKey(a.id()) && (inScope || member==null);
            boolean needsHistory=!history.isEmpty() || !memberHistory.isEmpty() || customers>0 || attributed || a.serviceProfile()==1;
            if(context.board()!=Board.customers && needsHistory && (inScope || member==null || attributed)
                    && !completeHistory(a,history,memberHistory,groups.keySet(),monthFrom,cutoff,cutover,earliestOwnership.get(a.id())))candidateCoverage=Coverage.PARTIAL;
            boolean monthly=history.stream().anyMatch(q -> "ENABLED".equals(q.state())
                && !q.startsAt().isAfter(cutoff)
                && q.startsAt().toInstant(ZoneOffset.UTC).isBefore(monthEnd)
                && (q.endsAt()==null || q.endsAt().toInstant(ZoneOffset.UTC).isAfter(monthStart))
                && (q.endsAt()==null || q.endsAt().isAfter(q.startsAt())));
            if (!(context.board()==Board.customers ? serviceNow || customers>0 : monthly
                    || context.board()==Board.netContribution && net.amounts().containsKey(a.id())
                    || context.board()==Board.firstPayment && first.confirmedFirst().containsKey(a.id()))) continue;
            if (context.scope()!=Scope.all && (groupId==null || !context.approvedGroupIds().contains(groupId))
                    && !(context.board()==Board.netContribution && net.amounts().containsKey(a.id()))) continue;
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
            boolean amountComplete=net.reason()==Reason.NONE;
            BigDecimal known=net.amounts().get(a.id());boolean subtotal=known!=null && known.signum()!=0;
            Amount amount=new Amount(amountComplete?net.amounts().getOrDefault(a.id(),BigDecimal.ZERO):subtotal?known:null,
                context.currency(),context.referenceMonth(),AmountKind.NET_CONTRIBUTION,
                amountComplete?Coverage.COMPLETE:subtotal?Coverage.PARTIAL:Coverage.UNKNOWN,net.reason(),"financial-source-unverified");
            candidates.add(new Candidate(a.id(),name,avatar,groupId==null?"待分组":groups.get(groupId).name(),qualification,
                firstPayment,new Count(customers,Coverage.COMPLETE,Reason.NONE),amount,null));
        }
        String sourceVersion=fingerprint(context,raw,candidates,candidateCoverage);
        List<Candidate> versioned=new ArrayList<>();
        for (Candidate c:candidates) {
            Amount old=c.amount(); Amount amount=new Amount(old.value(),old.currency(),old.month(),old.kind(),old.coverage(),old.reason(),sourceVersion);
            versioned.add(new Candidate(c.agentId(),c.name(),c.avatarUrl(),c.groupName(),c.qualification(),c.firstPayment(),c.customers(),amount,null));
        }
        // Month selection remains current-only; older months are separately governed by saved publications.
        return new Read(context,sourceVersion,candidateCoverage,versioned,List.of(currentMonth),raw);
    }

    private static boolean completeHistory(Account account,List<QualificationInterval> qualifications,List<MemberInterval> members,
            Set<Long> groups,LocalDateTime from,LocalDateTime cutoff,LocalDateTime cutover,LocalDateTime earliestOwnership) {
        MemberInterval priorMember=null;
        for(MemberInterval m:members.stream().filter(m -> !m.startsAt().isAfter(cutoff)
                && (m.endsAt()==null || m.endsAt().isAfter(from) || m.startsAt().equals(m.endsAt()) && !m.startsAt().isBefore(from)))
                .sorted(Comparator.comparing(MemberInterval::startsAt).thenComparing(MemberInterval::version)).toList()) {
            if(priorMember!=null && m.version()!=priorMember.version()+1)return false;
            priorMember=m;
        }
        List<QualificationInterval> ordered=qualifications.stream().filter(q -> !q.startsAt().isAfter(cutoff))
            .sorted(Comparator.comparing(QualificationInterval::startsAt).thenComparing(QualificationInterval::version)).toList();
        LocalDateTime firstEnabled=ordered.stream().filter(q -> "ENABLED".equals(q.state()))
            .map(QualificationInterval::startsAt).min(LocalDateTime::compareTo).orElse(null);
        boolean inactivePrefixKnown=!ordered.isEmpty() && ordered.get(0).version()==1 && cutover!=null
            && !ordered.get(0).startsAt().isBefore(cutover) && (firstEnabled==null || firstEnabled.isAfter(from))
            && (firstEnabled==null ? earliestOwnership==null && members.isEmpty()
                : (earliestOwnership==null || !earliestOwnership.isBefore(firstEnabled))
                    && members.stream().allMatch(m -> !m.startsAt().isBefore(firstEnabled)));
        LocalDateTime historyFrom=inactivePrefixKnown && ordered.get(0).startsAt().isBefore(from)?ordered.get(0).startsAt():from;
        if(!ordered.isEmpty()) {
            QualificationInterval initial=ordered.get(0);
            // The post-cutover command writes first v1 only after confirming that no qualification history exists.
            if(initial.version()==1 && cutover!=null && !initial.startsAt().isBefore(cutover) && initial.startsAt().isAfter(from)
                    && (earliestOwnership==null || !earliestOwnership.isBefore(initial.startsAt()))
                    && members.stream().allMatch(m -> !m.startsAt().isBefore(initial.startsAt()))
                    && (members.isEmpty() || members.stream().min(Comparator.comparing(MemberInterval::startsAt)
                        .thenComparing(MemberInterval::version)).orElseThrow().version()==1))historyFrom=initial.startsAt();
        }
        QualificationInterval previous=null,current=null;
        for(QualificationInterval q:ordered) {
            boolean empty=q.startsAt().equals(q.endsAt());
            if(q.endsAt()!=null && !q.endsAt().isAfter(historyFrom) && !(empty && q.startsAt().equals(historyFrom)))continue;
            if(previous==null ? q.startsAt().isAfter(historyFrom)
                    : !Objects.equals(previous.endsAt(),q.startsAt()) || q.version()!=previous.version()+1)return false;
            LocalDateTime start=q.startsAt().isBefore(historyFrom)?historyFrom:q.startsAt();
            LocalDateTime end=q.endsAt()==null || q.endsAt().isAfter(cutoff)?cutoff:q.endsAt();
            boolean beforeFirstEnabled=inactivePrefixKnown && (firstEnabled==null || q.startsAt().isBefore(firstEnabled));
            if(!beforeFirstEnabled && !empty && !"REMOVED".equals(q.state())
                    && !completeMembers(members,groups,start,end,active(q.startsAt(),q.endsAt(),cutoff)))return false;
            if(active(q.startsAt(),q.endsAt(),cutoff))current=q;
            previous=q;
        }
        if(current==null)return false;
        // Account disable and qualification disable are independent; neither implies lost history.
        return inactivePrefixKnown && firstEnabled==null || "REMOVED".equals(current.state()) || account.isDeleted()==0 && account.compatibleRole()==1
            && account.profileVersion()!=null && account.profileVersion()>0 && Objects.equals(account.profileDeleted(),0)
            && account.profileEnabled()!=null && account.profileSeatType()!=null && !account.profileSeatType().isBlank();
    }

    private static boolean completeMembers(List<MemberInterval> members,Set<Long> groups,LocalDateTime from,LocalDateTime to,boolean inclusiveEnd) {
        MemberInterval previous=null;
        for(MemberInterval m:members.stream().sorted(Comparator.comparing(MemberInterval::startsAt).thenComparing(MemberInterval::version)).toList()) {
            if(m.endsAt()!=null && !m.endsAt().isAfter(from) && !(m.startsAt().equals(m.endsAt()) && m.startsAt().equals(from)))continue;
            if(m.startsAt().isAfter(to) || !inclusiveEnd && m.startsAt().equals(to))break;
            if(m.groupId()!=null && !groups.contains(m.groupId()))return false;
            if(previous==null ? m.startsAt().isAfter(from)
                    : !Objects.equals(previous.endsAt(),m.startsAt()) || m.version()!=previous.version()+1)return false;
            if(m.endsAt()==null || m.endsAt().isAfter(to) || !inclusiveEnd && m.endsAt().equals(to))return true;
            previous=m;
        }
        return false;
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
        if (snapshot.issues().stream().anyMatch(i -> i.reason()==null
                || i.reason().contains("READ_FAILED") && i.customerId()==null && firstSource(i.source()))) throw failed();
        if(snapshot.coverage().stream().anyMatch(c -> firstSource(c.source())
                && c.reasons().stream().anyMatch(r -> r.contains("READ_FAILED"))
                && snapshot.issues().stream().noneMatch(i -> i.source()==c.source() && i.reason()!=null && i.reason().contains("READ_FAILED"))))throw failed();
        if(snapshot.firstHistory().stream().anyMatch(h -> h.reasons().stream().anyMatch(r -> r.contains("READ_FAILED")))
            && snapshot.issues().stream().noneMatch(i -> i.reason()!=null && i.reason().contains("READ_FAILED")))throw failed();
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
            if (!proved(attribution,f,snapshot) || "UNKNOWN".equals(attribution.agentStatus())) {
                complete=false;if (reason==Reason.NONE) reason=Reason.ATTRIBUTION_UNKNOWN;continue;
            }
            if (SupportAnalyticsService.firstReady(histories,f.customerId()) && "KNOWN".equals(attribution.agentStatus()))
                result.merge(attribution.agentAdminId(),1L,Math::addExact);
        }
        return new Financial(Map.copyOf(result),complete,reason);
    }
    private static boolean firstSource(SupportPaymentFacts.Source source) {
        return source!=SupportPaymentFacts.Source.ORDER_REFUND && !SupportPaymentFacts.genesis(source);
    }
    private static boolean proved(AttributionRow attribution,Fact fact,SupportPaymentFacts.Snapshot snapshot) {
        return attribution!=null && SupportAnalyticsService.aligned(attribution,fact,snapshot.businessZone())
            && SupportAnalyticsService.validLayers(attribution) && !SupportAnalyticsService.proofRejected(snapshot,fact);
    }
    private record Net(Map<Long,BigDecimal> amounts,Reason reason) { }
    private static Net net(Evidence raw,Set<Long> ids,Context context,Set<Long> agents) {
        Map<Long,NetCustomer> customers=new HashMap<>();Reason reason=Reason.NONE;
        for(NetCustomer customer:raw.net().customers()) {
            if(!ids.contains(customer.customerId()) || customer.state()==null
                    || customers.putIfAbsent(customer.customerId(),customer)!=null
                    || customer.reasons().stream().anyMatch(r->r==null || r.isBlank()))throw failed();
            if(customer.state()==EvidenceState.READY) {
                if(!customer.reasons().isEmpty() || customer.usdt()==null || customer.nex()==null
                        || customer.usdt().signum()<0 || customer.nex().signum()<0)throw failed();
            } else {
                if(customer.reasons().isEmpty() || customer.usdt()!=null || customer.nex()!=null)throw failed();
                reason=Reason.SOURCE_INCOMPLETE;
            }
        }
        if(!customers.keySet().equals(ids))reason=Reason.SOURCE_INCOMPLETE;
        Map<String,Fact> facts=new HashMap<>();
        if(raw.finance()!=null)for(Fact fact:raw.finance().facts())facts.put(fact.factId(),fact);
        Map<String,AttributionRow> attributions=new HashMap<>();raw.attributions().forEach(r->attributions.put(r.factId(),r));
        Map<String,AttributionProof> proofs=new HashMap<>();raw.attributionProofs().forEach(p->proofs.put(p.factId(),p));
        Map<String,ContributionOrigin> origins=new HashMap<>();Set<String> invalidOrigins=new HashSet<>();
        for(ContributionOrigin origin:raw.net().origins()) {
            NetCustomer customer=customers.get(origin.customerId());
            if(customer==null || customer.state()!=EvidenceState.READY || !token(origin.contributionId())
                    || origins.putIfAbsent(origin.contributionId(),origin)!=null
                    || !stamp(origin.formedAt(),origin.businessZone(),origin.fractionalSecondDigits(),origin.sourceReference()))throw failed();
            Fact fact=facts.get(origin.paymentFactId());AttributionRow attribution=attributions.get(origin.paymentFactId());
            if(fact==null || fact.customerId()!=origin.customerId() || fact.kind()==Kind.DEVICE_PURCHASE_REFUND
                    || !Objects.equals(fact.succeededAt(),origin.formedAt()) || fact.fractionalSecondDigits()!=origin.fractionalSecondDigits()
                    || !proved(attribution,fact,raw.finance()) || !savedProof(proofs.get(origin.paymentFactId()),attribution,fact)
                    || "UNKNOWN".equals(attribution.agentStatus()) || "UNKNOWN".equals(attribution.groupStatus())
                    || "UNKNOWN".equals(attribution.ownerStatus())
                    || "KNOWN".equals(attribution.agentStatus()) && !agents.contains(attribution.agentAdminId())) {
                invalidOrigins.add(origin.contributionId());reason=Reason.ATTRIBUTION_UNKNOWN;
            }
        }
        Map<Long,Map<Long,BigDecimal>> byCustomer=new HashMap<>();Map<String,BigDecimal> totals=new HashMap<>();
        Map<String,BigDecimal> formations=new HashMap<>();Set<Long> rejectedRoots=new HashSet<>();
        Instant from=evaluatedFrom(context),to=evaluatedTo(context);
        for(ContributionChange change:raw.net().changes()) {
            ContributionOrigin origin=origins.get(change.contributionId());
            if(origin==null || origin.customerId()!=change.originCustomerId() || change.amount()==null
                    || !Set.of("USDT","NEX").contains(change.currency())
                    || !stamp(change.succeededAt(),change.businessZone(),change.fractionalSecondDigits(),change.sourceReference())
                    || change.succeededAt().isBefore(origin.formedAt()))throw failed();
            totals.merge(change.originCustomerId()+":"+change.currency(),change.amount(),BigDecimal::add);
            if(invalidOrigins.contains(origin.contributionId()))continue;
            Fact fact=facts.get(origin.paymentFactId());
            if(!change.currency().equals(fact.currency()))throw failed();
            // Replay exposes contribution changes per lot, without a unique leg ID. Equal rows can be distinct lots.
            if(change.amount().signum()>0) {
                if(change.succeededAt().equals(origin.formedAt()) && change.businessZone().equals(origin.businessZone())
                        && change.fractionalSecondDigits()==origin.fractionalSecondDigits()
                        && change.sourceReference().equals(origin.sourceReference()))
                    formations.merge(origin.contributionId(),change.amount(),BigDecimal::add);
                else {reason=Reason.SOURCE_INCOMPLETE;rejectedRoots.add(origin.customerId());}
            }
            Instant occurred=change.succeededAt().atZone(SupportLeaderboard.BUSINESS_ZONE).toInstant();
            if(!context.currency().equals(change.currency()) || occurred.isBefore(from) || !occurred.isBefore(to)
                    || occurred.isAfter(context.evaluatedAt()))continue;
            AttributionRow attribution=attributions.get(origin.paymentFactId());
            if(!"KNOWN".equals(attribution.agentStatus()))continue;
            if(context.scope()!=Scope.all && (attribution.groupId()==null || !context.approvedGroupIds().contains(attribution.groupId())))continue;
            byCustomer.computeIfAbsent(change.originCustomerId(),id->new HashMap<>()).merge(attribution.agentAdminId(),change.amount(),BigDecimal::add);
        }
        for(NetCustomer customer:customers.values())if(customer.state()==EvidenceState.READY) {
            if(totals.getOrDefault(customer.customerId()+":USDT",BigDecimal.ZERO).compareTo(customer.usdt())!=0
                    || totals.getOrDefault(customer.customerId()+":NEX",BigDecimal.ZERO).compareTo(customer.nex())!=0) {
                reason=Reason.SOURCE_INCOMPLETE;rejectedRoots.add(customer.customerId());
            }
        }
        for(ContributionOrigin origin:origins.values())if(!invalidOrigins.contains(origin.contributionId())) {
            Fact fact=facts.get(origin.paymentFactId());BigDecimal formed=formations.get(origin.contributionId());
            // Deposits credit the whole payment; consumption credits only the previously uncredited portion.
            if(formed==null || formed.signum()<=0 || formed.compareTo(fact.amount())>0
                    || fact.kind()!=Kind.DEPOSIT && fact.kind()!=Kind.DEVICE_PURCHASE && fact.kind()!=Kind.CONSUMPTION
                    || fact.kind()==Kind.DEPOSIT && formed.compareTo(fact.amount())!=0) {
                reason=Reason.SOURCE_INCOMPLETE;rejectedRoots.add(origin.customerId());
            }
        }
        Map<Long,BigDecimal> amounts=new HashMap<>();
        byCustomer.forEach((customer,values)->{if(!rejectedRoots.contains(customer))values.forEach((agent,value)->amounts.merge(agent,value,BigDecimal::add));});
        return new Net(Map.copyOf(amounts),reason);
    }
    private static boolean token(String value) {return value!=null && !value.isBlank() && value.length()<=512;}
    private static boolean stamp(LocalDateTime at,String zone,int precision,String reference) {
        return at!=null && SupportLeaderboard.BUSINESS_ZONE.getId().equals(zone) && precision>=0 && precision<=9
            && at.getNano()%(int)Math.pow(10,9-precision)==0 && token(reference);
    }
    private static boolean savedProof(AttributionProof proof,AttributionRow attribution,Fact fact) {
        if(proof==null || proof.captureDbUtc()==null || proof.sourceFactJson()==null || proof.attributionEvidenceJson()==null)return false;
        try {
            var reader=new ObjectMapper().reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            JsonNode source=reader.readTree(proof.sourceFactJson()),evidence=reader.readTree(proof.attributionEvidenceJson());
            return source.isObject() && evidence.isObject() && jsonText(source,"factId",fact.factId())
                && jsonId(source,"customerId",fact.customerId()) && jsonId(source,"ledgerId",fact.ledgerId())
                && jsonText(source,"kind",fact.kind().name()) && jsonText(source,"source",fact.source().name())
                && jsonText(source,"currency",fact.currency()) && source.path("amount").isNumber()
                && source.path("amount").decimalValue().compareTo(fact.amount())==0
                && LocalDateTime.parse(source.path("succeededAt").textValue()).equals(fact.succeededAt())
                && jsonText(source,"businessZone",attribution.sourceBusinessZone())
                && jsonId(source,"fractionalSecondDigits",(long)fact.fractionalSecondDigits())
                && jsonText(evidence,"schemaVersion",attribution.captureSchemaVersion())
                && jsonText(evidence,"captureMode",attribution.captureMode())
                && jsonText(evidence,"captureZone","UTC")
                && LocalDateTime.parse(evidence.path("captureDbUtc").textValue()).equals(proof.captureDbUtc())
                && jsonText(evidence,"sourcePartition",proof.sourcePartition())
                && jsonLayer(evidence.path("agent"),attribution.agentStatus(),attribution.agentAdminId())
                && jsonLayer(evidence.path("group"),attribution.groupStatus(),attribution.groupId())
                && jsonLayer(evidence.path("owner"),attribution.ownerStatus(),attribution.ownerAdminId());
        }catch(IOException | IllegalArgumentException | DateTimeException | NullPointerException ex){return false;}
    }
    private static boolean jsonText(JsonNode node,String field,String expected) {
        JsonNode value=node.get(field);return value!=null && (expected==null?value.isNull():value.isTextual() && expected.equals(value.textValue()));
    }
    private static boolean jsonId(JsonNode node,String field,Long expected) {
        JsonNode value=node.get(field);return value!=null && (expected==null?value.isNull():value.isIntegralNumber()
            && value.canConvertToLong() && value.longValue()==expected);
    }
    private static boolean jsonLayer(JsonNode layer,String status,Long id) {
        return layer.isObject() && jsonText(layer,"status",status) && jsonId(layer,"id",id);
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
            write(out,"support-leaderboard-source-v3-net");write(out,SupportLeaderboardPolicy.DEFINITION);
            write(out,context.board());write(out,context.rankMonth()==null?null:context.rankMonth().toString());
            write(out,context.referenceMonth().toString());write(out,context.currency());write(out,context.scope());
            write(out,context.approvedGroupIds().stream().sorted().toList());write(out,context.definitionVersion());
            write(out,raw.accounts());write(out,raw.qualifications());write(out,raw.memberships());write(out,raw.groups());write(out,raw.bindings());
            write(out,raw.qualificationCutoverUtc());
            write(out,raw.productionCustomers());write(out,raw.attributions());write(out,raw.attributionProofs());
            write(out,raw.net().customers().stream().sorted(Comparator.comparingLong(NetCustomer::customerId)).toList());
            write(out,raw.net().origins().stream().sorted(Comparator.comparing(ContributionOrigin::contributionId)).toList());
            write(out,raw.net().changes().stream().sorted(Comparator.comparing(Object::toString)).toList());
            SupportPaymentFacts.Snapshot s=raw.finance();write(out,s==null?null:s.businessZone());
            if (s!=null) {
                write(out,s.facts().stream().sorted(Comparator.comparing(Fact::factId)).toList());
                write(out,s.issues().stream().sorted(Comparator.comparing(Object::toString)).toList());
                write(out,s.coverage().stream().sorted(Comparator.comparing(c -> c.source().name())).toList());
                write(out,s.firstHistory().stream().sorted(Comparator.comparingLong(SupportPaymentFacts.FirstHistory::customerId)).toList());
                write(out,s.customerCoverage().stream().sorted(Comparator.comparingLong(SupportPaymentFacts.CustomerCoverage::customerId)
                    .thenComparing(c -> c.source().name())).toList());
            }
            // Evaluation clocks are reported separately; source identity is the complete read tuple and projection, not a clock.
            write(out,coverage);write(out,candidates);
            return "slbs-v3:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
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

