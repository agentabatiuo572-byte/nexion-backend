package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats;
import ffdd.opsconsole.content.domain.SupportAnalyticsStats.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest;
import ffdd.opsconsole.content.dto.SupportAnalyticsQueryRequest.*;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper;
import ffdd.opsconsole.content.mapper.SupportAnalyticsMapper.*;
import ffdd.opsconsole.device.facade.SupportDeviceReadFacade.DeviceEvidence;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts;
import ffdd.opsconsole.finance.facade.SupportFundsReadFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Private authorized query only. Evidence and canonical identities never cross this HTTP projection. */
@ApplicationService
public class SupportAnalyticsPrivateQueryService {
    private final SupportOwnershipService ownership;
    private final SupportAnalyticsService stats;
    private final SupportAnalyticsMapper mapper;
    private final SupportFundsReadFacade funds;
    // Existing AppWalletBillsService.allowed and EarningsReleaseService.ASSETS, not a new currency catalog.
    static final Set<String> CURRENCIES=Set.of("USDT","NEX");
    static final Set<Sort> SORTS=Collections.unmodifiableSet(EnumSet.allOf(Sort.class));
    public SupportAnalyticsPrivateQueryService(SupportOwnershipService ownership,SupportAnalyticsService stats,SupportAnalyticsMapper mapper) {
        this.ownership=Objects.requireNonNull(ownership);this.stats=Objects.requireNonNull(stats);this.mapper=Objects.requireNonNull(mapper);
        this.funds=null;
    }
    @Autowired
    public SupportAnalyticsPrivateQueryService(SupportOwnershipService ownership,SupportAnalyticsService stats,SupportAnalyticsMapper mapper,SupportFundsReadFacade funds) {
        this.ownership=Objects.requireNonNull(ownership);this.stats=Objects.requireNonNull(stats);this.mapper=Objects.requireNonNull(mapper);this.funds=Objects.requireNonNull(funds);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> query(Map<String,List<String>> raw) {
        try {return execute(raw);}
        catch(DataAccessException ex){throw unavailable();}
    }
    private Map<String,Object> execute(Map<String,List<String>> raw) {
        var q=SupportAnalyticsQueryRequest.fromParameters(raw).normalize(CURRENCIES,SORTS);
        validateView(q);
        ReadScope scope=ownership.defaultQueryScope(q.groupId(),q.agentId());
        var grants=grants(scope.actorId());
        var groups=checked(mapper.currentGrantedGroupIds(scope));
        boolean directory=grants.stream().anyMatch(g->"platform_a1_read".equals(g.permissionCode()));
        final SupportAnalyticsService.QueryEvaluation evaluation;
        final Sources sources;
        final SupportAnalyticsQueryVersion.Version version;
        try {
            evaluation=stats.evaluateForQuery(q.toStatsQuery(scope),scope,directory);
            var c=evaluation.evidence();if(c.currentFailed || c.eventFailed)throw unavailable();
            sources=readSources(scope,c,grants,groups);
            version=SupportAnalyticsQueryVersion.evaluate(q,evidence(q,evaluation,sources));
        } catch(DataAccessException | IllegalArgumentException | IllegalStateException | NullPointerException ex) {
            reauthorize(q,scope,grants,groups);throw unavailable();
        } catch(BizException ex) {
            reauthorize(q,scope,grants,groups);if(ex.getCode()==503)throw unavailable();throw ex;
        }
        // Reauthorization always precedes version comparison, including unverifiable/failed sources.
        reauthorize(q,scope,grants,groups);
        if(version.status()==SupportAnalyticsQueryVersion.Status.FAILED)throw unavailable();
        try {SupportAnalyticsQueryVersion.requireExpected(q,version);}
        catch(SupportAnalyticsQueryVersion.VersionConflict ex){throw new BizException(409,ex.getMessage());}
        if(q.filter()!=ServiceFilter.ALL && q.filter()!=ServiceFilter.WINDOW_ACTIVE && sources.taskRead()!=SupportAnalyticsQueryVersion.ReadState.COMPLETE)throw unavailable();
        var selected=evaluation.result().currentCustomers().stream().filter(row->matches(q,row,sources)).toList();
        var rows=rows(q,evaluation,sources,selected);
        rows.sort(rowOrder(q.direction()));
        long offset=(q.pageNum()-1)*q.pageSize();
        int start=(int)Math.min(offset,rows.size()),end=(int)Math.min(offset+q.pageSize(),rows.size());
        var payload=map("view",q.view().name(),"basis",q.basis().name(),"businessZone","Asia/Shanghai",
            "pageNum",q.pageNum(),"pageSize",q.pageSize(),"total",version.status()==SupportAnalyticsQueryVersion.Status.READY?(long)rows.size():null,"observedTotal",(long)rows.size(),"records",rows.subList(start,end).stream().map(Row::body).toList(),
            "versionState",version.status().name(),"queryVersion",version.value(),"canContinue",version.status()==SupportAnalyticsQueryVersion.Status.READY && end<rows.size(),
            "recordsStatus",q.view()==View.AGENTS?evaluation.result().personnel().status().name():version.status().name(),
            "selectedCustomerCount",(long)selected.size(),"selectedCurrent",current(stats.selectedCurrent(evaluation,selected)),
            "scopeSummary",scopeSummary(evaluation.result(),sources),"funds",selectedFunds(sources,selected));
        // asOf is display-only; no request clock enters the version.
        payload.put("asOf",evaluation.result().asOf().toString());
        if(q.view()==View.AGENTS && evaluation.result().personnel().status()!=SupportAnalyticsStats.Status.AVAILABLE)payload.put("total",null);
        return payload;
    }
    private void reauthorize(Normalized q,ReadScope scope,List<PermissionStamp> grants,List<Long> groups) {
        ReadScope finalScope=ownership.defaultQueryScope(q.groupId(),q.agentId());
        var finalGrants=grants(finalScope.actorId());
        var finalGroups=checked(mapper.currentGrantedGroupIds(finalScope));
        if(!scope.equals(finalScope) || !grantKeys(grants).equals(grantKeys(finalGrants)) || !new TreeSet<>(groups).equals(new TreeSet<>(finalGroups)))
            throw new BizException(409,"SUPPORT_ANALYTICS_QUERY_CHANGED");
    }

    private List<PermissionStamp> grants(Long actor) {
        var rows=checked(mapper.currentReadGrants(actor)).stream()
            .filter(g->SupportOwnershipService.hasAuthority(g.permissionCode())).toList();
        if(rows.stream().noneMatch(g->Set.of("service_m1_read","service_m3_read").contains(g.permissionCode())))
            throw new BizException(403,"SUPPORT_SCOPE_FORBIDDEN");
        return rows;
    }
    private static Set<PermissionStamp> grantKeys(List<PermissionStamp> rows) {return new HashSet<>(rows);}
    private static <T> List<T> checked(List<T> rows) {if(rows==null || rows.stream().anyMatch(Objects::isNull))throw unavailable();return List.copyOf(rows);}
    private static BizException unavailable(){return new BizException(503,"SUPPORT_ANALYTICS_SOURCE_UNAVAILABLE");}
    private static boolean bool(Integer v){return Integer.valueOf(1).equals(v);}
    private static Boolean nullableBool(Integer v){if(v!=null && v!=0 && v!=1)throw unavailable();return v==null?null:v==1;}
    private static void validateView(Normalized q) {
        Set<Sort> allowed=switch(q.view()) {
            case OVERVIEW,CUSTOMERS,ACTIVITY -> EnumSet.complementOf(EnumSet.of(Sort.BOUND_CUSTOMER_COUNT,Sort.ACTIVE_CUSTOMER_COUNT,Sort.FIRST_CONFIRMED_CUSTOMER_COUNT,Sort.HASHRATE,Sort.SUCCEEDED_AT,Sort.AMOUNT));
            case AGENTS -> EnumSet.of(Sort.BOUND_CUSTOMER_COUNT,Sort.ACTIVE_CUSTOMER_COUNT,Sort.FIRST_CONFIRMED_CUSTOMER_COUNT,Sort.DEVICE_COUNT,Sort.PERSONAL_DEPOSIT);
            case FINANCE -> EnumSet.of(Sort.SUCCEEDED_AT,Sort.AMOUNT);
            case DEVICES -> EnumSet.of(Sort.HASHRATE,Sort.LAST_ACTIVE_AT,Sort.REGISTERED_AT,Sort.DEVICE_COUNT);
        };
        if(!allowed.contains(q.sortKey()) || q.basis()==Basis.CURRENT_ASSET && (q.view()==View.FINANCE || q.firstState()!=First.ALL
                || Set.of(Sort.PERSONAL_DEPOSIT,Sort.TEAM_DEPOSIT,Sort.FIRST_SUCCEEDED_AT).contains(q.sortKey())))
            throw new BizException(422,"SUPPORT_ANALYTICS_SORT_INVALID");
    }

    private record Sources(List<Long> groups,List<PermissionStamp> grants,List<PersonStamp> people,List<RoleStamp> roles,
        List<QualificationStamp> qualifications,List<GroupStamp> groupRows,List<RelationshipStamp> relations,
        Map<Long,RootDisplayRow> display,ffdd.opsconsole.content.domain.SupportRules rules,
        Map<Long,ActivityIdentityRow> activity,Map<Long,TaskRow> tasks,List<PendingReplyRow> pending,
        SupportAnalyticsQueryVersion.ReadState activityRead,SupportAnalyticsQueryVersion.ReadState taskRead,
        List<CustomerTagRow> tags,SupportFundsReadFacade.Snapshot funds,SupportAnalyticsQueryVersion.ReadState fundsRead) { }
    private Sources readSources(ReadScope scope,SupportAnalyticsService.QueryCapture c,List<PermissionStamp> grants,List<Long> groups) {
        var roots=new TreeSet<>(c.current.keySet());var groupIds=new TreeSet<>(groups);var peopleIds=new TreeSet<Long>();peopleIds.add(scope.actorId());
        for(var root:c.current.values()){if(root.ownerAgentId()!=null)peopleIds.add(root.ownerAgentId());if(root.currentGroupId()!=null)groupIds.add(root.currentGroupId());}
        for(var a:c.accounts)peopleIds.add(a.accountId());
        var groupRows=checked(mapper.groupStamps(scope,groupIds));
        for(var g:groupRows)if(g.supervisorAdminId()!=null)peopleIds.add(g.supervisorAdminId());
        var people=checked(mapper.personnelStamps(scope,peopleIds));
        // Internal authorized people set is mandatory. Missing account rows cannot become stable zero.
        if(!peopleIds.equals(new TreeSet<>(people.stream().map(PersonStamp::id).toList())))throw unavailable();
        var roles=checked(mapper.roleStamps(scope,peopleIds));var quals=checked(mapper.qualificationStamps(scope,peopleIds));
        var relations=new ArrayList<RelationshipStamp>();relations.addAll(checked(mapper.assignmentStamps(scope,roots)));
        relations.addAll(checked(mapper.routeStamps(scope,roots)));relations.addAll(checked(mapper.memberStamps(scope,peopleIds)));
        relations.addAll(checked(mapper.ownerStamps(scope,groupIds)));
        var display=index(checked(mapper.rootDisplayRows(scope,roots)),RootDisplayRow::customerId);
        if(!roots.equals(display.keySet()))throw unavailable();
        var tags=checked(mapper.customerTagRows(scope,roots));var tagIds=new HashSet<Long>();
        if(tags.stream().anyMatch(t->t.id()==null || t.id()<=0 || !roots.contains(t.customerId()) || !tagIds.add(t.id()) || t.tag()==null || t.createdAt()==null || t.updatedAt()==null))throw unavailable();
        var fundSnapshot=funds==null?null:funds.readCurrent(List.copyOf(roots));
        var fundsRead=funds==null?SupportAnalyticsQueryVersion.ReadState.NOT_REQUESTED:SupportAnalyticsQueryVersion.fundsObservation(fundSnapshot,roots);
        var rules=mapper.queryRules(scope);if(rules==null)throw unavailable();
        Map<Long,ActivityIdentityRow> activity=Map.of();Map<Long,TaskRow> tasks=Map.of();List<PendingReplyRow> pending=List.of();
        var activityRead=c.activityWindow!=null && c.activityWindow.status()==SupportAnalyticsStats.Status.FAILED?SupportAnalyticsQueryVersion.ReadState.FAILED:SupportAnalyticsQueryVersion.ReadState.UNKNOWN;var taskRead=SupportAnalyticsQueryVersion.ReadState.UNKNOWN;
        if(c.activityCoverage!=null && c.activityCoverage.observedThroughAt()!=null && c.activityWindow!=null && c.activityWindow.fromInclusive()!=null) {
            try {
                var w=c.activityCoverage.observedThroughAt();activity=index(checked(mapper.activityIdentityRows(scope,roots,w)),ActivityIdentityRow::customerId);
                activityRead=SupportAnalyticsQueryVersion.ReadState.COMPLETE;
                var bound=new TreeSet<>(c.current.values().stream().filter(row->"BOUND".equals(row.category())).map(CurrentCustomer::customerId).toList());
                tasks=index(checked(mapper.taskRows(scope,bound,w,c.activityCoverage.coverageStartAt(),
                    rules.dormantDays()==null?null:w.minusDays(rules.dormantDays()),c.activityWindow.fromInclusive(),rules.maintenanceDays(),null,c.activityCoverage.evaluatedDbAt())),TaskRow::customerId);
                pending=checked(mapper.pendingReplyRows(scope,bound));
                taskRead=bound.equals(tasks.keySet())?SupportAnalyticsQueryVersion.ReadState.COMPLETE:SupportAnalyticsQueryVersion.ReadState.UNKNOWN;
            } catch(DataAccessException ex){activityRead=SupportAnalyticsQueryVersion.ReadState.FAILED;taskRead=SupportAnalyticsQueryVersion.ReadState.FAILED;}
        }
        return new Sources(groups,grants,people,roles,quals,groupRows,relations,display,rules,activity,tasks,pending,activityRead,taskRead,tags,fundSnapshot,fundsRead);
    }
    private static <T> Map<Long,T> index(List<T> rows,java.util.function.Function<T,Long> id) {
        var out=new TreeMap<Long,T>();for(var row:rows){Long key=id.apply(row);if(key==null || key<=0 || out.putIfAbsent(key,row)!=null)throw unavailable();}return out;
    }

    private SupportAnalyticsQueryVersion.Evidence evidence(Normalized q,SupportAnalyticsService.QueryEvaluation e,Sources s) {
        var c=e.evidence();var v=SupportAnalyticsQueryVersion.ReadState.COMPLETE;
        var authority=new SupportAnalyticsQueryVersion.Authority(c.scope,s.groups(),
            s.people().stream().map(p->new SupportAnalyticsQueryVersion.Personnel(p.id(),p.status(),p.version(),nullableBool(p.profileEnabled()),nullableBool(p.profileDeleted()),p.profileVersion(),p.nickname()==null?p.username():p.nickname(),p.profileSeatType(),p.avatarAssetId(),p.avatarVersion())).toList(),
            s.roles().stream().map(r->new SupportAnalyticsQueryVersion.Role(r.relationId(),r.roleId(),r.adminId(),r.roleCode(),r.status())).toList(),
            s.grants().stream().map(p->new SupportAnalyticsQueryVersion.Permission(p.roleRelationId(),p.roleId(),p.rolePermissionId(),p.permissionId(),p.permissionCode(),p.roleStatus(),p.permissionStatus())).toList(),
            s.qualifications().stream().map(r->new SupportAnalyticsQueryVersion.Qualification(r.id(),r.adminId(),r.kind(),r.state(),r.version(),r.startsAt(),r.endsAt())).toList(),
            s.groupRows().stream().map(g->new SupportAnalyticsQueryVersion.Group(g.id(),g.name(),g.supervisorAdminId(),g.status(),g.version())).toList(),
            s.relations().stream().map(r->new SupportAnalyticsQueryVersion.Relationship(SupportAnalyticsQueryVersion.RelationKind.valueOf(r.kind()),r.id(),r.customerId(),r.agentId(),r.groupId(),r.ownerId(),r.state(),nullableBool(r.deleted()),r.version(),r.startsAt(),r.endsAt())).toList());
        var customers=e.result().currentCustomers().stream().map(row->{var d=s.display().get(row.customerId());var t=s.tasks().get(row.customerId());return new SupportAnalyticsQueryVersion.Customer(row.customerId(),row.category().name(),row.placement().name(),row.handoverRequired(),row.owner().agentId(),row.owner().groupId(),d.nickname(),d.status(),d.registeredAt(),d.assignedAt(),t==null?null:t.preferenceUpdatedAt(),d.poolEnteredAt(),d.poolVersion(),null,null);}).toList();
        var first=new ArrayList<SupportAnalyticsQueryVersion.First>();
        for(long id:new TreeSet<>(c.financialIds)){var f=c.first.get(id);var h=c.firstHistory.get(id);boolean ready=h!=null && h.status()==SupportPaymentFacts.Status.READY;
            first.add(new SupportAnalyticsQueryVersion.First(id,f==null?null:f.factId(),ready?f==null?"NONE":"CONFIRMED":"UNKNOWN",ready?"AVAILABLE":"UNKNOWN",f==null?SupportAnalyticsQueryVersion.Proof.MISSING:c.validAttributions.containsKey(f.factId())?SupportAnalyticsQueryVersion.Proof.ACCEPTED:SupportAnalyticsQueryVersion.Proof.REJECTED,h==null?List.of("HISTORY_UNVERIFIED"):h.reasons()));}
        var attributions=c.rawAttributions.stream().map(a->new SupportAnalyticsQueryVersion.Attribution(a.factId(),a.customerId(),a.kind(),a.source(),a.ledgerId(),a.sourceBusinessId(),a.orderNo(),a.orderType(),a.originalFactId(),a.currency(),a.amount(),a.succeededAt(),a.sourceBusinessZone(),a.successTimeField(),a.fractionalSecondDigits(),a.captureMode(),a.captureSchemaVersion(),a.agentAdminId(),a.groupId(),a.ownerAdminId(),a.agentStatus(),a.groupStatus(),a.ownerStatus(),null,c.validAttributions.containsKey(a.factId())?SupportAnalyticsQueryVersion.Proof.ACCEPTED:SupportAnalyticsQueryVersion.Proof.REJECTED)).toList();
        var activities=new ArrayList<SupportAnalyticsQueryVersion.Activity>();
        for(var row:e.result().currentCustomers()){var a=c.activities.get(row.customerId());var identity=s.activity().get(row.customerId());
            if(a!=null && !Objects.equals(a.lastEffectiveAt(),identity==null?null:identity.occurredAt()))throw unavailable();
            activities.add(new SupportAnalyticsQueryVersion.Activity(row.customerId(),identity==null?null:identity.eventId(),identity==null?null:identity.seq(),identity==null?null:identity.sourceRef(),a==null?null:a.lastEffectiveAt(),a==null?"UNKNOWN":a.state().name(),a==null?"UNKNOWN":a.status().name()));}
        var tasks=s.tasks().values().stream().map(t->new SupportAnalyticsQueryVersion.Task(t.customerId(),nullableBool(t.enabled()),t.preferenceVersion(),t.openCycleId(),t.openCycleId()==null?null:"OPEN",t.executionId(),null,t.lastExecutionAt(),t.lastSucceededAt(),t.nextDueAt(),nullableBool(t.firstContact()),t.contactFactId(),t.pendingConversationNo(),t.pendingThroughMessageId(),null,t.waitingSinceAt(),t.stoppedReason(),nullableBool(t.due()),nullableBool(t.waitingReply()),nullableBool(t.preferencePresent()),t.pendingReplyCount(),t.activityStatus(),t.windowStatus(),s.pending().stream().filter(p->p.customerId().equals(t.customerId())).map(p->new SupportAnalyticsQueryVersion.PendingReply(p.messageId(),p.conversationNo(),p.throughMessageId(),p.createdAt())).toList(),t.preferenceUpdatedAt())).toList();
        var window=c.activityWindow;var coverage=c.activityCoverage;
        var ac=coverage==null?null:new SupportAnalyticsQueryVersion.ActivityCoverage(coverage.coverageStartAt(),coverage.observedThroughAt(),window.fromInclusive(),window.throughInclusive());
        return new SupportAnalyticsQueryVersion.Evidence(new SupportAnalyticsQueryVersion.Reads(c.rosterStatus==SupportAnalyticsStats.Status.FAILED?SupportAnalyticsQueryVersion.ReadState.FAILED:v,v,c.snapshot==null?SupportAnalyticsQueryVersion.ReadState.NOT_REQUESTED:v,c.attributionFailed?SupportAnalyticsQueryVersion.ReadState.FAILED:v,v,c.deviceFailed?SupportAnalyticsQueryVersion.ReadState.FAILED:v,s.activityRead(),s.taskRead()),authority,List.copyOf(c.financialIds),customers,
            c.eventIds.entrySet().stream().map(r->new SupportAnalyticsQueryVersion.EventCandidate(r.getKey(),r.getValue())).toList(),s.rules(),c.snapshot,attributions,first,List.copyOf(c.trees.values()),
            c.stock==null?List.of():c.stock.devices().stream().map(d->deviceProof(d,c)).toList(),c.stock==null?List.of():c.stock.unknownHoldingDevices().stream().map(d->deviceProof(d,c)).toList(),ac,activities,tasks,List.copyOf(c.legacyIds),c.teamSnapshot,c.teamSnapshot==null?SupportAnalyticsQueryVersion.ReadState.NOT_REQUESTED:v,
            s.display().values().stream().map(d->new SupportAnalyticsQueryVersion.Profile(d.customerId(),d.sourceUpdatedAt(),d.avatarObjectKey(),d.vRank(),d.userLevel(),
                s.tags().stream().filter(t->t.customerId().equals(d.customerId())).map(t->new SupportAnalyticsQueryVersion.Tag(t.id(),t.tag(),t.createdAt(),t.updatedAt())).toList())).toList(),v,s.funds(),s.fundsRead());
    }
    private static SupportAnalyticsQueryVersion.Device deviceProof(DeviceEvidence d,SupportAnalyticsService.QueryCapture c) {
        var purchase=SupportAnalyticsService.productionDevice(d)==1
            ?c.facts.values().stream().filter(f->SupportAnalyticsService.paidDeviceFact(d,f,c.snapshot)).findFirst().orElse(null):null;
        return new SupportAnalyticsQueryVersion.Device(d,null,purchase==null?SupportAnalyticsQueryVersion.Acquisition.UNKNOWN:SupportAnalyticsQueryVersion.Acquisition.PAID_PURCHASE,purchase==null?null:purchase.factId(),purchase==null?List.of("ACQUISITION_NOT_PROVEN"):List.of());
    }

    private static boolean matches(Normalized q,SupportAnalyticsStats.Customer c,Sources s) {
        if(q.category()!=SupportAnalyticsQueryRequest.Category.ALL && !q.category().name().equals(c.category().name()))return false;
        if(q.firstState()!=First.ALL && !q.firstState().name().equals(c.first().state().name()))return false;
        var d=s.display().get(c.customerId());
        if(q.keyword()!=null && !(d.nickname()!=null && d.nickname().toLowerCase(Locale.ROOT).contains(q.keyword().toLowerCase(Locale.ROOT))) && !d.customerNo().equals(q.keyword()))return false;
        var t=s.tasks().get(c.customerId());
        return switch(q.filter()) {
            case ALL -> true;
            case WINDOW_ACTIVE -> c.metrics().activity().state()==WindowState.ACTIVE;
            case ACTIVE,DORMANT,UNKNOWN -> t!=null && q.filter().name().equals(t.activityStatus());
            case DUE -> t!=null && bool(t.due());
            case WAITING_REPLY -> t!=null && bool(t.waitingReply());
            case FIRST_CONTACT -> t!=null && bool(t.firstContact()) && bool(t.enabled());
            case STOPPED -> t!=null && Integer.valueOf(0).equals(t.enabled());
            case TODO -> t!=null && (bool(t.due()) || bool(t.waitingReply()) || bool(t.firstContact()) && bool(t.enabled()));
        };
    }

    record Row(long id,String secondary,Object sort,Map<String,Object> body) { }
    static Comparator<Row> rowOrder(Direction direction) {
        return (a,b)->{int n=compare(a.sort(),b.sort(),direction);if(n!=0)return n;n=Long.compare(a.id(),b.id());return n!=0?n:a.secondary().compareTo(b.secondary());};
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static int compare(Object a,Object b,Direction d) {if(a==null)return b==null?0:1;if(b==null)return -1;int n=((Comparable)a).compareTo(b);return d==Direction.ASC?n:-Integer.signum(n);}
    private List<Row> rows(Normalized q,SupportAnalyticsService.QueryEvaluation e,Sources s,List<SupportAnalyticsStats.Customer> selected) {
        List<Row> out=new ArrayList<>();Map<Long,SupportAnalyticsStats.Customer> roots=new HashMap<>();for(var c:selected)roots.put(c.customerId(),c);
        switch(q.view()) {
            case OVERVIEW,CUSTOMERS,ACTIVITY -> {for(var c:selected)out.add(new Row(c.customerId(),"",customerSort(q,c,s),customer(c,s)));}
            case FINANCE -> {for(var f:e.evidence().selected)if(roots.containsKey(f.customerId())){var c=roots.get(f.customerId());Object sort=q.sortKey()==Sort.AMOUNT?f.amount():f.succeededAt();
                out.add(new Row(f.customerId(),f.factId(),sort,map("customerId",Long.toString(f.customerId()),"customerNo",s.display().get(f.customerId()).customerNo(),"nickname",s.display().get(f.customerId()).nickname(),"kind",f.kind().name(),"currency",f.currency(),"amount",decimal(f.amount()),"succeededAt",time(f.succeededAt()),"orderNo",f.orderNo(),"historyStatus",e.evidence().firstHistory.get(f.customerId())==null?"UNKNOWN":e.evidence().firstHistory.get(f.customerId()).status().name())));}}
            case DEVICES -> {if(e.evidence().stock!=null){for(var d:e.evidence().stock.devices())if(roots.containsKey(d.customerId()) && SupportAnalyticsService.productionDevice(d)>=0)out.add(deviceRow(q,d,roots.get(d.customerId()),s,e.evidence(),SupportAnalyticsService.productionDevice(d)==0));for(var d:e.evidence().stock.unknownHoldingDevices())if(roots.containsKey(d.customerId()) && SupportAnalyticsService.productionDevice(d)>=0)out.add(deviceRow(q,d,roots.get(d.customerId()),s,e.evidence(),true));}}
            case AGENTS -> {for(var a:e.evidence().accounts){var members=selected.stream().filter(c->Objects.equals(c.owner().agentId(),a.accountId())).toList();var metric=stats.selectedCurrent(e,members);var person=s.people().stream().filter(p->p.id()==a.accountId()).findFirst().orElseThrow(SupportAnalyticsPrivateQueryService::unavailable);
                Object sort=switch(q.sortKey()){case BOUND_CUSTOMER_COUNT -> (long)members.size();case ACTIVE_CUSTOMER_COUNT -> confirmed(metric.activity().active());case FIRST_CONFIRMED_CUSTOMER_COUNT -> confirmed(metric.firstConfirmed());case DEVICE_COUNT -> confirmed(metric.devices().held());case PERSONAL_DEPOSIT -> deposit(metric.ownLifetime(),q.currency());default -> null;};
                out.add(new Row(a.accountId(),"",sort,map("accountId",Long.toString(a.accountId()),"displayName",person.nickname()==null?person.username():person.nickname(),"avatar",personAvatar(person),"avatarRef",a.serviceAccount() && s.grants().stream().anyMatch(g->"service_m1_read".equals(g.permissionCode()))?personAvatarRef(person,null):null,"account",account(a),"current",current(metric),"funds",funds(s,members.stream().map(SupportAnalyticsStats.Customer::customerId).collect(java.util.stream.Collectors.toSet())))));}}
        }
        return out;
    }
    private static Row deviceRow(Normalized q,DeviceEvidence d,SupportAnalyticsStats.Customer c,Sources s,SupportAnalyticsService.QueryCapture capture,boolean unknown) {
        Object sort=q.sortKey()==Sort.HASHRATE?d.hashrate():customerSort(q,c,s);var proof=deviceProof(d,capture);
        return new Row(d.deviceId(),"",sort,map("deviceId",Long.toString(d.deviceId()),"customerId",Long.toString(d.customerId()),"deviceType",d.deviceType(),"hashrate",decimal(d.hashrate()),"holdingStatus",unknown?"UNKNOWN":"AVAILABLE","connectionStatus",d.connectionStatus().name(),"acquisition",proof.acquisition().name(),"activatedAt",time(d.activatedAt()),"deactivatedAt",time(d.deactivatedAt())));
    }
    private static Object customerSort(Normalized q,SupportAnalyticsStats.Customer c,Sources s) {
        var d=s.display().get(c.customerId());var t=s.tasks().get(c.customerId());var m=c.metrics();
        return switch(q.sortKey()) {
            case REGISTERED_AT -> d.registeredAt();case ASSIGNED_AT -> d.assignedAt();case LAST_ACTIVE_AT -> m.activity().lastEffectiveAt();
            case FIRST_SUCCEEDED_AT -> c.first().state()==FirstState.CONFIRMED && c.first().observedCandidate()!=null?c.first().observedCandidate().succeededAt():null;
            case DIRECT_INVITATION_COUNT -> confirmed(m.invitations().directCustomers());case TEAM_CUSTOMER_COUNT -> confirmed(m.invitations().descendantCustomers());
            case PERSONAL_DEPOSIT -> deposit(m.lifetime(),q.currency());
            case TEAM_DEPOSIT -> m.invitations().descendantDeposits().stream().filter(v->v.currency().equals(q.currency())).map(v->v.deposits().confirmedAmount()).filter(Objects::nonNull).findFirst().orElse(null);
            case DEVICE_COUNT -> confirmed(m.devices().held());case HASHRATE -> null;
            case NEXT_DUE_AT -> t==null?null:t.nextDueAt();case WAITING_SINCE_AT -> t==null?null:t.waitingSinceAt();
            case STATE_CHANGED_AT -> t==null?null:t.preferenceUpdatedAt();case POOL_ENTERED_AT -> d.poolEnteredAt();default -> null;
        };
    }
    private static Long confirmed(Count c){return c==null?null:c.confirmedValue();}
    private static BigDecimal deposit(List<CustomerCurrencyTotals> totals,String currency){return totals.stream().filter(t->t.currency().equals(currency)).map(t->t.deposits().confirmedAmount()).filter(Objects::nonNull).findFirst().orElse(null);}
    private static Map<String,Object> customer(SupportAnalyticsStats.Customer c,Sources s) {
        var d=s.display().get(c.customerId());var t=s.tasks().get(c.customerId());var m=c.metrics();var first=c.first().observedCandidate();
        var advisor=s.people().stream().filter(p->Objects.equals(p.id(),c.owner().agentId())).findFirst().orElse(null);
        return map("customerId",Long.toString(c.customerId()),"customerNo",d.customerNo(),"nickname",d.nickname(),"avatar",customerAvatarRef(d),"level",level(d),
            "customTags",s.tags().stream().filter(tg->tg.customerId().equals(c.customerId())).sorted(Comparator.comparing(CustomerTagRow::id)).map(CustomerTagRow::tag).toList(),
            "systemTags",systemTags(d),"systemTagsStatus","PARTIAL","profileRef","/api/admin/content/support-workbench/customers/"+c.customerId(),
            "registeredAt",time(d.registeredAt()),"assignedAt",utc(d.assignedAt()),"category",c.category().name(),"placement",c.placement().name(),"handoverRequired",c.handoverRequired(),"owner",map("agentId",id(c.owner().agentId()),"groupId",id(c.owner().groupId()),"agentName",advisor==null?null:advisor.nickname()==null?advisor.username():advisor.nickname(),"advisorAvatar",personAvatar(advisor),"advisorAvatarRef",personAvatarRef(advisor,c.customerId())),
            "first",map("state",c.first().state().name(),"status",c.first().status().name(),"kind",first==null?null:first.kind(),"currency",first==null?null:first.currency(),"amount",first==null?null:decimal(first.amount()),"succeededAt",first==null?null:time(first.succeededAt())),
            "lifetime",m.lifetime().stream().map(SupportAnalyticsPrivateQueryService::customerMoney).toList(),"lifetimeStatus",m.lifetimeStatus().name(),
            "invitations",map("direct",count(m.invitations().directCustomers()),"descendants",count(m.invitations().descendantCustomers()),"status",m.invitations().status().name(),"deposits",m.invitations().descendantDeposits().stream().map(x->map("currency",x.currency(),"deposits",money(x.deposits()))).toList()),
            "devices",devices(m.devices()),"activity",map("state",m.activity().state().name(),"status",m.activity().status().name(),"lastEffectiveAt",utc(m.activity().lastEffectiveAt())),
            "task",t==null?map("status","UNAVAILABLE"):map("status",s.taskRead().name(),"enabled",nullableBool(t.enabled()),"due",nullableBool(t.due()),"waitingReply",nullableBool(t.waitingReply()),"firstContact",nullableBool(t.firstContact()),"nextDueAt",utc(t.nextDueAt()),"waitingSinceAt",businessTime(t.waitingSinceAt())),"funds",funds(s,Set.of(c.customerId())));
    }

    private static String level(RootDisplayRow d){return d.vRank()!=null && !d.vRank().isBlank()?d.vRank():d.userLevel()!=null && !d.userLevel().isBlank()?d.userLevel():null;}
    private static String customerAvatarRef(RootDisplayRow d){String key=d.avatarObjectKey();return key!=null && key.startsWith("users/"+d.customerId()+"/avatar/") && !key.contains("..")?"/api/admin/content/support-workbench/customers/"+d.customerId()+"/avatar":null;}
    private static Map<String,Object> personAvatar(PersonStamp p){return p!=null && p.avatarAssetId()!=null && p.avatarAssetId().matches("[a-f0-9-]{36}") && p.avatarVersion()!=null && p.avatarVersion()>=0?map("assetId",p.avatarAssetId(),"version",p.avatarVersion()):null;}
    private static String personAvatarRef(PersonStamp p,Long customer){return personAvatar(p)==null?null:"/api/admin/content/support-agents/"+p.id()+"/avatar"+(customer==null?"":"?customerId="+customer);}
    private static List<String> systemTags(RootDisplayRow d){var tags=new ArrayList<String>();if(level(d)!=null)tags.add(level(d));if(d.status()!=null)switch(d.status().toUpperCase(Locale.ROOT)){case "ACTIVE"->tags.add("账户正常");case "RESTRICTED"->tags.add("账户受限");case "FROZEN"->tags.add("账户冻结");case "BANNED"->tags.add("账户停用");default->{}}return List.copyOf(tags);}
    private static Map<String,Object> selectedFunds(Sources s,List<SupportAnalyticsStats.Customer> selected) {
        var out=funds(s,selected.stream().map(SupportAnalyticsStats.Customer::customerId).collect(java.util.stream.Collectors.toSet()));
        var groups=new TreeSet<>(s.groups());selected.stream().map(c->c.owner().groupId()).filter(Objects::nonNull).forEach(groups::add);
        out.put("groups",groups.stream().map(group->map("groupId",Long.toString(group),"funds",funds(s,selected.stream().filter(c->Objects.equals(c.owner().groupId(),group)).map(SupportAnalyticsStats.Customer::customerId).collect(java.util.stream.Collectors.toSet())))).toList());return out;
    }
    private static Map<String,Object> funds(Sources s,Set<Long> ids) {
        if(s.funds()==null)return map("balanceStatus","UNAVAILABLE","withdrawalStatus","UNAVAILABLE");
        var f=s.funds();var wallets=f.wallets()==null?List.<SupportFundsReadFacade.WalletEvidence>of():f.wallets().rows().stream().filter(w->ids.contains(w.customerId())).toList();
        var withdrawals=f.withdrawals()==null?List.<SupportFundsReadFacade.WithdrawalEvidence>of():f.withdrawals().rows().stream().filter(w->ids.contains(w.customerId())).toList();
        boolean known=s.fundsRead()==SupportAnalyticsQueryVersion.ReadState.COMPLETE && f.wallets()!=null && wallets.size()==ids.size() && wallets.stream().allMatch(w->w.state()==SupportFundsReadFacade.EvidenceState.READY);
        var balances=new ArrayList<Map<String,Object>>();for(String currency:List.of("USDT","NEX")) {
            var values=wallets.stream().map(w->"USDT".equals(currency)?w.usdtAvailable():w.nexAvailable()).filter(SupportAnalyticsPrivateQueryService::nonnegativeAmount).toList();
            BigDecimal observed=values.isEmpty()?ids.isEmpty() && s.fundsRead()==SupportAnalyticsQueryVersion.ReadState.COMPLETE?BigDecimal.ZERO:null:values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
            balances.add(map("currency",currency,"observedAmount",decimal(observed),"confirmedAmount",known?decimal(observed):null));
        }
        var currencies=new TreeSet<String>();withdrawals.stream().map(SupportFundsReadFacade.WithdrawalEvidence::currency).filter(Objects::nonNull).filter(Set.of("USDT","NEX")::contains).forEach(currencies::add);
        var totals=new ArrayList<Map<String,Object>>();for(String currency:currencies) {
            var successful=withdrawals.stream().filter(w->currency.equals(w.currency()) && w.state()==SupportFundsReadFacade.WithdrawalState.SUCCESS).toList();
            var processing=withdrawals.stream().filter(w->currency.equals(w.currency()) && w.state()==SupportFundsReadFacade.WithdrawalState.PROCESSING).toList();
            totals.add(map("currency",currency,"successPrincipal",observed(withdrawalSum(successful,SupportFundsReadFacade.WithdrawalEvidence::principal)),"successActualFee",observed(withdrawalSum(successful,SupportFundsReadFacade.WithdrawalEvidence::actualFee)),
                "successNet",observed(withdrawalSum(successful,SupportFundsReadFacade.WithdrawalEvidence::net)),"processingPrincipal",observed(withdrawalSum(processing,SupportFundsReadFacade.WithdrawalEvidence::principal))));
        }
        long knownWithdrawals=withdrawals.stream().filter(w->w.state()!=SupportFundsReadFacade.WithdrawalState.UNKNOWN).count();
        String withdrawalStatus=s.fundsRead()!=SupportAnalyticsQueryVersion.ReadState.COMPLETE?"UNKNOWN":knownWithdrawals==withdrawals.size()?"READY":knownWithdrawals==0?"UNKNOWN":"PARTIAL";
        return map("scope","SELECTED_CURRENT_CUSTOMERS","customerCount",(long)ids.size(),"balanceStatus",known?"READY":wallets.stream().anyMatch(w->w.state()==SupportFundsReadFacade.EvidenceState.READY)?"PARTIAL":"UNKNOWN",
            "balances",balances,"sourceObservationStatus",s.fundsRead().name(),"walletReadStatus",f.wallets()==null?"UNKNOWN":f.wallets().state()==null?"UNKNOWN":f.wallets().state().name(),
            "walletReadReasons",f.wallets()==null?List.of():f.wallets().reasons().stream().map(Enum::name).toList(),"withdrawalReadStatus",f.withdrawals()==null?"UNKNOWN":f.withdrawals().state()==null?"UNKNOWN":f.withdrawals().state().name(),
            "withdrawalReadReasons",f.withdrawals()==null?List.of():f.withdrawals().reasons().stream().map(Enum::name).toList(),"withdrawalStatus",withdrawalStatus,"withdrawalReadScope","CURRENT_CUSTOMER_RAW_ROWS",
            "historicalEnvironmentStatus",f.withdrawals()==null || f.withdrawals().historicalEnvironmentStatus()==null?"UNKNOWN":f.withdrawals().historicalEnvironmentStatus().name(),"eventOwnershipStatus",f.withdrawals()==null || f.withdrawals().eventOwnershipStatus()==null?"UNKNOWN":f.withdrawals().eventOwnershipStatus().name(),"withdrawals",totals);
    }
    private static boolean nonnegativeAmount(BigDecimal value){return value!=null && value.signum()>=0 && value.stripTrailingZeros().scale()<=6 && value.precision()-value.scale()<=12;}
    private static BigDecimal withdrawalSum(List<SupportFundsReadFacade.WithdrawalEvidence> rows,java.util.function.Function<SupportFundsReadFacade.WithdrawalEvidence,BigDecimal> value){var values=rows.stream().map(value).filter(SupportAnalyticsPrivateQueryService::nonnegativeAmount).toList();return rows.isEmpty() || values.size()!=rows.size()?null:values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static Map<String,Object> observed(BigDecimal value){return map("observedAmount",decimal(value),"confirmedAmount",null);}

    // Deliberately explicit private whitelist: no reflection and no raw Stats/Evidence serialization.
    static Map<String,Object> map(Object... fields){var out=new LinkedHashMap<String,Object>();for(int i=0;i<fields.length;i+=2)out.put((String)fields[i],fields[i+1]);return out;}
    static String decimal(BigDecimal n){return n==null?null:n.signum()==0?"0":n.stripTrailingZeros().toPlainString();}
    // Finance, user registration, device and conversation timestamps follow the existing businessUtc profile contract.
    private static String time(LocalDateTime t){return businessTime(t);}
    private static String businessTime(LocalDateTime t){return t==null?null:t.atZone(SupportAnalyticsQueryRequest.BUSINESS_ZONE).toInstant().toString();}
    // Additive support assignment/maintenance/activity history explicitly stores UTC DATETIME.
    private static String utc(LocalDateTime t){return t==null?null:t.toInstant(java.time.ZoneOffset.UTC).toString();}
    private static String id(Long n){return n==null?null:n.toString();}
    private static Map<String,Object> count(Count c){return map("observed",c.observedValue(),"confirmed",c.confirmedValue(),"status",c.status().name());}
    private static Map<String,Object> money(Money m){return map("observed",decimal(m.observedAmount()),"confirmed",decimal(m.confirmedAmount()),"observedEvents",m.observedEvents(),"confirmedEvents",m.confirmedEvents(),"observedCustomers",m.observedCustomers(),"status",m.status().name());}
    private static Map<String,Object> customerMoney(CustomerCurrencyTotals x){return map("currency",x.currency(),"deposits",money(x.deposits()),"purchases",money(x.purchases()));}
    private static Map<String,Object> devices(DeviceSummary d){return map("held",count(d.held()),"unknownHolding",count(d.unknownHolding()),"status",d.status().name(),"partitions",d.partitions().stream().map(p->map("dimension",p.dimension(),"value",p.value(),"devices",count(p.devices()))).toList());}
    private static Map<String,Object> current(CurrentMetrics m){return map("lifetimeBasis",m.lifetimeBasis().name(),"status",m.status().name(),"ownLifetime",m.ownLifetime().stream().map(SupportAnalyticsPrivateQueryService::customerMoney).toList(),"firstConfirmed",count(m.firstConfirmed()),"firstNone",count(m.firstNone()),"firstUnknown",count(m.firstUnknown()),"devices",devices(m.devices()),"activity",map("active",count(m.activity().active()),"inactive",count(m.activity().inactive()),"unknown",count(m.activity().unknown()),"status",m.activity().status().name(),"window",map("days",m.activity().window().days(),"from",utc(m.activity().window().fromInclusive()),"through",utc(m.activity().window().throughInclusive()),"status",m.activity().window().status().name())));}
    private static Map<String,Object> account(AccountRow a){return map("serviceAccount",a.serviceAccount(),"supervisorAccount",a.supervisorAccount(),"serviceCategoryStatus",a.serviceCategoryStatus().name(),"supervisorCategoryStatus",a.supervisorCategoryStatus().name(),"accountState",a.accountState().name(),"serviceQualification",a.serviceQualification().name(),"supervisorQualification",a.supervisorQualification().name(),"receptionEligibility",a.receptionState().name(),"memberState",a.memberState().name(),"groupId",id(a.groupId()),"handoverRequired",a.handoverRequired(),"status",a.status().name());}
    private static Map<String,Object> personnel(PersonnelSummary p){return map("status",p.status().name(),"serviceAccounts",count(p.serviceAccounts()),"groupMembers",count(p.groupMembers()),"supervisors",count(p.supervisors()),"people",count(p.people()),"groups",count(p.groups()),"partitions",p.partitions().stream().map(x->map("dimension",x.dimension(),"value",x.value(),"accounts",count(x.accounts()))).toList());}
    private static Map<String,Object> financial(FinancialSummary f){return map("status",f.status().name(),"firstCandidates",count(f.firstCandidates()),"currencies",f.currencies().stream().map(x->map("currency",x.currency(),"deposits",money(x.deposits()),"purchases",money(x.purchases()),"purchaseRefunds",money(x.purchaseRefunds()),"net",money(x.net()))).toList(),"firstSources",f.firstSources().stream().map(x->map("currency",x.currency(),"deposits",money(x.deposits()),"purchases",money(x.purchases()))).toList());}
    private static Map<String,Object> scopeSummary(Result r,Sources s){return map("mode",r.currentScope().mode().name(),"total",r.currentScope().total(),"bound",r.currentScope().bound(),"pending",r.currentScope().pending(),"anomaly",r.currentScope().anomaly(),"status",r.currentScope().status().name(),"current",current(r.currentMetrics()),"financial",financial(r.financialSummary()),"restrictedSummary",map("customers",count(r.restrictedSummary().customers()),"firstCandidates",count(r.restrictedSummary().firstCandidates())),"personnel",personnel(r.personnel()),"groups",r.groups().stream().map(g->map("groupId",Long.toString(g.groupId()),"name",s.groupRows().stream().filter(x->x.id()==g.groupId()).map(GroupStamp::name).findFirst().orElse(null),"total",g.customers().total(),"current",current(g.current()),"period",financial(g.period()),"personnel",personnel(g.personnel()))).toList());}
}
