package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.Normalized;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.ServiceFilter;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.Sort;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.team.facade.SupportInvitationReadFacade;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Internal, explicit observation fingerprint. Does not authorize, query a database or retain prior snapshots. */
public final class SupportAnalyticsQueryVersion {
    private SupportAnalyticsQueryVersion() { }
    private static final Set<SupportPaymentFacts.Source> CANONICAL_SOURCES = Set.of(
        SupportPaymentFacts.Source.DEPOSIT_ORDER, SupportPaymentFacts.Source.CARD_TOPUP,
        SupportPaymentFacts.Source.VIETQR, SupportPaymentFacts.Source.HDPAY, SupportPaymentFacts.Source.WALLET_ORDER,
        SupportPaymentFacts.Source.TRADE_IN, SupportPaymentFacts.Source.CAPACITY_KEEP,
        SupportPaymentFacts.Source.TRIAL_CONVERT, SupportPaymentFacts.Source.ORDER_REFUND);
    public enum Status { READY, UNKNOWN, FAILED }
    public enum ReadState { COMPLETE, UNKNOWN, FAILED, NOT_REQUESTED }
    public enum Proof { ACCEPTED, REJECTED, MISSING }
    public enum RelationKind { ASSIGNMENT, ROUTE, MEMBERSHIP, GROUP_OWNER }
    public enum Acquisition { PAID_PURCHASE, UNKNOWN }
    public record Version(Status status, String value) {
        public Version {
            if (status == null || (status == Status.READY ? !SupportAnalyticsQueryRequest.validVersion(value) : value != null))
                throw invalid();
        }
    }
    /** Every COMPLETE assertion is supplied only by the authorized RR reader after a full, untruncated read. */
    public record Reads(ReadState authority, ReadState candidates, ReadState finance,
            ReadState attribution, ReadState invitations, ReadState devices, ReadState activity, ReadState tasks) { }
    public record Personnel(long adminId, Integer accountStatus, Long accountVersion,
            Boolean profileEnabled, Boolean profileDeleted, Long profileVersion,String displayName,String profileSeatType,String avatarAssetId,Long avatarVersion) {
        public Personnel(long adminId,Integer accountStatus,Long accountVersion,Boolean profileEnabled,Boolean profileDeleted,Long profileVersion,String displayName,String profileSeatType){this(adminId,accountStatus,accountVersion,profileEnabled,profileDeleted,profileVersion,displayName,profileSeatType,null,null);}
        public Personnel(long adminId,Integer accountStatus,Long accountVersion,Boolean profileEnabled,Boolean profileDeleted,Long profileVersion){this(adminId,accountStatus,accountVersion,profileEnabled,profileDeleted,profileVersion,null,null);}
    }
    public record Role(long relationId, long roleId, long adminId, String roleCode, Integer status) { }
    public record Permission(long roleRelationId, long roleId, long rolePermissionId, long permissionId,
            String permissionCode, Integer roleStatus, Integer permissionStatus) { }
    public record Qualification(long id, long adminId, String kind, String state, Long version,
            LocalDateTime startsAt, LocalDateTime endsAt) { }
    public record Group(long id, String name, Long supervisorAdminId, String status, Long version) { }
    public record Relationship(RelationKind kind, long id, Long customerId, Long agentId, Long groupId,
            Long ownerId, String state, Boolean deleted, Long version, LocalDateTime startsAt, LocalDateTime endsAt) { }
    public record Authority(ReadScope scope, List<Long> authorizedGroupIds, List<Personnel> personnel,
            List<Role> roles, List<Permission> permissions, List<Qualification> qualifications,
            List<Group> groups, List<Relationship> relationships) { }
    /** Display/filter/sort inputs only. Do not supply an entire customer profile or private contact details. */
    public record Customer(long customerId, String category, String placement, boolean handoverRequired,
            Long agentId, Long groupId, String keywordDisplayName, String state,
            LocalDateTime registeredAt, LocalDateTime assignedAt, LocalDateTime stateChangedAt,
            LocalDateTime poolEnteredAt, Long poolVersion, String avatarAssetId, Long avatarVersion) { }
    public record EventCandidate(String factId, long customerId) { }
    /** Customer object key is internal fingerprint evidence, never an asset/version invented for HTTP. */
    public record Profile(long customerId,LocalDateTime sourceUpdatedAt,String avatarObjectKey,String vRank,String userLevel,List<Tag> tags) { }
    public record Tag(long id,String tag,LocalDateTime createdAt,LocalDateTime updatedAt) { }
    public record First(long customerId, String canonicalFactId, String firstState, String selectionStatus,
            Proof attributionProof, List<String> reasons) { }
    /** Financial tuple must be the saved row, not a replacement copied from the newly evaluated fact. */
    public record Attribution(String factId, long customerId, String kind, String source, Long ledgerId,
            String sourceBusinessId, String orderNo, String orderType, String originalFactId, String currency,
            BigDecimal amount, LocalDateTime succeededAt, String sourceBusinessZone, String successTimeField,
            Integer fractionalSecondDigits, String captureMode, String captureSchemaVersion,
            Long agentAdminId, Long groupId, Long ownerAdminId, String agentStatus, String groupStatus,
            String ownerStatus, String sourcePartition, Proof validation) { }
    /** rowVersion is optional only where the existing reader does not expose it; derived semantics still required. */
    public record Device(SupportDeviceReadFacade.DeviceEvidence evidence, Long rowVersion,
            Acquisition acquisition, String purchaseFactId, List<String> acquisitionReasons) { }
    public record Activity(long customerId, Long lastEventId, Long activitySeq, String sourceRef,
            LocalDateTime lastEffectiveAt, String windowState, String coverageState) { }
    public record ActivityCoverage(LocalDateTime coverageStartAt, LocalDateTime observedThroughAt,
            LocalDateTime windowStartAt, LocalDateTime windowEndAt) { }
    public record PendingReply(long messageId,String conversationNo,Long throughMessageId,LocalDateTime createdAt) { }
    /** Required only when an approved service filter or task sort participates. No task collection/writer is added. */
    public record Task(long customerId, Boolean enabled, Long preferenceVersion, Long cycleId,
            String cycleStatus, Long executionId, String executionStatus, LocalDateTime lastExecutionAt,
            LocalDateTime lastSucceededAt, LocalDateTime nextDueAt, Boolean firstContact, Long contactFactId,
            String pendingConversationNo, Long pendingThroughMessageId, Long replyCursor,
            LocalDateTime waitingSinceAt, String stoppedReason,Boolean due,Boolean waitingReply,
            Boolean preferencePresent,Long pendingReplyCount,String activityStatus,String windowStatus,
            List<PendingReply> pendingReplies,LocalDateTime preferenceUpdatedAt) { }
    /** Lists are full authorized candidate/source sets; never pass page records or totals as substitute evidence. */
    public record Evidence(Reads reads, Authority authority, List<Long> candidateCustomerIds,
            List<Customer> customers, List<EventCandidate> eventCandidates, SupportRules rules,
            SupportPaymentFacts.Snapshot finance, List<Attribution> attributions, List<First> first,
            List<SupportInvitationReadFacade.Invitation> invitations, List<Device> devices,
            List<Device> unknownHoldingDevices, ActivityCoverage activityCoverage,
            List<Activity> activities, List<Task> tasks,List<Long> legacyCandidateCustomerIds,
            SupportPaymentFacts.Snapshot descendantFinance,ReadState descendantFinanceRead,
            List<Profile> profiles,ReadState profilesRead,SupportFundsReadFacade.Snapshot funds,ReadState fundsRead) {
        public Evidence(Reads reads,Authority authority,List<Long> candidateCustomerIds,List<Customer> customers,List<EventCandidate> eventCandidates,SupportRules rules,
                SupportPaymentFacts.Snapshot finance,List<Attribution> attributions,List<First> first,List<SupportInvitationReadFacade.Invitation> invitations,
                List<Device> devices,List<Device> unknownHoldingDevices,ActivityCoverage activityCoverage,List<Activity> activities,List<Task> tasks,
                List<Long> legacyCandidateCustomerIds,SupportPaymentFacts.Snapshot descendantFinance,ReadState descendantFinanceRead) {
            this(reads,authority,candidateCustomerIds,customers,eventCandidates,rules,finance,attributions,first,invitations,devices,unknownHoldingDevices,activityCoverage,activities,tasks,
                legacyCandidateCustomerIds,descendantFinance,descendantFinanceRead,List.of(),ReadState.NOT_REQUESTED,null,ReadState.NOT_REQUESTED);
        }
        public Evidence(Reads reads,Authority authority,List<Long> candidateCustomerIds,List<Customer> customers,
                List<EventCandidate> eventCandidates,SupportRules rules,SupportPaymentFacts.Snapshot finance,List<Attribution> attributions,
                List<First> first,List<SupportInvitationReadFacade.Invitation> invitations,List<Device> devices,List<Device> unknownHoldingDevices,
                ActivityCoverage activityCoverage,List<Activity> activities,List<Task> tasks) {
            this(reads,authority,candidateCustomerIds,customers,eventCandidates,rules,finance,attributions,first,invitations,devices,
                unknownHoldingDevices,activityCoverage,activities,tasks,List.of(),null,ReadState.NOT_REQUESTED);
        }
    }

