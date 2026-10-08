package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

/** Group facts only. Customer object authorization is integrated separately at each existing boundary. */
@ApplicationService
@RequiredArgsConstructor
public class SupportGroupService {
    private final SupportGroupMapper mapper;
    private final SupportOwnershipService ownership;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;

    @Transactional
    public List<Group> groups() {
        authority("service_m1_read");
        ReadScope scope=ownership.defaultQueryScope(null,null);
        if(scope.mode()==ReadMode.PERSONAL)forbidden();
        return mapper.scopedGroups(scope);
    }

    @Transactional
    public Map<String,Object> detail(Long id) {
        authority("service_m1_read");
        ReadScope scope=ownership.defaultQueryScope(id,null);
        if(scope.mode()==ReadMode.PERSONAL)forbidden();
        Group g=mapper.readableGroup(scope,id);if(g==null)notFound();
        return Map.of("group",g,"members",mapper.members(id),"memberCount",mapper.memberCount(id),
                "routedCustomers",mapper.routeCount(id));
    }

    @Transactional
    public Map<String,Object> supervisors() {
        authority("platform_a1_read"); ownership.requireSuperAdmin();
        var rows=mapper.supervisors();
        Set<Long> supervisorIds=new HashSet<>();
        for(var row:rows) supervisorIds.add(((Number)row.get("adminId")).longValue());
        Set<Long> members=new HashSet<>(mapper.memberIds());
        Set<Long> people=new HashSet<>(members);people.addAll(supervisorIds);
        return Map.of("items",rows,"supervisorCount",supervisorIds.size(),"memberCount",members.size(),"peopleCount",people.size());
    }

    public ApiResult<Group> create(String key,Create r) {
        if(r==null) invalid(); name(r.name()); id(r.supervisorAdminId());
        management();
        if(!isSuper() && !ownership.actorId().equals(r.supervisorAdminId())) forbidden();
        return command("CREATE",key,r,r.reason(),()-> {
            lockAccounts(ownership.actorId(),r.supervisorAdminId()); management();
            if(!isSuper() && !ownership.actorId().equals(r.supervisorAdminId())) forbidden();
            requireSupervisor(r.supervisorAdminId());
            LocalDateTime at=mapper.now();
            Map<String,Object> values=new HashMap<>(); values.put("name",r.name().trim());values.put("owner",r.supervisorAdminId());values.put("at",at);
            mapper.insertGroup(values);Long group=((Number)values.get("id")).longValue();
            changed(mapper.insertOwner(group,r.supervisorAdminId(),at,1L,ownership.actorId(),r.reason().trim(),operation(key)));
            record("CREATED",group,key,Map.of("request",r,"before","ABSENT","after",mapper.group(group)));
            invalidate(r.reason(),r.supervisorAdminId());
            return ApiResult.ok(mapper.group(group));
        });
    }

    public ApiResult<Group> rename(Long id,String key,Rename r) {
        id(id);if(r==null)invalid();name(r.name());version(r.expectedVersion());management();readable(mapper.group(id));
        return command("RENAME",key,List.of(id,r),r.reason(),()-> {
            lockAccounts(ownership.actorId());Group g=readable(mapper.lockGroup(id)); expected(g.version(),r.expectedVersion());
            if("ARCHIVED".equals(g.status())) throw new BizException(409,"SUPPORT_GROUP_ARCHIVED");
            changed(mapper.rename(id,r.name().trim(),r.expectedVersion(),mapper.now()));record("RENAMED",id,key,Map.of("request",r,"before",g,"after",mapper.group(id)));
            return ApiResult.ok(mapper.group(id));
        });
    }

