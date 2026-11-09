package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.TaskPoolMapper;
import ffdd.opsconsole.device.mapper.TaskPoolMapper.*;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper.TypeVersion;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskPoolService {
    private final TaskPoolMapper mapper;
    private final SimulatedTaskPolicyMapper versions;
    private final SimulatedTaskPolicyService policies;
    private final SimulatedTaskWakeService wakes;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final ObjectMapper json;
    private final Clock clock;
    public record RandomAdd(Integer count,List<String> taskClasses,String idempotencyKey,String reason) { }
    public record Status(List<String> taskTypeIds,Boolean enabled,String idempotencyKey,String reason) { }
    private record Snapshot(List<Pool> pools,List<TypeVersion> types,List<Reference> references) { }
    private Snapshot snapshot(LocalDateTime at) {return new Snapshot(mapper.pools(),mapper.types(at),mapper.references(at));}

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(String kind) {
        if(!Set.of("DEVICE","PHONE").contains(kind))throw new BizException(422,"TASK_POOL_KIND_INVALID");
        var data=snapshot(LocalDateTime.now(clock));
        return data.pools().stream().filter(p->p.kind().equals(kind)).map(p->poolView(p,data)).toList();
    }
    private Pool pool(String code,List<Pool> pools,boolean deviceOnly) {
        var p=pools.stream().filter(row->row.code().equals(code)).findFirst().orElseThrow(()->new BizException(404,"TASK_POOL_NOT_FOUND"));
        if(deviceOnly&&!"DEVICE".equals(p.kind()))throw new BizException(422,"TASK_POOL_KIND_FORBIDDEN");
        return p;
    }
    private Map<String,Object> poolView(Pool pool,Snapshot data) {
        var tasks=data.types().stream().filter(t->pool.code().equals(t.poolCode())).toList();
        var enabled=tasks.stream().filter(TypeVersion::enabled).toList();
        var refs=new LinkedHashMap<String,Map<String,Object>>();
        for(var r:data.references())if(policies.strings(r.poolsJson()).contains(pool.code()))refs.put(r.productKey(),Map.of("productKey",r.productKey(),"productName",r.productName(),"state",r.state()));
        var row=new LinkedHashMap<String,Object>();
        row.put("code",pool.code());row.put("kind",pool.kind());row.put("level",pool.level());row.put("name",pool.name());row.put("sortOrder",pool.sortOrder());
        row.put("taskCount",tasks.size());row.put("enabledCount",enabled.size());row.put("referencedBy",List.copyOf(refs.values()));
        BigDecimal avg=enabled.isEmpty()?null:BigDecimal.valueOf(enabled.stream().mapToLong(TypeVersion::durationSeconds).sum())
                .divide(BigDecimal.valueOf(60L*enabled.size()),6,RoundingMode.HALF_UP);
        row.put("avgDurationMinutes",avg==null?null:avg.setScale(1,RoundingMode.HALF_UP).toPlainString());
        row.put("estTasksPerDevicePerDay",avg==null?null:BigDecimal.valueOf(1440).divide(avg,1,RoundingMode.HALF_UP).toPlainString());
        return row;
    }
    @Transactional(readOnly=true)
    public PageResult<Map<String,Object>> tasks(String code,String taskClass,Boolean enabled,int pageNum,int pageSize) {
        pool(code,mapper.pools(),false);
        if(taskClass!=null&&!SimulatedTaskPolicyService.TASK_CLASSES.contains(taskClass))throw new BizException(422,"TASK_CLASS_INVALID");
        if(pageNum<1||pageSize<1||pageSize>300)throw new BizException(422,"PAGE_INVALID");
        var rows=mapper.types(LocalDateTime.now(clock)).stream().filter(t->code.equals(t.poolCode()))
                .filter(t->taskClass==null||taskClass.equals(t.taskClass())).filter(t->enabled==null||enabled==t.enabled()).toList();
        return new PageResult<>(rows.size(),pageNum,pageSize,rows.stream().skip((long)(pageNum-1)*pageSize).limit(pageSize).map(this::taskView).toList());
    }
    private Map<String,Object> taskView(TypeVersion t) {
        var row=new LinkedHashMap<String,Object>();
        row.put("taskTypeId",t.taskTypeId());row.put("name",t.name());row.put("taskClass",t.taskClass());row.put("durationMinutes",t.durationSeconds()/60);
        row.put("enabled",t.enabled());row.put("version",t.version());row.put("effectiveAt",t.effectiveAt());
        if("PHONE".equals(t.poolCode())) {row.put("baseReward",t.baseReward().toPlainString());row.put("asset",t.asset());}
        return row;
    }
    @Transactional
    public ApiResult<Map<String,Object>> randomAdd(String code,RandomAdd request) {
        reason(request==null?null:request.reason());
        if(request.count()==null||request.count()<1||request.count()>50)throw new BizException(422,"TASK_POOL_COUNT_INVALID");
        var classes=request.taskClasses()==null?SimulatedTaskPolicyService.TASK_CLASSES:request.taskClasses();
        if(classes.isEmpty()||classes.stream().anyMatch(c->c==null||!SimulatedTaskPolicyService.TASK_CLASSES.contains(c)))throw new BizException(422,"TASK_CLASS_INVALID");
        var selected=SimulatedTaskPolicyService.TASK_CLASSES.stream().filter(classes::contains).toList();
        return batch("E2_POOL_RANDOM",request.idempotencyKey(),Map.of("pool",code,"count",request.count(),"classes",selected,"reason",request.reason().trim()),()->{
            policies.lockE3Configuration();var p=mapper.lock(code);pool(code,p==null?List.of():List.of(p),true);
            var at=LocalDateTime.now(clock);var data=snapshot(at);
            var finalPool=new ArrayList<>(data.types().stream().filter(t->code.equals(t.poolCode())).toList());
            int enabled=(int)finalPool.stream().filter(TypeVersion::enabled).count();
            if(finalPool.size()+request.count()>300)throw new TaskPoolException("TASK_POOL_TOTAL_LIMIT",Map.of("limit",300,"total",finalPool.size(),"requested",request.count()));
            if(enabled+request.count()>200)throw new TaskPoolException("TASK_POOL_ENABLED_LIMIT",Map.of("limit",200,"enabled",enabled,"requested",request.count()));
            var names=new HashSet<String>();for(var t:finalPool)names.add(t.name());
            var created=new ArrayList<TypeVersion>();var random=ThreadLocalRandom.current();
            // Independent English content combinations exceed the per-pool cap even with a single class filter.
            String[] styles={"watercolor","detailed","minimal","abstract","modern","cinematic","natural","technical","creative","multilingual","compact","structured","expressive","semantic","focused","dynamic","balanced","colorful","documentary","precise","contextual","regional","adaptive","classic","digital","studio","urban","scientific","conversational","illustrated","professional","educational"};
            Map<String,String[]> topics=Map.of(
                "EM",new String[]{"document indexing","product search","semantic retrieval","article matching","topic clustering","intent matching","catalog indexing","text similarity","query expansion","knowledge search","news grouping","review matching","passage retrieval","tag extraction","entity matching","question search","vector ranking","content grouping","reference search","summary matching"},
                "SP",new String[]{"voice transcription","meeting notes","speech recognition","audio cleanup","podcast captions","dialogue transcription","speaker alignment","voice segmentation","interview notes","lecture captions","call transcription","audio translation","narration alignment","voice synthesis","speech timing","subtitle alignment","accent recognition","keyword detection","pronunciation review","sound classification"},
                "IG",new String[]{"portrait","landscape","product scene","interior concept","character sketch","botanical study","city illustration","cover artwork","fashion concept","food illustration","architectural scene","poster design","texture study","wildlife scene","travel postcard","still life","lighting study","vehicle concept","storybook scene","garden concept"},
                "LL",new String[]{"long context chat","document summary","answer generation","language translation","content drafting","dialogue response","report analysis","knowledge extraction","technical explanation","article revision","question answering","product description","topic analysis","instruction response","research summary","story outline","text classification","review analysis","writing assistance","context synthesis"},
                "FT",new String[]{"instruction tuning","dialogue adaptation","domain tuning","response alignment","text adaptation","language tuning","classification tuning","knowledge adaptation","style alignment","retrieval tuning","summary adaptation","intent tuning","document adaptation","entity tuning","ranking adaptation","translation tuning","reasoning alignment","caption adaptation","query tuning","answer alignment"},
                "VG",new String[]{"city timelapse","nature sequence","character animation","product showcase","camera transition","ocean scene","motion study","travel montage","landscape flyover","studio sequence","weather animation","wildlife scene","architectural tour","garden sequence","light transition","action sequence","food showcase","abstract motion","documentary scene","story sequence"});
            Map<String,String> bases=Map.of("EM","embedding","SP","speech processing","IG","image gen","LL","language inference","FT","fine tuning","VG","video gen");
            for(int n=0;n<request.count();n++) {
                String cls=selected.get(random.nextInt(selected.size())),taskName;
                do {taskName=bases.get(cls)+" - "+styles[random.nextInt(styles.length)]+" "+topics.get(cls)[random.nextInt(topics.get(cls).length)];}while(!names.add(taskName));
                int min=switch(cls){case "EM"->30;case "SP"->45;case "IG","LL"->60;default->90;};
                int max=switch(cls){case "EM"->60;case "SP"->90;default->120;};
                int minutes=min+5*random.nextInt((max-min)/5+1);
                var t=new TypeVersion("DT-"+UUID.randomUUID().toString().replace("-",""),1,at,taskName,cls,minutes*60,BigDecimal.valueOf(random.nextLong(100000,1100000),6),"USDT",true,code);
                created.add(t);finalPool.add(t);
            }
            policies.validateDeviceBatch(finalPool); // Once, on the final batch; no publishType loop or B1/A2.
            for(var t:created)versions.insertType(t);
            event("E2_TASK_POOL_RANDOM_ADDED",code,request.reason(),Map.of("count",created.size(),"classes",selected));
            wakes.afterCommitPools(List.of(code));
            var all=new ArrayList<>(data.types());all.addAll(created);
            return ApiResult.ok(Map.of("created",created.stream().map(this::taskView).toList(),"pool",poolView(p,new Snapshot(data.pools(),all,data.references()))));
        });
    }
    @Transactional
    public ApiResult<Map<String,Object>> status(String code,Status request) {
        reason(request==null?null:request.reason());var ids=ids(request.taskTypeIds());
        if(request.enabled()==null)throw new BizException(422,"TASK_POOL_STATUS_REQUIRED");
        return batch("E2_POOL_STATUS",request.idempotencyKey(),Map.of("pool",code,"ids",ids,"enabled",request.enabled(),"reason",request.reason().trim()),()->{
            policies.lockE3Configuration();var p=mapper.lock(code);pool(code,p==null?List.of():List.of(p),true);
            var at=LocalDateTime.now(clock);var data=snapshot(at);
            var finalPool=new ArrayList<TypeVersion>();var updates=new ArrayList<TypeVersion>();
            var existing=data.types().stream().filter(t->code.equals(t.poolCode())).toList();
            if(!existing.stream().map(TypeVersion::taskTypeId).toList().containsAll(ids))throw new BizException(422,"TASK_TYPE_NOT_IN_POOL");
            for(var t:existing) {
                if(ids.contains(t.taskTypeId())&&t.enabled()!=request.enabled()) {
                    t=new TypeVersion(t.taskTypeId(),t.version()+1,at,t.name(),t.taskClass(),t.durationSeconds(),t.baseReward(),t.asset(),request.enabled(),t.poolCode());updates.add(t);
                }
                finalPool.add(t);
            }
            long enabled=finalPool.stream().filter(TypeVersion::enabled).count();
            if(enabled>200)throw new TaskPoolException("TASK_POOL_ENABLED_LIMIT",Map.of("limit",200,"enabled",existing.stream().filter(TypeVersion::enabled).count(),"requested",updates.size()));
            policies.validateDeviceBatch(finalPool);
            for(var t:updates)versions.insertType(t);
            event("E2_TASK_POOL_STATUS_CHANGED",code,request.reason(),Map.of("count",updates.size(),"enabled",request.enabled()));
            if(request.enabled())wakes.afterCommitPools(List.of(code));
            var all=new ArrayList<>(data.types().stream().filter(t->!code.equals(t.poolCode())).toList());all.addAll(finalPool);
            return ApiResult.ok(Map.of("updated",updates.size(),"pool",poolView(p,new Snapshot(data.pools(),all,data.references()))));
        });
    }
    @Transactional(readOnly=true)
    public Map<String,Object> disableImpact(String code,List<String> requested) {
        var ids=ids(requested);var data=snapshot(LocalDateTime.now(clock));pool(code,data.pools(),true);
        if(!data.types().stream().filter(t->code.equals(t.poolCode())).map(TypeVersion::taskTypeId).toList().containsAll(ids))throw new BizException(422,"TASK_TYPE_NOT_IN_POOL");
        var products=new LinkedHashMap<String,Map<String,String>>();
        for(var r:data.references())if(!"DRAFT".equals(r.state())) {
            var scope=policies.strings(r.poolsJson());
            if(!scope.contains(code))continue;
            var candidates=data.types().stream().filter(t->t.enabled()&&scope.contains(t.poolCode())).toList();
            if(!candidates.isEmpty() && candidates.stream().allMatch(t->ids.contains(t.taskTypeId())))products.put(r.productKey(),Map.of("productKey",r.productKey(),"productName",r.productName()));
        }
        long devices=mapper.deviceCounts().stream().filter(c->products.containsKey(c.productKey())).mapToLong(DeviceCount::count).sum();
        return Map.of("productsLosingAllTasks",List.copyOf(products.values()),"devicesAffected",devices);
    }
    public List<String> datacenterLabels() {return mapper.datacenterLabels();}
    private List<String> ids(List<String> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>300||ids.stream().anyMatch(id->id==null||id.isBlank()))throw new BizException(422,"TASK_TYPE_IDS_REQUIRED");
        return ids.stream().distinct().sorted().toList();
    }
    private void reason(String reason) {if(reason==null||reason.trim().length()<8||reason.trim().length()>200)throw new BizException(422,"REASON_LENGTH_INVALID");}
    private void event(String event,String code,String reason,Map<String,Object> detail) {
        var payload=new LinkedHashMap<String,Object>(detail);payload.put("poolCode",code);payload.put("reason",reason.trim());
        String actor=AdminActorResolver.resolve(null);payload.put("operator",actor);
        audit.recordRequired(AuditLogWriteRequest.builder().action(event).resourceType("TASK_POOL").resourceId(code).bizNo(code)
                .actorType("ADMIN").actorUsername(actor).result("SUCCESS").riskLevel("HIGH").detail(payload).build());
        outbox.publish("TASK_POOL",code,event,payload);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private ApiResult<Map<String,Object>> batch(String scope,String key,Object body,Supplier<ApiResult<Map<String,Object>>> action) {
        final String hash;
        try {hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(body)));}
        catch(java.security.NoSuchAlgorithmException|JsonProcessingException e){throw new IllegalStateException(e);}
        try {return (ApiResult)idempotency.executeRetained(scope,key,hash,ApiResult.class,(Supplier)action);}
        catch(BizException e) {
            if("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH".equals(e.getMessage()))throw new BizException(409,"TASK_POOL_IDEMPOTENCY_CONFLICT");
            throw e;
        }
    }
}
