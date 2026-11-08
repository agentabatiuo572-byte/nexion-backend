package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.dto.TaskSimulationSettings;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskPolicyMapper.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

/** Published configuration history; reads never publish or repair a missing past version. */
@Service
@RequiredArgsConstructor
public class SimulatedTaskPolicyService {
    public static final List<String> TASK_CLASSES=List.of("IG","VG","LL","FT","EM","SP");
    private final SimulatedTaskPolicyMapper mapper;
    private final ObjectProvider<DeviceOpsRepository> devices;
    private final ObjectMapper json;
    private final Clock clock;
    public record TaskTypeSnapshot(String taskTypeId,long typeVersion,String name,String taskClass,
            int durationSeconds,BigDecimal baseReward,String asset,boolean enabled) { }
    public record PolicyVersion(String productKey,long version,LocalDateTime effectiveAt,
            List<TaskTypeSnapshot> taskTypes,long e3Version,Map<String,String> e3Config, BigDecimal monthlyTargetUsdt, BigDecimal monthlyTargetBandPct) { }
    public record TypeView(int durationMinutes,BigDecimal baseReward,String asset,boolean enabled,
            long version,LocalDateTime effectiveAt) { }
    public record ProductView(List<String> allowedTaskTypeIds,List<String> allowedTaskClasses,long productTaskPolicyVersion,
            LocalDateTime productTaskPolicyEffectiveAt) { }

    public PolicyVersion policyAt(String productKey,LocalDateTime at) {
        var product=mapper.productAt(productKey,at);
        if(product==null)throw new BizException(422,"SIMULATED_PRODUCT_POLICY_HISTORY_MISSING");
        var e3=mapper.e3At(at);
        if(e3==null)throw new BizException(422,"SIMULATED_E3_POLICY_HISTORY_MISSING");
        var types=new ArrayList<TaskTypeSnapshot>();
        LocalDateTime effectiveAt=product.effectiveAt().isAfter(e3.effectiveAt())?product.effectiveAt():e3.effectiveAt();
        List<TypeVersion> versions;
        if(product.allowedTaskClassesJson()!=null) {
            versions=typesInClasses(strings(product.allowedTaskClassesJson()),at);
        } else {
            // ID-based history remains authoritative before the class policy's effective time.
            var ids=strings(product.allowedTaskTypeIdsJson());
            versions=ids.isEmpty()?List.of():mapper.typesAt(ids,at);
            if(versions.size()!=ids.size())throw new BizException(422,"SIMULATED_TASK_TYPE_HISTORY_MISSING");
        }
        for(var type:versions) {
            if(type.effectiveAt().isAfter(effectiveAt))effectiveAt=type.effectiveAt();
            types.add(new TaskTypeSnapshot(type.taskTypeId(),type.version(),type.name(),type.taskClass(),type.durationSeconds(),
                    type.baseReward(),type.asset(),type.enabled()));
        }
        return new PolicyVersion(productKey,product.version(),effectiveAt,List.copyOf(types),e3.version(),
                Map.copyOf(decode(e3.configJson(),new TypeReference<Map<String,String>>(){})),product.monthlyTargetUsdt(),product.monthlyTargetBandPct());
    }

    public PolicyVersion nextPolicyAfter(String productKey,LocalDateTime after,LocalDateTime notAfter) {
        var product=mapper.productAt(productKey,after);
        LocalDateTime next=mapper.nextProduct(productKey,after,notAfter);
        var e3=mapper.nextE3(after,notAfter);
        if(e3!=null&&(next==null||e3.isBefore(next)))next=e3;
        if(product!=null) {
            boolean byClass=product.allowedTaskClassesJson()!=null;
            var scope=strings(byClass?product.allowedTaskClassesJson():product.allowedTaskTypeIdsJson());
            if(!scope.isEmpty()) {
                var type=byClass?mapper.nextTypeInClasses(scope,after,notAfter):mapper.nextType(scope,after,notAfter);
                if(type!=null&&(next==null||type.isBefore(next)))next=type;
            }
        }
        return next==null?null:policyAt(productKey,next);
    }