    public ApiResult<Group> status(Long id,String key,Status r) {
        id(id);if(r==null||!Set.of("ENABLED","DISABLED","ARCHIVED").contains(String.valueOf(r.status())))invalid();
        version(r.expectedVersion());management();
        // Read owner before locks, then recheck under the group lock; never append a reverse-order account lock.
        Group seen=readable(mapper.group(id));
        return command("STATUS",key,List.of(id,r),r.reason(),()-> {
            lockAccounts(ownership.actorId(),seen.supervisorAdminId());Group g=readable(mapper.lockGroup(id));
            expected(g.version(),r.expectedVersion());expected(g.supervisorAdminId(),seen.supervisorAdminId());
            if("ARCHIVED".equals(g.status()) || g.status().equals(r.status())
                    || ("ARCHIVED".equals(r.status())&&!"DISABLED".equals(g.status())))
                throw new BizException(409,"SUPPORT_GROUP_TRANSITION_INVALID");
            if("ENABLED".equals(r.status())) requireSupervisor(g.supervisorAdminId());
            else if(mapper.memberCount(id)!=0 || mapper.routeCount(id)!=0 || mapper.pendingGroupOperations(id)!=0)
                throw new BizException(409,"SUPPORT_GROUP_HANDOVER_REQUIRED");
            changed(mapper.status(id,r.status(),r.expectedVersion(),mapper.now()));record("STATUS_CHANGED",id,key,Map.of("request",r,"before",g,"after",mapper.group(id)));
            invalidate(r.reason(),g.supervisorAdminId());
            return ApiResult.ok(mapper.group(id));
        });
    }

    public ApiResult<Group> owner(Long id,String key,ffdd.opsconsole.content.dto.SupportGroupRequests.Owner r) {
        id(id);if(r==null)invalid();id(r.supervisorAdminId());version(r.expectedVersion());
        authority("service_m1_write");ownership.requireSuperAdmin();Group seen=mapper.group(id);
        if(seen==null)notFound();
        return command("OWNER",key,List.of(id,r),r.reason(),()-> {
            lockAccounts(ownership.actorId(),seen.supervisorAdminId(),r.supervisorAdminId());ownership.requireSuperAdmin();
            Group g=mapper.lockGroup(id);if(g==null)notFound();expected(g.version(),r.expectedVersion());expected(g.supervisorAdminId(),seen.supervisorAdminId());
            requireSupervisor(r.supervisorAdminId());
            if(g.supervisorAdminId().equals(r.supervisorAdminId()))throw new BizException(409,"SUPPORT_GROUP_OWNER_UNCHANGED");
            LocalDateTime at=mapper.now();changed(mapper.closeOwner(id,g.supervisorAdminId(),at));
            changed(mapper.owner(id,r.supervisorAdminId(),r.expectedVersion(),at));
            changed(mapper.insertOwner(id,r.supervisorAdminId(),at,g.version()+1,ownership.actorId(),r.reason().trim(),operation(key)));
            record("OWNER_CHANGED",id,key,Map.of("request",r,"before",g,"after",mapper.group(id)));
            var affected=new TreeSet<Long>(mapper.candidateIdsSnapshot(id));affected.add(g.supervisorAdminId());affected.add(r.supervisorAdminId());
            events.publishEvent(new ScopeChanged(affected,r.reason().trim()));return ApiResult.ok(mapper.group(id));
        });
    }