    public static Version evaluate(Normalized query, Evidence input) {
        Objects.requireNonNull(query, "normalized query");
        if (input == null) return new Version(Status.UNKNOWN, null);
        // An incomplete domain cannot hide an explicit read failure elsewhere.
        if (failed(input)) return new Version(Status.FAILED, null);
        if (input.reads() == null) return new Version(Status.UNKNOWN, null);
        Reads r = input.reads();
        if(input.profilesRead()!=ReadState.COMPLETE && input.profilesRead()!=ReadState.NOT_REQUESTED
                || input.fundsRead()!=ReadState.COMPLETE && input.fundsRead()!=ReadState.NOT_REQUESTED)return new Version(Status.UNKNOWN,null);
        boolean emptyFinance=input.candidateCustomerIds()!=null && input.candidateCustomerIds().isEmpty() && input.finance()==null && r.finance()==ReadState.NOT_REQUESTED;
        List<ReadState> required = new ArrayList<>(Arrays.asList(r.authority(), r.candidates(),
                r.attribution(), r.invitations(), r.devices(), r.activity()));
        if(!emptyFinance)required.add(r.finance());
        if (needsTasks(query)) required.add(r.tasks());
        if (required.contains(ReadState.FAILED)) return new Version(Status.FAILED, null);
        if (required.stream().anyMatch(s -> s != ReadState.COMPLETE)) return new Version(Status.UNKNOWN, null);
        if (r.tasks() == null || (!needsTasks(query) && r.tasks() != ReadState.COMPLETE && r.tasks() != ReadState.NOT_REQUESTED))
            return new Version(r.tasks() == ReadState.FAILED ? Status.FAILED : Status.UNKNOWN, null);
        if (!present(input, needsTasks(query))) return new Version(Status.UNKNOWN, null);
        if (!Objects.equals(query.groupId(), input.authority().scope().requestedGroupId())
                || !Objects.equals(query.agentId(), input.authority().scope().requestedAgentId())) throw invalid();
        if (!emptyFinance && !SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId().equals(input.finance().businessZone())) throw invalid();
        if (!emptyFinance && !input.finance().coverage().stream().map(SupportPaymentFacts.Coverage::source).collect(java.util.stream.Collectors.toSet())
                .containsAll(CANONICAL_SOURCES)) return new Version(Status.UNKNOWN, null);
        for (var issue : emptyFinance?List.<SupportPaymentFacts.Issue>of():input.finance().issues()) {
            if (issue.reason() == null) return new Version(Status.UNKNOWN, null);
            if (issue.reason().contains("READ_FAILED")) return new Version(Status.FAILED, null);
        }
        if (!emptyFinance && input.finance().firstHistory().stream().flatMap(h -> h.reasons().stream()).anyMatch(reason -> reason.contains("READ_FAILED")))
            return new Version(Status.FAILED, null);
        if (!emptyFinance && input.finance().coverage().stream().flatMap(c->c.reasons().stream()).anyMatch(reason->reason.contains("READ_FAILED")))
            return new Version(Status.FAILED,null);
        if (!emptyFinance && input.finance().coverage().stream().anyMatch(c -> c.observedStatus() != SupportPaymentFacts.Status.READY))
            return new Version(Status.UNKNOWN, null);
        for (var tree : input.invitations()) {
            if (tree.completeness() == SupportInvitationReadFacade.Completeness.FAILED
                    || tree.reasons().contains(SupportInvitationReadFacade.Reason.SOURCE_READ_FAILED))
                return new Version(Status.FAILED, null);
            if (tree.completeness() != SupportInvitationReadFacade.Completeness.COMPLETE)
                return new Version(Status.UNKNOWN, null);
        }
        boolean extraNeeded=query.basis()!=ffdd.opsconsole.content.domain.SupportAnalyticsStats.Basis.CURRENT_ASSET && input.invitations().stream()
            .flatMap(t->t.descendantCustomerIds().stream()).anyMatch(id->!input.candidateCustomerIds().contains(id));
        if(input.descendantFinanceRead()==ReadState.FAILED)return new Version(Status.FAILED,null);
        if(extraNeeded && (input.descendantFinanceRead()!=ReadState.COMPLETE || input.descendantFinance()==null))return new Version(Status.UNKNOWN,null);
        if(input.descendantFinance()!=null) {
            if(!SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId().equals(input.descendantFinance().businessZone()))throw invalid();
            if(input.descendantFinance().coverage().stream().flatMap(c->c.reasons().stream()).anyMatch(reason->reason.contains("READ_FAILED")))return new Version(Status.FAILED,null);
            if(!input.descendantFinance().coverage().stream().map(SupportPaymentFacts.Coverage::source).collect(java.util.stream.Collectors.toSet()).containsAll(CANONICAL_SOURCES))return new Version(Status.UNKNOWN,null);
            if(input.descendantFinanceRead()!=ReadState.COMPLETE || input.descendantFinance().coverage().stream().anyMatch(c->c.observedStatus()!=SupportPaymentFacts.Status.READY))return new Version(Status.UNKNOWN,null);
            if(input.descendantFinance().issues().stream().anyMatch(i->i.reason()!=null && i.reason().contains("READ_FAILED")))return new Version(Status.FAILED,null);
            if(input.descendantFinance().firstHistory().stream().flatMap(h->h.reasons().stream()).anyMatch(reason->reason.contains("READ_FAILED")))return new Version(Status.FAILED,null);
            Set<Long> extraIds=new TreeSet<>();input.invitations().forEach(t->extraIds.addAll(t.descendantCustomerIds()));extraIds.removeAll(input.candidateCustomerIds());
            if(!extraIds.equals(new TreeSet<>(input.descendantFinance().firstHistory().stream().map(SupportPaymentFacts.FirstHistory::customerId).toList())))return new Version(Status.UNKNOWN,null);
            if(input.descendantFinance().facts().stream().anyMatch(f->!extraIds.contains(f.customerId())))throw invalid();
        }
        if (!covered(input)) return new Version(Status.UNKNOWN, null);
        Set<Long> roots=new TreeSet<>(input.customers().stream().map(Customer::customerId).toList());
        if(input.profilesRead()==ReadState.COMPLETE && (input.profiles()==null || !roots.equals(new TreeSet<>(input.profiles().stream().map(Profile::customerId).toList()))
            || input.profiles().stream().anyMatch(p->p.sourceUpdatedAt()==null || p.tags()==null)))return new Version(Status.UNKNOWN,null);
        if(input.fundsRead()==ReadState.COMPLETE && (input.funds()==null || fundsObservation(input.funds(),roots)!=ReadState.COMPLETE))return new Version(Status.UNKNOWN,null);
        // Freeze encoded bytes in this call. Later mutation cannot alter the returned digest.
        Encoder out = new Encoder();
        out.s("saq-v1"); encodeQuery(out, query); encodeAuthority(out, input.authority());
        out.ids(input.candidateCustomerIds());
        out.rows("customers", input.customers(), Customer::customerId, SupportAnalyticsQueryVersion::customer);
        out.rows("eventCandidates", input.eventCandidates(), EventCandidate::factId,
                c -> bytes(e -> { e.s(c.factId()); e.n(c.customerId()); }));
        rules(out, input.rules());out.s(emptyFinance?"EMPTY_AUTHORIZED_FINANCIAL_SCOPE":"OBSERVED_FINANCE");if(!emptyFinance)finance(out, input.finance());
        out.s("legacyCandidateCustomerIds");out.ids(input.legacyCandidateCustomerIds());
        out.s("descendantFinance");out.en(input.descendantFinanceRead());if(input.descendantFinance()!=null)finance(out,input.descendantFinance());
        out.rows("attributions", input.attributions(), Attribution::factId, SupportAnalyticsQueryVersion::attribution);
        out.rows("first", input.first(), First::customerId, f -> bytes(e -> {
            e.n(f.customerId()); e.s(f.canonicalFactId()); e.s(f.firstState()); e.s(f.selectionStatus());
            e.en(f.attributionProof()); e.strings(f.reasons()); }));
        out.rows("invitations", input.invitations(), SupportInvitationReadFacade.Invitation::rootCustomerId,
                t -> bytes(e -> { e.n(t.rootCustomerId()); e.n(t.rootSandbox()); e.ids(t.directCustomerIds());
                    e.ids(t.descendantCustomerIds()); e.en(t.completeness()); e.strings(t.reasons().stream().map(Enum::name).toList()); }));
        out.rows("devices", input.devices(), d -> d.evidence().deviceId(), SupportAnalyticsQueryVersion::device);
        out.rows("unknownHoldingDevices", input.unknownHoldingDevices(), d -> d.evidence().deviceId(), SupportAnalyticsQueryVersion::device);
        var c = input.activityCoverage(); out.s("activityCoverage"); out.time(c.coverageStartAt()); out.time(c.observedThroughAt());
        out.time(c.windowStartAt()); out.time(c.windowEndAt());
        out.rows("activities", input.activities(), Activity::customerId, a -> bytes(e -> {
            e.n(a.customerId()); e.n(a.lastEventId()); e.n(a.activitySeq()); e.s(a.sourceRef());
            e.time(a.lastEffectiveAt()); e.s(a.windowState()); e.s(a.coverageState()); }));
        out.s("tasks"); out.en(r.tasks());
        if (r.tasks() == ReadState.COMPLETE) out.rows("taskRows", input.tasks(), Task::customerId, SupportAnalyticsQueryVersion::task);
        out.s("profiles");out.en(input.profilesRead());
        if(input.profilesRead()==ReadState.COMPLETE)out.rows("profileRows",input.profiles(),Profile::customerId,p->bytes(e->{
            e.n(p.customerId());e.time(p.sourceUpdatedAt());e.s(p.avatarObjectKey());e.s(p.vRank());e.s(p.userLevel());
            e.rows("tags",p.tags(),Tag::id,t->bytes(x->{x.n(t.id());x.s(t.tag());x.time(t.createdAt());x.time(t.updatedAt());}));}));
        out.s("currentFunds");out.en(input.fundsRead());if(input.fundsRead()==ReadState.COMPLETE)funds(out,input.funds());
        return new Version(Status.READY, "saq-v1:" + sha256(out.bytes()));
    }

