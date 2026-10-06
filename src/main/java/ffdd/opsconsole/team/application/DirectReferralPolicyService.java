package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.mapper.NexMarketMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.team.domain.DirectReferralPolicy;
import ffdd.opsconsole.team.dto.DirectReferralPolicyRequest;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DirectReferralPolicyService {
    static final String VERSION_KEY = "team.direct-referral.policy-version";
    public static final String SEVEN_REVISION_KEY = "team.seven-layer.revision";
    public static final String CUTOVER_KEY = "team.seven-layer.cutover-at";
    private final DirectReferralMapper mapper;
    private final PlatformConfigFacade config;
    private final NexMarketMapper prices;
    private final TreasuryCoverageFacade coverage;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final Environment environment;

    public record Scope(String sourceEnvironment,String runId,int sandbox) { }
    public Scope scope() {
        if (FundsSandboxProfileGuard.isStrictProductionProfile(environment.getActiveProfiles())) return new Scope("PRODUCTION","",0);
        if (FundsSandboxProfileGuard.isStrictTestProfile(environment.getActiveProfiles())) {
            String run = environment.getProperty("NEXION_ACCEPTANCE_RUN_ID", "").trim();
            if (run.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,95}")) return new Scope("SANDBOX",run,1);
        }
        throw new BizException(503,"DIRECT_REFERRAL_PROFILE_INVALID");
    }
    public DirectReferralPolicy at(LocalDateTime at) {
        return readPolicy(mapper.policyAt(at));
    }
    public DirectReferralPolicy atForUpdate(LocalDateTime at) {return readPolicy(mapper.policyAtForUpdate(at));}
    private DirectReferralPolicy readPolicy(DirectReferralMapper.PolicyRow row) {
        if(row==null) return new DirectReferralPolicy(0,null,DirectReferralPolicy.DISABLED,DirectReferralPolicy.DISABLED);
        try {
            var policy=new DirectReferralPolicy(row.policyVersion(),row.effectiveAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant(),
                    readPurchase(row.purchaseJson()),json.readValue(row.deviceEarningJson(),DirectReferralPolicy.Rule.class),json.readTree(row.purchaseJson()).path("schemaVersion").asInt(1));
            policy.purchase().validate(); policy.deviceEarning().validate(); return policy;
        } catch(Exception e) {throw new BizException(503,"DIRECT_REFERRAL_POLICY_INVALID");}
    }
    private DirectReferralPolicy.Rule readPurchase(String raw) throws Exception {
        var value=json.readTree(raw);
        if (value.path("schemaVersion").asInt()==2) return new DirectReferralPolicy.Rule(value.path("enabled").booleanValue(),BigDecimal.TEN,
                value.path("usdtSharePct").decimalValue(),0);
        return json.readValue(raw,DirectReferralPolicy.Rule.class);
    }
    public Instant cutoverAt() {
        var raw=config.activeValue(CUTOVER_KEY).orElse(null);
        if(raw==null||raw.isBlank())return null;
        try{return Instant.parse(raw);}catch(RuntimeException e){throw new BizException(503,"SEVEN_LAYER_CUTOVER_INVALID");}
    }
    public boolean sevenLayerActive(LocalDateTime at) { var boundary=cutoverAt();return boundary!=null&&!at.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().isBefore(boundary); }
    public void requireSchema(Integer version) { if(version!=null&&!Set.of(1,2).contains(version))throw new BizException(422,"TEAM_SCHEMA_VERSION_UNSUPPORTED");if(sevenLayerActive(now())&&!Integer.valueOf(2).equals(version))throw new BizException(409,"TEAM_SCHEMA_UPDATE_REQUIRED"); }
    public long sevenLayerRevision() {return config.activeValue(SEVEN_REVISION_KEY).map(Long::parseLong).orElse(0L);}
    public long lockSevenLayerRevision() {
        var value=config.activeValueForUpdate(SEVEN_REVISION_KEY).orElseThrow(()->new BizException(503,"SEVEN_LAYER_REVISION_UNAVAILABLE"));
        try{return Long.parseLong(value);}catch(RuntimeException e){throw new BizException(503,"SEVEN_LAYER_REVISION_UNAVAILABLE");}
    }
    public long bumpSevenLayerRevision() {long next=Math.addExact(lockSevenLayerRevision(),1);config.upsertAdminValue(SEVEN_REVISION_KEY,Long.toString(next),"NUMBER","team","七层配置一致性版本锁");return next;}
    public Map<String,Object> sevenLayerReference() {
        return sevenLayerReference(false);
    }
    private Map<String,Object> sevenLayerReference(boolean currentRead) {
        var reference=new LinkedHashMap<String,Object>();reference.put("revision",currentRead?lockSevenLayerRevision():sevenLayerRevision());
        var rows=currentRead?mapper.sevenLayerReferenceForUpdate():mapper.sevenLayerReference();var first=rows==null||rows.isEmpty()?Map.<String,Object>of():rows.get(0);
        reference.put("baseRatePct",first.get("usdtPct"));reference.put("legacyNexPerUsd",first.get("nexReward"));
        reference.put("coolingDays",(currentRead?config.activeValueForUpdate("commission/cooling-days"):config.activeValue("commission/cooling-days")).map(Integer::valueOf).orElse(null));return reference;
    }
    public BigDecimal price() {var value=prices.latestNexUsdtPrice();return value!=null&&value.signum()>0?value:null;}
    public Map<String,Object> current() {
        var p=at(now());var s=scope();Map<String,Object> out=new LinkedHashMap<>();
        out.put("source","server");out.put("serverCanonical",true);out.put("sourceEnvironment",s.sourceEnvironment());out.put("runId",s.runId());
        out.put("configured",p.policyVersion()>0);out.put("policyVersion",p.policyVersion());out.put("effectiveAt",p.effectiveAt());
        out.put("policySchemaVersion",p.schemaVersion());
        out.put("nexUsdtPrice",price());out.put("purchase",p.purchase());out.put("deviceEarning",p.deviceEarning());return out;
    }
    public Map<String,Object> current(Integer schemaVersion) {
        requireSchema(schemaVersion);var out=current();if(!Integer.valueOf(2).equals(schemaVersion))return out;
        var p=at(now());if(sevenLayerActive(now()))out.remove("purchase");out.put("schemaVersion",2);out.put("purchaseSplitConfigured",p.schemaVersion()==2);
        out.put("purchaseSplit",Map.of("enabled",p.schemaVersion()==2&&p.purchase().enabled(),"usdtSharePct",p.schemaVersion()==2?p.purchase().usdtSharePct():new BigDecimal("50")));
        out.put("sevenLayerReference",sevenLayerReference());out.put("sevenLayerRevision",sevenLayerRevision());
        boolean enabled=sevenLayerActive(now());out.put("settlementMode",enabled?"SEVEN_V2":"DIRECT_ONLY_V1");out.put("sevenLayerEnabled",enabled);out.put("cutoverAt",cutoverAt());return out;
    }
    public static void validate(DirectReferralPolicyRequest request,Instant now) {
        if(request==null||request.expectedVersion()==null||request.expectedVersion()<0
                ||request.effectivePurchase()==null||request.deviceEarning()==null||request.reason()==null
                ||request.reason().trim().length()<8||request.reason().trim().length()>200) throw new BizException(422,"DIRECT_REFERRAL_POLICY_REQUEST_INVALID");
        if(Integer.valueOf(2).equals(request.schemaVersion())&&(request.purchase()!=null||request.expectedSevenLayerRevision()==null||request.expectedSevenLayerRevision()<0||request.purchaseSplit()==null))throw new BizException(422,"DIRECT_REFERRAL_POLICY_SCHEMA_INVALID");
        if(!Integer.valueOf(2).equals(request.schemaVersion())&&(request.purchaseSplit()!=null||request.expectedSevenLayerRevision()!=null))throw new BizException(422,"DIRECT_REFERRAL_POLICY_SCHEMA_INVALID");
        if(request.schemaVersion()!=null&&!Set.of(1,2).contains(request.schemaVersion()))throw new BizException(422,"DIRECT_REFERRAL_POLICY_SCHEMA_INVALID");
        request.effectivePurchase().validate();request.deviceEarning().validate();
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> publish(String key,DirectReferralPolicyRequest request) {
        validate(request,clock.instant());
        if(key==null||key.isBlank())throw new BizException(400,"IDEMPOTENCY_KEY_REQUIRED");
        if(!A2ReplayContext.isReplaying()||A2ReplayContext.operationId()==null)throw new BizException(409,"A2_CONFIRMATION_REQUIRED");
        requireSchema(request.schemaVersion());
        if(Integer.valueOf(2).equals(request.schemaVersion())&&!sevenLayerActive(now()))throw new BizException(409,"SEVEN_LAYER_NOT_ACTIVE");
        long revision=Integer.valueOf(2).equals(request.schemaVersion())?lockSevenLayerRevision():sevenLayerRevision();
        if(Integer.valueOf(2).equals(request.schemaVersion())&&revision!=request.expectedSevenLayerRevision())throw new BizException(409,"SEVEN_LAYER_REVISION_CONFLICT");
        config.activeValueForUpdate(VERSION_KEY).orElseThrow(()->new BizException(503,"DIRECT_REFERRAL_VERSION_UNAVAILABLE"));
        long version=mapper.latestVersionForUpdate();
        if(version!=request.expectedVersion())throw new BizException(409,"DIRECT_REFERRAL_VERSION_CONFLICT");
        var before=atForUpdate(now());
        var reference=Integer.valueOf(2).equals(request.schemaVersion())?sevenLayerReference(true):Map.<String,Object>of();
        if(Integer.valueOf(2).equals(request.schemaVersion()))reference.put("revision",revision);
        boolean purchaseAmplifies=Integer.valueOf(2).equals(request.schemaVersion())?purchaseAmplifies(request.effectivePurchase(),before.schemaVersion()==2?before.purchase():DirectReferralPolicy.DISABLED,money(reference.get("legacyNexPerUsd")),true):request.purchase().amplifies(before.purchase());
        if(purchaseAmplifies||request.deviceEarning().amplifies(before.deviceEarning())){
            var c=coverage.snapshot();
            if(c==null||!c.reliable()||c.coverageRatio()==null||c.redlinePct()==null)throw new BizException(503,"COVERAGE_SNAPSHOT_UNAVAILABLE");
            if(c.coverageRatio().compareTo(c.redlinePct())<0)throw new BizException(409,"COVERAGE_BELOW_REDLINE");
        }
        Instant effective=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try {
            if(mapper.insertPolicy(version+1,LocalDateTime.ofInstant(effective,DateTimeFormatConfig.BUSINESS_ZONE),
                    json.writeValueAsString(Integer.valueOf(2).equals(request.schemaVersion())?Map.of("schemaVersion",2,"enabled",request.purchaseSplit().enabled(),"usdtSharePct",request.purchaseSplit().usdtSharePct(),"sevenLayerRevision",revision,"sevenLayerReference",reference):request.purchase()),json.writeValueAsString(request.deviceEarning()),
                    A2ReplayContext.operationId(),request.reason().trim())!=1)throw new BizException(409,"DIRECT_REFERRAL_VERSION_CONFLICT");
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        config.upsertAdminValue(VERSION_KEY,Long.toString(version+1),"NUMBER","team","直属分成版本锁");
        audit.recordRequired(AuditLogWriteRequest.builder().action("F_DIRECT_REFERRAL_POLICY_APPROVED").resourceType("TEAM_POLICY")
                .resourceId("direct_referral_policy/current").actorUsername(AdminActorResolver.resolve("A2"))
                .riskLevel("HIGH").detail(Map.of("policyVersion",version+1,"effectiveAt",effective.toString(),"request",request,"operationId",A2ReplayContext.operationId())).build());
        var result=current(request.schemaVersion());if(Integer.valueOf(2).equals(request.schemaVersion())){result.put("sevenLayerReference",reference);result.put("sevenLayerRevision",revision);}result.put("approvedPolicyVersion",version+1);result.put("approvedEffectiveAt",effective);return result;
    }
    public boolean purchaseAmplifies(DirectReferralPolicy.Rule after,DirectReferralPolicy.Rule before) {
        return purchaseAmplifies(after,before,money(sevenLayerReference().get("legacyNexPerUsd")),false);
    }
    private boolean purchaseAmplifies(DirectReferralPolicy.Rule after,DirectReferralPolicy.Rule before,BigDecimal coefficient,boolean currentRead) {
        if(before.enabled()&&after.enabled())return before.usdtSharePct().compareTo(after.usdtSharePct())!=0;
        if(before.enabled())return true; // Restoring full USDT increases that asset even if NEX shrinks.
        if(!after.enabled())return false;
        return coefficient.signum()==0||new BigDecimal("100").subtract(after.usdtSharePct()).movePointLeft(2).compareTo(coefficient.multiply(zeroPrice(currentRead)))>0;
    }
    private BigDecimal zeroPrice(boolean currentRead){var p=currentRead?prices.latestNexUsdtPriceForUpdate():price();if(p==null||p.signum()<=0)throw new BizException(503,"DIRECT_REFERRAL_PRICE_UNAVAILABLE");return p;}
    private BigDecimal money(Object value){return value==null?BigDecimal.ZERO:new BigDecimal(value.toString());}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE);}
}
