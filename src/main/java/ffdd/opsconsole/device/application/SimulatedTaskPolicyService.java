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
    private final SimulatedTaskPolicyMapper mapper;
    private final ObjectProvider<DeviceOpsRepository> devices;
    private final ObjectMapper json;
    private final Clock clock;
    public record TaskTypeSnapshot(String taskTypeId,long typeVersion,String name,String taskClass,
            int durationSeconds,BigDecimal baseReward,String asset,boolean enabled) { }
    public record PolicyVersion(String productKey,long version,LocalDateTime effectiveAt,
            List<TaskTypeSnapshot> taskTypes,long e3Version,Map<String,String> e3Config) { }
    public record TypeView(int durationMinutes,BigDecimal baseReward,String asset,boolean enabled,
            long version,LocalDateTime effectiveAt) { }
    public record ProductView(List<String> allowedTaskTypeIds,long productTaskPolicyVersion,
            LocalDateTime productTaskPolicyEffectiveAt) { }

    public PolicyVersion policyAt(String productKey,LocalDateTime at) {
        var product=mapper.productAt(productKey,at);
        if(product==null)throw new BizException(422,"SIMULATED_PRODUCT_POLICY_HISTORY_MISSING");
        var e3=mapper.e3At(at);
        if(e3==null)throw new BizException(422,"SIMULATED_E3_POLICY_HISTORY_MISSING");
        var types=new ArrayList<TaskTypeSnapshot>();
        LocalDateTime effectiveAt=product.effectiveAt().isAfter(e3.effectiveAt())?product.effectiveAt():e3.effectiveAt();
        for(String id:decode(product.allowedTaskTypeIdsJson(),new TypeReference<List<String>>(){})) {
            var type=mapper.typeAt(id,at);
            if(type==null)throw new BizException(422,"SIMULATED_TASK_TYPE_HISTORY_MISSING");
            if(type.effectiveAt().isAfter(effectiveAt))effectiveAt=type.effectiveAt();
            types.add(new TaskTypeSnapshot(id,type.version(),type.name(),type.taskClass(),type.durationSeconds(),
                    type.baseReward(),type.asset(),type.enabled()));
        }
        return new PolicyVersion(productKey,product.version(),effectiveAt,List.copyOf(types),e3.version(),
                Map.copyOf(decode(e3.configJson(),new TypeReference<Map<String,String>>(){})));
    }

    public PolicyVersion nextPolicyAfter(String productKey,LocalDateTime after,LocalDateTime notAfter) {
        var product=mapper.productAt(productKey,after);
        LocalDateTime next=mapper.nextProduct(productKey,after,notAfter);
        var e3=mapper.nextE3(after,notAfter);
        if(e3!=null&&(next==null||e3.isBefore(next)))next=e3;
        if(product!=null) {
            List<String> ids=decode(product.allowedTaskTypeIdsJson(),new TypeReference<List<String>>(){});
            if(!ids.isEmpty()) {
                var type=mapper.nextType(ids,after,notAfter);
                if(type!=null&&(next==null||type.isBefore(next)))next=type;
            }
        }
        return next==null?null:policyAt(productKey,next);
    }

    public TypeView typeView(String taskId) {
        var t=mapper.typeAt(taskId,LocalDateTime.now(clock));
        return t==null?null:new TypeView(t.durationSeconds()/60,t.baseReward(),t.asset(),t.enabled(),t.version(),t.effectiveAt());
    }
    public List<TaskTypeSnapshot> currentTypes() {
        return mapper.currentTypes().stream().map(t->new TaskTypeSnapshot(t.taskTypeId(),t.version(),t.name(),t.taskClass(),
                t.durationSeconds(),t.baseReward(),t.asset(),t.enabled())).toList();
    }
    public ProductView productView(String key) {
        var version=mapper.productAt(key,LocalDateTime.now(clock));
        String draft="phone".equals(key)?null:mapper.draftTypes(key);
        List<String> ids=draft!=null?decode(draft,new TypeReference<List<String>>(){}):version==null?List.of():decode(version.allowedTaskTypeIdsJson(),new TypeReference<List<String>>(){});
        return new ProductView(List.copyOf(ids),version==null?0:version.version(),version==null?null:version.effectiveAt());
    }
    public void validateType(TaskSimulationSettings s) {
        if(s==null||s.durationMinutes()==null||s.durationMinutes()<1||s.durationMinutes()>1440)
            throw new BizException(422,"SIMULATED_DURATION_MINUTES_INVALID");
        if(s.baseReward()==null||s.baseReward().signum()<0||s.baseReward().compareTo(new BigDecimal("999999999999.999999"))>0
                || s.baseReward().stripTrailingZeros().scale()>6)throw new BizException(422,"SIMULATED_FIXED_REWARD_INVALID");
        if(!Set.of("USDT","NEX").contains(s.asset()==null?"":s.asset()))throw new BizException(422,"SIMULATED_ASSET_INVALID");
        if(s.enabled()==null||s.expectedVersion()==null||s.expectedVersion()<0)throw new BizException(422,"SIMULATED_POLICY_VERSION_REQUIRED");
    }
    public void publishType(String id,String name,String taskClass,TaskSimulationSettings settings,LocalDateTime at) {
        mapper.lockE3Configuration();
        var before=mapper.lockType(id);
        if(settings.expectedVersion()!=(before==null?0:before.version()))throw new BizException(409,"SIMULATED_TASK_POLICY_VERSION_CONFLICT");
        initializeE3(at);
        mapper.insertType(new TypeVersion(id,before==null?1:before.version()+1,at,name,taskClass,
                settings.durationMinutes()*60,settings.baseReward().setScale(6,RoundingMode.UNNECESSARY),settings.asset(),settings.enabled()));
    }
    public List<String> validateAllowed(List<String> raw,boolean publishing) {
        if(raw==null)throw new BizException(422,"SIMULATED_ALLOWED_TYPES_REQUIRED");
        var ids=new LinkedHashSet<String>();
        for(String id:raw) {
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,64}"))throw new BizException(422,"SIMULATED_TASK_TYPE_ID_INVALID");
            var type=publishing?mapper.lockType(id):mapper.typeAt(id,LocalDateTime.now(clock));
            if(type==null||publishing&&!type.enabled())throw new BizException(422,"SIMULATED_TASK_TYPE_NOT_AVAILABLE");
            ids.add(id);
        }
        if(ids.size()>200||publishing&&ids.isEmpty())throw new BizException(422,"SIMULATED_ALLOWED_TYPES_REQUIRED");
        return List.copyOf(ids);
    }
    public void saveProduct(String key,List<String> raw,boolean publishing,LocalDateTime at) {
        List<String> ids=publishing?publishProduct(key,raw,null,at).allowedTaskTypeIds():validateAllowed(raw,false);
        mapper.updateDraft(key,encode(ids));
    }
    public ProductView publishProduct(String key,List<String> raw,Long expectedVersion,LocalDateTime at) {
        mapper.lockE3Configuration();List<String> ids=validateAllowed(raw,true);
        var before=mapper.lockProduct(key);
        if(expectedVersion!=null&&expectedVersion!=(before==null?0:before.version()))throw new BizException(409,"SIMULATED_PRODUCT_POLICY_VERSION_CONFLICT");
        initializeE3(at);
        mapper.insertProduct(new ProductVersion(key,before==null?1:before.version()+1,at,encode(ids)));
        return new ProductView(ids,before==null?1:before.version()+1,at);
    }
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