    private static boolean failed(Evidence input) {
        Reads r=input.reads();
        if(r!=null && Arrays.asList(r.authority(),r.candidates(),r.finance(),r.attribution(),r.invitations(),r.devices(),r.activity(),r.tasks()).contains(ReadState.FAILED)
                || input.descendantFinanceRead()==ReadState.FAILED || input.profilesRead()==ReadState.FAILED || input.fundsRead()==ReadState.FAILED
                || failedFunds(input.funds()) || failedFinance(input.finance()) || failedFinance(input.descendantFinance()))return true;
        if(input.first()!=null && input.first().stream().filter(Objects::nonNull).anyMatch(f->readFailed(f.reasons())))return true;
        return input.invitations()!=null && input.invitations().stream().filter(Objects::nonNull).anyMatch(t->
            t.completeness()==SupportInvitationReadFacade.Completeness.FAILED || t.reasons().contains(SupportInvitationReadFacade.Reason.SOURCE_READ_FAILED));
    }
    private static boolean failedFinance(SupportPaymentFacts.Snapshot snapshot) {
        return snapshot!=null && (snapshot.issues().stream().anyMatch(i->i.reason()!=null && i.reason().contains("READ_FAILED"))
            || snapshot.coverage().stream().anyMatch(c->readFailed(c.reasons()))
            || snapshot.firstHistory().stream().anyMatch(h->readFailed(h.reasons())));
    }
    private static boolean readFailed(Collection<String> reasons) {return reasons!=null && reasons.stream().anyMatch(r->r!=null && r.contains("READ_FAILED"));}

