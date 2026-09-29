package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.shared.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in runner supplies the complete isolated MySQL/Redis bundle; no fallback catalog. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=18129"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="S3_EVIDENCE_DIR",matches=".+")
class SupportBindingRuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingMapper mapper;
    @Autowired SupportOwnershipService ownership;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired AdminPermissionCache permissions;
    @Autowired ObjectMapper json;
    @Autowired AppSupportService app;
    @Autowired ffdd.opsconsole.auth.application.AppUserRegistrationService registration;
    @Autowired ffdd.opsconsole.auth.mapper.AppUserRegistrationMapper registrationMapper;
    @Autowired org.springframework.context.ConfigurableApplicationContext context;
    @Autowired ffdd.opsconsole.onboarding.application.OnboardingCalibrationService onboarding;
    @Autowired ffdd.opsconsole.content.web.OpsConversationController conversationCommands;
    @Autowired ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper timeoutMapper;
    private final String run="s3_"+UUID.randomUUID().toString().replace("-", "").substring(0,10);
    private long superId,manager,g1,g2,a,b,c,d;
    private final Map<String,Object> fixture=new LinkedHashMap<>();
    private final Map<String,String> proofs=new LinkedHashMap<>();

    @Test void globalSearchDoesNotSubstituteForModuleReadAndDisabledManagerCannotMutate() throws Exception {
        long boss=admin("GLOBAL_BOSS","SUPER_ADMIN","MANAGER"),manager=admin("DISABLED_MANAGER","SUPPORT","MANAGER"),agent=admin("GLOBAL_AGENT","SUPPORT","DEDICATED");
        as(boss);long customer=register("GLOBAL_CUSTOMER",null);transfer(agent,List.of(customer),key());
        var c=app.startConversation(customer,key(),new AppSupportService.StartConversationRequest("support","Global read grant probe")).getData().conversation();
        String token=token(boss),managerToken=token(manager),path="/api/admin/platform/search?keyword="+c.conversationNo();
        assertThat(http("GET",path,token,null,null).toString()).contains(c.conversationNo());
        var ids=jdbc.queryForList("SELECT rp.id FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code='SUPER_ADMIN' AND p.permission_code='service_m3_read' AND rp.is_deleted=0",Long.class);
        try {
            ids.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE id=?",id));permissions.evict(boss);
            assertThat(http("GET","/api/admin/content/conversations/"+c.conversationNo(),token,null,null).path("code").asInt()).isEqualTo(403);
            var search=http("GET",path,token,null,null);assertThat(search.path("code").asInt()).isZero();assertThat(search.toString()).doesNotContain(c.conversationNo());
        } finally {ids.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=0 WHERE id=?",id));permissions.evict(boss);}
        var profile=new LinkedHashMap<String,Object>(Map.of("position","专属客服","serviceTypes",List.of("support","advisor"),"tags",List.of(),"enabled",true,"busy",true,"maxConcurrent",0,"expectedVersion",1,"reason","Manager profile authority probe"));
        profile.put("operator","spoofed");
        String update="/api/admin/content/support-agents/"+agent+"/profile",profileKey=key();
        var changed=http("PATCH",update,managerToken,profile,profileKey);assertThat(changed.path("code").asInt()).as("enabled manager: %s",changed).isZero();
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",manager);
        assertThat(http("PATCH",update,managerToken,profile,profileKey).path("code").asInt()).isEqualTo(403);
        profile.put("expectedVersion",2);profile.put("busy",false);
        assertThat(http("PATCH",update,managerToken,profile,key()).path("code").asInt()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version FROM nx_support_agent_profile WHERE admin_id=?",Long.class,agent)).isEqualTo(2L);
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"read-grant-evidence.json"),"{\"globalSearchRequiresM3Read\":true,\"disabledManagerFreshAndReplayDenied\":true}");
    }

    @Test void unansweredOldRowsCannotStarveHandledIdleCandidate() {
        String prefix="starve_"+run;
        for(int i=0;i<101;i++) {
            String no=prefix+i;
            jdbc.update("INSERT INTO nx_conversation(conversation_no,user_id,conversation_type,status,last_message,last_message_at,created_at,updated_at) VALUES(?,0,'support','OPEN','probe',DATE_SUB(NOW(),INTERVAL 30 DAY),DATE_SUB(NOW(),INTERVAL 30 DAY),NOW())",no);
            if(i<100) jdbc.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_type,sender_name,content,created_at,updated_at) SELECT id,conversation_no,'user','probe','pending',NOW(),NOW() FROM nx_conversation WHERE conversation_no=?",no);
        }
        var rows=timeoutMapper.selectDueCloseCandidates(java.time.LocalDateTime.now().minusDays(1),100);
        assertThat(rows).anyMatch(row->row.conversationNo().equals(prefix+100));
        assertThat(rows).noneMatch(row->row.conversationNo().startsWith(prefix) && !row.conversationNo().equals(prefix+100));
    }

    @Test void sendingAndTransferSerializeInBothOrders() throws Exception {
        long boss=admin("SEND_LOCK_BOSS","SUPER_ADMIN","MANAGER"),first=admin("SEND_LOCK_G1","SUPPORT","DEDICATED"),second=admin("SEND_LOCK_G2","SUPPORT","DEDICATED");
        as(boss);long customer=register("SEND_LOCK_CUSTOMER",null);transfer(first,List.of(customer),key());
        var c=app.startConversation(customer,key(),new AppSupportService.StartConversationRequest("support","Concurrent source")).getData().conversation();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for(boolean sendFirst:List.of(true,false)) {
                as(boss);transfer(first,List.of(customer),key());
                var view=app.conversation(customer,c.conversationNo()).getData().conversation();
                var reply=new ConversationReplyRequest("Serialized send "+sendFirst,view.status(),view.version(),"Concurrent send transfer check","spoofed");
                var transfer=request(second,List.of(customer));var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
                var holding=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                    as(sendFirst?first:boss);ownership.lockCustomer(customer);held.countDown();
                    try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}
                    return sendFirst?conversationCommands.reply(c.conversationNo(),key(),reply).getCode():bindings.transfer(key(),transfer).getCode();
                }));
                assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var blocked=executor.submit(()->{
                    as(sendFirst?boss:first);
                    try {return sendFirst?bindings.transfer(key(),transfer).getCode():conversationCommands.reply(c.conversationNo(),key(),reply).getCode();}
                    catch(ffdd.opsconsole.shared.exception.BizException ex){return ex.getCode();}
                });
                Thread.sleep(100);assertThat(blocked.isDone()).isFalse();release.countDown();
                assertThat(holding.get(10,java.util.concurrent.TimeUnit.SECONDS)).isZero();
                assertThat(blocked.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(sendFirst?0:404);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=? AND content=?",Long.class,c.conversationNo(),reply.body())).isEqualTo(sendFirst?1L:0L);
                assertThat(mapper.current(customer).agentAdminId()).isEqualTo(second);
            }
        } finally {executor.shutdownNow();}
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"send-transfer-evidence.json"),"{\"sendFirstCommitsBeforeTransfer\":true,\"transferFirstRevokesWaitingSend\":true}");
    }

    @Test void convertedPrivateTextIsHiddenButInternalCollaborationRemains() throws Exception {
        long boss=admin("PRIVATE_BOSS","SUPER_ADMIN","MANAGER"),first=admin("PRIVATE_G1","SUPPORT","DEDICATED"),second=admin("PRIVATE_G2","SUPPORT","DEDICATED");
        String one=token(first),two=token(second),superToken=token(boss);
        for(boolean appConversion:List.of(false,true)) {
            as(boss);long customer=register(appConversion?"PRIVATE_APP":"PRIVATE_ADMIN",null);transfer(first,List.of(customer),key());
            String marker="private"+UUID.randomUUID().toString().replace("-","");
            var c=app.startConversation(customer,key(),new AppSupportService.StartConversationRequest("support",marker)).getData().conversation();
            long through=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,c.conversationNo());
            assertThat(http("POST","/api/admin/content/conversations/"+c.conversationNo()+"/replies",one,
                Map.of("body","Handled source question","expectedStatus",c.status(),"expectedVersion",c.version(),"replyTargets",List.of(Map.of("conversationNo",c.conversationNo(),"throughMessageId",through)),"reason","Private source preparation"),key()).path("code").asInt()).isZero();
            var fresh=app.conversation(customer,c.conversationNo()).getData().conversation();String customerToken=userToken(customer);
            String path=(appConversion?"/api/app/support":"/api/admin/content")+"/conversations/"+c.conversationNo()+"/ticket";
            var converted=http("POST",path,appConversion?customerToken:one,Map.of("category","withdrawal","priority","NORMAL","title",marker,"expectedStatus",fresh.status(),"expectedVersion",fresh.version(),"reason","Private transcript conversion"),key());
            assertThat(converted.path("code").asInt()).as("convert %s: %s",appConversion,converted).isZero();
            var ticket=converted.path("data").path("ticket").path("ticket");String no=ticket.path("ticketNo").asText();
            assertThat(no).isNotBlank();assertThat(ticket.path("sourceConversationNo").asText()).isEqualTo(c.conversationNo());
            String endpoint="/api/admin/content/tickets/"+no,noteKey=key();
            var note=Map.of("body","Internal coordination stays readable","expectedStatus",ticket.path("status").asText(),"expectedVersion",ticket.path("version").asLong(),"reason","Internal coordination verification");
            assertThat(http("POST",endpoint+"/internal-notes",one,note,noteKey).toString()).contains(marker);
            as(boss);transfer(second,List.of(customer),key());
            var hidden=http("GET",endpoint,one,null,null);assertThat(hidden.path("code").asInt()).isZero();
            assertThat(hidden.toString()).doesNotContain(marker).contains("Internal coordination stays readable");
            assertThat(hidden.path("data").path("ticket").path("contentRestricted").asBoolean()).isTrue();
            assertThat(http("GET","/api/admin/content/tickets?scope=all&userId="+customer,one,null,null).toString()).doesNotContain(marker);
            assertThat(http("GET","/api/admin/content/tickets?scope=all&keyword="+marker,one,null,null).path("data").path("total").asLong()).isZero();
            assertThat(http("POST",endpoint+"/internal-notes",one,note,noteKey).toString()).doesNotContain(marker);
            var h=hidden.path("data").path("ticket");
            var changed=http("PATCH",endpoint+"/status",one,Map.of("status","IN_PROGRESS","expectedStatus",h.path("status").asText(),"expectedVersion",h.path("version").asLong(),"reason","Collaboration survives transfer"),key());
            assertThat(changed.path("code").asInt()).isZero();assertThat(changed.toString()).doesNotContain(marker);
            var next=changed.path("data").path("ticket");
            var secondNote=http("POST",endpoint+"/internal-notes",one,Map.of("body","Former advisor may coordinate internally","expectedStatus",next.path("status").asText(),"expectedVersion",next.path("version").asLong(),"reason","Internal permission preserved"),key());
            assertThat(secondNote.path("code").asInt()).isZero();assertThat(secondNote.toString()).doesNotContain(marker).contains("Former advisor may coordinate internally");
            for(String allowed:List.of(two,superToken)) assertThat(http("GET",endpoint,allowed,null,null).toString()).contains(marker);
            assertThat(http("GET","/api/app/support/tickets/"+no,customerToken,null,null).toString()).contains(marker).doesNotContain("Former advisor may coordinate internally");
            var current=http("GET",endpoint,two,null,null).path("data").path("ticket");String privateKey=key();
            var privateReply=Map.of("body","Private reply "+marker,"expectedStatus",current.path("status").asText(),"expectedVersion",current.path("version").asLong(),"reason","Module read revocation verification");
            assertThat(http("POST",endpoint+"/replies",two,privateReply,privateKey).path("code").asInt()).isZero();
            var preEscalation=http("GET",endpoint,two,null,null).path("data").path("ticket");String escalationKey=key();
            var escalation=Map.of("ownerAgentId",String.valueOf(second),"expectedStatus",preEscalation.path("status").asText(),"expectedVersion",preEscalation.path("version").asLong(),"reason","Restricted escalation replay contract");
            if(!appConversion)assertThat(http("POST",endpoint+"/escalate",two,escalation,escalationKey).path("code").asInt()).isZero();
            var grants=jdbc.queryForList("SELECT rp.id FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code='SUPPORT' AND p.permission_code='service_m3_read' AND rp.is_deleted=0",Long.class);
            try {
                grants.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE id=?",id));permissions.evict(second);
                assertThat(http("GET",endpoint,two,null,null).toString()).doesNotContain(marker);
                var receipt=http("GET","/api/admin/content/support-workbench/commands/"+privateKey,two,null,null);
                assertThat(receipt.path("code").asInt()).isZero();assertThat(receipt.toString()).doesNotContain(marker);
                assertThat(http("POST",endpoint+"/replies",two,privateReply,privateKey).toString()).doesNotContain(marker);
                var escalated=http("POST",endpoint+"/escalate",two,escalation,escalationKey);
                assertThat(escalated.path("code").asInt()).as("restricted escalate: %s",escalated).isZero();
                assertThat(escalated.path("data").path("conversation").path("conversationNo").asText()).isNotBlank();
                assertThat(escalated.toString()).doesNotContain(marker);
                assertThat(http("POST",endpoint+"/escalate",two,escalation,escalationKey)).isEqualTo(escalated);
            } finally {grants.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=0 WHERE id=?",id));permissions.evict(second);}
            jdbc.update("UPDATE nx_support_ticket SET source_conversation_no=NULL WHERE ticket_no=?",no);
            assertThat(http("GET",endpoint,one,null,null).toString()).doesNotContain(marker);
            assertThat(http("GET",endpoint,two,null,null).toString()).contains(marker);
        }
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"ticket-private-evidence.json"),"{\"adminAndAppConversion\":true,\"detailListKeywordReplayHidden\":true,\"internalNotesStatusPreserved\":true,\"unknownRestricted\":true,\"currentSupervisorAndAppRead\":true}");
    }

    @Test void disabledProfileCannotReadM1Customers() throws Exception {
        long boss=admin("DISABLED_BOSS","SUPER_ADMIN","MANAGER"),agent=admin("DISABLED_G1","SUPPORT","DEDICATED");as(boss);
        long customer=register("DISABLED_CUSTOMER",null);transfer(agent,List.of(customer),key());String token=token(agent);
        for(String path:List.of("/api/admin/content/support-agents","/api/admin/content/support-agents/page"))
            assertThat(http("GET",path,token,null,null).path("data").path("advisorAssignments").toString()).contains(String.valueOf(customer));
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",agent);
        for(String path:List.of("/api/admin/content/support-agents","/api/admin/content/support-agents/page"))
            assertThat(http("GET",path,token,null,null).path("code").asInt()).as("disabled profile %s",path).isEqualTo(403);
        assertThat(mapper.current(customer).agentAdminId()).isEqualTo(agent);
    }

    @Test void ticketWritesRequireCurrentAdvisorAndPersistActualAuthor() throws Exception {
        long boss=admin("TICKET_BOSS","SUPER_ADMIN","MANAGER"),first=admin("TICKET_G1","SUPPORT","DEDICATED"),second=admin("TICKET_G2","SUPPORT","DEDICATED");
        as(boss);long customer=register("TICKET_CUSTOMER",null);transfer(first,List.of(customer),key());
        String one=token(first),two=token(second),superToken=token(boss);
        var create=Map.of("userId",customer,"category","withdrawal","priority","NORMAL","title","Runtime support ticket","body","Real advisor ticket opening","operator","spoofed","reason","Real ticket ownership verification");
        assertThat(http("POST","/api/admin/content/tickets",two,create,key()).path("code").asInt()).isEqualTo(404);
        assertThat(http("POST","/api/admin/content/tickets",superToken,create,key()).path("code").asInt()).isEqualTo(404);
        var created=http("POST","/api/admin/content/tickets",one,create,key());assertThat(created.path("code").asInt()).as("ticket create: %s",created).isZero();
        var ticket=created.path("data").path("ticket");String no=ticket.path("ticketNo").asText();
        assertThat(jdbc.queryForObject("SELECT sender_id FROM nx_support_ticket_message WHERE ticket_no=? AND sender_type='agent' ORDER BY id LIMIT 1",Long.class,no)).isEqualTo(first);
        String replyKey=key();var reply=Map.of("body","Real ticket reply","expectedStatus",ticket.path("status").asText(),"expectedVersion",ticket.path("version").asLong(),"reason","Real ticket reply authorization");
        assertThat(http("POST","/api/admin/content/tickets/"+no+"/replies",one,reply,replyKey).path("code").asInt()).isZero();
        assertModuleRecoveryRevoked(first,"SUPPORT","service_m2_read",one,replyKey);
        as(boss);transfer(second,List.of(customer),key());
        assertThat(http("POST","/api/admin/content/tickets/"+no+"/replies",one,reply,replyKey).path("code").asInt()).isEqualTo(404);
        var latest=http("GET","/api/admin/content/tickets/"+no,two,null,null).path("data").path("ticket");
        jdbc.update("UPDATE nx_support_agent_profile SET busy=1,transferable=0,max_concurrent=0 WHERE admin_id=?",second);
        var escalation=Map.of("ownerAgentId",String.valueOf(first),"ownerAgentName","spoofed","expectedStatus",latest.path("status").asText(),"expectedVersion",latest.path("version").asLong(),"reason","Escalation retains current advisor");
        var escalated=http("POST","/api/admin/content/tickets/"+no+"/escalate",two,escalation,key());
        assertThat(escalated.path("code").asInt()).as("ticket escalation: %s",escalated).isZero();
        assertThat(escalated.path("data").path("conversation").path("ownerAgentId").asText()).isEqualTo(String.valueOf(second));
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"ticket-evidence.json"),"{\"createReplyEscalateCurrentAdvisor\":true,\"realAuthor\":true,\"oldKeyDenied\":true}");
    }

    @Test void bindingRejectsNumericCoercionWithoutChangingAssignment() throws Exception {
        long boss=admin("INPUT_BOSS","SUPER_ADMIN","MANAGER"),agent=admin("INPUT_G1","SUPPORT","DEDICATED");as(boss);
        long customer=register("INPUT_CUSTOMER",null);String token=token(boss);
        var before=mapper.current(customer);
        var result=http("POST","/api/admin/content/support-agents/assignments/transfer",token,
            Map.of("targetAgentAdminId",new java.math.BigDecimal(agent+".5"),"customers",List.of(Map.of("id",customer,"expectedVersion",1)),"reason","Reject fractional assignment IDs"),key());
        assertThat(result.path("code").asInt()).as("fractional target agent must be rejected").isIn(400,422);
        assertThat(mapper.current(customer)).isEqualTo(before);
        transfer(agent,List.of(customer),key());var current=mapper.current(customer);
        for(String field:List.of("targetAgentAdminId","id","expectedAssignmentId","expectedVersion")) {
            long valid=switch(field){case "targetAgentAdminId"->agent;case "id"->customer;case "expectedAssignmentId"->current.id();default->current.version();};
            for(Object bad:List.of(new java.math.BigDecimal(valid+".5"),String.valueOf(valid),new java.math.BigInteger("9223372036854775808"),9007199254740992L)) {
                var row=new LinkedHashMap<String,Object>(Map.of("id",customer,"expectedAssignmentId",current.id(),"expectedVersion",current.version()));
                var payload=new LinkedHashMap<String,Object>(Map.of("targetAgentAdminId",agent,"customers",List.of(row),"reason","Strict binding number verification"));
                if(field.equals("targetAgentAdminId"))payload.put(field,bad);else row.put(field,bad);
                assertThat(http("POST","/api/admin/content/support-agents/assignments/transfer",token,payload,key()).path("code").asInt()).as("strict %s",field).isIn(400,422);
                assertThat(mapper.current(customer)).isEqualTo(current);
            }
        }
        String legacy="/api/admin/content/support-agents/"+agent+"/assignments";
        var legacyRequest=Map.of("userId",customer,"expectedAssignmentId",current.id(),"expectedVersion",current.version(),"operator","spoofed","reason","Legacy command snapshot test");
        String legacyKey=key();var original=http("POST",legacy,token,legacyRequest,legacyKey);
        assertThat(original.path("code").asInt()).as("legacy assignment: %s",original).isZero();
        assertModuleRecoveryRevoked(boss,"SUPER_ADMIN","service_m1_read",token,legacyKey);
        long other=admin("INPUT_G2","SUPPORT","DEDICATED");transfer(other,List.of(customer),key());
        assertThat(http("POST",legacy,token,legacyRequest,legacyKey)).isEqualTo(original);
        for(String field:List.of("userId","expectedAssignmentId","expectedVersion")) {
            var bad=new LinkedHashMap<String,Object>(legacyRequest);bad.put(field,"1");
            assertThat(http("POST",legacy,token,bad,key()).path("code").asInt()).isIn(400,422);
        }
        assertThat(http("POST",legacy+"/batch",token,Map.of("userIds",List.of(String.valueOf(customer)),"operator","spoofed","reason","Strict batch input verification"),key()).path("code").asInt()).isIn(400,422);
        assertThat(http("PATCH","/api/admin/content/support-agents/"+agent+"/seat-assignment",token,
            Map.of("position","专属客服","userIds",List.of(new java.math.BigDecimal(customer+".5")),"expectedVersion",1,"reason","Strict seat input verification"),key()).path("code").asInt()).isIn(400,422);
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"strict-binding-evidence.json"),"{\"newAndLegacyCommandsRejectCoercion\":true,\"legacyReplayStableAfterAnotherTransfer\":true}");
    }

    @Test void unansweredMessagesCannotBeSealedAndCrossSegmentReplyDoesNotRewriteHistory() throws Exception {
        long boss=admin("REPLY_BOSS","SUPER_ADMIN","MANAGER"),agent=admin("REPLY_G1","SUPPORT","DEDICATED");as(boss);
        long customer=register("REPLY_CUSTOMER",null);transfer(agent,List.of(customer),key());String token=token(agent);
        var old=app.startConversation(customer,key(),new AppSupportService.StartConversationRequest("support","Old pending question")).getData().conversation();
        var close=Map.of("status","CLOSED","expectedStatus",old.status(),"expectedVersion",old.version(),"reason","Try sealing unanswered messages");
        assertThat(http("PATCH","/api/admin/content/conversations/"+old.conversationNo()+"/status",token,close,key()).path("code").asInt()).isEqualTo(409);
        var convert=Map.of("category","other","priority","NORMAL","title","Pending conversion","expectedStatus",old.status(),"expectedVersion",old.version(),"reason","Try converting unanswered messages");
        assertThat(http("POST","/api/admin/content/conversations/"+old.conversationNo()+"/ticket",token,convert,key()).path("code").asInt()).isEqualTo(409);
        jdbc.update("UPDATE nx_conversation SET status='CLOSED' WHERE conversation_no=?",old.conversationNo());
        var historic=jdbc.queryForMap("SELECT status,version,last_message,updated_at FROM nx_conversation WHERE conversation_no=?",old.conversationNo());
        var fresh=app.startConversation(customer,key(),new AppSupportService.StartConversationRequest("support","New pending question")).getData().conversation();
        long oldCursor=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,old.conversationNo());
        long newCursor=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,fresh.conversationNo());
        var targets=List.of(Map.of("conversationNo",old.conversationNo(),"throughMessageId",oldCursor),Map.of("conversationNo",fresh.conversationNo(),"throughMessageId",newCursor));
        var reply=Map.of("body","This answers both questions","expectedStatus",fresh.status(),"expectedVersion",fresh.version(),"replyTargets",targets,"reason","Cross segment reply check");
        assertThat(http("POST","/api/admin/content/conversations/"+fresh.conversationNo()+"/replies",token,reply,key()).path("code").asInt()).isZero();
        assertThat(mapper.pendingReplies(old.conversationNo())).isZero();assertThat(mapper.pendingReplies(fresh.conversationNo())).isZero();
        assertThat(jdbc.queryForMap("SELECT status,version,last_message,updated_at FROM nx_conversation WHERE conversation_no=?",old.conversationNo())).isEqualTo(historic);
        var now=app.conversation(customer,fresh.conversationNo()).getData().conversation();
        assertThat(http("PATCH","/api/admin/content/conversations/"+fresh.conversationNo()+"/status",token,
            Map.of("status","RESOLVED","expectedStatus",now.status(),"expectedVersion",now.version(),"reason","Handled conversations can resolve"),key()).path("code").asInt()).isZero();
        var resolved=app.conversation(customer,fresh.conversationNo()).getData().conversation();
        assertThat(http("PATCH","/api/admin/content/conversations/"+fresh.conversationNo()+"/status",token,
            Map.of("status","CLOSED","expectedStatus",resolved.status(),"expectedVersion",resolved.version(),"reason","Resolved conversations can close"),key()).path("code").asInt()).isZero();
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"unanswered-evidence.json"),"{\"closeAndConvertBlocked\":true,\"explicitCrossSegmentReply\":true,\"historyUnchanged\":true}");
    }

    @Test void concurrentRegistrationTransferAndSynchronousListenerRollback() throws Exception {
        long boss=admin("LOCK_BOSS","SUPER_ADMIN","MANAGER"),first=admin("LOCK_G1","SUPPORT","DEDICATED"),second=admin("LOCK_G2","SUPPORT","DEDICATED");
        as(boss);rules("UNLIMITED",null);long parent=register("LOCK_PARENT",null);transfer(first,List.of(parent),key());
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var birth=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
                mapper.lockCustomer(parent);held.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("lock timeout");}catch(InterruptedException e){throw new RuntimeException(e);}
                return register("LOCK_CHILD",parent);
            }));
            assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var r=request(second,List.of(parent));
            var move=executor.submit(()->{as(boss);return bindings.transfer(key(),r);});
            Thread.sleep(100);assertThat(move.isDone()).isFalse();release.countDown();
            long child=birth.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(move.get(10,java.util.concurrent.TimeUnit.SECONDS).getCode()).isZero();
            assertThat(mapper.current(child).agentAdminId()).isEqualTo(first);assertThat(mapper.current(parent).agentAdminId()).isEqualTo(second);
            assertThat(mapper.current(register("AFTER_MOVE",parent)).agentAdminId()).isEqualTo(second);

            var fail=new java.util.concurrent.atomic.AtomicBoolean(true);
            org.springframework.context.ApplicationListener<org.springframework.context.PayloadApplicationEvent<?>> listener=event->{
                if(event.getPayload() instanceof SupportBindingService.SupportAssignmentChanged change && change.customerId().equals(parent) && fail.get())throw new IllegalStateException("S4_ATOMIC_FAILURE_INJECTION");
            };
            context.addApplicationListener(listener);
            var before=mapper.current(parent);String retry=key();var command=request(first,List.of(parent));
            assertThatThrownBy(()->bindings.transfer(retry,command)).hasMessageContaining("S4_ATOMIC_FAILURE_INJECTION");
            assertThat(mapper.current(parent)).isEqualTo(before);fail.set(false);
            assertThat(bindings.transfer(retry,command).getCode()).isZero();
            var race=request(second,List.of(parent));var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> attempt=()->{as(boss);start.await();try{return bindings.transfer(key(),race).getCode();}catch(ffdd.opsconsole.shared.exception.BizException ex){return ex.getCode();}};
            var one=executor.submit(attempt);var two=executor.submit(attempt);start.countDown();
            assertThat(List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(0,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=? AND status='ACTIVE' AND is_deleted=0",Long.class,parent)).isEqualTo(1L);
        } finally {release.countDown();executor.shutdownNow();}
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"concurrency-evidence.json"),json.writeValueAsString(Map.of("checkedAt",java.time.Instant.now().toString(),"registrationTransferLock",true,"listenerRollbackRetry",true,"competingTransferCas",true)));
    }

    @Test void realRegistrationRollsBackBindingFailureAndRetriesSameOtp() throws Exception {
        String phone="188"+String.format("%08d",Math.abs((long)run.hashCode())%100000000);
        String clientIp="198.18."+((run.hashCode()>>>8)&255)+"."+(run.hashCode()&255);
        // OTP delivery/CAPTCHA are outside S3. Seed an expiring challenge; run the real registration transaction.
        String challenge="REG-"+UUID.randomUUID().toString().replace("-", "");
        registrationMapper.insertChallengeInEnvironment(challenge,"+86",phone,clientIp,"PRODUCTION","123456",10);
        var request=new ffdd.opsconsole.auth.dto.UserRegistrationRequest("+86",phone,challenge,"123456",
            System.getenv("S3_FIXTURE_PASSWORD"),null,"zh");
        String trigger="s3_fail_"+run;
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON nx_support_binding_pool FOR EACH ROW BEGIN IF EXISTS(SELECT 1 FROM nx_user WHERE id=NEW.customer_id AND phone='"+phone+"') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S3_BINDING_FAILURE_INJECTION'; END IF; END");
        try {
            assertThatThrownBy(()->{
                var result=registration.register(request,clientIp);
                fail("Failure injection was not reached: "+result.getCode()+" "+result.getMessage());
            }).hasStackTraceContaining("S3_BINDING_FAILURE_INJECTION");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE phone=?",Long.class,phone)).isZero();
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        var created=registration.register(request,clientIp);
        assertThat(created.getCode()).as("registration retry: %s",created.getMessage()).isZero();
        long id=created.getData().user().userId();
        assertThat(mapper.poolVersion(id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user WHERE phone=?",Long.class,phone)).isEqualTo(1L);
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"registration-evidence.json"),json.writeValueAsString(
            Map.of("checkedAt",java.time.Instant.now().toString(),"customerId",id,"atomicRollback",true,"sameOtpRetry",true)));
    }

    @Test void rulesRejectFractionStringAndOverflowWithoutChangingVersion() throws Exception {
        long actor=admin("STRICT_RULES","SUPER_ADMIN","MANAGER");String token=token(actor);
        for(String field:List.of("maxInheritanceDepth","dormantDays","maintenanceDays","activityWindowDays")) for(Object bad:List.of(1.5,"2",2147483648L)) {
            long version=mapper.rules().version();
            Map<String,Object> payload=new LinkedHashMap<>(Map.of("inheritanceMode","LIMITED","maxInheritanceDepth",2,"expectedVersion",version,"reason","Strict integer runtime check"));payload.put(field,bad);
            var response=http("PUT","/api/admin/content/support-agents/rules",token,
                payload,key());
            assertThat(response.path("code").asInt()).as("reject numeric coercion for %s",bad).isIn(400,422);
            assertThat(mapper.rules().version()).isEqualTo(version);
        }
        Files.writeString(Path.of(System.getenv("S3_EVIDENCE_DIR"),"strict-rules-evidence.json"),"{\"fractionStringOverflowRejected\":true}");
    }

    @Test void isolatedApplicationStartsWithBindingSchema() throws Exception {
        assertThat(System.getenv("NEXION_DB_URL")).startsWith("jdbc:mysql://127.0.0.1:33329/cs_redesign?");
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("cs_redesign");
        assertThat(mapper.rules()).isNotNull();
        superId=admin("SUPER","SUPER_ADMIN","MANAGER");manager=admin("MANAGER","SUPPORT","MANAGER");
        g1=admin("G1","SUPPORT","DEDICATED");g2=admin("G2","SUPPORT","DEDICATED");
        as(superId); rules("UNCONFIGURED",null);
        a=register("A",null); assertPool(a,"RULE_UNCONFIGURED");
        as(manager); transfer(g1,List.of(a),key());
        assertThat(mapper.current(a).depth()).isZero();
        as(superId);rules("LIMITED",2);
        b=register("B",a);c=register("C",b);d=register("D",c);
        assertThat(mapper.current(b).depth()).isEqualTo(1);assertThat(mapper.current(c).depth()).isEqualTo(2);
        assertThat(mapper.current(c).segmentRootId()).isEqualTo(a);assertPool(d,"DEPTH_LIMIT");
        long waitingChild=register("WAITING_CHILD",d);assertPool(waitingChild,"INVITER_UNBOUND");
        as(manager);transfer(g2,List.of(d),key());
        long e=register("E",d),f=register("F",e);
        assertThat(mapper.current(f).depth()).isEqualTo(2);assertThat(mapper.current(f).agentAdminId()).isEqualTo(g2);
        assertPool(waitingChild,"INVITER_UNBOUND");
        as(superId);rules("LIMITED",0);assertPool(register("ZERO",a),"DEPTH_LIMIT");
        rules("UNLIMITED",null);long deep=register("UNLIMITED",f);assertThat(mapper.current(deep).depth()).isEqualTo(3);
        assertPool(register("NO_INVITER",null),"NO_INVITER");
        jdbc.update("UPDATE nx_support_agent_profile SET busy=1,max_concurrent=0,transferable=0 WHERE admin_id=?",g1);
        long busyChild=register("BUSY_CHILD",a);assertThat(mapper.current(busyChild).agentAdminId()).isEqualTo(g1);
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",g1);
        assertPool(register("DISABLED_CHILD",a),"AGENT_UNAVAILABLE");
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=1 WHERE admin_id=?",g1);
        proofs.put("s3-ac02","MySQL A0/B1/C2/D-pool; new D0/E1/F2; modes unconfigured/0/2/unlimited; direct inviter only; busy/capacity ignored; disabled profile rejected");

        as(manager);String transferKey=key();var before=mapper.current(a);
        var request=request(g2,List.of(a));bindings.transfer(transferKey,request);var after=mapper.current(a);
        assertThat(after.id()).isNotEqualTo(before.id());assertThat(after.depth()).isZero();
        assertThat(mapper.current(b).agentAdminId()).isEqualTo(g1);
        bindings.transfer(transferKey,request);assertThat(mapper.current(a).id()).isEqualTo(after.id());
        transfer(g2,List.of(a),key());assertThat(mapper.current(a).id()).isEqualTo(after.id());
        assertThatThrownBy(()->bindings.transfer(transferKey,request(g1,List.of(a)))).isInstanceOf(RuntimeException.class);
        var bad=new SupportBindingRequest(g2,List.of(new SupportBindingRequest.Customer(b,mapper.current(b).id(),999L),
                new SupportBindingRequest.Customer(c,mapper.current(c).id(),mapper.current(c).version())),"Atomic conflict test");
        assertThatThrownBy(()->bindings.transfer(key(),bad)).isInstanceOf(RuntimeException.class);
        assertThat(mapper.current(c).agentAdminId()).isEqualTo(g1);
        transfer(g2,List.of(b,c),key());assertThat(mapper.current(b).depth()).isZero();assertThat(mapper.current(c).depth()).isZero();
        proofs.put("s3-ac04","Real retained-command replay/no-op; single selection leaves descendants unchanged; stale batch rolls back all; explicit batch opens independent roots");

        long unbound=register("UNBOUND",null);assertThat(mapper.current(unbound)).isNull();
        var incoming=app.startConversation(unbound,key(),new AppSupportService.StartConversationRequest("support","Unbound customer message"));
        assertThat(incoming.getCode()).as("unbound message").isZero();String pendingNo=incoming.getData().conversation().conversationNo();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,pendingNo)).isEqualTo(1L);
        as(manager);transfer(g1,List.of(unbound),key());as(g1);ownership.readConversation(pendingNo);
        assertThat(app.conversation(unbound,pendingNo).getData().conversation().ownerAgentId()).isEqualTo(String.valueOf(g1));
        var advisor=app.startConversation(unbound,key(),new AppSupportService.StartConversationRequest("advisor","Advisor entry preserves assignment"));
        assertThat(advisor.getCode()).isZero();assertThat(advisor.getData().conversation().ownerAgentId()).isEqualTo(String.valueOf(g1));
        proofs.put("s3-ac03","Both support/advisor use the current binding with busy/max0/transferable0; unbound message persisted before allocation and is readable by new advisor");

        String g1Token=token(g1),g2Token=token(g2),managerToken=token(manager),superToken=token(superId);
        var detail=http("GET","/api/admin/content/conversations/"+pendingNo,g1Token,null,null);
        assertThat(detail.path("code").asInt()).as("owner HTTP detail: %s",detail).isZero();
        assertThat(http("GET","/api/admin/content/conversations/"+pendingNo,g2Token,null,null).path("code").asInt()).isEqualTo(404);
        var header=detail.path("data").path("conversation");
        Map<String,Object> reply=Map.of("body","Current advisor reply","expectedStatus",header.path("status").asText(),"expectedVersion",header.path("version").asLong(),"reason","Runtime reply check","operator","spoofed-other-advisor");
        String replyKey=key();
        assertThat(http("POST","/api/admin/content/conversations/"+pendingNo+"/replies",managerToken,reply,key()).path("code").asInt()).isEqualTo(404);
        var sent=http("POST","/api/admin/content/conversations/"+pendingNo+"/replies",g1Token,reply,replyKey);
        assertThat(sent.path("code").asInt()).as("owner send: %s",sent).isZero();
        assertThat(jdbc.queryForObject("SELECT sender_id FROM nx_conversation_message WHERE conversation_no=? AND sender_type='agent' ORDER BY id DESC LIMIT 1",Long.class,pendingNo)).isEqualTo(g1);
        assertThat(http("GET","/api/admin/content/support-workbench/commands/"+replyKey,g1Token,null,null).path("code").asInt()).isZero();
        assertModuleRecoveryRevoked(g1,"SUPPORT","service_m3_read",g1Token,replyKey);
        var wsHeader=app.conversation(unbound,pendingNo).getData().conversation();String wsKey=key();
        var wsReply=Map.of("body","Socket once and HTTP recovery","expectedStatus",wsHeader.status(),"expectedVersion",wsHeader.version(),"reason","Cross transport retained recovery");
        long beforeWs=jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=?",Long.class,pendingNo);
        com.fasterxml.jackson.databind.JsonNode wsResult;
        try(var writer=socket(g1Token)) {
            writer.send(Map.of("type","command","requestId","s3-once","operation","reply","conversationNo",pendingNo,"idempotencyKey",wsKey,"body",wsReply));
            wsResult=writer.await("ack").path("result");assertThat(wsResult.path("code").asInt()).isZero();
        }
        assertThat(http("POST","/api/admin/content/conversations/"+pendingNo+"/replies",g1Token,wsReply,wsKey)).isEqualTo(wsResult);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=?",Long.class,pendingNo)).isEqualTo(beforeWs+1);
        String customerToken=userToken(unbound),otherCustomerToken=userToken(b);
        try(var oldSocket=socket(g1Token);var newSocket=socket(g2Token);var customerSocket=socket(customerToken,true);var unrelatedSocket=socket(otherCustomerToken,true);var oldStream=stream(g1Token);var newStream=stream(g2Token)) {
        oldSocket.send(Map.of("type","watch","conversationNo",pendingNo));
        as(manager);transfer(g2,List.of(unbound),key());
        oldSocket.await("scope-invalidated");newSocket.await("scope-invalidated");
        assertThat(customerSocket.await("scope-invalidated").path("customerId").asLong()).isEqualTo(unbound);
        assertThat(unrelatedSocket.frames).noneMatch(frame->frame.contains("scope-invalidated"));
        oldStream.await("event:scope-invalidated");newStream.await("event:scope-invalidated");
        oldSocket.send(Map.of("type","watch","conversationNo",pendingNo));
        assertThat(oldSocket.await("error").path("code").asInt()).isEqualTo(404);
        oldSocket.send(Map.of("type","typing","conversationNo",pendingNo,"typing",true));
        assertThat(oldSocket.await("error").path("code").asInt()).isEqualTo(404);
        for(String op:List.of("read","reply","create")) {
            oldSocket.send(Map.of("type","command","requestId","revoked-"+op,"operation",op,"conversationNo",pendingNo,"idempotencyKey",key(),"body",
                op.equals("create")?Map.of("conversationType","support","userId",unbound,"ownerAgentId",String.valueOf(g1),"openingText","Old advisor denied","reason","Revocation create verification"):wsReply));
            assertThat(oldSocket.await("ack").path("result").path("code").asInt()).as("revoked socket %s",op).isIn(403,404);
        }
        newSocket.send(Map.of("type","watch","conversationNo",pendingNo));
        newSocket.await("presence");
        }
        assertThat(http("GET","/api/admin/content/support-workbench/commands/"+replyKey,g1Token,null,null).path("code").asInt()).isEqualTo(404);
        assertThat(app.conversation(unbound,pendingNo).getData().conversation().ownerAgentId()).isEqualTo(String.valueOf(g2));
        assertThat(http("GET","/api/admin/content/conversations/"+pendingNo,g1Token,null,null).path("code").asInt()).isEqualTo(404);
        assertThat(http("POST","/api/admin/content/conversations/"+pendingNo+"/replies",g1Token,reply,replyKey).path("code").asInt()).isEqualTo(404);
        assertThat(http("GET","/api/admin/content/conversations/"+pendingNo,g2Token,null,null).path("code").asInt()).isZero();
        var listing=http("GET","/api/admin/content/conversations?ownerAgentId="+g2+"&userId="+unbound,g1Token,null,null);
        assertThat(listing.path("data").path("total").asLong()).isZero();
        jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",g2);
        assertThat(app.conversation(unbound,pendingNo).getData().conversation().ownerAgentId()).isEqualTo(String.valueOf(g2));
        assertThat(http("GET","/api/admin/content/conversations/"+pendingNo,g2Token,null,null).path("code").asInt()).isIn(401,403,404);
        as(manager);assertThat(bindings.handover(g2,true,1,20).getTotal()).isGreaterThan(0);
        jdbc.update("UPDATE nx_admin SET status=1 WHERE id=?",g2);
        jdbc.update("UPDATE nx_support_agent_profile SET seat_type='GENERAL' WHERE admin_id=?",g2);
        assertThat(http("GET","/api/admin/content/conversations?userId="+unbound,g2Token,null,null).path("code").asInt()).isEqualTo(403);
        jdbc.update("UPDATE nx_support_agent_profile SET seat_type='DEDICATED' WHERE admin_id=?",g2);
        try(var revoked=stream(g2Token)) {
            sessions.revokeSessions(g2);
            var view=app.conversation(unbound,pendingNo).getData().conversation();
            assertThat(app.replyConversation(unbound,pendingNo,key(),new AppSupportService.ReplyRequest("After session revocation",view.status(),view.version())).getCode()).isZero();
            revoked.await("CLOSED");
            assertThat(revoked.lines).noneMatch(line->line.contains("After session revocation"));
        }
        g2Token=token(g2);
        proofs.put("s3-ac05","Real HTTP+WS+SSE: both advisors receive assignment invalidation without chat; old watch/replay/recovery denied; new watch works; expired SSE closes without payload; disabled account/profile denied; authoritative owner projection after allocation/transfer/disable");
        proofs.put("s3-ac06","Authenticated sender_id persisted; supervisor cannot send as an advisor; old message unchanged after transfer");

        as(manager);assertThatThrownBy(()->rules("LIMITED",2)).isInstanceOf(RuntimeException.class);
        as(g1);assertThatThrownBy(()->bindings.pool(null,null,1,20)).isInstanceOf(RuntimeException.class);
        as(superId);var version=mapper.rules().version();rules("LIMITED",2);
        assertThatThrownBy(()->bindings.updateRules(key(),new SupportRulesRequest(null,null,null,"LIMITED",2,version,"Stale rules check"))).isInstanceOf(RuntimeException.class);
        assertThat(mapper.rules().dormantDays()).isNull();assertThat(mapper.rules().maintenanceDays()).isNull();
        as(manager);assertThat(bindings.pool(null,null,1,1).getTotal()).isGreaterThan(bindings.pool(null,null,1,1).getRecords().size());
        assertThatThrownBy(()->bindings.pool(null,null,Long.MAX_VALUE,100)).isInstanceOf(RuntimeException.class);
        proofs.put("s3-ac13","Actual roles and rule CAS; D/M/W remain independently null; limited0 accepted; paginated pool total exceeds current page; overflow rejected");
        fixture.put("customers",Map.of("A",a,"B",b,"C",c,"D",d,"UNBOUND_MESSAGE_CUSTOMER",unbound));
        fixture.put("conversationNo",pendingNo);
        fixture.put("password",System.getenv("S3_FIXTURE_PASSWORD"));
        fixture.put("tokens",Map.of("G1",g1Token,"G2",g2Token,"MANAGER",managerToken,"SUPER",superToken));
        fixture.put("customerTokens",Map.of("UNBOUND_MESSAGE_CUSTOMER",customerToken,"B",otherCustomerToken));
        Path dir=Path.of(System.getenv("S3_EVIDENCE_DIR"));
        Files.writeString(dir.resolve("runtime-identities.json"),json.writeValueAsString(fixture));
        Files.writeString(dir.resolve("scenario-evidence.json"),json.writeValueAsString(Map.of("checkedAt",java.time.Instant.now().toString(),"checks",proofs)));
        SecurityContextHolder.clearContext();
    }

    private long admin(String label,String role,String seat) {
        String username=run+"_"+label;
        String hash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(System.getenv("S3_FIXTURE_PASSWORD"));
        jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname,super_admin,status) VALUES(?,?,?,?,1)",username,hash,label,"SUPER_ADMIN".equals(role)?1:0);
        Long id=jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,username);
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code=? AND is_deleted=0",id,role);
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,?,?,'support,advisor','',0,1,1,0)",id,seat,seat);
        fixture.put(label,Map.of("id",id,"username",username));return id;
    }
    private long register(String label,Long inviter) {
        return new TransactionTemplate(transactions).execute(status->{
            if(inviter!=null)mapper.lockCustomer(inviter);
            String referral=UUID.randomUUID().toString().replace("-", "").substring(0,20).toUpperCase();
            String phone="199"+String.format("%08d",Math.abs((long)referral.hashCode())%100000000);
            String password=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(System.getenv("S3_FIXTURE_PASSWORD"));
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,sponsor_user_id,status,sandbox) VALUES('+86',?,'127.0.0.1',?,?,?,?,'ACTIVE',0)",phone,password,run+"_"+label,referral,inviter);
            Long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);
            fixture.put("CUSTOMER_"+label,Map.of("id",id,"phone",phone,"countryCode","+86","referralCode",referral));
            bindings.register(id,inviter);return id;
        });
    }
    private void as(long id) {
        var auth=new UsernamePasswordAuthenticationToken(String.valueOf(id),null,List.of(new SimpleGrantedAuthority("service_m3_write")));
        auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private String token(long id) {
        String username=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);
        return tokens.createToken(id,"ADMIN",username,List.of(),sessions.createSession(id,username));
    }
    private void assertModuleRecoveryRevoked(long actor,String role,String permission,String token,String commandKey) throws Exception {
        var ids=jdbc.queryForList("SELECT rp.id FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code=? AND p.permission_code=? AND rp.is_deleted=0",Long.class,role,permission);
        assertThat(ids).isNotEmpty();
        try {
            ids.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=1 WHERE id=?",id));permissions.evict(actor);
            assertThat(http("GET","/api/admin/content/support-workbench/commands/"+commandKey,token,null,null).path("code").asInt()).as("module grant revoked: %s",permission).isEqualTo(403);
        } finally {ids.forEach(id->jdbc.update("UPDATE nx_admin_role_permission SET is_deleted=0 WHERE id=?",id));permissions.evict(actor);}
        assertThat(http("GET","/api/admin/content/support-workbench/commands/"+commandKey,token,null,null).path("code").asInt()).isZero();
    }
    private String key(){return "s3-"+UUID.randomUUID();}
    private void rules(String mode,Integer depth){var r=mapper.rules();bindings.updateRules(key(),new SupportRulesRequest(null,null,null,mode,depth,r.version(),"Isolated runtime configuration"));}
    private SupportBindingRequest request(long target,List<Long> customers){return new SupportBindingRequest(target,customers.stream().map(id->{var r=mapper.current(id);return new SupportBindingRequest.Customer(id,r==null?null:r.id(),r==null?mapper.poolVersion(id):r.version());}).toList(),"Isolated runtime transfer");}
    private void transfer(long target,List<Long> customers,String key){assertThat(bindings.transfer(key,request(target,customers)).getCode()).isZero();}
    private void assertPool(long id,String reason){assertThat(mapper.current(id)).isNull();assertThat(jdbc.queryForObject("SELECT reason FROM nx_support_binding_pool WHERE customer_id=?",String.class,id)).isEqualTo(reason);}
    private com.fasterxml.jackson.databind.JsonNode http(String method,String path,String token,Object body,String key) throws Exception {
        var b=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:18129"+path)).timeout(java.time.Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);
        b.header("Content-Type","application/json");b.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=java.net.http.HttpClient.newHttpClient().send(b.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }

    private SocketProbe socket(String token) throws Exception {
        return socket(token,false);
    }
    private String userToken(long id) throws Exception {
        assertThat(onboarding.defer(id,new ffdd.opsconsole.onboarding.application.OnboardingCalibrationService.ActionRequest("s3-device-"+id,0,key())).getCode()).isZero();
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",id,session,session);
        String token=tokens.createUserToken(id,"s3-customer",List.of(),session,java.time.Duration.ofHours(8),UserAuthEnvironment.PRODUCTION);
        var terms=http("GET","/api/legal/terms/current?locale=en&jurisdiction=GLOBAL",token,null,null);
        assertThat(terms.path("code").asInt()).as("existing published terms").isZero();
        assertThat(http("POST","/api/legal/terms/acknowledgment",token,Map.of("locale","en","jurisdiction","GLOBAL","version",terms.path("data").path("version").asText(),"confirmed",true,"idempotencyKey",key(),"runId",""),null).path("code").asInt()).isZero();
        return token;
    }
    private SocketProbe socket(String token,boolean app) throws Exception {
        var ticket=http("POST",app?"/api/app/support/realtime-ticket":"/api/admin/content/conversations/realtime-ticket",token,null,null);
        assertThat(ticket.path("code").asInt()).as("socket ticket: %s",ticket).isZero();
        var probe=new SocketProbe();
        probe.socket=java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(java.net.URI.create("ws://127.0.0.1:18129/ws/conversations"),probe).get(10,java.util.concurrent.TimeUnit.SECONDS);
        probe.send(Map.of("type","auth","ticket",ticket.path("data").path("ticket").asText()));probe.await("ready");return probe;
    }
    private final class SocketProbe implements java.net.http.WebSocket.Listener,AutoCloseable {
        java.net.http.WebSocket socket;
        final java.util.concurrent.BlockingQueue<String> frames=new java.util.concurrent.LinkedBlockingQueue<>();
        final StringBuilder buffer=new StringBuilder();
        public void onOpen(java.net.http.WebSocket ws){ws.request(1);}
        public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket ws,CharSequence data,boolean last){
            buffer.append(data);if(last){frames.add(buffer.toString());buffer.setLength(0);}ws.request(1);return null;
        }
        void send(Object frame) throws Exception {socket.sendText(json.writeValueAsString(frame),true).get(5,java.util.concurrent.TimeUnit.SECONDS);}
        com.fasterxml.jackson.databind.JsonNode await(String type) throws Exception {
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<end){String frame=frames.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS);if(frame!=null){var n=json.readTree(frame);if(type.equals(n.path("type").asText()))return n;}}
            throw new AssertionError("Missing socket event "+type);
        }
        public void close(){socket.abort();}
    }
    private StreamProbe stream(String token) throws Exception {
        var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
            java.net.URI.create("http://127.0.0.1:18129/api/admin/content/conversations/stream"))
            .header("Authorization","Bearer "+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);return new StreamProbe(response.body());
    }
    private static final class StreamProbe implements AutoCloseable {
        final java.io.InputStream input;
        final List<String> lines=new java.util.concurrent.CopyOnWriteArrayList<>();
        StreamProbe(java.io.InputStream input){this.input=input;Thread reader=new Thread(()->{
            try(var in=new java.io.BufferedReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null)lines.add(line);}
            catch(java.io.IOException ignored){}finally{lines.add("CLOSED");}
        });reader.setDaemon(true);reader.start();}
        void await(String value) throws Exception {long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<end){if(lines.stream().anyMatch(line->line.replace(" ","").equals(value)))return;Thread.sleep(25);}throw new AssertionError("Missing SSE "+value);}
        public void close() throws Exception {input.close();}
    }
}
