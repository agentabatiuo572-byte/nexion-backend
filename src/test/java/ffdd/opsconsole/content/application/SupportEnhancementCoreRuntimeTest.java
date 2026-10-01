package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.content.domain.SupportRandom;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.shared.security.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="CS_ENHANCE_CORE_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportEnhancementCoreRuntimeTest.EventCapture.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportEnhancementCoreRuntimeTest {
    @DynamicPropertySource static void boundary(DynamicPropertyRegistry registry) {SupportEnhancementPreparationTest.isolatedBoundary(registry);}
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate mybatisSession;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingRandomService random;
    @Autowired SupportBindingMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired ObjectMapper json;
    @Autowired AppSupportService app;
    @Autowired SupportCustomerProfileService profiles;
    @Autowired SupportHumanMessageService humanMessages;
    @Autowired org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired EventCapture events;
    @Autowired ffdd.opsconsole.platform.web.OpsAdminAccountController accounts;
    @Autowired SupportAttachmentPolicy attachmentPolicy;
    @Autowired ffdd.opsconsole.shared.storage.StorageProperties storageProperties;
    @Autowired AdminPermissionCache permissions;
    @Autowired ffdd.opsconsole.shared.storage.ObjectStorageService storage;
    @Autowired ffdd.opsconsole.onboarding.application.OnboardingCalibrationService onboarding;
    private final String run="enhance_"+UUID.randomUUID().toString().substring(0,8);
    private final List<Long> createdAdmins=new ArrayList<>();
    private List<Long> originallyEnabled;
    private ffdd.opsconsole.content.domain.SupportRules oldRules;
    private long boss,first,second;
    private final Map<String,Object> proofs=new LinkedHashMap<>();

    @BeforeEach void prepare() {
        events.events.clear();
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("cs_enhance_20261001");
        oldRules=mapper.rules();
        originallyEnabled=jdbc.queryForList("SELECT admin_id FROM nx_support_agent_profile WHERE enabled=1 AND is_deleted=0",Long.class);
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE enabled=1 AND is_deleted=0");
        boss=admin("boss","SUPER_ADMIN","MANAGER");first=admin("first","SUPPORT","DEDICATED");second=admin("second","SUPPORT","DEDICATED");
        as(boss);
    }
    @AfterEach void restore() {
        if(originallyEnabled!=null) originallyEnabled.forEach(id->jdbc.update("UPDATE nx_support_agent_profile SET enabled=1 WHERE admin_id=?",id));
        createdAdmins.forEach(id->{jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",id);jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",id);});
        if(oldRules!=null) jdbc.update("UPDATE nx_support_rules SET dormant_days=?,maintenance_days=?,activity_window_days=?,inheritance_mode=?,max_inheritance_depth=?,unbound_assignment_mode=?,mode_effective_at=?,version=version+1 WHERE id=1",
            oldRules.dormantDays(),oldRules.maintenanceDays(),oldRules.activityWindowDays(),oldRules.inheritanceMode(),oldRules.maxInheritanceDepth(),oldRules.unboundAssignmentMode(),oldRules.modeEffectiveAt());
        SecurityContextHolder.clearContext();
    }

    @Test void randomAllocationUsesFrozenDurableResultsAndOnlyNewAutomaticPool() throws Exception {
        String bossToken=token(boss),managerToken=token(admin("manager","SUPPORT","MANAGER"));
        rules("LIMITED",0,"SUPERVISOR");
        var before=mapper.rules();String key=key();
        var request=new LinkedHashMap<String,Object>();request.put("dormantDays",null);request.put("maintenanceDays",null);request.put("activityWindowDays",null);
        request.put("inheritanceMode","LIMITED");request.put("maxInheritanceDepth",0);request.put("unboundAssignmentMode","AUTO_RANDOM");
        request.put("expectedVersion",before.version());request.put("reason","New automatic allocation runtime proof");
        assertThat(http("PUT","/api/admin/content/support-agents/rules",managerToken,request,key()).path("code").asInt()).isEqualTo(403);
        long oldPool=customer(null);
        assertThat(http("PUT","/api/admin/content/support-agents/rules",bossToken,request,key).path("code").asInt()).isZero();
        var refreshed=http("GET","/api/admin/content/support-agents/rules",bossToken,null,null).path("data");
        assertThat(refreshed.path("unboundAssignmentMode").asText()).isEqualTo("AUTO_RANDOM");assertThat(refreshed.path("modeEffectiveAt").asText()).isNotBlank();
        assertThat(mapper.current(oldPool)).isNull();assertThat(mapper.autoEligible(oldPool)).isFalse();
        proof("A01","HTTP write and refreshed version/mode; manager denied");proof("A05","Existing pool not adopted by mode change");

        long root=customer(null);var rootAssignment=mapper.current(root);assertThat(rootAssignment.source()).isEqualTo("RANDOM");
        long exceeded=customer(root);var newSegment=mapper.current(exceeded);
        assertThat(newSegment.segmentRootId()).isEqualTo(exceeded);assertThat(newSegment.depth()).isZero();assertThat(newSegment.parentAssignmentId()).isNull();
        assertThat(Set.of(first,second)).contains(newSegment.agentAdminId());
        jdbc.update("UPDATE nx_support_agent_profile SET busy=1,max_concurrent=0 WHERE admin_id IN (?,?)",first,second);
        assertThat(mapper.current(customer(null))).isNotNull();proof("A03","Natural/depth-exceeded customers use deduplicated eligible advisors despite busy/maxConcurrent");
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",rootAssignment.agentAdminId().equals(first)?second:first);
        long same=customer(root);var sameAssignment=mapper.current(same);
        assertThat(sameAssignment.agentAdminId()).isEqualTo(rootAssignment.agentAdminId());assertThat(sameAssignment.segmentRootId()).isEqualTo(same);
        rules("LIMITED",1,"AUTO_RANDOM");long inherited=customer(same);
        assertThat(mapper.current(inherited).parentAssignmentId()).isEqualTo(sameAssignment.id());assertThat(mapper.current(inherited).depth()).isEqualTo(1);
        rules("UNLIMITED",null,"SUPERVISOR");long inheritedSupervisor=customer(inherited);
        assertThat(mapper.current(inheritedSupervisor).parentAssignmentId()).isEqualTo(mapper.current(inherited).id());
        assertThat(mapper.current(same).id()).isEqualTo(sameAssignment.id());proof("A02","L0/L1/unlimited inherit priority across allocation modes");proof("A04","Same advisor starts new root; new descendants inherit; previous rows unchanged");

        rules("UNCONFIGURED",null,"AUTO_RANDOM");long unconfigured=customer(null);assertThat(mapper.current(unconfigured)).isNotNull();
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id IN (?,?)",first,second);
        long waiting=customer(null);assertThat(mapper.current(waiting)).isNull();assertThat(mapper.autoEligible(waiting)).isTrue();
        assertThat(jdbc.queryForObject("SELECT auto_attempt_state FROM nx_support_binding_pool WHERE customer_id=?",String.class,waiting)).isEqualTo("WAITING_CANDIDATE");
        long attempts=jdbc.queryForObject("SELECT attempts FROM nx_support_binding_pool WHERE customer_id=?",Long.class,waiting);
        bindings.retryAutomatic(waiting);assertThat(jdbc.queryForObject("SELECT attempts FROM nx_support_binding_pool WHERE customer_id=?",Long.class,waiting)).isGreaterThan(attempts);
        rules("UNCONFIGURED",null,"SUPERVISOR");bindings.retryAutomatic(waiting);assertThat(mapper.current(waiting)).isNull();
        assertThat(jdbc.queryForObject("SELECT auto_attempt_state FROM nx_support_binding_pool WHERE customer_id=?",String.class,waiting)).isEqualTo("PAUSED");
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=1 WHERE admin_id=?",first);
        rules("UNCONFIGURED",null,"AUTO_RANDOM");bindings.retryAutomatic(waiting);assertThat(mapper.current(waiting).agentAdminId()).isEqualTo(first);
        assertThat(mapper.current(oldPool)).isNull();proof("A06","No candidate succeeds registration with durable attempts; original eligible row retries");proof("A07","Supervisor mode pauses; re-enable resumes only original automatic records");
        assertThat(mapper.current(unconfigured)).isNotNull();

        rules("LIMITED",0,"SUPERVISOR");long manual=customer(null),stale=customer(null),unsafe=customer(null);
        jdbc.update("UPDATE nx_support_binding_pool SET reason='MIGRATION_REVIEW' WHERE customer_id=?",unsafe);
        var preview=random.preview(new SupportRandomRequest.Preview(List.of(pool(manual),pool(stale),pool(unsafe)),false,null,null));
        assertThat(preview.count()).isEqualTo(2);assertThat(preview.excluded()).extracting(SupportRandom.Excluded::customerId).containsExactly(unsafe);
        var confirmation=new SupportRandomRequest.Confirm(preview.id(),preview.rulesVersion(),"Frozen historic random allocation proof");
        jdbc.update("UPDATE nx_support_binding_pool SET version=version+1 WHERE customer_id=?",stale);
        String operation=key();var result=random.confirm(operation,confirmation);assertThat(result.getCode()).isZero();
        long assignedId=mapper.current(manual).id();assertThat(mapper.current(stale)).isNull();
        assertThat(json.valueToTree(result).toString()).contains("POOL_VERSION_CHANGED");
        assertThat(json.readTree(json.writeValueAsString(random.confirm(operation,confirmation)))).isEqualTo(json.readTree(json.writeValueAsString(result)));
        jdbc.update("UPDATE nx_admin_idempotency_record SET status='UNKNOWN',response_json=NULL WHERE scope=? AND idempotency_key=?","SUPPORT_RANDOM:"+boss,operation);
        assertThat(random.confirm(operation,confirmation).getCode()).isZero();assertThat(mapper.current(manual).id()).isEqualTo(assignedId);
        assertThat(http("GET","/api/admin/content/support-workbench/commands/"+operation,bossToken,null,null).toString()).contains("COMPLETED",String.valueOf(assignedId));
        assertThatThrownBy(()->random.confirm(operation,new SupportRandomRequest.Confirm(preview.id(),preview.rulesVersion(),"Different request must conflict")))
            .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        proof("A10","Replay and UNKNOWN recover original assignment; changed payload denied");proof("A11","Review-required rows excluded without guessing");proof("A12","Frozen version conflict returns per-customer outcome, no descendant assignment");
        long expiredCustomer=customer(null);var expired=random.preview(new SupportRandomRequest.Preview(List.of(pool(expiredCustomer)),false,null,null));
        jdbc.update("UPDATE nx_support_random_preview SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",expired.id());
        assertThatThrownBy(()->random.confirm(key(),new SupportRandomRequest.Confirm(expired.id(),expired.rulesVersion(),"Expired allocation confirmation proof"))).isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        assertThat(mapper.current(expiredCustomer)).isNull();
        writeProof("random-runtime.json");
    }

    private void proof(String id,String evidence){proofs.put("core-"+id,Map.of("status","pass","evidence",evidence));}
    @Test void conversationFiltersPaginationAndArchiveKeepScopeAndLifecycle() throws Exception {
        rules("UNLIMITED",null,"SUPERVISOR");long customer=customer(null),other=customer(null);transfer(first,customer);transfer(second,other);
        String owner=token(first),supervisor=token(boss),client=userToken(customer),base="/api/admin/content/conversations";
        var expected=new LinkedHashSet<String>();
        for(int i=0;i<37;i++)expected.add(conversation(customer,i%3==0?"CLOSED":i%3==1?"RESOLVED":"OPEN",i%4==0,i%5==0?2:0));
        conversation(other,"OPEN",false,4);
        for(var filter:Map.of("open","status=OPEN","resolved","status=RESOLVED","closed","status=CLOSED","unread","unreadOnly=true","archived","archived=true").entrySet()) {
            var own=http("GET",base+"/overview",owner,null,null);assertThat(own.path("code").asInt()).isZero();
            var ownRows=http("GET",base+"?"+filter.getValue()+"&pageSize=100",owner,null,null);assertThat(ownRows.path("code").asInt()).as("scoped filter %s",ownRows).isZero();
            assertThat(own.path("data").path(filter.getKey()).asLong()).isEqualTo(ownRows.path("data").path("total").asLong());
            for(var row:ownRows.path("data").path("records"))assertThat(row.path("userId").asLong()).isEqualTo(customer);
            var global=http("GET",base+"/overview",supervisor,null,null);var globalRows=http("GET",base+"?"+filter.getValue()+"&pageSize=1",supervisor,null,null);
            assertThat(global.path("code").asInt()).isZero();assertThat(globalRows.path("code").asInt()).isZero();
            assertThat(global.path("data").path(filter.getKey()).asLong()).isEqualTo(globalRows.path("data").path("total").asLong());
        }
        var seen=new LinkedHashSet<String>();
        for(int page=1;page<=8;page++) {
            var rows=http("GET",base+"?keyword="+run+"&pageNum="+page+"&pageSize=5&ownerAgentId="+second,owner,null,null);
            assertThat(rows.path("code").asInt()).isZero();assertThat(rows.path("data").path("total").asLong()).isEqualTo(37);
            for(var row:rows.path("data").path("records")) {
                assertThat(seen.add(row.path("conversationNo").asText())).isTrue();assertThat(row.path("ownerAgentId").asLong()).isEqualTo(first);
                assertThat(row.path("ownerAgentName").asText()).isEqualTo("first");assertThat(row.path("conversationType").asText()).isEqualTo("advisor");
                assertThat(row.has("unreadCount")).isTrue();assertThat(row.has("archived")).isTrue();
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        proof("R25","Actual own/supervisor overview counts equal scoped lifecycle/unread/archive list totals");
        proof("R26","37 equal-time records searched through 8 HTTP pages without omissions/duplicates; current advisor, type and unread fields retained; foreign owner filter cannot widen scope");
        String resolved=conversation(customer,"RESOLVED",false,0),closed=conversation(customer,"CLOSED",false,0),unselected=conversation(customer,"OPEN",false,0);
        var archive=http("PATCH",base+"/"+resolved+"/archive",owner,Map.of("archived",true,"expectedStatus","RESOLVED","expectedVersion",0,"reason","Independent archive proof","operator",run),key());
        assertThat(archive.path("code").asInt()).isZero();assertThat(archive.path("data").path("status").asText()).isEqualTo("RESOLVED");assertThat(archive.path("data").path("archived").asBoolean()).isTrue();
        for(String route:List.of("/api/app/support/conversations/"+resolved,"/api/app/support/conversations?pageSize=100","/api/app/support/conversations/cursor?pageSize=100")) {
            var refreshed=http("GET",route,client,null,null);assertThat(refreshed.path("code").asInt()).isZero();
            var row=route.contains("pageSize")?findConversation(refreshed.path("data").path("records"),resolved):refreshed.path("data").path("conversation");
            assertThat(row.path("archived").asBoolean()).isTrue();assertThat(row.path("status").asText()).isEqualTo("RESOLVED");
        }
        assertThat(http("PATCH",base+"/"+closed+"/archive",owner,Map.of("archived",true,"expectedStatus","CLOSED","expectedVersion",0,"reason","Closed archive proof","operator",run),key()).path("code").asInt()).isZero();
        var reopened=http("PATCH",base+"/"+closed+"/archive",owner,Map.of("archived",false,"expectedStatus","CLOSED","expectedVersion",1,"reason","Closed unarchive proof","operator",run),key());
        assertThat(reopened.path("code").asInt()).isZero();assertThat(reopened.path("data").path("status").asText()).isEqualTo("CLOSED");
        assertThat(http("POST",base+"/"+closed+"/replies",owner,Map.of("body","Old closed segment stays closed","expectedStatus","CLOSED","expectedVersion",2,"reason","Closed segment denial","operator",run),key()).path("code").asInt()).isEqualTo(409);
        var started=http("POST","/api/app/support/conversations",client,Map.of("conversationType","advisor","openingText","Actual customer question"),key());
        assertThat(started.path("code").asInt()).as("customer question: %s",started).isZero();String pending=started.path("data").path("conversation").path("conversationNo").asText();
        var pendingView=started.path("data").path("conversation");long pendingVersion=pendingView.path("version").asLong();
        assertThat(http("PATCH",base+"/"+pending+"/archive",owner,Map.of("archived",true,"expectedStatus","OPEN","expectedVersion",pendingVersion,"reason","Pending must block","operator",run),key()).path("code").asInt()).isEqualTo(409);
        assertThat(http("PATCH",base+"/archive/batch",owner,Map.of("conversationNos",List.of(unselected,pending),"expectedVersions",Map.of(unselected,0,pending,pendingVersion),"reason","Batch pending must block","operator",run),key()).path("code").asInt()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT archived FROM nx_conversation WHERE conversation_no=?",Boolean.class,unselected)).isFalse();
        String selected=conversation(customer,"RESOLVED",false,0);String operation=key();
        var batch=Map.of("conversationNos",List.of(selected,unselected),"expectedVersions",Map.of(selected,0,unselected,0),"reason","Explicit archive list proof","operator",run);
        assertThat(http("PATCH",base+"/archive/batch",owner,batch,operation).path("code").asInt()).isZero();
        assertThat(http("PATCH",base+"/archive/batch",owner,batch,operation).path("code").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT archived FROM nx_conversation WHERE conversation_no=?",Boolean.class,pending)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM nx_conversation WHERE conversation_no=?",String.class,unselected)).isEqualTo("OPEN");
        assertThat(mapper.current(customer).agentAdminId()).isEqualTo(first);
        proof("R27","Real archive/unarchive preserves lifecycle and assignment; client detail/list/cursor read true; pending refused; unarchived CLOSED still refuses old-segment send");
        proof("R28","Actual mixed pending batch commits none; explicit successful list and replay affect only selected headers without lifecycle change");
        transfer(second,customer);assertThat(http("GET",base+"/"+resolved,owner,null,null).path("code").asInt()).isEqualTo(404);
        var history=http("GET",base+"?userId="+customer+"&pageSize=100",token(second),null,null);assertThat(history.path("code").asInt()).isZero();
        assertThat(history.path("data").path("total").asLong()).isEqualTo(expected.size()+5);
        String newName="advisor_"+run;jdbc.update("UPDATE nx_admin SET nickname=? WHERE id=?",newName,second);
        var byNewName=http("GET",base+"?userId="+customer+"&keyword="+newName+"&pageSize=100",token(second),null,null);
        assertThat(byNewName.path("data").path("total").asLong()).isEqualTo(expected.size()+5);assertThat(byNewName.path("data").path("records")).hasSize(expected.size()+5);
        for(var row:byNewName.path("data").path("records"))assertThat(row.path("ownerAgentName").asText()).isEqualTo(newName);
        assertThat(http("GET",base+"?userId="+customer+"&keyword=historical-name",token(second),null,null).path("data").path("total").asLong()).isZero();
        proof("R21","All customer history remains after formal transfer; former advisor detail revoked, current advisor receives whole history");writeProof("conversation-runtime.json");
    }
    private String conversation(long customer,String status,boolean archived,int unread) {
        String no=run+"_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO nx_conversation(conversation_no,user_id,conversation_type,status,owner_agent_id,owner_agent_name,unread_count,last_message,last_message_at,created_at,updated_at,archived) VALUES(?,?,'advisor',?,'historical-owner','historical-name',?,?, '2026-09-01 12:00:00','2026-09-01 12:00:00','2026-09-01 12:00:00',?)",no,customer,status,unread,run,archived);return no;
    }
    private com.fasterxml.jackson.databind.JsonNode findConversation(com.fasterxml.jackson.databind.JsonNode rows,String no) {for(var row:rows)if(no.equals(row.path("conversationNo").asText()))return row;throw new AssertionError("Conversation missing: "+no);}
    @Test void serviceProfileAndDevicePagesKeepUnknownDistinctAndAnnotationsPrivate() throws Exception {
        rules("UNLIMITED",null,"SUPERVISOR");long customer=customer(null),empty=customer(null);transfer(first,customer);transfer(first,empty);
        String owner=token(first),manager=token(boss),client=userToken(customer),path="/api/admin/content/support-workbench/customers/"+customer;
        jdbc.update("UPDATE nx_user SET v_rank='V3',user_level='L2',created_at='2026-08-01 12:00:00' WHERE id=?",customer);
        jdbc.update("UPDATE nx_user_session SET created_at='2026-08-02 12:00:00' WHERE user_id=?",customer);
        jdbc.update("INSERT INTO nx_support_activity_event(customer_id,seq,source_ref,occurred_at) VALUES(?,1,?,'2026-08-03 04:00:00')",customer,run);
        String no=conversation(customer,"OPEN",false,0),base="/api/admin/content/conversations/"+no;
        var initial=http("GET",path+"/360",owner,null,null).path("data").path("profile");var identity=initial.path("identity").path("data");
        assertThat(identity.path("customerId").asLong()).isEqualTo(customer);assertThat(identity.path("userNo").asText()).isEqualTo("U"+String.format("%08d",customer));
        assertThat(identity.path("level").asText()).isEqualTo("V3");assertThat(identity.path("phoneMasked").asText()).contains("****");assertThat(identity.path("region").asText()).isEqualTo("+86");
        assertThat(identity.path("registeredAt").asText()).isEqualTo("2026-08-01T04:00:00Z");assertThat(identity.path("lastLoginAt").asText()).isEqualTo("2026-08-02T04:00:00Z");
        assertThat(java.time.Instant.parse(initial.path("service").path("data").path("lastEffectiveAt").asText())).isEqualTo(java.time.Instant.parse("2026-08-03T04:00:00Z"));
        assertThat(initial.path("risk").path("status").asText()).isEqualTo("UNKNOWN");assertThat(initial.path("security").path("status").asText()).isEqualTo("FORBIDDEN");
        for(String field:List.of("assignment","conversationStatus","activityStatus","maintenanceEnabled","lastEffectiveAt","nextMaintenanceAt","lastServiceAt","conversationCount","ticketCount"))assertThat(initial.path("service").path("data").has(field)).as("service field %s",field).isTrue();
        jdbc.update("UPDATE nx_user SET v_rank='',user_level='' WHERE id=?",empty);
        var unknown=http("GET","/api/admin/content/support-workbench/customers/"+empty+"/360",owner,null,null).path("data").path("profile");
        assertThat(unknown.path("identity").path("data").path("level").isNull()).isTrue();assertThat(unknown.path("identity").path("data").path("lastLoginAt").isNull()).isTrue();
        assertThat(unknown.path("devices").path("data").path("total").asLong()).isZero();assertThat(unknown.path("devices").path("data").path("hashrateTotal").asText()).isEqualTo("0");
        var deviceIds=new HashSet<Long>();
        for(int i=0;i<211;i++) {
            String instance=run+"_device_"+i;
            jdbc.update("INSERT INTO nx_user_device(user_id,instance_no,name,device_type,status,hashrate,daily_usdt,daily_nex,purchased_at,activated_at) VALUES(?,?,?,'PHONE','ACTIVE',88.123456,1.123456,2.123456,'2026-08-01 12:00:00','2026-08-01 12:00:00')",customer,instance,instance);
            deviceIds.add(jdbc.queryForObject("SELECT id FROM nx_user_device WHERE instance_no=?",Long.class,instance));
        }
        var seen=new HashSet<Long>();
        for(int page=1;page<=8;page++) {
            var response=http("GET",path+"/devices?pageNum="+page+"&pageSize=31",owner,null,null);assertThat(response.path("data").path("status").asText()).isEqualTo("READY");var devices=response.path("data").path("data");
            assertThat(devices.path("total").asLong()).isEqualTo(211);assertThat(devices.path("onlineCount").isNull()).isTrue();assertThat(devices.path("hashrateTotal").isNull()).isTrue();assertThat(devices.path("idleCount").isNull()).isTrue();
            for(var row:devices.path("records")){assertThat(seen.add(row.path("id").asLong())).isTrue();assertThat(row.path("hashrate").isNull()).isTrue();assertThat(row.path("dailyUsdt").isNull()).isTrue();assertThat(row.path("storedHashrate").asText()).isEqualTo("88.123456");}
        }
        assertThat(seen).isEqualTo(deviceIds);
        for(long device:deviceIds)jdbc.update("INSERT INTO nx_user_device_runtime(user_device_id,online_status,heartbeat_at) VALUES(?,'ONLINE',DATE_SUB(NOW(),INTERVAL 1 SECOND))",device);
        var live=http("GET",path+"/devices",owner,null,null).path("data").path("data");assertThat(live.path("onlineCount").asLong()).isEqualTo(211);assertThat(live.path("fieldStatuses").path("onlineCount").asText()).isEqualTo("READY");
        jdbc.update("UPDATE nx_user_device_runtime SET heartbeat_at=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE user_device_id=?",deviceIds.iterator().next());
        assertThat(http("GET",path+"/devices",owner,null,null).path("data").path("data").path("onlineCount").asLong()).isEqualTo(210);
        String hidden="nx_user_device_hidden_"+run.substring(8);boolean renamed=false;
        try {jdbc.execute("RENAME TABLE nx_user_device TO "+hidden);renamed=true;
            var failed=http("GET",path+"/360",owner,null,null);assertThat(failed.path("code").asInt()).isZero();assertThat(failed.path("data").path("profile").path("devices").path("status").asText()).isEqualTo("ERROR");
            assertThat(failed.path("data").path("profile").path("devices").path("data").isNull()).isTrue();
        }finally{if(renamed)jdbc.execute("RENAME TABLE "+hidden+" TO nx_user_device");}
        assertThat(http("GET",path+"/devices",owner,null,null).path("data").path("status").asText()).isEqualTo("READY");
        var tag=Map.of("tag","Private follow-up","reason","Persist custom tag proof","operator",run);
        assertThat(http("POST",base+"/customer-tags",manager,tag,key()).path("code").asInt()).isEqualTo(404);
        assertThat(http("POST",base+"/customer-tags",owner,tag,key()).path("code").asInt()).isZero();
        var note=http("POST",base+"/customer-notes",owner,Map.of("text","Internal private note "+run,"reason","Persist original author proof","operator",run),key());
        assertThat(note.path("code").asInt()).isZero();assertThat(note.path("data").path("authorId").asLong()).isEqualTo(first);assertThat(note.path("data").path("authorName").asText()).isNotBlank();
        assertThat(java.time.Instant.parse(note.path("data").path("createdAt").asText()).toEpochMilli()).isEqualTo(note.path("data").path("ts").asLong());
        var annotations=http("GET",path+"/360",owner,null,null).path("data").path("profile").path("annotations").path("data");
        assertThat(annotations.path("customTags").toString()).contains("Private follow-up");assertThat(annotations.path("notes").toString()).contains("Internal private note",String.valueOf(first));
        assertThat(annotations.path("systemTags").toString()).contains("V3","账户正常");
        assertThat(http("DELETE",base+"/customer-tags",owner,Map.of("tag","V3","reason","System tag cannot be removed","operator",run),key()).path("code").asInt()).isEqualTo(404);
        assertThat(http("GET",path+"/360",owner,null,null).path("data").path("profile").path("annotations").path("data").path("systemTags").toString()).contains("V3");
        var appDetail=http("GET","/api/app/support/conversations/"+no,client,null,null);assertThat(appDetail.toString()).doesNotContain("Internal private note","Private follow-up");assertThat(appDetail.path("data").path("customerProfile").isNull()).isTrue();
        assertThat(http("DELETE",base+"/customer-tags",owner,tag,key()).path("code").asInt()).isZero();
        String noteId=note.path("data").path("id").asText();
        assertThat(http("DELETE",base+"/customer-notes/"+noteId,owner,Map.of("reason","Remove scoped annotation proof","operator",run),key()).path("code").asInt()).isZero();
        assertThat(http("GET",path+"/360",owner,null,null).path("data").path("profile").path("annotations").path("data").path("notes").size()).isZero();
        transfer(second,customer);assertThat(http("POST",base+"/customer-tags",owner,tag,key()).path("code").asInt()).isEqualTo(404);
        assertThat(http("POST",base+"/customer-notes",owner,Map.of("text","Forbidden old owner","reason","Old owner must fail","operator",run),key()).path("code").asInt()).isEqualTo(404);
        proof("R06","Real service identity separates numeric ID and canonical userNo");proof("R07","Stored V3 overrides L2; absent levels remain null");
        proof("R08","Actual masked phone, region and business-time registration projected");proof("R09","Three distinct persisted register/login/effective-activity dates retained");
        proof("R10","No trusted risk score stays UNKNOWN and private risk/security data FORBIDDEN");
        proof("R15","211 equal-time devices reached through 8 real HTTP pages without truncation, duplicates or omissions");
        proof("R16","No devices yields true 0; missing telemetry null; 211 fresh reports online, one stale excludes it; source failure ERROR and retry READY; mixed stored units never summed");
        proof("R17","System tags derived from real identity remain separate from editable persistent custom tags");
        proof("R18","Actual custom tag write/remove persisted and refreshed; supervisor and transferred former advisor denied");
        proof("R19","Actual note author ID/name/UTC timestamp match DB; write/remove refresh, supervisor/old owner denial and no client exposure");
        proof("R35","Complete prior service lifecycle/assignment/activity/maintenance/count fields retained beside new grouped profile");writeProof("profile-device-runtime.json");
    }
    @Test void fullFinancialHistoryIsPrecisePaginatedAndSectionFailuresStayLocal() throws Exception {
        rules("UNCONFIGURED",null,"AUTO_RANDOM");long customer=customer(null);long owner=mapper.current(customer).agentAdminId();
        jdbc.update("INSERT INTO nx_user_wallet(user_id,usdt_available,nex_available) VALUES(?,12.123456,0) ON DUPLICATE KEY UPDATE usdt_available=12.123456,nex_available=0",customer);
        for(int i=0;i<31;i++) {
            deposit(customer,run+"_chain_"+i,"USDT","0.123456","CHAIN_TOPUP");
            String card=run+"_card_"+i;long credited=ledger(customer,card,"CARD_TOPUP","USDT","1.000001");
            jdbc.update("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,currency,amount_usdt,payment_status,wallet_ledger_id,created_at) VALUES(?,?,?,'Card',?,'VND',1.000001,'SUCCESS',?,'2026-09-01 12:00:00')",card,card,customer,card,credited);
            String manual=run+"_manual_"+i;ledger(customer,"D1-VIETQR-"+manual,"VIETQR_DEPOSIT","USDT","2.123456");
            jdbc.update("INSERT INTO nx_vietqr_reconciliation(reconciliation_no,intent_no,user_id,view_type,status,received_vnd,locked_fx_rate_vnd_per_usdt,credited_usdt,created_at) VALUES(?,?,?,'MATCHED','CREDITED',50000,25000,2.123456,'2026-09-01 12:00:00')",manual,manual,customer);
            String hd=run+"_hd_"+i;ledger(customer,hd,"VIETQR_DEPOSIT","USDT","10.01");
            jdbc.update("INSERT INTO nx_vietqr_intent(intent_no,user_id,create_idempotency_key,create_request_hash,requested_usdt,payable_vnd,credited_usdt,received_vnd,locked_fx_rate_vnd_per_usdt,fx_quote_version,payment_rail,memo_code,status,expires_at,created_at) VALUES(?,?,?,REPEAT('a',64),10.01,250250,10.01,250250,25000,1,'HDPAY',?,'CREDITED',DATE_ADD(NOW(),INTERVAL 1 DAY),'2026-09-01 12:00:00')",hd,customer,hd,hd);
            jdbc.update("INSERT INTO nx_hdpay_payin_order(merchant_order_id,amount_vnd,submission_status,settlement_status,settled_usdt,wallet_ledger_biz_no,settled_at,request_hash) VALUES(?,250250,'CREATED','CREDITED',10.01,?,'2026-09-01 12:00:00',REPEAT('a',64))",hd,hd);
            withdrawal(customer,run+"_out_"+i,i%2==0?"CONFIRMED":"SUCCESS","10.123456","0.123456","10",true);
        }
        deposit(customer,run+"_nex","NEX","0.000001","DEPOSIT");
        String shared=run+"_shared";long sharedLedger=deposit(customer,shared,"USDT","0.25","CARD_TOPUP");
        jdbc.update("INSERT INTO nx_payment_record(payment_no,order_no,user_id,provider,provider_payment_id,amount_usdt,payment_status,wallet_ledger_id,created_at) VALUES(?,?,?,'Card',?,0.25,'CHARGEBACK',?,'2026-09-01 12:00:00')",shared,shared,customer,shared,sharedLedger);
        jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,created_at) VALUES(?,?,'TRC20',?,'USDT',7.000001,'PENDING','2026-09-01 12:00:00')",customer,run+"_pending",run+"_pending");
        ledger(customer,run+"_recovery","ADMIN_ADJUST","USDT","17");
        var processing=List.of("SUBMITTED","PENDING","REVIEW_PENDING","REVIEWING","EXTENDED_HOLD","DELAYED","REVIEW_PASSED","PENDING_CHAIN","PROCESSING","SENT","CHAIN_SUBMITTED");
        for(String state:processing)withdrawal(customer,run+"_"+state,state,"1.000001","0","1.000001",false);
        for(String state:List.of("REVIEW_REJECTED","REJECTED","ADDRESS_INVALID","TX_FAILED","FAILED","REFUNDED"))withdrawal(customer,run+"_"+state,state,"999","0","999",false);
        String token=token(owner),path="/api/admin/content/support-workbench/customers/"+customer;
        var refreshed=http("GET",path+"/360",token,null,null);assertThat(refreshed.path("code").asInt()).as("profile: %s",refreshed).isZero();
        var profile=refreshed.path("data").path("profile");var finance=profile.path("finance");assertThat(finance.path("status").asText()).isEqualTo("READY");
        var totals=finance.path("data").path("byCurrency");var usdt=findCurrency(totals,"USDT");var nex=findCurrency(totals,"NEX");
        assertThat(usdt.path("availableBalance").asText()).isEqualTo("12.123456");
        assertThat(usdt.path("creditedDepositTotal").asText()).isEqualTo(new java.math.BigDecimal("13.256913").multiply(new java.math.BigDecimal("31")).add(new java.math.BigDecimal("0.25")).stripTrailingZeros().toPlainString());
        assertThat(nex.path("creditedDepositTotal").asText()).isEqualTo("0.000001");assertThat(nex.path("availableBalance").asText()).isEqualTo("0");
        assertThat(usdt.path("successfulWithdrawalPrincipalTotal").asText()).isEqualTo("313.827136");
        assertThat(usdt.path("successfulWithdrawalFeeTotal").asText()).isEqualTo("3.827136");assertThat(usdt.path("successfulWithdrawalNetTotal").asText()).isEqualTo("310");
        assertThat(usdt.path("processingWithdrawalPrincipalTotal").asText()).isEqualTo("11.000011");
        assertThat(usdt.path("depositRefundTotal").isNull()).isTrue();assertThat(usdt.path("fieldStatuses").path("depositRefundTotal").asText()).isEqualTo("UNKNOWN");
        for(String section:List.of("identity","finance","devices","risk","annotations","service","security","riskCases"))assertThat(profile.path(section).path("evaluatedAt")).isEqualTo(profile.path("evaluatedAt"));
        var ids=new HashSet<String>();long total=0;boolean chargeback=false;
        for(int page=1;;page++) {
            var response=http("GET",path+"/flows?pageNum="+page+"&pageSize=17",token,null,null);assertThat(response.path("data").path("status").asText()).isEqualTo("READY");
            var flows=response.path("data").path("data");total=flows.path("total").asLong();
            for(var row:flows.path("records")) {assertThat(ids.add(row.path("sourceId").asText())).isTrue();
                assertThat(row.path("principal").isTextual() || row.path("principal").isNull()).isTrue();
                if((run+"_card_0").equals(row.path("bizNo").asText())) {assertThat(row.path("currency").asText()).isEqualTo("USDT");assertThat(row.path("principal").asText()).isEqualTo("1.000001");}
                if(shared.equals(row.path("bizNo").asText())) {assertThat(row.path("status").asText()).isEqualTo("CHARGEBACK");chargeback=true;}
            }
            if(ids.size()>=total)break;assertThat(page).isLessThan(20);
        }
        assertThat(total).isGreaterThan(150);assertThat(ids).hasSize((int)total);assertThat(chargeback).isTrue();
        var range=http("GET",path+"/flows?currency=NEX&from=2026-09-01T04:00:00Z&to=2026-09-01T04:00:01Z",token,null,null);
        assertThat(range.path("data").path("data").path("total").asLong()).isEqualTo(1);assertThat(range.path("data").path("data").path("records").get(0).path("createdAt").asText()).isEqualTo("2026-09-01T04:00:00Z");
        assertThat(http("GET",path+"/flows?from=bad",token,null,null).path("code").asInt()).isEqualTo(422);
        jdbc.update("UPDATE nx_payment_record SET payment_status='REFUNDED' WHERE payment_no=?",run+"_card_0");
        var afterRefund=http("GET",path+"/360",token,null,null);assertThat(findCurrency(afterRefund.path("data").path("profile").path("finance").path("data").path("byCurrency"),"USDT").path("creditedDepositTotal")).isEqualTo(usdt.path("creditedDepositTotal"));
        var refundFlows=http("GET",path+"/flows?status=REFUNDED",token,null,null);assertThat(refundFlows.path("data").path("data").path("records")).anyMatch(row->(run+"_card_0").equals(row.path("bizNo").asText()));
        assertThat(profile.toString()).doesNotContain("objectKey","modelVersion","password_hash");assertThat(profile.path("security").path("status").asText()).isEqualTo("FORBIDDEN");
        assertThat(http("GET",path+"/360",token(second==owner?first:second),null,null).path("code").asInt()).isEqualTo(404);
        boolean renamed=false;String temporary="enhance_payment_fault_"+run.substring(8);
        try {jdbc.execute("ALTER TABLE nx_payment_record RENAME TO "+temporary);renamed=true;
            var broken=http("GET",path+"/360",token,null,null);assertThat(broken.path("code").asInt()).as("local failure: %s",broken).isZero();
            assertThat(broken.path("data").path("profile").path("finance").path("status").asText()).isEqualTo("ERROR");
            assertThat(broken.path("data").path("profile").path("identity").path("status").asText()).isEqualTo("READY");
        }finally{if(renamed)jdbc.execute("ALTER TABLE "+temporary+" RENAME TO nx_payment_record");}
        assertThat(http("GET",path+"/360",token,null,null).path("data").path("profile").path("finance").path("status").asText()).isEqualTo("READY");
        long abnormal=customer(null);jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status) VALUES(?,?,'TRC20',?,'USDT',2,'SUCCESS')",abnormal,run+"_abnormal",run+"_abnormal");
        as(boss);var unknown=json.valueToTree(profiles.profile(abnormal));var anomaly=findCurrency(unknown.path("finance").path("data").path("byCurrency"),"USDT");
        assertThat(anomaly.path("creditedDepositTotal").isNull()).isTrue();assertThat(anomaly.path("fieldStatuses").path("creditedDepositTotal").asText()).isEqualTo("UNKNOWN");
        assertThat(anomaly.path("availableBalance").isNull()).isTrue();assertThat(anomaly.path("fieldStatuses").path("availableBalance").asText()).isEqualTo("UNKNOWN");
        for(String id:List.of("R11","R12","R13","R14","F01","F02","F03","F05"))proof(id,"Actual HTTP reads four credit channels with 31 rows each, full-history precise dual-currency totals, VND card payment with USDT ledger amount, complete pages/time filter, successful withdrawal principal/fee/net vs 11 processing aliases, refunded/rejected/failed withdrawals excluded, duplicate chain/card counted once; changing later payment state to REFUNDED keeps original credited total and exposes refund flow, refund aggregate and absent-wallet amounts remain UNKNOWN. Fixtures do not claim original refund write execution or every malformed source combination.");
        proof("R20","Actual service-scoped 360 owner read, unrelated advisor denied, forbidden security/risk-case sections");writeProof("finance-profile-runtime.json");
    }
    @Test void everyReadableProfileGroupSurvivesItsOwnDatabaseFailureAndRecovers() throws Exception {
        rules("UNLIMITED",null,"SUPERVISOR");long customer=customer(null);transfer(first,customer);String owner=token(first),url="/api/admin/content/support-workbench/customers/"+customer+"/360";
        var original=jdbc.queryForMap("SELECT id,nickname,status,avatar_url FROM nx_user WHERE id=?",customer);
        var counts=new LinkedHashMap<String,Long>();for(String table:List.of("nx_user","nx_user_wallet","nx_payment_record","nx_user_device_runtime","nx_customer_note","nx_support_ticket","nx_admin_risk_score_user"))counts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        var faults=List.of(new String[]{"identity","ALTER TABLE nx_user RENAME COLUMN avatar_url TO enhance_avatar_fault","ALTER TABLE nx_user RENAME COLUMN enhance_avatar_fault TO avatar_url"},
            new String[]{"finance","RENAME TABLE nx_payment_record TO enhance_payment_fault","RENAME TABLE enhance_payment_fault TO nx_payment_record"},
            new String[]{"devices","RENAME TABLE nx_user_device_runtime TO enhance_runtime_fault","RENAME TABLE enhance_runtime_fault TO nx_user_device_runtime"},
            new String[]{"risk","ALTER TABLE nx_admin_risk_score_user RENAME COLUMN row_version TO enhance_risk_fault","ALTER TABLE nx_admin_risk_score_user RENAME COLUMN enhance_risk_fault TO row_version"},
            new String[]{"annotations","RENAME TABLE nx_customer_note TO enhance_note_fault","RENAME TABLE enhance_note_fault TO nx_customer_note"},
            new String[]{"service","RENAME TABLE nx_support_ticket TO enhance_ticket_fault","RENAME TABLE enhance_ticket_fault TO nx_support_ticket"});
        for(var fault:faults) {
            var before=http("GET",url,owner,null,null);assertThat(before.path("code").asInt()).isZero();boolean changed=false;
            try {jdbc.execute(fault[1]);changed=true;var failed=http("GET",url,owner,null,null);assertThat(failed.path("code").asInt()).as("group %s HTTP",fault[0]).isZero();var groups=failed.path("data").path("profile");
                assertThat(groups.path(fault[0]).path("status").asText()).as("group %s",fault[0]).isEqualTo("ERROR");assertThat(groups.path(fault[0]).path("data").isNull()).isTrue();
                for(String group:List.of("identity","finance","devices","risk","annotations","service"))if(!group.equals(fault[0])) {
                    if(fault[0].equals("identity") && group.equals("risk"))assertThat(groups.path(group).path("status").asText()).isEqualTo("UNKNOWN");
                    else assertThat(groups.path(group).path("status")).as("%s must survive %s",group,fault[0]).isEqualTo(before.path("data").path("profile").path(group).path("status"));
                }
                assertThat(groups.path("security").path("status").asText()).isEqualTo("FORBIDDEN");assertThat(groups.path("riskCases").path("status").asText()).isEqualTo("FORBIDDEN");
            } finally {if(changed)jdbc.execute(fault[2]);}
            assertThat(jdbc.queryForMap("SELECT id,nickname,status,avatar_url FROM nx_user WHERE id=?",customer)).isEqualTo(original);
            for(var count:counts.entrySet())assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+count.getKey(),Long.class)).isEqualTo(count.getValue());
            var restored=http("GET",url,owner,null,null);assertThat(restored.path("code").asInt()).isZero();assertThat(restored.path("data").path("profile").path(fault[0]).path("status")).isEqualTo(before.path("data").path("profile").path(fault[0]).path("status"));
        }
        proof("F04","Each readable identity/finance/devices/risk/annotations/service group receives actual scoped MySQL table/column failure, HTTP returns its own ERROR/null, independent groups survive, dependent risk is UNKNOWN after identity failure, schema restore recovers original status and business row counts/customer fields remain unchanged; security/riskCases remain FORBIDDEN.");writeProof("profile-errors-runtime.json");
    }
    private com.fasterxml.jackson.databind.JsonNode findCurrency(com.fasterxml.jackson.databind.JsonNode rows,String currency) {for(var row:rows)if(currency.equals(row.path("currency").asText()))return row;throw new AssertionError("currency missing "+currency);}
    @Test void adminAvatarUsesRealStorageAndAccountCasAcrossEveryProjection() throws Exception {
        String superToken=token(boss),managerToken=token(admin("avatar_manager","SUPPORT","MANAGER"));
        byte[] original=png(0xff3344),replacement=png(0x2244ff);
        assertThat(uploadAvatar(managerToken,original,"image/png",key(),key()).path("code").asInt()).isEqualTo(403);
        assertThat(uploadAvatar(superToken,"<svg/>".getBytes(),"image/svg+xml",key(),key()).path("code").asInt()).isEqualTo(415);
        String upload=key(),uploadKey=key();var asset=uploadAvatar(superToken,original,"image/png",upload,uploadKey);
        assertThat(asset.path("code").asInt()).isZero();assertThat(asset.toString()).doesNotContain("objectKey","bucket");
        String assetId=asset.path("data").path("assetId").asText();assertThat(uploadAvatar(superToken,original,"image/png",upload,uploadKey).path("data").path("assetId").asText()).isEqualTo(assetId);
        assertThat(download("/api/admin/platform/accounts/avatar-assets/"+assetId,superToken).statusCode()).isEqualTo(200);
        assertThat(uploadAvatar(superToken,original,"image/jpeg",key(),key()).path("code").asInt()).isEqualTo(415);
        long maxBytes=attachmentPolicy.getMaxBytes(),maxPixels=attachmentPolicy.getMaxPixels();
        try {attachmentPolicy.setMaxBytes(16L);assertThat(uploadAvatar(superToken,original,"image/png",key(),key()).path("code").asInt()).isEqualTo(413);
            attachmentPolicy.setMaxBytes(maxBytes);attachmentPolicy.setMaxPixels(1L);assertThat(uploadAvatar(superToken,original,"image/png",key(),key()).path("code").asInt()).isEqualTo(413);
        }finally{attachmentPolicy.setMaxBytes(maxBytes);attachmentPolicy.setMaxPixels(maxPixels);}
        long otherSuper=admin("foreign_asset_super","SUPER_ADMIN","MANAGER");String foreignAsset=uploadAvatar(token(otherSuper),original,"image/png",key(),key()).path("data").path("assetId").asText();
        assertThat(download("/api/admin/platform/accounts/avatar-assets/"+foreignAsset,superToken).statusCode()).isEqualTo(404);
        var createBody=Map.of("username",run+"_avatar_created","displayName","Avatar creator","email",run+"@example.invalid","role","support","reason","Create optional avatar proof","operator",run,"avatarAssetId",assetId);String createKey=key();
        var created=http("POST","/api/admin/platform/accounts",superToken,createBody,createKey);
        assertThat(created.path("code").asInt()).as("account creation: %s",created.path("message").asText()).isZero();var account=created.path("data");long target=account.path("id").asLong();createdAdmins.add(target);
        assertThat(account.path("credentialDeliveryStatus").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        // Read-capability fixture readiness is separate from the unchanged password-change workflow.
        jdbc.update("UPDATE nx_admin_account_state SET credential_delivery_status='ACTIVE' WHERE admin_id=?",target);
        assertThat(http("POST","/api/admin/platform/accounts",superToken,createBody,createKey).path("data").path("id")).isEqualTo(account.path("id"));
        assertThat(http("POST","/api/admin/platform/accounts",managerToken,createBody,createKey).path("code").asInt()).isEqualTo(403);
        assertThat(account.path("avatarAssetId").asText()).isEqualTo(assetId);assertThat(account.path("avatarVersion").asLong()).isEqualTo(1);
        var foreignEdit=Map.of("username",account.path("username").asText(),"displayName",account.path("name").asText(),"email",account.path("email").asText(),"reason","Foreign staged asset must fail","operator",run,"expectedVersion",account.path("version").asText(),"avatarAssetId",foreignAsset);
        assertThat(http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,foreignEdit,key()).path("code").asInt()).isEqualTo(404);
        String badUpload=key(),bucket=storageProperties.getBucket();
        try {storageProperties.setBucket("unavailable-"+run.replace('_','-'));assertThat(uploadAvatar(superToken,original,"image/png",badUpload,key()).path("code").asInt()).isNotZero();}
        finally{storageProperties.setBucket(bucket);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_admin_avatar_asset WHERE uploader_id=? AND client_upload_id=?",Long.class,boss,badUpload)).isZero();
        assertThat(jdbc.queryForObject("SELECT avatar_asset_id FROM nx_admin_account_state WHERE admin_id=?",String.class,target)).isEqualTo(assetId);
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,'DEDICATED','DEDICATED','support,advisor','',0,1,1,0)",target);
        var noAvatar=http("POST","/api/admin/platform/accounts",superToken,Map.of("username",run+"_no_avatar","displayName","No avatar allowed","email",run+"_empty@example.invalid","role","support","reason","Optional avatar remains optional","operator",run),key());
        assertThat(noAvatar.path("code").asInt()).isZero();createdAdmins.add(noAvatar.path("data").path("id").asLong());assertThat(noAvatar.path("data").path("avatarAssetId").isNull()).isTrue();
        var before=jdbc.queryForMap("SELECT username,nickname,email,password_hash,status,super_admin,version FROM nx_admin WHERE id=?",target);
        String nextAsset=uploadAvatar(superToken,replacement,"image/png",key(),key()).path("data").path("assetId").asText();
        var edit=new LinkedHashMap<String,Object>();edit.put("username",account.path("username").asText());edit.put("displayName",account.path("name").asText());edit.put("email",account.path("email").asText());
        edit.put("expectedVersion",account.path("version").asText());edit.put("reason","Replace avatar only without changing identity");edit.put("operator",run);edit.put("avatarAssetId",nextAsset);
        var changed=http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,edit,key());assertThat(changed.path("code").asInt()).isZero();
        var after=jdbc.queryForMap("SELECT username,nickname,email,password_hash,status,super_admin,version FROM nx_admin WHERE id=?",target);
        for(String field:List.of("username","nickname","email","password_hash","status","super_admin"))assertThat(after.get(field)).as("identity preserved %s",field).isEqualTo(before.get(field));
        assertThat(((Number)after.get("version")).longValue()).isEqualTo(((Number)before.get("version")).longValue()+1);
        assertThat(changed.path("data").path("avatarVersion").asLong()).isEqualTo(2);assertThat(changed.path("data").path("avatarAssetId").asText()).isEqualTo(nextAsset);
        assertThat(http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,edit,key()).path("code").asInt()).isEqualTo(409);
        var bytes=download("/api/admin/platform/accounts/"+target+"/avatar",superToken);assertThat(bytes.statusCode()).isEqualTo(200);assertThat(bytes.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(bytes.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes.body()))).isNotNull();
        String missingAsset=uploadAvatar(superToken,original,"image/png",key(),key()).path("data").path("assetId").asText();
        storage.remove(jdbc.queryForObject("SELECT object_key FROM nx_support_admin_avatar_asset WHERE id=?",String.class,missingAsset));
        edit.put("expectedVersion",changed.path("data").path("version").asText());edit.put("avatarAssetId",missingAsset);
        assertThat(http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,edit,key()).path("code").asInt()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT version FROM nx_admin WHERE id=?",Long.class,target)).isEqualTo(((Number)after.get("version")).longValue());
        assertThat(jdbc.queryForObject("SELECT avatar_asset_id FROM nx_admin_account_state WHERE admin_id=?",String.class,target)).isEqualTo(nextAsset);
        String cancelled=uploadAvatar(superToken,original,"image/png",key(),key()).path("data").path("assetId").asText();
        assertThat(http("DELETE","/api/admin/platform/accounts/avatar-assets/"+cancelled,superToken,null,key()).path("code").asInt()).isZero();
        edit.put("avatarAssetId",cancelled);assertThat(http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,edit,key()).path("code").asInt()).isEqualTo(409);
        String expired=uploadAvatar(superToken,original,"image/png",key(),key()).path("data").path("assetId").asText();jdbc.update("UPDATE nx_support_admin_avatar_asset SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",expired);
        edit.put("avatarAssetId",expired);assertThat(http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,edit,key()).path("code").asInt()).isEqualTo(409);
        rules("UNCONFIGURED",null,"SUPERVISOR");long customer=customer(null);as(boss);transfer(target,customer);
        String user=userToken(customer);var advisor=http("GET","/api/app/support/advisor",user,null,null);assertThat(advisor.path("code").asInt()).isZero();
        assertThat(advisor.toString()).contains(nextAsset);assertThat(download("/api/app/support/advisor/avatar/"+target,user).statusCode()).isEqualTo(200);
        long outsider=customer(null);assertThat(download("/api/app/support/advisor/avatar/"+target,userToken(outsider)).statusCode()).isEqualTo(404);
        String targetToken=token(target),clientMessage=key();
        var started=http("POST","/api/admin/content/conversations",targetToken,Map.of("conversationType","advisor","userId",customer,"openingText","Verified historical author","reason","Historical avatar author proof","operator",run,"kind","TEXT","clientMessageId",clientMessage,"expectedAssignmentId",mapper.current(customer).id()),key());
        assertThat(started.path("code").asInt()).isZero();String no=started.path("data").path("conversationNo").asText();
        long authored=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,target,clientMessage);
        jdbc.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content) SELECT id,conversation_no,?,'agent','Unknown historical author','Legacy unknown author' FROM nx_conversation WHERE conversation_no=?",target,no);
        String refreshedAsset=uploadAvatar(superToken,original,"image/png",key(),key()).path("data").path("assetId").asText();edit.put("avatarAssetId",refreshedAsset);
        var changedAgain=http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,edit,key());assertThat(changedAgain.path("code").asInt()).isZero();assertThat(changedAgain.path("data").path("avatarVersion").asLong()).isEqualTo(3);
        var agentList=http("GET","/api/admin/content/support-agents?pageSize=100",superToken,null,null);assertThat(agentList.path("code").asInt()).isZero();assertThat(agentList.toString()).contains(refreshedAsset);
        transfer(second,customer);
        var history=http("GET","/api/app/support/conversations/"+no,user,null,null);assertThat(history.path("code").asInt()).isZero();
        assertThat(history.path("data").path("conversation").path("ownerAgentId").asLong()).isEqualTo(second);
        var originalAuthor=findMessage(history.path("data").path("messages"),authored);assertThat(originalAuthor.path("senderId").asLong()).isEqualTo(target);
        assertThat(originalAuthor.path("senderAvatar").path("assetId").asText()).isEqualTo(refreshedAsset);assertThat(originalAuthor.path("senderAvatar").path("version").asLong()).isEqualTo(3);
        for(var row:history.path("data").path("messages"))if("Legacy unknown author".equals(row.path("content").asText())){assertThat(row.path("authorConfidence").asText()).isEqualTo("UNKNOWN");assertThat(row.path("senderAvatar").isNull()).isTrue();}
        assertThat(download("/api/app/support/advisor/avatar/"+target,user).statusCode()).isEqualTo(200);
        assertThat(http("GET","/api/admin/content/conversations/"+no,targetToken,null,null).path("code").asInt()).isEqualTo(404);
        assertThat(findMessage(http("GET","/api/admin/content/conversations/"+no,token(second),null,null).path("data").path("messages"),authored).path("senderAvatar").path("assetId").asText()).isEqualTo(refreshedAsset);
        String customerKey="users/"+customer+"/avatar/"+UUID.randomUUID();storage.put(customerKey,"image/png",new java.io.ByteArrayInputStream(original),original.length);
        jdbc.update("UPDATE nx_user SET avatar_url=? WHERE id=?",customerKey,customer);
        var profile=http("GET","/api/admin/content/support-workbench/customers/"+customer+"/360",token(second),null,null);
        assertThat(profile.path("code").asInt()).as("customer avatar profile: %s",profile.path("message").asText()).isZero();
        assertThat(profile.toString()).doesNotContain(customerKey,"private/admin-avatar");assertThat(profile.path("data").path("profile").path("identity").path("data").path("avatar").asText()).endsWith("/avatar");
        assertThat(download("/api/admin/content/support-workbench/customers/"+customer+"/avatar",token(second)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT avatar_url FROM nx_user WHERE id=?",String.class,customer)).isEqualTo(customerKey);
        proof("V01","Actual optional create without avatar and with READY asset both succeed; account state and private S3 attached");
        proof("V02","Actual avatar-only CAS increments account/avatar versions; every identity field preserved; stale/storage/cancel/expiry failures retain original");
        proof("V03","Actual multipart non-super/foreign staged assets/false MIME/SVG/byte/pixel limits denied; real S3 upload failure rolls back asset and preserves account image; shared decode/reencode and no-store bytes");
        proof("V04","Same admin state asset/version read through account and current-advisor projection with fresh bytes");
        proof("V05","Actual transfer retains original verified sender ID and refreshed original avatar version in client/new-advisor history; legacy unproven author stays placeholder, former advisor revoked");
        proof("V06","Customer private avatar controlled readonly route; raw object key hidden and stored key untouched");writeProof("avatar-runtime.json");
    }
    private void transfer(long target,long customer) {as(boss);var old=mapper.current(customer);assertThat(bindings.transfer(key(),new SupportBindingRequest(target,List.of(new SupportBindingRequest.Customer(customer,old==null?null:old.id(),old==null?mapper.poolVersion(customer):old.version())),"Isolated formal transfer proof")).getCode()).isZero();}
    @Test void skuAndLinkMessagesUseRealTransportsAndCurrentSkuLock() throws Exception {
        rules("UNLIMITED",null,"SUPERVISOR");long customer=customer(null);transfer(first,customer);long assignment=mapper.current(customer).id();
        String owner=token(first),client=userToken(customer),sku="lumen-"+run.substring(8),base="/api/admin/content/conversations";
        for(int i=0;i<17;i++)jdbc.update("INSERT INTO nx_product(product_no,name,product_type,status,store_status,price_usdt,estimated_daily_usdt,stock) VALUES(?,?,'DEVICE',?, ?,10,0.1,100)",sku+"-"+i,"Lumen "+run+" "+i,i==16?"OFF_SALE":"ON_SALE",i==16?"off":"on");
        var catalogIds=new HashSet<String>();
        for(int page=1;page<=4;page++) {
            var catalog=http("GET","/api/admin/content/support-workbench/skus?keyword="+sku+"&pageSize=5&pageNum="+page,owner,null,null);
            assertThat(catalog.path("code").asInt()).as("SKU query: %s",catalog).isZero();assertThat(catalog.path("data").path("total").asLong()).isEqualTo(16);
            for(var row:catalog.path("data").path("records")){assertThat(catalogIds.add(row.path("skuId").asText())).isTrue();assertThat(row.path("status").asText()).isEqualTo("on");}
        }
        assertThat(catalogIds).hasSize(16);assertThat(catalogIds).doesNotContain(sku+"-16");
        var opening=new LinkedHashMap<String,Object>(Map.of("conversationType","advisor","userId",customer,"openingText","Actual SKU caption","reason","Real SKU shared-protocol proof","operator",run,"kind","SKU","skuId",sku+"-0","clientMessageId",key(),"expectedAssignmentId",assignment));
        String operation=key();var sent=http("POST",base,owner,opening,operation);assertThat(sent.path("code").asInt()).as("SKU initiate: %s",sent).isZero();String no=sent.path("data").path("conversationNo").asText();
        long message=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,first,opening.get("clientMessageId"));
        var detail=http("GET","/api/app/support/conversations/"+no,client,null,null);assertThat(detail.path("code").asInt()).isZero();
        var skuMessage=findMessage(detail.path("data").path("messages"),message);assertThat(skuMessage.path("kind").asText()).isEqualTo("SKU");assertThat(skuMessage.path("content").asText()).isEqualTo("Actual SKU caption");
        assertThat(skuMessage.path("skuId").asText()).isEqualTo(sku+"-0");assertThat(skuMessage.path("skuName").asText()).isEqualTo("Lumen "+run+" 0");
        assertThat(skuMessage.path("targetAvailability").asText()).isEqualTo("AVAILABLE");
        jdbc.update("UPDATE nx_product SET status='OFF_SALE',store_status='off',name='Current renamed product' WHERE product_no=?",sku+"-0");
        assertThat(http("POST",base,owner,opening,key()).path("code").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,first,opening.get("clientMessageId"))).isEqualTo(1);
        opening.put("clientMessageId",key());assertThat(http("POST",base,owner,opening,key()).path("code").asInt()).isEqualTo(409);
        assertThat(findMessage(http("GET","/api/app/support/conversations/"+no,client,null,null).path("data").path("messages"),message).path("targetAvailability").asText()).isEqualTo("UNAVAILABLE");
        var link=reply(no,owner,assignment,"LINK");link.put("linkTarget",Map.of("type","WALLET","params",Map.of()));
        try(var socket=socket(owner)) {
            socket.send(Map.of("type","command","requestId","enhance-link","operation","reply","conversationNo",no,"idempotencyKey",key(),"body",link));
            assertThat(socket.await("ack").path("result").path("code").asInt()).isZero();
        }
        long linked=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,first,link.get("clientMessageId"));
        var projected=findMessage(http("GET","/api/app/support/conversations/"+no,client,null,null).path("data").path("messages"),linked);
        assertThat(projected.path("kind").asText()).isEqualTo("LINK");assertThat(projected.path("linkTarget").path("type").asText()).isEqualTo("WALLET");
        for(var invalid:List.of(Map.of("type","ADMIN","params",Map.of()),Map.of("type","HOME","params",Map.of("url","javascript:alert(1)")),Map.of("type","GENESIS","params",Map.of()))) {
            var bad=reply(no,owner,assignment,"LINK");bad.put("linkTarget",invalid);assertThat(http("POST",base+"/"+no+"/replies",owner,bad,key()).path("code").asInt()).isEqualTo(422);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,first,bad.get("clientMessageId"))).isZero();
        }
        // A consistent RR snapshot must not bypass a later committed off-sale row.
        String contested=sku+"-1";var executor=Executors.newSingleThreadExecutor();
        try {
            var tx=new TransactionTemplate(transactions);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.execute(status->{as(first);assertThat(jdbc.queryForObject("SELECT store_status FROM nx_product WHERE product_no=?",String.class,contested)).isEqualTo("on");
                try{executor.submit(()->jdbc.update("UPDATE nx_product SET status='OFF_SALE',store_status='off' WHERE product_no=?",contested)).get(5,TimeUnit.SECONDS);}catch(Exception ex){throw new RuntimeException(ex);}
                var request=new ConversationReplyRequest("Caption","OPEN",0L,"Concurrent SKU validation",run,null,null,"SKU",null,"SERVICE",key(),assignment,contested,null);
                assertThatThrownBy(()->humanMessages.prepare(customer,"ADMIN",first,key(),"REPLY:"+no,request,request.clientMessageId(),"SKU","SERVICE",null,assignment))
                    .isInstanceOfSatisfying(ffdd.opsconsole.shared.exception.BizException.class,ex->assertThat(ex.getCode()).isEqualTo(409));status.setRollbackOnly();return true;});
        }finally{executor.shutdownNow();}
        long countBefore=jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=?",Long.class,customer);
        String legacy="Historical genesis message remains readable; unavailable purchase is not invented";
        jdbc.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content) SELECT id,conversation_no,?,'agent','Old unknown advisor',? FROM nx_conversation WHERE conversation_no=?",second,legacy,no);
        assertThat(http("GET","/api/app/support/conversations/"+no,client,null,null).toString()).contains(legacy);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=?",Long.class,customer)).isEqualTo(countBefore);
        proof("R03","Real SKU search 16 on-sale rows over 4 pages, caption/name/id persisted to client; down-sale new send denied, committed replay unchanged; two actual RR connections prove current locked status wins");
        proof("R04","Valid typed destination committed through actual WS; administrative/arbitrary/retired destinations rejected before any message, legacy text untouched");writeProof("sku-link-runtime.json");
    }
    private Map<String,Object> reply(String no,String owner,long assignment,String kind) throws Exception {
        var view=http("GET","/api/admin/content/conversations/"+no,owner,null,null).path("data").path("conversation");
        return new LinkedHashMap<>(Map.of("body","Protocol caption","expectedStatus",view.path("status").asText(),"expectedVersion",view.path("version").asLong(),"reason","Actual typed reply proof","operator",run,"kind",kind,"clientMessageId",key(),"expectedAssignmentId",assignment));
    }
    private com.fasterxml.jackson.databind.JsonNode findMessage(com.fasterxml.jackson.databind.JsonNode rows,long id){for(var row:rows)if(row.path("id").asLong()==id)return row;throw new AssertionError("Message missing "+id);}
    private SocketProbe socket(String token) throws Exception {
        return socket(token,false);
    }
    private SocketProbe socket(String token,boolean app) throws Exception {
        var ticket=http("POST",app?"/api/app/support/realtime-ticket":"/api/admin/content/conversations/realtime-ticket",token,null,null);assertThat(ticket.path("code").asInt()).isZero();var probe=new SocketProbe();
        probe.socket=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:18141/ws/conversations"),probe).get(10,TimeUnit.SECONDS);
        probe.send(Map.of("type","auth","ticket",ticket.path("data").path("ticket").asText()));probe.await("ready");return probe;
    }
    private final class SocketProbe implements java.net.http.WebSocket.Listener,AutoCloseable {
        java.net.http.WebSocket socket;final BlockingQueue<String> frames=new LinkedBlockingQueue<>();final StringBuilder buffer=new StringBuilder();
        public void onOpen(java.net.http.WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(java.net.http.WebSocket ws,CharSequence data,boolean last){buffer.append(data);if(last){frames.add(buffer.toString());buffer.setLength(0);}ws.request(1);return null;}
        void send(Object frame)throws Exception{socket.sendText(json.writeValueAsString(frame),true).get(5,TimeUnit.SECONDS);}
        com.fasterxml.jackson.databind.JsonNode await(String type)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){String frame=frames.poll(200,TimeUnit.MILLISECONDS);if(frame!=null){var data=json.readTree(frame);if(type.equals(data.path("type").asText()))return data;}}throw new AssertionError("Missing event "+type);}
        com.fasterxml.jackson.databind.JsonNode presence(String field,boolean expected)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){String frame=frames.poll(200,TimeUnit.MILLISECONDS);if(frame!=null){var data=json.readTree(frame);if("presence".equals(data.path("type").asText())&&data.path(field).asBoolean()==expected)return data;}}throw new AssertionError("Missing presence "+field+"="+expected);}
        public void close(){socket.abort();}
    }
    static class EventCapture {
        final List<ConversationMessageEvent> events=new CopyOnWriteArrayList<>();
        @org.springframework.context.event.EventListener public void event(ConversationMessageEvent event){events.add(event);}
    }
    @Test void existingAssignmentSurvivesUnavailableAdvisorAndManualRandomRace() throws Exception {
        rules("UNLIMITED",null,"SUPERVISOR");long root=customer(null);transfer(first,root);long child=customer(root);long rootAssignment=mapper.current(root).id(),childAssignment=mapper.current(child).id();
        String client=userToken(root);
        jdbc.update("UPDATE nx_support_agent_profile SET busy=1,max_concurrent=0 WHERE admin_id=?",first);
        assertThat(http("POST","/api/app/support/conversations",client,Map.of("conversationType","advisor","openingText","Busy advisor preserves identity"),key()).path("code").asInt()).isZero();
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",first);
        assertThat(http("POST","/api/app/support/conversations",client,Map.of("conversationType","advisor","openingText","Disabled advisor preserves identity"),key()).path("code").asInt()).isZero();
        assertThat(mapper.current(root).id()).isEqualTo(rootAssignment);assertThat(mapper.current(child).id()).isEqualTo(childAssignment);
        transfer(second,root);assertThat(mapper.current(root).agentAdminId()).isEqualTo(second);assertThat(mapper.current(child).agentAdminId()).isEqualTo(first);
        proof("A08","Actual client questions retain busy and disabled advisor assignment; explicit supervisor transfer changes selected root only, not its descendant");
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=1 WHERE admin_id=?",first);
        long contested=customer(null);var preview=random.preview(new SupportRandomRequest.Preview(List.of(pool(contested)),false,null,null));
        var randomCommand=new SupportRandomRequest.Confirm(preview.id(),preview.rulesVersion(),"Random versus explicit assignment race");
        var manualCommand=new SupportBindingRequest(second,List.of(new SupportBindingRequest.Customer(contested,null,mapper.poolVersion(contested))),"Manual versus random assignment race");
        var executor=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            var automatic=executor.submit(()->{as(boss);ready.countDown();go.await();return random.confirm(key(),randomCommand);});
            var manual=executor.submit(()->{as(boss);ready.countDown();go.await();try{return bindings.transfer(key(),manualCommand).getCode();}catch(ffdd.opsconsole.shared.exception.BizException ex){return ex.getCode();}});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(automatic.get(20,TimeUnit.SECONDS).getCode()).isZero();assertThat(manual.get(20,TimeUnit.SECONDS)).isIn(0,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE' AND is_deleted=0",Long.class,contested)).isEqualTo(1);
        }finally{executor.shutdownNow();}
        // Holding the admin lock lets a previously eligible snapshot wait behind a disable commit.
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",second);long waiting=customer(null);
        var candidate=random.preview(new SupportRandomRequest.Preview(List.of(pool(waiting)),false,null,null));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);executor=Executors.newFixedThreadPool(2);
        try {
            var disable=executor.submit(()->new TransactionTemplate(transactions).execute(status->{mapper.lockAgent(first);locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",first);return true;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var draw=executor.submit(()->{as(boss);return random.confirm(key(),new SupportRandomRequest.Confirm(candidate.id(),candidate.rulesVersion(),"Candidate disable current-read proof"));});
            assertThatThrownBy(()->draw.get(1,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);release.countDown();disable.get(10,TimeUnit.SECONDS);
            assertThat(draw.get(10,TimeUnit.SECONDS).getCode()).isZero();assertThat(mapper.current(waiting)).isNull();
        }finally{release.countDown();executor.shutdownNow();}
        proof("A09","Separate real connections race manual/random with one active binding; held selected advisor lock then committed disable yields no assignment and no deadlock");writeProof("binding-availability-runtime.json");
    }
    @Test void accountCommandReplayUsesCurrentActorAndLegacyReceiptsFailClosed() throws Exception {
        long otherSuper=admin("other_super","SUPER_ADMIN","MANAGER");String actorToken=token(boss),otherToken=token(otherSuper),operation=key();
        var body=Map.of("username",run+"_replay_account","displayName","Replay account","email",run+"_replay@example.invalid","role","support","reason","Current actor replay isolation proof","operator",run);
        var created=http("POST","/api/admin/platform/accounts",actorToken,body,operation);assertThat(created.path("code").asInt()).isZero();createdAdmins.add(created.path("data").path("id").asLong());
        assertThat(http("POST","/api/admin/platform/accounts",actorToken,body,operation)).isEqualTo(created);
        var foreign=http("POST","/api/admin/platform/accounts",otherToken,body,operation);assertThat(foreign.path("code").asInt()).isNotZero();assertThat(foreign.toString()).doesNotContain("temporaryPassword");
        var dto=json.convertValue(body,ffdd.opsconsole.platform.dto.AdminAccountCreateRequest.class);String legacyKey=key();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("|"+dto).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO nx_admin_idempotency_record(scope,idempotency_key,request_hash,status,response_json,expires_at) VALUES('A1:ACCOUNT_CREATE',?,?,'SUCCEEDED',?,DATE_ADD(NOW(),INTERVAL 1 DAY))",legacyKey,hash,"{\"code\":0,\"data\":{\"temporaryPassword\":\"legacy-placeholder\"}}");
        var legacy=http("POST","/api/admin/platform/accounts",otherToken,body,legacyKey);assertThat(legacy.path("code").asInt()).isEqualTo(409);assertThat(legacy.toString()).doesNotContain("legacy-placeholder","temporaryPassword");
        jdbc.update("UPDATE nx_admin_role_relation SET is_deleted=1 WHERE admin_id=?",boss);jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code='SUPPORT' AND is_deleted=0",boss);jdbc.update("UPDATE nx_admin SET super_admin=0 WHERE id=?",boss);
        as(boss);var revoked=accounts.createAccount(operation,dto);assertThat(revoked.getCode()).isEqualTo(403);assertThat(revoked.getData()).isNull();
        permissions.evict(boss);var revokedHttp=http("POST","/api/admin/platform/accounts",actorToken,body,operation);assertThat(revokedHttp.path("code").asInt()).isEqualTo(403);assertThat(revokedHttp.toString()).doesNotContain("temporaryPassword");
        proof("X01","Actual A1 HTTP cache isolated by actor; second SUPER never sees first temporary credential, unprovable legacy receipt fails 409; downgraded actor denied via HTTP and direct Controller with retained authority before cached body");writeProof("account-replay-runtime.json");
    }
    @Test void originalAccountActionsAndTimeoutPermissionsRemainIndependentOfServiceRead() throws Exception {
        // Restore only the existing C5 event registry contract missing from the old local snapshot.
        String originalMigration=Files.readString(Path.of("scripts/migrations/20260719_c5_security_closure.sql"));
        int start=originalMigration.indexOf("INSERT INTO nx_event_schema_registry"),end=originalMigration.indexOf("INSERT INTO nx_event_schema_property",originalMigration.indexOf("INSERT INTO nx_event_schema_property",start)+1);
        assertThat(start).isPositive();assertThat(end).isGreaterThan(start);
        try(var connection=jdbc.getDataSource().getConnection()) {org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(originalMigration.substring(start,end).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        String accountMigration=Files.readString(Path.of("scripts/migrations/20260718_c2_account_action_closure.sql"));
        try(var connection=jdbc.getDataSource().getConnection()) {org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ByteArrayResource(accountMigration.substring(accountMigration.indexOf("INSERT INTO nx_event_schema_registry")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        rules("UNLIMITED",null,"SUPERVISOR");long customer=customer(null);transfer(first,customer);String owner=token(first),supervisor=token(boss),path="/api/admin/content/support-workbench/customers/"+customer+"/360",userPath="/api/admin/users/profiles/"+customer;
        var original=http("GET",path,owner,null,null).path("data").path("profile").path("actions");
        assertThat(original.path("resetPassword").path("allowed").asBoolean()).isFalse();assertThat(original.path("freeze").path("allowed").asBoolean()).isFalse();
        assertThat(original.path("resetPassword").path("reason").asText()).isEqualTo("PERMISSION_REQUIRED");
        var reset=new LinkedHashMap<String,Object>(Map.of("reason","Original confirmed security flow","operator",run,"operatorConfirmed",false));
        assertThat(http("POST",userPath+"/security/password-reset",owner,reset,key()).path("code").asInt()).isEqualTo(403);
        var superActions=http("GET",path,supervisor,null,null).path("data").path("profile").path("actions");assertThat(superActions.path("resetPassword").path("allowed").asBoolean()).isTrue();
        assertThat(http("POST",userPath+"/security/password-reset",supervisor,reset,key()).path("code").asInt()).isEqualTo(422);
        reset.put("operatorConfirmed",true);String resetKey=key();var resetResult=http("POST",userPath+"/security/password-reset",supervisor,reset,resetKey);assertThat(resetResult.path("code").asInt()).as("Reset result: %s",resetResult.path("message").asText()).isZero();
        assertThat(jdbc.queryForObject("SELECT password_reset_required FROM nx_user_security WHERE user_id=?",Boolean.class,customer)).isTrue();
        var freeze=Map.of("status","FROZEN","reasonCode","OTHER","reason","Original account freeze proof","operator",run);
        assertThat(http("PATCH",userPath+"/status",owner,freeze,key()).path("code").asInt()).isEqualTo(403);
        var frozen=http("PATCH",userPath+"/status",supervisor,freeze,key());assertThat(frozen.path("code").asInt()).as("Freeze: %s",frozen.path("message").asText()).isZero();assertThat(jdbc.queryForObject("SELECT status FROM nx_user WHERE id=?",String.class,customer)).isEqualTo("FROZEN");
        assertThat(http("GET",path,supervisor,null,null).path("data").path("profile").path("identity").path("data").path("accountStatus").asText()).isEqualTo("FROZEN");
        assertThat(http("PATCH",userPath+"/status",supervisor,Map.of("status","ACTIVE","reason","Original account unfreeze proof","operator",run),key()).path("code").asInt()).isZero();
        var grants=jdbc.queryForList("SELECT rp.id FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code='SUPPORT' AND p.permission_code='user_c3_adjust_create' AND rp.is_deleted=0",Long.class);
        assertThat(grants).isNotEmpty();
        try {grants.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE id=?",id));permissions.evict(first);
            var restricted=http("GET",path,owner,null,null);assertThat(restricted.path("code").asInt()).isZero();assertThat(restricted.path("data").path("profile").path("actions").path("adjustBalance").path("allowed").asBoolean()).isFalse();
            long adjustments=jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_asset_adjustment WHERE user_id=?",Long.class,customer);
            assertThat(http("POST",userPath+"/asset-adjustments",owner,Map.of("reason","No finance write grant"),key()).path("code").asInt()).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_wallet_asset_adjustment WHERE user_id=?",Long.class,customer)).isEqualTo(adjustments);
        }finally{grants.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=0 WHERE id=?",id));permissions.evict(first);}
        String timeout="/api/admin/content/conversations/timeout-policy";var policy=http("GET",timeout,supervisor,null,null);assertThat(policy.path("code").asInt()).isZero();
        int oldWarn=policy.path("data").path("warnMinutes").asInt(),oldClose=policy.path("data").path("closeMinutes").asInt(),newWarn=oldWarn==7?6:7,newClose=Math.max(oldClose,newWarn+1);
        var update=Map.of("warnMinutes",newWarn,"closeMinutes",newClose,"expectedVersion",policy.path("data").path("version").asLong(),"reason","Original timeout policy roundtrip","operator",run);
        assertThat(http("PUT",timeout,owner,update,key()).path("code").asInt()).isEqualTo(403);assertThat(http("PUT",timeout,supervisor,update,key()).path("code").asInt()).isZero();
        assertThat(http("GET",timeout,supervisor,null,null).path("data").path("version").asLong()).isEqualTo(policy.path("data").path("version").asLong()+1);
        assertThat(http("GET",timeout,supervisor,null,null).path("data").path("warnMinutes").asInt()).isEqualTo(newWarn);
        assertThat(http("PUT",timeout,supervisor,Map.of("warnMinutes",oldWarn,"closeMinutes",oldClose,"expectedVersion",policy.path("data").path("version").asLong()+1,"reason","Restore isolated original timeout policy","operator",run),key()).path("code").asInt()).isZero();
        assertThat(mapper.current(customer).agentAdminId()).isEqualTo(first);
        proof("R23","Real owner service read does not grant password reset; SUPER retains original confirmation, reason and real persisted password-reset state");
        proof("R24","Real original freeze/unfreeze and revoked financial grant checked independently; service profile remains readable without finance write and forbidden operation writes nothing");
        proof("R33","Actual timeout read/save/refresh uses original manage grant, ordinary advisor denied and binding unchanged");writeProof("original-actions-runtime.json");
    }
    @Test void presenceTypingAndReadReceiptsUseActualSignalsWithoutReplying() throws Exception {
        rules("UNLIMITED",null,"SUPERVISOR");long customer=customer(null);transfer(first,customer);String owner=token(first),client=userToken(customer);
        var created=http("POST","/api/app/support/conversations",client,Map.of("conversationType","support","openingText","Actual pending customer signal"),key());assertThat(created.path("code").asInt()).isZero();String no=created.path("data").path("conversation").path("conversationNo").asText();
        long pending=mapper.pendingReplies(no);
        assertThat(pending).isPositive();
        try(var advisor=socket(owner)) {
            advisor.send(Map.of("type","watch","conversationNo",no));advisor.presence("online",false);
            try(var customerSocket=socket(client,true)) {
                customerSocket.send(Map.of("type","watch","conversationNo",no));advisor.presence("online",true);
                customerSocket.send(Map.of("type","typing","conversationNo",no,"active",true));advisor.presence("typing",true);
                customerSocket.send(Map.of("type","typing","conversationNo",no,"active",false));advisor.presence("typing",false);
            }
            advisor.presence("online",false);
        }
        assertThat(mapper.pendingReplies(no)).isEqualTo(pending);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_maintenance_execution WHERE customer_id=?",Long.class,customer)).isZero();
        proof("R34","Actual authenticated client WS join/typing true/false/disconnect drives presence; signals never handle pending messages or create maintenance execution; original MySQL read/reply mutex proves exact receipts independently");writeProof("presence-runtime.json");
    }
    @Test void eventsArePublishedOnlyAfterActualTransactionCommit() throws Exception {
        String no="enhance-after-commit-"+run;
        var event=ConversationMessageEvent.builder().conversationNo(no).eventType(ConversationMessageEvent.EventType.MESSAGE).senderType("AGENT").body("Committed only").build();
        new TransactionTemplate(transactions).execute(status->{jdbc.update("INSERT INTO nx_customer_note(user_id,author,content) VALUES(?,?,'Rollback marker')",customer(null),run);
            OpsConversationAfterCommitPublisher.publish(eventPublisher,event);assertThat(events.events).isEmpty();status.setRollbackOnly();return true;});
        assertThat(events.events).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_customer_note WHERE author=?",Long.class,run)).isZero();
        new TransactionTemplate(transactions).execute(status->{OpsConversationAfterCommitPublisher.publish(eventPublisher,event);assertThat(events.events).isEmpty();return true;});
        assertThat(events.events).containsExactly(event);proof("X01","Shared publisher emits no client event on real JDBC rollback and exactly one after commit");writeProof("publisher-runtime.json");
    }
    private byte[] png(int color) throws Exception {var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,color);var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);return output.toByteArray();}
    private com.fasterxml.jackson.databind.JsonNode uploadAvatar(String token,byte[] bytes,String mime,String client,String key) throws Exception {
        String boundary="enhance"+UUID.randomUUID();var output=new java.io.ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"clientUploadId\"\r\n\r\n"+client+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(bytes);output.write(("\r\n--"+boundary+"--\r\n").getBytes());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18141/api/admin/platform/accounts/avatar-assets")).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build();
        return json.readTree(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
    private HttpResponse<byte[]> download(String path,String token) throws Exception {var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18141"+path)).header("Authorization","Bearer "+token).GET().build();return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofByteArray());}
    private String userToken(long customer) throws Exception {
        assertThat(onboarding.defer(customer,new ffdd.opsconsole.onboarding.application.OnboardingCalibrationService.ActionRequest("enhance-device-"+customer,0,key())).getCode()).isZero();
        String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",customer,session,session);
        String token=tokens.createUserToken(customer,"enhance-customer",List.of(),session,java.time.Duration.ofHours(8),UserAuthEnvironment.PRODUCTION);
        var terms=http("GET","/api/legal/terms/current?locale=en&jurisdiction=GLOBAL",token,null,null);assertThat(terms.path("code").asInt()).isZero();
        assertThat(http("POST","/api/legal/terms/acknowledgment",token,Map.of("locale","en","jurisdiction","GLOBAL","version",terms.path("data").path("version").asText(),"confirmed",true,"idempotencyKey",key(),"runId",""),null).path("code").asInt()).isZero();return token;
    }
    private long ledger(long customer,String no,String type,String asset,String amount) {
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,?,?,'IN',?,0,'SUCCESS','2026-09-01 12:00:00')",customer,no,type,asset,new java.math.BigDecimal(amount));
        return jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE user_id=? AND biz_no=? AND biz_type=?",Long.class,customer,no,type);
    }
    private long deposit(long customer,String no,String asset,String amount,String type) {
        long id=ledger(customer,no,type,asset,amount);
        jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at,created_at) VALUES(?,?,'TRC20',?,?,?,'SUCCESS',?,'2026-09-01 12:00:00','2026-09-01 12:00:00')",customer,no,no,asset,new java.math.BigDecimal(amount),id);return id;
    }
    private void withdrawal(long customer,String no,String state,String amount,String fee,String net,boolean completed) {
        jdbc.update("INSERT INTO nx_withdrawal_order(user_id,withdrawal_no,asset,amount,target_address,status,d2_penalty_fee_rate,d2_gross_fee,d2_nex_burned,d2_nex_fee_offset_rate,d2_fee_waived,d2_actual_fee,d2_net_receive,completed_at,created_at) VALUES(?,?,'USDT',?,'isolated-address',?,0,0,0,0,0,?,?,?,'2026-09-01 12:00:00')",customer,no,new java.math.BigDecimal(amount),state,new java.math.BigDecimal(fee),new java.math.BigDecimal(net),completed?java.time.LocalDateTime.of(2026,9,1,12,0):null);
    }
    @Test void partialFailureResumesFrozenRecipientsWithoutRedrawingCommittedCustomer() throws Exception {
        rules("UNCONFIGURED",null,"SUPERVISOR");long one=customer(null),two=customer(null);
        var preview=random.preview(new SupportRandomRequest.Preview(List.of(pool(one),pool(two)),false,null,null));
        var command=new SupportRandomRequest.Confirm(preview.id(),preview.rulesVersion(),"Recover actual partial recipient commits");
        String operation=key(),constraint="enhance_partial_"+run.substring(8);
        boolean constrained=false;
        try {
            jdbc.execute("ALTER TABLE nx_support_random_result ADD CONSTRAINT "+constraint+" CHECK(customer_id<>"+two+")");constrained=true;
            assertThatThrownBy(()->random.confirm(operation,command)).isInstanceOf(RuntimeException.class);
            assertThat(mapper.current(one)).isNotNull();assertThat(mapper.current(two)).isNull();
            long original=mapper.current(one).id();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_random_result WHERE actor_id=? AND operation_id=?",Long.class,boss,operation)).isEqualTo(1L);
            jdbc.update("UPDATE nx_admin_idempotency_record SET status='UNKNOWN',response_json=NULL WHERE scope=? AND idempotency_key=?","SUPPORT_RANDOM:"+boss,operation);
            jdbc.execute("ALTER TABLE nx_support_random_result DROP CHECK "+constraint);constrained=false;
            assertThat(random.confirm(operation,command).getCode()).isZero();
            assertThat(mapper.current(one).id()).isEqualTo(original);assertThat(mapper.current(two)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_random_result WHERE actor_id=? AND operation_id=?",Long.class,boss,operation)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE' AND is_deleted=0",Long.class,one)).isEqualTo(1L);
            proof("A10","Real second-recipient database fault preserves first commit; UNKNOWN resumes only missing recipient, same assignment ID");
            writeProof("random-partial-runtime.json");
        } finally {if(constrained)jdbc.execute("ALTER TABLE nx_support_random_result DROP CHECK "+constraint);}
    }

    @Test void competingAllocationsAndModePauseLinearizeAcrossSeparateConnections() throws Exception {
        rules("UNCONFIGURED",null,"SUPERVISOR");long customer=customer(null);
        var p=random.preview(new SupportRandomRequest.Preview(List.of(pool(customer)),false,null,null));
        var command=new SupportRandomRequest.Confirm(p.id(),p.rulesVersion(),"Competing frozen random confirmations");
        var executor=Executors.newFixedThreadPool(3);var started=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            var one=executor.submit(()->{as(boss);started.countDown();go.await();return random.confirm(key(),command);});
            var two=executor.submit(()->{as(boss);started.countDown();go.await();return random.confirm(key(),command);});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(one.get(20,TimeUnit.SECONDS).getCode()).isZero();assertThat(two.get(20,TimeUnit.SECONDS).getCode()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE' AND is_deleted=0",Long.class,customer)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_random_result WHERE customer_id=? AND status='ASSIGNED'",Long.class,customer)).isEqualTo(1L);
            proof("A09","Two actual connection transactions compete for same customer; exactly one active binding, second recipient conflict");
            jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id IN (?,?)",first,second);
            rules("UNCONFIGURED",null,"AUTO_RANDOM");long waiting=customer(null);assertThat(mapper.current(waiting)).isNull();
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            var mode=executor.submit(()->new TransactionTemplate(transactions).execute(status->{as(boss);mapper.lockRules();
                jdbc.update("UPDATE nx_support_rules SET unbound_assignment_mode='SUPERVISOR',version=version+1 WHERE id=1");locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("mode release missing");}catch(InterruptedException ex){throw new IllegalStateException(ex);}return true;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var retry=executor.submit(()->{bindings.retryAutomatic(waiting);return true;});
            assertThatThrownBy(()->retry.get(1,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();assertThat(mode.get(10,TimeUnit.SECONDS)).isTrue();assertThat(retry.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(mapper.current(waiting)).isNull();assertThat(jdbc.queryForObject("SELECT auto_attempt_state FROM nx_support_binding_pool WHERE customer_id=?",String.class,waiting)).isEqualTo("PAUSED");
            proof("A07","Held rules row blocks separate retry transaction; committed supervisor mode wins before drawing");writeProof("random-concurrency-runtime.json");
        }finally{executor.shutdownNow();}
    }
    @Test void originalDuplicateAndOrphanMigrationAbortBeforeChangingRows() throws Exception {
        String schema="cs_enhance_20261001_c1_audit";
        String base="jdbc:mysql://127.0.0.1:33329/";
        String options="?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        var server=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                base+options,System.getenv("NEXION_DB_USERNAME"),System.getenv("NEXION_DB_PASSWORD")));
        boolean ownsSchema=false;
        try {
            server.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");ownsSchema=true;
            var fixture=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    base+schema+options,System.getenv("NEXION_DB_USERNAME"),System.getenv("NEXION_DB_PASSWORD")));
            for(String ddl:List.of(
                    "CREATE TABLE nx_support_agent_user_assignment(id BIGINT PRIMARY KEY,user_id BIGINT,agent_admin_id BIGINT,status VARCHAR(16),is_deleted INT,starts_at DATETIME,ends_at DATETIME)",
                    "CREATE TABLE nx_admin(id BIGINT PRIMARY KEY,status INT,is_deleted INT)",
                    "CREATE TABLE nx_support_agent_profile(admin_id BIGINT PRIMARY KEY,enabled INT,is_deleted INT,seat_type VARCHAR(16),service_types VARCHAR(64))",
                    "CREATE TABLE nx_user(id BIGINT PRIMARY KEY,is_deleted INT)",
                    "CREATE TABLE nx_admin_role_relation(admin_id BIGINT,role_id BIGINT,is_deleted INT)",
                    "CREATE TABLE nx_admin_role(id BIGINT PRIMARY KEY,role_code VARCHAR(16),status INT,is_deleted INT)"))fixture.execute(ddl);
            fixture.update("INSERT INTO nx_admin VALUES(10,1,0)");
            fixture.update("INSERT INTO nx_support_agent_profile VALUES(10,1,0,'DEDICATED','advisor')");
            fixture.update("INSERT INTO nx_user VALUES(1,0)");
            fixture.update("INSERT INTO nx_admin_role VALUES(1,'SUPPORT',1,0)");
            fixture.update("INSERT INTO nx_admin_role_relation VALUES(10,1,0)");
            String sql=Files.readString(Path.of("scripts/migrations/20260929_support_binding_s3.sql"));
            int start=sql.indexOf("CREATE PROCEDURE"),end=sql.indexOf("END$$",start);
            assertThat(start).isGreaterThanOrEqualTo(0);assertThat(end).isGreaterThan(start);
            fixture.execute(sql.substring(start,end+3));
            fixture.update("INSERT INTO nx_support_agent_user_assignment VALUES(1,1,10,'ACTIVE',0,NOW(),NULL),(2,1,10,'ACTIVE',0,NOW(),NULL)");
            assertThatThrownBy(()->fixture.execute("CALL support_binding_s3_migrate()"))
                    .hasStackTraceContaining("SUPPORT_DUPLICATE_ASSIGNMENT_REVIEW_REQUIRED");
            assertThat(fixture.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment",Long.class)).isEqualTo(2L);
            assertThat(fixture.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='nx_support_agent_user_assignment' AND column_name='version'",Long.class,schema)).isZero();
            fixture.update("DELETE FROM nx_support_agent_user_assignment");
            fixture.update("INSERT INTO nx_support_agent_user_assignment VALUES(3,999,10,'ACTIVE',0,NOW(),NULL)");
            assertThatThrownBy(()->fixture.execute("CALL support_binding_s3_migrate()"))
                    .hasStackTraceContaining("SUPPORT_ORPHAN_ASSIGNMENT_REVIEW_REQUIRED");
            assertThat(fixture.queryForObject("SELECT user_id FROM nx_support_agent_user_assignment",Long.class)).isEqualTo(999L);
        } finally {
            if(ownsSchema) server.execute("DROP DATABASE "+schema);
        }
    }

    @Test void originalOrphanCycleAndUnknownInheritanceRemainReviewRequired() {
        rules("UNLIMITED",null,"AUTO_RANDOM");
        for(String malformed:List.of("ORPHAN_ROOT","SPONSOR_CYCLE","UNKNOWN_DEPTH","UNKNOWN_PARENT")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                status.setRollbackOnly();
                long inviter=customer(null),other=customer(null);
                var assignment=mapper.current(inviter);
                if("ORPHAN_ROOT".equals(malformed)) jdbc.update("UPDATE nx_support_agent_user_assignment SET segment_root_id=9007199254740991 WHERE id=?",assignment.id());
                if("SPONSOR_CYCLE".equals(malformed)) {
                    jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",other,inviter);
                    jdbc.update("UPDATE nx_user SET sponsor_user_id=? WHERE id=?",inviter,other);
                }
                if("UNKNOWN_DEPTH".equals(malformed)) jdbc.update("UPDATE nx_support_agent_user_assignment SET depth=NULL WHERE id=?",assignment.id());
                if("UNKNOWN_PARENT".equals(malformed)) jdbc.update("UPDATE nx_support_agent_user_assignment SET depth=1,parent_assignment_id=9007199254740991 WHERE id=?",assignment.id());
                mybatisSession.clearCache();
                long child=customer(inviter);
                assertThat(mapper.current(child)).as(malformed).isNull();
                assertThat(mapper.poolReason(child)).as(malformed).isEqualTo("MIGRATION_REVIEW");
                assertThat(mapper.autoEligible(child)).as(malformed).isFalse();
                bindings.retryAutomatic(child);
                assertThat(mapper.current(child)).isNull();
                as(boss);
                var preview=random.preview(new SupportRandomRequest.Preview(List.of(pool(child)),false,null,null));
                assertThat(preview.count()).isZero();
                assertThat(preview.excluded()).extracting(SupportRandom.Excluded::reason).containsExactly("REVIEW_REQUIRED");
            });
        }
    }
    private void writeProof(String filename) throws Exception {Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"),filename),json.writeValueAsString(Map.of("run",run,"checkedAt",java.time.Instant.now().toString(),"database","cs_enhance_20261001","checks",proofs,"workflowRunId",System.getenv().getOrDefault("WORKFLOW_RUN_ID",""),"snapshotHash",System.getenv().getOrDefault("WORKFLOW_SNAPSHOT_HASH",""))));}
    private SupportRandom.Customer pool(long customer){return new SupportRandom.Customer(customer,mapper.poolVersion(customer));}
    private long admin(String label,String role,String seat) {
        String name=run+"_"+label;
        jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname,super_admin,status) VALUES(?,'fixture-disabled-password',?,?,1)",name,label,"SUPER_ADMIN".equals(role)?1:0);
        long id=jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,name);
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code=? AND is_deleted=0",id,role);
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,?,?,'support,advisor','',0,1,1,0)",id,seat,seat);
        createdAdmins.add(id);return id;
    }
    private long customer(Long inviter) {
        return new TransactionTemplate(transactions).execute(status->{
            if(inviter!=null)mapper.lockCustomer(inviter);
            String referral=UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();
            String phone="198"+String.format("%08d",Math.abs((long)referral.hashCode())%100000000);
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,sponsor_user_id,status,sandbox) VALUES('+86',?,'127.0.0.1','fixture-disabled-password',?,?,?,'ACTIVE',0)",phone,run,referral,inviter);
            long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);bindings.register(id,inviter);return id;
        });
    }
    private void rules(String inheritance,Integer depth,String mode) {as(boss);var rules=mapper.rules();assertThat(bindings.updateRules(key(),new SupportRulesRequest(null,null,null,inheritance,depth,rules.version(),"Core isolated rules proof",mode)).getCode()).isZero();}
    private void as(long id) {var auth=new UsernamePasswordAuthenticationToken(String.valueOf(id),null,List.of(new SimpleGrantedAuthority("platform_a1_write"),new SimpleGrantedAuthority("platform_a1_read"),new SimpleGrantedAuthority("service_m1_write"),new SimpleGrantedAuthority("service_m1_read"),new SimpleGrantedAuthority("service_m3_read"),new SimpleGrantedAuthority("service_m3_write")));auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);}
    private String token(long id){String username=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);return tokens.createToken(id,"ADMIN",username,List.of(),sessions.createSession(id,username));}
    private String key(){return "enhance-"+UUID.randomUUID();}
    private com.fasterxml.jackson.databind.JsonNode http(String method,String path,String token,Object body,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18141"+path)).timeout(java.time.Duration.ofSeconds(20));
        if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);
        request.header("Content-Type","application/json");request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return json.readTree(HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
}