    public TypeView typeView(String taskId) {
        var t=mapper.typeAt(taskId,LocalDateTime.now(clock));
        return t==null?null:toView(t);
    }
    public Map<String,TypeView> typeViews(List<String> ids) {
        var views=new HashMap<String,TypeView>();
        if(!ids.isEmpty())for(var type:mapper.typesAt(ids,LocalDateTime.now(clock)))views.put(type.taskTypeId(),toView(type));
        return views;
    }
    private TypeView toView(TypeVersion t) {
        return new TypeView(t.durationSeconds()/60,t.baseReward(),t.asset(),t.enabled(),t.version(),t.effectiveAt());
    }
    public List<TaskTypeSnapshot> currentTypes() {
        return mapper.currentTypes().stream().map(t->new TaskTypeSnapshot(t.taskTypeId(),t.version(),t.name(),t.taskClass(),
                t.durationSeconds(),t.baseReward(),t.asset(),t.enabled())).toList();
    }
    public ProductView productView(String key) {
        var at=LocalDateTime.now(clock);
        var version=mapper.productAt(key,at);
        String classesJson="phone".equals(key)?null:mapper.draftClasses(key);
        if(classesJson==null && version!=null)classesJson=version.allowedTaskClassesJson();
        var classes=classesJson==null?List.<String>of():strings(classesJson);
        List<String> ids;
        if(classesJson!=null) {
            ids=typesInClasses(classes,at).stream().map(TypeVersion::taskTypeId).toList();
        } else {
            String old="phone".equals(key)?null:mapper.draftTypes(key);
            if(old==null && version!=null)old=version.allowedTaskTypeIdsJson();
            ids=old==null?List.of():strings(old);
        }
        return new ProductView(ids,classes,version==null?0:version.version(),version==null?null:version.effectiveAt());
    }
    public void validateType(TaskSimulationSettings s) {
        if(s==null||s.durationMinutes()==null||s.durationMinutes()<1||s.durationMinutes()>1440)
            throw new BizException(422,"SIMULATED_DURATION_MINUTES_INVALID");
        if(s.baseReward()==null||s.baseReward().signum()<0||s.baseReward().compareTo(new BigDecimal("999999999999.999999"))>0
                || s.baseReward().stripTrailingZeros().scale()>6)throw new BizException(422,"SIMULATED_FIXED_REWARD_INVALID");
        if(!Set.of("USDT","UVT").contains(s.asset()==null?"":s.asset()))throw new BizException(422,"SIMULATED_ASSET_INVALID");
        if(s.enabled()==null||s.expectedVersion()==null||s.expectedVersion()<0)throw new BizException(422,"SIMULATED_POLICY_VERSION_REQUIRED");
    }
    public void publishType(String id,String name,String taskClass,TaskSimulationSettings settings,LocalDateTime at) {
        mapper.lockE3Configuration();
        var before=mapper.lockType(id);
        if(settings.expectedVersion()!=(before==null?0:before.version()))throw new BizException(409,"SIMULATED_TASK_POLICY_VERSION_CONFLICT");
        validateTargetPools(id, taskClass, settings, at);
        initializeE3(at);
        mapper.insertType(new TypeVersion(id,before==null?1:before.version()+1,at,name,taskClass,
                settings.durationMinutes()*60,settings.baseReward().setScale(6,RoundingMode.UNNECESSARY),settings.asset(),settings.enabled()));
    }
    public List<String> writeClasses(List<String> classes,List<String> legacyIds,List<String> previousClasses,List<String> previousIds) {
        if(classes!=null) {
            if(classes.stream().anyMatch(c->c==null||!TASK_CLASSES.contains(c)))throw new BizException(422,"SIMULATED_TASK_CLASS_INVALID");
            return TASK_CLASSES.stream().filter(classes::contains).toList();
        }
        // An older E1 client may resubmit an unchanged product. It cannot grant a whole class by sending individual IDs.
        if(legacyIds!=null && !new HashSet<>(legacyIds).equals(new HashSet<>(previousIds)))
            throw new BizException(409,"SIMULATED_TASK_CLASSES_REQUIRED");
        return previousClasses;
    }
    private List<TypeVersion> typesInClasses(List<String> classes,LocalDateTime at) {
        return classes.isEmpty()?List.of():mapper.typesInClassesAt(classes,at);
    }
    public void saveProduct(String key,List<String> classes,boolean publishing,LocalDateTime at) {
        saveProduct(key,classes,publishing,at,true);
    }
    public void saveProduct(String key,List<String> classes,boolean publishing,LocalDateTime at,boolean approvedScopeChange) {
        mapper.lockE3Configuration();
        var before=mapper.lockProduct(key);
        if(publishing && !approvedScopeChange && before!=null) {
            var types=before.allowedTaskClassesJson()==null ? mapper.typesAt(strings(before.allowedTaskTypeIdsJson()),at)
                    :typesInClasses(strings(before.allowedTaskClassesJson()),at);
            var target=mapper.targetConfig(key);
            if(target!=null && target.monthlyTargetUsdt()!=null)validatePool(key,types);
            initializeE3(at);
            mapper.insertProduct(new ProductVersion(key,before.version()+1,at,before.allowedTaskTypeIdsJson(),before.allowedTaskClassesJson(),
                    target==null?null:target.monthlyTargetUsdt(),target==null?null:target.monthlyTargetBandPct()));
        } else if(publishing)publishProduct(key,classes,null,at);
        var ids=typesInClasses(classes,at).stream().map(TypeVersion::taskTypeId).toList();
        mapper.updateDraft(key,encode(ids),encode(classes));
    }
    public boolean hasPublishedProduct(String key) { return mapper.productAt(key,LocalDateTime.now(clock))!=null; }
    public ProductView publishProduct(String key,List<String> classes,Long expectedVersion,LocalDateTime at) {
        mapper.lockE3Configuration();
        var before=mapper.lockProduct(key);
        if(expectedVersion!=null&&expectedVersion!=(before==null?0:before.version()))throw new BizException(409,"SIMULATED_PRODUCT_POLICY_VERSION_CONFLICT");
        var target=mapper.targetConfig(key);
        if(classes.isEmpty() && (target==null || target.monthlyTargetUsdt()==null))throw new BizException(422,"SIMULATED_TASK_CLASSES_REQUIRED");
        var types=typesInClasses(classes,at);
        if(target!=null && target.monthlyTargetUsdt()!=null)validatePool(key,types);
        else if(types.stream().noneMatch(TypeVersion::enabled))throw new BizException(422,"SIMULATED_TASK_TYPE_NOT_AVAILABLE");
        var ids=types.stream().map(TypeVersion::taskTypeId).toList();
        initializeE3(at);
        mapper.insertProduct(new ProductVersion(key,before==null?1:before.version()+1,at,encode(ids),encode(classes),
                target==null?null:target.monthlyTargetUsdt(),target==null?null:target.monthlyTargetBandPct()));
        return new ProductView(ids,classes,before==null?1:before.version()+1,at);
    }
    public void validateTargetProduct(String key,List<String> classes,boolean usePublishedScope) {
        mapper.lockE3Configuration();
        var at=LocalDateTime.now(clock);
        var before=usePublishedScope?mapper.productAt(key,at):null;
        var types=before==null?typesInClasses(classes,at):before.allowedTaskClassesJson()==null
                ?mapper.typesAt(strings(before.allowedTaskTypeIdsJson()),at):typesInClasses(strings(before.allowedTaskClassesJson()),at);
        validatePool(key,types);
    }
    private static boolean ineligible(TypeVersion t) {
        return t.enabled() && !DeviceMonthlyTargetPacing.eligible(t.asset(),t.baseReward(),t.durationSeconds());
    }
    private static void validatePool(String key,List<TypeVersion> types) {
        if(types.stream().anyMatch(SimulatedTaskPolicyService::ineligible))throw new MonthlyTargetPoolException(List.of(key));
    }
    /** E2 writes and A2 replay share the configuration lock with every target/scope publication. */
    public void validateTargetPools(String changedId,String taskClass,TaskSimulationSettings settings,LocalDateTime at) {
        mapper.lockE3Configuration();
        var affected=new ArrayList<String>();
        var replacement=new TypeVersion(changedId,0,at,"",taskClass,settings.durationMinutes()*60,settings.baseReward(),settings.asset(),settings.enabled());
        for(var target:mapper.targetProducts()) {
            String key=target.productKey(); var published=mapper.productAt(key,at);
            String classesJson=published==null?mapper.draftClasses(key):published.allowedTaskClassesJson();
            List<String> classes=classesJson==null?null:strings(classesJson);
            var ids=classes==null?strings(published==null?mapper.draftTypes(key):published.allowedTaskTypeIdsJson()):List.<String>of();
            var pool=new ArrayList<>(classes==null?(ids.isEmpty()?List.<TypeVersion>of():mapper.typesAt(ids,at)):typesInClasses(classes,at));
            pool.removeIf(t->t.taskTypeId().equals(changedId));
            if(classes==null?ids.contains(changedId):classes.contains(taskClass))pool.add(replacement);
            if(pool.stream().anyMatch(SimulatedTaskPolicyService::ineligible))affected.add(key);
        }
        if(!affected.isEmpty())throw new MonthlyTargetPoolException(affected);
    }
    private List<String> strings(String value) { if(value==null)return List.of(); return decode(value,new TypeReference<List<String>>(){}); }
    public void lockE3Configuration() { mapper.lockE3Configuration(); }
    public void recordE3(LocalDateTime at) {
        var previous=mapper.lockE3Version();
        if(previous!=null)mapper.insertE3(new E3Version(previous.version()+1,at,encode(devices.getObject().e3Config())));
    }
    private void initializeE3(LocalDateTime at) {
        if(mapper.lockE3Version()==null)mapper.insertE3(new E3Version(1,at,encode(devices.getObject().e3Config())));
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("SIMULATED_POLICY_JSON_INVALID",failure);}
    }
    private <T>T decode(String value,TypeReference<T> type) {
        try {return json.readValue(value,type);}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("SIMULATED_POLICY_HISTORY_INVALID",failure);}
    }
}
