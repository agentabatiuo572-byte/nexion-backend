package ffdd.opsconsole.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.PaymentPasswordMapper;
import ffdd.opsconsole.auth.mapper.PaymentSecurityMapper;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PIN controls deliberately never touch login guards, sessions, or the login password. */
@Service
@RequiredArgsConstructor
public class OpsPaymentSecurityService {
    public static final String RELEASE="user_c5_payment_password_release";
    public static final String POLICY_WRITE="user_c5_payment_password_policy_write";
    private static final String EFFECT="仅解除钱包支付密码锁定并清零错误次数；保留支付密码和历史记录，银行及链上直付不受影响。";
    private static final String POLICY_EFFECT="新校验采用最新策略；已有锁定的截止时间保持不变，仅约束钱包支付和修改支付密码。";
    private final PaymentPasswordMapper credentials;
    private final PaymentSecurityMapper repo;
    private final SupportOwnershipService ownership;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public record Values(Integer maxAttempts,Integer windowMinutes,Integer lockMinutes) {}
    public record PolicyRequest(Values values,Long expectedVersion,String reason,Boolean confirmed) {}
    public record ReleaseRequest(String userId,String recordId,String operation,Long expectedVersion,String reason,Boolean confirmed) {}

    public Map<String,Object> policy() {
        Map<String,Object> row=credentials.policy(false);
        if(row==null) throw new BizException(503,"PAYMENT_PASSWORD_POLICY_UNAVAILABLE");
        return map("version",number(row,"version"),"values",values(row),"constraints",Map.of(
                "maxAttempts",Map.of("min",1,"max",20),"windowMinutes",Map.of("min",1,"max",1440),
                "lockMinutes",Map.of("min",1,"max",1440)),"effectDescription",POLICY_EFFECT,
                "editable",SupportOwnershipService.hasAuthority(POLICY_WRITE),"updatedAt",time(row.get("updated_at")),"updatedBy",row.get("updated_by"));
    }

    @Transactional
    public Map<String,Object> changePolicy(String key,PolicyRequest request) {
        requireAuthority(POLICY_WRITE); requireKey(key);
        if(request==null || request.values()==null || request.expectedVersion()==null || !Boolean.TRUE.equals(request.confirmed()))
            throw new BizException(422,"PAYMENT_PASSWORD_POLICY_INPUT_INVALID");
        requireReason(request.reason());
        Values v=request.values();
        if(!between(v.maxAttempts(),1,20)||!between(v.windowMinutes(),1,1440)||!between(v.lockMinutes(),1,1440))
            throw new BizException(422,"PAYMENT_PASSWORD_POLICY_INPUT_INVALID");
        long actor=ownership.actorId(); String hash=digest(encode(request));
        Map<String,Object> command=open(actor,"policy",key,0,null,hash);
        if(command.get("response_json")!=null) return decode(command.get("response_json"));
        Map<String,Object> old=credentials.policy(true);
        if(old==null) throw new BizException(503,"PAYMENT_PASSWORD_POLICY_UNAVAILABLE");
        String auditId=null,operator=AdminActorResolver.resolve("admin:"+actor),status="REJECTED";
        Long changed=null;
        if(number(old,"version")==request.expectedVersion()) {
            changed=clock.millis(); auditId=id("PINAUDIT");
            if(repo.policy(v.maxAttempts(),v.windowMinutes(),v.lockMinutes(),request.expectedVersion(),changed,operator)!=1)
                throw new BizException(409,"PAYMENT_PASSWORD_POLICY_VERSION_CONFLICT");
            recordAudit(auditId,"POLICY",0,operator,"PAYMENT_PASSWORD_POLICY_CHANGED",request.reason());
            status="APPLIED";
        }
        Map<String,Object> receipt=map("commandKey",key,"status",status,"auditId",auditId,"changedAt",time(changed),
                "changedBy",changed==null?null:operator,"reason",request.reason().trim(),"oldValues",values(old),
                "newValues",v,"effectDescription",POLICY_EFFECT);
        finish(actor,"policy",key,receipt); return receipt;
    }

    public Map<String,Object> policyReceipt(String key) { return receipt("policy",null,key); }