    private static boolean failedFunds(SupportFundsReadFacade.Snapshot s) {
        return s!=null && (s.wallets()!=null && (s.wallets().state()==SupportFundsReadFacade.ReadState.FAILED || s.wallets().reasons().contains(SupportFundsReadFacade.Reason.WALLET_READ_FAILED)
                || s.wallets().rows().stream().anyMatch(w->w.reasons().contains(SupportFundsReadFacade.Reason.WALLET_READ_FAILED)))
            || s.withdrawals()!=null && (s.withdrawals().state()==SupportFundsReadFacade.ReadState.FAILED || s.withdrawals().reasons().contains(SupportFundsReadFacade.Reason.WITHDRAWAL_READ_FAILED)
                || s.withdrawals().rows().stream().anyMatch(w->w.reasons().contains(SupportFundsReadFacade.Reason.WITHDRAWAL_READ_FAILED))));
    }
    /** Row-quality UNKNOWN is separate from missing/failed observation of the source. */
    static ReadState fundsObservation(SupportFundsReadFacade.Snapshot s,Set<Long> roots) {
        if(s==null)return ReadState.UNKNOWN;
        if(failedFunds(s))return ReadState.FAILED;
        if(!SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId().equals(s.businessZone()) || !roots.equals(new TreeSet<>(s.customerIds()))
            || s.customerIds().size()!=roots.size())throw invalid();
        var wallets=new TreeMap<Long,SupportFundsReadFacade.WalletEvidence>();var walletIds=new HashSet<Long>();var withdrawals=new TreeMap<Long,SupportFundsReadFacade.WithdrawalEvidence>();
        for(var w:s.wallets()==null?List.<SupportFundsReadFacade.WalletEvidence>of():s.wallets().rows()) {
            if(!roots.contains(w.customerId()) || w.state()==null)throw invalid();
            if(wallets.putIfAbsent(w.customerId(),w)!=null)throw invalid();
            if(w.walletId()!=null && !walletIds.add(w.walletId()))throw invalid();
        }
        for(var w:s.withdrawals()==null?List.<SupportFundsReadFacade.WithdrawalEvidence>of():s.withdrawals().rows()) {
            if(!roots.contains(w.customerId()) || w.withdrawalId()<=0 || w.state()==null)throw invalid();
            if(withdrawals.putIfAbsent(w.withdrawalId(),w)!=null)throw invalid();
        }
        if(s.wallets()==null || s.withdrawals()==null || s.wallets().state()==null || s.withdrawals().state()==null)return ReadState.UNKNOWN;
        if(!roots.equals(wallets.keySet()))return ReadState.UNKNOWN;
        if(s.withdrawals().historicalEnvironmentStatus()==null || s.withdrawals().eventOwnershipStatus()==null)return ReadState.UNKNOWN;
        if(s.wallets().reasons().contains(SupportFundsReadFacade.Reason.SOURCE_OBSERVATION_UNVERIFIED)
            || s.wallets().reasons().contains(SupportFundsReadFacade.Reason.WALLET_IDENTITY_UNVERIFIED))return ReadState.UNKNOWN;
        for(var w:wallets.values()) {
            if(w.reasons().contains(SupportFundsReadFacade.Reason.WALLET_MISSING) && w.walletId()==null)continue;
            if(w.walletId()==null || w.walletId()<=0 || w.version()==null || w.version()<0 || w.updatedAt()==null
                || s.wallets().evaluatedDbAt()==null || w.updatedAt().isAfter(s.wallets().evaluatedDbAt())
                || w.reasons().contains(SupportFundsReadFacade.Reason.SOURCE_OBSERVATION_UNVERIFIED) || w.reasons().contains(SupportFundsReadFacade.Reason.WALLET_IDENTITY_UNVERIFIED))return ReadState.UNKNOWN;
        }
        if(!s.withdrawals().rows().isEmpty() && (s.withdrawals().evaluatedDbAt()==null || s.withdrawals().rows().stream().anyMatch(w->w.updatedAt()==null || w.updatedAt().isAfter(s.withdrawals().evaluatedDbAt()))))return ReadState.UNKNOWN;
        return ReadState.COMPLETE;
    }
    private static void funds(Encoder e,SupportFundsReadFacade.Snapshot s) {
        e.s(s.businessZone());e.ids(s.customerIds());e.en(s.wallets().state());e.strings(s.wallets().reasons().stream().map(Enum::name).toList());
        e.rows("wallets",s.wallets().rows(),SupportFundsReadFacade.WalletEvidence::customerId,w->bytes(x->{x.n(w.customerId());x.n(w.walletId());x.n(w.version());
            x.decimal(w.usdtAvailable());x.decimal(w.nexAvailable());x.time(w.updatedAt());x.en(w.state());x.strings(w.reasons().stream().map(Enum::name).toList());}));
        var withdrawals=s.withdrawals();e.en(withdrawals.state());e.strings(withdrawals.reasons().stream().map(Enum::name).toList());e.en(withdrawals.historicalEnvironmentStatus());e.en(withdrawals.eventOwnershipStatus());
        e.rows("withdrawals",withdrawals.rows(),SupportFundsReadFacade.WithdrawalEvidence::withdrawalId,w->bytes(x->{x.n(w.withdrawalId());x.n(w.customerId());x.s(w.currency());x.decimal(w.principal());
            x.decimal(w.actualFee());x.decimal(w.net());x.s(w.status());x.s(w.canonicalStatus());x.en(w.state());x.time(w.completedAt());x.time(w.updatedAt());x.strings(w.reasons().stream().map(Enum::name).toList());}));
    }