    public ApiResult<Map<String,Object>> move(Long agent,String key,Move r) {
        id(agent);if(r==null)invalid();if(r.targetGroupId()!=null)id(r.targetGroupId());
        nonnegative(r.expectedMemberVersion());management();
        Member seen=mapper.memberCurrent(agent);
        Long seenSource=seen==null?null:seen.groupId();
        if(!isSuper()&&(seenSource==null||r.targetGroupId()==null))forbidden();
        if(seenSource!=null)readable(mapper.group(seenSource));
        if(r.targetGroupId()!=null)readable(mapper.group(r.targetGroupId()));
        var plannedGroupIds=new TreeSet<Long>();if(seenSource!=null)plannedGroupIds.add(seenSource);if(r.targetGroupId()!=null)plannedGroupIds.add(r.targetGroupId());
        Map<Long,Group> plannedGroups=new HashMap<>();
        for(Long group:plannedGroupIds)plannedGroups.put(group,mapper.group(group));
        return command("MEMBER",key,List.of(agent,r),r.reason(),()-> {
            var accountIds=new TreeSet<Long>(List.of(ownership.actorId(),agent));
            for(Group g:plannedGroups.values())accountIds.add(g.supervisorAdminId());
            lockAccounts(accountIds.toArray(Long[]::new));management();
            var qualification=mapper.qualification(agent,"SERVICE");
            if(qualification==null||"REMOVED".equals(qualification.state())) throw new BizException(422,"SUPPORT_SERVICE_QUALIFICATION_REQUIRED");
            Member old=mapper.memberCurrent(agent);expected(old==null?0L:old.version(),r.expectedMemberVersion());
            Long source=old==null?null:old.groupId();
            if(!Objects.equals(source,seenSource))throw new BizException(409,"SUPPORT_GROUP_VERSION_CONFLICT");
            if(Objects.equals(source,r.targetGroupId()))throw new BizException(409,"SUPPORT_GROUP_MEMBER_UNCHANGED");
            Map<Long,Group> groups=new HashMap<>();TreeSet<Long> ids=new TreeSet<>();if(source!=null)ids.add(source);if(r.targetGroupId()!=null)ids.add(r.targetGroupId());
            for(Long group:ids)groups.put(group,readable(mapper.lockGroup(group)));
            for(Group g:groups.values())expected(g.supervisorAdminId(),plannedGroups.get(g.id()).supervisorAdminId());
            if(!isSuper()&&(source==null||r.targetGroupId()==null))forbidden();
            if(source!=null)expected(groups.get(source).version(),r.sourceGroupVersion());
            if(r.targetGroupId()!=null) {
                Group target=groups.get(r.targetGroupId());expected(target.version(),r.targetGroupVersion());
                if(!"ENABLED".equals(target.status()))throw new BizException(409,"SUPPORT_GROUP_TARGET_UNAVAILABLE");
                if(mapper.ownerCurrent(target.id())==null)throw new BizException(409,"SUPPORT_GROUP_TARGET_UNAVAILABLE");
                requireSupervisor(target.supervisorAdminId());
            }
            LocalDateTime at=mapper.now();if(old!=null)changed(mapper.closeMember(old.id(),old.version(),at));
            changed(mapper.insertMember(agent,r.targetGroupId(),at,(old==null?0:old.version())+1,ownership.actorId(),r.reason().trim(),operation(key)));
            for(Group group:groups.values())changed(mapper.touch(group.id(),group.version(),at));
            record("MEMBER_MOVED",agent,key,Map.of("request",r,"before",old==null?"UNPROVEN":old,"after",mapper.member(agent)));
            var affected=new TreeSet<Long>();affected.add(agent);for(Group g:groups.values())affected.add(g.supervisorAdminId());
            events.publishEvent(new ScopeChanged(affected,r.reason().trim()));
            return ApiResult.ok(Map.of("member",mapper.member(agent),"boundCustomers",mapper.boundCount(agent)));
        });
    }

