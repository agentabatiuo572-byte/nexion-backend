package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.dto.TaskSimulationSettings;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.capacity.E3CapacityCurve;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;

/** Immutable published authorization. Mutable pool labels never participate in runtime resolution. */
@Service
@RequiredArgsConstructor
public class SimulatedTaskPolicyService {
    public static final List<String> TASK_CLASSES=List.of("EM","SP","IG","LL","FT","VG");
    private final SimulatedTaskPolicyMapper mapper;
    private final ObjectProvider<DeviceOpsRepository> devices;
    private final ObjectMapper json;
    private final Clock clock;
    private final SimulatedTaskPolicyHistory historyLoader;
    private final SimulatedTaskWakeService wakes;
    private final java.util.concurrent.atomic.AtomicReference<SimulatedTaskPolicyHistory.History> history=new java.util.concurrent.atomic.AtomicReference<>();
    public record TaskTypeSnapshot(String taskTypeId,long typeVersion,String name,String taskClass,
            int durationSeconds,BigDecimal baseReward,String asset,boolean enabled,String poolCode) { }
    public record PolicyVersion(String productKey,long version,LocalDateTime effectiveAt,
            List<TaskTypeSnapshot> taskTypes,long e3Version,Map<String,String> e3Config,
            BigDecimal monthlyTargetUsdt,BigDecimal monthlyTargetBandPct,List<String> allowedPoolCodes) { }
    public record TypeView(int durationMinutes,BigDecimal baseReward,String asset,boolean enabled,
            long version,LocalDateTime effectiveAt) { }
    public record ProductView(List<String> allowedPoolCodes,long productTaskPolicyVersion,
            LocalDateTime productTaskPolicyEffectiveAt) { }