    /** HTTP layer translates this fixed code into 409; authorization must run before invoking it. */
    public static void requireExpected(Normalized query, Version observed) {
        if (query.expectedVersion() == null) {
            if (query.pageNum() > 1) throw new IllegalArgumentException("SUPPORT_ANALYTICS_VERSION_REQUIRED");
            return;
        }
        if (!SupportAnalyticsQueryRequest.validVersion(query.expectedVersion())) throw new IllegalArgumentException("SUPPORT_ANALYTICS_VERSION_INVALID");
        if (observed.status() != Status.READY) throw new VersionConflict("SUPPORT_ANALYTICS_VERSION_UNVERIFIABLE");
        if (!query.expectedVersion().equals(observed.value())) throw new VersionConflict("SUPPORT_ANALYTICS_QUERY_CHANGED");
    }
    public static final class VersionConflict extends RuntimeException {
        private VersionConflict(String code) { super(code); }
    }
    private static boolean needsTasks(Normalized q) {
        return Set.of(ServiceFilter.DUE, ServiceFilter.WAITING_REPLY, ServiceFilter.FIRST_CONTACT, ServiceFilter.STOPPED, ServiceFilter.TODO).contains(q.filter())
                || Set.of(Sort.NEXT_DUE_AT, Sort.WAITING_SINCE_AT, Sort.STATE_CHANGED_AT).contains(q.sortKey());
    }
    private static boolean present(Evidence e, boolean tasks) {
        if (e.authority() == null || e.rules() == null || e.rules().version() == null || e.candidateCustomerIds() == null
                || e.customers() == null || e.eventCandidates() == null || e.finance() == null && !e.candidateCustomerIds().isEmpty() || e.attributions() == null
                || e.first() == null || e.invitations() == null || e.devices() == null || e.unknownHoldingDevices() == null
                || e.activityCoverage() == null || e.activities() == null || (tasks || e.reads().tasks() == ReadState.COMPLETE) && e.tasks() == null)
            return false;
        Authority a = e.authority();
        return a.scope() != null && a.authorizedGroupIds() != null && a.personnel() != null && a.roles() != null
                && !a.roles().isEmpty() && a.permissions() != null && !a.permissions().isEmpty() && a.qualifications() != null
                && a.groups() != null && a.relationships() != null && a.personnel().stream().anyMatch(p -> p.adminId() == a.scope().actorId()
                    && p.accountStatus() != null && p.accountVersion() != null)
                && e.activityCoverage().observedThroughAt() != null;
    }
    private static boolean covered(Evidence e) {
        Set<Long> ids = new TreeSet<>(e.candidateCustomerIds());
        Set<Long> currentIds = new TreeSet<>(e.customers().stream().map(Customer::customerId).toList());
        Set<Long> represented = new TreeSet<>(currentIds);
        represented.addAll(e.eventCandidates().stream().map(EventCandidate::customerId).toList());
        if(e.legacyCandidateCustomerIds()==null || e.descendantFinanceRead()==null)return false;
        if(!e.legacyCandidateCustomerIds().isEmpty() && (e.authority().scope().mode()!=ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.ALL
            || e.authority().scope().requestedGroupId()!=null || e.authority().scope().requestedAgentId()!=null))throw invalid();
        represented.addAll(e.legacyCandidateCustomerIds());
        if (!ids.equals(represented)) return false;
        if (e.authority().personnel().stream().anyMatch(p -> p.accountStatus() == null || p.accountVersion() == null)
                || e.authority().qualifications().stream().anyMatch(q -> q.version() == null || q.kind() == null || q.state() == null)
                || e.authority().groups().stream().anyMatch(g -> g.version() == null || g.status() == null)
                || e.authority().relationships().stream().anyMatch(r -> r.version() == null || r.kind() == null || r.state() == null)
                || e.authority().permissions().stream().anyMatch(p -> p.permissionCode() == null || p.roleStatus() == null || p.permissionStatus() == null)
                || e.authority().roles().stream().anyMatch(r -> r.roleCode() == null || r.status() == null)) return false;
        for (Customer c : e.customers()) {
            if (c.category() == null || c.placement() == null) return false;
            if ("BOUND".equals(c.category()) && c.agentId() == null) return false;
            if (c.agentId() != null && e.authority().relationships().stream().noneMatch(r -> r.kind() == RelationKind.ASSIGNMENT
                    && Objects.equals(r.customerId(),c.customerId()) && Objects.equals(r.agentId(),c.agentId()))) return false;
            if (c.groupId() != null && (e.authority().groups().stream().noneMatch(g -> g.id() == c.groupId())
                    || e.authority().relationships().stream().noneMatch(r -> r.kind() == RelationKind.GROUP_OWNER && Objects.equals(r.groupId(),c.groupId())))) return false;
        }
        if (!ids.equals(new TreeSet<>(e.first().stream().map(First::customerId).toList()))
                || !currentIds.equals(new TreeSet<>(e.invitations().stream().map(SupportInvitationReadFacade.Invitation::rootCustomerId).toList()))
                || !currentIds.equals(new TreeSet<>(e.activities().stream().map(Activity::customerId).toList()))
                || !ids.equals(new TreeSet<>((e.finance()==null?List.<SupportPaymentFacts.FirstHistory>of():e.finance().firstHistory()).stream().map(SupportPaymentFacts.FirstHistory::customerId).filter(ids::contains).toList()))) return false;
        Set<Long> boundIds=new TreeSet<>(e.customers().stream().filter(c->"BOUND".equals(c.category())).map(Customer::customerId).toList());
        if (e.reads().tasks() == ReadState.COMPLETE && (!boundIds.equals(new TreeSet<>(e.tasks().stream().map(Task::customerId).toList()))
                || e.tasks().stream().anyMatch(t->t.due()==null || t.waitingReply()==null || t.enabled()==null || t.firstContact()==null
                    || t.preferencePresent()==null || t.pendingReplyCount()==null || t.pendingReplies()==null))) return false;
        for (First first : e.first()) {
            if (first.firstState() == null || first.selectionStatus() == null || first.attributionProof() == null || first.reasons() == null) return false;
            if ("CONFIRMED".equals(first.firstState()) && (first.canonicalFactId() == null || e.finance().facts().stream()
                    .noneMatch(f -> f.factId().equals(first.canonicalFactId()) && f.customerId() == first.customerId()))) return false;
            if (!Set.of("CONFIRMED", "NONE", "UNKNOWN").contains(first.firstState())) throw invalid();
        }
        if (e.activities().stream().anyMatch(a -> a.windowState() == null || a.coverageState() == null
                || a.lastEffectiveAt() != null && a.lastEventId() == null && a.activitySeq() == null)) return false;
        if (e.attributions().stream().anyMatch(a -> a.validation() == null)) return false;
        if (e.finance()!=null && e.finance().firstHistory().stream().anyMatch(h -> h.status() == null)) return false;
        Set<String> proofs = new HashSet<>(e.attributions().stream().map(Attribution::factId).toList());
        if (e.eventCandidates().stream().anyMatch(c -> !proofs.contains(c.factId()))) return false;
        Set<Long> knownDevices = new HashSet<>();
        for (Device d : e.devices()) knownDevices.add(d.evidence().deviceId());
        if (e.unknownHoldingDevices().stream().anyMatch(d -> knownDevices.contains(d.evidence().deviceId()))) throw invalid();
        List<Device> allDevices = new ArrayList<>(e.devices()); allDevices.addAll(e.unknownHoldingDevices());
        for (Device item : allDevices) {
            if (!currentIds.contains(item.evidence().customerId())) throw invalid();
            if (item.acquisition() == Acquisition.PAID_PURCHASE && e.finance().facts().stream().noneMatch(f ->
                    f.kind() == SupportPaymentFacts.Kind.DEVICE_PURCHASE && f.customerId() == item.evidence().customerId()
                    && f.factId().equals(item.purchaseFactId()))) return false;
        }
        return true;
    }
    private static void encodeQuery(Encoder e, Normalized q) {
        e.s("query"); e.en(q.view()); e.en(q.category()); e.en(q.firstState()); e.en(q.filter()); e.s(q.keyword());
        e.en(q.basis()); e.instant(q.from()); e.instant(q.to()); e.s(SupportAnalyticsQueryRequest.BUSINESS_ZONE.getId());
        e.n(q.groupId()); e.n(q.agentId()); e.s(q.currency()); e.en(q.sortKey()); e.en(q.direction()); e.n(q.pageSize());
        e.s("NULL_LAST_BOTH_DIRECTIONS_ID_ASC");
    }
    private static void encodeAuthority(Encoder e, Authority a) {
        e.s("authority"); e.s("ADMIN"); e.n(a.scope().actorId()); e.en(a.scope().mode());
        e.n(a.scope().requestedGroupId()); e.n(a.scope().requestedAgentId()); e.ids(a.authorizedGroupIds());
        e.rows("personnel", a.personnel(), Personnel::adminId, p -> bytes(x -> { x.n(p.adminId()); x.n(p.accountStatus());
            x.n(p.accountVersion()); x.bool(p.profileEnabled()); x.bool(p.profileDeleted()); x.n(p.profileVersion());x.s(p.displayName());x.s(p.profileSeatType());x.s(p.avatarAssetId());x.n(p.avatarVersion()); }));
        e.rows("roles", a.roles(), Role::relationId, r -> bytes(x -> { x.n(r.relationId()); x.n(r.roleId()); x.n(r.adminId()); x.s(r.roleCode()); x.n(r.status()); }));
        e.rows("permissions", a.permissions(), p -> new PermissionKey(p.roleRelationId(), p.rolePermissionId(), p.permissionId()), p -> bytes(x -> {
            x.n(p.roleRelationId()); x.n(p.roleId()); x.n(p.rolePermissionId()); x.n(p.permissionId()); x.s(p.permissionCode());
            x.n(p.roleStatus()); x.n(p.permissionStatus()); }));
        e.rows("qualifications", a.qualifications(), Qualification::id, q -> bytes(x -> { x.n(q.id()); x.n(q.adminId()); x.s(q.kind()); x.s(q.state()); x.n(q.version()); x.time(q.startsAt()); x.time(q.endsAt()); }));
        e.rows("groups", a.groups(), Group::id, g -> bytes(x -> { x.n(g.id()); x.s(g.name()); x.n(g.supervisorAdminId()); x.s(g.status()); x.n(g.version()); }));
        e.rows("relationships", a.relationships(), r -> new RelationKey(r.kind(), r.id()), r -> bytes(x -> {
            x.en(r.kind()); x.n(r.id()); x.n(r.customerId()); x.n(r.agentId()); x.n(r.groupId()); x.n(r.ownerId());
            x.s(r.state()); x.bool(r.deleted()); x.n(r.version()); x.time(r.startsAt()); x.time(r.endsAt()); }));
    }
    private static byte[] customer(Customer c) { return bytes(e -> {
        e.n(c.customerId()); e.s(c.category()); e.s(c.placement()); e.bool(c.handoverRequired()); e.n(c.agentId()); e.n(c.groupId());
        e.s(c.keywordDisplayName()); e.s(c.state()); e.time(c.registeredAt()); e.time(c.assignedAt()); e.time(c.stateChangedAt());
        e.time(c.poolEnteredAt()); e.n(c.poolVersion()); e.s(c.avatarAssetId()); e.n(c.avatarVersion()); }); }
    private static void rules(Encoder e, SupportRules r) {
        e.s("rules"); e.n(r.version()); e.n(r.dormantDays()); e.n(r.maintenanceDays()); e.n(r.activityWindowDays());
        e.s(r.inheritanceMode()); e.n(r.maxInheritanceDepth()); e.s(r.unboundAssignmentMode()); e.time(r.modeEffectiveAt());
    }
    private static void finance(Encoder e, SupportPaymentFacts.Snapshot s) {
        e.s("finance"); e.s(s.businessZone()); // evaluatedAt deliberately excluded
        e.rows("facts", s.facts(), SupportPaymentFacts.Fact::factId, f -> {
            if (SupportPaymentFacts.validateCanonical(f, s.businessZone()) != null) throw invalid();
            return bytes(x -> { x.s(f.factId()); x.en(f.kind()); x.en(f.source()); x.strings(f.sourceIds()); x.n(f.customerId()); x.n(f.ledgerId());
                x.s(f.sourceBusinessId()); x.s(f.orderNo()); x.s(f.orderType()); x.s(f.originalFactId()); x.s(f.currency()); x.decimal(f.amount());
                x.time(f.succeededAt()); x.s(f.successTimeField()); x.n(f.fractionalSecondDigits()); x.time(f.providerPaidAt());
                x.time(f.ledgerRecordedAt()); x.time(f.sourceConfirmationAt()); x.s(f.sourceVersion()); x.en(f.historicalEnvironmentStatus()); });
        });
        e.rows("coverage", s.coverage(), c -> c.source().name(), c -> bytes(x -> {
            x.en(c.source()); x.en(c.observedStatus()); x.en(c.historyStatus()); x.en(c.refundStatus()); x.en(c.historicalEnvironmentStatus());
            x.time(c.supportedFrom()); x.strings(c.reasons()); x.n(c.excludedFreeOrNonProductionRows()); x.s(c.adapterVersion()); }));
        e.rowsByBytes("issues", s.issues(), i -> bytes(x -> { x.en(i.source()); x.s(i.sourceId()); x.s(i.reason()); x.n(i.customerId()); }));
        e.rows("firstHistory", s.firstHistory(), SupportPaymentFacts.FirstHistory::customerId,
                h -> bytes(x -> { x.n(h.customerId()); x.en(h.status()); x.strings(h.reasons()); }));
    }
    private static byte[] attribution(Attribution a) { return bytes(e -> {
        e.s(a.factId()); e.n(a.customerId()); e.s(a.kind()); e.s(a.source()); e.n(a.ledgerId()); e.s(a.sourceBusinessId());
        e.s(a.orderNo()); e.s(a.orderType()); e.s(a.originalFactId()); e.s(a.currency()); e.decimal(a.amount()); e.time(a.succeededAt());
        e.s(a.sourceBusinessZone()); e.s(a.successTimeField()); e.n(a.fractionalSecondDigits()); e.s(a.captureMode()); e.s(a.captureSchemaVersion());
        e.n(a.agentAdminId()); e.n(a.groupId()); e.n(a.ownerAdminId()); e.s(a.agentStatus()); e.s(a.groupStatus()); e.s(a.ownerStatus());
        e.s(a.sourcePartition()); e.en(a.validation()); }); }
    private static byte[] device(Device item) { return bytes(e -> {
        var d = Objects.requireNonNull(item.evidence()); e.n(d.deviceId()); e.n(d.customerId()); e.s(d.sourceOrderNo()); e.s(d.sourceChannel());
        e.s(d.deviceType()); e.decimal(d.hashrate()); e.s(d.ownershipStatus()); e.s(d.lifecycleStatus()); e.time(d.activatedAt());
        e.time(d.deactivatedAt()); e.n(d.pendingDeactivate()); e.s(d.sourceEnvironment()); e.s(d.runId());
        var r = d.runtime(); e.bool(r != null);
        if (r != null) { e.n(r.runtimeId()); e.s(r.reportedStatus()); e.time(r.heartbeatAt()); e.s(r.pausedReason()); e.s(r.activeTaskNo()); e.n(r.networkReachable()); }
        if (d.connectionStatus() == null || item.acquisition() == null) throw invalid();
        e.en(d.connectionStatus()); e.n(item.rowVersion()); e.en(item.acquisition()); e.s(item.purchaseFactId()); e.strings(item.acquisitionReasons());
        // Reader evaluatedDbAt and runtime.updatedAt are not result evidence in this contract.
    }); }
    private static byte[] task(Task t) { return bytes(e -> {
        e.n(t.customerId()); e.bool(t.enabled()); e.n(t.preferenceVersion()); e.n(t.cycleId()); e.s(t.cycleStatus());
        e.n(t.executionId()); e.s(t.executionStatus()); e.time(t.lastExecutionAt()); e.time(t.lastSucceededAt()); e.time(t.nextDueAt());
        e.bool(t.firstContact()); e.n(t.contactFactId()); e.s(t.pendingConversationNo()); e.n(t.pendingThroughMessageId());
        e.n(t.replyCursor()); e.time(t.waitingSinceAt()); e.s(t.stoppedReason());
        e.bool(t.due());e.bool(t.waitingReply());e.bool(t.preferencePresent());e.n(t.pendingReplyCount());e.s(t.activityStatus());e.s(t.windowStatus());e.time(t.preferenceUpdatedAt());
        e.rows("pendingReplies",t.pendingReplies(),PendingReply::messageId,p->bytes(x->{x.n(p.messageId());x.s(p.conversationNo());x.n(p.throughMessageId());x.time(p.createdAt());})); }); }

