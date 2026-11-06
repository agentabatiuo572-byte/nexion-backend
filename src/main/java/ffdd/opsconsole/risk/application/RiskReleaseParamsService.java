package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.dto.RiskReleaseParamUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RiskReleaseParamsService {
    private static final String PREFIX="risk.k1.release.";
    private static final String VERSION_KEY=PREFIX+"version";
    private static final List<String> KEYS = List.of(
            "duplicateAccountPendingFrom", "duplicateAccountFreezeFrom", "appAttestationReleaseHours", "releaseMode");
    private static final Set<String> RETIRED_KEYS = Set.of(
            "freePhoneSlotsPerCluster", "pendingReleaseHours", "freeSlotRequiresBinding");
    private static final List<String> PROMOTION_CONTRACT_KEYS = List.of(
            "freePhoneSlotsPerCluster", "duplicateAccountPendingFrom", "duplicateAccountFreezeFrom",
            "pendingReleaseHours", "appAttestationReleaseHours", "releaseMode", "freeSlotRequiresBinding");
    private final PlatformConfigFacade config;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final TreasuryCoverageFacade coverageFacade;
    // Automatic device-proof release is retired; stored legacy settings cannot re-enable it.
    private static final boolean trustedAttestationEnabled = false;

    public List<Map<String, Object>> rows() {
        return rows(KEYS, values());
    }

    public List<Map<String, Object>> promotionContractRows() {
        Map<String, String> snapshot = values();
        for (String key : RETIRED_KEYS) {
            String value = config.storedValue(PREFIX + key).filter(StringUtils::hasText)
                    .orElseThrow(() -> new BizException(503, "K1_RELEASE_CONFIG_UNAVAILABLE"));
            snapshot.put(key, value.trim());
        }
        return rows(PROMOTION_CONTRACT_KEYS, snapshot);
    }

    private List<Map<String, Object>> rows(List<String> keys, Map<String, String> values) {
        long version = number(VERSION_KEY);
        return keys.stream().map(key -> {
            Map<String, Object> row = new LinkedHashMap<>();
            boolean mode = "releaseMode".equals(key);
            String effectiveValue = mode ? "manual_only" : values.get(key);
            row.put("key", key);
            row.put("name", name(key));
            row.put("value", effectiveValue);
            row.put("val", effectiveValue);
            row.put("version", version);
            row.put("adjustable", !mode && !"appAttestationReleaseHours".equals(key));
            row.put("unit", unit(key));
            row.put("sub", "pendingReleaseHours".equals(key) ? "只统计，不自动放款" : "人工审核释放规则");
            row.put("note", mode ? "仅支持人工审核释放" : "用户端只读");
            return row;
        }).toList();
    }

    public ApiResult<Map<String,Object>> update(String key,String idempotencyKey,RiskReleaseParamUpdateRequest request){
        if ("appAttestationReleaseHours".equals(key)) throw new BizException(410,"K1_TRUSTED_ATTESTATION_RETIRED");
        if (RETIRED_KEYS.contains(key)) throw new BizException(410, "K1_RELEASE_PARAM_RETIRED");
        if(!KEYS.contains(key)||request==null||!StringUtils.hasText(request.reason())||request.reason().trim().length()<8)throw new BizException(422,"K1_RELEASE_PARAM_REQUEST_INVALID");
        if(request.expectedVersion()==null||request.expectedVersion()<0)throw new BizException(400,"EXPECTED_VERSION_REQUIRED");
        String value=normalize(key,request.value());
        if ("releaseMode".equals(key) && "attest_or_manual".equals(value) && !trustedAttestationEnabled) {
            throw new BizException(410,"K1_TRUSTED_ATTESTATION_RETIRED");
        }
        @SuppressWarnings({"rawtypes","unchecked"}) ApiResult<Map<String,Object>> result=(ApiResult<Map<String,Object>>)(ApiResult)idempotency.execute("K1_RELEASE_PARAM:"+key,idempotencyKey,hash(key+"|"+value+"|"+request.expectedVersion()+"|"+request.reason()),ApiResult.class,(Supplier)(()->updateOnce(key,value,request)));
        return result;
    }

    @Transactional(rollbackFor=Exception.class)
    protected ApiResult<Map<String,Object>> updateOnce(String key,String value,RiskReleaseParamUpdateRequest request){
        long version;
        try{version=Long.parseLong(config.activeValueForUpdate(VERSION_KEY).orElseThrow());}catch(Exception e){throw new BizException(503,"K1_RELEASE_CONFIG_UNAVAILABLE");}
        if(version!=request.expectedVersion())throw new BizException(409,"K1_RELEASE_VERSION_CONFLICT");
        Map<String,String> before=values();Map<String,String> after=new LinkedHashMap<>(before);after.put(key,value);validateCross(after);
        TreasuryCoverageSnapshot coverageAtSubmit=isLoosening(key,before.get(key),value)?requireCoverageGate():null;
        config.upsertAdminValue(PREFIX+key,value,type(key),"risk","K1 earnings release parameter");
        config.upsertAdminValue(VERSION_KEY,String.valueOf(version+1),"NUMBER","risk","K1 earnings release aggregate version");
        Map<String,Object> detail=new LinkedHashMap<>();detail.put("before",before.get(key));detail.put("after",value);detail.put("versionBefore",version);detail.put("versionAfter",version+1);detail.put("reason",request.reason().trim());
        if(coverageAtSubmit!=null){detail.put("coverageAtSubmit",coverageAtSubmit.coverageRatio());detail.put("coverageRedline",coverageAtSubmit.redlinePct());}
        audit.recordRequired(AuditLogWriteRequest.builder().action("K1_RELEASE_PARAM_UPDATED").resourceType("RISK_RELEASE_PARAM").resourceId(key).actorUsername(AdminActorResolver.resolve(request.operator())).riskLevel("HIGH").detail(detail).build());
        return ApiResult.ok(Map.of("releaseParams",rows()));
    }

    public boolean manualOnly(){return true;}
    public boolean trustedAttestationEnabled(){return trustedAttestationEnabled;}
    public int pendingFrom(){return parse(values().get("duplicateAccountPendingFrom"),2,50);}
    public int freezeFrom(){return parse(values().get("duplicateAccountFreezeFrom"),1,50);}
    private Map<String,String> values(){Map<String,String> map=new LinkedHashMap<>();for(String key:KEYS){if("appAttestationReleaseHours".equals(key)){map.put(key,"1");continue;}String value=config.activeValue(PREFIX+key).filter(StringUtils::hasText).orElseThrow(()->new BizException(503,"K1_RELEASE_CONFIG_UNAVAILABLE"));map.put(key,value.trim());}validateCross(map);return map;}
    private void validateCross(Map<String,String> values) {
        int pending = parse(values.get("duplicateAccountPendingFrom"), 2, 50);
        int freeze = parse(values.get("duplicateAccountFreezeFrom"), 1, 50);
        if (pending > freeze) throw new BizException(422, "K1_RELEASE_PARAM_RELATION_INVALID");
        if (!Set.of("attest_or_manual", "manual_only").contains(values.get("releaseMode"))) {
            throw new BizException(422, "K1_RELEASE_PARAM_VALUE_INVALID");
        }
    }
    private String normalize(String key, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if ("releaseMode".equals(key)) {
            if (!Set.of("attest_or_manual", "manual_only").contains(normalized)) {
                throw new BizException(422, "K1_RELEASE_PARAM_VALUE_INVALID");
            }
            return normalized;
        }
        return String.valueOf(parse(normalized, "duplicateAccountPendingFrom".equals(key) ? 2 : 1, 50));
    }
    private int parse(String v,int min,int max){try{int n=Integer.parseInt(v);if(n<min||n>max)throw new Exception();return n;}catch(Exception e){throw new BizException(422,"K1_RELEASE_PARAM_VALUE_INVALID");}}
    private long number(String key){try{return Long.parseLong(config.activeValue(key).orElseThrow());}catch(Exception e){throw new BizException(503,"K1_RELEASE_CONFIG_UNAVAILABLE");}}
    private String type(String key){return "releaseMode".equals(key)?"STRING":"NUMBER";}
    private String name(String key) {
        return switch (key) {
            case "appAttestationReleaseHours" -> "已停止使用";
            case "freePhoneSlotsPerCluster" -> "每簇免费手机号位";
            case "duplicateAccountPendingFrom" -> "重复账户待审起点";
            case "duplicateAccountFreezeFrom" -> "重复账户冻结起点";
            case "pendingReleaseHours" -> "簇级释放统计窗口";
            case "releaseMode" -> "释放模式";
            default -> "免费位需绑定";
        };
    }
    private String unit(String key){return key.endsWith("Hours")?"小时":"";}
    private boolean isLoosening(String key,String before,String after){
        if(before==null||before.equals(after))return false;
        return switch(key){
            case "duplicateAccountPendingFrom","duplicateAccountFreezeFrom"->Integer.parseInt(after)>Integer.parseInt(before);
            case "releaseMode"->"manual_only".equals(before)&&"attest_or_manual".equals(after);
            default->false;
        };
    }
    public TreasuryCoverageSnapshot requireCoverageForAmplifyingRelease(){return requireCoverageGate();}
    private TreasuryCoverageSnapshot requireCoverageGate(){
        final TreasuryCoverageSnapshot snapshot;
        try{snapshot=coverageFacade.snapshot();}catch(Exception e){throw new BizException(503,"K1_COVERAGE_UNAVAILABLE");}
        if(snapshot==null||!snapshot.reliable()||snapshot.coverageRatio()==null||snapshot.redlinePct()==null||snapshot.coverageRatio().signum()<=0||snapshot.redlinePct().signum()<=0)throw new BizException(503,"K1_COVERAGE_UNAVAILABLE");
        if(snapshot.redlineBreached())throw new BizException(422,"COVERAGE_BELOW_REDLINE");
        return snapshot;
    }
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
