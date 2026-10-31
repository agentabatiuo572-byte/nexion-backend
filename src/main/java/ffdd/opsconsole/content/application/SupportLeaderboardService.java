package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.dto.SupportLeaderboardQuery;
import ffdd.opsconsole.content.dto.SupportLeaderboardQuery.Normalized;
import ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardAuthorizationMapper.Qualification;
import ffdd.opsconsole.shared.exception.BizException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Public aggregate projection, separate from all existing private object scopes. */
@Service
public class SupportLeaderboardService {
    public static final String REFRESH_KEY="support.leaderboard.refresh_interval_minutes";
    private static final String DEFINITION=SupportLeaderboardPolicy.DEFINITION;
    private final SupportOwnershipService ownership;
    private final SupportLeaderboardAuthorizationMapper authorization;
    private final SupportLeaderboardCache cache;
    private final SupportAdminAvatarService avatars;
    private final Clock clock;

    public SupportLeaderboardService(SupportOwnershipService ownership,SupportLeaderboardAuthorizationMapper authorization,
        SupportLeaderboardCache cache,SupportAdminAvatarService avatars,Clock clock) {
        this.ownership=ownership;this.authorization=authorization;this.cache=cache;this.avatars=avatars;this.clock=clock;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(Map<String,List<String>> raw) { return execute(raw,null); }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(Map<String,List<String>> raw,long agentId) { return execute(raw,agentId); }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public SupportAttachmentService.Content avatar(Map<String,List<String>> raw,long agentId) {
        Authorization initial=authorize();
        try {
            Resolved resolved=resolve(raw,initial); Row row=SupportLeaderboard.publicSummary(resolved.publication().snapshot(),agentId,resolved.query().expectedVersion());
            reauthorize(initial);
            if(row.avatarUrl()==null) throw new BizException(404,"AVATAR_NOT_FOUND");
            String expected="/api/admin/content/support-workbench/leaderboard/"+agentId+"/avatar?assetVersion=";
            if(!row.avatarUrl().startsWith(expected) || !row.avatarUrl().substring(expected.length()).matches("[1-9][0-9]*")) throw unavailable();
            long assetVersion;try {assetVersion=Long.parseLong(row.avatarUrl().substring(expected.length()));}
            catch(NumberFormatException ex){throw unavailable();}
            AssetReference current=authorization.avatarReference(agentId);
            reauthorize(initial);
            if(current==null || current.assetId()==null)throw new BizException(404,"AVATAR_NOT_FOUND");
            if(!Objects.equals(current.version(),assetVersion))throw new BizException(409,"AVATAR_STATE_CONFLICT");
            return avatars.publicLeaderboardContent(new AvatarAuthorization(initial.actor(),agentId,assetVersion,current.assetId()));
        } catch(DataAccessException ex) {reauthorize(initial);throw unavailable();}
        catch(BizException ex) {reauthorize(initial);throw ex;}
        catch(IllegalArgumentException | IllegalStateException | NullPointerException ex) {reauthorize(initial);throw unavailable();}
    }
    /** A private constructor limits this internal capability to the authorized public service. */
    public static final class AvatarAuthorization {
        private final long actor,agent,assetVersion;
        // This nested authorization value is not a Spring configuration field.
        @SuppressWarnings("ArchitectureConfigField")
        private final String assetId;
        private AvatarAuthorization(long actor,long agent,long assetVersion,String assetId){this.actor=actor;this.agent=agent;this.assetVersion=assetVersion;this.assetId=assetId;}
        public long actor(){return actor;} public long agent(){return agent;} public long assetVersion(){return assetVersion;}
        public String assetId(){return assetId;}
    }
    private Map<String,Object> execute(Map<String,List<String>> raw,Long agent) {
        Authorization initial=authorize();
        try {
            Resolved r=resolve(raw,initial); Snapshot snapshot=r.publication().snapshot();
            reauthorize(initial);
            Map<String,Object> body=metadata(r,initial);
            if(agent!=null) body.put("row",row(SupportLeaderboard.publicSummary(snapshot,agent,r.query().expectedVersion()),r.query(),snapshot.viewVersion(),initial.actor()));
            else {
                Page p=SupportLeaderboard.page(snapshot,r.query().keyword(),r.query().pageNum(),r.query().pageSize(),r.query().expectedVersion(),initial.actor());
                Podium podium=SupportLeaderboard.podium(snapshot);
                body.putAll(map("total",p.total(),"ranked",p.ranked(),"unranked",p.unranked(),"matched",p.matched(),
                    "pageNum",p.pageNum(),"pageSize",p.pageSize(),"rows",p.rows().stream().map(x->row(x,r.query(),p.viewVersion(),initial.actor())).toList(),
                    "podium",map("total",podium.total(),"rows",podium.rows().stream().map(x->row(x,r.query(),p.viewVersion(),initial.actor())).toList()),
                    "self",map("row",p.self().row()==null?null:row(p.self().row(),r.query(),p.viewVersion(),initial.actor()),
                        "gap",p.self().gap()==null?null:p.self().gap().toPlainString(),"reason",p.self().reason().name(),"pageNum",p.self().pageNum())));
            }
            reauthorize(initial); return body;
        } catch(DataAccessException ex){reauthorize(initial);throw unavailable();}
        catch(BizException ex){reauthorize(initial);throw ex;}
        catch(IllegalArgumentException | IllegalStateException | NullPointerException ex){reauthorize(initial);throw unavailable();}
    }
    private record Resolved(Normalized query,Publication publication,List<YearMonth> months,Instant now,int interval,boolean refreshFailed) { }
    private Resolved resolve(Map<String,List<String>> raw,Authorization access) {
        SupportLeaderboardQuery query=SupportLeaderboardQuery.fromParameters(raw);
        Instant now=clock.instant();
        YearMonth proposed=ffdd.opsconsole.shared.config.BusinessCalendar.legacy().month(now);
        if(query.month()!=null)try {proposed=YearMonth.parse(query.month());}
        catch(DateTimeException ex){throw new BizException(422,"SUPPORT_LEADERBOARD_QUERY_INVALID");}
        Normalized preliminary=query.normalize(now,Set.of(proposed),SupportAnalyticsPrivateQueryService.CURRENCIES,"USDT",access.scopes());
        Context selected=context(preliminary,access,now);
        SupportLeaderboardCache.Generation generation=cache.current();
        List<YearMonth> months=generation.months(selected);
        // Only a published month is selectable. A missing current stream is warming, never an invented empty board.
        if(preliminary.rankMonth()!=null && !months.contains(preliminary.rankMonth())) {
            if(!preliminary.rankMonth().equals(ffdd.opsconsole.shared.config.BusinessCalendar.legacy().month(now)))
                throw new BizException(422,"SUPPORT_LEADERBOARD_MONTH_UNAVAILABLE");
            throw new BizException(503,"SUPPORT_LEADERBOARD_CACHE_NOT_READY");
        }
        var entry=generation.entries().get(SupportLeaderboardPublicationService.streamKey(selected));
        if(entry==null)throw new BizException(503,"SUPPORT_LEADERBOARD_CACHE_NOT_READY");
        Publication publication=entry.publication();
        if(!DEFINITION.equals(publication.snapshot().context().definitionVersion()))throw unavailable();
        return new Resolved(preliminary,publication,months,now,SupportLeaderboardPolicy.REFRESH_MINUTES,entry.refreshFailed());
    }
    private static Context context(Normalized q,Authorization a,Instant now) {
        Set<Long> groups=q.groupId()==null?a.scopes().get(q.scope()):Set.of(q.groupId());
        return new Context(q.board(),q.rankMonth(),q.referenceMonth(),q.currency(),q.scope(),groups,DEFINITION,now);
    }
    private static boolean expired(Publication p,Instant now,int interval){return !now.isBefore(p.snapshot().context().evaluatedAt().plusSeconds(interval*60L));}
    private record Authorization(long actor,Account account,List<Role> roles,List<Grant> grants,List<Qualification> qualifications,
        List<Member> own,List<Group> managed,Map<Scope,Set<Long>> scopes) { }
    private Authorization authorize() {
        long actor=ownership.actorId();var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated())throw new BizException(401,"LOGIN_REQUIRED");
        if(!(authentication.getDetails() instanceof Map<?,?> details) || !"ADMIN".equals(details.get("subjectType")))
            throw new BizException(403,"SUPPORT_SUBJECT_REQUIRED");
        if(actor<=0 || actor>9007199254740991L)throw denied();
        Account account=authorization.account(actor);if(account==null)throw denied();
        List<Role> roles=checked(authorization.roles(actor)); List<Grant> grants=checked(authorization.grants(actor)).stream()
            .filter(g->SupportOwnershipService.hasAuthority(g.code())).toList();
        if(grants.isEmpty())throw denied();
        boolean superAdmin=roles.stream().anyMatch(r->Set.of("SUPER","SUPERADMIN","SUPER_ADMIN").contains(r.code()));
        boolean support=roles.stream().anyMatch(r->"SUPPORT".equals(r.code()));
        List<Qualification> qualifications=checked(authorization.qualifications(actor));
        boolean service=support && qualifications.stream().filter(q->"SERVICE".equals(q.kind())).count()==1;
        boolean supervisor=support && qualifications.stream().filter(q->"SUPERVISOR".equals(q.kind())).count()==1;
        if(!superAdmin && !service && !supervisor)throw denied();
        List<Member> own=service?checked(authorization.ownGroups(actor)):List.of();if(own.size()>1)throw denied();
        List<Group> managed=superAdmin || supervisor?checked(authorization.managedGroups(actor,superAdmin)):List.of();
        Map<Scope,Set<Long>> scopes=new EnumMap<>(Scope.class);scopes.put(Scope.all,Set.of());
        if(own.size()==1){if(own.get(0).groupId()==null)throw denied();scopes.put(Scope.ownGroup,Set.of(own.get(0).groupId()));}
        if(!managed.isEmpty())scopes.put(Scope.managedGroups,Set.copyOf(managed.stream().map(Group::id).toList()));
        return new Authorization(actor,account,roles,grants,qualifications,own,managed,Map.copyOf(scopes));
    }
    private void reauthorize(Authorization initial){Authorization current=authorize();if(!initial.equals(current))throw new BizException(409,"SUPPORT_LEADERBOARD_SCOPE_CHANGED");}
    private static <T> List<T> checked(List<T> values){if(values==null || values.stream().anyMatch(Objects::isNull))throw unavailable();return List.copyOf(values);}
    private Map<String,Object> metadata(Resolved r,Authorization a) {
        Snapshot s=r.publication().snapshot();Context c=s.context();
        List<Map<String,Object>> options=new ArrayList<>();options.add(map("scope","all","groups",List.of()));
        if(a.scopes().containsKey(Scope.ownGroup))options.add(map("scope","ownGroup","groups",a.own().stream().map(g->map("id",g.groupId().toString(),"name",g.name())).toList()));
        if(a.scopes().containsKey(Scope.managedGroups))options.add(map("scope","managedGroups","groups",a.managed().stream().map(g->map("id",g.id().toString(),"name",g.name())).toList()));
        return map("viewVersion",s.viewVersion(),"queryVersion",s.viewVersion(),"sourceVersion",s.sourceVersion(),"definitionVersion",c.definitionVersion(),
            "board",c.board().name(),"rankMonth",c.rankMonth()==null?null:c.rankMonth().toString(),"referenceMonth",c.referenceMonth().toString(),"currency",ffdd.opsconsole.common.domain.LegacyUvtProof.currency(c.currency()),
            "scope",c.scope().name(),"businessZone",SupportLeaderboard.BUSINESS_ZONE.getId(),"asOf",c.evaluatedAt().toString(),
            "publishedAt",r.publication().publishedAt().toString(),"state",s.state().name(),"candidateCoverage",s.candidateCoverage().name(),
            "stale",expired(r.publication(),r.now(),r.interval()),"refreshFailed",r.refreshFailed(),"selectableMonths",r.months().stream().map(Object::toString).toList(),
            "currencies",SupportAnalyticsPrivateQueryService.CURRENCIES.stream().sorted().toList(),"scopeOptions",options);
    }
    private Map<String,Object> row(Row r,Normalized q,String version,long actor) {
        Movement m=r.movement();
        return map("agentId",Long.toString(r.agentId()),"name",r.name(),"avatarUrl",r.avatarUrl()==null?null:avatarUrl(r.agentId(),q,version),
            "groupName",r.groupName(),"qualification",r.qualification().name(),"rank",r.rank(),"isTied",r.isTied(),"rankMetricCoverage",r.rankMetricCoverage().name(),
            "firstPayment",count(r.firstPayment()),"customers",count(r.customers()),"amount",map("value",r.amount().value()==null?null:r.amount().value().toPlainString(),
                "currency",ffdd.opsconsole.common.domain.LegacyUvtProof.currency(r.amount().currency()),"month",r.amount().month().toString(),"kind",r.amount().kind().name(),"coverage",r.amount().coverage().name(),"reason",r.amount().reason().name()),
            "movement",map("kind",m.kind().name(),"places",m.places(),"previousRank",m.previousRank(),"baselineAt",m.baselineAt()==null?null:m.baselineAt().toString(),
                "baselineVersion",m.baselineVersion(),"reason",m.reason().name()),"canViewCustomers",ownership.canReadAgent(actor,r.agentId()));
    }
    private static Map<String,Object> count(Count c){return map("value",c.value(),"coverage",c.coverage().name(),"reason",c.reason().name());}
    private static String avatarUrl(long agent,Normalized q,String version) {
        var params=map("board",q.board().name(),"currency",q.currency(),"scope",q.scope().name(),"expectedVersion",version);
        if(q.rankMonth()!=null)params.put("month",q.rankMonth().toString());if(q.groupId()!=null)params.put("groupId",q.groupId().toString());
        return "/api/admin/content/support-workbench/leaderboard/"+agent+"/avatar?"+String.join("&",params.entrySet().stream()
            .map(e->e.getKey()+"="+URLEncoder.encode(e.getValue().toString(),StandardCharsets.UTF_8)).toList());
    }
    private static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static BizException unavailable(){return new BizException(503,"SUPPORT_LEADERBOARD_SOURCE_FAILED");}
    private static BizException denied(){return new BizException(403,"SUPPORT_SCOPE_FORBIDDEN");}
}
