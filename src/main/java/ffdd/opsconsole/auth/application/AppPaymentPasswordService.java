package ffdd.opsconsole.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.facade.PaymentAuthorizationFacade;
import ffdd.opsconsole.auth.mapper.PaymentPasswordMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** All PIN verification attempts commit separately from the purchase transaction. */
@Service
public class AppPaymentPasswordService implements PaymentAuthorizationFacade {
    private static final Set<String> PURCHASE_KINDS = Set.of("order-pay", "trial-convert", "trade-in",
            "capacity-replace", "capacity-keep");
    private final PaymentPasswordMapper repo;
    private final ObjectMapper json;
    private final Clock clock;
    private final UserOtpDeliveryService delivery;
    private final PlatformConfigFacade configs;
    private final AuditLogService audit;
    private final TransactionTemplate transaction, independent, nested;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    @SuppressWarnings("ArchitectureConfigField") // Bound explicitly by the constructor's @Value parameters.
    private final String pepper, pepperVersion;
    @SuppressWarnings("ArchitectureConfigField") // Bound explicitly by the constructor's @Value parameters.
    private final int maxAttempts, windowSeconds, lockSeconds;

    public AppPaymentPasswordService(PaymentPasswordMapper repo, ObjectMapper json, Clock clock,
            UserOtpDeliveryService delivery, PlatformConfigFacade configs, AuditLogService audit,PlatformTransactionManager manager,
            @Value("${nexion.auth.payment-password.pepper:}") String pepper,
            @Value("${nexion.auth.payment-password.pepper-version:v1}") String pepperVersion,
            @Value("${nexion.auth.payment-password.max-attempts:5}") int maxAttempts,
            @Value("${nexion.auth.payment-password.window-seconds:900}") int windowSeconds,
            @Value("${nexion.auth.payment-password.lock-seconds:900}") int lockSeconds) {
        this.repo=repo; this.json=json; this.clock=clock; this.delivery=delivery; this.configs=configs; this.audit=audit;
        this.pepper=pepper; this.pepperVersion=pepperVersion;
        this.maxAttempts=maxAttempts; this.windowSeconds=windowSeconds; this.lockSeconds=lockSeconds;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public record State(boolean configured, long credentialVersion, Instant lockedUntil,
                        int remainingAttempts, Instant serverTime) {}
    public record Setup(String newPassword, String confirmPassword) {
        @Override public String toString() { return "Setup[REDACTED]"; }
    }
    public record Change(String currentPassword, String newPassword, String confirmPassword,
                         Long expectedCredentialVersion, String attemptNo) {
        @Override public String toString() { return "Change[REDACTED]"; }
    }
    public record Reset(String resetProof, String newPassword, String confirmPassword,
                        Long expectedCredentialVersion) {
        @Override public String toString() { return "Reset[REDACTED]"; }
    }

    public State state(long userId) {
        requireAvailable();
        return stateOf(userId, repo.credential(userId, false));
    }

    private State stateOf(long userId, Map<String,Object> credential) {
        Policy policy=policy();
        Instant now=clock.instant();
        if (credential==null) return new State(false,0,null,policy.maxAttempts(),now);
        Instant until=PaymentPasswordMapper.instant(credential.get("locked_until"));
        if (until!=null && !until.isAfter(now)) return new State(credential.get("password_hash")!=null,
                PaymentPasswordMapper.number(credential,"credential_version"),null,policy.maxAttempts(),now);
        Instant since=now.minusSeconds(policy.windowSeconds());
        int errors=repo.failureCount(userId,since.toEpochMilli());
        return new State(credential.get("password_hash")!=null,
                PaymentPasswordMapper.number(credential,"credential_version"),until,
                until==null ? Math.max(0,policy.maxAttempts()-errors) : 0,now);
    }

    public ApiResult<Map<String,Object>> setup(long userId, String key, Setup request) {
        requireAvailable();
        if (request==null) throw new BizException(422,"PAYMENT_PASSWORD_REQUIRED");
        requireNew(request.newPassword(),request.confirmPassword());
        return command(userId,"setup",key,Map.of("operation","setup"),null,() -> {
            Map<String,Object> current=repo.credential(userId,true);
            if (current.get("password_hash")!=null) throw new BizException(409,"PAYMENT_PASSWORD_ALREADY_CONFIGURED");
            Map<String,Object> user=repo.user(userId,true);
            if (user==null) throw new BizException(401,"USER_AUTH_REQUIRED");
            String phone=verifiedPhone(userId,user);
            writePassword(userId,request.newPassword(),phone);
            auditMutation(userId,"USER_PAYMENT_PASSWORD_SETUP");
            return mutation(userId,key);
        });
    }

    public ApiResult<Map<String,Object>> change(long userId, String key, Change request) {
        requireAvailable();
        if (request==null) throw new BizException(422,"PAYMENT_PASSWORD_REQUIRED");
        requireNew(request.newPassword(),request.confirmPassword());
        return command(userId,"change",key,Map.of("operation","change",
                "version",request.expectedCredentialVersion()==null ? -1 : request.expectedCredentialVersion()),
                new Authorization(request.currentPassword(),request.expectedCredentialVersion(),request.attemptNo()),() -> {
            if (request.newPassword().equals(request.currentPassword()))
                throw new BizException(422,"PAYMENT_PASSWORD_NEW_MUST_DIFFER");
            Map<String,Object> user=repo.user(userId,true);
            if (user==null) throw new BizException(401,"USER_AUTH_REQUIRED");
            writePassword(userId,request.newPassword(),verifiedPhone(userId,user));
            auditMutation(userId,"USER_PAYMENT_PASSWORD_CHANGED");
            return mutation(userId,key);
        });
    }

    private ApiResult<Map<String,Object>> mutation(long userId,String key) {
        return ApiResult.ok(Map.of("commandKey",key,"status","SUCCEEDED","state",state(userId)));
    }

    private void writePassword(long userId,String password,String phoneHash) {
        repo.clearAttemptFailures(userId);
        repo.credentialUnlockAudit(userId,clock.millis());
        repo.closeSecurityRecords(userId,clock.millis());
        repo.writePassword(userId,encoder.encode(secret(password)),pepperVersion,clock.millis(),phoneHash);
    }

    @Override public <T> ApiResult<T> execute(Long userId,String kind,String key,Object summary,
                                             Authorization authorization,Supplier<ApiResult<T>> action) {
        if (!PURCHASE_KINDS.contains(kind)) throw new IllegalArgumentException("Unknown purchase command kind");
        return command(userId,kind,key,summary,authorization,action);
    }

    @Override public <T> ApiResult<T> execute(Long userId,String kind,String key,Object summary,
            Authorization authorization,Supplier<ApiResult<T>> existingSuccess,Supplier<ApiResult<T>> action) {
        if (!PURCHASE_KINDS.contains(kind)) throw new IllegalArgumentException("Unknown purchase command kind");
        return command(userId,kind,key,summary,authorization,existingSuccess,action);
    }

    private <T> ApiResult<T> command(long userId,String kind,String key,Object summary,
                                    Authorization authorization,Supplier<ApiResult<T>> action) {
        return command(userId,kind,key,summary,authorization,() -> null,action);
    }

    @SuppressWarnings("unchecked")
    private <T> ApiResult<T> command(long userId,String kind,String key,Object summary,
                                    Authorization authorization,Supplier<ApiResult<T>> existingSuccess,Supplier<ApiResult<T>> action) {
        requireKey(key);
        String digest=hash(String.valueOf(summary));
        independent.executeWithoutResult(tx -> {
            repo.ensure(userId);
            repo.insertCommand(userId,kind,key,digest,clock.millis());
            String legacyScope=legacyScope(userId,kind);
            if (legacyScope!=null) {
                Map<String,Object> legacy=repo.legacy(legacyScope,key);
                if (legacy!=null && "SUCCEEDED".equals(legacy.get("status")) && legacy.get("response_json")!=null) {
                    if (!digest.equals(legacy.get("request_hash"))) throw new BizException(409,"PAYMENT_COMMAND_INPUT_CHANGED");
                    ApiResult<?> receipt=decode(String.valueOf(legacy.get("response_json")),ApiResult.class);
                    repo.importCommand(userId,kind,key,receipt.getCode()==0 ? "SUCCEEDED" : "REJECTED",String.valueOf(legacy.get("response_json")));
                }
            }
        });
        return transaction.execute(tx -> {
            Map<String,Object> row=repo.command(userId,kind,key,false);
            if (!digest.equals(row.get("request_hash"))) throw new BizException(409,"PAYMENT_COMMAND_INPUT_CHANGED");
            if (row.get("response_json")!=null) return (ApiResult<T>)decode(String.valueOf(row.get("response_json")),ApiResult.class);
            ApiResult<T> existing=existingSuccess.get();
            if (existing!=null && existing.getCode()==0) {
                repo.finishCommand(userId,kind,key,"SUCCEEDED",encode(existing),clock.millis());
                return existing;
            }
            requireAvailable();
            if (!"setup".equals(kind) && !"reset".equals(kind)) {
                ApiResult<State> verification=independent.execute(v -> verify(userId,kind,key,authorization));
                if (verification.getCode()!=0) return (ApiResult<T>)(ApiResult<?>)verification;
                Map<String,Object> credential=repo.credential(userId,true);
                if (PaymentPasswordMapper.number(credential,"credential_version")!=authorization.credentialVersion())
                    return (ApiResult<T>)(ApiResult<?>)ApiResult.fail(409,"PAYMENT_PASSWORD_VERSION_CONFLICT",stateOf(userId,credential));
            } else repo.credential(userId,true);
            ApiResult<T> result;
            try {
                result=nested.execute(savepoint -> {
                    ApiResult<T> response=action.get();
                    if (response.getCode()!=0) savepoint.setRollbackOnly();
                    return response;
                });
            } catch (BizException rejection) {
                result=ApiResult.fail(rejection.getCode(),rejection.getMessage());
            }
            if (result.getCode()!=0 && authorization!=null && authorization.attemptNo()!=null)
                repo.rejectBusiness(userId,authorization.attemptNo());
            repo.finishCommand(userId,kind,key,result.getCode()==0 ? "SUCCEEDED" : "REJECTED",encode(result),clock.millis());
            return result;
        });
    }

    private ApiResult<State> verify(long userId,String kind,String key,Authorization authorization) {
        if (authorization==null || authorization.paymentPassword()==null || authorization.credentialVersion()==null
                || authorization.attemptNo()==null) return ApiResult.fail(422,"PAYMENT_PASSWORD_REQUIRED",state(userId));
        if (!authorization.paymentPassword().matches("[0-9]{6}") || !authorization.attemptNo().matches("[A-Za-z0-9:_-]{8,128}"))
            return ApiResult.fail(422,"PAYMENT_PASSWORD_INVALID",state(userId));
        Map<String,Object> current=repo.credential(userId,true);
        String binding=hash(kind+"|"+key+"|"+authorization.credentialVersion());
        Map<String,Object> prior=repo.attempt(userId,authorization.attemptNo());
        if (prior!=null) {
            if (!binding.equals(prior.get("binding_hash"))) return ApiResult.fail(409,"PAYMENT_PASSWORD_ATTEMPT_CONFLICT",stateOf(userId,current));
            int code=((Number)prior.get("result_code")).intValue();
            return code==0 ? ApiResult.fail(409,"PAYMENT_PASSWORD_ATTEMPT_ALREADY_USED",stateOf(userId,current))
                    : ApiResult.fail(code,code==429 ? "PAYMENT_PASSWORD_LOCKED" : String.valueOf(prior.get("result_message")),decode(String.valueOf(prior.get("state_json")),State.class));
        }
        State before=stateOf(userId,current);
        if (!before.configured()) return ApiResult.fail(409,"PAYMENT_PASSWORD_NOT_CONFIGURED",before);
        if (before.lockedUntil()!=null) return ApiResult.fail(429,"PAYMENT_PASSWORD_LOCKED",before);
        if (before.credentialVersion()!=authorization.credentialVersion()) return ApiResult.fail(409,"PAYMENT_PASSWORD_VERSION_CONFLICT",before);
        if (!pepperVersion.equals(current.get("pepper_version"))) return ApiResult.fail(503,"PAYMENT_PASSWORD_UNAVAILABLE",before);
        Policy policy=policy();
        Instant now=clock.instant();
        if (current.get("locked_until")!=null) {
            repo.clearFailures(userId,now.toEpochMilli());
            repo.clearAttemptFailures(userId);
        }
        boolean correct=encoder.matches(secret(authorization.paymentPassword()),String.valueOf(current.get("password_hash")));
        int code=correct ? 0 : 422;
        String message=correct ? "VERIFIED" : "PAYMENT_PASSWORD_INCORRECT";
        if (correct) {
            repo.clearFailures(userId,now.toEpochMilli());
            repo.clearAttemptFailures(userId);
        }
        repo.insertAttempt(userId,authorization.attemptNo(),binding,code,message,now.toEpochMilli(),"change".equals(kind) ? "CHANGE" : "PAYMENT");
        State after=stateOf(userId,repo.credential(userId,false));
        if (!correct && after.remainingAttempts()==0) {
            long until=now.plusSeconds(policy.lockSeconds()).toEpochMilli();
            repo.lockCredential(userId,until);
            repo.closeSecurityRecords(userId,clock.millis());
            repo.insertSecurityRecord("PINLOCK-"+UUID.randomUUID().toString().replace("-",""),userId,now.toEpochMilli(),until,policy.maxAttempts(),policy.windowSeconds()/60);
            after=stateOf(userId,repo.credential(userId,false));
            code=429; message="PAYMENT_PASSWORD_LOCKED";
        }
        repo.finishAttempt(userId,authorization.attemptNo(),code,encode(after));
        return new ApiResult<>(code,message,after);
    }

    public Map<String,Object> query(long userId,String kind,String key) {
        requireKey(key);
        if (!PURCHASE_KINDS.contains(kind) && !Set.of("setup","change","reset").contains(kind))
            throw new BizException(422,"PAYMENT_COMMAND_KIND_INVALID");
        try {
            return independent.execute(tx -> {
                Map<String,Object> row=repo.command(userId,kind,key,true);
                if (row==null || "READY".equals(row.get("status"))) {
                    String scope=legacyScope(userId,kind);
                    Map<String,Object> legacy=scope==null ? null : repo.legacy(scope,key);
                    if (legacy!=null) {
                        String legacyStatus=String.valueOf(legacy.get("status"));
                        if ("SUCCEEDED".equals(legacyStatus) && legacy.get("response_json")!=null) {
                            ApiResult<?> receipt=decode(String.valueOf(legacy.get("response_json")),ApiResult.class);
                            Map<String,Object> result=new LinkedHashMap<>();
                            result.put("kind",kind); result.put("commandKey",key);
                            result.put("status",receipt.getCode()==0 ? "SUCCEEDED" : "REJECTED");
                            result.put("canResumeSameKey",false); result.put("receipt",receipt.getData());
                            result.put("message",receipt.getMessage()); result.put("code",receipt.getCode());
                            return result;
                        }
                        if (Set.of("UNKNOWN","PROCESSING").contains(legacyStatus))
                            return Map.of("kind",kind,"commandKey",key,"status","PROCESSING".equals(legacyStatus) ? "PROCESSING" : "INDETERMINATE","canResumeSameKey",false);
                        if ("SUCCEEDED".equals(legacyStatus))
                            return Map.of("kind",kind,"commandKey",key,"status","INDETERMINATE","canResumeSameKey",false);
                    }
                }
                if (row==null || "READY".equals(row.get("status")))
                    return Map.of("kind",kind,"commandKey",key,"status","NOT_SEEN","canResumeSameKey",true);
                ApiResult<?> response=decode(String.valueOf(row.get("response_json")),ApiResult.class);
                Map<String,Object> result=new LinkedHashMap<>();
                result.put("kind",kind); result.put("commandKey",key); result.put("status",row.get("status"));
                result.put("canResumeSameKey",false); result.put("code",response.getCode());
                result.put("message",response.getMessage()); result.put("receipt",response.getData());
                if (response.getData() instanceof Map<?,?> data && data.containsKey("state")) result.put("state",data.get("state"));
                return result;
            });
        } catch (PessimisticLockingFailureException busy) {
            return Map.of("kind",kind,"commandKey",key,"status","PROCESSING","canResumeSameKey",false);
        } catch (ReceiptInvalidException invalid) {
            return Map.of("kind",kind,"commandKey",key,"status","INDETERMINATE","canResumeSameKey",false);
        }
    }

    private String legacyScope(long userId,String kind) {
        String operation=switch(kind) {
            case "order-pay" -> "ORDER_PAYMENT";
            case "trial-convert" -> "TRIAL_CONVERT:PRODUCTION";
            case "trade-in" -> "E3_TRADEIN_SUBMIT";
            case "capacity-replace" -> "E3_CAPACITY_REPLACE";
            case "capacity-keep" -> "E3_CAPACITY_KEEP";
            default -> null;
        };
        return operation==null ? null : "APP:"+operation+":USER:"+userId;
    }

    public Map<String,Object> securityCommand(long userId,String key) {
        requireKey(key);
        Map<String,Object> row=repo.securityCommand(userId,key);
        return row==null ? Map.of("commandKey",key,"status","NOT_SEEN","canResumeSameKey",true)
                : query(userId,String.valueOf(row.get("kind")),key);
    }

    public void recordVerifiedPhone(long userId,String country,String phone) {
        repo.recordPhone(userId,phoneHash(country,phone),clock.millis());
    }
    /** Only the existing current-password + old/new OTP change chain may transfer a PIN recovery channel. */
    public void transferVerifiedPhone(long userId,String country,String phone) {
        recordVerifiedPhone(userId,country,phone);
        repo.transferPhone(userId,phoneHash(country,phone));
    }
    private String verifiedPhone(long userId,Map<String,Object> user) {
        if (user.get("country_code")==null || user.get("phone")==null) return null;
        String digest=phoneHash(String.valueOf(user.get("country_code")),String.valueOf(user.get("phone")));
        Map<String,Object> verified=repo.verifiedPhone(userId);
        return verified!=null && digest.equals(verified.get("phone_hash")) ? digest : null;
    }

    public Map<String,Object> resetChallenge(long userId,String sessionId) {
        requireAvailable();
        return independent.execute(tx -> {
            repo.ensure(userId);
            Map<String,Object> credential=repo.credential(userId,true),user=recoveryUser(userId,credential);
            AppOtpPolicy policy=AppOtpPolicy.load(configs);
            Instant now=clock.instant();
            int recent=repo.countResetSince(userId,now.minusSeconds(policy.cooldownSeconds()).toEpochMilli());
            int daily=repo.countResetSince(userId,now.minusSeconds(86400).toEpochMilli());
            if (recent>0 || daily>=policy.max24h()) throw new BizException(429,"PAYMENT_PASSWORD_RESET_RATE_LIMITED");
            String country=String.valueOf(user.get("country_code")),phone=String.valueOf(user.get("phone"));
            if (!delivery.available(country)) throw new BizException(503,"PAYMENT_PASSWORD_RECOVERY_UNAVAILABLE");
            String challenge="PINRESET-"+UUID.randomUUID().toString().replace("-","");
            String code=delivery.verificationCode(country);
            Instant expires=now.plusSeconds(policy.ttlMinutes()*60L);
            repo.expireResets(userId,now.toEpochMilli());
            repo.insertReset(challenge,userId,hash(sessionId),String.valueOf(credential.get("recovery_phone_hash")),PaymentPasswordMapper.number(credential,"credential_version"),encoder.encode(secret(code)),expires.toEpochMilli(),now.toEpochMilli());
            delivery.deliver(country,phone,challenge,code,policy.ttlMinutes());
            return Map.of("challengeNo",challenge,"phoneMasked","****"+phone.substring(Math.max(0,phone.length()-4)),"resendAfterSec",policy.cooldownSeconds(),"expiresAt",expires);
        });
    }

    public ApiResult<Map<String,Object>> resetVerify(long userId,String sessionId,String challenge,String code) {
        requireAvailable();
        if (challenge==null || !challenge.matches("PINRESET-[a-f0-9]{32}") || code==null || !code.matches("[0-9]{6}"))
            throw new BizException(422,"PAYMENT_PASSWORD_RESET_CODE_INVALID");
        return independent.execute(tx -> {
            Map<String,Object> credential=repo.credential(userId,true);
            recoveryUser(userId,credential);
            Map<String,Object> row=repo.resetChallenge(userId,challenge);
            Instant now=clock.instant();
            if (row==null || !hash(sessionId).equals(row.get("session_hash")) || row.get("consumed_at")!=null
                    || row.get("verified_at")!=null || !PaymentPasswordMapper.instant(row.get("expires_at")).isAfter(now)
                    || !credential.get("recovery_phone_hash").equals(row.get("phone_hash"))
                    || PaymentPasswordMapper.number(credential,"credential_version")!=PaymentPasswordMapper.number(row,"credential_version")
                    || PaymentPasswordMapper.number(row,"attempt_count")>=AppOtpPolicy.load(configs).maxVerifyAttempts())
                return ApiResult.fail(422,"PAYMENT_PASSWORD_RESET_CODE_INVALID_OR_EXPIRED");
            repo.incrementReset(challenge);
            if (!encoder.matches(secret(code),String.valueOf(row.get("code_hash"))))
                return ApiResult.fail(422,"PAYMENT_PASSWORD_RESET_CODE_INVALID_OR_EXPIRED");
            String proof=UUID.randomUUID().toString()+UUID.randomUUID();
            Instant expires=now.plusSeconds(300);
            repo.verifyReset(challenge,now.toEpochMilli(),hash(proof),expires.toEpochMilli());
            return ApiResult.ok(Map.of("resetProof",proof,"expiresAt",expires));
        });
    }

    public ApiResult<Map<String,Object>> reset(long userId,String sessionId,String key,Reset request) {
        requireAvailable();
        if (request==null || request.resetProof()==null || request.resetProof().length()>128) throw new BizException(422,"PAYMENT_PASSWORD_RESET_PROOF_REQUIRED");
        requireNew(request.newPassword(),request.confirmPassword());
        return command(userId,"reset",key,Map.of("operation","reset","version",request.expectedCredentialVersion()==null ? -1 : request.expectedCredentialVersion()),null,() -> {
            Map<String,Object> credential=repo.credential(userId,true);
            recoveryUser(userId,credential);
            if (request.expectedCredentialVersion()==null || PaymentPasswordMapper.number(credential,"credential_version")!=request.expectedCredentialVersion()) throw new BizException(409,"PAYMENT_PASSWORD_VERSION_CONFLICT");
            Map<String,Object> proof=repo.resetProof(userId,hash(request.resetProof()));
            Instant now=clock.instant();
            if (proof==null || proof.get("consumed_at")!=null || !hash(sessionId).equals(proof.get("session_hash"))
                    || !PaymentPasswordMapper.instant(proof.get("proof_expires_at")).isAfter(now)
                    || !credential.get("recovery_phone_hash").equals(proof.get("phone_hash"))
                    || PaymentPasswordMapper.number(credential,"credential_version")!=PaymentPasswordMapper.number(proof,"credential_version")) throw new BizException(422,"PAYMENT_PASSWORD_RESET_PROOF_INVALID_OR_EXPIRED");
            repo.consumeReset(String.valueOf(proof.get("challenge_no")),now.toEpochMilli());
            writePassword(userId,request.newPassword(),String.valueOf(credential.get("recovery_phone_hash")));
            auditMutation(userId,"USER_PAYMENT_PASSWORD_RESET");
            return mutation(userId,key);
        });
    }

    private Map<String,Object> recoveryUser(long userId,Map<String,Object> credential) {
        Map<String,Object> user=repo.user(userId,true);
        String verified=user==null ? null : verifiedPhone(userId,user);
        if (credential==null || credential.get("password_hash")==null || verified==null
                || !verified.equals(credential.get("recovery_phone_hash"))) throw new BizException(422,"PAYMENT_PASSWORD_RECOVERY_UNAVAILABLE");
        return user;
    }

    private void auditMutation(long userId,String action) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action)
                .resourceType("USER_PAYMENT_PASSWORD").resourceId(String.valueOf(userId)).userId(userId)
                .actorId(userId).actorType("USER").actorUsername("user:"+userId).riskLevel("HIGH").result("SUCCESS")
                .detail(Map.of("credentialVersion",state(userId).credentialVersion())).build());
    }

    private record Policy(int maxAttempts,int windowSeconds,int lockSeconds) {}
    private Policy policy() {
        Map<String,Object> row=repo.policy(false);
        if(row==null) throw new BizException(503,"PAYMENT_PASSWORD_POLICY_UNAVAILABLE");
        Policy policy=new Policy(((Number)row.get("max_attempts")).intValue(),((Number)row.get("window_minutes")).intValue()*60,((Number)row.get("lock_minutes")).intValue()*60);
        if(policy.maxAttempts()<1||policy.maxAttempts()>20||policy.windowSeconds()<60||policy.windowSeconds()>86400
                ||policy.lockSeconds()<60||policy.lockSeconds()>86400) throw new BizException(503,"PAYMENT_PASSWORD_POLICY_UNAVAILABLE");
        return policy;
    }
    private void requireAvailable() {
        if (pepper==null || pepper.isBlank() || pepper.getBytes(StandardCharsets.UTF_8).length<32 || pepperVersion==null
                || !pepperVersion.matches("[A-Za-z0-9_-]{1,32}") || maxAttempts<1 || maxAttempts>20
                || windowSeconds<60 || lockSeconds<60) throw new BizException(503,"PAYMENT_PASSWORD_UNAVAILABLE");
    }
    private void requireKey(String key) {
        if (key==null || !key.matches("[A-Za-z0-9:_-]{8,128}")) throw new BizException(422,"PAYMENT_COMMAND_KEY_INVALID");
    }
    static void requireNew(String password,String confirmation) {
        if (password==null || !password.matches("[0-9]{6}")) throw new BizException(422,"PAYMENT_PASSWORD_INVALID");
        if (!password.equals(confirmation)) throw new BizException(422,"PAYMENT_PASSWORD_CONFIRM_MISMATCH");
        boolean identical=true,ascending=true,descending=true;
        for (int i=1;i<6;i++) {
            identical &= password.charAt(i)==password.charAt(0);
            ascending &= password.charAt(i)-password.charAt(i-1)==1;
            descending &= password.charAt(i)-password.charAt(i-1)==-1;
        }
        if (identical || ascending || descending) throw new BizException(422,"PAYMENT_PASSWORD_TOO_SIMPLE");
    }
    private String secret(String password) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(password.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String phoneHash(String country,String phone) { return hash(country.replace("+","")+"|"+phone); }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("PAYMENT_COMMAND_SERIALIZATION_FAILED",invalid); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); }
        catch (JsonProcessingException invalid) { throw new ReceiptInvalidException(invalid); }
    }
    private static final class ReceiptInvalidException extends IllegalStateException {
        ReceiptInvalidException(JsonProcessingException cause) { super("PAYMENT_COMMAND_RECEIPT_INVALID",cause); }
    }
}
