package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService;
import java.util.*;
import java.nio.file.*;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual isolated HTTP, SQL, login, multipart and private object storage; never fixture HTTP stubs. */
@org.springframework.context.annotation.Import(SupportIsolatedRuntime.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=${S4_HTTP_PORT:18129}",
    "nexion.support.attachments.allowed-mime-types=image/png,image/jpeg","nexion.support.attachments.max-bytes=1048576",
    "nexion.support.attachments.max-pixels=1000000","nexion.support.attachments.ttl-seconds=300"})
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="S4_EVIDENCE_DIR",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SupportS4RuntimeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired OnboardingCalibrationService onboarding;
    @Autowired SupportAttachmentService attachments;
    @Autowired ffdd.opsconsole.shared.storage.ObjectStorageService storage;
    @Autowired ffdd.opsconsole.shared.storage.StorageProperties storageProperties;
    private final String run="s4_"+UUID.randomUUID().toString().substring(0,8);
    private final Map<String,Boolean> checks=new LinkedHashMap<>();
    private final Map<String,Object> samples=new LinkedHashMap<>();
    private final HttpClient client=HttpClient.newHttpClient();
    private long boss,g1,g2,customer;
    private String adminToken,otherToken,bossToken,customerToken;

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(SupportIsolatedRuntime.database());
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(33329);
        boss=admin("SUPER_ADMIN","MANAGER");g1=admin("SUPPORT","DEDICATED");g2=admin("SUPPORT","DEDICATED");
        as(boss);customer=customer();transfer(customer,g1);
        adminToken=token(g1);otherToken=token(g2);bossToken=token(boss);customerToken=userToken(customer);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @AfterAll void evidence() throws Exception {
        Set<String> required=Set.of("s4-ac01","s4-ac07","s4-ac08","s4-ac09","s4-ac10","s4-ac13","s4-supplement");
        assertThat(checks.keySet()).containsAll(required);
        Path dir=Path.of(System.getenv("S4_EVIDENCE_DIR"));Files.createDirectories(dir);
        Files.writeString(dir.resolve("scenario-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("at",Instant.now().toString(),"checks",checks,"samples",samples)));
        // Handoff credentials stay outside Git, on the same restricted local evidence volume as S3.
        as(boss);long manager=admin("SUPPORT","MANAGER");
        long bound=customer();transfer(bound,g1);long unbound=customer();
        assertThat(mapper.current(unbound)).isNull();
        Map<String,Object> identities=new LinkedHashMap<>();
        for(var entry:Map.of("SUPER",boss,"MANAGER",manager,"G1",g1,"G2",g2).entrySet()) {
            long id=entry.getValue();
            identities.put(entry.getKey(),Map.of("id",id,"username",jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id),"token",token(id)));
        }
        for(var entry:Map.of("CUSTOMER",bound,"CUSTOMER_UNBOUND",unbound).entrySet()) {
            long id=entry.getValue();
            identities.put(entry.getKey(),Map.of("id",id,"countryCode","+86","phone",jdbc.queryForObject("SELECT phone FROM nx_user WHERE id=?",String.class,id),"token",userToken(id)));
        }
        identities.put("password",System.getenv("S3_FIXTURE_PASSWORD"));identities.put("at",Instant.now().toString());
        Files.writeString(dir.resolve("runtime-identities.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(identities));
    }

    @Test void maintenanceHttpNeedsNewInteractiveLoginAndKeepsExecutionSeparate() throws Exception {
        rules(null,1,null);
        var created=ok(http("POST","/api/app/support/conversations",customerToken,Map.of("conversationType","support","openingText","Customer asks for help"),key()));
        String no=created.path("conversation").path("conversationNo").asText();
        var request=reply(no,"MAINTENANCE","Maintenance contact",key());
        String command=key();
        ok(http("POST",replyPath(no),adminToken,request,command));
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(1);
        assertThat(cycleStatus()).isEqualTo("OPEN");
        ok(http("GET",maintenancePath()+"/history",adminToken,null,null));
        ok(http("POST",replyPath(no),adminToken,request,command));
        // Persistent client-message id survives a different command-cache key.
        ok(http("POST",replyPath(no),adminToken,request,key()));
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(1);
        var changed=new LinkedHashMap<>(request);changed.put("body","Changed retry");
        assertCode(http("POST",replyPath(no),adminToken,changed,key()),409);
        long baseline=scalar("SELECT baseline_activity_seq FROM nx_support_maintenance_cycle WHERE customer_id=? ORDER BY id DESC LIMIT 1");
        ok(http("POST",replyPath(no),adminToken,reply(no,"MAINTENANCE","Second maintenance contact",key()),key()));
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(2);
        assertThat(scalar("SELECT baseline_activity_seq FROM nx_support_maintenance_cycle WHERE customer_id=? ORDER BY id DESC LIMIT 1")).isEqualTo(baseline);
        var fresh=ok(http("GET","/api/app/support/conversations/"+no,customerToken,null,null)).path("conversation");
        ok(http("POST","/api/app/support/conversations/"+no+"/replies",customerToken,Map.of("body","Chat is not account activity","expectedStatus",fresh.path("status").asText(),"expectedVersion",fresh.path("version").asLong()),key()));
        assertThat(cycleStatus()).isEqualTo("OPEN");
        assertThat(count("nx_support_activity_event")).isZero();
        var login=login(customer);
        assertThat(cycleStatus()).isEqualTo("SUCCEEDED");
        assertThat(count("nx_support_activity_event")).isEqualTo(1);
        String refresh=login.path("refreshToken").asText();
        ok(http("POST","/auth/users/refresh",null,Map.of("refreshToken",refresh),null));
        assertThat(count("nx_support_activity_event")).isEqualTo(1);
        ok(http("POST",replyPath(no),adminToken,reply(no,"MAINTENANCE","New cycle before next reminder",key()),key()));
        assertThat(cycleStatus()).isEqualTo("OPEN");
        long assignment=mapper.current(customer).id();
        var stop=Map.of("enabled",false,"reason","Customer requested maintenance pause","expectedVersion",1,"expectedAssignmentId",assignment);
        String stopKey=key();
        ok(http("PATCH",maintenancePath(),adminToken,stop,stopKey));
        assertThat(cycleStatus()).isEqualTo("STOPPED");
        ok(http("GET","/api/admin/content/support-workbench/commands/"+stopKey,adminToken,null,null));
        login(customer);
        assertThat(cycleStatus()).isEqualTo("STOPPED");
        ok(http("POST",replyPath(no),adminToken,reply(no,"SERVICE","Service remains possible while stopped",key()),key()));
        assertCode(http("POST",replyPath(no),adminToken,reply(no,"MAINTENANCE","Must not maintain while stopped",key()),key()),409);
        ok(http("PATCH",maintenancePath(),adminToken,Map.of("enabled",true,"reason","Customer requested maintenance resume","expectedVersion",2,"expectedAssignmentId",assignment),key()));
        assertThat(cycleStatus()).isEqualTo("STOPPED");
        ok(http("POST",replyPath(no),adminToken,reply(no,"MAINTENANCE","New cycle after explicit resume",key()),key()));
        as(boss);transfer(customer,g2);
        assertThat(cycleStatus()).isEqualTo("TRANSFERRED");
        assertCode(http("GET","/api/admin/content/support-workbench/commands/"+stopKey,adminToken,null,null),404);
        assertCode(http("POST",replyPath(no),adminToken,request,key()),404);
        var stats=ok(http("GET","/api/admin/content/support-workbench/overview",adminToken,null,null));
        assertThat(stats.path("performance").path("successfulCycleCount").asLong()).isEqualTo(1);
        assertThat(stats.path("performance").path("successfulCustomerCount").asLong()).isEqualTo(1);
        samples.put("maintenanceAfterTransfer",stats);
        checks.put("s4-ac07",true);checks.put("s4-ac08",true);checks.put("s4-ac10",true);
    }

    @Test void snapshotPaginationUnknownAndTodoUnionAgree() throws Exception {
        rules(10,2,3);
        as(boss);long second=customer();transfer(second,g1);
        long third=customer();transfer(third,g1);
        ok(http("POST","/api/app/support/conversations",customerToken,Map.of("conversationType","support","openingText","One customer multiple reasons"),key()));
        // Explicit historical fixture is isolated to this customer; never invent production history.
        jdbc.update("INSERT INTO nx_support_activity_event(customer_id,seq,source_ref,occurred_at) VALUES(?,1,?,UTC_TIMESTAMP(6)-INTERVAL 5 DAY)",customer,"fixture:"+key());
        var all=ok(http("GET","/api/admin/content/support-workbench/customers?filter=TODO&pageSize=1",adminToken,null,null));
        assertThat(all.path("overview").path("boundTotal").asInt()).isEqualTo(3);
        assertThat(all.path("overview").path("todoTotal").asInt()).isEqualTo(3);
        assertThat(all.path("overview").path("waitingReplyTotal").asInt()).isEqualTo(1);
        assertThat(all.path("customers").path("total").asInt()).isEqualTo(3);
        assertThat(all.path("customers").path("records").size()).isEqualTo(1);
        assertThat(all.path("overview").path("activeTotal").isNull()).isTrue();
        var active=ok(http("GET","/api/admin/content/support-workbench/customers?filter=ACTIVE",adminToken,null,null));
        assertThat(active.path("customers").path("total").asInt()).isEqualTo(1);
        var window=ok(http("GET","/api/admin/content/support-workbench/customers?filter=WINDOW_ACTIVE",adminToken,null,null));
        assertThat(window.path("customers").path("total").asInt()).isZero();
        var seen=new HashSet<Long>();
        for(int page=1;page<=3;page++) {
            var response=ok(http("GET","/api/admin/content/support-workbench/customers?filter=TODO&pageSize=1&pageNum="+page,adminToken,null,null));
            assertThat(response.path("overview").path("todoTotal").asInt()).isEqualTo(response.path("customers").path("total").asInt());
            seen.add(response.path("customers").path("records").get(0).path("customerId").asLong());
        }
        assertThat(seen).hasSize(3);
        var preference=Map.of("enabled",false,"reason","Pause just proactive maintenance","expectedVersion",1,"expectedAssignmentId",mapper.current(customer).id());
        ok(http("PATCH",maintenancePath(),adminToken,preference,key()));
        var stopped=ok(http("GET","/api/admin/content/support-workbench/customers?filter=STOPPED",adminToken,null,null));
        assertThat(stopped.path("overview").path("stoppedTotal").asInt()).isEqualTo(1);
        assertThat(stopped.path("customers").path("total").asInt()).isEqualTo(1);
        assertThat(stopped.path("overview").path("waitingReplyTotal").asInt()).isEqualTo(1);
        assertCode(http("GET","/api/admin/content/support-workbench/overview?agentId="+g2,adminToken,null,null),403);
        assertCode(http("GET","/api/admin/content/support-workbench/customers/"+customer,otherToken,null,null),404);
        rules(null,2,null);
        var partial=ok(http("GET","/api/admin/content/support-workbench/overview",adminToken,null,null));
        assertThat(partial.path("overview").path("activeTotal").isNull()).isTrue();
        assertThat(partial.path("overview").path("dormantTotal").isNull()).isTrue();
        assertThat(partial.path("overview").path("dueTotal").asInt()).isEqualTo(2);
        assertThat(partial.path("performance").path("timeZone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(partial.path("evaluatedAt").asText()).endsWith("Z");
        samples.put("workbenchSnapshot",all);samples.put("stoppedSnapshot",stopped);samples.put("independentRules",partial);
        checks.put("s4-ac01",true);checks.put("s4-ac13",true);checks.put("s4-supplement",true);
    }

    @Test void realPrivateImagesHaveSeparateUploadSendAndRevocation() throws Exception {
        byte[] png=image("png"),jpg=image("jpeg");
        var policy=ok(http("GET","/api/admin/content/conversations/attachments/policy",adminToken,null,null));
        assertThat(policy.path("available").asBoolean()).isTrue();
        String uploadId=key(),uploadKey=key();
        var ready=ok(upload(adminToken,png,"image/png","probe.png",uploadId,uploadKey));
        String attachment=ready.path("id").asText(),path="/api/admin/content/conversations/attachments/"+attachment+"/content";
        assertThat(count("nx_support_maintenance_execution")).isZero();
        assertThat(ok(upload(adminToken,png,"image/png","probe.png",uploadId,uploadKey)).path("id").asText()).isEqualTo(attachment);
        assertCode(json.readTree(download(path,otherToken,null).body()),404);
        assertCode(json.readTree(download(path,bossToken,null).body()),404); // READY is uploader-only.
        var pre=download(path,adminToken,null);
        assertThat(pre.statusCode()).isEqualTo(200);
        assertThat(pre.headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(ImageIO.read(new ByteArrayInputStream(pre.body())).getWidth()).isEqualTo(4);
        String object=jdbc.queryForObject("SELECT object_key FROM nx_support_attachment WHERE id=?",String.class,attachment);
        var anonymous=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:19029/"+storageProperties.getBucket()+"/"+object)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymous.statusCode()).isEqualTo(403);
        var body=new LinkedHashMap<String,Object>();
        body.put("conversationType","support");body.put("userId",customer);body.put("openingText","");
        body.put("reason","Image maintenance runtime verification");body.put("kind","IMAGE");body.put("attachmentId",attachment);
        body.put("intent","MAINTENANCE");body.put("clientMessageId",key());body.put("expectedAssignmentId",mapper.current(customer).id());
        var sent=ok(http("POST","/api/admin/content/conversations",adminToken,body,key()));
        String no=sent.path("conversationNo").asText();
        ok(http("POST","/api/admin/content/conversations",adminToken,body,key()));
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(1);
        var detail=ok(http("GET","/api/admin/content/conversations/"+no,adminToken,null,null));
        assertThat(detail.toString()).contains("IMAGE",attachment,"VERIFIED","MAINTENANCE").doesNotContain("private/support/");
        var appImage=download("/api/app/support/attachments/"+attachment+"/content",customerToken,null);
        assertThat(appImage.statusCode()).isEqualTo(200);assertThat(appImage.body()).isEqualTo(pre.body());
        assertCode(http("DELETE","/api/admin/content/conversations/attachments/"+attachment,adminToken,null,key()),409);
        String draft=ok(upload(adminToken,jpg,"image/jpeg","probe.jpg",key(),key())).path("id").asText();
        String cancelled=ok(upload(adminToken,png,"image/png","cancel.png",key(),key())).path("id").asText();
        ok(http("DELETE","/api/admin/content/conversations/attachments/"+cancelled,adminToken,null,key()));
        assertCode(json.readTree(download("/api/admin/content/conversations/attachments/"+cancelled+"/content",adminToken,null).body()),409);
        String expired=ok(upload(adminToken,png,"image/png","expired.png",key(),key())).path("id").asText();
        jdbc.update("UPDATE nx_support_attachment SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",expired);
        assertCode(json.readTree(download("/api/admin/content/conversations/attachments/"+expired+"/content",adminToken,null).body()),409);
        attachments.cleanupExpired();
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_attachment WHERE id=?",String.class,expired)).isEqualTo("EXPIRED");
        assertCode(upload(adminToken,png,"image/jpeg","mismatch.jpg",key(),key()),415);
        assertCode(upload(adminToken,"<svg/>".getBytes(),"image/svg+xml","bad.svg",key(),key()),415);
        assertCode(upload(adminToken,new byte[1048577],"image/png","large.png",key(),key()),413);
        assertCode(upload(adminToken,"not an image".getBytes(),"image/png","broken.png",key(),key()),422);
        assertCode(upload(adminToken,png,"image/png","../path.png",key(),key()),422);
        // Remove a READY object to prove send fails without creating an empty message/execution.
        String missing=ok(upload(adminToken,png,"image/png","missing.png",key(),key())).path("id").asText();
        storage.remove(jdbc.queryForObject("SELECT object_key FROM nx_support_attachment WHERE id=?",String.class,missing));
        var missingBody=new LinkedHashMap<>(body);missingBody.put("attachmentId",missing);missingBody.put("clientMessageId",key());
        assertCode(http("POST","/api/admin/content/conversations",adminToken,missingBody,key()),503);
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(1);
        as(boss);transfer(customer,g2);
        assertCode(json.readTree(download(path,adminToken,"bytes=0-10").body()),404);
        assertThat(download(path,otherToken,"bytes=0-10").statusCode()).isEqualTo(200);
        assertThat(download(path,bossToken,null).statusCode()).isEqualTo(200);
        assertCode(json.readTree(download("/api/admin/content/conversations/attachments/"+draft+"/content",otherToken,null).body()),404);
        assertCode(json.readTree(download("/api/admin/content/conversations/attachments/"+draft+"/content",adminToken,null).body()),404);
        samples.put("attachmentPolicy",policy);samples.put("uploadReady",ready);samples.put("imageConversation",detail);
        checks.put("s4-ac09",true);
    }

    @Test void structuredPayloadRejectsRecordTextCollisionThroughBothRecoveryKeys() throws Exception {
        String clientId=key(),command=key();
        var original=new LinkedHashMap<String,Object>();
        original.put("conversationType","support");original.put("userId",customer);
        original.put("openingText","hello, reason=abcdefgh");original.put("reason","ijklmnop");
        original.put("clientMessageId",clientId);original.put("expectedAssignmentId",mapper.current(customer).id());
        ok(http("POST","/api/admin/content/conversations",adminToken,original,command));
        var collision=new LinkedHashMap<>(original);collision.put("openingText","hello");collision.put("reason","abcdefgh, reason=ijklmnop");
        assertCode(http("POST","/api/admin/content/conversations",adminToken,collision,command),409);
        assertCode(http("POST","/api/admin/content/conversations",adminToken,collision,key()),409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE actor_id=? AND client_message_id=?",Long.class,g1,clientId)).isEqualTo(1);
        samples.put("structuredPayloadCollision","Both same-key and different-key HTTP replay rejected with 409; one durable message");
    }

    @Test void newConversationOpeningReplyHandlesOnlyExplicitOldTargetsAtomically() throws Exception {
        var legacy=new ConversationInitiateRequest("support",1L,null,null,"hello","reason text",null);
        assertThat(legacy.toString()).isEqualTo("ConversationInitiateRequest[conversationType=support, userId=1, ownerAgentId=null, ownerAgentName=null, openingText=hello, reason=reason text, operator=null]");
        var modern=new ConversationInitiateRequest("support",1L,null,null,"hello","reason text",null,"TEXT",null,"SERVICE","client-compat",2L);
        assertThat(SupportMessagePayload.encode(modern)).isEqualTo("{\"conversationType\":\"support\",\"userId\":1,\"ownerAgentId\":null,\"ownerAgentName\":null,\"openingText\":\"hello\",\"reason\":\"reason text\",\"operator\":null,\"kind\":\"TEXT\",\"attachmentId\":null,\"intent\":\"SERVICE\",\"clientMessageId\":\"client-compat\",\"expectedAssignmentId\":2}");
        String adminPath="/api/admin/content/conversations";
        var old=ok(http("POST","/api/app/support/conversations",customerToken,
                Map.of("conversationType","support","openingText","Historical question to handle"),key())).path("conversation");
        String oldNo=old.path("conversationNo").asText();
        long through=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,oldNo);
        jdbc.update("UPDATE nx_conversation SET status='CLOSED' WHERE conversation_no=?",oldNo);
        String otherOldNo=ok(http("POST","/api/app/support/conversations",customerToken,
                Map.of("conversationType","support","openingText","Unselected historical question"),key())).path("conversation").path("conversationNo").asText();
        jdbc.update("UPDATE nx_conversation SET status='CLOSED' WHERE conversation_no=?",otherOldNo);
        assertThat(otherOldNo).isNotEqualTo(oldNo);
        long assignment=mapper.current(customer).id();
        var opening=new LinkedHashMap<String,Object>();
        opening.put("conversationType","support");opening.put("userId",customer);
        opening.put("openingText","One real first reply answers the selected old question");
        opening.put("reason","Explicit historical reply coverage");opening.put("clientMessageId",key());
        opening.put("expectedAssignmentId",assignment);opening.put("intent","MAINTENANCE");
        // Omitted targets never clear another conversation's pending question.
        ok(http("POST",adminPath,adminToken,opening,key()));
        assertThat(mapper.pendingReplies(oldNo)).isEqualTo(1);
        assertThat(mapper.pendingReplies(otherOldNo)).isEqualTo(1);

        // Reproduce a newer historical customer message that arrived after the selected cursor snapshot.
        jdbc.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at,updated_at) SELECT id,conversation_no,?,'user','runtime','Newer historical question remains pending',NOW(),NOW() FROM nx_conversation WHERE conversation_no=?",customer,oldNo);
        long newer=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=?",Long.class,oldNo);
        assertThat(newer).isGreaterThan(through);
        int oldCount=ok(http("GET",adminPath+"/"+oldNo,adminToken,null,null)).path("messages").size();
        opening.put("clientMessageId",key());
        opening.put("replyTargets",List.of(Map.of("conversationNo",oldNo,"throughMessageId",through)));
        String command=key();
        String newNo=ok(http("POST",adminPath,adminToken,opening,command)).path("conversationNo").asText();
        assertThat(newNo).isNotEqualTo(oldNo);
        var newDetail=ok(http("GET",adminPath+"/"+newNo,adminToken,null,null));
        assertThat(newDetail.path("messages").size()).isEqualTo(1);
        assertThat(newDetail.path("messages").get(0).path("content").asText())
                .isEqualTo(opening.get("openingText"));
        var oldDetail=ok(http("GET",adminPath+"/"+oldNo,adminToken,null,null));
        assertThat(oldDetail.path("conversation").path("status").asText()).isEqualTo("CLOSED");
        assertThat(oldDetail.path("messages").size()).isEqualTo(oldCount);
        assertThat(jdbc.queryForObject("SELECT through_message_id FROM nx_support_reply_cursor WHERE conversation_no=?",Long.class,oldNo)).isEqualTo(through);
        assertThat(mapper.pendingReplies(oldNo)).isEqualTo(1);
        assertThat(mapper.pendingReplies(otherOldNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_reply_cursor WHERE conversation_no=?",Long.class,otherOldNo)).isZero();
        var customerDetail=ok(http("GET","/api/admin/content/support-workbench/customers/"+customer,adminToken,null,null));
        assertThat(customerDetail.path("customer").path("waitingReply").asBoolean()).isTrue();
        assertThat(ok(http("POST",adminPath,adminToken,opening,command)).path("conversationNo").asText()).isEqualTo(newNo);
        long beforeExecutions=count("nx_support_maintenance_execution");
        var changed=new LinkedHashMap<>(opening);
        changed.put("replyTargets",List.of(Map.of("conversationNo",oldNo,"throughMessageId",newer)));
        assertCode(http("POST",adminPath,adminToken,changed,command),409);
        assertCode(http("POST",adminPath,adminToken,changed,key()),409);
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(beforeExecutions);

        as(boss);long otherCustomer=customer();transfer(otherCustomer,g1);
        String foreignNo=ok(http("POST","/api/app/support/conversations",userToken(otherCustomer),
                Map.of("conversationType","support","openingText","Different customer's private question"),key())).path("conversation").path("conversationNo").asText();
        long foreignMessage=jdbc.queryForObject("SELECT MAX(id) FROM nx_conversation_message WHERE conversation_no=? AND sender_type='user'",Long.class,foreignNo);
        long beforeConversations=scalar("SELECT COUNT(*) FROM nx_conversation WHERE user_id=?");
        long beforeMessages=scalar("SELECT COUNT(*) FROM nx_conversation_message m JOIN nx_conversation c ON c.conversation_no=m.conversation_no WHERE c.user_id=?");
        long beforeCycles=count("nx_support_maintenance_cycle");
        opening.put("clientMessageId",key());
        opening.put("replyTargets",List.of(Map.of("conversationNo",oldNo,"throughMessageId",newer),
                Map.of("conversationNo",foreignNo,"throughMessageId",foreignMessage)));
        assertCode(http("POST",adminPath,adminToken,opening,key()),422);
        assertThat(scalar("SELECT COUNT(*) FROM nx_conversation WHERE user_id=?")).isEqualTo(beforeConversations);
        assertThat(scalar("SELECT COUNT(*) FROM nx_conversation_message m JOIN nx_conversation c ON c.conversation_no=m.conversation_no WHERE c.user_id=?")).isEqualTo(beforeMessages);
        assertThat(count("nx_support_maintenance_execution")).isEqualTo(beforeExecutions);
        assertThat(count("nx_support_maintenance_cycle")).isEqualTo(beforeCycles);
        assertThat(jdbc.queryForObject("SELECT through_message_id FROM nx_support_reply_cursor WHERE conversation_no=?",Long.class,oldNo)).isEqualTo(through);
        assertThat(mapper.pendingReplies(foreignNo)).isEqualTo(1);
        assertThat(ok(http("GET",adminPath+"/"+oldNo,adminToken,null,null)).path("messages").size()).isEqualTo(oldCount);
        assertThat(ok(http("GET",adminPath+"/"+foreignNo,adminToken,null,null)).path("messages").size()).isEqualTo(1);
        samples.put("openingReplyTargets","One HTTP create writes one first reply; only explicit historical cursor advances; newer and unselected questions remain; cross-customer target rolls back conversation/message/maintenance.");
        checks.put("s4-opening-reply-targets",true);
    }

    @Test void unboundCustomerCanUploadAndSendPrivateImage() throws Exception {
        as(boss);long unbound=customer();assertThat(mapper.current(unbound)).isNull();
        String user=userToken(unbound),uploadId=key();byte[] png=image("png");
        JsonNode uploaded=ok(upload(user,png,"image/png","camera.png",uploadId,key(),true));
        String attachment=uploaded.path("id").asText();
        assertThat(ok(upload(user,png,"image/png","camera.png",uploadId,key(),true)).path("id").asText()).isEqualTo(attachment);
        String path="/api/app/support/attachments/"+attachment+"/content";
        assertThat(download(path,user,null).statusCode()).isEqualTo(200);
        assertCode(json.readTree(download(path,customerToken,null).body()),404);
        var message=Map.of("conversationType","support","kind","IMAGE","attachmentId",attachment,"clientMessageId",key());
        var sent=ok(http("POST","/api/app/support/conversations",user,message,key()));
        var replay=ok(http("POST","/api/app/support/conversations",user,message,key()));
        assertThat(replay.path("conversation").path("conversationNo")).isEqualTo(sent.path("conversation").path("conversationNo"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=? AND attachment_id=?",Long.class,unbound,attachment)).isEqualTo(1);
        assertThat(download(path,user,null).statusCode()).isEqualTo(200);
        samples.put("unboundImage","Real multipart upload, private bytes, captionless send and durable replay passed while unbound");
        // Resolve the pending image through the ordinary advisor reply before the customer's ticket conversion.
        as(boss);transfer(unbound,g1);
        String no=sent.path("conversation").path("conversationNo").asText();
        var source=ok(http("GET","/api/admin/content/conversations/"+no,adminToken,null,null)).path("conversation");
        long imageMessage=jdbc.queryForObject("SELECT message_id FROM nx_support_human_message WHERE customer_id=? AND attachment_id=?",Long.class,unbound,attachment);
        ok(http("POST",replyPath(no),adminToken,Map.of("body","Image received; continue in the ticket", "reason","Image ticket transcript verification",
                "expectedStatus",source.path("status").asText(),"expectedVersion",source.path("version").asLong(),
                "replyTargets",List.of(Map.of("conversationNo",no,"throughMessageId",imageMessage))),key()));
        var ready=ok(http("GET","/api/app/support/conversations/"+no,user,null,null)).path("conversation");
        var converted=ok(http("POST","/api/app/support/conversations/"+no+"/ticket",user,
                Map.of("category","account","title","Image question follow-up","expectedStatus",ready.path("status").asText(),
                        "expectedVersion",ready.path("version").asLong()),key()));
        String ticketNo=converted.path("ticket").path("ticket").path("ticketNo").asText();
        assertThat(ticketNo).isNotBlank();
        var ticket=ok(http("GET","/api/app/support/tickets/"+ticketNo,user,null,null));
        assertThat(ticket.toString()).contains("图片（在来源会话查看）");
        assertThat(ticket.path("ticket").path("sourceConversationNo").asText()).isEqualTo(no);
        assertThat(jdbc.queryForObject("SELECT source_conversation_no FROM nx_support_ticket WHERE ticket_no=?",String.class,ticketNo)).isEqualTo(no);
        var restricted=ok(http("GET","/api/admin/content/tickets/"+ticketNo,otherToken,null,null));
        assertThat(restricted.path("ticket").path("contentRestricted").asBoolean()).isTrue();
        assertThat(restricted.toString()).doesNotContain("图片（在来源会话查看）");
        samples.put("captionlessImageTicket","Actual App conversion preserves image marker and source conversation; unauthorized advisor receives R08 restricted projection.");
    }

    private String maintenancePath(){return "/api/admin/content/support-workbench/customers/"+customer+"/maintenance";}
    private String replyPath(String no){return "/api/admin/content/conversations/"+no+"/replies";}
    private Map<String,Object> reply(String no,String intent,String text,String clientId) throws Exception {
        var c=ok(http("GET","/api/admin/content/conversations/"+no,adminToken,null,null)).path("conversation");
        return Map.of("body",text,"expectedStatus",c.path("status").asText(),"expectedVersion",c.path("version").asLong(),
            "reason","S4 runtime controlled message","intent",intent,"clientMessageId",clientId,"expectedAssignmentId",mapper.current(customer).id());
    }
    private long scalar(String sql){return jdbc.queryForObject(sql,Long.class,customer);}
    private long count(String table){return scalar("SELECT COUNT(*) FROM "+table+" WHERE customer_id=?");}
    private String cycleStatus(){return jdbc.queryForObject("SELECT status FROM nx_support_maintenance_cycle WHERE customer_id=? ORDER BY id DESC LIMIT 1",String.class,customer);}
    private void rules(Integer d,Integer m,Integer w){
        as(boss);var current=mapper.rules();
        assertThat(bindings.updateRules(key(),new SupportRulesRequest(d,m,w,current.inheritanceMode(),current.maxInheritanceDepth(),current.version(),"S4 isolated rules fixture")).getCode()).isZero();
    }
    private void transfer(long id,long agent){var a=mapper.current(id);assertThat(bindings.transfer(key(),new SupportBindingRequest(agent,List.of(new SupportBindingRequest.Customer(id,a==null?null:a.id(),a==null?mapper.poolVersion(id):a.version())),"S4 isolated customer transfer")).getCode()).isZero();}
    private String key(){return "s4-"+UUID.randomUUID();}
    private long admin(String role,String seat){
        String name=run+"_"+UUID.randomUUID().toString().substring(0,8);
        String password=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(System.getenv("S3_FIXTURE_PASSWORD"));
        jdbc.update("INSERT INTO nx_admin(username,password_hash,nickname,super_admin,status) VALUES(?,?,?,?,1)",name,password,name,"SUPER_ADMIN".equals(role)?1:0);
        long id=jdbc.queryForObject("SELECT id FROM nx_admin WHERE username=?",Long.class,name);
        jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code=? AND is_deleted=0",id,role);
        jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,?,?,'support,advisor','',0,1,1,0)",id,seat,seat);return id;
    }
    private long customer(){
        return new TransactionTemplate(transactions).execute(status->{
            String ref=UUID.randomUUID().toString().replace("-","").substring(0,20);
            String phone="198"+String.format("%08d",Math.abs((long)ref.hashCode())%100000000);
            String password=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(System.getenv("S3_FIXTURE_PASSWORD"));
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1',?,?,?,'ACTIVE',0)",phone,password,run,ref);
            long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,ref);bindings.register(id,null);return id;
        });
    }
    private void as(long id){
        var auth=new UsernamePasswordAuthenticationToken(String.valueOf(id),null,List.of(new SimpleGrantedAuthority("service_m3_write"),new SimpleGrantedAuthority("service_m3_read")));
        auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private String token(long id){String name=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,id);return tokens.createToken(id,"ADMIN",name,List.of(),sessions.createSession(id,name));}
    private String userToken(long id) throws Exception {
        assertThat(onboarding.defer(id,new OnboardingCalibrationService.ActionRequest("s4-device-"+id,0,key())).getCode()).isZero();
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",id,session,session);
        String token=tokens.createUserToken(id,"s4-customer",List.of(),session,Duration.ofHours(8),UserAuthEnvironment.PRODUCTION);
        var terms=ok(http("GET","/api/legal/terms/current?locale=en&jurisdiction=GLOBAL",token,null,null));
        ok(http("POST","/api/legal/terms/acknowledgment",token,Map.of("locale","en","jurisdiction","GLOBAL","version",terms.path("version").asText(),"confirmed",true,"idempotencyKey",key(),"runId",""),null));return token;
    }
    private JsonNode login(long id) throws Exception {
        String phone=jdbc.queryForObject("SELECT phone FROM nx_user WHERE id=?",String.class,id);
        return ok(http("POST","/auth/users/login",null,Map.of("countryCode","+86","phone",phone,"password",System.getenv("S3_FIXTURE_PASSWORD")),null));
    }
    private JsonNode http(String method,String path,String token,Object body,String key) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+SupportIsolatedRuntime.port()+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json");
        if(token!=null)b.header("Authorization","Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return json.readTree(client.send(b.build(),HttpResponse.BodyHandlers.ofString()).body());
    }
    private JsonNode ok(JsonNode value){assertThat(value.path("code").asInt(-1)).as("HTTP result: %s",value.path("message").asText()).isZero();return value.path("data");}
    private void assertCode(JsonNode value,int code){assertThat(value.path("code").asInt()).as("HTTP result: %s",value.path("message").asText()).isEqualTo(code);}
    private byte[] image(String format) throws Exception {var image=new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,0xff8844);var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);return out.toByteArray();}
    private JsonNode upload(String token,byte[] bytes,String mime,String name,String uploadId,String key) throws Exception {
        return upload(token,bytes,mime,name,uploadId,key,false);
    }
    private JsonNode upload(String token,byte[] bytes,String mime,String name,String uploadId,String key,boolean app) throws Exception {
        String boundary="s4boundary"+UUID.randomUUID();var out=new ByteArrayOutputStream();
        var fields=new LinkedHashMap<String,String>();fields.put("clientUploadId",uploadId);
        if(!app){fields.put("customerId",String.valueOf(customer));fields.put("expectedAssignmentId",String.valueOf(mapper.current(customer).id()));}
        for(var field:fields.entrySet())
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes());
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes());
        out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+SupportIsolatedRuntime.port()+(app?"/api/app/support/attachments":"/api/admin/content/conversations/attachments"))).timeout(Duration.ofSeconds(20))
            .header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return json.readTree(client.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
    private HttpResponse<byte[]> download(String path,String token,String range) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+SupportIsolatedRuntime.port()+path)).timeout(Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);if(range!=null)b.header("Range",range);
        return client.send(b.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
}