    public Map<String,Object> status(long userId,boolean support) {
        authorize(userId,support);
        Map<String,Object> row=credentials.credential(userId,false);
        boolean configured=row!=null&&row.get("password_hash")!=null;
        Long until=row==null?null:nullableLong(row.get("locked_until"));
        boolean locked=configured&&until!=null&&until>clock.millis();
        return map("userId",String.valueOf(userId),"state",locked?"LOCKED":configured?"SET":"UNSET",
                "updatedAt",row==null?null:time(row.get("changed_at")),"lockedUntil",locked?time(until):null,"observedAt",clock.instant());
    }

    public Map<String,Object> summary(long userId,boolean support) {
        authorize(userId,support); Map<String,Object> row=repo.latest(userId);
        return map("userId",String.valueOf(userId),"observedAt",clock.instant(),"record",row==null?null:view(row));
    }

    public Map<String,Object> list(Long userId,boolean support,String keyword,String state,String from,String to,int page,int size) {
        paging(page,size);
        if(userId!=null) authorize(userId,support);
        else if(support||ownership.currentSupportReader()) throw new BizException(403,"PAYMENT_PASSWORD_GLOBAL_READ_FORBIDDEN");
        String term=keyword==null?"":keyword.trim(),filter=state==null||"ALL".equals(state)?"":state.trim();
        if(term.length()>100||!Set.of("","ACTIVE","RELEASED","EXPIRED").contains(filter)) throw new BizException(422,"PAYMENT_PASSWORD_FILTER_INVALID");
        Long start=parseTime(from,false),end=parseTime(to,true); long now=clock.millis();
        if(start!=null&&end!=null&&start>end) throw new BizException(422,"PAYMENT_PASSWORD_FILTER_INVALID");
        return map("records",repo.records(userId,term,filter,start,end,now,size,(long)(page-1)*size).stream().map(this::view).toList(),
                "total",repo.count(userId,term,filter,start,end,now),"pageNum",page,"pageSize",size);
    }

    public Map<String,Object> detail(String record,Long scopedUser,boolean support) {
        Map<String,Object> row=ownedRecord(record,scopedUser,support,false); long user=number(row,"user_id");
        List<Map<String,Object>> events=repo.audits(record).stream().map(a -> map("id",a.get("audit_no"),"occurredAt",time(a.get("occurred_at")),
                "operator",a.get("operator"),"action",a.get("action"),"reason",a.get("reason"))).toList();
        return map("record",view(row),"attempts",attempts(user,support,1,10),"audit",events);
    }

    public Map<String,Object> recordAttempts(String record,Long scopedUser,boolean support,int page,int size) {
        return attempts(number(ownedRecord(record,scopedUser,support,false),"user_id"),support,page,size);
    }

    public Map<String,Object> attempts(long user,boolean support,int page,int size) {
        authorize(user,support); paging(page,size);
        List<Map<String,Object>> attempts=repo.attempts(user,size,(long)(page-1)*size).stream().map(row -> {
            String result=switch(((Number)row.get("result_code")).intValue()) {case 0 -> "SUCCEEDED";case 429 -> "LOCKED"; default -> "INCORRECT";};
            if("BUSINESS_REJECTED".equals(row.get("result_message"))) result="BUSINESS_REJECTED";
            return map("id",row.get("attempt_no"),"occurredAt",time(row.get("occurred_at")),"purpose",row.get("purpose"),
                    "result",result,"channel",row.get("channel"),"clientIpMasked",null);
        }).toList();
        return map("userId",String.valueOf(user),"records",attempts,"total",repo.attemptCount(user),"pageNum",page,"pageSize",size);
    }

