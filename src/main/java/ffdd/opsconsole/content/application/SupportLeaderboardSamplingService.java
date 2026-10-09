package ffdd.opsconsole.content.application;

import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.content.mapper.SupportLeaderboardMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardPublicationMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardSamplingMapper;
import ffdd.opsconsole.content.mapper.SupportLeaderboardSamplingMapper.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** System-owned public aggregates only. No Authentication, private scope, or business fact writer. */
@Service
public class SupportLeaderboardSamplingService {
    static final int STREAM_BATCH_LIMIT=64;
    private static final Logger log=LoggerFactory.getLogger(SupportLeaderboardSamplingService.class);
    private final SupportLeaderboardSamplingMapper catalogue;
    private final SupportLeaderboardMapper clock;
    private final SupportLeaderboardSourceService source;
    private final SupportLeaderboardPublicationService publications;
    private final SupportLeaderboardPublicationMapper publicationMapper;
    private final PlatformConfigFacade config;
    private final ProductionSupportPathGuard production;
    private final TransactionTemplate reads;
    private final AtomicReference<String> afterKey=new AtomicReference<>("");

    public SupportLeaderboardSamplingService(SupportLeaderboardSamplingMapper catalogue,SupportLeaderboardMapper clock,
        SupportLeaderboardSourceService source,SupportLeaderboardPublicationService publications,
        SupportLeaderboardPublicationMapper publicationMapper,PlatformConfigFacade config,
        ProductionSupportPathGuard production,PlatformTransactionManager transactions) {
        this.catalogue=catalogue;this.clock=clock;this.source=source;this.publications=publications;
        this.publicationMapper=publicationMapper;this.config=config;this.production=production;
        reads=new TransactionTemplate(transactions);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setReadOnly(true);
    }

    public record Result(String status,int planned,int scanned,int due,int committed,int superseded,int failed,int deferred,int retired) { }
    private record Plan(Instant now,int interval,NavigableMap<String,Context> contexts) { }
    private record Prepared(List<Snapshot> snapshots,int retired) { }

    /** A bounded cursor advances on failures too, so one bad stream cannot starve the rest. */
    public synchronized Result sample() {
        if(!production.productionSupportAutomationAllowed()) return empty("PROFILE_BLOCKED");
        final Plan plan;
        try {plan=reads.execute(status->{int interval=SupportLeaderboardPolicy.refreshMinutes(config);
            Instant now=now();return new Plan(now,interval,contexts(now));});}
        catch(RuntimeException failure){warn("PLAN_FAILED",null,failure);return empty("PLAN_FAILED");}
        Objects.requireNonNull(plan);
        List<String> window=window(plan.contexts(),afterKey.get(),STREAM_BATCH_LIMIT);
        if(!window.isEmpty())afterKey.set(window.get(window.size()-1));
        int deferred=plan.contexts().size()-window.size(),failed=0;
        List<Context> due=new ArrayList<>();
        for(String key:window) {
            Context context=plan.contexts().get(key);
            try {
                // Decode before trusting freshness: corrupt latest is never an absent stream or a safe hint.
                Boolean expired=reads.execute(status->{
                    if(publicationMapper.latest(key)==null)return true;
                    Publication previous=publications.latest(context,null);
                    return !plan.now().isBefore(previous.snapshot().context().evaluatedAt().plusSeconds(plan.interval()*60L));
                });
                if(Boolean.TRUE.equals(expired))due.add(context);
            }catch(RuntimeException failure){failed++;warn("LATEST_FAILED",key,failure);}
        }
        if(due.isEmpty())return new Result("IDLE",plan.contexts().size(),window.size(),0,0,0,failed,deferred,0);
        final Prepared prepared;
        try {
            prepared=reads.execute(status->{
                var captured=source.captureForSampling();
                Instant cutoff=captured.cutoff().toInstant(ZoneOffset.UTC);
                NavigableMap<String,Context> current=contexts(cutoff);
                List<Snapshot> snapshots=new ArrayList<>();int retired=0;
                for(Context hint:due) {
                    Context context=current.get(SupportLeaderboardPublicationService.streamKey(hint));
                    if(context==null){retired++;continue;}
                    var read=source.projectCaptured(captured,context);
                    snapshots.add(SupportLeaderboard.calculate(read.context(),read.sourceVersion(),read.candidateCoverage(),read.candidates()));
                }
                return new Prepared(List.copyOf(snapshots),retired);
            });
        }catch(RuntimeException failure){warn("SOURCE_BATCH_FAILED",null,failure);
            return new Result("SOURCE_FAILED",plan.contexts().size(),window.size(),due.size(),0,0,failed+due.size(),deferred,0);}
        Objects.requireNonNull(prepared);
        int committed=0,superseded=0;
        for(Snapshot snapshot:prepared.snapshots()) {
            String key=SupportLeaderboardPublicationService.streamKey(snapshot.context());
            try {
                List<Publication> baseline=reads.execute(status->publications.previousBusinessDay(snapshot.context()));
                Snapshot enriched=SupportLeaderboard.withMovement(snapshot,Objects.requireNonNull(baseline));
                publications.publish(enriched); // This injected bean returns after its own REQUIRES_NEW commit.
                committed++;
            }catch(BizException failure){
                if(failure.getCode()==409 && "SUPPORT_LEADERBOARD_LATE_EVALUATION".equals(failure.getMessage()))superseded++;
                else {failed++;warn("PUBLICATION_FAILED",key,failure);}
            }catch(RuntimeException failure){failed++;warn("PUBLICATION_FAILED",key,failure);}
        }
        return new Result("SAMPLED",plan.contexts().size(),window.size(),due.size(),committed,superseded,failed,deferred,prepared.retired());
    }