    @Transactional(readOnly=true)
    public PolicyVersion policyAt(String productKey,LocalDateTime at) {
        var snapshot=historyAtCallerEpoch();
        return snapshot==null?policyAtDatabase(productKey,at):policyAt(snapshot,productKey,at);
    }
    private SimulatedTaskPolicyHistory.History historyAtCallerEpoch() {
        long epoch=mapper.epoch();
        var snapshot=history.get();
        if(snapshot==null || epoch>snapshot.epoch()) {
            var loaded=historyLoader.load();
            snapshot=history.accumulateAndGet(loaded,(old,next)->old==null||next.epoch()>old.epoch()?next:old);
        }
        // Older RR views and a caller's uncommitted publication resolve in their own DB view.
        return snapshot.epoch()==epoch && snapshot.cacheable()?snapshot:null;
    }
    private boolean authorizes(ProductVersion p,TypeVersion t,List<String> scope) {
        if(p.allowedPoolCodesJson()!=null) {
            // A DEVICE weight can never become a per-task price, even for a malformed published policy.
            if(t.poolCode()!=null && !"PHONE".equals(t.poolCode()) && p.monthlyTargetUsdt()==null)return false;
            return t.poolCode()!=null && scope.contains(t.poolCode());
        }
        if(t.poolCode()!=null && !"PHONE".equals(t.poolCode()))return false;
        return p.allowedTaskClassesJson()!=null?scope.contains(t.taskClass())
                :scope.contains(t.taskTypeId());
    }
    private List<String> scope(ProductVersion p) {
        return strings(p.allowedPoolCodesJson()!=null?p.allowedPoolCodesJson():p.allowedTaskClassesJson()!=null?p.allowedTaskClassesJson():p.allowedTaskTypeIdsJson());
    }
    private PolicyVersion policyAt(SimulatedTaskPolicyHistory.History h,String key,LocalDateTime at) {
        ProductVersion product=null;
        for(var p:h.products())if(p.productKey().equals(key)&&!p.effectiveAt().isAfter(at))product=p;
        if(product==null)throw new BizException(422,"SIMULATED_PRODUCT_POLICY_HISTORY_MISSING");
        E3Version e3=null;
        for(var e:h.e3())if(!e.effectiveAt().isAfter(at))e3=e;
        if(e3==null)throw new BizException(422,"SIMULATED_E3_POLICY_HISTORY_MISSING");
        var current=new LinkedHashMap<String,TypeVersion>();
        for(var t:h.types())if(!t.effectiveAt().isAfter(at))current.put(t.taskTypeId(),t);
        var versions=new ArrayList<TypeVersion>();
        var scope=scope(product);
        for(var t:current.values())if(authorizes(product,t,scope))versions.add(t);
        return resolve(product,e3,versions);
    }
    private PolicyVersion resolve(ProductVersion p,E3Version e,List<TypeVersion> versions) {
        var effective=p.effectiveAt().isAfter(e.effectiveAt())?p.effectiveAt():e.effectiveAt();
        var types=new ArrayList<TaskTypeSnapshot>();
        for(var t:versions) {
            if(t.effectiveAt().isAfter(effective))effective=t.effectiveAt();
            types.add(new TaskTypeSnapshot(t.taskTypeId(),t.version(),t.name(),t.taskClass(),t.durationSeconds(),t.baseReward(),t.asset(),t.enabled(),t.poolCode()));
        }
        return new PolicyVersion(p.productKey(),p.version(),effective,List.copyOf(types),e.version(),
                Map.copyOf(decode(e.configJson(),new TypeReference<Map<String,String>>(){})),p.monthlyTargetUsdt(),p.monthlyTargetBandPct(),strings(p.allowedPoolCodesJson()));
    }
    private List<TypeVersion> versionsAt(ProductVersion p,LocalDateTime at) {
        List<TypeVersion> types;
        if(p.allowedPoolCodesJson()!=null)types=typesInPools(strings(p.allowedPoolCodesJson()),at);
        else if(p.allowedTaskClassesJson()!=null) {
            var classes=strings(p.allowedTaskClassesJson());
            types=classes.isEmpty()?List.of():mapper.typesInClassesAt(classes,at);
        } else {
            var ids=strings(p.allowedTaskTypeIdsJson());
            types=ids.isEmpty()?List.of():mapper.typesAt(ids,at);
        }
        var scope=scope(p);
        return types.stream().filter(t->authorizes(p,t,scope)).toList();
    }
    PolicyVersion policyAtDatabase(String key,LocalDateTime at) {
        var p=mapper.productAt(key,at);
        if(p==null)throw new BizException(422,"SIMULATED_PRODUCT_POLICY_HISTORY_MISSING");
        var e=mapper.e3At(at);
        if(e==null)throw new BizException(422,"SIMULATED_E3_POLICY_HISTORY_MISSING");
        return resolve(p,e,versionsAt(p,at));
    }
    @Transactional(readOnly=true)
    public PolicyVersion nextPolicyAfter(String key,LocalDateTime after,LocalDateTime notAfter) {
        var h=historyAtCallerEpoch();
        if(h==null)return nextPolicyAfterDatabase(key,after,notAfter);
        ProductVersion p=null; LocalDateTime next=null;
        for(var v:h.products())if(v.productKey().equals(key)) {
            if(!v.effectiveAt().isAfter(after))p=v;
            else if(!v.effectiveAt().isAfter(notAfter)&&(next==null||v.effectiveAt().isBefore(next)))next=v.effectiveAt();
        }
        for(var e:h.e3())if(e.effectiveAt().isAfter(after)&&!e.effectiveAt().isAfter(notAfter)
                &&(next==null||e.effectiveAt().isBefore(next)))next=e.effectiveAt();
        if(p!=null) {
            var scope=scope(p);
            for(var t:h.types())if(authorizes(p,t,scope)&&t.effectiveAt().isAfter(after)&&!t.effectiveAt().isAfter(notAfter)
                    &&(next==null||t.effectiveAt().isBefore(next)))next=t.effectiveAt();
        }
        return next==null?null:policyAt(h,key,next);
    }
    PolicyVersion nextPolicyAfterDatabase(String key,LocalDateTime after,LocalDateTime notAfter) {
        var p=mapper.productAt(key,after);
        LocalDateTime next=mapper.nextProduct(key,after,notAfter),e3=mapper.nextE3(after,notAfter);
        if(e3!=null&&(next==null||e3.isBefore(next)))next=e3;
        if(p!=null) {
            List<String> scope=strings(p.allowedPoolCodesJson()!=null?p.allowedPoolCodesJson():p.allowedTaskClassesJson()!=null?p.allowedTaskClassesJson():p.allowedTaskTypeIdsJson());
            if(!scope.isEmpty()) {
                var t=p.allowedPoolCodesJson()!=null?mapper.nextTypeInPools(scope,after,notAfter)
                        :p.allowedTaskClassesJson()!=null?mapper.nextTypeInClasses(scope,after,notAfter):mapper.nextType(scope,after,notAfter);
                if(t!=null&&(next==null||t.isBefore(next)))next=t;
            }
        }
        return next==null?null:policyAtDatabase(key,next);
    }