    public ApiResult<List<ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification>> qualification(Long admin,String key,
            ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification r) {
        id(admin);if(r==null||!Set.of("SERVICE","SUPERVISOR").contains(String.valueOf(r.qualificationKind()))
                ||!Set.of("ENABLED","DISABLED","REMOVED").contains(String.valueOf(r.state())))invalid();
        nonnegative(r.expectedQualificationVersion());nonnegative(r.expectedAccountVersion());
        authority("platform_a1_write");ownership.requireSuperAdmin();
        return command("QUALIFICATION",key,List.of(admin,r),r.reason(),()-> {
            lockAccounts(ownership.actorId(),admin);ownership.requireSuperAdmin();
            Account account=mapper.lockAccount(admin);expected(account.version(),r.expectedAccountVersion());
            var old=mapper.qualification(admin,r.qualificationKind());expected(old==null?0L:old.version(),r.expectedQualificationVersion());
            if("ENABLED".equals(r.state()) && (account.status()!=1||mapper.compatibleAccount(admin)!=1))
                throw new BizException(422,"SUPPORT_QUALIFICATION_ACCOUNT_INCOMPATIBLE");
            if(!"ENABLED".equals(r.state()))guardQualificationExit(admin,r.qualificationKind());
            if(old!=null&&old.state().equals(r.state()))throw new BizException(409,"SUPPORT_QUALIFICATION_UNCHANGED");
            LocalDateTime at=mapper.now();replaceQualification(admin,r.qualificationKind(),r.state(),old,at,key,r.reason());
            if("SERVICE".equals(r.qualificationKind()) && "ENABLED".equals(r.state()) && mapper.member(admin)==null)
                changed(mapper.insertMember(admin,null,at,1L,ownership.actorId(),r.reason().trim(),operation(key)));
            record("QUALIFICATION_CHANGED",admin,key,Map.of("request",r,"before",old==null?"UNPROVEN":old,"after",mapper.qualification(admin,r.qualificationKind())));
            invalidate(r.reason(),admin);return ApiResult.ok(mapper.qualifications(admin));
        });
    }

    /** A route changes only the unbound queue. It never creates or moves a personal binding. */
    public ApiResult<ffdd.opsconsole.content.domain.SupportGroupFacts.Route> route(Long customer,String key,
            ffdd.opsconsole.content.dto.SupportGroupRequests.Route r) {
        id(customer);if(r==null)invalid();if(r.targetGroupId()!=null)id(r.targetGroupId());
        nonnegative(r.expectedRouteVersion());authority("service_m1_write");ownership.requireSuperAdmin();
        var seen=mapper.routeSnapshot(customer);
        return command("ROUTE",key,List.of(customer,r),r.reason(),()-> {
            ownership.lockCustomer(customer);
            var groupIds=new TreeSet<Long>();if(seen!=null && seen.groupId()!=null)groupIds.add(seen.groupId());
            if(r.targetGroupId()!=null)groupIds.add(r.targetGroupId());
            Map<Long,Group> beforeGroups=new HashMap<>();TreeSet<Long> admins=new TreeSet<>();admins.add(ownership.actorId());
            for(Long groupId:groupIds) {Group g=mapper.group(groupId);if(g==null)notFound();beforeGroups.put(groupId,g);admins.add(g.supervisorAdminId());}
            lockAccounts(admins.toArray(Long[]::new));ownership.requireSuperAdmin();
            Map<Long,Group> locked=new HashMap<>();
            for(Long groupId:groupIds) {Group g=mapper.lockGroup(groupId);if(g==null)notFound();expected(g.version(),beforeGroups.get(groupId).version());locked.put(groupId,g);}
            if(mapper.currentBindingCount(customer)!=0 || mapper.routePoolVersion(customer)==null)
                throw new BizException(409,"SUPPORT_ROUTE_REQUIRES_UNBOUND_POOL");
            var old=mapper.routeCurrent(customer);
            if(old==null && mapper.openRouteCount(customer)!=0)throw new BizException(409,"SUPPORT_GROUP_ROUTE_CONFLICT");
            expected(old==null?0L:old.version(),r.expectedRouteVersion());
            if(!Objects.equals(seen==null?null:seen.groupId(),old==null?null:old.groupId()))
                throw new BizException(409,"SUPPORT_GROUP_ROUTE_CONFLICT");
            Long source=old==null?null:old.groupId();
            if(Objects.equals(source,r.targetGroupId()))throw new BizException(409,"SUPPORT_GROUP_ROUTE_UNCHANGED");
            if(source!=null)expected(locked.get(source).version(),r.sourceGroupVersion());
            if(r.targetGroupId()!=null) {
                Group target=locked.get(r.targetGroupId());expected(target.version(),r.targetGroupVersion());
                if(!"ENABLED".equals(target.status()) || mapper.ownerCurrent(target.id())==null)
                    throw new BizException(409,"SUPPORT_GROUP_TARGET_UNAVAILABLE");
                requireSupervisor(target.supervisorAdminId());
            }
            LocalDateTime at=mapper.now();if(old!=null)changed(mapper.closeRoute(old.id(),old.version(),at));
            changed(mapper.insertRoute(customer,r.targetGroupId(),at,(old==null?0L:old.version())+1,ownership.actorId(),r.reason().trim(),operation(key)));
            for(Group g:locked.values())changed(mapper.touch(g.id(),g.version(),at));
            var after=mapper.routeCurrent(customer);
            record("ROUTE_CHANGED",customer,key,Map.of("request",r,"before",old==null?"UNROUTED":old,"after",after));
            var affected=new HashSet<Long>();for(Group g:locked.values())affected.add(g.supervisorAdminId());affected.add(ownership.actorId());
            events.publishEvent(new ScopeChanged(affected,r.reason().trim()));return ApiResult.ok(after);
        });
    }