    private Instant now(){LocalDateTime value=clock.nowUtc();if(value==null)throw SupportLeaderboardPolicy.unavailable();return value.toInstant(ZoneOffset.UTC);}
    private NavigableMap<String,Context> contexts(Instant at){LocalDateTime cutoff=LocalDateTime.ofInstant(at,ZoneOffset.UTC);
        return catalogue(at,checked(catalogue.viewers(cutoff)),checked(catalogue.groups(cutoff)),checked(catalogue.members(cutoff)));}
    private static <T> List<T> checked(List<T> values){if(values==null || values.stream().anyMatch(Objects::isNull))throw SupportLeaderboardPolicy.unavailable();return values;}

    static NavigableMap<String,Context> catalogue(Instant at,List<Viewer> viewers,List<SamplingGroup> groups,List<SamplingMember> members) {
        Set<Long> groupIds=new TreeSet<>();Map<Long,Set<Long>> managed=new HashMap<>();
        for(SamplingGroup g:groups){safeId(g.id());safeId(g.supervisorId());if(!groupIds.add(g.id()))throw SupportLeaderboardPolicy.unavailable();
            managed.computeIfAbsent(g.supervisorId(),id->new TreeSet<>()).add(g.id());}
        Map<Long,Long> own=new HashMap<>();
        for(SamplingMember m:members){safeId(m.adminId());safeId(m.groupId());
            if(own.putIfAbsent(m.adminId(),m.groupId())!=null)throw SupportLeaderboardPolicy.unavailable();}
        NavigableMap<String,Context> result=new TreeMap<>();add(result,at,Scope.all,Set.of());Set<Long> seen=new HashSet<>();
        for(Viewer v:viewers){safeId(v.adminId());if(!seen.add(v.adminId()) || !v.superAdmin() && !v.service() && !v.supervisor())throw SupportLeaderboardPolicy.unavailable();
            Long member=own.get(v.adminId());if(v.service() && member!=null)add(result,at,Scope.ownGroup,Set.of(member));
            Set<Long> approved=v.superAdmin()?groupIds:v.supervisor()?managed.getOrDefault(v.adminId(),Set.of()):Set.of();
            if(!approved.isEmpty()){add(result,at,Scope.managedGroups,approved);
                for(long id:approved)add(result,at,Scope.managedGroups,Set.of(id));}
        }
        return Collections.unmodifiableNavigableMap(result);
    }
    private static void safeId(long id){if(id<=0 || id>9007199254740991L)throw SupportLeaderboardPolicy.unavailable();}
    private static void add(Map<String,Context> output,Instant at,Scope scope,Set<Long> groups){YearMonth month=YearMonth.from(at.atZone(SupportLeaderboard.BUSINESS_ZONE));
        for(Board board:Board.values())for(String currency:SupportAnalyticsPrivateQueryService.CURRENCIES){
            Context context=new Context(board,board==Board.customers?null:month,month,currency,scope,Set.copyOf(groups),SupportLeaderboardPolicy.DEFINITION,at);
            output.put(SupportLeaderboardPublicationService.streamKey(context),context);}
    }
    static List<String> window(NavigableMap<String,Context> contexts,String after,int limit){
        if(limit<1 || limit>STREAM_BATCH_LIMIT)throw new IllegalArgumentException("Invalid sampling budget");
        List<String> keys=new ArrayList<>(Math.min(limit,contexts.size()));
        for(String key:contexts.tailMap(after,false).keySet()){keys.add(key);if(keys.size()==limit)return List.copyOf(keys);}
        for(String key:contexts.headMap(after,true).keySet()){keys.add(key);if(keys.size()==limit)break;}
        return List.copyOf(keys);
    }
    private static Result empty(String status){return new Result(status,0,0,0,0,0,0,0,0);}
    private static void warn(String category,String stream,RuntimeException failure){
        log.warn("Leaderboard sampling {} stream={} failureType={}",category,stream,failure.getClass().getSimpleName());
    }
}
