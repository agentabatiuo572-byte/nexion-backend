package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.common.boundary.PhoneReplacementAdminReadContract;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.PhoneReplacementMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PhoneReplacementAdminReadService implements PhoneReplacementAdminReadContract {
    private final PhoneReplacementService domain;
    private final PhoneReplacementMapper mapper;
    private final OnboardingCalibrationMapper calibration;
    private final OnboardingCalibrationService onboarding;
    private final WheelSandboxProfile runtime;
    private final Environment environment;
    private final Clock clock;
    private final ffdd.opsconsole.shared.security.CurrentAppSessionService sessions;

    public Scope scope() {
        runtime.requireKnownRuntime();
        UserAuthEnvironment.resolve(environment).orElseThrow(()->new BizException(503,"ONBOARDING_RUNTIME_PROFILE_UNSUPPORTED"));
        return runtime.mode()==WheelSandboxProfile.Mode.SANDBOX?new Scope("SANDBOX",runtime.requireRunId()):new Scope("PRODUCTION","");
    }

    public Account account(long userId) {
        runtime.requireKnownRuntime();
        var audience=UserAuthEnvironment.resolve(environment)
                .orElseThrow(()->new BizException(503,"ONBOARDING_RUNTIME_PROFILE_UNSUPPORTED"));
        if(!audience.acceptsSandbox(mapper.adminUserSandbox(userId)))
            throw new BizException(404,"USER_NOT_FOUND");
        return runtime.mode()==WheelSandboxProfile.Mode.SANDBOX
                ?new Account(userId,"SANDBOX",runtime.requireRunId()):Account.production(userId);
    }

    @Transactional
    public Map<String,Object> overview(Account account) {
        // Shared eligibility checks lock runtime/risk rows. Follow activation order first, without creating facts.
        mapper.lockPolicy();
        mapper.lockUser(account.userId());
        var binding=mapper.lockBinding(account);
        var sessionViews=sessions.adminSummary(account.userId());
        var current=sessionViews.stream().filter(s->"APP".equals(s.sessionGroup())).findFirst().orElse(null);
        String pause=binding==null?null:calibration.phoneReplacementHardPause(account.userId(),binding.userDeviceId());
        var blocks=new ArrayList<String>();
        if(pause!=null)blocks.add("PHONE_REPLACEMENT_RISK_PAUSED");
        if(current!=null)blocks.addAll(onboarding.activationBlockReasons(account.userId(),current.installationId()));
        var result=new LinkedHashMap<String,Object>();
        result.put("eligibility",domain.eligibility(account,current==null?null:current.installationId(),null,
                current!=null && current.executionSupported(),blocks));
        result.put("currentApp",current);
        result.put("sessions",sessionViews);
        result.put("pendingRequest",domain.currentRequest(account,null));
        result.put("pendingOperations",mapper.pendingOperations(account));
        result.put("executionPauseReason",binding==null?null:mapper.executionPauseReason(account,binding.userDeviceId()));
        return result;
    }

    public Request request(Account account,String requestId) {
        return domain.request(account,requestId);
    }

    public List<Map<String,Object>> grants(Account account,int offset,int limit) {
        var rows=domain.grants(account,offset,limit).stream().map(PhoneReplacementAdminReadService::fields).toList();
        var metadata=mapper.grantMetadata(account,offset,limit);
        for(var row:rows) metadata.stream().filter(m->Objects.equals(m.get("grantId"),row.get("grantId"))).findFirst().ifPresent(row::putAll);
        return withAudit(account,rows,"grantId");
    }
    public List<Map<String,Object>> requestHistory(Account account,int offset,int limit) {
        return withAudit(account,domain.requests(account,offset,limit).stream().map(PhoneReplacementAdminReadService::fields).toList(),"requestId");
    }
    private List<Map<String,Object>> withAudit(Account account,List<Map<String,Object>> rows,String idField) {
        if(rows.isEmpty())return rows;
        var operations=mapper.resourceOperations(account,rows.stream().map(r->(String)r.get(idField)).toList());
        for(var row:rows)row.put("operations",operations.stream().filter(o->Objects.equals(o.get("resourceId"),row.get(idField))).toList());
        return rows;
    }
    private static Map<String,Object> fields(Object value) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
    }
}