    private static byte[] bytes(Consumer<Encoder> body) { Encoder e = new Encoder(); body.accept(e); return e.bytes(); }
    private record PermissionKey(long relationId, long grantId, long permissionId) implements Comparable<PermissionKey> {
        public int compareTo(PermissionKey other) {
            int c = Long.compare(relationId, other.relationId); if (c != 0) return c;
            c = Long.compare(grantId, other.grantId); return c != 0 ? c : Long.compare(permissionId, other.permissionId);
        }
    }
    private record RelationKey(RelationKind kind, long id) implements Comparable<RelationKey> {
        public int compareTo(RelationKey other) { int c = kind.compareTo(other.kind); return c != 0 ? c : Long.compare(id, other.id); }
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("SUPPORT_ANALYTICS_VERSION_EVIDENCE_INVALID"); }
    private static final class Encoder {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream data = new DataOutputStream(buffer);
        byte[] bytes() { return buffer.toByteArray(); }
        void raw(byte[] value) { try { data.writeInt(value.length); data.write(value); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
        void s(String value) {
            if (value != null) for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
            try { data.writeBoolean(value != null); if (value != null) raw(value.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
        }
        void n(Number value) { try { data.writeBoolean(value != null); if (value != null) data.writeLong(value.longValue()); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
        void bool(Boolean value) { try { data.writeByte(value == null ? 0 : value ? 2 : 1); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
        void en(Enum<?> value) { s(value == null ? null : value.name()); }
        void decimal(BigDecimal value) { s(value == null ? null : value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString()); }
        void time(LocalDateTime value) {
            if (value != null && value.getNano() % 1000 != 0) throw invalid();
            s(value == null ? null : String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d.%06d", value.getYear(), value.getMonthValue(), value.getDayOfMonth(), value.getHour(), value.getMinute(), value.getSecond(), value.getNano() / 1000));
        }
        void instant(Instant value) { bool(value != null); if (value != null) { n(value.getEpochSecond()); n(value.getNano()); } }
        void ids(Collection<Long> values) { TreeSet<Long> sorted = new TreeSet<>(Objects.requireNonNull(values)); n(sorted.size()); for (Long id : sorted) { if (id == null || id <= 0) throw invalid(); n(id); } }
        void strings(Collection<String> values) { TreeSet<String> sorted = new TreeSet<>(Objects.requireNonNull(values)); n(sorted.size()); sorted.forEach(this::s); }
        <T, K extends Comparable<? super K>> void rows(String tag, Collection<T> rows, Function<T, K> identity, Function<T, byte[]> encode) {
            s(tag); TreeMap<K, byte[]> sorted = new TreeMap<>();
            for (T row : Objects.requireNonNull(rows)) {
                K key = Objects.requireNonNull(identity.apply(Objects.requireNonNull(row))); byte[] value = encode.apply(row);
                byte[] old = sorted.putIfAbsent(key, value); if (old != null && !Arrays.equals(old, value)) throw invalid();
            }
            n(sorted.size()); sorted.values().forEach(this::raw);
        }
        <T> void rowsByBytes(String tag, Collection<T> rows, Function<T, byte[]> encode) {
            s(tag); TreeSet<byte[]> sorted = new TreeSet<>(Arrays::compareUnsigned);
            for (T row : rows) sorted.add(encode.apply(row)); n(sorted.size()); sorted.forEach(this::raw);
        }
    }
}
