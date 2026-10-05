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
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DirectReferralPolicyService {
    static final String VERSION_KEY = "team.direct-referral.policy-version";
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
        var row=mapper.policyAt(at);
        if(row==null) return new DirectReferralPolicy(0,null,DirectReferralPolicy.DISABLED,DirectReferralPolicy.DISABLED);
        try {
            var policy=new DirectReferralPolicy(row.policyVersion(),row.effectiveAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant(),
                    json.readValue(row.purchaseJson(),DirectReferralPolicy.Rule.class),json.readValue(row.deviceEarningJson(),DirectReferralPolicy.Rule.class));
            policy.purchase().validate(); policy.deviceEarning().validate(); return policy;
        } catch(Exception e) {throw new BizException(503,"DIRECT_REFERRAL_POLICY_INVALID");}
    }
    public BigDecimal price() {var value=prices.latestNexUsdtPrice();return value!=null&&value.signum()>0?value:null;}
    public Map<String,Object> current() {
        var p=at(now());var s=scope();Map<String,Object> out=new LinkedHashMap<>();
        out.put("source","server");out.put("serverCanonical",true);out.put("sourceEnvironment",s.sourceEnvironment());out.put("runId",s.runId());
        out.put("configured",p.policyVersion()>0);out.put("policyVersion",p.policyVersion());out.put("effectiveAt",p.effectiveAt());
        out.put("nexUsdtPrice",price());out.put("purchase",p.purchase());out.put("deviceEarning",p.deviceEarning());return out;
    }
    public static void validate(DirectReferralPolicyRequest request,Instant now) {
        if(request==null||request.expectedVersion()==null||request.expectedVersion()<0
                ||request.purchase()==null||request.deviceEarning()==null||request.reason()==null
                ||request.reason().trim().length()<8||request.reason().trim().length()>200) throw new BizException(422,"DIRECT_REFERRAL_POLICY_REQUEST_INVALID");
        request.purchase().validate();request.deviceEarning().validate();
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> publish(String key,DirectReferralPolicyRequest request) {
        validate(request,clock.instant());
        if(key==null||key.isBlank())throw new BizException(400,"IDEMPOTENCY_KEY_REQUIRED");
        if(!A2ReplayContext.isReplaying()||A2ReplayContext.operationId()==null)throw new BizException(409,"A2_CONFIRMATION_REQUIRED");
        config.activeValueForUpdate(VERSION_KEY).orElseThrow(()->new BizException(503,"DIRECT_REFERRAL_VERSION_UNAVAILABLE"));
        long version=mapper.latestVersion();
        if(version!=request.expectedVersion())throw new BizException(409,"DIRECT_REFERRAL_VERSION_CONFLICT");
        var before=at(now());
        if(request.purchase().amplifies(before.purchase())||request.deviceEarning().amplifies(before.deviceEarning())){
            var c=coverage.snapshot();
            if(c==null||!c.reliable()||c.coverageRatio()==null||c.redlinePct()==null)throw new BizException(503,"COVERAGE_SNAPSHOT_UNAVAILABLE");
            if(c.coverageRatio().compareTo(c.redlinePct())<0)throw new BizException(409,"COVERAGE_BELOW_REDLINE");
        }
        Instant effective=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try {
            if(mapper.insertPolicy(version+1,LocalDateTime.ofInstant(effective,DateTimeFormatConfig.BUSINESS_ZONE),
                    json.writeValueAsString(request.purchase()),json.writeValueAsString(request.deviceEarning()),
                    A2ReplayContext.operationId(),request.reason().trim())!=1)throw new BizException(409,"DIRECT_REFERRAL_VERSION_CONFLICT");
        } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        config.upsertAdminValue(VERSION_KEY,Long.toString(version+1),"NUMBER","team","直属分成版本锁");
        audit.recordRequired(AuditLogWriteRequest.builder().action("F_DIRECT_REFERRAL_POLICY_APPROVED").resourceType("TEAM_POLICY")
                .resourceId("direct_referral_policy/current").actorUsername(AdminActorResolver.resolve("A2"))
                .riskLevel("HIGH").detail(Map.of("policyVersion",version+1,"effectiveAt",effective.toString(),"request",request,"operationId",A2ReplayContext.operationId())).build());
        var result=current();result.put("approvedPolicyVersion",version+1);result.put("approvedEffectiveAt",effective);return result;
    }
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE);}
}
