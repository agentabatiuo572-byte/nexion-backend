package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real private bytes and DB-grant HTTP checks on the leased isolated boundary. */
@EnabledIfEnvironmentVariable(named="CS_ENHANCE_AVATAR_READ_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportAdminAvatarReadRuntimeTest extends SupportBulkRuntimeFixture {
    private static final String METHOD="controlledAvatarReadScopesAndReplacement";
    private static final String AVATAR="/api/admin/content/support-agents/";
    private List<Map<String,Object>> originalSupportGrants=List.of();
    private final List<Long> forgedMessageIds=new ArrayList<>();

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }

    @BeforeEach void prepareAvatarFixture() {
        boundary();
        originalSupportGrants=jdbc.queryForList("SELECT rp.id,rp.is_deleted,p.permission_code FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id LEFT JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code='SUPPORT' AND r.status=1 AND r.is_deleted=0 ORDER BY rp.id");
        assertThat(originalSupportGrants).isNotEmpty();
        startFixture();
    }

    @AfterEach void restoreAvatarFixture() {
        var cleanup=new ArrayList<Runnable>();
        for(var grant:originalSupportGrants)
            cleanup.add(()->SharedMutationJournal.restorePermission(jdbc,run,"SupportAdminAvatarReadRuntimeTest",boss,"restoreAvatarFixture#originalGrant",((Number)grant.get("id")).longValue()));
        for(long message:forgedMessageIds) {
            cleanup.add(()->jdbc.update("DELETE FROM nx_support_human_message WHERE message_id=?",message));
            cleanup.add(()->jdbc.update("DELETE FROM nx_conversation_message WHERE id=?",message));
        }
        cleanup.add(this::restoreFixture);cleanup.add(permissions::evictAll);cleanup.add(SecurityContextHolder::clearContext);
        SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }

    @Test void controlledAvatarReadScopesAndReplacement() throws Exception {
        assertThat(System.getenv("WORKFLOW_RUN_ID")).isNotBlank();
        assertThat(System.getenv("WORKFLOW_SNAPSHOT_HASH")).isNotBlank();
        assertThat(System.getenv("CS_ENHANCE_EVIDENCE_DIR")).isNotBlank();
        long manager=admin("avatar_manager","SUPPORT","MANAGER");
        long general=admin("avatar_general","SUPPORT","GENERAL");
        long unrelated=admin("avatar_unrelated","SUPPORT","DEDICATED");
        long legacy=admin("avatar_legacy","SUPPORT","GENERAL");
        long forged=admin("avatar_forged","SUPPORT","GENERAL");
        long changedRole=admin("avatar_changed_role","SUPPORT","GENERAL");
        long withoutProfile=admin("avatar_without_profile","SUPPORT","GENERAL");
        long noAvatar=admin("avatar_missing","SUPPORT","GENERAL");
        long content=admin("avatar_content","CONTENT","GENERAL");
        for(long actor:admins)
            assertThat(jdbc.update("UPDATE nx_admin SET username=?,email=? WHERE id=?","avatar_"+actor,"avatar_"+actor+"@example.invalid",actor)).isEqualTo(1);
        long customer=objectCustomer(first),otherCustomer=objectCustomer(first);
        String superToken=token(boss),firstToken=token(first),secondToken=token(second),managerToken=token(manager),unrelatedToken=token(unrelated);
        var placeholder=http("GET","/api/admin/content/support-workbench/customers/"+customer+"/360",firstToken,null,null);
        assertThat(placeholder.path("code").asInt()).isZero();
        assertThat(placeholder.path("data").path("customer").path("advisorAvatar").isNull()).isTrue();
        assertThat(placeholder.path("data").path("customer").path("advisorAvatarRef").isNull()).isTrue();
        assertThat(placeholder.path("data").path("profile").path("service").path("status").asText()).isEqualTo("READY");
        assertThat(placeholder.path("data").path("profile").path("service").path("data").path("advisorAvatar").isNull()).isTrue();
        assertThat(placeholder.path("data").path("profile").path("service").path("data").path("advisorAvatarRef").isNull()).isTrue();
        int originalColor=0x22AA44,replacementColor=0xCC3366,secondColor=0x3355CC,otherColor=0xCCAA22;
        var original=attachAvatar(first,superToken,originalColor);
        var currentSecond=attachAvatar(second,superToken,secondColor);
        for(long target:List.of(general,unrelated,legacy,forged,changedRole,withoutProfile,content))
            attachAvatar(target,superToken,otherColor);

        // The real original write path establishes one genuinely verified historical author.
        String conversation=openConversation(firstToken,customer,"Verified original advisor message");
        String otherConversation=openConversation(firstToken,otherCustomer,"Unrelated conversation for inconsistent-key fixture");
        long actualMessage=jdbc.queryForObject("SELECT h.message_id FROM nx_support_human_message h JOIN nx_conversation_message m ON m.id=h.message_id WHERE h.customer_id=? AND h.actor_type='ADMIN' AND h.actor_id=? AND m.conversation_no=?",Long.class,customer,first,conversation);
        long conversationId=jdbc.queryForObject("SELECT id FROM nx_conversation WHERE conversation_no=?",Long.class,conversation);
        long otherConversationId=jdbc.queryForObject("SELECT id FROM nx_conversation WHERE conversation_no=?",Long.class,otherConversation);
        long legacyMessage=insertAgentMessage(conversationId,conversation,legacy,"Legacy author has no metadata");
        long forgedMessage=insertAgentMessage(otherConversationId,conversation,forged,"Metadata cannot repair contradictory conversation keys");
        forgedMessageIds.add(forgedMessage);
        jdbc.update("INSERT INTO nx_support_human_message(message_id,customer_id,assignment_id,actor_type,actor_id,client_message_id,kind,intent,committed_at,payload_hash) VALUES(?,?,?,'ADMIN',?,?,'TEXT','SERVICE',UTC_TIMESTAMP(6),?)",forgedMessage,customer,bindingMapper.current(customer).id(),forged,"avatar-forged-"+UUID.randomUUID(),"0".repeat(64));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE message_id=?",Long.class,legacyMessage)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message m JOIN nx_support_human_message h ON h.message_id=m.id JOIN nx_conversation c ON c.id=m.conversation_id AND c.conversation_no=m.conversation_no WHERE m.id=?",Long.class,forgedMessage)).isZero();
        long messagesBeforeReads=jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no IN (?,?)",Long.class,conversation,otherConversation);

        restrictSupportGrants(Set.of("service_m1_read","service_m3_read"));
        assertThat(permissions.getPermissionCodes(first)).containsExactlyInAnyOrder("service_m1_read","service_m3_read");
        assertThat(permissions.getPermissionCodes(unrelated)).containsExactlyInAnyOrder("service_m1_read","service_m3_read");
        // Every authorization assertion below is a real JWT request resolved through current DB grants.
        readImage(path(first,null),firstToken,originalColor);
        readImage(path(first,customer),firstToken,originalColor);
        denied(path(second,null),firstToken,404);
        denied(path(first,customer),unrelatedToken,404);
        denied(path(unrelated,customer),firstToken,404);
        denied(path(content,customer),firstToken,404);
        denied(path(legacy,customer),firstToken,404);
        denied(path(forged,customer),firstToken,404);
        var initialMessages=http("GET","/api/admin/content/conversations/"+conversation,firstToken,null,null);
        assertThat(initialMessages.path("code").asInt()).isZero();
        var unknown=message(initialMessages,legacyMessage);assertThat(unknown.path("authorConfidence").asText()).isEqualTo("UNKNOWN");assertThat(unknown.path("senderAvatar").isNull() || unknown.path("senderAvatar").isMissingNode()).isTrue();
        assertAvatar(message(initialMessages,actualMessage).path("senderAvatar"),original);

        // No image is a different failure from a real image outside the allowed target scope.
        denied(path(noAvatar,null),managerToken,404);
        denied(path(content,null),managerToken,404);
        jdbc.update("UPDATE nx_support_agent_profile SET is_deleted=1 WHERE admin_id=?",withoutProfile);
        denied(path(withoutProfile,null),managerToken,404);
        addLatestContentRole(changedRole);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=? AND rr.is_deleted=0 AND r.role_code='SUPPORT'",Long.class,changedRole)).isPositive();
        denied(path(changedRole,null),managerToken,404);
        jdbc.update("UPDATE nx_support_agent_profile SET enabled=0,busy=1,seat_type='GENERAL',service_types='support' WHERE admin_id=?",general);
        readImage(path(general,null),managerToken,otherColor);
        readImage(path(first,null),managerToken,originalColor);
        readImage(path(first,customer),managerToken,originalColor);
        var ownRoster=http("GET","/api/admin/content/support-agents",firstToken,null,null);assertThat(ownRoster.path("code").asInt()).isZero();assertThat(ownRoster.path("data").path("agents")).hasSize(1);assertThat(ownRoster.path("data").path("agents").get(0).path("adminId").asLong()).isEqualTo(first);
        var rosterRows=roster(managerToken);var generalRow=record(rosterRows,general,"adminId");assertThat(generalRow.path("enabled").asBoolean()).isFalse();assertThat(generalRow.path("busy").asBoolean()).isTrue();assertThat(generalRow.path("seatType").asText()).isEqualTo("GENERAL");assertThat(generalRow.path("avatarRef").asText()).isEqualTo(path(general,null));
        var missingRow=record(rosterRows,noAvatar,"adminId");assertThat(missingRow.path("avatarAssetId").isNull()).isTrue();assertThat(missingRow.path("avatarRef").isNull()).isTrue();

        restrictSupportGrants(Set.of("service_m1_read"));
        assertThat(permissions.getPermissionCodes(manager)).containsExactly("service_m1_read");
        readImage(path(first,null),managerToken,originalColor);
        readImage(path(first,customer),managerToken,originalColor);
        restrictSupportGrants(Set.of("service_m3_read"));
        assertThat(permissions.getPermissionCodes(first)).containsExactly("service_m3_read");
        denied(path(first,null),firstToken,403);
        readImage(path(first,customer),firstToken,originalColor);
        denied(path(first,null),managerToken,403);
        restrictSupportGrants(Set.of("service_m1_read","service_m3_read"));

        denied("/api/admin/platform/accounts/"+first+"/avatar",firstToken,403);
        assertThat(uploadAvatar(first,firstToken,png(otherColor)).path("code").asInt()).isEqualTo(403);
        var currentAccount=account(first,superToken);
        assertThat(http("PATCH","/api/admin/platform/accounts/"+first+"/profile",firstToken,profileEdit(currentAccount,original.path("avatarAssetId").asText()),key()).path("code").asInt()).isEqualTo(403);
        String user=userToken(customer);
        denied(path(first,customer),user,403);
        readImage("/api/app/support/advisor/avatar/"+first,user,originalColor);
        readImage("/api/admin/platform/accounts/"+first+"/avatar",superToken,originalColor);

        var replaced=attachAvatar(first,superToken,replacementColor);
        assertThat(replaced.path("avatarVersion").asLong()).isEqualTo(original.path("avatarVersion").asLong()+1);
        assertThat(replaced.path("avatarAssetId").asText()).isNotEqualTo(original.path("avatarAssetId").asText());
        var firstRow=record(roster(managerToken),first,"adminId");assertThat(firstRow.path("avatarAssetId")).isEqualTo(replaced.path("avatarAssetId"));assertThat(firstRow.path("avatarVersion")).isEqualTo(replaced.path("avatarVersion"));readImage(firstRow.path("avatarRef").asText(),firstToken,replacementColor);
        assertCustomerProjection(firstToken,customer,first,replaced,replacementColor);
        var replacementMessages=http("GET","/api/admin/content/conversations/"+conversation,firstToken,null,null);assertThat(replacementMessages.path("code").asInt()).isZero();var sender=message(replacementMessages,actualMessage);assertThat(sender.path("senderId").asLong()).isEqualTo(first);assertThat(sender.path("authorConfidence").asText()).isEqualTo("VERIFIED");assertAvatar(sender.path("senderAvatar"),replaced);
        readImage(path(sender.path("senderId").asLong(),customer),firstToken,replacementColor);
        readImage("/api/app/support/advisor/avatar/"+first,user,replacementColor);

        transfer(second,customer);SecurityContextHolder.clearContext();
        denied(path(first,customer),firstToken,404);
        denied(path(second,customer),firstToken,404);
        readImage(path(second,customer),secondToken,secondColor);
        readImage(path(first,customer),secondToken,replacementColor);
        denied(path(first,customer),unrelatedToken,404);
        assertCustomerProjection(secondToken,customer,second,currentSecond,secondColor);
        var transferredMessages=http("GET","/api/admin/content/conversations/"+conversation,secondToken,null,null);assertThat(transferredMessages.path("code").asInt()).isZero();var historical=message(transferredMessages,actualMessage);assertThat(historical.path("senderId").asLong()).isEqualTo(first);assertAvatar(historical.path("senderAvatar"),replaced);assertThat(historical.path("senderAvatar").path("assetId")).isNotEqualTo(currentSecond.path("avatarAssetId"));
        // Historical author qualification is the committed fact, not today's target roster eligibility.
        jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",first);addLatestContentRole(first);permissions.evictAll();
        readImage(path(first,customer),secondToken,replacementColor);
        denied(path(first,null),managerToken,404);
        readImage("/api/app/support/advisor/avatar/"+first,user,replacementColor);
        readImage("/api/app/support/advisor/avatar/"+second,user,secondColor);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no IN (?,?)",Long.class,conversation,otherConversation)).isEqualTo(messagesBeforeReads);
        assertThat(executions(customer)).isZero();assertThat(executions(otherCustomer)).isZero();
        writeAvatarEvidence();
    }

    private void restrictSupportGrants(Set<String> keep) {
        for(String required:keep)assertThat(originalSupportGrants.stream().anyMatch(row->required.equals(row.get("permission_code")))).as("Registered SUPPORT grant %s",required).isTrue();
        for(var grant:originalSupportGrants)
            SharedMutationJournal.sql(jdbc,run,"SupportAdminAvatarReadRuntimeTest",boss,"restrictSupportGrants#exactGrant","UPDATE nx_admin_role_permission SET is_deleted=? WHERE id=?",keep.contains(grant.get("permission_code"))?0:1,grant.get("id"));
        permissions.evictAll();SecurityContextHolder.clearContext();
    }

    private JsonNode attachAvatar(long target,String superToken,int color) throws Exception {
        var before=account(target,superToken);
        var identity=jdbc.queryForMap("SELECT username,nickname,email,status,super_admin,version FROM nx_admin WHERE id=?",target);
        var upload=uploadAvatar(boss,superToken,png(color));assertThat(upload.path("code").asInt()).as("Real avatar upload: %s",upload.path("message")).isZero();assertThat(upload.toString()).doesNotContain("objectKey","bucket");
        var changed=http("PATCH","/api/admin/platform/accounts/"+target+"/profile",superToken,profileEdit(before,upload.path("data").path("assetId").asText()),key());assertThat(changed.path("code").asInt()).as("Original full identity avatar CAS: %s",changed.path("message")).isZero();
        var after=jdbc.queryForMap("SELECT username,nickname,email,status,super_admin,version FROM nx_admin WHERE id=?",target);
        for(String field:List.of("username","nickname","email","status","super_admin"))assertThat(after.get(field)).as("Preserved account %s",field).isEqualTo(identity.get(field));
        assertThat(((Number)after.get("version")).longValue()).isEqualTo(((Number)identity.get("version")).longValue()+1);
        var account=account(target,superToken);assertThat(account.path("avatarAssetId")).isEqualTo(changed.path("data").path("avatarAssetId"));assertThat(account.path("avatarVersion")).isEqualTo(changed.path("data").path("avatarVersion"));
        readImage("/api/admin/platform/accounts/"+target+"/avatar",superToken,color);return account;
    }

    private Map<String,Object> profileEdit(JsonNode account,String asset) {
        var request=new LinkedHashMap<String,Object>();request.put("username",account.path("username").asText());request.put("displayName",account.path("name").asText());request.put("email",account.path("email").asText());request.put("expectedVersion",account.path("version").asText());request.put("avatarAssetId",asset);request.put("reason","Replace avatar while preserving original identity");request.put("operator",run);return request;
    }

    private JsonNode account(long target,String superToken) throws Exception {
        var response=http("GET","/api/admin/platform/accounts/overview",superToken,null,null);assertThat(response.path("code").asInt()).isZero();return record(response.path("data").path("operators"),target,"id");
    }

    private String openConversation(String advisor,long customer,String text) throws Exception {
        var request=new ConversationInitiateRequest("advisor",customer,null,null,text,"Real verified avatar author fixture",run,"TEXT",null,"SERVICE","avatar-message-"+UUID.randomUUID(),bindingMapper.current(customer).id());
        var response=http("POST","/api/admin/content/conversations",advisor,request,key());assertThat(response.path("code").asInt()).as("Original conversation send: %s",response.path("message")).isZero();
        String no=response.path("data").path("conversationNo").asText();assertThat(no).isNotBlank();return no;
    }

    private long insertAgentMessage(long conversationId,String no,long author,String text) {
        jdbc.update("INSERT INTO nx_conversation_message(conversation_id,conversation_no,sender_id,sender_type,sender_name,content,created_at) VALUES(?,?,?,'agent',?,?,NOW(6))",conversationId,no,author,run,text);
        return jdbc.queryForObject("SELECT id FROM nx_conversation_message WHERE conversation_no=? AND sender_id=? AND content=?",Long.class,no,author,text);
    }

    private void addLatestContentRole(long target) {
        assertThat(jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id,updated_at) SELECT ?,id,DATE_ADD(NOW(6),INTERVAL 1 SECOND) FROM nx_admin_role WHERE role_code='CONTENT' AND status=1 AND is_deleted=0 ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=DATE_ADD(NOW(6),INTERVAL 1 SECOND)",target)).isPositive();
        permissions.evict(target);
    }

    private JsonNode uploadAvatar(long uploader,String actor,byte[] image) throws Exception {
        String multipart="avatar-read-"+UUID.randomUUID(),client=key(),command=key();var body=new ByteArrayOutputStream();
        var intent=objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,uploader,null,null,client,command,null,storageProperties.getBucket(),false);
        body.write(("--"+multipart+"\r\nContent-Disposition: form-data; name=\"clientUploadId\"\r\n\r\n"+client+"\r\n--"+multipart+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(image);body.write(("\r\n--"+multipart+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+"/api/admin/platform/accounts/avatar-assets")).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+actor).header("Idempotency-Key",command).header("Content-Type","multipart/form-data; boundary="+multipart).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return json.readTree(sendObjectRequest(intent,request).body());
    }

    private static String path(long target,Long customer) {return AVATAR+target+"/avatar"+(customer==null?"":"?customerId="+customer);}
    private void denied(String path,String actor,int expected) throws Exception {assertThat(download(path,actor).statusCode()).as("Avatar read denied at %s",path).isEqualTo(expected);}
    private void readImage(String path,String actor,int expectedColor) throws Exception {
        var response=download(path,actor);assertThat(response.statusCode()).as("Actual avatar bytes at %s",path).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body()));assertThat(image).isNotNull();assertThat(image.getWidth()).isEqualTo(4);assertThat(image.getHeight()).isEqualTo(4);assertThat(image.getRGB(1,1)&0xFFFFFF).isEqualTo(expectedColor);
    }

    private JsonNode roster(String actor) throws Exception {
        var rows=json.createArrayNode();var ids=new java.util.HashSet<Long>();long total=Long.MAX_VALUE;
        for(long page=1;(page-1)*100<total;page++) {
            var response=http("GET","/api/admin/content/support-agents/page?pageNum="+page+"&pageSize=100",actor,null,null);
            assertThat(response.path("code").asInt()).isZero();var data=response.path("data");total=data.path("total").asLong();
            assertThat(data.path("pageNum").asLong()).isEqualTo(page);assertThat(data.path("pageSize").asLong()).isEqualTo(100);
            for(var row:data.path("records")) {assertThat(ids.add(row.path("adminId").asLong())).isTrue();rows.add(row);}
        }
        assertThat(rows.size()).isEqualTo(total);return rows;
    }
    private JsonNode record(JsonNode rows,long id,String field) {return java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(row->row.path(field).asLong()==id).findFirst().orElseThrow(()->new AssertionError("Expected actual "+field+"="+id));}
    private JsonNode message(JsonNode response,long id) {return record(response.path("data").path("messages"),id,"id");}
    private void assertAvatar(JsonNode avatar,JsonNode account) {assertThat(avatar.path("assetId")).isEqualTo(account.path("avatarAssetId"));assertThat(avatar.path("version")).isEqualTo(account.path("avatarVersion"));}

    private void assertCustomerProjection(String actor,long customer,long advisor,JsonNode account,int color) throws Exception {
        var list=http("GET","/api/admin/content/support-workbench/customers?pageNum=1&pageSize=100",actor,null,null);assertThat(list.path("code").asInt()).isZero();var row=record(list.path("data").path("customers").path("records"),customer,"customerId");assertAvatar(row.path("advisorAvatar"),account);assertThat(row.path("agentAdminId").asLong()).isEqualTo(advisor);assertThat(row.path("advisorAvatarRef").asText()).isEqualTo(path(advisor,customer));readImage(row.path("advisorAvatarRef").asText(),actor,color);
        var detail=http("GET","/api/admin/content/support-workbench/customers/"+customer+"/360",actor,null,null);assertThat(detail.path("code").asInt()).isZero();assertAvatar(detail.path("data").path("customer").path("advisorAvatar"),account);var service=detail.path("data").path("profile").path("service");assertThat(service.path("status").asText()).isEqualTo("READY");assertAvatar(service.path("data").path("advisorAvatar"),account);assertThat(service.path("data").path("advisorAvatarRef").asText()).isEqualTo(path(advisor,customer));readImage(service.path("data").path("advisorAvatarRef").asText(),actor,color);
        assertThat(row.toString()).doesNotContain("objectKey","bucket");assertThat(service.toString()).doesNotContain("objectKey","bucket");
    }

    private void writeAvatarEvidence() throws Exception {
        var checks=new LinkedHashMap<String,Object>();
        checks.put("avatar-service-read",check("SUPPORT JWT has only actual DB service_m1_read/service_m3_read grants. Self and current customer read real PNG; equally permitted unrelated advisor receives 404 before and after transfer. m3-only requires customer context; m1-only supervisor reads roster and customer through the same private asset."));
        checks.put("avatar-roster-scope",check("Ordinary M1 roster is self only; supervisor reads active SUPPORT roster including disabled/busy GENERAL profile. Other account, missing profile, missing image and newer primary CONTENT role are denied despite old SUPPORT relation and real attached images."));
        checks.put("avatar-history",check("Actual original HTTP message metadata and sender establish VERIFIED author. Legacy message without metadata and forged metadata with contradictory conversation ID/number cannot authorize images. History remains readable after author becomes inactive and changes current role."));
        checks.put("avatar-transfer",check("Formal transfer immediately returns 404 to prior advisor's customer-context requests. New advisor reads current advisor and actual historical first author; current customer projection changes while old message sender/avatar stays the original author."));
        checks.put("avatar-a1-boundary",check("Service-only JWT receives 403 on original A1 avatar GET, multipart upload and complete profile PATCH. Superadmin original upload/full identity CAS/read remains successful; all identity fields stay unchanged and version increments."));
        checks.put("avatar-version",check("Before upload, real customer/360 service advisorAvatar/ref are null; M1 missing-image metadata/ref stays null. Real PNG replacement increments authoritative avatar version and changes resource. HTTP M1 avatarRef, customer list/detail/360 advisorAvatarRef and VERIFIED message senderAvatar reload that exact version; decoded private bytes show replacement color with image/png, no-store and nosniff."));
        checks.put("avatar-subject",check("Real production USER JWT is denied on ADMIN avatar endpoint while original App current/history reads succeed. Reads create no messages or maintenance executions; private object key and bucket are absent from projections."));
        Path directory=Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"));Files.createDirectories(directory);
        var scene=new LinkedHashMap<String,Object>();scene.put("checkedAt",Instant.now().toString());scene.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));scene.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));scene.put("database",SupportRuntimeTarget.current().database());scene.put("port",SupportRuntimeTarget.current().httpPort());scene.put("pid",ProcessHandle.current().pid());scene.put("checks",checks);
        Files.writeString(directory.resolve("avatar-read-runtime.json"),json.writeValueAsString(scene));
    }
    private Map<String,Object> check(String evidence) {return Map.of("status","pass","suite",getClass().getSimpleName(),"method",METHOD,"testcase",METHOD,"evidence",evidence);}
}