    /** One logical now, one caller epoch query, and one read-only snapshot for every catalog SKU. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,List<Integer>> taskLevels(List<String> keys) {
        var at=LocalDateTime.now(clock);var h=historyAtCallerEpoch();
        var rows=h!=null?h.products():keys.isEmpty()?List.<ProductVersion>of():mapper.productsAt(keys,at);
        var requested=new HashSet<>(keys);var latest=new HashMap<String,ProductVersion>();
        for(var p:rows)if(requested.contains(p.productKey())&&!p.effectiveAt().isAfter(at))latest.put(p.productKey(),p);
        var result=new LinkedHashMap<String,List<Integer>>();
        for(var key:keys) {
            var p=latest.get(key);var pools=p==null?List.<String>of():strings(p.allowedPoolCodesJson());
            result.put(key,java.util.stream.IntStream.rangeClosed(1,4).filter(level->pools.contains("L"+level)).boxed().toList());
        }
        return result;
    }

    public TypeView typeView(String id) { var t=mapper.typeAt(id,LocalDateTime.now(clock));return t==null?null:toView(t); }
    public Map<String,TypeView> typeViews(List<String> ids) {
        var result=new HashMap<String,TypeView>();
        if(!ids.isEmpty())for(var t:mapper.typesAt(ids,LocalDateTime.now(clock)))result.put(t.taskTypeId(),toView(t));
        return result;
    }
    private TypeView toView(TypeVersion t) {return new TypeView(t.durationSeconds()/60,t.baseReward(),t.asset(),t.enabled(),t.version(),t.effectiveAt());}
    public List<TaskTypeSnapshot> currentTypes() {
        return typesInPools(List.of("PHONE"),LocalDateTime.now(clock)).stream().map(t->new TaskTypeSnapshot(t.taskTypeId(),t.version(),t.name(),t.taskClass(),t.durationSeconds(),t.baseReward(),t.asset(),t.enabled(),t.poolCode())).toList();
    }
    public ProductView productView(String key) {
        var p=mapper.productAt(key,LocalDateTime.now(clock));
        String pools="phone".equals(key)?null:mapper.draftPools(key);
        if(pools==null&&p!=null)pools=p.allowedPoolCodesJson();
        return new ProductView(strings(pools),p==null?0:p.version(),p==null?null:p.effectiveAt());
    }
    public List<String> writePools(String key,List<String> proposed,List<String> previous) {
        var pools=proposed==null?previous:proposed;
        if("phone".equals(key)) {
            if(proposed==null)throw new BizException(422,"TASK_POOL_FORMAT_RETIRED");
            if(!pools.equals(List.of("PHONE")))throw new BizException(422,"SKU_POOL_KIND_INVALID");
        } else if(pools.stream().anyMatch(code->code==null||!List.of("L1","L2","L3","L4").contains(code)))
            throw new BizException(422,"SKU_POOL_KIND_INVALID");
        return pools.stream().distinct().sorted().toList();
    }
    public void validateProductScope(String key,List<String> pools,boolean usePublishedScope,BigDecimal target,BigDecimal band,boolean publishing,boolean clearing) {
        mapper.lockE3Configuration();
        var at=LocalDateTime.now(clock);
        writePools(key,pools,List.of());
        var published=usePublishedScope?mapper.productAt(key,at):null;
        var effective=published==null?pools:strings(published.allowedPoolCodesJson());
        if(target==null && (!pools.isEmpty() || !effective.isEmpty()) && !"phone".equals(key))
            throw new BizException(422,clearing?"MONTHLY_TARGET_CLEAR_FORBIDDEN_WITH_POOLS":"SKU_POOL_TARGET_REQUIRED");
        if(publishing && pools.isEmpty())throw new BizException(422,"SKU_POOL_REQUIRED");
        if(target!=null) {
            validatePool(key,typesInPools(effective,at));
            validateTargetReward(key,target,band,devices.getObject().e3Config());
        }
    }
    public void validateType(TaskSimulationSettings s) {
        if(s==null||s.durationMinutes()==null||s.durationMinutes()<1||s.durationMinutes()>1440)throw new BizException(422,"SIMULATED_DURATION_MINUTES_INVALID");
        if(s.baseReward()==null||s.baseReward().signum()<0||s.baseReward().compareTo(DeviceMonthlyTargetPacing.MAX_REWARD)>0
                ||s.baseReward().stripTrailingZeros().scale()>6)throw new BizException(422,"SIMULATED_FIXED_REWARD_INVALID");
        if(!Set.of("USDT","UVT").contains(s.asset()==null?"":s.asset()))throw new BizException(422,"SIMULATED_ASSET_INVALID");
        if(s.enabled()==null||s.expectedVersion()==null||s.expectedVersion()<0)throw new BizException(422,"SIMULATED_POLICY_VERSION_REQUIRED");
    }
    public void requirePhoneType(String id) {
        var t=mapper.typeAt(id,LocalDateTime.now(clock));
        if(t==null||!"PHONE".equals(t.poolCode()))throw new BizException(422,"TASK_POOL_KIND_FORBIDDEN");
    }
    public void publishType(String id,String name,String taskClass,TaskSimulationSettings s,LocalDateTime at) {
        mapper.lockE3Configuration();
        var before=mapper.lockType(id);
        if(before!=null&&!"PHONE".equals(before.poolCode()))throw new BizException(422,"TASK_POOL_KIND_FORBIDDEN");
        if(s.expectedVersion()!=(before==null?0:before.version()))throw new BizException(409,"SIMULATED_TASK_POLICY_VERSION_CONFLICT");
        initializeE3(at);
        mapper.insertType(new TypeVersion(id,before==null?1:before.version()+1,at,name,taskClass,s.durationMinutes()*60,s.baseReward().setScale(6,RoundingMode.UNNECESSARY),s.asset(),s.enabled(),"PHONE"));
        wakes.afterCommitProducts(List.of("phone"));
    }
    public void saveProduct(String key,List<String> pools,boolean publishing,LocalDateTime at) {saveProduct(key,pools,publishing,at,true);}
    public void saveProduct(String key,List<String> pools,boolean publishing,LocalDateTime at,boolean approvedScopeChange) {
        mapper.lockE3Configuration();
        var before=mapper.lockProduct(key);
        var target=mapper.targetConfig(key);
        var effective=publishing&&!approvedScopeChange&&before!=null?strings(before.allowedPoolCodesJson()):pools;
        validateProductScope(key,effective,false,target==null?null:target.monthlyTargetUsdt(),target==null?null:target.monthlyTargetBandPct(),false,false);
        if(publishing)appendProduct(key,effective,before,at,target);
        mapper.updateDraft(key,encode(pools));
    }
    public boolean hasPublishedProduct(String key) {return mapper.productAt(key,LocalDateTime.now(clock))!=null;}
    public ProductView publishProduct(String key,List<String> pools,Long expectedVersion,LocalDateTime at) {
        mapper.lockE3Configuration();var before=mapper.lockProduct(key);
        if(expectedVersion!=null&&expectedVersion!=(before==null?0:before.version()))throw new BizException(409,"SIMULATED_PRODUCT_POLICY_VERSION_CONFLICT");
        var target=mapper.targetConfig(key);
        validateProductScope(key,pools,false,target==null?null:target.monthlyTargetUsdt(),target==null?null:target.monthlyTargetBandPct(),true,false);
        appendProduct(key,pools,before,at,target);
        return new ProductView(pools,before==null?1:before.version()+1,at);
    }
    private void appendProduct(String key,List<String> pools,ProductVersion before,LocalDateTime at,TargetConfig target) {
        initializeE3(at);
        mapper.insertProduct(new ProductVersion(key,before==null?1:before.version()+1,at,"[]",null,encode(pools),target==null?null:target.monthlyTargetUsdt(),target==null?null:target.monthlyTargetBandPct()));
        wakes.afterCommitProducts(List.of(key));
    }
    private List<TypeVersion> typesInPools(List<String> pools,LocalDateTime at) {return pools.isEmpty()?List.of():mapper.typesInPoolsAt(pools,at);}
    private static void validateTargetReward(String key,BigDecimal target,BigDecimal band,Map<String,String> config) {
        String participation=E3DeviceCapacityPolicy.classify(key,null);
        BigDecimal capacity=Boolean.parseBoolean(config.get(participation))?E3CapacityCurve.maxCapacityPct(config,config):BigDecimal.valueOf(100);
        if(DeviceMonthlyTargetPacing.rewardBound(target,band,capacity).compareTo(DeviceMonthlyTargetPacing.MAX_REWARD)>0)throw new MonthlyTargetRewardOutOfRangeException(List.of(key));
    }
    public void validateTargetRewards(Map<String,String> before,Map<String,String> candidate) {
        boolean curveChanged=List.of("stageEarlyEnd","stageMidEnd","capacityBand1DeltaPct","capacityBand2DeltaPct","capacityBand3DeltaPct","capacityFloorPct").stream().anyMatch(k->!Objects.equals(before.get(k),candidate.get(k)));
        BigDecimal maximum=E3CapacityCurve.maxCapacityPct(before,candidate);var affected=new LinkedHashSet<String>();
        for(var t:mapper.targetProducts()) {
            String participation=E3DeviceCapacityPolicy.classify(t.productKey(),null);
            boolean was=Boolean.parseBoolean(before.get(participation)),now=Boolean.parseBoolean(candidate.get(participation));
            if(!(curveChanged&&(was||now)||was!=now))continue;
            if(DeviceMonthlyTargetPacing.rewardBound(t.monthlyTargetUsdt(),t.monthlyTargetBandPct(),now?maximum:BigDecimal.valueOf(100)).compareTo(DeviceMonthlyTargetPacing.MAX_REWARD)>0)affected.add(t.productKey());
        }
        if(!affected.isEmpty())throw new MonthlyTargetRewardOutOfRangeException(List.copyOf(affected));
    }
    public void validateDeviceBatch(List<TypeVersion> finalPool) {validatePool("DEVICE",finalPool);}
    private static void validatePool(String key,List<TypeVersion> types) {
        if(types.stream().anyMatch(t->t.enabled()&&!DeviceMonthlyTargetPacing.eligible(t.asset(),t.baseReward(),t.durationSeconds())))throw new MonthlyTargetPoolException(List.of(key));
    }
    List<String> strings(String value) {return value==null?List.of():decode(value,new TypeReference<List<String>>(){});}
    public void lockE3Configuration() {mapper.lockE3Configuration();}
    public void recordE3(LocalDateTime at) {var p=mapper.lockE3Version();if(p!=null)mapper.insertE3(new E3Version(p.version()+1,at,encode(devices.getObject().e3Config())));}
    private void initializeE3(LocalDateTime at) {if(mapper.lockE3Version()==null)mapper.insertE3(new E3Version(1,at,encode(devices.getObject().e3Config())));}
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("SIMULATED_POLICY_JSON_INVALID",e);}
    }
    private <T>T decode(String value,TypeReference<T> type) {
        try{return json.readValue(value,type);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("SIMULATED_POLICY_HISTORY_INVALID",e);}
    }
}
