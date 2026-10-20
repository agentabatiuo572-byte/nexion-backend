package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementPolicyOperations;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper.Row;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class PhoneReplacementPolicyPublicationService implements PhoneReplacementPolicyOperations {
    public static final String PREFIX = "E.compute.phoneBinding.";
    public static final String VERSION = PREFIX + "policyVersion";
    public static final String BATCH = "PHONE_POLICY_PUBLISH";
    public static final String SINGLE = "PHONE_POLICY_PARAM_PUBLISH";
    public static final List<String> FIELDS = List.of("allowReplacement", "minReplacementIntervalDays", "replacementWindowDays", "maxReplacementsInWindow");
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final PhoneReplacementContract domain;
    private final PlatformConfigFacade config;
    private final PhoneReplacementPolicyOperationMapper commands;
    private final PhoneReplacementAdminAccess access;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final Clock clock;

    public record Result(Receipt receipt, Map<String,String> fieldErrors) { }

    public Policy currentPolicy() { return domain.currentPolicy(); }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Result> publish(Map<String,String> values, Long expectedVersion, String reason, String key, boolean single) {
        boolean flagOnly = single && values != null && values.keySet().equals(Set.of(PREFIX + "allowReplacement"));
        String permission = flagOnly ? "device_e6_flag_toggle" : "device_e6_write";
        if (!A2ReplayContext.isReplaying()) {
            if (flagOnly) access.require("device_e6_write",permission); else access.require(permission);
            return ApiResult.fail(409,"A2_CONFIRMATION_REQUIRED");
        }
        long actor=access.requirePolicyApproval();
        if (key == null || key.isBlank() || key.length() > 128 || reason == null || reason.trim().length() < 8 || reason.trim().length() > 200)
            throw new PhoneReplacementException(422, "PHONE_POLICY_COMMAND_INVALID");
        var errors = new LinkedHashMap<String,String>();
        if (values == null || values.isEmpty() || values.keySet().stream().anyMatch(k -> k == null || !k.startsWith(PREFIX) || !FIELDS.contains(k.substring(PREFIX.length()))))
            throw new PhoneReplacementException(422, "INVALID_POLICY", Map.of("policy", "手机策略字段无效"));
        if (single ? values.size() != 1 : !values.keySet().equals(new HashSet<>(FIELDS.stream().map(PREFIX::concat).toList())))
            throw new PhoneReplacementException(422, "INVALID_POLICY", Map.of("policy", "批量发布必须包含四项手机策略，且不能混入其他参数"));
        if (expectedVersion == null || expectedVersion < 1) errors.put("policyVersion", "请刷新策略后再次确认");
        if (!errors.isEmpty()) throw new PhoneReplacementException(422, "INVALID_POLICY", errors);

        Policy current = domain.lockCurrentPolicy(); // Same policy-before-account order as phone commands.
        String type = single ? SINGLE : BATCH;
        String hash = hash(Map.of("values", new TreeMap<>(values), "expectedPolicyVersion", expectedVersion, "reason", reason.trim()));
        Row prior = commands.lockByKey(actor,type,key.trim());
        if (prior != null) {
            if (!hash.equals(prior.requestHash())) throw new PhoneReplacementException(409, "IDEMPOTENCY_MISMATCH");
            return ApiResult.ok(new Result(receipt(prior), Map.of()));
        }
        if (current.policyVersion() == null || !current.available() && single) throw new PhoneReplacementException(503, "PHONE_REPLACEMENT_POLICY_UNAVAILABLE");
        if (!expectedVersion.equals(current.policyVersion())) throw new PhoneReplacementException(409, "POLICY_VERSION_CHANGED");
        if (current.policyVersion() >= 9_007_199_254_740_991L) throw new PhoneReplacementException(422, "INVALID_POLICY",Map.of("policyVersion","策略版本超出范围"));
        var candidateValues = current.available() ? values(current) : new LinkedHashMap<String,String>();
        candidateValues.putAll(values);
        Policy candidate = parse(candidateValues,current.policyVersion()+1,errors);
        if (candidate != null) errors.putAll(domain.validatePolicy(candidate,clock.millis()));
        if (!errors.isEmpty()) throw new PhoneReplacementException(422, "INVALID_POLICY",errors);

        String id = "PPO" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.millis();
        one(commands.insert(new Row(id,actor,type,key.trim(),hash,permission,"PENDING",now,null,null,null)));
        candidateValues.forEach((name,value) -> config.upsertAdminValue(name,value,"STRING","e6_compute","Versioned phone replacement policy"));
        config.upsertAdminValue(VERSION,String.valueOf(candidate.policyVersion()),"INTEGER","e6_compute","Phone policy version");
        var result = new LinkedHashMap<String,Object>();
        result.put("policy",candidate);result.put("values",candidateValues);result.put("previousPolicyVersion",current.policyVersion());
        result.put("a2OperationId",A2ReplayContext.operationId());
        String auditRef="PHONE_POLICY:"+id;
        var detail = Map.<String,Object>of("oldPolicy",current,"newPolicy",candidate,"operationId",id,"reason",reason.trim(),"operatorId",actor);
        audit.recordRequired(AuditLogWriteRequest.builder().traceId(auditRef).action("ADMIN.PHONE_REPLACEMENT_POLICY_UPDATED")
                .resourceType("PHONE_REPLACEMENT_POLICY").resourceId("global").actorType("ADMIN").actorId(actor)
                .result("SUCCEEDED").riskLevel("HIGH").detail(detail).build());
        outbox.publish("PHONE_REPLACEMENT_POLICY", "global", "admin.phone_replacement_policy_updated", detail);
        one(commands.complete(id,clock.millis(),write(result),auditRef));
        return ApiResult.ok(new Result(receipt(commands.lockByKey(actor,type,key.trim())),Map.of()));
    }

    @Override @Transactional(readOnly = true)
    public Receipt operation(String id) {
        // Missing IDs reveal no policy or administrator data.
        Row row=commands.find(id);
        if(row==null){access.requirePolicyReceiptRead();return null;}
        requireRead(row);
        return receipt(row);
    }
    @Override @Transactional(readOnly = true)
    public Receipt operationByKey(String type,String key) {
        if(type == null || !Set.of(BATCH,SINGLE).contains(type) || key == null || key.isBlank() || key.length() > 128)
            throw new PhoneReplacementException(422,"PHONE_POLICY_COMMAND_INVALID");
        long actor=access.requirePolicyReceiptRead();
        Row row=commands.findByKey(actor,type,key);
        if(row!=null)requireRead(row);
        return row==null?null:receipt(row);
    }
    private void requireRead(Row row){
        access.requirePolicyReceiptRead();
    }
    private Receipt receipt(Row row){
        return new Receipt(row.operationId(),row.commandType(),row.status(),row.submittedAt(),row.completedAt(),
                read(row.originalResultJson()),domain.currentPolicy(),row.auditRef());
    }
    public static LinkedHashMap<String,String> values(Policy policy){
        var map=new LinkedHashMap<String,String>();
        map.put(PREFIX+"allowReplacement",Boolean.TRUE.equals(policy.allowReplacement())?"on":"off");
        map.put(PREFIX+"minReplacementIntervalDays",String.valueOf(policy.minReplacementIntervalDays()));
        map.put(PREFIX+"replacementWindowDays",String.valueOf(policy.replacementWindowDays()));
        map.put(PREFIX+"maxReplacementsInWindow",String.valueOf(policy.maxReplacementsInWindow()));return map;
    }
    private static Policy parse(Map<String,String> values,long version,Map<String,String> errors){
        String flag=values.get(PREFIX+"allowReplacement");
        if(!"on".equals(flag) && !"off".equals(flag))errors.put("allowReplacement","请选择开启或关闭");
        Long days=integer(values,"minReplacementIntervalDays",errors);
        Long window=integer(values,"replacementWindowDays",errors),count=integer(values,"maxReplacementsInWindow",errors);
        if(window!=null && window>Integer.MAX_VALUE)errors.put("replacementWindowDays","滚动窗口须为1至365天");
        if(count!=null && count>Integer.MAX_VALUE)errors.put("maxReplacementsInWindow","更换次数须为1至100");
        return errors.isEmpty()?new Policy(true,"on".equals(flag),days,window.intValue(),count.intValue(),version):null;
    }
    private static Long integer(Map<String,String> values,String field,Map<String,String> errors){
        String value=values.get(PREFIX+field);
        try{if(value==null || !value.matches("[0-9]{1,16}"))throw new NumberFormatException();return Long.parseLong(value);}
        catch(NumberFormatException invalid){errors.put(field,"请输入非负整数");return null;}
    }
    private static String write(Object value){try{return JSON.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("PHONE_POLICY_SERIALIZATION_FAILED",invalid);}}
    private static Object read(String value){try{return value==null?null:JSON.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception invalid){throw new IllegalStateException("PHONE_POLICY_RECEIPT_UNAVAILABLE",invalid);}}
    private static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception invalid){throw new IllegalStateException(invalid);}}
    private static void one(int changed){if(changed!=1)throw new PhoneReplacementException(409,"PHONE_POLICY_WRITE_CONFLICT");}
}