    /** Called with the existing A1 account lock, before its CAS update. No account service dependency. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void accountChanging(Long admin,String nextRole,boolean disabling,String key,String reason) {
        boolean incompatible=nextRole!=null&&!Set.of("support","super").contains(nextRole);
        var supervisor=mapper.qualification(admin,"SUPERVISOR");
        if((incompatible||disabling)&&supervisor!=null&&!"REMOVED".equals(supervisor.state())) {
            guardQualificationExit(admin,"SUPERVISOR");
            if(mapper.boundCount(admin)>0)throw new BizException(409,"SUPPORT_PERSONAL_HANDOVER_REQUIRED");
        }
        if(incompatible)guardQualificationExit(admin,"SERVICE");
    }

    /** Runs only after A1 CAS succeeded, in the same transaction as its role/status and audit. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void accountChanged(Long admin,String nextRole,boolean disabling,String key,String reason) {
        boolean incompatible=nextRole!=null&&!Set.of("support","super").contains(nextRole);
        var supervisor=mapper.qualification(admin,"SUPERVISOR");
        if(incompatible || (disabling&&supervisor!=null&&"ENABLED".equals(supervisor.state()))) {
            LocalDateTime at=mapper.now();
            var before=mapper.qualifications(admin);
            for(var q:before) {
                if("REMOVED".equals(q.state())||(!incompatible&&!"SUPERVISOR".equals(q.qualificationKind())))continue;
                replaceQualification(admin,q.qualificationKind(),incompatible?"REMOVED":"DISABLED",q,at,key,reason);
            }
            record("ACCOUNT_QUALIFICATIONS_CHANGED",admin,key,Map.of("role",nextRole==null?"UNCHANGED":nextRole,"disabled",disabling,"reason",reason,"before",before,"after",mapper.qualifications(admin)));
        }
        // Ordinary account disable deliberately keeps member and assignment rows, and the independent qualification.
        invalidate(reason,admin);
    }

    /** Legacy position text cannot grant/remove either qualification after cutover. */
    public void validateLegacySeat(Long admin,String requestedSeat) {
        if(mapper.cutoverApplied()==0 && mapper.qualifications(admin).isEmpty())return;
        String kind="MANAGER".equals(requestedSeat)?"SUPERVISOR":"SERVICE";
        if(!Set.of("MANAGER","DEDICATED").contains(requestedSeat)||mapper.qualified(admin,kind)!=1)
            throw new BizException(409,"SUPPORT_EXPLICIT_QUALIFICATION_REQUIRED");
    }

    /** Null means the explicitly pre-cutover legacy resolver still owns this account. */
    public Boolean supervisorQualification(Long admin) {
        if(mapper.qualification(admin,"SUPERVISOR")==null && mapper.cutoverApplied()==0)return null;
        return mapper.qualified(admin,"SUPERVISOR")==1;
    }