    @Transactional
    public Map<String,Object> release(String record,Long scopedUser,boolean support,String key,ReleaseRequest request) {
        requireAuthority(RELEASE); requireKey(key);
        if(request==null || !record.equals(request.recordId()) || request.expectedVersion()==null
                || !Boolean.TRUE.equals(request.confirmed()) || !Set.of("RELEASE_LOCK","CLEAR_EXCEPTION").contains(request.operation()==null?"":request.operation()))
            throw new BizException(422,"PAYMENT_PASSWORD_RELEASE_INPUT_INVALID");
        requireReason(request.reason());
        Map<String,Object> snapshot=ownedRecord(record,scopedUser,support,false); long user=number(snapshot,"user_id");
        if(!String.valueOf(user).equals(request.userId())) throw new BizException(404,"PAYMENT_PASSWORD_RECORD_NOT_FOUND");
        long actor=ownership.actorId(); String surface=support?"support":"global";
        Map<String,Object> command=open(actor,surface,key,user,record,digest(encode(request)));
        if(command.get("response_json")!=null) return decode(command.get("response_json"));
        // Match the App lock order: credential, user/ownership, then security history.
        Map<String,Object> credential=credentials.credential(user,true);
        ownership.lockCustomer(user); authorize(user,support);
        Map<String,Object> row=ownedRecord(record,scopedUser,support,true);
        String status="REJECTED",auditId=null,operator=AdminActorResolver.resolve("admin:"+actor); Long changed=null;
        boolean lock="PAYMENT_PASSWORD_LOCK".equals(row.get("type"))&&"RELEASE_LOCK".equals(request.operation());
        boolean exception="PAYMENT_PASSWORD_EXCEPTION".equals(row.get("type"))&&"CLEAR_EXCEPTION".equals(request.operation());
        if((lock||exception) && active(row) && number(row,"version")==request.expectedVersion()
                && (exception || credential!=null&&Objects.equals(nullableLong(row.get("locked_until")),nullableLong(credential.get("locked_until"))))) {
            if(repo.release(record,user,request.expectedVersion())!=1) throw new BizException(409,"PAYMENT_PASSWORD_RECORD_VERSION_CONFLICT");
            if(lock) {credentials.clearAttemptFailures(user); credentials.clearFailures(user,clock.millis());}
            changed=clock.millis(); auditId=id("PINAUDIT"); status="APPLIED";
            recordAudit(auditId,record,user,operator,lock?"PAYMENT_PASSWORD_LOCK_RELEASED":"PAYMENT_PASSWORD_EXCEPTION_CLEARED",request.reason());
            row=repo.record(record,false);
        }
        Map<String,Object> result=map("commandKey",key,"status",status,"userId",String.valueOf(user),"recordId",record,
                "operation",request.operation(),"auditId",auditId,"changedAt",time(changed),"changedBy",changed==null?null:operator,
                "reason",request.reason().trim(),"record",view(row));
        finish(actor,surface,key,result); return result;
    }

    public Map<String,Object> releaseReceipt(Long user,boolean support,String key) {
        requireAuthority(RELEASE); return receipt(support?"support":"global",user,key);
    }

    private Map<String,Object> receipt(String surface,Long user,String key) {
        requireKey(key); if(user!=null) authorize(user,true);
        Map<String,Object> row=repo.command(ownership.actorId(),surface,key,false);
        if(row==null || user!=null&&number(row,"user_id")!=user) throw new BizException(404,"PAYMENT_PASSWORD_COMMAND_NOT_FOUND");
        if(!"policy".equals(surface)) authorize(number(row,"user_id"),"support".equals(surface));
        if(row.get("response_json")==null) throw new BizException(409,"PAYMENT_PASSWORD_COMMAND_PENDING");
        return decode(row.get("response_json"));
    }

