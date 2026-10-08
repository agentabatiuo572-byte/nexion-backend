package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.content.mapper.*;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.storage.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated fixture shared by two separate JVM suites; no worker authentication is synthesized. */
abstract class SupportBulkRuntimeFixture {
    static final String BASE="/api/admin/content/support-workbench/bulk";
    private static final Set<String> OBJECT_CONTEXTS=Set.of("SupportAdminAvatarA2RuntimeTest","SupportAdminAvatarReadRuntimeTest",
        "SupportAvatarCompensationRuntimeTest","SupportBulkRuntimeTest");
    @Autowired ObjectProvider<SupportObjectEvidenceLedger> objectEvidenceProvider;
    private String objectTestcase;
    private final Set<String> objectTestcases=new LinkedHashSet<>();
    @BeforeEach void identifyObjectTestcase(TestInfo info) {
        objectTestcase=info.getTestMethod().orElseThrow().getName();
    }
    SupportObjectEvidenceLedger objects() {
        return Objects.requireNonNull(objectEvidenceProvider.getIfAvailable(),"Object-producing business context must import its evidence ledger");
    }
    SupportObjectEvidenceLedger.Intent objectRequest(SupportObjectEvidenceLedger.Kind kind,long actor,Long customer,
            Long assignment,String client,String command,String exactKey,String expectedBucket,boolean missingBucket) {
        objectTestcases.add(Objects.requireNonNull(objectTestcase,"Actual JUnit testcase is required"));
        return objects().request(new SupportObjectEvidenceLedger.Request(getClass().getSimpleName(),objectTestcase,kind,
            "ADMIN",actor,customer,assignment,client,command,exactKey,expectedBucket,missingBucket));
    }
    void cleanupObjectEvidence() {
        if(!OBJECT_CONTEXTS.contains(getClass().getSimpleName()))return;
        var ledger=objects();
        SupportObjectEvidenceLedger.cleanupIndependently(objectTestcases.stream()
            .<Runnable>map(testcase->()->ledger.cleanup(getClass().getSimpleName(),testcase)).toArray(Runnable[]::new));
    }
    HttpResponse<String> sendObjectRequest(SupportObjectEvidenceLedger.Intent intent,HttpRequest request) throws Exception {
        try {
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            JsonNode body;
            try {body=json.readTree(response.body());}
            catch(Exception parseFailure) {
                try {objects().httpOutcome(intent,response.statusCode(),json.getNodeFactory().textNode(response.body()));}
                catch(Throwable evidenceFailure) {parseFailure.addSuppressed(evidenceFailure);}
                throw parseFailure;
            }
            objects().httpOutcome(intent,response.statusCode(),body);
            return response;
        } catch(Exception|Error failure) {
            try {objects().requestFailure(intent,failure);} catch(Throwable evidenceFailure) {failure.addSuppressed(evidenceFailure);}
            throw failure;
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate actorRedis;
    private SupportFixtureActors actorEvidence;
    SupportFixtureActors fixtureActors() {
        if (actorEvidence == null) actorEvidence = new SupportFixtureActors(jdbc, actorRedis, json, transactions, run, getClass().getSimpleName());
        return actorEvidence;
    }
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    @Autowired SupportBindingService bindings;
    @Autowired SupportBindingMapper bindingMapper;
    @Autowired SupportBulkMapper bulkMapper;
    @Autowired SupportBulkService bulk;
    @Autowired SupportActivityService activity;
    @Autowired SupportMaintenanceService maintenance;
    @Autowired SupportAttachmentService attachments;
    @Autowired OpsConversationService conversations;
    @Autowired JwtTokenProvider tokens;
    @Autowired AdminSessionRegistry sessions;
    @Autowired AdminPermissionCache permissions;
    @Autowired ObjectStorageService storage;
    @Autowired StorageProperties storageProperties;
    @Autowired ffdd.opsconsole.onboarding.application.OnboardingCalibrationService onboarding;
    final String run="bulk_"+UUID.randomUUID().toString().substring(0,8);
    final List<Long> admins=new ArrayList<>();
    final Set<Long> retainedAdmins=new HashSet<>();
    final Map<String,Object> proofs=new LinkedHashMap<>();
    private final Map<Long,String> customerTokens=new HashMap<>();
    private SupportOriginalProfiles originalProfiles;
    SupportRules oldRules;
    long boss,first,second;

    void boundary() {
        fixtureActors().assertBusinessEntry();
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(SupportRuntimeTarget.current().database());
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(SupportRuntimeTarget.current().databasePort());
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
        assertThat(System.getenv("CS_ENHANCE_BULK_ENABLED")).isEqualTo("true");
    }
    void startFixture() {
        boundary();oldRules=bindingMapper.rules();
        originalProfiles=SupportOriginalProfiles.suspend(jdbc,transactions);
        boss=admin("boss","SUPER_ADMIN","MANAGER");first=admin("first","SUPPORT","DEDICATED");second=admin("second","SUPPORT","DEDICATED");
        as(boss);var rules=bindingMapper.rules();
        assertThat(bindings.updateRules(key(),new SupportRulesRequest(7,3,7,"UNLIMITED",null,rules.version(),"Bulk isolated fixture rules","SUPERVISOR")).getCode()).isZero();
    }
    void restoreFixture() {
        var cleanup=new ArrayList<Runnable>();
        cleanup.add(this::cleanupObjectEvidence);
        cleanup.add(()->{if(actorEvidence!=null)actorEvidence.cleanupAll(retainedAdmins);});
        cleanup.add(()->{if(oldRules!=null && boss>0) SharedMutationJournal.cleanupSql(jdbc,run,"SupportBulkRuntimeFixture",boss,"SupportBulkRuntimeFixture#rules-sql-1","UPDATE nx_support_rules SET dormant_days=?,maintenance_days=?,activity_window_days=?,inheritance_mode=?,max_inheritance_depth=?,unbound_assignment_mode=?,mode_effective_at=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE id=1",
            oldRules.dormantDays(),oldRules.maintenanceDays(),oldRules.activityWindowDays(),oldRules.inheritanceMode(),oldRules.maxInheritanceDepth(),oldRules.unboundAssignmentMode(),oldRules.modeEffectiveAt(),boss);});
        cleanup.add(()->{if(originalProfiles!=null) originalProfiles.restoreAndVerify();});
        cleanup.add(SecurityContextHolder::clearContext);
        SupportObjectEvidenceLedger.cleanupIndependently(cleanup.toArray(Runnable[]::new));
    }
    long admin(String label,String role,String seat) {
        String username=run+"_"+label+"_"+admins.size();
        long id=fixtureActors().createSql(username,"fixture-disabled-password",label,role,seat);
        admins.add(id);
        return id;
    }
    long customer(long actor) {
        long customer=new TransactionTemplate(transactions).execute(status->{
            String referral=UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();
            String phone="198"+String.format("%08d",Math.abs((long)referral.hashCode())%100000000);
            jdbc.update("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1','fixture-disabled-password',?,?,'ACTIVE',0)",phone,run,referral);
            long id=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);bindings.register(id,null);return id;
        });
        transfer(actor,customer);return customer;
    }
    long objectCustomer(long actor) {
        String referral=UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase();
        String phone="198"+String.format("%08d",Math.abs((long)referral.hashCode())%100000000);
        long customer=objects().createCustomer(getClass().getSimpleName(),Objects.requireNonNull(objectTestcase),referral,()->{
            var generated=new org.springframework.jdbc.support.GeneratedKeyHolder();
            int affected=jdbc.update(connection->{
                var statement=connection.prepareStatement("INSERT INTO nx_user(country_code,phone,client_ip,password_hash,nickname,referral_code,status,sandbox) VALUES('+86',?,'127.0.0.1','fixture-disabled-password',?,?,'ACTIVE',0)",java.sql.Statement.RETURN_GENERATED_KEYS);
                statement.setString(1,phone);statement.setString(2,run);statement.setString(3,referral);return statement;
            },generated);
            long id=Objects.requireNonNull(generated.getKey(),"Exact INSERT generated customer ID").longValue();
            long lookup=jdbc.queryForObject("SELECT id FROM nx_user WHERE referral_code=?",Long.class,referral);
            assertThat(affected).isEqualTo(1);assertThat(lookup).isEqualTo(id);
            bindings.register(id,null);
            return new SupportObjectEvidenceLedger.CustomerInsert(id,affected,lookup);
        });
        transfer(actor,customer);return customer;
    }
    void transfer(long actor,long customer) {
        as(boss);var old=bindingMapper.current(customer);
        assertThat(bindings.transfer(key(),new SupportBindingRequest(actor,List.of(new SupportBindingRequest.Customer(customer,old==null?null:old.id(),old==null?bindingMapper.poolVersion(customer):old.version())),"Bulk isolated formal transfer")).getCode()).isZero();
    }
    void as(long actor) {
        var authorities=List.of("platform_a1_write","platform_a1_read","service_m1_write","service_m1_read","service_m3_read","service_m3_write").stream().map(SimpleGrantedAuthority::new).toList();
        var auth=new UsernamePasswordAuthenticationToken(String.valueOf(actor),null,authorities);
        auth.setDetails(Map.of("subjectType","ADMIN","username",run));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    String token(long actor) {String username=jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?",String.class,actor);return tokens.createToken(actor,"ADMIN",username,List.of(),sessions.createSession(actor,username));}
    String userToken(long customer) throws Exception {
        String existing=customerTokens.get(customer);if(existing!=null)return existing;
        assertThat(onboarding.defer(customer,new ffdd.opsconsole.onboarding.application.OnboardingCalibrationService.ActionRequest("bulk-device-"+customer,0,key())).getCode()).isZero();
        String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO nx_user_session(user_id,refresh_token_id,session_chain_id,expires_at,last_active_at) VALUES(?,?,?,DATE_ADD(NOW(),INTERVAL 1 DAY),NOW())",customer,session,session);
        String token=tokens.createUserToken(customer,"bulk-customer",List.of(),session,Duration.ofHours(8),UserAuthEnvironment.PRODUCTION);
        var terms=http("GET","/api/legal/terms/current?locale=en&jurisdiction=GLOBAL",token,null,null);assertThat(terms.path("code").asInt()).isZero();
        assertThat(http("POST","/api/legal/terms/acknowledgment",token,Map.of("locale","en","jurisdiction","GLOBAL","version",terms.path("data").path("version").asText(),"confirmed",true,"idempotencyKey",key(),"runId",""),null).path("code").asInt()).isZero();customerTokens.put(customer,token);return token;
    }
    String key() {return "bulk-test-"+UUID.randomUUID();}
    JsonNode http(String method,String path,String token,Object body,String command) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);if(command!=null)request.header("Idempotency-Key",command);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());return json.readTree(response.body());
    }
    JsonNode preview(long actor,List<Long> ids,List<Long> exclusions,String mode,SupportBulkRequest.Filters filters) throws Exception {
        var response=http("POST",BASE+"/preview",token(actor),new SupportBulkRequest.Preview(filters,ids,exclusions,mode),null);
        assertThat(response.path("code").asInt()).as("preview result %s",response.path("message")).isZero();return response.path("data");
    }
    String batch(long actor,List<Long> customers,String intent,String kind,String body,String sku,SupportLinkTarget link,String asset) throws Exception {
        var preview=preview(actor,customers,List.of(),"EXPLICIT",null);
        assertThat(preview.path("count").asInt()).isEqualTo(customers.size());
        String id=preview.path("selectionId").asText();
        var response=http("POST",BASE,token(actor),new SupportBulkRequest.Create(id,intent,kind,body,sku,link,asset,"Bulk real delivery test"),key());
        assertThat(response.path("code").asInt()).as("create result %s",response.path("message")).isZero();return response.path("data").path("batchId").asText();
    }
    Map<String,Object> row(String batch,long customer) {return jdbc.queryForMap("SELECT * FROM nx_support_bulk_recipient WHERE batch_id=? AND customer_id=?",batch,customer);}
    long messageCount(long customer) {return jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_human_message WHERE customer_id=? AND actor_type='ADMIN'",Long.class,customer);}
    long executions(long customer) {return jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_maintenance_execution WHERE customer_id=?",Long.class,customer);}
    void send(String batch,long customer) {SecurityContextHolder.clearContext();bulk.processRecipient(batch,customer);}
    void counts(String batch,long sent,long failed,long skipped,long cancelled,long pending) {
        var actual=jdbc.queryForMap("SELECT COUNT(*) total,SUM(state='SENT') sent,SUM(state='FAILED') failed,SUM(state='SKIPPED') skipped,SUM(state='CANCELLED') cancelled,SUM(state='PENDING') pending FROM nx_support_bulk_recipient WHERE batch_id=?",batch);
        for(var expected:Map.of("sent",sent,"failed",failed,"skipped",skipped,"cancelled",cancelled,"pending",pending).entrySet()) assertThat(((Number)actual.get(expected.getKey())).longValue()).as(expected.getKey()).isEqualTo(expected.getValue());
        assertThat(((Number)actual.get("total")).longValue()).isEqualTo(sent+failed+skipped+cancelled+pending);
        assertThat(jdbc.queryForObject("SELECT frozen_count FROM nx_support_bulk_job WHERE id=?",Long.class,batch)).isEqualTo(sent+failed+skipped+cancelled+pending);
    }
    void cancel(long actor,String batch) {
        as(actor);long version=jdbc.queryForObject("SELECT version FROM nx_support_bulk_job WHERE id=?",Long.class,batch);
        assertThat(bulk.cancel(batch,key(),new SupportBulkRequest.Mutation(version,"Cancel remaining actual recipients")).getCode()).isZero();
    }
    byte[] png(int color) throws Exception {var image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,color);var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();}
    JsonNode upload(long actor,byte[] bytes,String upload,String command) throws Exception {
        var intent=objectRequest(SupportObjectEvidenceLedger.Kind.BULK_ASSET,actor,null,null,upload,command,null,storageProperties.getBucket(),false);
        return upload(actor,bytes,upload,command,intent);
    }
    JsonNode upload(long actor,byte[] bytes,String upload,String command,SupportObjectEvidenceLedger.Intent intent) throws Exception {
        String boundary="bulk"+UUID.randomUUID();var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"clientUploadId\"\r\n\r\n"+upload+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bulk.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+BASE+"/attachments")).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token(actor)).header("Idempotency-Key",command).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return json.readTree(sendObjectRequest(intent,request).body());
    }
    HttpResponse<byte[]> download(String path,String token) throws Exception {return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(SupportRuntimeTarget.current().httpBase()+path)).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    void proof(String id,String method,String evidence) {proofs.put("bulk-"+id,Map.of("status","pass","testcase",method,"suite",getClass().getSimpleName(),"evidence",evidence));}
    void writeProof(String filename) throws Exception {
        Path directory=Path.of(Objects.requireNonNull(System.getenv("CS_ENHANCE_EVIDENCE_DIR")));Files.createDirectories(directory);
        Files.writeString(directory.resolve(filename),json.writeValueAsString(Map.of("run",run,"checkedAt",Instant.now().toString(),"database",SupportRuntimeTarget.current().database(),"port",SupportRuntimeTarget.current().httpPort(),"checks",proofs,"workflowRunId",Objects.requireNonNull(System.getenv("WORKFLOW_RUN_ID")),"snapshotHash",Objects.requireNonNull(System.getenv("WORKFLOW_SNAPSHOT_HASH")))));
    }
    static class EventCapture {
        final List<ConversationMessageEvent> events=new CopyOnWriteArrayList<>();
        volatile java.util.function.Consumer<ConversationMessageEvent> hook;
        @org.springframework.context.event.EventListener public void event(ConversationMessageEvent event) {events.add(event);var current=hook;if(current!=null)current.accept(event);}
    }
}
