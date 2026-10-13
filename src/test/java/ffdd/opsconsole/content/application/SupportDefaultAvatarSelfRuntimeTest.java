package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.auth.application.*;
import ffdd.opsconsole.platform.domain.*;
import ffdd.opsconsole.platform.dto.*;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.audit.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** Opt-in real SQL/HTTP/S3 regression, no login/MFA session or whole-I6 acceptance claim. */
@EnabledIfEnvironmentVariable(named="CS_ENHANCE_DEFAULT_AVATAR_SELF_ENABLED",matches="true")
@EnabledIfEnvironmentVariable(named="CS_ENHANCE_BULK_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportObjectEvidenceLedger.Configuration.class,
        SupportDefaultAvatarSelfRuntimeTest.AuditFailureConfiguration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportDefaultAvatarSelfRuntimeTest extends SupportBulkRuntimeFixture {
    static final String ACCOUNTS="/api/admin/platform/accounts",SELF=ACCOUNTS+"/self/avatar";
    static final String METHOD="defaultCreationAndAvatarOnlySelfCommandsPersistAndFailuresPreserveOriginal";
    static final AtomicReference<String> FAILED_AUDIT_ACTOR=new AtomicReference<>();
    static final AtomicReference<AuditLogWriteRequest> FAILED_AUDIT_WRITE=new AtomicReference<>();
    @Autowired SupportSelfAvatarService selfAvatars;
    @Autowired AdminAccountStateMapper accountStates;
    @Autowired AdminMfaCipher mfaCipher;
    @Autowired AdminTotpService totp;
    private SupportGroupRuntimeFixtures ownedGroups;
    private final Map<Long,String> actorTokens=new LinkedHashMap<>();
    private final Set<String> pendingTickets=new LinkedHashSet<>();
    private final Set<String> ownAssetIds=new LinkedHashSet<>();
    private long checker;
    private boolean entered,validated;

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }
    /** Fault injection surrounds the real audit service, never replaces its mapper/storage/permissions. */
    @TestConfiguration(proxyBeanMethods=false)
    static class AuditFailureConfiguration {
        @Bean static BeanPostProcessor requiredAuditOwnedFault() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof AuditLogService))return bean;
                    var proxy=new ProxyFactory(bean);proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor)invocation->{
                        var authentication=SecurityContextHolder.getContext().getAuthentication();
                        String actor=authentication==null?null:String.valueOf(authentication.getPrincipal());
                        if("recordRequired".equals(invocation.getMethod().getName()) && actor!=null
                                && actor.equals(FAILED_AUDIT_ACTOR.get()) && invocation.getArguments()[0] instanceof AuditLogWriteRequest write
                                && Set.of("SUPPORT_SELF_AVATAR_UPDATED","SUPPORT_DEFAULT_AVATAR_PREPARED","A1_OPERATOR_CREATED").contains(write.getAction())) {
                            FAILED_AUDIT_WRITE.set(write);throw new IllegalStateException("NATIVE_OWNED_AUDIT_FAILURE");
                        }
                        return invocation.proceed();
                    });return proxy.getProxy();
                }
            };
        }
    }
    @BeforeEach void prepareOwnedActors() {
        boundary();entered=true;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",Long.class)).isEqualTo(377L);
        assertThat(System.getenv("WORKFLOW_RUN_ID")).isNotBlank();assertThat(System.getenv("WORKFLOW_SNAPSHOT_HASH")).isNotBlank();
        boss=admin("defaultboss","SUPER_ADMIN","MANAGER");checker=admin("defaultchecker","SUPER_ADMIN","MANAGER");
        first=admin("legacyself","SUPPORT","DEDICATED");second=admin("otheractor","SUPPORT","DEDICATED");
        for(long actor:List.of(boss,checker)) {
            fixtureActors().creationReference(actor);jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",actor);
            accountStates.upsertMfaBinding(actor,mfaCipher.encrypt(totp.generateSecret()),LocalDateTime.now());
            permissions.evict(actor);assertThat(permissions.getPermissionCodes(actor)).contains("platform_a1_write","platform_a2_operation_approve");
        }
        for(long actor:List.of(first,second)) {
            permissions.evict(actor);assertThat(permissions.getPermissionCodes(actor)).doesNotContain("platform_a1_read","platform_a1_write");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_account_state WHERE admin_id=?",Long.class,actor)).isZero();
        }
        ownedGroups=new SupportGroupRuntimeFixtures(fixtureActors(),jdbc,groupMapper,groupService,Set::of);
        ownedGroups.asSuper(boss,()->{ownedGroups.serviceMember(first);ownedGroups.serviceMember(second);});
    }

    @Test void defaultCreationAndAvatarOnlySelfCommandsPersistAndFailuresPreserveOriginal() throws Exception {
        Map<String,Object> schemaBefore=schemaShape();
        assertThat(json.readTree(getClass().getClassLoader().getResourceAsStream("support/default-avatars/manifest.json")).path("entries")).hasSize(8);
        var created=new ArrayList<Long>();
        for(String gender:List.of("MALE","FEMALE","UNSPECIFIED")) {
            String command=key();var request=createRequest(ownUsername(gender.toLowerCase()),gender);
            JsonNode initial=createDefault(boss,command,request,null,null,false,true);success(initial);
            long id=initial.path("data").path("id").asLong();created.add(id);
            var original=accountState(id);String asset=String.valueOf(original.get("avatar_asset_id"));
            assertThat(ownAssetIds.add(asset)).as("Every real new account has an independent asset").isTrue();
            assertThat(original.get("avatar_gender")).isEqualTo(gender);assertThat(((Number)original.get("avatar_version")).longValue()).isEqualTo(1L);
            assertAttachedDefault(id,asset,gender);assertImage(ACCOUNTS+"/"+id+"/avatar",boss,512,null);
            JsonNode replay=createDefault(boss,command,request,null,null,false,true);success(replay);
            assertThat(replay.path("data").path("id").asLong()).isEqualTo(id);assertThat(accountState(id)).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE attached_admin_id=?",Long.class,id)).isEqualTo(1L);
        }
        long defaultAssetCount=jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=?",Long.class,boss);
        long female=created.get(1);var beforeRole=accountState(female);
        for(String role:List.of("finance","support")) {
            var current=overview(female);
            success(jsonCall("PATCH",ACCOUNTS+"/"+female+"/role",boss,new AdminAccountRoleUpdateRequest(role,
                    "Native ordinary role change without redraw",username(boss),current.path("version").asText()),key()));
            assertThat(accountState(female)).isEqualTo(beforeRole);
        }
        long male=created.get(0);var beforeEdit=accountState(male);var account=overview(male);
        success(jsonCall("PATCH",ACCOUNTS+"/"+male+"/profile",boss,new AdminAccountProfileUpdateRequest(
                account.path("username").asText(),"Renamed without redraw",account.path("email").asText(),"Native ordinary profile edit",username(boss),account.path("version").asText(),null,"FEMALE"),key()));
        assertThat(accountState(male).get("avatar_asset_id")).isEqualTo(beforeEdit.get("avatar_asset_id"));
        assertThat(accountState(male).get("avatar_version")).isEqualTo(beforeEdit.get("avatar_version"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=?",Long.class,boss)).isEqualTo(defaultAssetCount);
        as(boss);var group=ownedGroups.create(boss,List.of(male),run+"_defaultgroup");
        assertThat(groupMapper.memberCurrent(male).groupId()).isEqualTo(group.id());
        assertThat(accountState(male).get("avatar_asset_id")).isEqualTo(beforeEdit.get("avatar_asset_id"));
        assertThat(accountState(male).get("avatar_version")).isEqualTo(beforeEdit.get("avatar_version"));
        // Existing real account without state: gender save initializes state and leaves the original NULL avatar alone.
        account=overview(first);success(jsonCall("PATCH",ACCOUNTS+"/"+first+"/profile",boss,new AdminAccountProfileUpdateRequest(
                account.path("username").asText(),account.path("name").asText(),account.path("email").asText(),"Native legacy gender initialization",username(boss),account.path("version").asText(),null,"FEMALE"),key()));
        assertThat(accountState(first).get("avatar_asset_id")).isNull();assertThat(((Number)accountState(first).get("avatar_version")).longValue()).isZero();
        // Original manual A1 upload/attach still uses the real route and preserves decoded pixels.
        String manual=upload(boss,ACCOUNTS+"/avatar-assets",0x224488);account=overview(first);
        success(jsonCall("PATCH",ACCOUNTS+"/"+first+"/profile",boss,new AdminAccountProfileUpdateRequest(
                account.path("username").asText(),account.path("name").asText(),account.path("email").asText(),"Native manual avatar attachment",username(boss),account.path("version").asText(),manual),key()));
        assertImage(SELF+"/content",first,4,0x224488);
        var original=snapshot(first);var originalSql=accountState(first);
        assertCode(jsonCall("GET",ACCOUNTS+"/overview",first,null,null),403);
        assertCode(jsonCall("POST",ACCOUNTS+"/avatar-assets/default",first,Map.of("avatarGender","MALE","clientUploadId",key()),key()),403);
        var prepared=prepareSelfDefault(first,"MALE");String next=prepared.path("assetId").asText();
        assertImage(SELF+"-assets/"+next,first,512,null);assertDefaultSource(next,"MALE",prepared.path("sourceHash").asText());
        assertCode(jsonCall("GET",SELF+"-assets/"+next,second,null,null),404);
        var foreignBefore=snapshot(second);
        assertCode(jsonCall("PATCH",SELF,second,Map.of("assetId",next,"expectedVersion",foreignBefore.path("accountVersion").asText(),"reason","Foreign asset denied"),key()),404);
        assertThat(snapshot(second)).isEqualTo(foreignBefore);
        assertThat(accountState(first)).isEqualTo(originalSql);
        assertCode(jsonCall("PATCH",SELF,first,Map.of("assetId",next,"expectedVersion",original.path("accountVersion").asText(),"reason","Extra target rejected","target",second),key()),400);
        assertCode(jsonCall("PATCH",SELF,first,Map.of("assetId",next,"expectedVersion",original.path("accountVersion").asText(),"reason","Extra name rejected","name","Injected"),key()),400);
        assertCode(jsonCall("PATCH",SELF,first,Map.of("assetId",next,"expectedVersion","0","reason","Stale version rejected"),key()),409);
        assertPreserved(first,originalSql,manual,4,0x224488);
        // A real required audit exception must roll back account CAS/reference, not merely return an error.
        FAILED_AUDIT_ACTOR.set(String.valueOf(first));as(first);
        try {assertThatThrownBy(()->selfAvatars.save(key(),new ffdd.opsconsole.content.dto.SupportAvatarRequests.SelfSave(next,original.path("accountVersion").asText(),"Native required audit failure"))).hasMessageContaining("NATIVE_OWNED_AUDIT_FAILURE");}
        finally {FAILED_AUDIT_ACTOR.set(null);SecurityContextHolder.clearContext();}
        assertThat(FAILED_AUDIT_WRITE.get().getAction()).isEqualTo("SUPPORT_SELF_AVATAR_UPDATED");
        assertThat(FAILED_AUDIT_WRITE.get().getDetail()).isInstanceOf(Map.class);
        assertThat(((Map<?,?>)FAILED_AUDIT_WRITE.get().getDetail()).get("toAvatarAssetId")).isEqualTo(next);
        assertPreserved(first,originalSql,manual,4,0x224488);
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?",String.class,next)).isEqualTo("READY");
        // Preserve the direct transaction assertion above and independently verify the real HTTP error boundary.
        FAILED_AUDIT_WRITE.set(null);FAILED_AUDIT_ACTOR.set(String.valueOf(first));
        try {
            var failedHttp=HttpClient.newHttpClient().send(request("PATCH",SELF,first,
                    Map.of("assetId",next,"expectedVersion",original.path("accountVersion").asText(),"reason","Native HTTP required audit failure"),key()),HttpResponse.BodyHandlers.ofString());
            assertThat(failedHttp.statusCode()).isEqualTo(500);
            assertCode(json.readTree(failedHttp.body()),500);
        } finally {FAILED_AUDIT_ACTOR.set(null);SecurityContextHolder.clearContext();}
        assertThat(FAILED_AUDIT_WRITE.get()).isNotNull();
        assertThat(FAILED_AUDIT_WRITE.get().getAction()).isEqualTo("SUPPORT_SELF_AVATAR_UPDATED");
        assertThat(((Map<?,?>)FAILED_AUDIT_WRITE.get().getDetail()).get("toAvatarAssetId")).isEqualTo(next);
        assertThat(snapshot(first)).isEqualTo(original);
        assertPreserved(first,originalSql,manual,4,0x224488);
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?",String.class,next)).isEqualTo("READY");
        String saveKey=key();var save=Map.of("assetId",next,"expectedVersion",snapshot(first).path("accountVersion").asText(),"reason","Native self avatar confirmed");
        success(jsonCall("PATCH",SELF,first,save,saveKey));var saved=snapshot(first);success(jsonCall("PATCH",SELF,first,save,saveKey));
        assertThat(snapshot(first)).isEqualTo(saved);assertThat(saved.path("assetId").asText()).isEqualTo(next);assertImage(SELF+"/content",first,512,null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='SUPPORT_SELF_AVATAR_UPDATED' AND resource_id=? AND result='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.idempotencyKey'))=?",Long.class,String.valueOf(first),saveKey)).isEqualTo(1L);
        // Self manual upload and cancellation are real storage commands without A1.
        String selfUpload=upload(first,SELF+"-assets",0xBB6622);assertImage(SELF+"-assets/"+selfUpload,first,4,0xBB6622);
        success(jsonCall("DELETE",SELF+"-assets/"+selfUpload,first,null,key()));assertThat(snapshot(first)).isEqualTo(saved);
        assertThat(storage.exists(objectKey(selfUpload))).isFalse();
        var beforeExpired=accountState(first);String expired=prepareSelfDefault(first,"FEMALE").path("assetId").asText();
        jdbc.update("UPDATE nx_support_admin_avatar_asset SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=? AND uploader_id=? AND state='READY'",expired,first);
        assertCode(jsonCall("PATCH",SELF,first,Map.of("assetId",expired,"expectedVersion",saved.path("accountVersion").asText(),"reason","Expired candidate rejected"),key()),409);
        assertPreserved(first,beforeExpired,next,512,null);assertThat(snapshot(first)).isEqualTo(saved);
        var retained=accountState(first);fixtureActors().creationReference(first);jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",first);
        try {assertCode(jsonCall("GET",SELF,first,null,null),403);assertThat(accountState(first)).isEqualTo(retained);}
        finally {jdbc.update("UPDATE nx_admin SET status=1 WHERE id=? AND is_deleted=0",first);}
        as(boss);var qualification=groupMapper.qualification(first,"SERVICE");
        success(json.valueToTree(ownedGroups.qualification(first,key(),new ffdd.opsconsole.content.dto.SupportGroupRequests.Qualification("SERVICE","DISABLED",qualification.version(),accountVersion(first),"Native revoke self eligibility"))));
        assertCode(jsonCall("GET",SELF,first,null,null),403);assertThat(accountState(first)).isEqualTo(retained);
        ownedGroups.asSuper(boss,()->ownedGroups.serviceMember(first));
        assertImage(SELF+"/content",first,512,null);
        // Default creation A2 uses a distinct real maker/checker and the exact frozen command.
        createA2Default();
        String auditClient=key(),auditCommand=key();
        var auditPrepare=objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,first,null,null,auditClient,auditCommand,null,storageProperties.getBucket(),false);
        var beforePrepare=accountState(first);FAILED_AUDIT_ACTOR.set(String.valueOf(first));
        try {assertCode(json.readTree(sendObjectRequest(auditPrepare,request("POST",SELF+"-assets/default",first,Map.of("avatarGender","FEMALE","clientUploadId",auditClient),auditCommand)).body()),500);}
        finally {FAILED_AUDIT_ACTOR.set(null);}
        assertThat(FAILED_AUDIT_WRITE.get().getAction()).isEqualTo("SUPPORT_DEFAULT_AVATAR_PREPARED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=? AND client_upload_id=?",Long.class,first,auditClient)).isZero();
        assertRolledBackObjects(auditCommand);assertPreserved(first,beforePrepare,next,512,null);
        // Warmed original storage, exact independently absent bucket: failed default prepare cannot clear current image.
        String normal=storageProperties.getBucket(),negative=normal+"-absent-"+UUID.randomUUID().toString().substring(0,8);
        var beforeStorage=accountState(first);String client=key(),command=key();
        var negativeIntent=objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,first,null,null,client,command,null,negative,true);
        storageProperties.setBucket(negative);
        try {assertCode(json.readTree(sendObjectRequest(negativeIntent,request("POST",SELF+"-assets/default",first,Map.of("avatarGender","MALE","clientUploadId",client),command)).body()),503);}
        finally {storageProperties.setBucket(normal);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=? AND client_upload_id=?",Long.class,first,client)).isZero();
        assertPreserved(first,beforeStorage,next,512,null);
        // A successful PUT followed by a mandatory creation-audit failure rolls back real new account and storage.
        String failedName=ownUsername("auditfail");var failed=createRequest(failedName,"FEMALE");String failedKey=key();
        var keysBefore=new LinkedHashSet<>(jdbc.queryForList("SELECT object_key FROM nx_support_admin_avatar_asset WHERE uploader_id=?",String.class,boss));
        FAILED_AUDIT_ACTOR.set(String.valueOf(boss));
        try {assertCode(createDefault(boss,failedKey,failed,null,null,false,true),500);}
        finally {FAILED_AUDIT_ACTOR.set(null);}
        assertThat(FAILED_AUDIT_WRITE.get().getAction()).isEqualTo("A1_OPERATOR_CREATED");
        assertThat(((Map<?,?>)FAILED_AUDIT_WRITE.get().getDetail()).get("defaultAvatarStatus")).isEqualTo("ATTACHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin WHERE username=?",Long.class,failedName)).isZero();
        assertThat(jdbc.queryForList("SELECT object_key FROM nx_support_admin_avatar_asset WHERE uploader_id=?",String.class,boss)).containsExactlyInAnyOrderElementsOf(keysBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='A1_OPERATOR_CREATED' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.idempotencyKey'))=?",Long.class,failedKey)).isZero();
        assertRolledBackObjects(failedKey);
        assertThat(schemaShape()).isEqualTo(schemaBefore);assertThat(jdbc.queryForObject("SELECT avatar_gender IS NULL FROM nx_admin_account_state WHERE admin_id=?",Boolean.class,checker)).isTrue();
        proofs.put("default-avatar-self",Map.of("status","pass","suite",getClass().getSimpleName(),"testcase",METHOD,"method",METHOD,
                "evidence","Real HTTP creation/default sources, independent assets/replays, original manual and A2, avatar-only self commands, CAS/extra-field/foreign404/disable/revoke/expiry/audit/storage failures with fresh SQL/content readback; full shared captures and creator-owned cleanup remain required."));validated=true;
    }

    private void createA2Default() throws Exception {
        String name=ownUsername("a2");String reason="Native distinct checker default approval";
        var params=new LinkedHashMap<String,Object>();params.put("username",name);params.put("displayName","Native A2 default");params.put("email",name+"@example.invalid");params.put("role","support");params.put("avatarGender","MALE");params.put("useDefaultAvatar",true);
        var proposal=new AuditOperationProposalRequest("后台账号头像(A1)",name,"No account","Default avatar",username(boss),"超管","acct",false,false,"超管","Native A2 default request", "A",new AuditReplayCommand("A","a1_account_create",params),new AuditLockTarget("A","account",name),null);
        var proposed=jsonCall("POST","/api/admin/platform/audit/operations",boss,proposal,key());success(proposed);String ticket=proposed.path("data").path("id").asText();pendingTickets.add(ticket);
        String frozen=jdbc.queryForObject("SELECT command_json FROM nx_audit_operation_ticket WHERE operation_id=?",String.class,ticket);
        assertThat(json.readTree(frozen).path("avatarMakerAdminId").asLong()).isEqualTo(boss);
        assertCode(jsonCall("POST","/api/admin/platform/audit/operations/"+ticket+"/approve",boss,Map.of("reason",reason,"operator","forged"),key()),403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin WHERE username=?",Long.class,name)).isZero();
        var create=new AdminAccountCreateRequest(name,"Native A2 default",name+"@example.invalid","support",null,reason,username(checker),null,null,"MALE",true);
        String command=key();success(createDefault(checker,command,create,ticket,frozen,false,true));pendingTickets.remove(ticket);
        long target=fixtureActors().actorForCommand(command);String asset=String.valueOf(accountState(target).get("avatar_asset_id"));assertThat(ownAssetIds.add(asset)).isTrue();assertAttachedDefault(target,asset,"MALE");
        success(createDefault(checker,command,create,ticket,frozen,false,true));assertImage(ACCOUNTS+"/"+target+"/avatar",boss,512,null);
    }
    private JsonNode createDefault(long actor,String command,AdminAccountCreateRequest create,String ticket,String frozen,boolean missing,boolean own) throws Exception {
        var intent=objects().request(new SupportObjectEvidenceLedger.Request(getClass().getSimpleName(),METHOD,SupportObjectEvidenceLedger.Kind.DEFAULT_CREATE_AVATAR,"ADMIN",actor,null,null,null,command,null,storageProperties.getBucket(),missing,new SupportObjectEvidenceLedger.DefaultCreateCommand(create,ticket,frozen)));
        SupportFixtureActors.ResponseCall action=()->json.readTree(sendObjectRequest(intent,request("POST",ticket==null?ACCOUNTS:"/api/admin/platform/audit/operations/"+ticket+"/approve",actor,ticket==null?create:Map.of("reason",create.reason(),"operator","forged"),command)).body());
        if(!own)return action.execute();
        return ticket==null?fixtureActors().createHttp(create.username(),command,action):fixtureActors().createApproval(create.username(),ticket,actor,command,action);
    }
    private AdminAccountCreateRequest createRequest(String name,String gender) {return new AdminAccountCreateRequest(name,"Native default",name+"@example.invalid","support",null,"Native explicit default avatar",username(boss),null,null,gender,true);}
    private String ownUsername(String label) {return "av_"+run.substring(5)+"_"+label;}
    private String username(long id) {return jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);}
    private long accountVersion(long actor) {return jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,actor);}
    private Map<String,Object> accountState(long actor) {return jdbc.queryForMap("SELECT avatar_asset_id,avatar_version,avatar_gender FROM nx_admin_account_state WHERE admin_id=?",actor);}
    private String objectKey(String asset) {return jdbc.queryForObject("SELECT object_key FROM nx_support_admin_avatar_asset WHERE id=?",String.class,asset);}
    private void assertRolledBackObjects(String command) throws Exception {
        var keys=new ArrayList<String>();
        try(var files=Files.list(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR")).resolve("object-ledger"))) {
            for(var file:files.toList()) {
                var entry=json.readTree(Files.readAllBytes(file));
                if("PUT_INTENT".equals(entry.path("eventType").asText()) && getClass().getSimpleName().equals(entry.path("suite").asText())
                        && command.equals(entry.path("payload").path("request").path("commandKey").asText()))keys.add(entry.path("object").path("key").asText());
            }
        }
        assertThat(keys).hasSize(1);
        for(String key:keys) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE object_key=?",Long.class,key)).isZero();
            assertThat(storage.exists(key)).as("Natural rollback compensated this exact staged object").isFalse();
        }
    }
    private JsonNode snapshot(long actor) throws Exception {var result=jsonCall("GET",SELF,actor,null,null);success(result);return result.path("data");}
    private JsonNode overview(long actor) throws Exception {var response=jsonCall("GET",ACCOUNTS+"/overview",boss,null,null);success(response);for(var row:response.path("data").path("operators"))if(row.path("id").asLong()==actor)return row;throw new AssertionError("Own actor missing from original overview");}
    private HttpRequest request(String method,String path,long actor,Object body,String command) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+actorTokens.computeIfAbsent(actor,this::token));
        if(command!=null)builder.header("Idempotency-Key",command);
        if(body!=null)builder.header("Content-Type","application/json");
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private JsonNode jsonCall(String method,String path,long actor,Object body,String command) throws Exception {
        var response=HttpClient.newHttpClient().send(request(method,path,actor,body,command),HttpResponse.BodyHandlers.ofString());
        return checkedHttpResult(response);
    }
    @Override HttpResponse<String> sendObjectRequest(SupportObjectEvidenceLedger.Intent intent,HttpRequest request) throws Exception {
        var response=super.sendObjectRequest(intent,request);
        checkedHttpResult(response);return response;
    }
    private JsonNode checkedHttpResult(HttpResponse<String> response) throws Exception {
        var result=json.readTree(response.body());
        assertThat(result.path("code").isIntegralNumber() && result.path("code").canConvertToInt()).as("Actual API integer code is required: %s",result).isTrue();
        int code=result.path("code").asInt();
        assertThat(code==0 || code>=400 && code<=599).as("Only explicit success/error API codes belong to this suite: %s",result).isTrue();
        assertThat(response.statusCode()).as("Actual HTTP status must agree with API code: %s",result).isEqualTo(code==0?200:code);
        return result;
    }
    private static void success(JsonNode response) {assertThat(response.path("code").asInt(-1)).as("Actual API result %s",response).isZero();}
    private static void assertCode(JsonNode response,int code) {assertThat(response.path("code").asInt(-1)).as("Actual API result %s",response).isEqualTo(code);}
    private JsonNode prepareSelfDefault(long actor,String gender) throws Exception {
        String client=key(),command=key();var intent=objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,actor,null,null,client,command,null,storageProperties.getBucket(),false);
        var body=Map.of("avatarGender",gender,"clientUploadId",client);var response=json.readTree(sendObjectRequest(intent,request("POST",SELF+"-assets/default",actor,body,command)).body());success(response);String asset=response.path("data").path("assetId").asText();ownAssetIds.add(asset);
        var replayIntent=objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,actor,null,null,client,command,null,storageProperties.getBucket(),false);
        var replay=json.readTree(sendObjectRequest(replayIntent,request("POST",SELF+"-assets/default",actor,body,command)).body());success(replay);assertThat(replay.path("data").path("assetId").asText()).isEqualTo(asset);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=? AND (client_upload_id=? OR idempotency_key=?)",Long.class,actor,client,command)).isEqualTo(1L);return response.path("data");
    }
    private String upload(long actor,String path,int color) throws Exception {
        String client=key(),command=key(),boundary="avatar-native-"+UUID.randomUUID();var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"clientUploadId\"\r\n\r\n"+client+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));bytes.write(png(color));bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var intent=objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,actor,null,null,client,command,null,storageProperties.getBucket(),false);
        var req=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+actorTokens.computeIfAbsent(actor,this::token)).header("Idempotency-Key",command).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        var response=json.readTree(sendObjectRequest(intent,req).body());success(response);String id=response.path("data").path("assetId").asText();ownAssetIds.add(id);return id;
    }
    private void assertAttachedDefault(long actor,String asset,String gender) throws Exception {
        var row=jdbc.queryForMap("SELECT state,attached_admin_id,request_hash FROM nx_support_admin_avatar_asset WHERE id=?",asset);
        assertThat(row.get("state")).isEqualTo("ATTACHED");assertThat(((Number)row.get("attached_admin_id")).longValue()).isEqualTo(actor);
        String source=null;var manifest=json.readTree(getClass().getClassLoader().getResourceAsStream("support/default-avatars/manifest.json"));
        for(var entry:manifest.path("entries"))if(SupportDefaultAvatarCatalog.sha256(("CREATE_DEFAULT|"+actor+"|"+entry.path("sha256").asText()).getBytes(StandardCharsets.UTF_8)).equals(row.get("request_hash")))source=entry.path("sha256").asText();
        assertThat(source).isNotNull();assertDefaultSource(asset,gender,source);
    }
    private void assertDefaultSource(String asset,String gender,String source) throws Exception {
        var manifest=json.readTree(getClass().getClassLoader().getResourceAsStream("support/default-avatars/manifest.json"));var matching=new ArrayList<JsonNode>();
        for(var entry:manifest.path("entries"))if(entry.path("sha256").asText().equals(source))matching.add(entry);
        assertThat(matching).hasSize(1);var entry=matching.get(0);assertThat(entry.path("reviewed").asBoolean()).isTrue();
        if(!"UNSPECIFIED".equals(gender))assertThat(entry.path("pool").asText()).isEqualTo(gender);
        byte[] actual;try(var stream=storage.get(objectKey(asset))){actual=stream.readAllBytes();}
        byte[] sourceBytes;try(var stream=getClass().getClassLoader().getResourceAsStream("support/default-avatars/"+entry.path("file").asText())){sourceBytes=stream.readAllBytes();}
        assertThat(SupportDefaultAvatarCatalog.sha256(sourceBytes)).isEqualTo(source);
        var encoded=SupportAttachmentService.decode(new SupportDefaultAvatarCatalog.ImageFile(entry.path("file").asText(),sourceBytes),new SupportAttachmentPolicy.View(true,List.of("image/png","image/jpeg"),1048576L,1000000L,300L,null));
        assertThat(SupportDefaultAvatarCatalog.sha256(actual)).isEqualTo(SupportDefaultAvatarCatalog.sha256(encoded.content()));
        var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(actual));assertThat(image).isNotNull();assertThat(image.getWidth()).isEqualTo(512);assertThat(image.getHeight()).isEqualTo(512);
    }
    private void assertImage(String path,long actor,int side,Integer color) throws Exception {
        var response=HttpClient.newHttpClient().send(request("GET",path,actor,null,null),HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("image/");assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body()));assertThat(image).isNotNull();assertThat(image.getWidth()).isEqualTo(side);assertThat(image.getHeight()).isEqualTo(side);if(color!=null)assertThat(image.getRGB(1,1)&0xffffff).isEqualTo(color);
    }
    private void assertPreserved(long actor,Map<String,Object> before,String asset,int side,Integer color) throws Exception {assertThat(accountState(actor)).isEqualTo(before);assertThat(snapshot(actor).path("assetId").asText()).isEqualTo(asset);assertThat(storage.exists(objectKey(asset))).isTrue();assertImage(SELF+"/content",actor,side,color);}
    private Map<String,Object> schemaShape() {return Map.of("columns",jdbc.queryForList("SELECT table_name,column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position"),"constraints",jdbc.queryForList("SELECT table_name,constraint_name,constraint_type FROM information_schema.table_constraints WHERE table_schema=DATABASE() ORDER BY table_name,constraint_name"));}
    @AfterEach void cleanOwnedEvidence() throws Exception {
        FAILED_AUDIT_ACTOR.set(null);if(!entered)return;
        SupportObjectEvidenceLedger.cleanupIndependently(
                ()->{for(String ticket:pendingTickets)try {assertCode(jsonCall("POST","/api/admin/platform/audit/operations/"+ticket+"/withdraw",boss,Map.of("reason","Native owned pending-ticket cleanup","operator",username(boss)),key()),0);}catch(Exception failure){throw new IllegalStateException(failure);}},
                ()->objects().cleanup(getClass().getSimpleName(),METHOD),()->{if(ownedGroups!=null)ownedGroups.cleanup();},
                ()->fixtureActors().cleanupAll(Set.of()),SecurityContextHolder::clearContext);
        if(validated)writeProof("default-avatar-self-runtime.json");
    }
}