    private Map<String,Object> ownedRecord(String id,Long user,boolean support,boolean lock) {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}")) throw new BizException(404,"PAYMENT_PASSWORD_RECORD_NOT_FOUND");
        if(user!=null) authorize(user,support);
        Map<String,Object> row=repo.record(id,lock);
        if(row==null||user!=null&&number(row,"user_id")!=user) throw new BizException(404,"PAYMENT_PASSWORD_RECORD_NOT_FOUND");
        authorize(number(row,"user_id"),support); return row;
    }

    private void authorize(long user,boolean support) {
        if(user<1) throw new BizException(404,"USER_NOT_FOUND");
        if(support||ownership.currentSupportReader()) ownership.requireRead(user);
        if(credentials.user(user,false)==null) throw new BizException(404,"USER_NOT_FOUND");
    }

    private Map<String,Object> view(Map<String,Object> row) {
        String state=String.valueOf(row.get("state"));
        if("ACTIVE".equals(state)&&!active(row)) state="EXPIRED";
        boolean allowed="ACTIVE".equals(state)&&Set.of("PAYMENT_PASSWORD_LOCK","PAYMENT_PASSWORD_EXCEPTION").contains(String.valueOf(row.get("type")))&&SupportOwnershipService.hasAuthority(RELEASE);
        return map("id",row.get("record_no"),"userId",String.valueOf(row.get("user_id")),"userLabel","user:"+row.get("user_id"),
                "version",number(row,"version"),"type",row.get("type"),"state",state,"reason",row.get("reason"),
                "startedAt",time(row.get("started_at")),"lockedUntil",time(row.get("locked_until")),
                "failedAttempts",row.get("failed_attempts"),"windowMinutes",row.get("window_minutes"),
                "capabilities",map("releaseAllowed",allowed,"disabledReason",allowed?null:"该记录已结束或当前没有钱包支付密码解除权限。",
                        "effectDescription","PAYMENT_PASSWORD_EXCEPTION".equals(row.get("type"))?"仅结束本条支付安全异常记录；保留支付密码、已有锁定和历史记录。":EFFECT));
    }
    private boolean active(Map<String,Object> row) {Long until=nullableLong(row.get("locked_until"));return "ACTIVE".equals(row.get("state"))&&(until!=null?until>clock.millis():"PAYMENT_PASSWORD_EXCEPTION".equals(row.get("type")));}
    private Map<String,Object> open(long actor,String surface,String key,long user,String record,String hash) {
        repo.createCommand(actor,surface,key,user,record,hash,clock.millis()); Map<String,Object> row=repo.command(actor,surface,key,true);
        if(row==null||!hash.equals(row.get("request_hash"))||number(row,"user_id")!=user||!Objects.equals(record,row.get("record_no")))
            throw new BizException(409,"PAYMENT_PASSWORD_COMMAND_INPUT_CHANGED");
        return row;
    }
    private void finish(long actor,String surface,String key,Map<String,Object> receipt) {repo.finish(actor,surface,key,encode(receipt));}
    private void recordAudit(String id,String record,long user,String operator,String action,String reason) {
        repo.audit(id,record,user,clock.millis(),operator,action,reason.trim());
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("USER_PAYMENT_PASSWORD")
                .resourceId(record).userId(user==0?null:user).riskLevel("HIGH").result("SUCCESS")
                .detail(Map.of("auditId",id,"reason",reason.trim())).build());
    }
    private static Map<String,Object> values(Map<String,Object> row) {return Map.of("maxAttempts",row.get("max_attempts"),"windowMinutes",row.get("window_minutes"),"lockMinutes",row.get("lock_minutes"));}
    private static void requireAuthority(String permission) {if(!SupportOwnershipService.hasAuthority(permission)) throw new BizException(403,"PAYMENT_PASSWORD_PERMISSION_DENIED");}
    private static void requireKey(String key) {if(key==null||!key.matches("[A-Za-z0-9_-]{8,128}")) throw new BizException(422,"PAYMENT_PASSWORD_COMMAND_KEY_INVALID");}
    private static void requireReason(String reason) {if(reason==null||reason.trim().length()<8||reason.trim().length()>200) throw new BizException(422,"PAYMENT_PASSWORD_REASON_INVALID");}
    private static boolean between(Integer n,int min,int max) {return n!=null&&n>=min&&n<=max;}
    private static void paging(int page,int size) {if(page<1||!Set.of(10,20,50).contains(size)) throw new BizException(422,"PAYMENT_PASSWORD_PAGE_INVALID");}
    private static Long parseTime(String value,boolean end) {try{
        if(value==null||value.isBlank()) return null;
        if(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            java.time.LocalDate date=java.time.LocalDate.parse(value);
            return BusinessCalendar.legacy().startOf(end?date.plusDays(1):date).toEpochMilli()-(end?1:0);
        }
        return Instant.parse(value).toEpochMilli();
    }catch(java.time.DateTimeException e){throw new BizException(422,"PAYMENT_PASSWORD_FILTER_INVALID");}}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static Long nullableLong(Object value) {return value==null?null:((Number)value).longValue();}
    private static Instant time(Object value) {return value==null?null:Instant.ofEpochMilli(((Number)value).longValue());}
    private static String id(String prefix) {return prefix+"-"+UUID.randomUUID().toString().replace("-","");}
    private static Map<String,Object> map(Object... pairs) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);return result;}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("PAYMENT_SECURITY_RECEIPT_INVALID",e);}}
    @SuppressWarnings("unchecked") private Map<String,Object> decode(Object value) {try{return json.readValue(String.valueOf(value),Map.class);}catch(JsonProcessingException e){throw new BizException(503,"PAYMENT_SECURITY_RECEIPT_UNAVAILABLE");}}
    private static String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