    private void guardQualificationExit(Long admin,String kind) {
        if("SUPERVISOR".equals(kind)&&mapper.ownedGroupCount(admin)>0)throw new BizException(409,"SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");
        if("SERVICE".equals(kind)&&(mapper.boundCount(admin)>0||(mapper.member(admin)!=null&&mapper.member(admin).groupId()!=null)))
            throw new BizException(409,"SUPPORT_PERSONAL_HANDOVER_REQUIRED");
    }
    private void replaceQualification(Long admin,String kind,String state,
            ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification old,LocalDateTime at,String key,String reason) {
        if(old!=null)changed(mapper.closeQualification(old.id(),old.version(),at));
        changed(mapper.insertQualification(admin,kind,state,at,(old==null?0:old.version())+1,ownership.actorId(),reason.trim(),operation(key)));
    }
    private Group readable(Group group) {
        if(group==null)notFound();
        if(!isSuper() && mapper.readableGroup(new ReadScope(ownership.actorId(),ReadMode.MANAGED,group.id(),null),group.id())==null)notFound();
        return group;
    }
    private void management(){authority("service_m1_write");if(!isSuper())requireSupervisor(ownership.actorId());}
    private boolean isSuper(){return ownership.currentSuperAdmin();}
    private void requireSupervisor(Long id){if(mapper.qualificationCurrent(id,"SUPERVISOR")==null)throw new BizException(422,"SUPPORT_SUPERVISOR_QUALIFICATION_REQUIRED");}
    private void invalidate(String reason,Long... ids){events.publishEvent(new ScopeChanged(new HashSet<>(Arrays.asList(ids)),reason.trim()));}
    private void lockAccounts(Long... ids){for(Long id:new TreeSet<>(Arrays.asList(ids)))if(mapper.lockAccount(id)==null)throw new BizException(404,"SUPPORT_ACCOUNT_NOT_FOUND");}
    private void record(String action,Long id,String key,Object detail){
        String resource=action.contains("QUALIFICATION")?"SUPPORT_ACCOUNT_QUALIFICATION":action.equals("MEMBER_MOVED")?"SUPPORT_GROUP_MEMBER":"SUPPORT_GROUP";
        audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_GROUP_"+action).resourceType(resource).resourceId(String.valueOf(id)).actorId(ownership.actorId()).result("SUCCESS").detail(Map.of("operationId",key,"change",detail)).build());
    }
    @SuppressWarnings("unchecked") private <T> ApiResult<T> command(String scope,String key,Object request,String reason,Supplier<ApiResult<T>> action){
        if(key==null||key.isBlank()||key.length()>96||reason==null||reason.trim().length()<6||reason.trim().length()>200)invalid();
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return idempotency.execute("SUPPORT_GROUP_"+scope+":"+ownership.actorId(),key,hash,ApiResult.class,action::get);
    }
    private String operation(String key){return ownership.actorId()+":"+key;}
    private static void authority(String p){if(!SupportOwnershipService.hasAuthority(p))forbidden();}
    private static void name(String s){if(s==null||s.isBlank()||s.trim().length()>120)invalid();}
    private static void id(Long id){if(id==null||id<1||id>9007199254740991L)invalid();}
    private static void version(Long v){if(v==null||v<1)invalid();}
    private static void nonnegative(Long v){if(v==null||v<0)invalid();}
    private static void expected(Long actual,Long expected){if(!Objects.equals(actual,expected))throw new BizException(409,"SUPPORT_GROUP_VERSION_CONFLICT");}
    private static void changed(int rows){if(rows!=1)throw new BizException(409,"SUPPORT_GROUP_VERSION_CONFLICT");}
    private static void forbidden(){throw new BizException(403,"SUPPORT_GROUP_FORBIDDEN");}
    private static void notFound(){throw new BizException(404,"SUPPORT_GROUP_NOT_FOUND");}
    private static void invalid(){throw new BizException(422,"SUPPORT_GROUP_REQUEST_INVALID");}
}
